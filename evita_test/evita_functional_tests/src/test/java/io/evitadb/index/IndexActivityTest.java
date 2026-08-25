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

package io.evitadb.index;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.EntityAttributeIndex;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.price.PriceSuperIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the per-index query / update counters and, above all, **the one invariant the whole design rests on**: the
 * {@link IndexActivity} holder survives `createCopyWithMergedTransactionalMemory`.
 *
 * A hot index is not mutated on commit - it is rebuilt. A counter held as a plain index field would therefore reset on
 * exactly the indexes worth measuring, and it would do so silently: the numbers would still look plausible, just
 * permanently small. Nothing else in the suite can see that, which is why the identity assertions below cover every
 * copy site there is - four entity-index merge copies, the catalog index's merge copy and its shallow copy. A seventh
 * one added later has to appear here too.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see IndexActivity
 */
@DisplayName("Index activity")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(MANAGEMENT)
class IndexActivityTest {

	private static final int INDEX_PK = 1;
	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "categories";
	/** An arbitrary but recognisable instant, and two later ones, so a crossed stamp is visible. */
	private static final long FIRST_MILLIS = 1_800_000_000_000L;
	private static final long SECOND_MILLIS = 1_800_000_060_000L;
	private static final long THIRD_MILLIS = 1_800_000_120_000L;
	/**
	 * The message every identity assertion fails with - one sentence saying what broke rather than "expected same".
	 */
	private static final String HOLDER_LOST =
		"The commit-time merge copy minted a new activity holder, so every commit that dirties an index silently " +
			"resets its counters - pass the existing holder through the reconstruction constructor";
	/** Daemon threads, so a recording thread that stalled cannot keep the surefire JVM alive after the test ends. */
	private static final ThreadFactory DAEMON_THREADS = runnable -> {
		final Thread thread = new Thread(runnable, "index-activity-recorder");
		thread.setDaemon(true);
		return thread;
	};

	@Nested
	@DisplayName("Holder")
	class HolderTest {

		@Test
		@DisplayName("A fresh holder has counted nothing and stamps nothing")
		void shouldStartAtZeroWithoutStamps() {
			final IndexActivity activity = new IndexActivity();

			assertEquals(0L, activity.getQueryCount());
			assertEquals(0L, activity.getUpdateCount());
			// null rather than the epoch: "not since the catalog was loaded" is a different statement from
			// "at 1970-01-01", and a client renders the latter as a date
			assertNull(activity.getLastQueriedAt());
			assertNull(activity.getLastUpdatedAt());
		}

		@Test
		@DisplayName("A fresh holder stamps the moment observation of its index began")
		void shouldRecordWhenObservationBegan() {
			final long before = System.currentTimeMillis();
			final IndexActivity activity = new IndexActivity();
			final long after = System.currentTimeMillis();

			// unlike the two "last at" stamps there is no "never" sentinel here - the reading is always set, and it is
			// what lets a client qualify a zero count ("never queried in the N minutes observed") honestly
			assertTrue(
				activity.getObservedSinceMillis() >= before,
				"Observation cannot have begun before the holder was constructed"
			);
			assertTrue(
				activity.getObservedSinceMillis() <= after,
				"Observation cannot have begun after the holder was constructed"
			);
			assertEquals(
				toTimestamp(activity.getObservedSinceMillis()), activity.getObservedSince(),
				"The timestamp must render the very millis the holder recorded"
			);
		}

		@Test
		@DisplayName("Recording a query advances only the query side")
		void shouldAdvanceOnlyTheQuerySideOnRecordQuery() {
			final IndexActivity activity = new IndexActivity();

			activity.recordQuery(FIRST_MILLIS);
			activity.recordQuery(SECOND_MILLIS);

			assertEquals(2L, activity.getQueryCount());
			assertEquals(0L, activity.getUpdateCount(), "A query must not be counted as maintenance");
			assertEquals(toTimestamp(SECOND_MILLIS), activity.getLastQueriedAt(), "The stamp is the last one");
			assertNull(activity.getLastUpdatedAt());
		}

		@Test
		@DisplayName("Recording an update advances only the update side")
		void shouldAdvanceOnlyTheUpdateSideOnRecordUpdate() {
			final IndexActivity activity = new IndexActivity();

			activity.recordUpdate(FIRST_MILLIS);
			activity.recordUpdate(SECOND_MILLIS);
			activity.recordUpdate(THIRD_MILLIS);

			assertEquals(3L, activity.getUpdateCount());
			assertEquals(0L, activity.getQueryCount(), "Maintenance must not be counted as a query");
			assertEquals(toTimestamp(THIRD_MILLIS), activity.getLastUpdatedAt(), "The stamp is the last one");
			assertNull(activity.getLastQueriedAt());
		}

		@Test
		@DisplayName("Each index gets a holder of its own")
		void shouldGiveEachIndexItsOwnHolder() {
			final GlobalEntityIndex first = globalIndex();
			final GlobalEntityIndex second = globalIndex();

			assertNotSame(
				first.getActivity(), second.getActivity(),
				"Two indexes sharing a holder would report each other's traffic"
			);
		}

		@Test
		@DisplayName("Concurrent recordings all arrive - none is lost to a read-modify-write race")
		void shouldLoseNoRecordingUnderConcurrency() throws InterruptedException {
			// the calibration, and the only reason the two counters advance through an `AtomicLongFieldUpdater` rather
			// than through a plain `this.queryCount++`: swap either increment for the plain one and this test fails,
			// while nothing else in the suite notices. The assertion is exact and interleaving-independent - every call
			// has to show up in the total however the threads were scheduled - so it belongs in the fast loop rather
			// than among the timing sweeps
			final int threads = 8;
			final int recordingsPerThread = 2_000;
			final IndexActivity activity = new IndexActivity();
			final CountDownLatch start = new CountDownLatch(1);
			final CountDownLatch finished = new CountDownLatch(threads);
			final ExecutorService pool = Executors.newFixedThreadPool(threads, DAEMON_THREADS);
			try {
				for (int thread = 0; thread < threads; thread++) {
					pool.submit(
						() -> {
							try {
								// released together, so the increments genuinely overlap instead of running one pool
								// thread's whole batch before the next one starts
								start.await();
								for (int recording = 0; recording < recordingsPerThread; recording++) {
									activity.recordQuery(FIRST_MILLIS);
									activity.recordUpdate(SECOND_MILLIS);
								}
							} catch (InterruptedException ex) {
								Thread.currentThread().interrupt();
							} finally {
								finished.countDown();
							}
						}
					);
				}
				start.countDown();
				assertTrue(
					finished.await(30, TimeUnit.SECONDS),
					"The recording threads did not finish within the budget - a counter is blocking rather than losing"
				);
			} finally {
				pool.shutdownNow();
			}

			final long expected = (long) threads * recordingsPerThread;
			assertEquals(expected, activity.getQueryCount(), "A concurrent query recording was lost");
			assertEquals(expected, activity.getUpdateCount(), "A concurrent update recording was lost");
			// nothing is asserted about the two stamps on purpose: they are plain last-writer-wins stores, so which of
			// the recordings left its instant resident is not a property this design claims
		}

	}

	/**
	 * The regression tests for the whole design. Each entity-index case dirties the index first, because an index that
	 * commits nothing is carried across the catalog version by reference and would pass trivially.
	 */
	@Nested
	@DisplayName("Holder survives the commit-time merge copy")
	class SurvivesMergeCopyTest {

		@Test
		@DisplayName("GlobalEntityIndex")
		void shouldCarryTheHolderThroughAGlobalIndexCopy() {
			assertHolderSurvivesCommit(globalIndex(), index -> index.insertPrimaryKeyIfMissing(1));
		}

		@Test
		@DisplayName("ReducedEntityIndex")
		void shouldCarryTheHolderThroughAReducedIndexCopy() {
			assertHolderSurvivesCommit(reducedIndex(), index -> index.insertPrimaryKeyIfMissing(1));
		}

		@Test
		@DisplayName("ReducedGroupEntityIndex")
		void shouldCarryTheHolderThroughAReducedGroupIndexCopy() {
			assertHolderSurvivesCommit(reducedGroupIndex(), index -> index.insertPrimaryKeyIfMissing(1, 7));
		}

		@Test
		@DisplayName("ReferencedTypeEntityIndex")
		void shouldCarryTheHolderThroughAReferencedTypeIndexCopy() {
			assertHolderSurvivesCommit(referencedTypeIndex(), index -> index.insertPrimaryKeyIfMissing(1, 7));
		}

		@Test
		@DisplayName("CatalogIndex")
		void shouldCarryTheHolderThroughACatalogIndexCopy() {
			final CatalogIndex index = new CatalogIndex(Scope.LIVE);
			final IndexActivity activity = index.getActivity();
			activity.recordQuery(FIRST_MILLIS);
			activity.recordUpdate(SECOND_MILLIS);

			assertStateAfterCommit(
				index,
				// writing a globally-unique attribute needs a whole schema; the copy this asserts on runs either way,
				// because a catalog index is rebuilt on every commit rather than only on a dirty one
				original -> {
				},
				(original, committed) -> {
					assertNotNull(committed);
					assertNotSame(original, committed, "The commit must have produced a copy");
					assertSame(activity, committed.getActivity(), HOLDER_LOST);
					assertEquals(1L, committed.getActivity().getQueryCount());
					assertEquals(1L, committed.getActivity().getUpdateCount());
				}
			);
		}

		@Test
		@DisplayName("CatalogIndex, going live or being renamed")
		void shouldCarryTheHolderThroughACatalogIndexShallowCopy() {
			// the sixth copy site, and the one no transactional test reaches: `createShallowCopyWithResetDirtyFlag`
			// runs at go-live and on a catalog rename, both of which carry the same logical index forward
			final CatalogIndex index = new CatalogIndex(Scope.LIVE);
			index.getActivity().recordUpdate(FIRST_MILLIS);

			final CatalogIndex copy = index.createShallowCopyWithResetDirtyFlag();

			assertSame(index.getActivity(), copy.getActivity(), HOLDER_LOST);
			assertEquals(1L, copy.getActivity().getUpdateCount());
		}

	}

	@Nested
	@DisplayName("Reconstruction constructor")
	class ReconstructionConstructorTest {

		@Test
		@DisplayName("An index rebuilt from persisted state counts into the holder it was handed")
		void shouldCountIntoTheHolderItWasHanded() {
			// this is the constructor the four `reloadPlan()` finalizers call, and they hand it a brand-new holder
			// rather than the one the previous incarnation held - which is what makes the counters "since catalog
			// load". Only the constructor's own half of that is pinned here: it adopts the holder it is given and
			// mints none of its own. The reload itself is covered end to end by
			// `IndexUsageStatisticsTest#shouldStartOverAfterARestart`, which closes the embedded instance and opens a
			// new one over the same directories
			final GlobalEntityIndex live = globalIndex();
			live.getActivity().recordQuery(FIRST_MILLIS);
			live.getActivity().recordUpdate(SECOND_MILLIS);

			final IndexActivity handedOver = new IndexActivity();
			final GlobalEntityIndex reloaded = new GlobalEntityIndex(
				live.getPrimaryKey(),
				live.getIndexKey(),
				live.version(),
				live.getAllPrimaryKeys(),
				Map.of(),
				new EntityAttributeIndex(ENTITY_TYPE),
				new PriceSuperIndex(),
				new HierarchyIndex(),
				new FacetIndex(),
				handedOver
			);

			assertSame(
				handedOver, reloaded.getActivity(),
				"The reconstructed index minted a holder of its own, so what the reload handed it counts nothing"
			);
			assertNotSame(live.getActivity(), reloaded.getActivity());
			assertEquals(0L, reloaded.getActivity().getQueryCount());
			assertEquals(0L, reloaded.getActivity().getUpdateCount());
			assertNull(reloaded.getActivity().getLastQueriedAt());
			assertNull(reloaded.getActivity().getLastUpdatedAt());

			// and the traffic it counts lands in that holder rather than anywhere the previous incarnation can see
			reloaded.getActivity().recordQuery(THIRD_MILLIS);
			assertEquals(1L, handedOver.getQueryCount());
			assertEquals(1L, live.getActivity().getQueryCount(), "The rebuilt index counted into the old holder");
		}

	}

	/**
	 * Records some traffic on the index, commits a change to it, and asserts the committed copy is still counting into
	 * the very same holder.
	 *
	 * @param index           the index to dirty and commit
	 * @param doInTransaction what to change so the commit produces a copy rather than carrying the index by reference
	 * @param <S>             the type the commit copy comes back as - the reduced kinds widen it to their shared base
	 * @param <T>             the index type under test
	 */
	private static <S extends EntityIndex, T extends EntityIndex & TransactionalStateProducer<S>>
	void assertHolderSurvivesCommit(
		@Nonnull T index,
		@Nonnull Consumer<T> doInTransaction
	) {
		final IndexActivity activity = index.getActivity();
		activity.recordQuery(FIRST_MILLIS);
		activity.recordUpdate(SECOND_MILLIS);

		assertStateAfterCommit(
			index,
			doInTransaction,
			(original, committed) -> {
				assertNotNull(committed);
				assertNotSame(original, committed, "The commit must have produced a copy");
				assertSame(activity, committed.getActivity(), HOLDER_LOST);
				assertEquals(1L, committed.getActivity().getQueryCount());
				assertEquals(1L, committed.getActivity().getUpdateCount());
				assertEquals(toTimestamp(SECOND_MILLIS), committed.getActivity().getLastUpdatedAt());
			}
		);
	}

	/**
	 * @return a fresh, empty global entity index
	 */
	@Nonnull
	private static GlobalEntityIndex globalIndex() {
		return new GlobalEntityIndex(INDEX_PK, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE));
	}

	/**
	 * @return a fresh, empty per-referenced-entity index
	 */
	@Nonnull
	private static ReducedEntityIndex reducedIndex() {
		return new ReducedEntityIndex(
			INDEX_PK, ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, representativeKey())
		);
	}

	/**
	 * @return a fresh, empty per-referenced-group index
	 */
	@Nonnull
	private static ReducedGroupEntityIndex reducedGroupIndex() {
		return new ReducedGroupEntityIndex(
			INDEX_PK, ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE, representativeKey())
		);
	}

	/**
	 * @return a fresh, empty per-reference-type index
	 */
	@Nonnull
	private static ReferencedTypeEntityIndex referencedTypeIndex() {
		return new ReferencedTypeEntityIndex(
			INDEX_PK, ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
		);
	}

	/**
	 * @return the discriminator the two reduced kinds are keyed by
	 */
	@Nonnull
	private static RepresentativeReferenceKey representativeKey() {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, 7));
	}

	/**
	 * Renders epoch millis the way {@link IndexActivity} does, so an assertion compares like with like rather than
	 * restating the conversion.
	 *
	 * @param millis the stamp to render
	 * @return the timestamp in the JVM's own zone
	 */
	@Nonnull
	private static OffsetDateTime toTimestamp(long millis) {
		return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
	}

}
