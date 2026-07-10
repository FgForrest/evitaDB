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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * Each data-load scenario is parametrized over the {@link CatalogState} in which the churn phase is exercised:
 *
 * - {@link CatalogState#WARMING_UP}: the original bulk workload - both the initial insertion and the subsequent
 *   churn run single-threaded inside the warm-up session, before the catalog is taken live, and
 * - {@link CatalogState#ALIVE}: the initial bulk insertion still runs in the warm-up session, but the catalog is
 *   then taken live via `goLiveAndClose` and the whole churn phase is replayed through full ACID transactions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@DisplayName("Warm-up bulk insertion and churn load generation")
@Tag(CONTRACT)
@Tag(QUERY)
class EvitaWarmUpInsertionTest implements EvitaTestSupport {
	public static final String THE_ENTITY = "theEntity";
	/**
	 * Number of records inserted in the initial bulk insertion phase.
	 */
	private static final int INITIAL_RECORD_COUNT = 500_000;
	/**
	 * Number of random remove/update operations performed in the churn phase that follows the initial insertion.
	 */
	private static final int CHURN_OPERATIONS = 500_000;
	/**
	 * Progress is reported to the standard output every this many operations.
	 */
	private static final int PROGRESS_STEP = 200_000;
	/**
	 * Number of churn units (operations in the data/range tests, moves in the chain test) grouped into a single
	 * transaction when the churn phase runs in {@link CatalogState#ALIVE} mode. Tune this to trade transaction
	 * granularity against throughput.
	 */
	private static final int CHURN_TRANSACTION_BATCH_SIZE = 100;
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

	@DisplayName("Generate load over the unique-attribute index (insert + churn)")
	@Tag(SLOW)
	@ParameterizedTest(name = "catalog state: {0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	void shouldGenerateLoadOfDataInWarmUpPhase(@Nonnull CatalogState mode) {
		this.evita.defineCatalog(TEST_CATALOG);

		final long start = System.nanoTime();
		// every (re)insert/update assigns a freshly minted unique url, so the global counter guarantees
		// uniqueness across both the insertion and the churn phase (the unique attribute index is exercised)
		final AtomicInteger urlSequence = new AtomicInteger();
		insertAndChurn(
			mode,
			session -> session.defineEntitySchema(THE_ENTITY)
				.withoutGeneratedPrimaryKey()
				.withAttribute("url", String.class, AttributeSchemaEditor::unique)
				.updateVia(session),
			builder -> builder.setAttribute("url", "http://www.example.com/" + urlSequence.getAndIncrement())
		);

		log.info("Set-up completed in: " + StringUtils.formatNano(System.nanoTime() - start));
	}

	@DisplayName("Generate load over the range index (insert + churn)")
	@Tag(SLOW)
	@ParameterizedTest(name = "catalog state: {0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	void shouldGenerateLoadOfRangeDataInWarmUpPhase(@Nonnull CatalogState mode) {
		this.evita.defineCatalog(TEST_CATALOG);

		final long start = System.nanoTime();
		// every (re)insert/update assigns a random range; overlapping ranges are allowed, so no uniqueness
		// is required - this exercises the range index (RangeIndex) add/remove paths
		final Random valueRandom = new Random(13);
		insertAndChurn(
			mode,
			session -> session.defineEntitySchema(THE_ENTITY)
				.withoutGeneratedPrimaryKey()
				.withAttribute("validity", IntegerNumberRange.class, AttributeSchemaEditor::filterable)
				.updateVia(session),
			builder -> {
				final int from = valueRandom.nextInt(100_000_000);
				final int length = valueRandom.nextInt(10_000);
				builder.setAttribute("validity", IntegerNumberRange.between(from, from + length));
			}
		);

		log.info("Set-up completed in: " + StringUtils.formatNano(System.nanoTime() - start));
	}

	@DisplayName("Generate load over the chain index (insert + coherent move churn)")
	@Tag(SLOW)
	@ParameterizedTest(name = "catalog state: {0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	void shouldGenerateLoadOfChainDataInWarmUpPhase(@Nonnull CatalogState mode) {
		this.evita.defineCatalog(TEST_CATALOG);

		final long start = System.nanoTime();
		insertAndChurnChain(mode);

		log.info("Set-up completed in: " + StringUtils.formatNano(System.nanoTime() - start));
	}

	/**
	 * Drives the two-phase warm-up workload shared by the data-load tests, in the requested {@link CatalogState}:
	 *
	 * 1. defines the entity schema and inserts {@link #INITIAL_RECORD_COUNT} records with primary keys
	 *    `1..INITIAL_RECORD_COUNT` - this always happens single-threaded in the WARMING_UP warm-up session, and
	 * 2. performs {@link #CHURN_OPERATIONS} random remove/update operations over that record set.
	 *
	 * The churn phase is executed according to `mode`:
	 *
	 * - {@link CatalogState#WARMING_UP}: the churn runs inside the same warm-up session, before `goLiveAndClose` -
	 *   the original, non-transactional bulk-mode workload, and
	 * - {@link CatalogState#ALIVE}: the catalog is first taken live via `goLiveAndClose`, then each churn operation
	 *   is applied through its own transactional read-write session (full ACID, one transaction per operation).
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
	 * @param mode                catalog state in which the churn phase is exercised (WARMING_UP or ALIVE)
	 * @param schemaDefinition    callback that defines the entity schema within the warm-up session
	 * @param attributeCustomizer callback that sets a fresh attribute value on the supplied entity builder
	 */
	private void insertAndChurn(
		@Nonnull CatalogState mode,
		@Nonnull Consumer<EvitaSessionContract> schemaDefinition,
		@Nonnull Consumer<EntityBuilder> attributeCustomizer
	) {
		// phase 1 - schema definition and initial bulk insertion always run in the WARMING_UP warm-up session
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				schemaDefinition.accept(session);
				insertInitial(session, attributeCustomizer);
				if (mode == CatalogState.WARMING_UP) {
					// WARMING_UP mode: churn within the same warm-up session, then take the catalog live
					churn(operation -> operation.accept(session), attributeCustomizer);
				}
				session.goLiveAndClose();
			}
		);
		if (mode == CatalogState.ALIVE) {
			// ALIVE mode: churn the now-live catalog through individual transactional read-write sessions
			churn(transactionalExecutor(), attributeCustomizer);
		}
	}

	/**
	 * Phase 1 of {@link #insertAndChurn}: inserts {@link #INITIAL_RECORD_COUNT} records with primary keys
	 * `1..INITIAL_RECORD_COUNT` into the supplied warm-up session, applying `attributeCustomizer` to each builder.
	 *
	 * @param session             the WARMING_UP warm-up session to insert into
	 * @param attributeCustomizer callback that sets a fresh attribute value on each entity builder
	 */
	private static void insertInitial(
		@Nonnull EvitaSessionContract session,
		@Nonnull Consumer<EntityBuilder> attributeCustomizer
	) {
		final long insertionStart = System.nanoTime();
		long lastLogNano = insertionStart;
		for (int i = 0; i < INITIAL_RECORD_COUNT; i++) {
			final EntityBuilder builder = session.createNewEntity(THE_ENTITY, i + 1);
			attributeCustomizer.accept(builder);
			builder.upsertVia(session);
			if (i % PROGRESS_STEP == 0) {
				final long now = System.nanoTime();
				System.out.println("Inserted: " + i + " (+" + StringUtils.formatNano(now - lastLogNano) + " since last log)");
				lastLogNano = now;
			}
		}
		log.info(
			"Initial insertion of " + INITIAL_RECORD_COUNT + " records completed in: " +
				StringUtils.formatNano(System.nanoTime() - insertionStart)
		);
	}

	/**
	 * Phase 2 of {@link #insertAndChurn}: performs {@link #CHURN_OPERATIONS} random remove/update/re-insert
	 * operations over the record set, each applied through the supplied {@link ChurnExecutor} (which decides whether
	 * the operation runs in the shared warm-up session or in its own transaction).
	 *
	 * @param executor            executor that applies each churn operation in the appropriate session/transaction
	 * @param attributeCustomizer callback that sets a fresh attribute value on each (re)inserted/updated builder
	 */
	private static void churn(
		@Nonnull ChurnExecutor executor,
		@Nonnull Consumer<EntityBuilder> attributeCustomizer
	) {
		final long churnStart = System.nanoTime();
		long lastLogNano = churnStart;
		final Random random = new Random(42);
		// tracks which primary keys currently hold a live record (index 0 is unused, keys are 1-based)
		final boolean[] alive = new boolean[INITIAL_RECORD_COUNT + 1];
		Arrays.fill(alive, 1, INITIAL_RECORD_COUNT + 1, true);
		int aliveCount = INITIAL_RECORD_COUNT;
		for (int op = 0; op < CHURN_OPERATIONS; op++) {
			final int primaryKey = 1 + random.nextInt(INITIAL_RECORD_COUNT);
			if (alive[primaryKey] && random.nextBoolean()) {
				// remove a live record
				executor.run(session -> session.deleteEntity(THE_ENTITY, primaryKey));
				alive[primaryKey] = false;
				aliveCount--;
			} else if (alive[primaryKey]) {
				// update a live record - fetch the existing entity (with its attributes) and open it for write
				executor.run(session -> {
					final EntityBuilder builder = session.getEntity(THE_ENTITY, primaryKey, attributeContentAll())
						.map(SealedEntity::openForWrite)
						.orElseThrow(() -> new IllegalStateException(
							"Live record with primary key " + primaryKey + " is unexpectedly missing!"));
					attributeCustomizer.accept(builder);
					builder.upsertVia(session);
				});
			} else {
				// re-insert a previously removed record (insert semantics)
				executor.run(session -> {
					final EntityBuilder builder = session.createNewEntity(THE_ENTITY, primaryKey);
					attributeCustomizer.accept(builder);
					builder.upsertVia(session);
				});
				alive[primaryKey] = true;
				aliveCount++;
			}
			if (op % PROGRESS_STEP == 0) {
				final long now = System.nanoTime();
				System.out.println(
					"Churn op: " + op + " (alive=" + aliveCount + ", +" +
						StringUtils.formatNano(now - lastLogNano) + " since last log)"
				);
				lastLogNano = now;
			}
		}
		// commit any units left in a partially-filled final batch (no-op for the WARMING_UP shared-session executor)
		executor.flush();
		log.info(
			"Churn of " + CHURN_OPERATIONS + " random remove/update operations completed in: " +
				StringUtils.formatNano(System.nanoTime() - churnStart)
		);
	}

	/**
	 * Variant of {@link #insertAndChurn} that exercises the unordered-array write path behind the `ChainIndex`
	 * (`TransactionalUnorderedIntArray` / `UnorderedLookup`) rather than the inverted or range index, in the
	 * requested {@link CatalogState}.
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
	 * The churn phase is executed according to `mode`:
	 *
	 * - {@link CatalogState#WARMING_UP}: the moves run inside the warm-up session, before `goLiveAndClose`, and
	 * - {@link CatalogState#ALIVE}: the catalog is first taken live via `goLiveAndClose`, then each move (its
	 *   up-to-three predecessor updates) is committed atomically as a single transaction against the live catalog.
	 *
	 * @param mode catalog state in which the churn phase is exercised (WARMING_UP or ALIVE)
	 */
	private void insertAndChurnChain(@Nonnull CatalogState mode) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(THE_ENTITY)
					.withoutGeneratedPrimaryKey()
					// a Predecessor-typed sortable attribute is what populates the ChainIndex, and therefore the
					// unordered-array write path (TransactionalUnorderedIntArray / UnorderedLookup) this test targets
					.withAttribute("order", Predecessor.class, AttributeSchemaEditor::sortable)
					.updateVia(session);
				insertInitialChain(session);
				if (mode == CatalogState.WARMING_UP) {
					// WARMING_UP mode: churn within the same warm-up session, then take the catalog live
					churnChain(operation -> operation.accept(session));
				}
				session.goLiveAndClose();
			}
		);
		if (mode == CatalogState.ALIVE) {
			// ALIVE mode: churn the now-live catalog, one transaction per move
			churnChain(transactionalExecutor());
		}
	}

	/**
	 * Phase 1 of {@link #insertAndChurnChain}: builds a single chain `1..INITIAL_RECORD_COUNT` (record `i` chained
	 * right after record `i - 1`) within the supplied warm-up session.
	 *
	 * @param session the WARMING_UP warm-up session to insert into
	 */
	private static void insertInitialChain(@Nonnull EvitaSessionContract session) {
		final long insertionStart = System.nanoTime();
		long lastLogNano = insertionStart;
		for (int i = 0; i < INITIAL_RECORD_COUNT; i++) {
			final int primaryKey = i + 1;
			final EntityBuilder builder = session.createNewEntity(THE_ENTITY, primaryKey);
			builder.setAttribute("order", primaryKey == 1 ? Predecessor.HEAD : new Predecessor(primaryKey - 1));
			builder.upsertVia(session);
			if (i % PROGRESS_STEP == 0) {
				final long now = System.nanoTime();
				System.out.println("Inserted: " + i + " (+" + StringUtils.formatNano(now - lastLogNano) + " since last log)");
				lastLogNano = now;
			}
		}
		log.info(
			"Initial insertion of " + INITIAL_RECORD_COUNT + " chained records completed in: " +
				StringUtils.formatNano(System.nanoTime() - insertionStart)
		);
	}

	/**
	 * Phase 2 of {@link #insertAndChurnChain}: churns the chain with coherent local moves over a maintained
	 * doubly-linked order (fixed seed). Each move relocates a random element after a random anchor (or to the
	 * `HEAD`) and is applied through the supplied {@link ChurnExecutor} as the (up to) three affected predecessor
	 * updates. In {@link CatalogState#ALIVE} all updates of a single move are committed together as one transaction.
	 *
	 * @param executor executor that applies each move (its up-to-three updates) in the appropriate session/transaction
	 */
	private static void churnChain(@Nonnull ChurnExecutor executor) {
		final long churnStart = System.nanoTime();
		long lastLogNano = churnStart;
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
			// x, then attach x's new successor - keeping every single mutation a true local move. In ALIVE mode all
			// updates for one move are committed together as a single transaction
			final int anchorForUpdate = anchor;
			final boolean updateOldSuccessor = sOld != 0 && sOld != x;
			final boolean updateNewSuccessor = sNew != 0 && sNew != x;
			executor.run(session -> {
				if (updateOldSuccessor) {
					updateOrder(session, sOld, pOld == 0 ? Predecessor.HEAD : new Predecessor(pOld));
				}
				updateOrder(session, x, anchorForUpdate == 0 ? Predecessor.HEAD : new Predecessor(anchorForUpdate));
				if (updateNewSuccessor) {
					updateOrder(session, sNew, new Predecessor(x));
				}
			});
			opsApplied += 1 + (updateOldSuccessor ? 1 : 0) + (updateNewSuccessor ? 1 : 0);

			if (opsApplied >= nextProgress) {
				final long now = System.nanoTime();
				System.out.println(
					"Churn op: " + opsApplied + " (+" + StringUtils.formatNano(now - lastLogNano) + " since last log)"
				);
				lastLogNano = now;
				nextProgress += PROGRESS_STEP;
			}
		}
		// commit any moves left in a partially-filled final batch (no-op for the WARMING_UP shared-session executor)
		executor.flush();
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

	/**
	 * Returns a {@link ChurnExecutor} that groups churn units into transactions of {@link #CHURN_TRANSACTION_BATCH_SIZE}
	 * and applies each batch in a single transactional read-write session against the (already live)
	 * {@link #TEST_CATALOG}. Units are buffered and replayed in submission order, so a unit always observes the writes
	 * of earlier units in the same batch; the session is committed when the batch fills up or {@link ChurnExecutor#flush()}
	 * drains the remainder (try-with-resources). With a batch size of `1` this degrades to one ACID transaction per unit.
	 *
	 * @return a transactional, batching churn executor
	 */
	@Nonnull
	private ChurnExecutor transactionalExecutor() {
		return new ChurnExecutor() {
			/** pending churn units not yet committed (drained once the batch fills up or on flush) */
			private final List<Consumer<EvitaSessionContract>> pending = new ArrayList<>(CHURN_TRANSACTION_BATCH_SIZE);

			@Override
			public void run(@Nonnull Consumer<EvitaSessionContract> unit) {
				this.pending.add(unit);
				if (this.pending.size() >= CHURN_TRANSACTION_BATCH_SIZE) {
					flush();
				}
			}

			@Override
			public void flush() {
				if (this.pending.isEmpty()) {
					return;
				}
				// replay the whole batch in submission order within a single transaction, then commit on close
				try (final EvitaSessionContract session = EvitaWarmUpInsertionTest.this.evita.createReadWriteSession(TEST_CATALOG)) {
					for (int i = 0; i < this.pending.size(); i++) {
						this.pending.get(i).accept(session);
					}
				}
				this.pending.clear();
			}
		};
	}

	/**
	 * Applies a single logical churn unit (one or more mutations that must be applied atomically) against a session
	 * supplied by the executor. In {@link CatalogState#WARMING_UP} the unit runs against the shared warm-up session;
	 * in {@link CatalogState#ALIVE} the executor buffers units and commits them in batches of
	 * {@link #CHURN_TRANSACTION_BATCH_SIZE} (see {@link #transactionalExecutor()}). Callers must invoke {@link #flush()}
	 * once the churn loop finishes to commit any partially-filled final batch.
	 */
	@FunctionalInterface
	private interface ChurnExecutor {
		/**
		 * Runs the given churn unit against an executor-provided session (possibly deferred until the batch is flushed).
		 *
		 * @param unit the mutations to apply (atomically) within a single session/transaction
		 */
		void run(@Nonnull Consumer<EvitaSessionContract> unit);

		/**
		 * Commits any buffered units that have not yet been flushed. A no-op for executors that apply units eagerly
		 * (such as the WARMING_UP shared-session executor).
		 */
		default void flush() {
			// executors that apply units eagerly have nothing to flush
		}
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
