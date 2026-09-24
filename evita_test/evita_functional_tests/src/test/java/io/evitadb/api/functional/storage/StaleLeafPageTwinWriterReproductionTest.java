/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.functional.storage;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.ConcurrentSessionAccessException;
import io.evitadb.api.exception.MandatoryAttributesNotProvidedException;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bPlusTree.BucketBPlusTree;
import io.evitadb.index.bPlusTree.PagedLeafHandle;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Writer-side reproduction harness for a stale leaf-page twin corruption: two on-disk leaf pages of a persisted
 * `PAGED` {@link io.evitadb.index.invertedIndex.InvertedIndex} bucket tree end up covering overlapping key
 * ranges because a frozen snapshot of a leaf is left reachable in the persisted leaf-page list right next to
 * the page that superseded it (see {@link io.evitadb.index.attribute.StaleLeafPageTwinReproductionTest} for the
 * load-side half of the reproduction and the production failure signatures the corruption causes downstream).
 *
 * CHECKSUM-ESTABLISHED FACTS the recipes below are aimed at, from the production incident that first surfaced
 * this corruption: the corrupted dataset was byte-identical to the backup taken right after a full reindex — a
 * fresh catalog populated in ONE `WARM_UP` session and then flipped ALIVE. The twin was therefore written by
 * the single warm-up flush at `goLiveAndClose`; transactional commits and savepoints are exonerated. The
 * in-memory bucket tree already contained BOTH twin leaves at flush time: a frozen 128-bucket leaf (exactly a
 * split half captured at the split moment) and, adjacent to it, a 190-bucket leaf whose first 128 buckets are
 * identical — two DISTINCT node instances (the persisted page list is strictly distinct). The insert stream
 * was NEAR-monotonic `OffsetDateTime`s with jitter (the observed leaf sizes 127/128/186/190/235 prove
 * middle-leaf splits and borrows, i.e. out-of-order inserts and removals, not pure appends).
 *
 * The primary recipes therefore drive the NON-TRANSACTIONAL (warm-up) mutation path of the
 * {@link io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree}:
 *
 * 1. a deterministic single-threaded warm-up stream (jittered near-monotonic values, re-publishes =
 *    remove+insert, deletions = leaf borrows/merges) swept across several seeds, with the IN-MEMORY tree
 *    scanned periodically and right before `goLive` — the corruption exists in memory before the flush,
 * 2. the top race hypothesis: SEVERAL THREADS upserting concurrently over ONE shared warm-up session
 *    (the session is only contractually non-thread-safe; nothing serializes the invocation path, so a
 *    racing leaf split can leave a stale clone of a split half reachable in the spine — the exact twin
 *    anatomy). Monotonic values concentrate every thread on the rightmost leaf to maximize the odds of
 *    two threads splitting the same full leaf.
 *
 * One transactional skip-on-fail churn variant is kept as a CONTROL only (that path is exonerated).
 *
 * The oracle asserts, in memory before the flush and on the reloaded catalog after it, the fundamental
 * invariant whose violation IS the corruption: bucket keys iterate strictly ascending and match a
 * reference model exactly (a stale twin surfaces as a duplicated key run), no two live leaves share a
 * persistence page sequence, and (post-reload) every model value resolves via a real query. Checked on
 * the GLOBAL index and the REDUCED index (the production twin lived in a reduced index, which receives a
 * filtered subsequence of the stream).
 *
 * While these tests pass, the corruption is NOT reproduced by the exercised recipes; a failure of any
 * assertion here is a reproduction of the writer-side incident and must be preserved verbatim.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(FILTER)
@DisplayName("Stale leaf-page twin writer-side reproduction harness")
class StaleLeafPageTwinWriterReproductionTest implements EvitaTestSupport {

	private static final String ATTRIBUTE_PUBLISHED = "published";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String REFERENCE_CATEGORIES = "categories";
	/** The category ~60% of entities reference — its REDUCED index receives a filtered subsequence. */
	private static final int CATEGORY_PK = 1;
	/** Deterministic base seed; the single-threaded warm-up recipe sweeps SEED..SEED+2. */
	private static final long SEED = 20260714L;
	/** Base timestamp of the `published` value stream (concrete value irrelevant). */
	private static final OffsetDateTime BASE =
		OffsetDateTime.of(2026, 7, 13, 11, 52, 31, 0, ZoneOffset.UTC);

	private TestPaths paths;
	private Evita evita;
	/** Reference model: live entity primary key → its current `published` value (values MAY collide). */
	private final TreeMap<Integer, OffsetDateTime> publishedByPk = new TreeMap<>();
	/** Reference model: primary keys referencing the category (⊆ {@link #publishedByPk} keys). */
	private final Set<Integer> pksInCategory = new HashSet<>();
	/** Next primary key to assign. */
	private int nextPk = 1;
	/** Monotonic position of the value stream (thread-safe for the concurrent recipe). */
	private final AtomicLong streamPosition = new AtomicLong(0);

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("StaleLeafPageTwinWriter");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterEach
	@Timeout(value = 90, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/*
		PRIMARY RECIPE 1 — deterministic single warm-up session: jittered stream, re-publishes, deletions
	 */

	@Test
	@DisplayName("a single warm-up session with a jittered re-publish stream writes a sound PAGED index")
	void shouldSurviveSingleWarmUpSessionWithJitteredRepublishStream() {
		for (long seed = SEED; seed < SEED + 3; seed++) {
			resetForNextRun();
			final Random random = new Random(seed);
			final String seedPhase = "warm-up seed " + seed;
			this.evita.defineCatalog(TEST_CATALOG);
			this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					defineSchema(session);
					// ~7000 ops in ONE warm-up session: jittered near-monotonic inserts (middle-leaf splits),
					// re-publishes (remove+insert — the production stream's shape), deletions (borrows/merges;
					// the observed 127-bucket leaf proves removals ran during warm-up). All mutations hit the
					// direct (non-transactional) B+ tree branches; the flush happens once, at goLiveAndClose.
					for (int op = 1; op <= 7_000; op++) {
						final int dice = random.nextInt(100);
						if (dice < 78 || this.publishedByPk.size() < 300) {
							upsertNewEntity(session, jitteredValue(random), random.nextInt(100) < 60);
						} else if (dice < 93) {
							republishEntity(session, pickRandomPk(random), jitteredValue(random));
						} else {
							deleteEntity(session, pickRandomPk(random));
						}
						if (op % 1_000 == 0) {
							// the corruption exists IN MEMORY before the flush — scan the live tree mid-stream
							assertIndexesSound(seedPhase + ", in-memory after op " + op);
						}
					}
					// the decisive scan: the in-memory tree right before the one-and-only flush
					assertIndexesSound(seedPhase + ", in-memory right before goLive");
					session.goLiveAndClose();
				}
			);
			assertIndexesSound(seedPhase + ", after goLive flush");
			assertTrue(
				publishedFilterIndex(globalIndexKey()).getInvertedIndex().isPaged(),
				seedInfo(seedPhase) + " the global `published` index is not PAGED — the recipe missed its target!"
			);
			reopenEvita();
			assertIndexesSound(seedPhase + ", after cold reload");
			assertModelResolvesViaQueries(seedPhase);
		}
	}

	/*
		PRIMARY RECIPE 2 — the race hypothesis: concurrent upserts over ONE shared warm-up session
	 */

	@Test
	@Timeout(value = 300, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
	@DisplayName("concurrent upserts over one shared warm-up session write a sound PAGED index")
	void shouldSurviveConcurrentUpsertsOnSingleWarmUpSession() {
		final int threadCount = 8;
		final int upsertsPerThread = 1_500;
		final int attempts = 3;
		for (int attempt = 1; attempt <= attempts; attempt++) {
			resetForNextRun();
			final String attemptPhase = "concurrent warm-up attempt " + attempt;
			final AtomicInteger raceExceptions = new AtomicInteger(0);
			final List<Throwable> sampleExceptions = Collections.synchronizedList(new ArrayList<>());
			final List<TreeMap<Integer, OffsetDateTime>> threadModels =
				Collections.synchronizedList(new ArrayList<>());
			final List<Set<Integer>> threadCategoryPks = Collections.synchronizedList(new ArrayList<>());
			// primary keys whose upsert THREW: warm-up documents a failed entity as partially applied, so
			// buckets carrying (only) these records are tolerated noise — the fatal signals stay ordering
			// violations, duplicated page sequences and silently LOST successful upserts
			final Set<Integer> toleratedPks = Collections.synchronizedSet(new HashSet<>());
			this.evita.defineCatalog(TEST_CATALOG);
			// a RAW long-lived read-write session (production full-reindex shape): a per-upsert failure on a
			// worker thread must not poison the session close the way the updateCatalog future join does
			final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG);
			boolean wentLive = false;
			try {
				defineSchema(session);
				// N threads share THE SAME warm-up session — the production full-reindex shape under
				// suspicion. Values are globally monotonic with a jittered minority, so every thread
				// hammers the rightmost leaf and two threads splitting the same full leaf is likely.
				final List<Thread> threads = new ArrayList<>(threadCount);
				for (int t = 0; t < threadCount; t++) {
					final Thread thread = new Thread(
						warmUpWriter(
							session, t, upsertsPerThread, 0L,
							raceExceptions, sampleExceptions, threadModels, threadCategoryPks, toleratedPks
						),
						"twin-warmup-" + t
					);
					threads.add(thread);
					thread.start();
				}
				for (final Thread thread : threads) {
					try {
						thread.join(180_000);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						fail(seedInfo(attemptPhase) + " interrupted while joining upsert threads");
					}
					assertFalse(thread.isAlive(), seedInfo(attemptPhase) + " thread " + thread.getName() + " hung!");
				}
				for (final TreeMap<Integer, OffsetDateTime> localModel : threadModels) {
					this.publishedByPk.putAll(localModel);
				}
				for (final Set<Integer> localCategoryPks : threadCategoryPks) {
					this.pksInCategory.addAll(localCategoryPks);
				}
				// post-fix oracle: every racing call must have been rejected LOUDLY with a
				// ConcurrentSessionAccessException — the concurrency guard never lets a second thread silently
				// race the shared session state. Any other exception would signal a corruption leak.
				assertConcurrentAccessRejectedLoudly(sampleExceptions, attemptPhase);
				// the decisive scan: the in-memory tree right before the one-and-only flush
				assertIndexesSound(
					attemptPhase + ", in-memory right before goLive (raceExceptions=" +
						raceExceptions.get() + firstSample(sampleExceptions) + ")",
					toleratedPks
				);
				try {
					session.goLiveAndClose();
					wentLive = true;
				} catch (RuntimeException goLiveFailure) {
					// the go-live flush choked on race residue — a loud (not silent) outcome; the in-memory
					// oracle above already ruled on the twin, so record and continue with the next attempt
					raceExceptions.incrementAndGet();
					if (sampleExceptions.size() < 5) {
						sampleExceptions.add(goLiveFailure);
					}
				}
			} finally {
				if (session.isActive()) {
					try {
						session.close();
					} catch (RuntimeException closeFailure) {
						// the close-time warm-up flush choked on race residue (e.g. a SortIndex whose sorted
						// array diverged from its value structures) — a loud, non-twin outcome; sample it and
						// let the next attempt run on a freshly dropped catalog
						raceExceptions.incrementAndGet();
						if (sampleExceptions.size() < 5) {
							sampleExceptions.add(closeFailure);
						}
					}
				}
			}
			if (wentLive) {
				assertIndexesSound(attemptPhase + ", after goLive flush", toleratedPks);
				reopenEvita();
				assertIndexesSound(
					attemptPhase + ", after cold reload (raceExceptions=" + raceExceptions.get() +
						firstSample(sampleExceptions) + ")",
					toleratedPks
				);
				assertModelResolvesViaQueries(attemptPhase, toleratedPks);
			}
		}
	}

	/*
		PRIMARY RECIPE 3 — production-shaped RARE overlap: one main writer + an occasional interferer
	 */

	@Test
	@Timeout(value = 300, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
	@DisplayName("split-aimed overlapping upserts over one shared warm-up session write a sound PAGED index")
	void shouldSurviveSplitAimedOverlappingUpsertsOnSingleWarmUpSession() {
		// The full-contention variant degrades into a loud cascade almost immediately — but the production
		// reindex COMPLETED, so its overlap (if any) must have been rare AND lucky. This variant spends its
		// overlap budget exclusively on the event that can mint the twin: an interferer SNIPES the split window
		// — it polls the rightmost-leaf fill and fires a single racing monotonic insert only when the leaf is
		// about to split (fill ≥ 254), so two threads execute splitLeafNode/adaptToLeafSplit on the same full
		// leaf. A stale clone of a split half left reachable in the spine is exactly the twin anatomy this
		// harness targets.
		final int attempts = 3;
		for (int attempt = 1; attempt <= attempts; attempt++) {
			resetForNextRun();
			final String attemptPhase = "split-aimed overlap warm-up attempt " + attempt;
			final AtomicInteger raceExceptions = new AtomicInteger(0);
			final List<Throwable> sampleExceptions = Collections.synchronizedList(new ArrayList<>());
			final List<TreeMap<Integer, OffsetDateTime>> threadModels =
				Collections.synchronizedList(new ArrayList<>());
			final List<Set<Integer>> threadCategoryPks = Collections.synchronizedList(new ArrayList<>());
			final Set<Integer> toleratedPks = Collections.synchronizedSet(new HashSet<>());
			this.evita.defineCatalog(TEST_CATALOG);
			final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG);
			boolean wentLive = false;
			try {
				defineSchema(session, false);
				final java.util.concurrent.atomic.AtomicBoolean writerRunning =
					new java.util.concurrent.atomic.AtomicBoolean(true);
				final Runnable writerLoop = warmUpWriter(
					session, 0, 12_000, 0L,
					raceExceptions, sampleExceptions, threadModels, threadCategoryPks, toleratedPks
				);
				final Thread writer = new Thread(
					() -> {
						try {
							writerLoop.run();
						} finally {
							writerRunning.set(false);
						}
					},
					"twin-warmup-writer"
				);
				final Thread interferer = new Thread(
					splitSniper(
						session, writerRunning,
						raceExceptions, sampleExceptions, threadModels, threadCategoryPks, toleratedPks
					),
					"twin-warmup-split-sniper"
				);
				writer.start();
				interferer.start();
				for (final Thread thread : List.of(writer, interferer)) {
					try {
						thread.join(180_000);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						fail(seedInfo(attemptPhase) + " interrupted while joining upsert threads");
					}
					assertFalse(thread.isAlive(), seedInfo(attemptPhase) + " thread " + thread.getName() + " hung!");
				}
				for (final TreeMap<Integer, OffsetDateTime> localModel : threadModels) {
					this.publishedByPk.putAll(localModel);
				}
				for (final Set<Integer> localCategoryPks : threadCategoryPks) {
					this.pksInCategory.addAll(localCategoryPks);
				}
				// post-fix oracle: every racing call must have been rejected LOUDLY with a
				// ConcurrentSessionAccessException — the concurrency guard never lets a second thread silently
				// race the shared session state. Any other exception would signal a corruption leak.
				assertConcurrentAccessRejectedLoudly(sampleExceptions, attemptPhase);
				// the decisive scan: the in-memory tree right before the one-and-only flush
				assertIndexesSound(
					attemptPhase + ", in-memory right before goLive (raceExceptions=" +
						raceExceptions.get() + firstSample(sampleExceptions) + ")",
					toleratedPks
				);
				try {
					session.goLiveAndClose();
					wentLive = true;
				} catch (RuntimeException goLiveFailure) {
					raceExceptions.incrementAndGet();
					if (sampleExceptions.size() < 5) {
						sampleExceptions.add(goLiveFailure);
					}
				}
			} finally {
				if (session.isActive()) {
					try {
						session.close();
					} catch (RuntimeException closeFailure) {
						raceExceptions.incrementAndGet();
						if (sampleExceptions.size() < 5) {
							sampleExceptions.add(closeFailure);
						}
					}
				}
			}
			if (wentLive) {
				assertIndexesSound(attemptPhase + ", after goLive flush", toleratedPks);
				reopenEvita();
				assertIndexesSound(
					attemptPhase + ", after cold reload (raceExceptions=" + raceExceptions.get() +
						firstSample(sampleExceptions) + ")",
					toleratedPks
				);
				assertModelResolvesViaQueries(attemptPhase, toleratedPks);
			}
		}
	}

	/**
	 * Builds the split-window sniper for the split-aimed overlap recipe: it polls the GLOBAL tree's
	 * rightmost-leaf fill (tolerating torn reads of the racing tree) and fires ONE racing monotonic insert
	 * whenever the leaf is about to split, so both threads run the split machinery on the same full leaf.
	 *
	 * @param session          the SHARED warm-up session
	 * @param writerRunning    cleared by the main writer when it finishes (stops the sniper)
	 * @param raceExceptions   shared counter of throwing upserts
	 * @param sampleExceptions shared sample of the first few thrown exceptions
	 * @param modelsOut        sink for the sniper's pk → value model
	 * @param categoryPksOut   sink for the sniper's category-referencing pks
	 * @param toleratedPks     shared sink for pks of throwing upserts
	 * @return the sniper loop
	 */
	@Nonnull
	private Runnable splitSniper(
		@Nonnull EvitaSessionContract session,
		@Nonnull java.util.concurrent.atomic.AtomicBoolean writerRunning,
		@Nonnull AtomicInteger raceExceptions,
		@Nonnull List<Throwable> sampleExceptions,
		@Nonnull List<TreeMap<Integer, OffsetDateTime>> modelsOut,
		@Nonnull List<Set<Integer>> categoryPksOut,
		@Nonnull Set<Integer> toleratedPks
	) {
		return () -> {
			final TreeMap<Integer, OffsetDateTime> localModel = new TreeMap<>();
			final Set<Integer> localCategoryPks = new HashSet<>();
			int pkCursor = 2_000_000;
			int fired = 0;
			final long deadline = System.nanoTime() + 60_000_000_000L;
			while (fired < 60 && writerRunning.get() && System.nanoTime() < deadline) {
				final int fill = probeRightmostLeafFillQuietly();
				if (fill >= 254) {
					final int pk = pkCursor++;
					final OffsetDateTime value = monotonicValue();
					try {
						upsertConcurrently(session, pk, value, true);
						localModel.put(pk, value);
						localCategoryPks.add(pk);
					} catch (RuntimeException raceCasualty) {
						raceExceptions.incrementAndGet();
						toleratedPks.add(pk);
						if (sampleExceptions.size() < 5) {
							sampleExceptions.add(raceCasualty);
						}
					}
					fired++;
				} else {
					Thread.onSpinWait();
				}
			}
			modelsOut.add(localModel);
			categoryPksOut.add(localCategoryPks);
		};
	}

	/**
	 * Concurrent-safe wrapper around the rightmost-leaf fill probe: the sniper reads the tree WHILE the writer
	 * mutates it, so any exception from the torn read is swallowed and reported as "unknown".
	 *
	 * @return the rightmost-leaf bucket count, or `-1` when unreadable
	 */
	private int probeRightmostLeafFillQuietly() {
		try {
			final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollection collection =
				(EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
			final EntityIndex index = collection.getIndexByKeyIfExists(globalIndexKey());
			if (index == null) {
				return -1;
			}
			final FilterIndex filterIndex =
				index.getFilterIndex(new AttributeIndexKey(null, ATTRIBUTE_PUBLISHED, null));
			if (filterIndex == null) {
				return -1;
			}
			final List<? extends PagedLeafHandle> handles = leafHandles(filterIndex.getInvertedIndex());
			if (handles == null || handles.isEmpty()) {
				return -1;
			}
			final PagedLeafHandle last = handles.get(handles.size() - 1);
			if (!(last instanceof io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.LeafPageHandle<?> leafHandle)) {
				return -1;
			}
			int count = 0;
			final io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor<?> cursor =
				leafHandle.cursor();
			while (cursor.next()) {
				count++;
			}
			return count;
		} catch (RuntimeException | Error tornRead) {
			return -1;
		}
	}

	/**
	 * Builds one warm-up upsert loop for the concurrent recipes: `upserts` inserts over the SHARED session with
	 * a thread-private pk range and the shared globally monotonic (30% jittered) value stream, optionally paced
	 * by a sleep between operations. Successful upserts land in a thread-local model (merged by the caller
	 * after joining); throwing upserts are counted, sampled and their pks marked tolerated (warm-up documents a
	 * failed entity as partially applied).
	 *
	 * @param session          the SHARED warm-up session
	 * @param threadNo         the thread ordinal (selects the pk range and the random seed)
	 * @param upserts          the number of upserts to issue
	 * @param sleepMillis      the pause between operations (0 = full speed)
	 * @param raceExceptions   shared counter of throwing upserts
	 * @param sampleExceptions shared sample of the first few thrown exceptions
	 * @param modelsOut        sink for the thread-local pk → value model
	 * @param categoryPksOut   sink for the thread-local category-referencing pks
	 * @param toleratedPks     shared sink for pks of throwing upserts
	 * @return the upsert loop
	 */
	@Nonnull
	private Runnable warmUpWriter(
		@Nonnull EvitaSessionContract session,
		int threadNo,
		int upserts,
		long sleepMillis,
		@Nonnull AtomicInteger raceExceptions,
		@Nonnull List<Throwable> sampleExceptions,
		@Nonnull List<TreeMap<Integer, OffsetDateTime>> modelsOut,
		@Nonnull List<Set<Integer>> categoryPksOut,
		@Nonnull Set<Integer> toleratedPks
	) {
		return () -> {
			final Random random = new Random(SEED + threadNo);
			final TreeMap<Integer, OffsetDateTime> localModel = new TreeMap<>();
			final Set<Integer> localCategoryPks = new HashSet<>();
			// disjoint per-thread pk ranges; the CONTENTION is on the shared index trees
			int pkCursor = 1_000_000 * (threadNo + 1);
			for (int op = 1; op <= upserts; op++) {
				final int pk = pkCursor++;
				final OffsetDateTime value = random.nextInt(100) < 70
					? monotonicValue()
					: jitteredValue(random);
				final boolean inCategory = random.nextInt(100) < 60;
				try {
					upsertConcurrently(session, pk, value, inCategory);
					localModel.put(pk, value);
					if (inCategory) {
						localCategoryPks.add(pk);
					}
				} catch (RuntimeException raceCasualty) {
					// an exception here is itself evidence of the unsynchronized-session race; it is counted
					// and sampled, but the reproduction target is the SILENT twin — the oracle decides
					raceExceptions.incrementAndGet();
					toleratedPks.add(pk);
					if (sampleExceptions.size() < 5) {
						sampleExceptions.add(raceCasualty);
					}
				}
				if (sleepMillis > 0) {
					try {
						Thread.sleep(sleepMillis);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			}
			modelsOut.add(localModel);
			categoryPksOut.add(localCategoryPks);
		};
	}

	/*
		CONTROL — transactional skip-on-fail churn (exonerated path, kept to guard the trunk replay)
	 */

	@Test
	@DisplayName("control: transactional skip-on-fail churn keeps every bucket tree sound")
	void shouldSurviveTransactionalSkipOnFailChurnControl() {
		resetForNextRun();
		final Random random = new Random(SEED);
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				defineSchema(session);
				for (int i = 0; i < 400; i++) {
					upsertNewEntity(session, monotonicValue(), true);
				}
				session.goLiveAndClose();
			}
		);
		assertIndexesSound("control warm-up");
		for (int commit = 1; commit <= 15; commit++) {
			this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					for (int op = 1; op <= 50; op++) {
						final int dice = random.nextInt(100);
						if (dice < 8) {
							failingInsert(session, monotonicValue());
						} else if (dice < 60) {
							upsertNewEntity(session, jitteredValue(random), true);
						} else {
							republishEntity(session, pickRandomPk(random), monotonicValue());
						}
					}
				},
				CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
			);
			assertIndexesSound("control commit " + commit);
		}
		reopenEvita();
		assertIndexesSound("control final reopen");
		assertModelResolvesViaQueries("control");
	}

	/*
		SCHEMA + RUN RESET
	 */

	/**
	 * Defines the production-shaped schema: both-flagged `published` OffsetDateTime attribute, mandatory
	 * `name`, indexed `categories` reference (⇒ a reduced index partitioned by the referenced category).
	 *
	 * @param session the open read-write session
	 */
	private void defineSchema(@Nonnull EvitaSessionContract session) {
		defineSchema(session, true);
	}

	/**
	 * Variant of {@link #defineSchema(EvitaSessionContract)} with a switchable `sortable` flag on `published`.
	 * The split-aimed concurrent recipe drops it: the SortIndex crashes loudly (insert- and flush-time) under
	 * races, drowning and blocking the SILENT twin this harness hunts; the twin lives in the (filterable)
	 * bucket tree either way.
	 *
	 * @param session           the open read-write session
	 * @param sortablePublished whether `published` is also sortable (the production attribute was both-flagged)
	 */
	private void defineSchema(@Nonnull EvitaSessionContract session, boolean sortablePublished) {
		session.defineEntitySchema(Entities.CATEGORY)
			.withoutGeneratedPrimaryKey()
			.updateVia(session);
		session.defineEntitySchema(Entities.PRODUCT)
			.withoutGeneratedPrimaryKey()
			// both-flagged, exactly like the production `published` attribute (the split-aimed recipe drops
			// `sortable`)
			.withAttribute(
				ATTRIBUTE_PUBLISHED, OffsetDateTime.class,
				whichIs -> {
					whichIs.filterable();
					if (sortablePublished) {
						whichIs.sortable();
					}
				}
			)
			// mandatory (non-nullable is the default) — the control variant omits it to force savepoint failures
			.withAttribute(ATTRIBUTE_NAME, String.class)
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
			)
			.updateVia(session);
		session.upsertEntity(session.createNewEntity(Entities.CATEGORY, CATEGORY_PK));
	}

	/**
	 * Clears the reference model and drops a possibly existing catalog so each seed / attempt starts from a
	 * genuinely fresh catalog (dropped through the API, never on disk).
	 */
	private void resetForNextRun() {
		this.publishedByPk.clear();
		this.pksInCategory.clear();
		this.nextPk = 1;
		this.streamPosition.set(0);
		this.evita.deleteCatalogIfExists(TEST_CATALOG);
	}

	/*
		VALUE ALLOCATION
	 */

	/**
	 * Returns the next strictly monotonic `published` value: {@link #BASE} + 50·position milliseconds.
	 *
	 * @return the next monotonic value
	 */
	@Nonnull
	private OffsetDateTime monotonicValue() {
		return BASE.plusNanos(50_000_000L * this.streamPosition.getAndIncrement());
	}

	/**
	 * Returns the next NEAR-monotonic value: the monotonic 50 ms grid step plus a ±2000 ms jitter — the
	 * production stream's shape. The jitter throws a minority of inserts back into already-built leaves
	 * (middle-leaf splits) and lets distinct entities collide on the same value (multi-record buckets).
	 *
	 * @param random the deterministic random source
	 * @return the next jittered value
	 */
	@Nonnull
	private OffsetDateTime jitteredValue(@Nonnull Random random) {
		final long position = this.streamPosition.getAndIncrement();
		final long jitterMillis = random.nextLong(4_001L) - 2_000L;
		return BASE.plusNanos(50_000_000L * position + 1_000_000L * jitterMillis);
	}

	/**
	 * Picks a random live primary key from the reference model.
	 *
	 * @param random the deterministic random source
	 * @return a live primary key
	 */
	private int pickRandomPk(@Nonnull Random random) {
		final int index = random.nextInt(this.publishedByPk.size());
		final Iterator<Integer> it = this.publishedByPk.keySet().iterator();
		for (int i = 0; i < index; i++) {
			it.next();
		}
		return it.next();
	}

	/*
		OPERATIONS
	 */

	/**
	 * Inserts a brand-new entity carrying the passed `published` value and the mandatory name; entities
	 * referencing the category feed the reduced index with a filtered subsequence. Updates the model.
	 *
	 * @param session    the open read-write session
	 * @param value      the `published` value to index
	 * @param inCategory whether the entity references the category
	 */
	private void upsertNewEntity(
		@Nonnull EvitaSessionContract session, @Nonnull OffsetDateTime value, boolean inCategory
	) {
		final int pk = this.nextPk++;
		final EntityBuilder builder = session.createNewEntity(Entities.PRODUCT, pk)
			.setAttribute(ATTRIBUTE_PUBLISHED, value)
			.setAttribute(ATTRIBUTE_NAME, "Product " + pk);
		if (inCategory) {
			builder.setReference(REFERENCE_CATEGORIES, CATEGORY_PK);
		}
		session.upsertEntity(builder);
		this.publishedByPk.put(pk, value);
		if (inCategory) {
			this.pksInCategory.add(pk);
		}
	}

	/**
	 * Concurrent-recipe insert: explicit primary key (thread-local cursor), NO shared-model mutation — the
	 * threads merge their local models after joining.
	 *
	 * @param session    the SHARED warm-up session
	 * @param pk         the thread-local primary key
	 * @param value      the `published` value to index
	 * @param inCategory whether the entity references the category
	 */
	private static void upsertConcurrently(
		@Nonnull EvitaSessionContract session, int pk, @Nonnull OffsetDateTime value, boolean inCategory
	) {
		final EntityBuilder builder = session.createNewEntity(Entities.PRODUCT, pk)
			.setAttribute(ATTRIBUTE_PUBLISHED, value)
			.setAttribute(ATTRIBUTE_NAME, "Product " + pk);
		if (inCategory) {
			builder.setReference(REFERENCE_CATEGORIES, CATEGORY_PK);
		}
		session.upsertEntity(builder);
	}

	/**
	 * Re-publishes an existing entity: its `published` value changes to the passed one (index-wise a bucket
	 * removal + a bucket insert — the production re-publish shape); updates the model.
	 *
	 * @param session the open read-write session
	 * @param pk      the primary key of the entity to re-publish
	 * @param value   the new `published` value
	 */
	private void republishEntity(@Nonnull EvitaSessionContract session, int pk, @Nonnull OffsetDateTime value) {
		final SealedEntity entity = session
			.getEntity(Entities.PRODUCT, pk, attributeContentAll(), referenceContentAll())
			.orElseThrow(() -> new AssertionError("Model entity " + pk + " unexpectedly missing!"));
		session.upsertEntity(
			entity.openForWrite().setAttribute(ATTRIBUTE_PUBLISHED, value)
		);
		this.publishedByPk.put(pk, value);
	}

	/**
	 * Deletes an existing entity (bucket removal ⇒ leaf underflow ⇒ borrow/merge); updates the model.
	 *
	 * @param session the open read-write session
	 * @param pk      the primary key of the entity to delete
	 */
	private void deleteEntity(@Nonnull EvitaSessionContract session, int pk) {
		session.deleteEntity(Entities.PRODUCT, pk);
		this.publishedByPk.remove(pk);
		this.pksInCategory.remove(pk);
	}

	/**
	 * Control-variant DELIBERATELY FAILING insert: omits the mandatory name so the validation throws AFTER the
	 * `published` index write and the per-entity savepoint rolls the entity back (skip-on-fail). Only used in
	 * the transactional control — during warm-up a failed entity is documented to stay partially applied, which
	 * would drown the twin signal in expected phantom keys.
	 *
	 * @param session the open read-write session
	 * @param value   the `published` value the failing entity briefly indexes
	 */
	private void failingInsert(@Nonnull EvitaSessionContract session, @Nonnull OffsetDateTime value) {
		final int pk = this.nextPk++;
		try {
			session.upsertEntity(
				session.createNewEntity(Entities.PRODUCT, pk)
					.setAttribute(ATTRIBUTE_PUBLISHED, value)
					.setReference(REFERENCE_CATEGORIES, CATEGORY_PK)
			);
			fail(seedInfo("control") + " failing insert of pk " + pk + " unexpectedly succeeded!");
		} catch (MandatoryAttributesNotProvidedException expected) {
			// the per-entity savepoint rolled the entity back — the batch continues (skip-on-fail)
		} catch (RuntimeException unexpected) {
			fail(
				seedInfo("control") + " failing insert of pk " + pk + " threw an unexpected exception — " +
					"a likely corruption signature: " + unexpected,
				unexpected
			);
		}
	}

	/*
		ORACLE
	 */

	/**
	 * The heart of the oracle: for the GLOBAL index and the REDUCED index, walks the live `published` bucket
	 * tree and asserts it matches the reference model bucket-for-bucket (strictly ascending distinct keys,
	 * exact record-id sets) and that no two live leaves share a persistence page sequence. Works both on the
	 * warm-up in-memory tree (page sequences still unassigned) and on the committed / reloaded tree.
	 *
	 * @param phase a human-readable description of the current phase (failure diagnostics)
	 */
	private void assertIndexesSound(@Nonnull String phase) {
		assertIndexesSound(phase, Collections.emptySet());
	}

	/**
	 * Tolerance-aware variant of {@link #assertIndexesSound(String)}: primary keys of upserts that visibly
	 * FAILED may linger partially applied (documented warm-up behavior), so buckets carrying only such records
	 * are tolerated. Ordering violations, duplicated page sequences and lost SUCCESSFUL upserts stay fatal.
	 *
	 * @param phase        a human-readable description of the current phase (failure diagnostics)
	 * @param toleratedPks primary keys whose partial index residue is tolerated
	 */
	private void assertIndexesSound(@Nonnull String phase, @Nonnull Set<Integer> toleratedPks) {
		assertIndexSound(globalIndexKey(), this.publishedByPk.keySet(), toleratedPks, "GLOBAL", phase);
		assertIndexSound(
			reducedIndexKey(), this.pksInCategory, toleratedPks,
			"REDUCED categories:" + CATEGORY_PK, phase
		);
	}

	/**
	 * Asserts a single entity index's `published` bucket tree matches the reference model restricted to the
	 * passed primary keys. A missing index (or filter index) is tolerated only while the restriction is empty.
	 *
	 * @param indexKey     the entity index key to check
	 * @param modelPks     the primary keys expected in this index (each contributes its model `published` value)
	 * @param toleratedPks primary keys whose partial index residue is tolerated (failed upserts)
	 * @param indexName    the index description (failure diagnostics)
	 * @param phase        the phase description (failure diagnostics)
	 */
	private void assertIndexSound(
		@Nonnull EntityIndexKey indexKey,
		@Nonnull Set<Integer> modelPks,
		@Nonnull Set<Integer> toleratedPks,
		@Nonnull String indexName,
		@Nonnull String phase
	) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection =
			(EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		final EntityIndex index = collection.getIndexByKeyIfExists(indexKey);
		if (index == null) {
			assertTrue(
				modelPks.isEmpty(),
				seedInfo(phase) + " index " + indexName + " missing although the model expects " +
					modelPks.size() + " value(s)!"
			);
			return;
		}
		final FilterIndex filterIndex =
			index.getFilterIndex(new AttributeIndexKey(null, ATTRIBUTE_PUBLISHED, null));
		if (filterIndex == null) {
			assertTrue(
				modelPks.isEmpty(),
				seedInfo(phase) + " filter index for `published` missing in " + indexName +
					" although the model expects " + modelPks.size() + " value(s)!"
			);
			return;
		}
		final InvertedIndex invertedIndex = filterIndex.getInvertedIndex();

		// build the expected bucket sequence: normalized key (Instant) → ascending record ids; values may
		// legitimately collide across entities, so a bucket may hold several records
		final TreeMap<Instant, List<Integer>> expected = new TreeMap<>();
		for (final Integer pk : modelPks) {
			final OffsetDateTime value = this.publishedByPk.get(pk);
			assertNotNull(value, seedInfo(phase) + " model inconsistency: pk " + pk + " has no value!");
			expected.computeIfAbsent(value.toInstant(), key -> new ArrayList<>(1)).add(pk);
		}
		for (final List<Integer> records : expected.values()) {
			Collections.sort(records);
		}

		// 1) merge-walk: live buckets vs. the reference model — strictly ascending keys (fatal on violation:
		//    the stale-twin signature), no phantom bucket beyond the tolerated partial residue, no lost key of
		//    a successful upsert, record-id sets matching up to tolerated extras
		final Iterator<ValueToRecord> actual = invertedIndex.getValueIterator();
		final Iterator<Entry<Instant, List<Integer>>> expectedIt = expected.entrySet().iterator();
		Entry<Instant, List<Integer>> pendingExpected = expectedIt.hasNext() ? expectedIt.next() : null;
		Serializable previousKey = null;
		int position = 0;
		while (actual.hasNext()) {
			final ValueToRecord bucket = actual.next();
			final Serializable bucketKey = bucket.getValue();
			if (previousKey != null && compareKeys(previousKey, bucketKey) >= 0) {
				fail(
					seedInfo(phase) + " " + indexName + ": bucket[" + position + "]=" + bucketKey +
						" does not sort after bucket[" + (position - 1) + "]=" + previousKey +
						" — cross-bucket ordering violated (stale-twin signature)!"
				);
			}
			// an expected key the tree skipped over is fatal — a successful upsert was silently lost
			if (pendingExpected != null && compareKeys(pendingExpected.getKey(), bucketKey) < 0) {
				fail(
					seedInfo(phase) + " " + indexName + ": model value " + pendingExpected.getKey() +
						" of records " + pendingExpected.getValue() +
						" is MISSING from the index (silently lost upsert)!"
				);
			}
			if (pendingExpected == null || compareKeys(pendingExpected.getKey(), bucketKey) > 0) {
				// a bucket the model does not know: tolerated only when every record belongs to a failed
				// (partially applied) upsert
				assertOnlyToleratedRecords(bucket, toleratedPks, indexName, phase, position);
			} else {
				// matching key: every expected record must be present; extras must be tolerated residue
				final int[] actualRecords = bucket.getRecordIds().getArray();
				final List<Integer> expectedRecords = pendingExpected.getValue();
				int expectedIdx = 0;
				for (final int actualRecord : actualRecords) {
					if (expectedIdx < expectedRecords.size() && expectedRecords.get(expectedIdx) == actualRecord) {
						expectedIdx++;
					} else {
						assertTrue(
							toleratedPks.contains(actualRecord),
							seedInfo(phase) + " " + indexName + ": bucket[" + position + "]=" + bucketKey +
								" holds unexpected record " + actualRecord + " (bucket=" +
								bucket.getRecordIds() + ", expected=" + expectedRecords + ")"
						);
					}
				}
				assertEquals(
					expectedIdx, expectedRecords.size(),
					seedInfo(phase) + " " + indexName + ": bucket[" + position + "]=" + bucketKey +
						" lost expected record(s): holds " + bucket.getRecordIds() + ", expected " +
						expectedRecords
				);
				pendingExpected = expectedIt.hasNext() ? expectedIt.next() : null;
			}
			previousKey = bucketKey;
			position++;
		}
		if (pendingExpected != null) {
			fail(
				seedInfo(phase) + " " + indexName + ": model value " + pendingExpected.getKey() +
					" of records " + pendingExpected.getValue() +
					" (and possibly more) is MISSING from the index (silently lost upsert)!"
			);
		}

		// 2) page-sequence uniqueness over the live leaves — two leaves sharing a page sequence is the direct
		//    write-side twin signature (unassigned sequences of a not-yet-flushed warm-up tree are skipped)
		final List<? extends PagedLeafHandle> handles = leafHandles(invertedIndex);
		if (handles != null) {
			final Set<Integer> seen = new HashSet<>(handles.size());
			for (final PagedLeafHandle handle : handles) {
				final int pageSequence = handle.getPageSequence();
				if (pageSequence != PagedLeafHandle.UNASSIGNED_PAGE_SEQUENCE && !seen.add(pageSequence)) {
					fail(
						seedInfo(phase) + " " + indexName + ": two live leaves share page sequence " +
							pageSequence + " — stale leaf-page twin in the live tree!"
					);
				}
			}
		}
	}

	/**
	 * Fails unless every record of the model-unknown bucket belongs to a tolerated (visibly failed, partially
	 * applied) upsert.
	 *
	 * @param bucket       the phantom bucket
	 * @param toleratedPks primary keys whose partial index residue is tolerated
	 * @param indexName    the index description (failure diagnostics)
	 * @param phase        the phase description (failure diagnostics)
	 * @param position     the bucket position (failure diagnostics)
	 */
	private static void assertOnlyToleratedRecords(
		@Nonnull ValueToRecord bucket,
		@Nonnull Set<Integer> toleratedPks,
		@Nonnull String indexName,
		@Nonnull String phase,
		int position
	) {
		for (final int record : bucket.getRecordIds().getArray()) {
			assertTrue(
				toleratedPks.contains(record),
				seedInfo(phase) + " " + indexName + ": phantom bucket[" + position + "]=" + bucket.getValue() +
					" holds record " + record + " that belongs to NO failed upsert — a rolled-in ghost " +
					"(bucket=" + bucket.getRecordIds() + ")!"
			);
		}
	}

	/**
	 * Compares two bucket keys of the same runtime type.
	 *
	 * @param a the first key
	 * @param b the second key
	 * @return negative / zero / positive per {@link Comparable} contract
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static int compareKeys(@Nonnull Serializable a, @Nonnull Serializable b) {
		return ((Comparable) a).compareTo(b);
	}

	/**
	 * Full end-to-end resolution check (run on the reloaded catalog): every distinct model value must resolve
	 * through a real `attributeEquals` query to exactly the entities carrying it.
	 *
	 * @param phase the phase description (failure diagnostics)
	 */
	private void assertModelResolvesViaQueries(@Nonnull String phase) {
		assertModelResolvesViaQueries(phase, Collections.emptySet());
	}

	/**
	 * Tolerance-aware variant of {@link #assertModelResolvesViaQueries(String)}: extra matches belonging to a
	 * visibly failed (partially applied) upsert are tolerated; a lost or wrongly resolved successful upsert is
	 * fatal.
	 *
	 * @param phase        the phase description (failure diagnostics)
	 * @param toleratedPks primary keys whose partial index residue is tolerated
	 */
	private void assertModelResolvesViaQueries(@Nonnull String phase, @Nonnull Set<Integer> toleratedPks) {
		final TreeMap<OffsetDateTime, List<Integer>> byValue = new TreeMap<>();
		for (final Entry<Integer, OffsetDateTime> entry : this.publishedByPk.entrySet()) {
			byValue.computeIfAbsent(entry.getValue(), key -> new ArrayList<>(1)).add(entry.getKey());
		}
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (final Entry<OffsetDateTime, List<Integer>> entry : byValue.entrySet()) {
					final List<EntityReferenceContract> matches = session.queryListOfEntityReferences(
						query(
							collection(Entities.PRODUCT),
							filterBy(attributeEquals(ATTRIBUTE_PUBLISHED, entry.getKey()))
						)
					);
					final List<Integer> matchedPks = new ArrayList<>(matches.size());
					for (final EntityReferenceContract match : matches) {
						final int matchedPk = match.getPrimaryKey();
						if (!toleratedPks.contains(matchedPk)) {
							matchedPks.add(matchedPk);
						}
					}
					Collections.sort(matchedPks);
					assertEquals(
						entry.getValue(), matchedPks,
						seedInfo(phase) + " value " + entry.getKey() + " resolved to wrong entities"
					);
				}
				return null;
			}
		);
	}

	/*
		INTROSPECTION HELPERS
	 */

	/**
	 * Returns the key of the GLOBAL entity index.
	 *
	 * @return the global index key
	 */
	@Nonnull
	private static EntityIndexKey globalIndexKey() {
		return new EntityIndexKey(EntityIndexType.GLOBAL);
	}

	/**
	 * Returns the key of the REDUCED entity index partitioned by the category.
	 *
	 * @return the reduced index key
	 */
	@Nonnull
	private static EntityIndexKey reducedIndexKey() {
		return new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY,
			Scope.LIVE,
			new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_CATEGORIES, CATEGORY_PK))
		);
	}

	/**
	 * Navigates to the live `published` {@link FilterIndex} of the passed entity index.
	 *
	 * @param indexKey the entity index key
	 * @return the filter index; never null (asserts on absence)
	 */
	@Nonnull
	private FilterIndex publishedFilterIndex(@Nonnull EntityIndexKey indexKey) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection =
			(EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		final EntityIndex index = collection.getIndexByKeyIfExists(indexKey);
		assertNotNull(index, "Entity index " + indexKey + " unexpectedly missing!");
		final FilterIndex filterIndex =
			index.getFilterIndex(new AttributeIndexKey(null, ATTRIBUTE_PUBLISHED, null));
		assertNotNull(filterIndex, "Filter index for `published` unexpectedly missing in " + indexKey + "!");
		return filterIndex;
	}

	/**
	 * Returns the live leaf-page handles of the passed inverted index's bucket tree, or `null` when the tree is
	 * not reachable (single private-field reflection; the handles themselves are public API).
	 *
	 * @param invertedIndex the inverted index to introspect
	 * @return the leaf handles in ascending key order, or `null` when unreachable
	 */
	@Nullable
	private static List<? extends PagedLeafHandle> leafHandles(@Nonnull InvertedIndex invertedIndex) {
		try {
			final Field bucketsField = InvertedIndex.class.getDeclaredField("buckets");
			bucketsField.setAccessible(true);
			final Object tree = bucketsField.get(invertedIndex);
			if (tree instanceof BucketBPlusTree<?> bucketTree) {
				return bucketTree.leafPageHandles();
			}
			return null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Closes the whole Evita instance and reopens it over the same directories — a full cold restart forcing
	 * the PAGED leaf pages to be reloaded from disk.
	 */
	private void reopenEvita() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Renders the first sampled race exception (if any) for failure diagnostics.
	 *
	 * @param sampleExceptions the sampled exceptions
	 * @return a short rendering, or an empty string
	 */
	@Nonnull
	private static String firstSample(@Nonnull List<Throwable> sampleExceptions) {
		return sampleExceptions.isEmpty() ? "" : ", first: " + sampleExceptions.get(0);
	}

	/**
	 * Post-fix oracle for the concurrency guard: every sampled exception a racing worker hit must be a
	 * {@link ConcurrentSessionAccessException} — the loud rejection of a second thread entering the
	 * `@NotThreadSafe` session. Any other exception means the guard failed to fence off the concurrent access and
	 * corruption leaked into the shared trees, which is exactly what this harness must never see silently swallowed.
	 *
	 * @param sampleExceptions the sampled race exceptions collected across all worker threads
	 * @param phase            the phase description (failure diagnostics)
	 */
	private static void assertConcurrentAccessRejectedLoudly(
		@Nonnull List<Throwable> sampleExceptions, @Nonnull String phase
	) {
		for (final Throwable sample : sampleExceptions) {
			assertTrue(
				isConcurrentAccessRejection(sample),
				seedInfo(phase) + " a racing call failed with something other than a loud " +
					"ConcurrentSessionAccessException, so the concurrency guard did not cleanly reject the " +
					"concurrent access (a likely corruption leak): " + sample
			);
		}
	}

	/**
	 * Returns true when the passed throwable is, or is caused by, a {@link ConcurrentSessionAccessException}.
	 *
	 * @param throwable the throwable to inspect (may be null)
	 * @return true if a {@link ConcurrentSessionAccessException} is found in the cause chain
	 */
	private static boolean isConcurrentAccessRejection(@Nullable Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof ConcurrentSessionAccessException) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Prefixes failure diagnostics with the deterministic seed and phase info.
	 *
	 * @param phase the phase description
	 * @return the diagnostics prefix
	 */
	@Nonnull
	private static String seedInfo(@Nonnull String phase) {
		return "[seed=" + SEED + ", " + phase + "]";
	}
}
