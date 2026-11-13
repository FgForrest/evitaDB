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
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.cache.FormulaCacheMismatchEvent;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.*;
import io.evitadb.core.query.algebra.base.*;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.identity.*;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIndexContainerFormula;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramComputer;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramProducer.AttributeHistogramRequest;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.*;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.RandomUtils;
import jdk.jfr.Recording;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openjdk.jol.info.GraphLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
		return request(DebugMode.PREFER_INDEX_SCAN);
	}

	/** Creates an entity-scoped request with the passed debug mode, or with none. */
	@Nonnull
	private static EvitaRequest request(@Nullable DebugMode mode) {
		final Query query = mode == null ? Query.query(collection("product")) :
			Query.query(collection("product"), require(debug(mode)));
		return new EvitaRequest(query, OffsetDateTime.now(), EntityReference.class, null);
	}

	/** Creates a price histogram computer over empty formulas, which computes the shared empty histogram. */
	@Nonnull
	private static PriceHistogramComputer emptyPriceHistogram() {
		return new PriceHistogramComputer(2, HistogramBehavior.STANDARD, 0, QueryPriceMode.WITH_TAX,
			EmptyFormula.INSTANCE, EmptyFormula.INSTANCE, null);
	}

	/** Binds and initializes one tree under an ordinary execution context. */
	private void prepare(@Nonnull Formula formula, @Nonnull QueryPlanningContext context) {
		context.analyse(formula);
		formula.initialize(context.createExecutionContext());
	}

	/** Closes the supervisor under observation and replaces it with one reading the controllable clock. */
	private void replaceCache(@Nonnull CacheOptions options) {
		replaceCache(options, this.clock::get);
	}

	/** Closes the supervisor under observation and replaces it with one reading the passed ticker. */
	private void replaceCache(@Nonnull CacheOptions options, @Nonnull Ticker ticker) {
		this.cache.close();
		this.cache = new FormulaCacheSupervisor(options, this.scheduler, ticker);
	}

	/** Creates a real planning root over the fixture collection and session with the passed supervisor. */
	@Nonnull
	private QueryPlanningContext root(@Nonnull EvitaRequest request, @Nonnull CacheSupervisor supervisor) {
		return new QueryPlanningContext(this.catalog, this.collection, this.session, request, Map.of(), Map.of(),
			supervisor);
	}

	/** Creates an independent body-fetch root of the fixture collection under the passed origin. */
	@Nonnull
	private QueryPlanningContext fetch(@Nonnull QueryPlanningContext origin) {
		return origin.createFetchContext(this.collection, request(), Map.of(), Map.of(), this.cache);
	}

	/** Returns the supervisor under observation. */
	@Nonnull
	private FormulaCacheSupervisor cache() {
		return this.cache;
	}

	/** Returns the current counters of the supervisor under observation. */
	@Nonnull
	private FormulaCacheSupervisor.Statistics statistics() {
		return this.cache.getStatistics();
	}

	/** Binds a fresh lowest-price formula and computes a price histogram over it through the supervisor. */
	private static void computeBoundPriceHistogram(
		@Nonnull PriceListAndCurrencyPriceSuperIndex index, @Nonnull QueryPlanningContext context,
		@Nonnull QueryExecutionContext execution
	) {
		final Formula formula = CachedResultTestSupport.lowestPriceFormula(index);
		context.analyse(formula);
		formula.initialize(execution);
		final PriceHistogramComputer computer = new PriceHistogramComputer(
			2, HistogramBehavior.STANDARD, 2, QueryPriceMode.WITH_TAX, formula, formula, null);
		computer.initialize(execution);
		assertNotSame(CacheableHistogramContract.EMPTY, execution.analyse(computer).compute());
	}

	/** Initializes and computes a histogram computer through the supervisor under the passed execution context. */
	@Nonnull
	private static CacheableHistogramContract computeHistogram(
		@Nonnull Object computer, @Nonnull QueryExecutionContext execution
	) {
		if (computer instanceof PriceHistogramComputer price) {
			price.initialize(execution);
			return execution.analyse(price).compute();
		} else if (computer instanceof AttributeHistogramComputer attribute) {
			attribute.initialize(execution);
			return execution.analyse(attribute).compute();
		}
		throw new IllegalArgumentException("Unknown histogram computer " + computer);
	}

	/** Places the eligible corrupt tuple at three different depths beneath production formula containers. */
	@Nonnull
	private static Formula corruptTree(@Nonnull Formula leaf, int position) {
		return switch (position) {
			case 0 -> leaf;
			case 1 -> new UserFilterFormula(new OrFormula(leaf, new ConstantFormula(new BaseBitmap(100))));
			case 2 -> new UserFilterFormula(
				new UserFilterFormula(leaf), new ConstantFormula(new BaseBitmap(1, 2, 100)));
			default -> throw new IllegalArgumentException("Unknown corrupt tuple position.");
		};
	}

	/** Rejects query-lifetime and index owners in the graph of a stored key, tuple or diagnostic witness. */
	private static void assertDetachedGraph(@Nonnull GraphLayout graph) {
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

	/**
	 * Which instances the binding walk binds, and what a bound or an unbound computation reads.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Binding of formula graphs")
	class Binding {
		/** Distinguishes a hit from repeated computation and from recursive cost accounting. */
		@Test
		@DisplayName("Restores the stored bitmap with zero paid cost and without computing children")
		void shouldRestoreMemoAndCost() {
			final QueryPlanningContext context = context(null);
			final InputBlindCachedFormula child = new InputBlindCachedFormula(1, 1);
			final Formula first = new OrFormula(child, new ConstantFormula(new BaseBitmap(2)));
			prepare(first, context);
			final Bitmap result = first.compute();
			assertTrue(statistics().admissions() > 0);
			final InputBlindCachedFormula nextChild = new InputBlindCachedFormula(1, 1);
			final Formula second = new OrFormula(nextChild, new ConstantFormula(new BaseBitmap(2)));
			prepare(second, context);
			assertNull(second.getMemoizedResult());
			assertEquals(0, nextChild.computations);
			assertSame(result, second.compute());
			assertEquals(0L, second.getCost());
			assertEquals(0L, second.getMemoizedCost());
			assertEquals(0, nextChild.computations);
			assertEquals(1, statistics().hits());
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
			assertEquals(0, statistics().hits());
			assertEquals(1, statistics().admissions());
		}

		/** The absent binding is the only additional compute-path check on disabled roots. */
		@Test
		@DisplayName("Builds no computation key on a disabled path")
		void shouldBuildNoKeyWhenUnbound() {
			final InputBlindCachedFormula formula = new InputBlindCachedFormula(3, 1);
			final QueryPlanningContext disabled = root(request(), NoCacheSupervisor.INSTANCE);
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
			assertTimeoutPreemptively(Duration.ofSeconds(5), () -> context(null).analyse(nodes[40]));
			for (final InputBlindCachedFormula node : nodes) assertEquals(1, node.childReads);
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
			assertEquals(0, statistics().hits() + statistics().misses());
			assertEquals(0, statistics().admissions());
		}

		/** A candidate is bound, and its key built, exactly when its estimated cost reaches the configured floor. */
		@ParameterizedTest(name = "floor = estimated cost + {0}")
		@ValueSource(longs = {0, 1})
		@DisplayName("Requests a candidate key only when its estimated cost reaches the floor")
		void shouldBindOnlyCandidatesReachingTheFloor(long aboveCost) {
			final long cost = new InputBlindCachedFormula(45, 1).getEstimatedCost();
			replaceCache(CacheOptions.builder(options(0, 0)).minimalComplexityThreshold(cost + aboveCost).build());
			final InputBlindCachedFormula formula = new InputBlindCachedFormula(45, 1);
			prepare(formula, context(null));
			formula.compute();
			final boolean bound = aboveCost == 0;
			assertEquals(bound, formula.identities > 0);
			assertEquals(bound ? 1 : 0, statistics().misses());
		}
	}

	/**
	 * Which planning roots and execution contexts may use the cache.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Gate of planning roots and execution contexts")
	class Gates {
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
			final FormulaCacheSupervisor.Statistics before = statistics();
			final QueryPlanningContext flagged = root(request(DebugMode.VERIFY_POSSIBLE_CACHING_TREES), cache());
			for (final QueryExecutionContext execution : new QueryExecutionContext[]{
				context.getInternalExecutionContext(), context.createExecutionContext(true, null),
				flagged.getInternalExecutionContext()
			}) {
				final OrFormula formula = new OrFormula(new BaseBitmap(1), new BaseBitmap(2));
				context.analyse(formula);
				formula.initialize(execution);
				formula.compute();
			}
			assertEquals(before.hits(), statistics().hits());
			assertEquals(before.misses(), statistics().misses());
			assertEquals(before.admissions(), statistics().admissions());
		}

		/** No binding, key construction or publication occurs when a root has a writing session or no entity type. */
		@Test
		@DisplayName("Closes the gate for writing sessions and catalog-wide roots")
		void shouldCloseGate() {
			try (final EvitaSession writing = FormulaCacheTestSessions.readWrite(
				FormulaCacheSupervisorTest.this.evita, "cacheTest")
			) {
				assertSame(NoCacheSupervisor.INSTANCE, cache().resolveForRoot(writing, "product"));
				writing.execute(internal -> {
					final QueryPlanningContext origin = new QueryPlanningContext(
						FormulaCacheSupervisorTest.this.catalog, FormulaCacheSupervisorTest.this.collection, internal,
						request(), Map.of(), Map.of(), cache());
					assertFalse(fetch(origin).isFormulaCacheEnabled());
				});
			}
			final Evita evita = FormulaCacheSupervisorTest.this.evita;
			evita.defineCatalog("warmingCache").updateViaNewSession(evita);
			try (final EvitaSessionContract warming = FormulaCacheTestSessions.readOnly(evita, "warmingCache")) {
				assertSame(NoCacheSupervisor.INSTANCE, cache().resolveForRoot(warming, "product"));
			}
			assertSame(NoCacheSupervisor.INSTANCE, cache().resolveForRoot(
				FormulaCacheSupervisorTest.this.session, null));
			assertSame(NoCacheSupervisor.INSTANCE, cache().resolveForRoot(null, "product"));
			assertSame(cache(), cache().resolveForRoot(
				FormulaCacheSupervisorTest.this.session, "product"));
			final QueryPlanningContext child = context(context(null));
			assertTrue(child.createExecutionContext().isFormulaCacheUsable());
		}

		/** The engine wiring installs no cache unless the configuration enables it. */
		@Test
		@DisplayName("Installs no formula result cache with the default configuration")
		void shouldInstallNoCacheByDefault() {
			assertNull(FormulaCacheSupervisorTest.this.evita.getFormulaCacheStatistics());
			assertFalse(FormulaCacheSupervisorTest.this.evita.management().getEngineSettings().queryCacheEnabled());
			assertFalse(FormulaCacheSupervisorTest.this.collection
				.createQueryContext(request(), FormulaCacheSupervisorTest.this.session).isFormulaCacheEnabled());
		}

		/**
		 * The gate reads the session, the catalog state, the entity type and the transaction, never the debug mode:
		 * every mode passes it once per planning root, nested contexts inherit the decision and keep the analysed
		 * instances, verification siblings never pass it, and a nested request without an entity type binds nothing.
		 */
		@ParameterizedTest(name = "{0}")
		@NullSource
		@EnumSource(value = DebugMode.class,
			names = {"PREFER_INDEX_SCAN", "VERIFY_POSSIBLE_CACHING_TREES", "VERIFY_ALTERNATIVE_INDEX_RESULTS"})
		@DisplayName("Resolves the gate once per planning root under every debug mode")
		void shouldResolveGateOncePerRootUnderEveryDebugMode(@Nullable DebugMode mode) {
			final QueryPlanningContext root = root(request(mode), cache());
			final QueryPlanningContext nested = new QueryPlanningContext(
				root, FormulaCacheSupervisorTest.this.catalog, FormulaCacheSupervisorTest.this.collection,
				FormulaCacheSupervisorTest.this.session, request(mode), null, Map.of(), Map.of(), cache()
			);
			assertTrue(root.isFormulaCacheEnabled());
			assertTrue(nested.isFormulaCacheEnabled());
			assertTrue(root.createExecutionContext(false, RandomUtils.getFrozenRandom()).isFormulaCacheUsable(),
				"A verification dry run is an execution");
			assertFalse(root.getInternalExecutionContext().isFormulaCacheUsable());

			final QueryExecutionContext nestedExecution = nested.createExecutionContext();
			final OrFormula formula = new OrFormula(new BaseBitmap(1), new BaseBitmap(2));
			assertSame(formula, nested.analyse(formula));
			formula.initialize(nestedExecution);
			formula.compute();
			final PriceHistogramComputer computer = emptyPriceHistogram();
			computer.initialize(nestedExecution);
			final EvitaResponseExtraResultComputer<CacheableHistogramContract> wrapped =
				nestedExecution.analyse(computer);
			assertNotSame(computer, wrapped);
			assertSame(CacheableHistogramContract.EMPTY, wrapped.compute());
			assertEquals(0, statistics().hits());
			assertEquals(2, statistics().misses());
			assertEquals(2, statistics().admissions());
			final OrFormula repeated = new OrFormula(new BaseBitmap(1), new BaseBitmap(2));
			prepare(repeated, nested);
			repeated.compute();
			assertEquals(1, statistics().hits());

			final QueryPlanningContext sibling = root.createVerificationSibling();
			final QueryPlanningContext siblingChild = context(sibling);
			assertFalse(sibling.isFormulaCacheEnabled());
			assertFalse(siblingChild.isFormulaCacheEnabled());
			final QueryExecutionContext siblingExecution = siblingChild.createExecutionContext();
			final PriceHistogramComputer siblingComputer = emptyPriceHistogram();
			siblingComputer.initialize(siblingExecution);
			assertSame(siblingComputer, siblingExecution.analyse(siblingComputer));

			final QueryPlanningContext wide = new QueryPlanningContext(
				root, FormulaCacheSupervisorTest.this.catalog, null, FormulaCacheSupervisorTest.this.session,
				new EvitaRequest(Query.query(filterBy(entityPrimaryKeyInSet(1))), OffsetDateTime.now(),
					EntityReference.class, null),
				null, Map.of(), Map.of(), cache()
			);
			final FormulaCacheSupervisor.Statistics beforeWide = statistics();
			final OrFormula unbound = new OrFormula(new BaseBitmap(3), new BaseBitmap(4));
			prepare(unbound, wide);
			unbound.compute();
			assertEquals(beforeWide.hits() + beforeWide.misses(),
				statistics().hits() + statistics().misses());
			assertEquals(beforeWide.admissions(), statistics().admissions());
		}

		/** Each fetch root decides eligibility for its own request and preserves the key-free sibling exclusion. */
		@Test
		@DisplayName("Decides fetch root eligibility independently of the origin's entity type")
		void shouldDecideFetchEligibilityIndependently() {
			final EvitaRequest wideRequest = new EvitaRequest(Query.query(filterBy(entityPrimaryKeyInSet(1))),
				OffsetDateTime.now(), EntityReference.class, null);
			final QueryPlanningContext wide = new QueryPlanningContext(FormulaCacheSupervisorTest.this.catalog, null,
				FormulaCacheSupervisorTest.this.session, wideRequest, Map.of(), Map.of(), cache());
			assertFalse(wide.isFormulaCacheEnabled());
			assertTrue(fetch(wide).isFormulaCacheEnabled());
			assertFalse(fetch(wide.createVerificationSibling()).isFormulaCacheEnabled());
		}

		/** Covers a fetch root under a bound real transaction and the second execution-context transaction check. */
		@Test
		@DisplayName("Closes fetch and execution gates under a bound transaction")
		void shouldCloseGatesUnderBoundTransaction() {
			final QueryPlanningContext root = context(null);
			try (final EvitaSession writing = FormulaCacheTestSessions.readWrite(
				FormulaCacheSupervisorTest.this.evita, "cacheTest")
			) {
				Transaction.executeInTransactionIfProvided(
					writing.getOpenedTransaction().orElseThrow(), () -> {
						assertFalse(root.createExecutionContext().isFormulaCacheUsable());
						assertFalse(fetch(root).isFormulaCacheEnabled());
						return null;
					});
			}
			assertEquals(0, statistics().hits() + statistics().misses());
			assertTrue(statistics().transactionBypasses() > 0);
		}
	}

	/**
	 * What a computation publishes, and what the caps and failures keep out of the cache.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Publication and per-entry caps")
	class PublicationAndCaps {
		/** Failed computations never reach publication. */
		@Test
		@DisplayName("Propagates computation failures without admitting a tuple")
		void shouldNotPublishFailures() {
			final InputBlindCachedFormula formula = new InputBlindCachedFormula(2, 1);
			formula.duringCompute = () -> { throw new IllegalStateException("failed computation"); };
			prepare(formula, context(null));
			assertThrows(IllegalStateException.class, formula::compute);
			assertEquals(0, statistics().admissions());
			assertNull(formula.getMemoizedResult());
			final InputBlindCachedFormula sizingFailure = new InputBlindCachedFormula(46, 1);
			sizingFailure.duringMeasure = () -> { throw new IllegalStateException("failed sizing"); };
			prepare(sizingFailure, context(null));
			assertThrows(IllegalStateException.class, sizingFailure::compute);
			assertEquals(0, statistics().admissions());
		}

		/** Exercises injection into every concrete candidate, including all four owners of price side outputs. */
		@ParameterizedTest
		@MethodSource("io.evitadb.core.cache.CachedResultTestSupport#formulaSuppliers")
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
			if (first instanceof FilteredPriceRecordAccessor prices && !(first instanceof PriceIndexContainerFormula)) {
				final FilteredPriceRecordAccessor restored = (FilteredPriceRecordAccessor) second;
				assertSame(prices.getFilteredPriceRecords(context.createExecutionContext()),
					restored.getFilteredPriceRecords(context.createExecutionContext()));
				if (first instanceof LowestPriceTerminationFormula stored &&
					second instanceof LowestPriceTerminationFormula lowest) {
					assertSame(stored.getFilteredPriceRecordsForHistogram(context.createExecutionContext()),
						lowest.getFilteredPriceRecordsForHistogram(context.createExecutionContext()));
				}
			}
		}

		/** Coordinates two real computations after both miss so exactly one publication must lose. */
		@Test
		@DisplayName("Counts duplicate computations and their complete retained charges")
		void shouldCountDuplicateWork() throws Exception {
			final CountDownLatch arrived = new CountDownLatch(2);
			final CountDownLatch release = new CountDownLatch(1);
			final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
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
						assertTrue(release.await(30, TimeUnit.SECONDS));
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
				final Future<Bitmap> firstResult = workers.submit(first::compute);
				final Future<Bitmap> secondResult = workers.submit(second::compute);
				assertTrue(arrived.await(30, TimeUnit.SECONDS));
				release.countDown();
				assertArrayEquals(firstResult.get(30, TimeUnit.SECONDS).getArray(),
					secondResult.get(30, TimeUnit.SECONDS).getArray());
				assertNotSame(first.getMemoizedResult(), second.getMemoizedResult());
				assertEquals(2, statistics().misses());
				assertEquals(1, statistics().admissions());
				assertEquals(1, statistics().duplicateAdmissions());
				assertTrue(statistics().duplicateBytes() > 0);
				assertTrue(statistics().duplicateComputeMillis() > 0);
			} finally {
				release.countDown();
				workers.shutdownNow();
			}
		}

		/** Inspects the actual published tuple and the detached first witness without opening the cache map. */
		@Test
		@DisplayName("Retains no query state through an admitted entry or mismatch witness")
		void shouldRetainOnlyDetachedEntryAndWitness() {
			replaceCache(options(1, 0));
			final QueryPlanningContext context = context(null);
			final InputBlindCachedFormula first = new InputBlindCachedFormula(44, 1);
			prepare(first, context);
			first.compute();
			assertEquals(1, statistics().entries());
			assertDetachedGraph(GraphLayout.parseInstance(first.getKey(), first.retainedTuple));
			final InputBlindCachedFormula second = new InputBlindCachedFormula(44, 2);
			prepare(second, context);
			assertThrows(GenericEvitaInternalError.class, second::compute);
			final String witness = statistics().witness();
			assertNotNull(witness);
			assertTrue(witness.length() < 6_000);
			assertDetachedGraph(GraphLayout.parseInstance(witness));
		}

		/** The integrated compute path refuses a complete charge above one percent of the configured budget. */
		@Test
		@DisplayName("Refuses entries above the per-entry cap")
		void shouldRefuseOversizedEntry() {
			replaceCache(CacheOptions.builder(options(0, 0)).cacheSizeInBytes(1_000L).build());
			final OrFormula formula = new OrFormula(new BaseBitmap(1), new BaseBitmap(2));
			prepare(formula, context(null));
			formula.compute();
			assertEquals(1, statistics().refusedOversized());
			assertEquals(0, statistics().entries());
		}
	}

	/**
	 * Recomputation of sampled hits, mismatch handling and the switch that disables this engine's cache.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Sampled verification and kill switch")
	class SampledVerification {
		/** Sampled verification must compare the same physical winner when two variants share an amount. */
		@Test
		@Tag(PRICE)
		@DisplayName("Keeps the cache active when a sampled lowest price ties across inner records")
		void shouldKeepCacheActiveWhenRecomputedLowestPriceTiesAcrossInnerRecords() {
			replaceCache(options(1, 0));
			final PriceListAndCurrencyPriceSuperIndex index = CachedResultTestSupport.tiedPriceIndex();
			final PriceEvaluationContext priceContext = new PriceEvaluationContext(null, index.getPriceIndexKey());
			final QueryPlanningContext context = context(null);
			final Formula first = new LowestPriceTerminationFormula(
				new PriceIndexContainerFormula(index, index, index.getIndexedPriceEntityIdsFormula()),
				priceContext, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, true);
			prepare(first, context);
			assertEquals(64, first.compute().size());
			final Formula second = new LowestPriceTerminationFormula(
				new PriceIndexContainerFormula(index, index, index.getIndexedPriceEntityIdsFormula()),
				priceContext, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, true);
			prepare(second, context);
			final long hitsBefore = statistics().hits();
			final long verifiedBefore = statistics().verifiedHits();
			assertEquals(64, assertDoesNotThrow(second::compute).size());
			assertTrue(statistics().hits() > hitsBefore);
			assertTrue(statistics().verifiedHits() > verifiedBefore);
			assertTrue(cache().isActive());
			assertEquals(0, statistics().mismatches());
		}

		/** A detector reaching an already closed cache still describes its own mismatch. */
		@Test
		@DisplayName("Describes a mismatch detected after the cache was disabled")
		void shouldDescribeMismatchWhenDetectedAfterCacheWasDisabled() {
			cache().close();
			final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class, () ->
				cache().verify(context(null).createExecutionContext(),
					new OrFormula(new BaseBitmap(1), new BaseBitmap(2)).getKey(), "C", "d",
					new CachedBitmapResult(new BaseBitmap(1), 1), new CachedBitmapResult(new BaseBitmap(2), 1), 0L));
			assertTrue(error.getPrivateMessage().contains("cached-only:1"), error.getPrivateMessage());
			assertNull(statistics().witness());
		}

		/** The stored first witness and the detecting caller's description have separate lifetimes. */
		@Test
		@DisplayName("Keeps the first witness while each mismatch describes its own records")
		void shouldKeepFirstWitnessWhenAnotherMismatchIsDetected() {
			final QueryExecutionContext execution = context(null).createExecutionContext();
			final FormulaKey key = new OrFormula(new BaseBitmap(1), new BaseBitmap(2)).getKey();
			final GenericEvitaInternalError first = assertThrows(GenericEvitaInternalError.class, () ->
				cache().verify(execution, key, "C", "first", new CachedBitmapResult(new BaseBitmap(1), 1),
					new CachedBitmapResult(new BaseBitmap(2), 1), 0L));
			final String witness = statistics().witness();
			final GenericEvitaInternalError second = assertThrows(GenericEvitaInternalError.class, () ->
				cache().verify(execution, key, "C", "second", new CachedBitmapResult(new BaseBitmap(3), 1),
					new CachedBitmapResult(new BaseBitmap(4), 1), 0L));
			assertEquals(first.getPrivateMessage(), witness);
			assertEquals(witness, statistics().witness());
			assertTrue(second.getPrivateMessage().contains("cached-only:3"), second.getPrivateMessage());
			assertTrue(second.getPrivateMessage().contains("second"));
		}

		/** Every in-flight detector emits once, even when another detector has already disabled the cache. */
		@Test
		@Tag(OBSERVABILITY)
		@DisplayName("Emits the three identifying labels once per confirming detector, none once the cache is off")
		void shouldEmitOnlyIdentifiersOncePerConfirmingDetector() throws Exception {
			replaceCache(options(1, 0));
			final Path recordingPath = Files.createTempFile("formula-cache-mismatch-", ".jfr");
			try (final Recording recording = new Recording()) {
				recording.enable(FormulaCacheMismatchEvent.class);
				recording.start();
				final InputBlindCachedFormula original = new InputBlindCachedFormula(71, 1);
				prepare(original, context(null));
				original.compute();
				final InputBlindCachedFormula equal = new InputBlindCachedFormula(71, 1);
				prepare(equal, context(null));
				equal.compute();
				final InputBlindCachedFormula nestedDetector = new InputBlindCachedFormula(71, 3);
				prepare(nestedDetector, context(null));
				final InputBlindCachedFormula detector = new InputBlindCachedFormula(71, 2);
				prepare(detector, context(null));
				// Both detectors obtained a hit before either compares its recomputation with the cached tuple.
				detector.duringCompute = () -> assertThrows(GenericEvitaInternalError.class, nestedDetector::compute);
				assertThrows(GenericEvitaInternalError.class, detector::compute);
				final InputBlindCachedFormula afterShutdown = new InputBlindCachedFormula(71, 4);
				prepare(afterShutdown, context(null));
				afterShutdown.compute();
				assertEquals(2, statistics().mismatches());
				recording.stop();
				recording.dump(recordingPath);
				final List<RecordedEvent> events = RecordingFile.readAllEvents(recordingPath).stream()
					.filter(event -> event.getEventType().getName().equals("io.evitadb.cache.FormulaCacheMismatch"))
					.filter(event -> InputBlindCachedFormula.class.getName().equals(event.getString("formulaClass")))
					.toList();
				assertEquals(2, events.size());
				for (final RecordedEvent event : events) {
					final Set<String> fields = CollectionUtils.createHashSet(4);
					for (final ValueDescriptor field : event.getFields()) fields.add(field.getName());
					fields.removeAll(Set.of("startTime", "duration", "eventThread", "stackTrace"));
					assertEquals(Set.of("catalogName", "entityType", "formulaClass"), fields);
					assertEquals("cacheTest", event.getString("catalogName"));
					assertEquals("product", event.getString("entityType"));
				}
			} finally {
				Files.deleteIfExists(recordingPath);
			}
		}

		/** A sampled hit keeps its freshly computed tuple and normal node cost. */
		@Test
		@DisplayName("Recomputes equal sampled hits and keeps the recomputation")
		void shouldKeepVerifiedTuple() {
			replaceCache(options(1, 0), Ticker.systemTicker());
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
			assertTrue(cache().isActive());
			assertEquals(1, statistics().verifiedHits());
		}

		/**
		 * A sampled hit recomputes the complete tuple, price outputs included, and keeps the recomputation; a capture
		 * or restore asymmetry would make the equal recomputation compare unequal and switch the cache off.
		 */
		@ParameterizedTest
		@MethodSource("io.evitadb.core.cache.CachedResultTestSupport#formulaSuppliers")
		@DisplayName("Confirms a sampled hit of every cacheable formula class and keeps its recomputation")
		void shouldConfirmSampledHitOfEveryCandidate(@Nonnull Supplier<Formula> factory) {
			replaceCache(options(1, 0));
			final QueryPlanningContext context = context(null);
			final Formula first = factory.get();
			final Formula second = factory.get();
			prepare(first, context);
			first.compute();
			final FormulaCacheSupervisor.Statistics before = statistics();
			prepare(second, context);
			second.compute();
			final FormulaCacheSupervisor.Statistics after = statistics();
			assertEquals(before.hits() + 1, after.hits());
			assertEquals(before.verifiedHits() + 1, after.verifiedHits());
			assertEquals(0, after.mismatches());
			assertTrue(cache().isActive());
			if (first instanceof FilteredPriceRecordAccessor stored && !(first instanceof PriceIndexContainerFormula)) {
				final FilteredPriceRecordAccessor recomputed = (FilteredPriceRecordAccessor) second;
				final QueryExecutionContext execution = context.createExecutionContext();
				final ResolvedFilteredPriceRecords storedPrices =
					(ResolvedFilteredPriceRecords) stored.getFilteredPriceRecords(execution);
				final ResolvedFilteredPriceRecords recomputedPrices =
					(ResolvedFilteredPriceRecords) recomputed.getFilteredPriceRecords(execution);
				assertNotSame(storedPrices, recomputedPrices);
				ResolvedFilteredPriceRecords storedInner = null;
				ResolvedFilteredPriceRecords recomputedInner = null;
				if (first instanceof LowestPriceTerminationFormula storedLowest &&
					second instanceof LowestPriceTerminationFormula recomputedLowest) {
					storedInner =
						(ResolvedFilteredPriceRecords) storedLowest.getFilteredPriceRecordsForHistogram(execution);
					recomputedInner =
						(ResolvedFilteredPriceRecords) recomputedLowest.getFilteredPriceRecordsForHistogram(execution);
					assertNotNull(storedInner);
					assertNotSame(storedInner, recomputedInner);
				}
				assertTrue(CachedResultEquivalence.equivalent(
					new CachedPriceRecordsResult(first.compute(), storedPrices, storedInner, 1),
					new CachedPriceRecordsResult(second.compute(), recomputedPrices, recomputedInner, 1)));
			}
		}

		/** Both histogram computers recompute a sampled hit, compare it with the stored one and return the new one. */
		@Test
		@Tag(HISTOGRAM)
		@DisplayName("Confirms sampled hits of both histogram computers and keeps their recomputations")
		void shouldConfirmSampledHistogramHits() {
			replaceCache(options(1, 0));
			final QueryExecutionContext execution = context(null).createExecutionContext();
			final PriceListAndCurrencyPriceSuperIndex index = CachedResultTestSupport.priceIndex();
			final AttributeHistogramRequest request = CachedResultTestSupport.attributeHistogramRequest();
			final AttributeHistogramComputer attributeProbe = CachedResultTestSupport.attributeHistogram(request);
			assertFalse(attributeProbe.getKey().isQueryLocal(), "The attribute histogram must be eligible");
			final Supplier<?>[] computers = {
				() -> CachedResultTestSupport.priceHistogram(index),
				() -> CachedResultTestSupport.attributeHistogram(request)
			};
			for (final Supplier<?> factory : computers) {
				final CacheableHistogramContract stored = computeHistogram(factory.get(), execution);
				final FormulaCacheSupervisor.Statistics before = statistics();
				final CacheableHistogramContract recomputed = computeHistogram(factory.get(), execution);
				assertNotSame(CacheableHistogramContract.EMPTY, stored);
				assertNotSame(stored, recomputed);
				assertTrue(CachedResultEquivalence.equivalent(
					new CachedHistogramResult(stored, 1), new CachedHistogramResult(recomputed, 1)));
				assertEquals(before.hits() + 1, statistics().hits());
				assertEquals(before.verifiedHits() + 1, statistics().verifiedHits());
			}
			assertEquals(0, statistics().mismatches());
			assertTrue(cache().isActive());
		}

		/** A sampled histogram recomputation reaches bound formulas, which must neither look up nor admit meanwhile. */
		@Test
		@Tag(HISTOGRAM)
		@Tag(PRICE)
		@DisplayName("Suspends bound formulas during a sampled histogram recomputation")
		void shouldSuspendBoundFormulasDuringSampledHistogramRecomputation() {
			replaceCache(options(1, 0));
			final PriceListAndCurrencyPriceSuperIndex index = CachedResultTestSupport.priceIndex();
			final QueryPlanningContext context = context(null);
			final QueryExecutionContext execution = context.createExecutionContext();
			final FormulaCacheSupervisor.Statistics start = statistics();
			computeBoundPriceHistogram(index, context, execution);
			final FormulaCacheSupervisor.Statistics first = statistics();
			assertTrue(first.admissions() - start.admissions() > 1,
				"The bound formulas below the histogram must reach the cache, not only the histogram");
			computeBoundPriceHistogram(index, context, execution);
			final FormulaCacheSupervisor.Statistics second = statistics();
			assertEquals(first.hits() + 1, second.hits(), "Only the histogram itself may be looked up");
			assertEquals(first.misses(), second.misses());
			assertEquals(first.admissions(), second.admissions());
			assertEquals(first.verifiedHits() + 1, second.verifiedHits());
		}

		/** Omitting an actual input is observable only when a hit is independently recomputed. */
		@ParameterizedTest
		@ValueSource(ints = {0, 1, 2})
		@DisplayName("Fails the detecting query for corrupt tuples at the top, middle and frontier")
		void shouldFailOnConfirmedMismatch(int position) {
			replaceCache(options(1, 0));
			final QueryPlanningContext context = context(null);
			final Formula first = corruptTree(new InputBlindCachedFormula(5, 1), position);
			prepare(first, context);
			first.compute();
			final Formula second = corruptTree(new InputBlindCachedFormula(5, 2), position);
			prepare(second, context);
			final InputBlindCachedFormula inFlight = new InputBlindCachedFormula(9, 3);
			prepare(inFlight, context);
			assertThrows(GenericEvitaInternalError.class, second::compute);
			final FormulaCacheSupervisor.Statistics killed = statistics();
			assertTrue(inFlight.compute().contains(3));
			assertEquals(1, inFlight.computations);
			assertEquals(killed.hits(), statistics().hits());
			assertEquals(killed.misses(), statistics().misses());
			assertEquals(killed.admissions(), statistics().admissions());
			assertFalse(cache().isActive());
			assertEquals(0, statistics().entries());
			assertEquals(1, statistics().mismatches());
			assertNotNull(statistics().witness());
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
			assertTrue(cache().isActive());
		}

		/** Suspension reaches contexts captured before the sample and independent roots created during it. */
		@Test
		@DisplayName("Suspends bound descendants across captured contexts and independent fetch roots")
		void shouldSuspendAllReachedContexts() {
			replaceCache(options(1, 0));
			final QueryPlanningContext root = context(null);
			final QueryPlanningContext captured = context(root);
			final QueryExecutionContext capturedExecution = captured.createExecutionContext();
			final QueryExecutionContext previousFetch = fetch(root).createExecutionContext();
			final InputBlindCachedFormula first = new InputBlindCachedFormula(7, 1);
			prepare(first, root);
			first.compute();
			final InputBlindCachedFormula second = new InputBlindCachedFormula(7, 1);
			second.duringCompute = () -> {
				final QueryPlanningContext fetchRoot = fetch(root);
				assertFalse(fetchRoot.createExecutionContext().isFormulaCacheUsable());
				assertFalse(capturedExecution.isFormulaCacheUsable());
				assertFalse(previousFetch.isFormulaCacheUsable());
				for (final QueryExecutionContext execution : new QueryExecutionContext[]{
					capturedExecution, previousFetch, fetchRoot.createExecutionContext()
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
			assertEquals(1, statistics().hits());
			assertEquals(1, statistics().misses());
			assertEquals(1, statistics().admissions());
			assertTrue(captured.createExecutionContext().isFormulaCacheUsable());
		}

		/** A real engine's kill switch changes its management state without changing another enabled engine. */
		@Test
		@DisplayName("Disables only the engine that owns the mismatching entry")
		void shouldDisableOwningEngineOnly() {
			final TestPaths owningPaths = createTestPaths("FormulaCacheSupervisorTest-owning");
			final TestPaths otherPaths = createTestPaths("FormulaCacheSupervisorTest-other");
			try (
				final Evita owning = new Evita(
					newTestEvitaConfigurationBuilder(owningPaths).cache(options(1, 0)).build());
				final Evita other = new Evita(
					newTestEvitaConfigurationBuilder(otherPaths).cache(options(1, 0)).build())
			) {
				owning.defineCatalog("owningCache").updateViaNewSession(owning);
				owning.updateCatalog("owningCache", writer -> {
					writer.defineEntitySchema("product").withoutGeneratedPrimaryKey().updateVia(writer);
					writer.upsertEntity(writer.createNewEntity("product", 1));
					writer.goLiveAndClose();
				});
				try (final EvitaSessionContract reader = FormulaCacheTestSessions.readOnly(owning, "owningCache")) {
					final EntityCollection owningCollection =
						((Catalog) owning.getCatalogInstanceOrThrowException("owningCache"))
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

		/** The expiry ticker releases a detecting query after the publication's first activity read, before insert. */
		@Test
		@DisplayName("Removes a publication racing a confirmed mismatch on another thread")
		void shouldRemovePublicationRacingMismatch() {
			cache().close();
			final AtomicBoolean armed = new AtomicBoolean();
			final AtomicReference<Runnable> detection = new AtomicReference<>();
			final ExecutorService detector = Executors.newSingleThreadExecutor(runnable -> {
				final Thread thread = new Thread(runnable, "publication-cache-mismatch");
				thread.setDaemon(true);
				return thread;
			});
			try {
				replaceCache(options(1, 600), () -> {
					if (armed.compareAndSet(true, false)) {
						try {
							detector.submit(detection.get()).get(30, TimeUnit.SECONDS);
						} catch (Exception exception) {
							throw new AssertionError("Concurrent mismatch detection did not complete.", exception);
						}
					}
					return FormulaCacheSupervisorTest.this.clock.get();
				});
				final InputBlindCachedFormula seed = new InputBlindCachedFormula(47, 1);
				prepare(seed, context(null));
				seed.compute();
				assertEquals(1, statistics().entries());
				final InputBlindCachedFormula corrupt = new InputBlindCachedFormula(47, 2);
				prepare(corrupt, context(null));
				detection.set(() -> assertThrows(GenericEvitaInternalError.class, corrupt::compute));
				final InputBlindCachedFormula publishing = new InputBlindCachedFormula(43, 1);
				publishing.duringCompute = () -> armed.set(true);
				prepare(publishing, context(null));
				publishing.compute();
				cache().performMaintenance();
				assertFalse(cache().isActive());
				assertEquals(1, statistics().mismatches());
				assertEquals(2, statistics().admissions());
				assertEquals(0, statistics().entries());
			} finally {
				detector.shutdownNow();
			}
		}
	}

	/**
	 * Idle expiry and closing of the cache.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Expiry and close")
	class Lifecycle {
		/** Idle maintenance and close release entries without relying on further query traffic. */
		@Test
		@DisplayName("Expires idle tuples and empties the cache on close")
		void shouldExpireAndClose() {
			replaceCache(options(0, 1));
			final OrFormula formula = new OrFormula(new BaseBitmap(1), new BaseBitmap(2));
			prepare(formula, context(null));
			formula.compute();
			assertEquals(1, statistics().entries());
			FormulaCacheSupervisorTest.this.clock.addAndGet(2_000_000_000L);
			cache().performMaintenance();
			assertEquals(0, statistics().entries());
			final OrFormula remaining = new OrFormula(new BaseBitmap(3), new BaseBitmap(4));
			prepare(remaining, context(null));
			remaining.compute();
			assertEquals(1, statistics().entries());
			cache().close();
			assertEquals(0, statistics().entries());
			assertFalse(cache().isActive());
			assertFalse(context(null).isFormulaCacheEnabled());
		}
	}

	/**
	 * Restoring, refusing and bypassing the histogram computers.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Histogram computers")
	class Histograms {
		/** A histogram wrapper uses its calling execution context and preserves the EMPTY singleton on a hit. */
		@Test
		@DisplayName("Returns the retained histogram instance including EMPTY")
		void shouldRestoreHistogramInstance() {
			final QueryPlanningContext context = context(null);
			final QueryExecutionContext execution = context.createExecutionContext();
			for (int i = 0; i < 2; i++) {
				final PriceHistogramComputer computer = emptyPriceHistogram();
				computer.initialize(execution);
				assertSame(CacheableHistogramContract.EMPTY, execution.analyse(computer).compute());
			}
			assertEquals(1, statistics().hits());
			final QueryExecutionContext prefetch = context.createExecutionContext(true, null);
			final PriceHistogramComputer computer = emptyPriceHistogram();
			computer.initialize(prefetch);
			final FormulaCacheSupervisor.Statistics before = statistics();
			assertSame(computer, prefetch.analyse(computer));
			computer.compute();
			assertEquals(before.hits(), statistics().hits());
			assertEquals(before.misses(), statistics().misses());
			assertEquals(before.admissions(), statistics().admissions());
		}

		/** A populated histogram is retained as the same detached instance, not converted or recomputed on a hit. */
		@Test
		@DisplayName("Returns the retained nonempty histogram instance")
		void shouldRestorePopulatedHistogramInstance() {
			final PriceListAndCurrencyPriceSuperIndex index = CachedResultTestSupport.priceIndex();
			final QueryExecutionContext execution = context(null).createExecutionContext();
			CacheableHistogramContract first = null;
			for (int i = 0; i < 2; i++) {
				final PriceHistogramComputer computer = CachedResultTestSupport.priceHistogram(index);
				computer.initialize(execution);
				final CacheableHistogramContract result = execution.analyse(computer).compute();
				assertTrue(result.getOverallCount() > 0);
				if (first == null) first = result;
				else assertSame(first, result);
				assertDetachedGraph(GraphLayout.parseInstance(computer.getKey(), result));
			}
			assertEquals(1, statistics().hits());
		}

		/** The histogram path applies the same floor as formulas and records no lookup for a cheap computer. */
		@Test
		@Tag(HISTOGRAM)
		@DisplayName("Returns a histogram computer below the floor unwrapped")
		void shouldLeaveHistogramBelowFloorUnwrapped() {
			replaceCache(CacheOptions.builder(options(0, 0)).minimalComplexityThreshold(Long.MAX_VALUE).build());
			final QueryExecutionContext execution = context(null).createExecutionContext();
			final PriceHistogramComputer computer = CachedResultTestSupport.priceHistogram(
				CachedResultTestSupport.priceIndex());
			computer.initialize(execution);
			assertSame(computer, execution.analyse(computer));
			computer.compute();
			assertEquals(0, statistics().hits() + statistics().misses());
		}

		/** A histogram whose complete charge exceeds the per-entry cap is returned but never handed to the cache. */
		@Test
		@Tag(HISTOGRAM)
		@DisplayName("Returns an oversized histogram without admitting it")
		void shouldRefuseOversizedHistogram() {
			replaceCache(CacheOptions.builder(options(0, 0)).cacheSizeInBytes(1_000L).build());
			final QueryExecutionContext execution = context(null).createExecutionContext();
			final PriceHistogramComputer computer = CachedResultTestSupport.priceHistogram(
				CachedResultTestSupport.priceIndex());
			computer.initialize(execution);
			assertTrue(execution.analyse(computer).compute().getOverallCount() > 0);
			assertEquals(1, statistics().refusedOversized());
			assertEquals(0, statistics().admissions());
			assertEquals(0, statistics().entries());
		}

		/** The attribute histogram computer is restored as the retained instance like the price one. */
		@Test
		@Tag(HISTOGRAM)
		@DisplayName("Returns the retained attribute histogram instance")
		void shouldRestoreAttributeHistogramInstance() {
			final QueryExecutionContext execution = context(null).createExecutionContext();
			final AttributeHistogramRequest request = CachedResultTestSupport.attributeHistogramRequest();
			final AttributeHistogramComputer first = CachedResultTestSupport.attributeHistogram(request);
			assertFalse(first.getKey().isQueryLocal(), "The attribute histogram must be eligible");
			final CacheableHistogramContract stored = computeHistogram(first, execution);
			final CacheableHistogramContract restored = computeHistogram(
				CachedResultTestSupport.attributeHistogram(request), execution);
			assertNotSame(CacheableHistogramContract.EMPTY, stored);
			assertSame(stored, restored);
			assertEquals(1, statistics().hits());
		}
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
			super(context, false, false, null, (type, entity) -> entity);
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
