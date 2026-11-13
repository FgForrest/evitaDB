/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.cache;

import com.github.benmanes.caffeine.cache.Ticker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.*;
import io.evitadb.core.query.algebra.base.*;
import io.evitadb.core.query.algebra.identity.*;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.*;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises binding, execution gates, publication and sampled recomputation with real contexts and bitmaps.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula result cache supervisor")
@Tag(ENGINE)
@Tag(CACHE)
class FormulaCacheSupervisorTest implements EvitaTestSupport {
	/** Isolated storage paths. */
	private TestPaths paths;
	/** Engine providing pinned schemas and real sessions. */
	private Evita evita;
	/** Scheduler owned by the fixture. */
	private Scheduler scheduler;
	/** Supervisor under observation. */
	private FormulaCacheSupervisor cache;
	/** Controllable monotonic clock. */
	private final AtomicLong clock = new AtomicLong();
	/** Read-only session on an alive catalog. */
	private EvitaSessionContract session;
	/** Pinned catalog. */
	private Catalog catalog;
	/** Pinned collection. */
	private EntityCollection collection;

	/** Builds the real catalog and a cache with deterministic maintenance. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FormulaCacheSupervisorTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog("cacheTest").updateViaNewSession(this.evita);
		this.evita.updateCatalog("cacheTest", s -> {
			s.defineEntitySchema("product").withoutGeneratedPrimaryKey().updateVia(s);
			s.upsertEntity(s.createNewEntity("product", 1));
			s.upsertEntity(s.createNewEntity("product", 2));
			s.goLiveAndClose();
		});
		this.session = FormulaCacheTestSessions.readOnly(this.evita, "cacheTest");
		this.catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException("cacheTest");
		this.collection = this.catalog.getCollectionForEntityInternal("product").orElseThrow();
		this.scheduler = new Scheduler(new ScheduledThreadPoolExecutor(1, runnable -> {
			final Thread thread = new Thread(runnable, "cache-test-maintenance");
			thread.setDaemon(true);
			return thread;
		}));
		this.cache = new FormulaCacheSupervisor(options(0, 0), this.scheduler, this.clock::get);
	}

	/** Closes caches before their scheduler and engine and removes owned directories. */
	@AfterEach
	void tearDown() {
		if (this.cache != null) this.cache.close();
		if (this.scheduler != null) this.scheduler.shutdownNow();
		if (this.session != null) this.session.close();
		if (this.evita != null) this.evita.close();
		if (this.paths != null) cleanupTestPaths(this.paths);
	}

	/** Returns a large enough budget for all fixture entries and no cost floor. */
	@Nonnull
	private static CacheOptions options(int sampling, int expiry) {
		return CacheOptions.builder().enabled(true).cacheSizeInBytes(100_000_000L)
			.minimalComplexityThreshold(0).verifyOneHitIn(sampling)
			.dropEntriesAfterSecondsOfInactivity(expiry).build();
	}

	/** Creates an independent real planning root with no supplied index dependencies. */
	@Nonnull
	private QueryPlanningContext context(@Nullable QueryPlanningContext parent) {
		return new QueryPlanningContext(parent, this.catalog, this.collection, this.session, request(), null,
			Map.of(), Map.of(), this.cache);
	}

	/** Creates an entity-scoped request. */
	@Nonnull
	private static EvitaRequest request() {
		return new EvitaRequest(Query.query(collection("product"), require(debug(
			io.evitadb.api.query.require.DebugMode.PREFER_INDEX_SCAN))), OffsetDateTime.now(), EntityReference.class, null);
	}

	/** Binds and initializes one tree under an ordinary execution context. */
	private void prepare(@Nonnull Formula formula, @Nonnull QueryPlanningContext context) {
		context.analyse(formula);
		formula.initialize(context.createExecutionContext());
	}

	/** Distinguishes a hit from repeated computation and from recursive cost accounting. */
	@Test
	@DisplayName("Restores the stored bitmap with zero paid cost and without computing children")
	void shouldRestoreMemoAndCost() {
		final QueryPlanningContext context = context(null);
		final InputBlindCachedFormula child = new InputBlindCachedFormula(1, 1);
		final Formula first = new OrFormula(child, new ConstantFormula(new BaseBitmap(2)));
		prepare(first, context);
		final Bitmap result = first.compute();
		assertTrue(this.cache.getStatistics().admissions() > 0);
		final InputBlindCachedFormula nextChild = new InputBlindCachedFormula(1, 1);
		final Formula second = new OrFormula(nextChild, new ConstantFormula(new BaseBitmap(2)));
		prepare(second, context);
		assertNull(second.getMemoizedResult());
		assertEquals(0, nextChild.computations);
		assertSame(result, second.compute());
		assertEquals(0L, second.getCost());
		assertEquals(0L, second.getMemoizedCost());
		assertEquals(0, nextChild.computations);
		assertEquals(1, this.cache.getStatistics().hits());
	}

	/** Retains the original key but removes all eligibility to read or publish after clearing memory. */
	@Test
	@DisplayName("Unbinds on clearMemory and starts clones unbound")
	void shouldUnbindOnClearAndClone() {
		final QueryPlanningContext context = context(null);
		final OrFormula formula = new OrFormula(new ConstantFormula(new BaseBitmap(1)),
			new ConstantFormula(new BaseBitmap(2)));
		prepare(formula, context);
		formula.compute();
		final FormulaKey key = formula.getKey();
		final Formula clone = formula.getCloneWithInnerFormulas(formula.getInnerFormulas());
		clone.initialize(context.createExecutionContext());
		clone.compute();
		formula.clearMemory();
		assertSame(key, formula.getKey());
		formula.compute();
		assertEquals(0, this.cache.getStatistics().hits());
		assertEquals(1, this.cache.getStatistics().admissions());
	}

	/** Excludes both eager planning and prefetch, including a flagged planning context. */
	@Test
	@DisplayName("Bypasses planning and prefetch while ordinary execution can hit")
	void shouldBypassPlanningAndPrefetch() {
		final QueryPlanningContext context = context(null);
		assertFalse(context.getInternalExecutionContext().isFormulaCacheUsable());
		assertFalse(context.createExecutionContext(true, null).isFormulaCacheUsable());
		assertTrue(context.createExecutionContext().isFormulaCacheUsable());
		final OrFormula warmed = new OrFormula(new BaseBitmap(1), new BaseBitmap(2));
		prepare(warmed, context);
		warmed.compute();
		final FormulaCacheSupervisor.Statistics before = this.cache.getStatistics();
		final QueryPlanningContext flagged = new QueryPlanningContext(this.catalog, this.collection, this.session,
			new EvitaRequest(Query.query(collection("product"), require(debug(
				io.evitadb.api.query.require.DebugMode.VERIFY_POSSIBLE_CACHING_TREES))),
				OffsetDateTime.now(), EntityReference.class, null), Map.of(), Map.of(), this.cache);
		for (final QueryExecutionContext execution : new QueryExecutionContext[]{
			context.getInternalExecutionContext(), context.createExecutionContext(true, null),
			flagged.getInternalExecutionContext()
		}) {
			final OrFormula formula = new OrFormula(new BaseBitmap(1), new BaseBitmap(2));
			context.analyse(formula);
			formula.initialize(execution);
			formula.compute();
		}
		assertEquals(before.hits(), this.cache.getStatistics().hits());
		assertEquals(before.misses(), this.cache.getStatistics().misses());
		assertEquals(before.admissions(), this.cache.getStatistics().admissions());
	}

	/** No binding, key construction or publication occurs when a root has a writing session or no entity type. */
	@Test
	@DisplayName("Closes the gate for writing sessions and catalog-wide roots")
	void shouldCloseGate() {
		try (final io.evitadb.core.session.EvitaSession writing = FormulaCacheTestSessions.readWrite(
			this.evita, "cacheTest")) {
			assertSame(NoCacheSupervisor.INSTANCE, this.cache.resolveForRoot(writing, "product"));
			writing.execute(internal -> {
				final QueryPlanningContext origin = new QueryPlanningContext(this.catalog, this.collection, internal,
					request(), Map.of(), Map.of(), this.cache);
				assertFalse(origin.createFetchContext(this.collection, request(), Map.of(), Map.of(), this.cache)
					.isFormulaCacheEnabled());
			});
		}
		this.evita.defineCatalog("warmingCache").updateViaNewSession(this.evita);
		try (final EvitaSessionContract warming = FormulaCacheTestSessions.readOnly(this.evita, "warmingCache")) {
			assertSame(NoCacheSupervisor.INSTANCE, this.cache.resolveForRoot(warming, "product"));
		}
		assertSame(NoCacheSupervisor.INSTANCE, this.cache.resolveForRoot(this.session, null));
		assertSame(NoCacheSupervisor.INSTANCE, this.cache.resolveForRoot(null, "product"));
		assertSame(this.cache, this.cache.resolveForRoot(this.session, "product"));
		final QueryPlanningContext child = context(context(null));
		assertTrue(child.createExecutionContext().isFormulaCacheUsable());
	}

	/** Failed computations never reach publication. */
	@Test
	@DisplayName("Propagates computation failures without admitting a tuple")
	void shouldNotPublishFailures() {
		final InputBlindCachedFormula formula = new InputBlindCachedFormula(2, 1);
		formula.duringCompute = () -> { throw new IllegalStateException("failed computation"); };
		prepare(formula, context(null));
		assertThrows(IllegalStateException.class, formula::compute);
		assertEquals(0, this.cache.getStatistics().admissions());
		assertNull(formula.getMemoizedResult());
		final InputBlindCachedFormula sizingFailure = new InputBlindCachedFormula(46, 1);
		sizingFailure.duringMeasure = () -> { throw new IllegalStateException("failed sizing"); };
		prepare(sizingFailure, context(null));
		assertThrows(IllegalStateException.class, sizingFailure::compute);
		assertEquals(0, this.cache.getStatistics().admissions());
	}

	/** The absent binding is the only additional compute-path check on disabled roots. */
	@Test
	@DisplayName("Builds no computation key on a disabled path")
	void shouldBuildNoKeyWhenUnbound() {
		final InputBlindCachedFormula formula = new InputBlindCachedFormula(3, 1);
		final QueryPlanningContext disabled = new QueryPlanningContext(this.catalog, this.collection, this.session,
			request(), Map.of(), Map.of(), NoCacheSupervisor.INSTANCE);
		final CountingExecutionContext execution = new CountingExecutionContext(disabled);
		disabled.analyse(formula);
		formula.initialize(execution);
		formula.compute();
		assertEquals(0, formula.identities);
		assertEquals(0, execution.usabilityReads);
	}

	/** Uses distinct instances rather than exponentially many DAG paths. */
	@Test
	@DisplayName("Walks a doubling DAG in linear work")
	void shouldWalkDagOnce() {
		final InputBlindCachedFormula[] nodes = new InputBlindCachedFormula[41];
		nodes[0] = new InputBlindCachedFormula(0, 1);
		for (int i = 1; i < nodes.length; i++) {
			nodes[i] = new InputBlindCachedFormula(i, 1, nodes[i - 1], nodes[i - 1]);
		}
		assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> context(null).analyse(nodes[40]));
		for (final InputBlindCachedFormula node : nodes) assertEquals(1, node.childReads);
	}

	/** A sampled hit keeps its freshly computed tuple and normal node cost. */
	@Test
	@DisplayName("Recomputes equal sampled hits and keeps the recomputation")
	void shouldKeepVerifiedTuple() {
		this.cache.close();
		this.cache = new FormulaCacheSupervisor(options(1, 0), this.scheduler, Ticker.systemTicker());
		final QueryPlanningContext context = context(null);
		final OrFormula first = new OrFormula(new BaseBitmap(1), new BaseBitmap(2));
		prepare(first, context);
		final Bitmap stored = first.compute();
		final OrFormula second = new OrFormula(new BaseBitmap(1), new BaseBitmap(2));
		prepare(second, context);
		final Bitmap recomputed = second.compute();
		assertNotSame(stored, recomputed);
		assertSame(recomputed, second.compute());
		assertNull(second.getMemoizedCost());
		assertTrue(second.getCost() > 0);
		assertEquals(second.getCost(), second.getMemoizedCost());
		assertTrue(this.cache.isActive());
		assertEquals(1, this.cache.getStatistics().verifiedHits());
	}

	/** Omitting an actual input is observable only when a hit is independently recomputed. */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	@DisplayName("Fails the detecting query for corrupt tuples at the top, middle and frontier")
	void shouldFailOnConfirmedMismatch(int position) {
		this.cache.close();
		this.cache = new FormulaCacheSupervisor(options(1, 0), this.scheduler, this.clock::get);
		final QueryPlanningContext context = context(null);
		final Formula first = corruptTree(new InputBlindCachedFormula(5, 1), position);
		prepare(first, context);
		first.compute();
		final Formula second = corruptTree(new InputBlindCachedFormula(5, 2), position);
		prepare(second, context);
		assertThrows(GenericEvitaInternalError.class, second::compute);
		assertFalse(this.cache.isActive());
		assertEquals(0, this.cache.getStatistics().entries());
		assertEquals(1, this.cache.getStatistics().mismatches());
		assertNotNull(this.cache.getStatistics().witness());
		assertTrue(context.createExecutionContext().isFormulaCacheUsable());
		assertFalse(context(null).isFormulaCacheEnabled());
	}

	/** Provides the explicit counterfactual for the corruption detector. */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	@DisplayName("Serves defective tuples at the top, middle and frontier when verification is disabled")
	void shouldExposeCorruptionWithoutSampling(int position) {
		final QueryPlanningContext context = context(null);
		final Formula first = corruptTree(new InputBlindCachedFormula(6, 1), position);
		prepare(first, context);
		first.compute();
		final Formula second = corruptTree(new InputBlindCachedFormula(6, 2), position);
		prepare(second, context);
		assertTrue(second.compute().contains(1));
		assertFalse(second.compute().contains(2));
		assertTrue(this.cache.isActive());
	}

	/** Places the eligible corrupt tuple at three different depths beneath production formula containers. */
	@Nonnull
	private static Formula corruptTree(@Nonnull Formula leaf, int position) {
		return switch (position) {
			case 0 -> leaf;
			case 1 -> new io.evitadb.core.query.algebra.facet.UserFilterFormula(
				new OrFormula(leaf, new ConstantFormula(new BaseBitmap(100))));
			case 2 -> new io.evitadb.core.query.algebra.facet.UserFilterFormula(
				new io.evitadb.core.query.algebra.facet.UserFilterFormula(leaf),
				new ConstantFormula(new BaseBitmap(1, 2, 100)));
			default -> throw new IllegalArgumentException("Unknown corrupt tuple position.");
		};
	}

	/** Suspension reaches contexts captured before the sample and independent roots created during it. */
	@Test
	@DisplayName("Suspends bound descendants across captured contexts and independent fetch roots")
	void shouldSuspendAllReachedContexts() {
		this.cache.close();
		this.cache = new FormulaCacheSupervisor(options(1, 0), this.scheduler, this.clock::get);
		final QueryPlanningContext root = context(null);
		final QueryPlanningContext captured = context(root);
		final QueryExecutionContext capturedExecution = captured.createExecutionContext();
		final QueryExecutionContext previousFetch = root.createFetchContext(
			this.collection, request(), Map.of(), Map.of(), this.cache).createExecutionContext();
		final InputBlindCachedFormula first = new InputBlindCachedFormula(7, 1);
		prepare(first, root);
		first.compute();
		final InputBlindCachedFormula second = new InputBlindCachedFormula(7, 1);
		second.duringCompute = () -> {
			final QueryPlanningContext fetch = root.createFetchContext(
				this.collection, request(), Map.of(), Map.of(), this.cache);
			assertFalse(fetch.createExecutionContext().isFormulaCacheUsable());
			assertFalse(capturedExecution.isFormulaCacheUsable());
			assertFalse(previousFetch.isFormulaCacheUsable());
			for (final QueryExecutionContext execution : new QueryExecutionContext[]{
				capturedExecution, previousFetch, fetch.createExecutionContext()
			}) {
				final OrFormula descendant = new OrFormula(new BaseBitmap(1), new BaseBitmap(2));
				root.analyse(descendant);
				descendant.initialize(execution);
				descendant.compute();
			}
		};
		prepare(second, root);
		second.compute();
		assertEquals(1, second.computations);
		assertEquals(1, this.cache.getStatistics().hits());
		assertEquals(1, this.cache.getStatistics().misses());
		assertEquals(1, this.cache.getStatistics().admissions());
		assertTrue(captured.createExecutionContext().isFormulaCacheUsable());
	}

	/** Idle maintenance and close release entries without relying on further query traffic. */
	@Test
	@DisplayName("Expires idle tuples and empties the cache on close")
	void shouldExpireAndClose() {
		this.cache.close();
		this.cache = new FormulaCacheSupervisor(options(0, 1), this.scheduler, this.clock::get);
		final OrFormula formula = new OrFormula(new BaseBitmap(1), new BaseBitmap(2));
		prepare(formula, context(null));
		formula.compute();
		assertEquals(1, this.cache.getStatistics().entries());
		this.clock.addAndGet(2_000_000_000L);
		this.cache.performMaintenance();
		assertEquals(0, this.cache.getStatistics().entries());
		final OrFormula remaining = new OrFormula(new BaseBitmap(3), new BaseBitmap(4));
		prepare(remaining, context(null));
		remaining.compute();
		assertEquals(1, this.cache.getStatistics().entries());
		this.cache.close();
		assertEquals(0, this.cache.getStatistics().entries());
		assertFalse(this.cache.isActive());
		assertFalse(context(null).isFormulaCacheEnabled());
	}

	/** Exercises injection into every concrete candidate, including all four owners of price side outputs. */
	@ParameterizedTest
	@MethodSource("io.evitadb.core.cache.CachedResultEquivalenceTest#formulaSuppliers")
	@DisplayName("Restores complete tuples for every cacheable formula class")
	void shouldRestoreEveryCandidate(@Nonnull Supplier<Formula> factory) {
		final QueryPlanningContext context = context(null);
		final Formula first = factory.get();
		final Formula second = factory.get();
		prepare(first, context);
		first.compute();
		prepare(second, context);
		assertSame(first.getMemoizedResult(), second.compute());
		assertEquals(0L, second.getCost());
		if (first instanceof io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor prices &&
			!(first instanceof io.evitadb.core.query.algebra.price.priceIndex.PriceIndexContainerFormula)) {
			final io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor restored =
				(io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor) second;
			assertSame(prices.getFilteredPriceRecords(context.createExecutionContext()),
				restored.getFilteredPriceRecords(context.createExecutionContext()));
			if (second instanceof io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula lowest) {
				assertNotNull(lowest.getFilteredPriceRecordsForHistogram(context.createExecutionContext()));
			}
		}
	}

	/** Each fetch root decides eligibility for its own request and preserves the key-free sibling exclusion. */
	@Test
	@DisplayName("Decides fetch root eligibility independently of the origin's entity type")
	void shouldDecideFetchEligibilityIndependently() {
		final EvitaRequest wideRequest = new EvitaRequest(Query.query(filterBy(entityPrimaryKeyInSet(1))),
			OffsetDateTime.now(), EntityReference.class, null);
		final QueryPlanningContext wide = new QueryPlanningContext(this.catalog, null, this.session, wideRequest,
			Map.of(), Map.of(), this.cache);
		assertFalse(wide.isFormulaCacheEnabled());
		assertTrue(wide.createFetchContext(this.collection, request(), Map.of(), Map.of(), this.cache)
			.isFormulaCacheEnabled());
		assertFalse(wide.createVerificationSibling().createFetchContext(
			this.collection, request(), Map.of(), Map.of(), this.cache).isFormulaCacheEnabled());
	}

	/** Covers a fetch root under a bound real transaction and the second execution-context transaction check. */
	@Test
	@DisplayName("Closes fetch and execution gates under a bound transaction")
	void shouldCloseGatesUnderBoundTransaction() {
		final QueryPlanningContext root = context(null);
		try (final io.evitadb.core.session.EvitaSession writing = FormulaCacheTestSessions.readWrite(
			this.evita, "cacheTest")) {
			io.evitadb.core.transaction.Transaction.executeInTransactionIfProvided(
				writing.getOpenedTransaction().orElseThrow(), () -> {
					assertFalse(root.createExecutionContext().isFormulaCacheUsable());
					assertFalse(root.createFetchContext(this.collection, request(), Map.of(), Map.of(), this.cache)
						.isFormulaCacheEnabled());
					return null;
				});
		}
		assertEquals(0, this.cache.getStatistics().hits() + this.cache.getStatistics().misses());
		assertTrue(this.cache.getStatistics().transactionBypasses() > 0);
	}

	/** Admission eligibility rejects opaque keys and large exact producer lists before looking them up. */
	@Test
	@DisplayName("Leaves query-local and overweight keys unbound")
	void shouldLeaveLocalAndOverweightKeysUnbound() {
		final QueryPlanningContext context = context(null);
		final int[] content = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < content.length; i++) content[i] = i;
		final OrFormula local = new OrFormula(new BaseBitmap(content), new BaseBitmap(-1));
		prepare(local, context);
		assertTrue(local.getKey().isQueryLocal());
		local.compute();
		final Bitmap[] operands = new Bitmap[TokenList.CAP];
		for (int i = 0; i < operands.length; i++) operands[i] = new TransactionalBitmap(new BaseBitmap(i));
		final OrFormula overweight = new OrFormula(operands);
		prepare(overweight, context);
		assertFalse(overweight.getKey().isQueryLocal());
		assertTrue(overweight.getKey().getWeight() > 4_096);
		overweight.compute();
		assertEquals(0, this.cache.getStatistics().hits() + this.cache.getStatistics().misses());
		assertEquals(0, this.cache.getStatistics().admissions());
	}

	/** Cheap candidates avoid even key construction for the tier. */
	@Test
	@DisplayName("Checks estimated cost before requesting a candidate key")
	void shouldCheckFloorBeforeKey() {
		this.cache.close();
		this.cache = new FormulaCacheSupervisor(CacheOptions.builder(options(0, 0))
			.minimalComplexityThreshold(Long.MAX_VALUE).build(), this.scheduler, this.clock::get);
		final InputBlindCachedFormula formula = new InputBlindCachedFormula(45, 1);
		prepare(formula, context(null));
		formula.compute();
		assertEquals(0, formula.identities);
		assertEquals(0, this.cache.getStatistics().misses());
	}

	/** Coordinates two real computations after both miss so exactly one publication must lose. */
	@Test
	@DisplayName("Counts duplicate computations and their complete retained charges")
	void shouldCountDuplicateWork() throws Exception {
		final java.util.concurrent.CountDownLatch arrived = new java.util.concurrent.CountDownLatch(2);
		final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		final java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2,
			runnable -> {
				final Thread thread = new Thread(runnable, "duplicate-cache-test");
				thread.setDaemon(true);
				return thread;
			});
		try {
			final InputBlindCachedFormula first = new InputBlindCachedFormula(42, 1);
			final InputBlindCachedFormula second = new InputBlindCachedFormula(42, 1);
			final Runnable rendezvous = () -> {
				arrived.countDown();
				try {
					assertTrue(release.await(30, java.util.concurrent.TimeUnit.SECONDS));
					Thread.sleep(5);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError(exception);
				}
			};
			first.duringCompute = rendezvous;
			second.duringCompute = rendezvous;
			prepare(first, context(null));
			prepare(second, context(null));
			final java.util.concurrent.Future<Bitmap> firstResult = workers.submit(first::compute);
			final java.util.concurrent.Future<Bitmap> secondResult = workers.submit(second::compute);
			assertTrue(arrived.await(30, java.util.concurrent.TimeUnit.SECONDS));
			release.countDown();
			assertArrayEquals(firstResult.get(30, java.util.concurrent.TimeUnit.SECONDS).getArray(),
				secondResult.get(30, java.util.concurrent.TimeUnit.SECONDS).getArray());
			assertNotSame(first.getMemoizedResult(), second.getMemoizedResult());
			assertEquals(2, this.cache.getStatistics().misses());
			assertEquals(1, this.cache.getStatistics().admissions());
			assertEquals(1, this.cache.getStatistics().duplicateAdmissions());
			assertTrue(this.cache.getStatistics().duplicateBytes() > 0);
			assertTrue(this.cache.getStatistics().duplicateComputeMillis() > 0);
		} finally {
			release.countDown();
			workers.shutdownNow();
		}
	}

	/** A real engine's kill switch changes its management state without changing another enabled engine. */
	@Test
	@DisplayName("Disables only the engine that owns the mismatching entry")
	void shouldDisableOwningEngineOnly() {
		final TestPaths owningPaths = createTestPaths("FormulaCacheSupervisorTest-owning");
		final TestPaths otherPaths = createTestPaths("FormulaCacheSupervisorTest-other");
		try (final Evita owning = new Evita(newTestEvitaConfigurationBuilder(owningPaths).cache(options(1, 0)).build());
			final Evita other = new Evita(newTestEvitaConfigurationBuilder(otherPaths).cache(options(1, 0)).build())) {
			owning.defineCatalog("owningCache").updateViaNewSession(owning);
			owning.updateCatalog("owningCache", writer -> {
				writer.defineEntitySchema("product").withoutGeneratedPrimaryKey().updateVia(writer);
				writer.upsertEntity(writer.createNewEntity("product", 1));
				writer.goLiveAndClose();
			});
			try (final EvitaSessionContract reader = FormulaCacheTestSessions.readOnly(owning, "owningCache")) {
				final EntityCollection owningCollection = ((Catalog) owning.getCatalogInstanceOrThrowException("owningCache"))
					.getCollectionForEntityInternal("product").orElseThrow();
				for (int result = 1; result <= 2; result++) {
					final QueryPlanningContext context = owningCollection.createQueryContext(request(), reader);
					final InputBlindCachedFormula formula = new InputBlindCachedFormula(67, result);
					context.analyse(formula);
					formula.initialize(context.createExecutionContext());
					if (result == 1) formula.compute();
					else assertThrows(GenericEvitaInternalError.class, formula::compute);
				}
				assertFalse(owning.getFormulaCacheStatistics().active());
				assertEquals(0, owning.getFormulaCacheStatistics().entries());
				assertFalse(owning.management().getEngineSettings().queryCacheEnabled());
				assertTrue(other.management().getEngineSettings().queryCacheEnabled());
				assertTrue(other.getFormulaCacheStatistics().active());
			}
		} finally {
			cleanupTestPaths(owningPaths);
			cleanupTestPaths(otherPaths);
		}
	}

	/** The expiry ticker releases a detecting query after the publication's first activity read, before insertion. */
	@Test
	@DisplayName("Removes a publication racing a confirmed mismatch on another thread")
	void shouldRemovePublicationRacingMismatch() {
		this.cache.close();
		final java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
		final java.util.concurrent.atomic.AtomicReference<Runnable> detection =
			new java.util.concurrent.atomic.AtomicReference<>();
		final java.util.concurrent.ExecutorService detector = java.util.concurrent.Executors.newSingleThreadExecutor(
			runnable -> {
				final Thread thread = new Thread(runnable, "publication-cache-mismatch");
				thread.setDaemon(true);
				return thread;
			});
		try {
			this.cache = new FormulaCacheSupervisor(options(1, 600), this.scheduler, () -> {
				if (armed.compareAndSet(true, false)) {
					try {
						detector.submit(detection.get()).get(30, java.util.concurrent.TimeUnit.SECONDS);
					} catch (Exception exception) {
						throw new AssertionError("Concurrent mismatch detection did not complete.", exception);
					}
				}
				return this.clock.get();
			});
			final InputBlindCachedFormula seed = new InputBlindCachedFormula(47, 1);
			prepare(seed, context(null));
			seed.compute();
			assertEquals(1, this.cache.getStatistics().entries());
			final InputBlindCachedFormula corrupt = new InputBlindCachedFormula(47, 2);
			prepare(corrupt, context(null));
			detection.set(() -> assertThrows(GenericEvitaInternalError.class, corrupt::compute));
			final InputBlindCachedFormula publishing = new InputBlindCachedFormula(43, 1);
			publishing.duringCompute = () -> armed.set(true);
			prepare(publishing, context(null));
			publishing.compute();
			this.cache.performMaintenance();
			assertFalse(this.cache.isActive());
			assertEquals(1, this.cache.getStatistics().mismatches());
			assertEquals(2, this.cache.getStatistics().admissions());
			assertEquals(0, this.cache.getStatistics().entries());
		} finally {
			detector.shutdownNow();
		}
	}

	/** Inspects the actual published tuple and the detached first witness without opening the cache map. */
	@Test
	@DisplayName("Retains no query state through an admitted entry or mismatch witness")
	void shouldRetainOnlyDetachedEntryAndWitness() {
		this.cache.close();
		this.cache = new FormulaCacheSupervisor(options(1, 0), this.scheduler, this.clock::get);
		final QueryPlanningContext context = context(null);
		final InputBlindCachedFormula first = new InputBlindCachedFormula(44, 1);
		prepare(first, context);
		first.compute();
		assertEquals(1, this.cache.getStatistics().entries());
		assertDetachedGraph(org.openjdk.jol.info.GraphLayout.parseInstance(first.getKey(), first.retainedTuple));
		final InputBlindCachedFormula second = new InputBlindCachedFormula(44, 2);
		prepare(second, context);
		assertThrows(GenericEvitaInternalError.class, second::compute);
		final String witness = this.cache.getStatistics().witness();
		assertNotNull(witness);
		assertTrue(witness.length() < 6_000);
		assertDetachedGraph(org.openjdk.jol.info.GraphLayout.parseInstance(witness));
	}

	/** Rejects query-lifetime and index owners in the graph of a stored key, tuple or diagnostic witness. */
	private static void assertDetachedGraph(@Nonnull org.openjdk.jol.info.GraphLayout graph) {
		for (final Class<?> type : graph.getClasses()) {
			assertFalse(Formula.class.isAssignableFrom(type), type.getName());
			assertFalse(QueryExecutionContext.class.isAssignableFrom(type), type.getName());
			assertFalse(QueryPlanningContext.class.isAssignableFrom(type), type.getName());
			assertFalse(EvitaSessionContract.class.isAssignableFrom(type), type.getName());
			assertFalse(Catalog.class.isAssignableFrom(type), type.getName());
			assertFalse(type.getName().startsWith("io.evitadb.index.") && type.getSimpleName().endsWith("Index"),
				type.getName());
		}
	}

	/** The integrated compute path refuses a complete charge above one percent of the configured budget. */
	@Test
	@DisplayName("Refuses entries above the per-entry cap")
	void shouldRefuseOversizedEntry() {
		this.cache.close();
		this.cache = new FormulaCacheSupervisor(CacheOptions.builder(options(0, 0)).cacheSizeInBytes(1_000L)
			.build(), this.scheduler, this.clock::get);
		final OrFormula formula = new OrFormula(new BaseBitmap(1), new BaseBitmap(2));
		prepare(formula, context(null));
		formula.compute();
		assertEquals(1, this.cache.getStatistics().refusedOversized());
		assertEquals(0, this.cache.getStatistics().entries());
	}

	/** A histogram wrapper uses its calling execution context and preserves the EMPTY singleton on a hit. */
	@Test
	@DisplayName("Returns the retained histogram instance including EMPTY")
	void shouldRestoreHistogramInstance() {
		final QueryPlanningContext context = context(null);
		final QueryExecutionContext execution = context.createExecutionContext();
		for (int i = 0; i < 2; i++) {
			final io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer computer =
				new io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer(
					2, io.evitadb.api.query.require.HistogramBehavior.STANDARD, 0,
					io.evitadb.api.query.require.QueryPriceMode.WITH_TAX, EmptyFormula.INSTANCE,
					EmptyFormula.INSTANCE, null);
			computer.initialize(execution);
			assertSame(io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.EMPTY,
				execution.analyse(computer).compute());
		}
		assertEquals(1, this.cache.getStatistics().hits());
		final QueryExecutionContext prefetch = context.createExecutionContext(true, null);
		final io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer computer =
			new io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer(
				2, io.evitadb.api.query.require.HistogramBehavior.STANDARD, 0,
				io.evitadb.api.query.require.QueryPriceMode.WITH_TAX, EmptyFormula.INSTANCE,
				EmptyFormula.INSTANCE, null);
		computer.initialize(prefetch);
		final FormulaCacheSupervisor.Statistics before = this.cache.getStatistics();
		assertSame(computer, prefetch.analyse(computer));
		computer.compute();
		assertEquals(before.hits(), this.cache.getStatistics().hits());
		assertEquals(before.misses(), this.cache.getStatistics().misses());
		assertEquals(before.admissions(), this.cache.getStatistics().admissions());
	}

	/** A populated histogram is retained as the same detached instance, not converted or recomputed on a hit. */
	@Test
	@DisplayName("Returns the retained nonempty histogram instance")
	void shouldRestorePopulatedHistogramInstance() {
		final Supplier<Formula> factory = CachedResultEquivalenceTest.formulaSuppliers()
			.filter(candidate -> candidate.get() instanceof
				io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula)
			.findFirst().orElseThrow();
		final QueryExecutionContext execution = context(null).createExecutionContext();
		io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract first = null;
		for (int i = 0; i < 2; i++) {
			final Formula formula = factory.get();
			final io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer computer =
				new io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer(
					2, io.evitadb.api.query.require.HistogramBehavior.STANDARD, 2,
					io.evitadb.api.query.require.QueryPriceMode.WITH_TAX, formula, formula, null);
			computer.initialize(execution);
			final io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract result =
				execution.analyse(computer).compute();
			assertTrue(result.getOverallCount() > 0);
			if (first == null) first = result;
			else assertSame(first, result);
			assertDetachedGraph(org.openjdk.jol.info.GraphLayout.parseInstance(computer.getKey(), result));
		}
		assertEquals(1, this.cache.getStatistics().hits());
	}

	/**
	 * Real execution context counting accesses that the unbound fast path must avoid.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class CountingExecutionContext extends QueryExecutionContext {
		/** Number of cache usability checks since construction. */
		private int usabilityReads;

		/** Constructs an ordinary context on a real pinned planning root. */
		private CountingExecutionContext(@Nonnull QueryPlanningContext context) {
			super(context, false, null, (type, entity) -> entity);
		}

		/** Counts the dynamic checks that a bound computation may perform. */
		@Override
		public boolean isFormulaCacheUsable() {
			this.usabilityReads++;
			return super.isFormulaCacheUsable();
		}
	}

	/**
	 * Deliberately omits the result and callback from its identity to exercise confirmed mismatch handling.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class InputBlindCachedFormula extends AbstractCacheableFormula {
		/** Generated stable discriminator for this deliberately defective test formula. */
		private static final long CLASS_ID = 3947775395710472824L;
		/** Declared input shared by the corrupt pair. */
		private final int id;
		/** Intentionally omitted semantic input. */
		private final Bitmap result;
		/** Optional work performed inside recomputation. */
		@Nullable private Runnable duringCompute;
		/** Optional failure during payload freezing or sizing. */
		@Nullable private Runnable duringMeasure;
		/** Number of actual computations. */
		private int computations;
		/** Number of identity declarations requested. */
		private int identities;
		/** Number of binding-walk child reads. */
		private int childReads;
		/** Exact tuple passed to publication, observed without exposing production cache internals. */
		@Nullable private CachedResult retainedTuple;

		/** Builds a deliberately incomplete identity over real bitmap output. */
		private InputBlindCachedFormula(int id, int result, @Nonnull Formula... children) {
			this.id = id;
			this.result = new BaseBitmap(result);
			initFields(children);
		}

		/** Counts key construction requests. */
		@Nonnull
		@Override
		protected IdentityRecord identity() {
			this.identities++;
			return new Identity(this.id);
		}

		/** Returns the generated discriminator. */
		@Override
		protected long getClassId() { return CLASS_ID; }

		/** Keeps a fixed cardinality so the doubling graph's estimate stays constant-time. */
		@Override
		public int getEstimatedCardinality() { return 1; }

		/** Returns a nonzero operation cost. */
		@Override
		public long getOperationCost() { return 1; }

		/** Counts distinct-instance child enumeration. */
		@Nonnull
		@Override
		public Formula[] getInnerFormulas() {
			this.childReads++;
			return super.getInnerFormulas();
		}

		/** Executes optional descendant work and returns the intentionally unkeyed input. */
		@Nonnull
		@Override
		protected Bitmap computeInternal() {
			this.computations++;
			if (this.duringCompute != null) this.duringCompute.run();
			return this.result;
		}

		/** Runs an optional sizing failure before the production long-charge calculation. */
		@Override
		protected long measurePayload(@Nonnull Bitmap computed) {
			if (this.duringMeasure != null) this.duringMeasure.run();
			return super.measurePayload(computed);
		}

		/** Captures the actual immutable tuple returned to the production publication path. */
		@Nonnull
		@Override
		protected CachedResult toCachedResult(@Nonnull Bitmap computed, int weight) {
			this.retainedTuple = super.toCachedResult(computed, weight);
			return this.retainedTuple;
		}

		/** Allocates a fresh, unbound instance. */
		@Nonnull
		@Override
		public Formula getCloneWithInnerFormulas(@Nonnull Formula... children) {
			return new InputBlindCachedFormula(this.id, this.result.iterator().nextInt(), children);
		}

		/**
		 * Incomplete identity used only by this detector fixture.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity(int id) implements IdentityRecord {
			/** Returns the generated formula discriminator. */
			@Override
			public long classId() { return CLASS_ID; }
			/** Feeds the single declared input. */
			@Override
			public void describeTo(@Nonnull KeyAccumulator accumulator) { accumulator.add(this.id); }
		}
	}
}
