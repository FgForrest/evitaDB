/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SLOW;

/**
 * This test contains various integration tests for {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@Tag(CONTRACT)
@Tag(QUERY)
class EvitaWarmUpInsertionTest implements EvitaTestSupport {
	public static final String THE_ENTITY = "theEntity";
	/**
	 * Number of records inserted in the initial bulk insertion phase.
	 */
	private static final int INITIAL_RECORD_COUNT = 10_000_000;
	/**
	 * Number of random remove/update operations performed in the churn phase that follows the initial insertion.
	 */
	private static final int CHURN_OPERATIONS = 10_000_000;
	/**
	 * Progress is reported to the standard output every this many operations.
	 */
	private static final int PROGRESS_STEP = 200_000;
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("EvitaWarmUpInsertionTest");
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Tag(SLOW)
	@Test
	void shouldGenerateLoadOfDataInWarmUpPhase() {
		this.evita.defineCatalog(TEST_CATALOG);

		final long start = System.nanoTime();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(THE_ENTITY)
					.withoutGeneratedPrimaryKey()
					.withAttribute("url", String.class, AttributeSchemaEditor::unique)
					.updateVia(session);

				// every (re)insert/update assigns a freshly minted unique url, so the global counter guarantees
				// uniqueness across both the insertion and the churn phase (the unique attribute index is exercised)
				final AtomicInteger urlSequence = new AtomicInteger();
				insertAndChurn(
					session,
					builder -> builder.setAttribute("url", "http://www.example.com/" + urlSequence.getAndIncrement())
				);

				session.goLiveAndClose();
			}
		);

		log.info("Set-up completed in: " + StringUtils.formatNano(System.nanoTime() - start));
	}

	@Tag(SLOW)
	@Test
	void shouldGenerateLoadOfRangeDataInWarmUpPhase() {
		this.evita.defineCatalog(TEST_CATALOG);

		final long start = System.nanoTime();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(THE_ENTITY)
					.withoutGeneratedPrimaryKey()
					.withAttribute("validity", IntegerNumberRange.class, AttributeSchemaEditor::filterable)
					.updateVia(session);

				// every (re)insert/update assigns a random range; overlapping ranges are allowed, so no uniqueness
				// is required - this exercises the range index (RangeIndex) add/remove paths
				final Random valueRandom = new Random(13);
				insertAndChurn(
					session,
					builder -> {
						final int from = valueRandom.nextInt(100_000_000);
						final int length = valueRandom.nextInt(10_000);
						builder.setAttribute("validity", IntegerNumberRange.between(from, from + length));
					}
				);

				session.goLiveAndClose();
			}
		);

		log.info("Set-up completed in: " + StringUtils.formatNano(System.nanoTime() - start));
	}

	@Tag(SLOW)
	@Test
	void shouldGenerateLoadOfChainDataInWarmUpPhase() {
		this.evita.defineCatalog(TEST_CATALOG);

		final long start = System.nanoTime();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(THE_ENTITY)
					.withoutGeneratedPrimaryKey()
					// a Predecessor-typed sortable attribute is what populates the ChainIndex, and therefore the
					// unordered-array write path (TransactionalUnorderedIntArray / UnorderedLookup) this test targets
					.withAttribute("order", Predecessor.class, AttributeSchemaEditor::sortable)
					.updateVia(session);

				insertAndChurnChain(session);

				session.goLiveAndClose();
			}
		);

		log.info("Set-up completed in: " + StringUtils.formatNano(System.nanoTime() - start));
	}

	/**
	 * Drives the two-phase warm-up workload shared by the data-load tests:
	 *
	 * 1. inserts {@link #INITIAL_RECORD_COUNT} records with primary keys `1..INITIAL_RECORD_COUNT`, and
	 * 2. performs {@link #CHURN_OPERATIONS} random remove/update operations over that record set.
	 *
	 * In the churn phase a random primary key is drawn each iteration: a live record is either removed (≈50 %) or
	 * updated with a fresh attribute value (≈50 %), while a previously removed record is re-inserted - this keeps the
	 * working set churning indefinitely without exhausting it. The churn uses a fixed seed so successive runs are
	 * comparable. The fresh attribute value for each (re)insert/update is applied by the supplied
	 * `attributeCustomizer` callback to a builder that this method prepares with the correct create/update semantics.
	 *
	 * Insertions and re-insertions of an absent primary key go through `createNewEntity` (insert semantics), whereas
	 * an update of a still-live record fetches the existing entity and opens it for write - using `createNewEntity`
	 * for an existing key would be rejected by the engine with an `InvalidMutationException`.
	 *
	 * @param session            the warm-up session to operate within (before `goLiveAndClose`)
	 * @param attributeCustomizer callback that sets a fresh attribute value on the supplied entity builder
	 */
	private static void insertAndChurn(
		@Nonnull EvitaSessionContract session,
		@Nonnull Consumer<EntityBuilder> attributeCustomizer
	) {
		// phase 1 - initial bulk insertion
		final long insertionStart = System.nanoTime();
		for (int i = 0; i < INITIAL_RECORD_COUNT; i++) {
			final EntityBuilder builder = session.createNewEntity(THE_ENTITY, i + 1);
			attributeCustomizer.accept(builder);
			builder.upsertVia(session);
			if (i % PROGRESS_STEP == 0) {
				System.out.println("Inserted: " + i);
			}
		}
		log.info(
			"Initial insertion of " + INITIAL_RECORD_COUNT + " records completed in: " +
				StringUtils.formatNano(System.nanoTime() - insertionStart)
		);

		// phase 2 - random remove/update churn
		final long churnStart = System.nanoTime();
		final Random random = new Random(42);
		// tracks which primary keys currently hold a live record (index 0 is unused, keys are 1-based)
		final boolean[] alive = new boolean[INITIAL_RECORD_COUNT + 1];
		Arrays.fill(alive, 1, INITIAL_RECORD_COUNT + 1, true);
		int aliveCount = INITIAL_RECORD_COUNT;
		for (int op = 0; op < CHURN_OPERATIONS; op++) {
			final int primaryKey = 1 + random.nextInt(INITIAL_RECORD_COUNT);
			if (alive[primaryKey] && random.nextBoolean()) {
				// remove a live record
				session.deleteEntity(THE_ENTITY, primaryKey);
				alive[primaryKey] = false;
				aliveCount--;
			} else if (alive[primaryKey]) {
				// update a live record - fetch the existing entity (with its attributes) and open it for write
				final EntityBuilder builder = session.getEntity(THE_ENTITY, primaryKey, attributeContentAll())
					.map(SealedEntity::openForWrite)
					.orElseThrow(() -> new IllegalStateException(
						"Live record with primary key " + primaryKey + " is unexpectedly missing!"));
				attributeCustomizer.accept(builder);
				builder.upsertVia(session);
			} else {
				// re-insert a previously removed record (insert semantics)
				final EntityBuilder builder = session.createNewEntity(THE_ENTITY, primaryKey);
				attributeCustomizer.accept(builder);
				builder.upsertVia(session);
				alive[primaryKey] = true;
				aliveCount++;
			}
			if (op % PROGRESS_STEP == 0) {
				System.out.println("Churn op: " + op + " (alive=" + aliveCount + ")");
			}
		}
		log.info(
			"Churn of " + CHURN_OPERATIONS + " random remove/update operations completed in: " +
				StringUtils.formatNano(System.nanoTime() - churnStart)
		);
	}

	/**
	 * Variant of {@link #insertAndChurn} that exercises the unordered-array write path behind the `ChainIndex`
	 * (`TransactionalUnorderedIntArray` / `UnorderedLookup`) rather than the inverted or range index.
	 *
	 * Phase 1 builds a single consistent chain of {@link #INITIAL_RECORD_COUNT} elements: record `1` is the chain
	 * `HEAD` and record `i` (for `i > 1`) is chained immediately after record `i - 1`. This drives one chain to full
	 * cardinality - the worst case for the array-backed unordered lookup, where every middle insert renumbers a
	 * suffix of positions.
	 *
	 * Phase 2 churns that chain with coherent local **moves** over a maintained doubly-linked order (fixed seed, so
	 * runs are comparable): each move relocates a random element after a random anchor (or to the `HEAD`) and is
	 * applied as the (up to) three affected predecessor updates - x's old successor, x itself, x's new successor -
	 * in the natural "detach-first" order. All records stay live, so the chain remains a single consistent run and
	 * never shatters into unbounded split subchains. This is the realistic reorder workload the `ChainIndex` is
	 * built for: every move perturbs only a constant number of neighbours and scales as `O(log N)`. (Purely-random
	 * permanent deletes would instead splinter the chain and stress the unrelated chain-collapse bookkeeping rather
	 * than the move path - see `ChainIndexChurnReproTest`.) Roughly {@link #CHURN_OPERATIONS} predecessor updates
	 * are emitted in total, preserving the previous write volume.
	 *
	 * Every update fetches the live entity via `getEntity(...).openForWrite()` (as in {@link #insertAndChurn}) and
	 * resets its `order` attribute.
	 *
	 * @param session the warm-up session to operate within (before `goLiveAndClose`)
	 */
	private static void insertAndChurnChain(@Nonnull EvitaSessionContract session) {
		// phase 1 - build a single chain 1..N (record i chained right after record i-1)
		final long insertionStart = System.nanoTime();
		for (int i = 0; i < INITIAL_RECORD_COUNT; i++) {
			final int primaryKey = i + 1;
			final EntityBuilder builder = session.createNewEntity(THE_ENTITY, primaryKey);
			builder.setAttribute("order", primaryKey == 1 ? Predecessor.HEAD : new Predecessor(primaryKey - 1));
			builder.upsertVia(session);
			if (i % PROGRESS_STEP == 0) {
				System.out.println("Inserted: " + i);
			}
		}
		log.info(
			"Initial insertion of " + INITIAL_RECORD_COUNT + " chained records completed in: " +
				StringUtils.formatNano(System.nanoTime() - insertionStart)
		);

		// phase 2 - coherent local moves over a maintained doubly-linked order: relocate a random element after a
		// random anchor (or to the HEAD), keeping every record live so the chain stays a single consistent run
		final long churnStart = System.nanoTime();
		final Random random = new Random(42);
		// maintained doubly-linked order of the (all-live) records: pred[pk]/succ[pk], 0 == HEAD / none
		final int[] pred = new int[INITIAL_RECORD_COUNT + 1];
		final int[] succ = new int[INITIAL_RECORD_COUNT + 1];
		for (int pk = 1; pk <= INITIAL_RECORD_COUNT; pk++) {
			pred[pk] = pk - 1;
			succ[pk] = pk == INITIAL_RECORD_COUNT ? 0 : pk + 1;
		}
		int head = 1;
		int opsApplied = 0;
		int nextProgress = 0;
		while (opsApplied < CHURN_OPERATIONS) {
			final int x = 1 + random.nextInt(INITIAL_RECORD_COUNT);
			// 10 % of moves promote the element to the chain head, otherwise relocate after a random anchor
			int anchor = random.nextInt(10) == 0 ? 0 : 1 + random.nextInt(INITIAL_RECORD_COUNT);
			if (anchor == x) {
				anchor = pred[x]; // avoid self-anchor; collapses to a no-op we skip below
			}
			if (anchor == pred[x]) {
				continue; // element already sits right after the anchor - nothing to do
			}

			final int pOld = pred[x];
			final int sOld = succ[x];
			// detach x from its current position
			if (pOld == 0) {
				head = sOld; // x was the head; its successor becomes the new head
			} else {
				succ[pOld] = sOld;
			}
			if (sOld != 0) {
				pred[sOld] = pOld;
			}
			// insert x right after the anchor (anchor == 0 means promote to head)
			final int sNew = anchor == 0 ? head : succ[anchor];
			if (anchor == 0) {
				head = x;
			} else {
				succ[anchor] = x;
			}
			pred[x] = anchor;
			succ[x] = sNew;
			if (sNew != 0) {
				pred[sNew] = x;
			}

			// apply the move as the (up to) three affected predecessor updates, in the natural "detach-first" order:
			// first reconnect x's old successor to x's old predecessor (so x stops dragging a suffix), then relocate
			// x, then attach x's new successor - keeping every single mutation a true local move
			if (sOld != 0 && sOld != x) {
				updateOrder(session, sOld, pOld == 0 ? Predecessor.HEAD : new Predecessor(pOld));
				opsApplied++;
			}
			updateOrder(session, x, anchor == 0 ? Predecessor.HEAD : new Predecessor(anchor));
			opsApplied++;
			if (sNew != 0 && sNew != x) {
				updateOrder(session, sNew, new Predecessor(x));
				opsApplied++;
			}

			if (opsApplied >= nextProgress) {
				System.out.println("Churn op: " + opsApplied);
				nextProgress += PROGRESS_STEP;
			}
		}
		log.info(
			"Churn of " + CHURN_OPERATIONS + " coherent move operations completed in: " +
				StringUtils.formatNano(System.nanoTime() - churnStart)
		);
	}

	/**
	 * Resets the `order` predecessor attribute of a still-live record. Fetches the existing entity (with its
	 * attributes) and opens it for write - using `createNewEntity` for an existing key would be rejected with an
	 * `InvalidMutationException`.
	 *
	 * @param session     the warm-up session to operate within
	 * @param primaryKey  primary key of the (live) record to update
	 * @param predecessor the new predecessor to set on the record's `order` attribute
	 */
	private static void updateOrder(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nonnull Predecessor predecessor
	) {
		final EntityBuilder builder = session.getEntity(THE_ENTITY, primaryKey, attributeContentAll())
			.map(SealedEntity::openForWrite)
			.orElseThrow(() -> new IllegalStateException(
				"Live record with primary key " + primaryKey + " is unexpectedly missing!"));
		builder.setAttribute("order", predecessor);
		builder.upsertVia(session);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return getEvitaConfiguration(-1);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(int inactivityTimeoutInSeconds) {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.serviceThreadPool(
						ThreadPoolOptions.serviceThreadPoolBuilder()
							.minThreadCount(1)
							.maxThreadCount(1)
							.queueSize(10_000)
							.build()
					)
					.closeSessionsAfterSecondsOfInactivity(inactivityTimeoutInSeconds)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.timeTravelEnabled(false)
					.fileSizeCompactionThresholdBytes(100_000_000)
					.minimalActiveRecordShare(0.8)
					.build()
			)
			.build();
	}

}
