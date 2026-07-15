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

package io.evitadb.index.range;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.range.RangeIndex.StartsEndsDTO;
import io.evitadb.store.index.serializer.IntRangeIndexSerializer;
import io.evitadb.store.index.serializer.TransactionalIntRangePointSerializer;
import io.evitadb.store.index.serializer.TransactionalIntegerBitmapSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.AssertionUtils.assertFormulaResultsIn;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link RangeIndex}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@DisplayName("RangeIndex: range-based record validity index backed by a transactional B+ tree")
class RangeIndexTest {
	private final RangeIndex tested = new RangeIndex();

	@Nested
	@DisplayName("\"valid now\" cache behavior")
	class ValidNowCache {

		@Test
		@DisplayName("Cache field starts out null on a fresh index")
		void cacheFieldStartsNull() {
			assertNull(RangeIndexTest.this.tested.envelopingNowCache);
		}

		@Test
		@DisplayName("Returns the same result as enveloping-inclusive for the between-bucket case")
		void getRecordsValidNowReturnsSameAsEnvelopingInclusiveForBetweenBucketCase() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);
			RangeIndexTest.this.tested.addRecord(150L, 300L, 2);
			final long now = 175L;

			final var expected = RangeIndexTest.this.tested.getRecordsEnvelopingInclusive(now);
			final var actual = RangeIndexTest.this.tested.getRecordsValidNowFormula(now);

			assertArrayEquals(expected.compute().getArray(), actual.compute().getArray());
		}

		@Test
		@DisplayName("Populates the cache with flanking bounds on a miss")
		void getRecordsValidNowPopulatesCacheWithFlankingBoundsOnMiss() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);
			RangeIndexTest.this.tested.addRecord(150L, 300L, 2);

			RangeIndexTest.this.tested.getRecordsValidNowFormula(175L);

			final var cache = RangeIndexTest.this.tested.envelopingNowCache;
			assertNotNull(cache);
			assertEquals(151L, cache.validFromInclusive());
			assertEquals(199L, cache.validToInclusive());
			assertArrayEquals(new int[]{1, 2}, cache.result().getArray());
		}

		@Test
		@DisplayName("Reuses the cached bitmap for a second call in the same bucket")
		void getRecordsValidNowReusesCachedBitmapForSecondCallInSameBucket() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);
			RangeIndexTest.this.tested.addRecord(150L, 300L, 2);

			RangeIndexTest.this.tested.getRecordsValidNowFormula(160L);
			final var snapshot = RangeIndexTest.this.tested.envelopingNowCache;
			assertNotNull(snapshot);

			RangeIndexTest.this.tested.getRecordsValidNowFormula(180L);
			assertSame(snapshot, RangeIndexTest.this.tested.envelopingNowCache);
		}

		@Test
		@DisplayName("Recomputes once now crosses into a different bucket")
		void getRecordsValidNowRecomputesAfterNowCrossesBoundary() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);
			RangeIndexTest.this.tested.addRecord(150L, 300L, 2);

			RangeIndexTest.this.tested.getRecordsValidNowFormula(175L);
			final var first = RangeIndexTest.this.tested.envelopingNowCache;

			RangeIndexTest.this.tested.getRecordsValidNowFormula(250L);
			final var second = RangeIndexTest.this.tested.envelopingNowCache;

			assertNotSame(first, second);
			assertEquals(201L, second.validFromInclusive());
			assertEquals(299L, second.validToInclusive());
			assertArrayEquals(new int[]{2}, second.result().getArray());
		}

		@Test
		@DisplayName("Caches exact-threshold bounds on a hit")
		void getRecordsValidNowReturnsExactThresholdBoundariesOnHit() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);
			RangeIndexTest.this.tested.addRecord(150L, 300L, 2);

			RangeIndexTest.this.tested.getRecordsValidNowFormula(150L);

			final var cache = RangeIndexTest.this.tested.envelopingNowCache;
			assertNotNull(cache);
			assertEquals(150L, cache.validFromInclusive());
			assertEquals(150L, cache.validToInclusive());
			assertArrayEquals(new int[]{1, 2}, cache.result().getArray());
		}

		@Test
		@DisplayName("Recomputes when leaving an exact-threshold bucket")
		void getRecordsValidNowRecomputesWhenLeavingExactThresholdBucket() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);
			RangeIndexTest.this.tested.addRecord(150L, 300L, 2);

			RangeIndexTest.this.tested.getRecordsValidNowFormula(150L);
			final var first = RangeIndexTest.this.tested.envelopingNowCache;

			RangeIndexTest.this.tested.getRecordsValidNowFormula(151L);
			assertNotSame(first, RangeIndexTest.this.tested.envelopingNowCache);
		}

		@Test
		@DisplayName("On an empty index returns empty and caches the maximal interval")
		void getRecordsValidNowOnEmptyIndexReturnsEmptyAndCachesMaxInterval() {
			final var formula = RangeIndexTest.this.tested.getRecordsValidNowFormula(0L);

			assertTrue(formula.compute().isEmpty());
			final var cache = RangeIndexTest.this.tested.envelopingNowCache;
			assertNotNull(cache);
			assertEquals(Long.MIN_VALUE + 1, cache.validFromInclusive());
			assertEquals(Long.MAX_VALUE - 1, cache.validToInclusive());
			assertTrue(cache.result().isEmpty());
		}

		@Test
		@DisplayName("Returns empty before the first threshold")
		void getRecordsValidNowBeforeFirstThresholdReturnsEmpty() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);

			final var formula = RangeIndexTest.this.tested.getRecordsValidNowFormula(50L);

			assertTrue(formula.compute().isEmpty());
			final var cache = RangeIndexTest.this.tested.envelopingNowCache;
			assertNotNull(cache);
			assertEquals(Long.MIN_VALUE + 1, cache.validFromInclusive());
			assertEquals(99L, cache.validToInclusive());
		}

		@Test
		@DisplayName("Returns empty after the last threshold")
		void getRecordsValidNowAfterLastThresholdReturnsEmpty() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);

			final var formula = RangeIndexTest.this.tested.getRecordsValidNowFormula(500L);

			assertTrue(formula.compute().isEmpty());
			final var cache = RangeIndexTest.this.tested.envelopingNowCache;
			assertNotNull(cache);
			assertEquals(201L, cache.validFromInclusive());
			assertEquals(Long.MAX_VALUE - 1, cache.validToInclusive());
		}
	}

	@Nested
	@DisplayName("Cache invalidation and recompute (non-transactional)")
	class CacheInvalidation {

		@Test
		@DisplayName("Non-transactional addRecord invalidates the cache")
		void nonTransactionalAddRecordInvalidatesCache() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);
			RangeIndexTest.this.tested.getRecordsValidNowFormula(150L);
			assertNotNull(RangeIndexTest.this.tested.envelopingNowCache);

			RangeIndexTest.this.tested.addRecord(120L, 180L, 2);

			assertNull(RangeIndexTest.this.tested.envelopingNowCache);
		}

		@Test
		@DisplayName("Non-transactional removeRecord invalidates the cache")
		void nonTransactionalRemoveRecordInvalidatesCache() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);
			RangeIndexTest.this.tested.addRecord(120L, 180L, 2);
			RangeIndexTest.this.tested.getRecordsValidNowFormula(150L);
			assertNotNull(RangeIndexTest.this.tested.envelopingNowCache);

			RangeIndexTest.this.tested.removeRecord(120L, 180L, 2);

			assertNull(RangeIndexTest.this.tested.envelopingNowCache);
		}

		@Test
		@DisplayName("Recompute after invalidation reflects the new state")
		void recomputeAfterInvalidationReflectsNewState() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);
			RangeIndexTest.this.tested.getRecordsValidNowFormula(150L);

			RangeIndexTest.this.tested.addRecord(140L, 160L, 2);
			final var formula = RangeIndexTest.this.tested.getRecordsValidNowFormula(150L);

			assertArrayEquals(new int[]{1, 2}, formula.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Cache semantics under an open transaction")
	class TransactionalCache {

		@Test
		@DisplayName("Transactional add does not invalidate the committed cache")
		void transactionalAddDoesNotInvalidateCommittedCache() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);
			RangeIndexTest.this.tested.getRecordsValidNowFormula(150L);
			final var before = RangeIndexTest.this.tested.envelopingNowCache;
			assertNotNull(before);

			assertStateAfterRollback(
				RangeIndexTest.this.tested,
				original -> {
					original.addRecord(140L, 160L, 2);
					assertSame(before, original.envelopingNowCache);
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertSame(before, original.envelopingNowCache);
				}
			);
		}

		@Test
		@DisplayName("Transactional read bypasses the cache and sees the transactional layer")
		void transactionalReadBypassesCacheAndSeesTxLayer() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);
			RangeIndexTest.this.tested.getRecordsValidNowFormula(150L);
			final var before = RangeIndexTest.this.tested.envelopingNowCache;
			assertNotNull(before);
			assertArrayEquals(new int[]{1}, before.result().getArray());

			assertStateAfterRollback(
				RangeIndexTest.this.tested,
				original -> {
					original.addRecord(140L, 160L, 2);
					final var inTx = original.getRecordsValidNowFormula(150L);
					assertArrayEquals(new int[]{1, 2}, inTx.compute().getArray());
					assertSame(before, original.envelopingNowCache);
				},
				(original, committedVersion) -> assertNull(committedVersion)
			);
		}

		@Test
		@DisplayName("Commit produces a fresh RangeIndex with a null cache")
		void commitProducesFreshRangeIndexWithNullCache() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);
			RangeIndexTest.this.tested.getRecordsValidNowFormula(150L);
			assertNotNull(RangeIndexTest.this.tested.envelopingNowCache);

			assertStateAfterCommit(
				RangeIndexTest.this.tested,
				original -> original.addRecord(140L, 160L, 2),
				(original, committedVersion) -> {
					assertNotNull(committedVersion);
					assertNull(committedVersion.envelopingNowCache);
					final var afterCommit = committedVersion.getRecordsValidNowFormula(150L);
					assertArrayEquals(new int[]{1, 2}, afterCommit.compute().getArray());
					assertNotNull(committedVersion.envelopingNowCache);
				}
			);
		}
	}

	@Nested
	@DisplayName("Version identity (formula-cache staleness token)")
	@Tag(CACHE)
	class VersionIdentity {

		@Test
		@DisplayName("Distinct instances carry distinct version ids (not the constant Void 1L)")
		void distinctInstancesHaveDistinctVersionIds() {
			final RangeIndex a = new RangeIndex();
			final RangeIndex b = new RangeIndex();
			// each RangeIndex must expose a UNIQUE id; the VoidTransactionMemoryProducer default 1L would make every
			// index collide and, worse, make the >100-bucket JoinFormula token constant -> cache never invalidates (#37)
			assertNotEquals(a.getId(), b.getId());
		}

		@Test
		@DisplayName("Commit after a range mutation mints a fresh version id (guards issue #37 stale cache)")
		void commitAfterMutationChangesVersionId() {
			final long originalId = RangeIndexTest.this.tested.getId();

			assertStateAfterCommit(
				RangeIndexTest.this.tested,
				original -> original.addRecord(100L, 200L, 1),
				(original, committedVersion) -> {
					// the surviving original delegate keeps its identity and its id
					assertEquals(originalId, original.getId());
					// the committed copy is a FRESH instance and MUST carry a fresh version id. A >100-bucket range
					// JoinFormula seeds its transactional-id token from this id; if it did not change across a mutating
					// commit the cached result would never be invalidated -> stale reads (issue #37).
					assertNotEquals(originalId, committedVersion.getId());
				}
			);
		}

		@Test
		@DisplayName("Commit without any mutation preserves the version id (untouched index stays cache-valid)")
		void commitWithoutMutationPreservesVersionId() {
			final long originalId = RangeIndexTest.this.tested.getId();

			assertStateAfterCommit(
				RangeIndexTest.this.tested,
				// touch a DIFFERENT structure inside the transaction so a transactional layer exists, but never mutate
				// the range index itself; a clean RangeIndex must return `this` at commit, preserving its id so cached
				// formulas over an untouched index stay valid
				original -> original.getRecordsValidNowFormula(150L),
				(original, committedVersion) -> assertEquals(originalId, committedVersion.getId())
			);
		}

		@Test
		@DisplayName("Mutating commit re-mints a > 100-bitmap range formula's token (guards issue #37 stale cache)")
		void highCardinalityRangeFormulaTokenChangesOnMutatingCommit() {
			// 150 records at pairwise-disjoint from/to thresholds -> 150 distinct start points and 150 distinct end
			// points, so getRecordsFrom(MIN_VALUE) folds > EXCESSIVE_HIGH_CARDINALITY (100) bitmaps into each JoinFormula,
			// which then seeds its staleness token from the index id (getId()) instead of the per-bitmap ids
			for (int i = 1; i <= 150; i++) {
				RangeIndexTest.this.tested.addRecord(i, 10_000 + i, i);
			}
			assertTrue(
				RangeIndexTest.this.tested.getRangePointCount()
					> 2 * TransactionalDataRelatedStructure.EXCESSIVE_HIGH_CARDINALITY,
				"Fixture must carry > 100 distinct start thresholds and > 100 distinct end thresholds!"
			);
			final long before = RangeIndexTest.this.tested.getRecordsFrom(Long.MIN_VALUE).getTransactionalIdHash();

			assertStateAfterCommit(
				RangeIndexTest.this.tested,
				original -> original.addRecord(10_000, 20_000, 100_000),
				(original, committedVersion) ->
					// the committed copy is a fresh instance with a fresh id; the > 100-bitmap JoinFormula seeds its
					// token from that id, so a cached result over this range is now invalidated. With the old constant
					// 1L id the token would be identical across the commit -> the stale read of issue #37.
					assertNotEquals(
						before, committedVersion.getRecordsFrom(Long.MIN_VALUE).getTransactionalIdHash(),
						"A mutating commit must re-mint the high-cardinality range token!"
					)
			);
		}

		@Test
		@DisplayName("Clean commit preserves a > 100-bitmap range formula's token (no over-invalidation)")
		void highCardinalityRangeFormulaTokenSurvivesCleanCommit() {
			// first fold a non-transactional 150-range build into a committed (clean) instance, so its dirty flag is
			// cleared and a subsequent read-only commit returns the very same instance (preserving its id)
			final RangeIndex[] cleanHolder = new RangeIndex[1];
			assertStateAfterCommit(
				RangeIndexTest.this.tested,
				original -> {
					for (int i = 1; i <= 150; i++) {
						original.addRecord(i, 10_000 + i, i);
					}
				},
				(original, committedVersion) -> cleanHolder[0] = committedVersion
			);
			final RangeIndex cleanIndex = cleanHolder[0];
			assertTrue(
				cleanIndex.getRangePointCount() > 2 * TransactionalDataRelatedStructure.EXCESSIVE_HIGH_CARDINALITY,
				"Fixture must carry > 100 distinct start thresholds and > 100 distinct end thresholds!"
			);
			final long before = cleanIndex.getRecordsFrom(Long.MIN_VALUE).getTransactionalIdHash();

			// then a commit that never touches the range index must return the same instance and preserve its token
			assertStateAfterCommit(
				cleanIndex,
				original -> original.getRecordsValidNowFormula(0L),
				(original, committedVersion) -> assertEquals(
					before, committedVersion.getRecordsFrom(Long.MIN_VALUE).getTransactionalIdHash(),
					"A commit that did not touch the index must preserve its high-cardinality range token!"
				)
			);
		}
	}

	@Nested
	@DisplayName("Concurrency")
	class Concurrency {

		@Test
		@DisplayName("Concurrent readers all agree on the result")
		void concurrentReadersAgreeOnResult() throws Exception {
			for (int i = 0; i < 1000; i++) {
				RangeIndexTest.this.tested.addRecord(i * 10L, i * 10L + 5L, i + 1);
			}
			final long now = 1234L;
			final int threads = 16;
			final java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threads);
			final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
			final java.util.List<java.util.concurrent.Future<int[]>> futures = new java.util.ArrayList<>();
			try {
				for (int t = 0; t < threads; t++) {
					futures.add(pool.submit(() -> {
						barrier.await();
						return RangeIndexTest.this.tested.getRecordsValidNowFormula(now).compute().getArray();
					}));
				}
				final int[] expected = RangeIndexTest.this.tested.getRecordsEnvelopingInclusive(now).compute().getArray();
				for (java.util.concurrent.Future<int[]> f : futures) {
					assertArrayEquals(expected, f.get());
				}
			} finally {
				pool.shutdown();
			}
		}
	}

	@Nested
	@DisplayName("Transactional add / remove / commit / rollback")
	class TransactionalMutations {

		@Test
		@DisplayName("Adds items inside a transaction and rolls them back")
		void shouldAddTransactionalItemsAndRollback() {
			assertStateAfterRollback(
				RangeIndexTest.this.tested,
				original -> {
					original.addRecord(5, 10, 1);
					original.addRecord(5, 10, 2);
					original.addRecord(7, 10, 3);
					original.addRecord(1, 5, 4);

					assertTrue(RangeIndexTest.this.tested.contains(1));
					assertTrue(RangeIndexTest.this.tested.contains(2));
					assertTrue(RangeIndexTest.this.tested.contains(3));
					assertTrue(RangeIndexTest.this.tested.contains(4));
					assertTrue(
						new StartsEndsDTO(
							asListOfBitmaps(new int[0], new int[]{4}, new int[]{1, 2}, new int[]{3}, new int[0], new int[0]),
							asListOfBitmaps(new int[0], new int[0], new int[]{4}, new int[0], new int[]{1, 2, 3}, new int[0])
						).effectivelyEquals(
							RangeIndex.collectAllStartsAndEnds(original)
						)
					);
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);

					assertFalse(original.contains(1));
					assertFalse(original.contains(2));
					assertFalse(original.contains(3));
					assertFalse(original.contains(4));
					assertTrue(
						new StartsEndsDTO(
							asListOfBitmaps(new int[0], new int[0]),
							asListOfBitmaps(new int[0], new int[0])
						).effectivelyEquals(
							RangeIndex.collectAllStartsAndEnds(original)
						)
					);
				}
			);
		}

		@Test
		@DisplayName("Adds items inside a transaction and commits them")
		void shouldAddTransactionalItemsAndCommit() {
			assertStateAfterCommit(
				RangeIndexTest.this.tested,
				original -> {
					original.addRecord(5, 10, 1);
					original.addRecord(5, 10, 2);
					original.addRecord(7, 10, 3);
					original.addRecord(1, 5, 4);

					assertTrue(RangeIndexTest.this.tested.contains(1));
					assertTrue(RangeIndexTest.this.tested.contains(2));
					assertTrue(RangeIndexTest.this.tested.contains(3));
					assertTrue(RangeIndexTest.this.tested.contains(4));
				},
				(original, committedVersion) -> {
					assertFalse(original.contains(1));
					assertFalse(original.contains(2));
					assertFalse(original.contains(3));
					assertFalse(original.contains(4));
					assertTrue(
						new StartsEndsDTO(
							asListOfBitmaps(new int[0], new int[0]),
							asListOfBitmaps(new int[0], new int[0])
						).effectivelyEquals(
							RangeIndex.collectAllStartsAndEnds(original)
						)
					);

					assertTrue(committedVersion.contains(1));
					assertTrue(committedVersion.contains(2));
					assertTrue(committedVersion.contains(3));
					assertTrue(committedVersion.contains(4));
					assertTrue(
						new StartsEndsDTO(
							asListOfBitmaps(new int[0], new int[]{4}, new int[]{1, 2}, new int[]{3}, new int[0], new int[0]),
							asListOfBitmaps(new int[0], new int[0], new int[]{4}, new int[0], new int[]{1, 2, 3}, new int[0])
						).effectivelyEquals(
							RangeIndex.collectAllStartsAndEnds(committedVersion)
						)
					);
				}
			);
		}

		@Test
		@DisplayName("Adds and removes items inside a transaction and commits the empty result")
		void shouldAddAndRemoveTransactionalItemsAndCommit() {
			assertStateAfterCommit(
				new RangeIndex(),
				original -> {
					original.addRecord(5, 10, 1);
					original.removeRecord(5, 10, 1);
					original.addRecord(7, 10, 3);
					original.removeRecord(7, 10, 3);

					assertFalse(original.contains(1));
					assertFalse(original.contains(3));
				},
				(original, committedVersion) -> {
					assertFalse(original.contains(1));
					assertFalse(original.contains(3));
					assertTrue(
						new StartsEndsDTO(
							asListOfBitmaps(new int[0], new int[0]),
							asListOfBitmaps(new int[0], new int[0])
						).effectivelyEquals(
							RangeIndex.collectAllStartsAndEnds(original)
						)
					);

					assertFalse(committedVersion.contains(1));
					assertFalse(committedVersion.contains(3));
					assertTrue(
						new StartsEndsDTO(
							asListOfBitmaps(new int[0], new int[0]),
							asListOfBitmaps(new int[0], new int[0])
						).effectivelyEquals(
							RangeIndex.collectAllStartsAndEnds(committedVersion)
						)
					);
				}
			);
		}

		@Test
		@DisplayName("Reproduces a production removal sequence that previously corrupted shared borders")
		void shouldPassErrorSituationInProduction1() {
			final RangeIndex tested = new RangeIndex(
				new TransactionalRangePoint[]{
					new TransactionalRangePoint(Long.MIN_VALUE),
					new TransactionalRangePoint(1L, new int[]{1, 3, 5, 11, 13, 14, 15}, new int[0]),
					new TransactionalRangePoint(2L, new int[0], new int[]{1, 3, 5, 11, 13, 14, 15}),
					new TransactionalRangePoint(Long.MAX_VALUE)
				}
			);

			assertStateAfterCommit(
				tested,
				original -> {
					original.removeRecord(1L, 2L, 11);
					original.removeRecord(1L, 2L, 13);
					original.removeRecord(1L, 2L, 15);
					original.addRecord(1L, 2L, -1);
					original.removeRecord(1L, 2L, 1);
					original.removeRecord(1L, 2L, 5);
					original.removeRecord(1L, 2L, 3);
				},
				(original, committedVersion) ->
					assertTrue(
						new StartsEndsDTO(
							asListOfBitmaps(new int[0], new int[]{-1, 14}, new int[0], new int[0]),
							asListOfBitmaps(new int[0], new int[0], new int[]{-1, 14}, new int[0])
						).effectivelyEquals(
							RangeIndex.collectAllStartsAndEnds(committedVersion)
						)
					)
			);
		}

		@Test
		@DisplayName("Commits transactional adds atomically, leaving the original untouched")
		void transactionalAddCommitsAtomically() {
			assertStateAfterCommit(
				new RangeIndex(),
				original -> {
					original.addRecord(5, 10, 1);
					original.addRecord(7, 12, 2);
					assertTrue(original.contains(1));
					assertTrue(original.contains(2));
				},
				(original, committedVersion) -> {
					// original delegate never mutated
					assertFalse(original.contains(1));
					assertFalse(original.contains(2));
					// committed version sees everything
					assertTrue(committedVersion.contains(1));
					assertTrue(committedVersion.contains(2));
					assertArrayEquals(new int[]{1, 2}, committedVersion.getAllRecords().getArray());
				}
			);
		}

		@Test
		@DisplayName("Rolls back transactional adds, leaving the original untouched")
		void transactionalAddRollbackLeavesOriginalUntouched() {
			assertStateAfterRollback(
				new RangeIndex(),
				original -> {
					original.addRecord(5, 10, 1);
					assertTrue(original.contains(1));
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertFalse(original.contains(1));
				}
			);
		}

		@Test
		@DisplayName("Mutating an existing point's bitmap commits atomically with the parent")
		void modifyingPointBitmapCommitsAtomically() {
			final RangeIndex base = new RangeIndex();
			base.addRecord(5, 10, 1);

			assertStateAfterCommit(
				base,
				original -> {
					// shares threshold 5 and 10 with record 1, mutating the existing range points in place
					original.addRecord(5, 10, 2);
					assertTrue(original.contains(2));
				},
				(original, committedVersion) -> {
					assertFalse(original.contains(2));
					assertTrue(committedVersion.contains(1));
					assertTrue(committedVersion.contains(2));
					assertArrayEquals(new int[]{1, 2}, committedVersion.getAllRecords().getArray());
				}
			);
		}

		@Test
		@DisplayName("Removing to empty inside a transaction sweeps the range points cleanly")
		void removeToEmptyInsideTransactionSweepsCleanly() {
			final RangeIndex base = new RangeIndex();
			base.addRecord(5, 10, 1);
			base.addRecord(5, 10, 2);

			assertStateAfterCommit(
				base,
				original -> {
					original.removeRecord(5, 10, 1);
					original.removeRecord(5, 10, 2);
					assertFalse(original.contains(1));
					assertFalse(original.contains(2));
				},
				(original, committedVersion) -> {
					// original still holds both
					assertTrue(original.contains(1));
					assertTrue(original.contains(2));
					// committed version is empty
					assertFalse(committedVersion.contains(1));
					assertFalse(committedVersion.contains(2));
					assertEquals(0, committedVersion.size());
				}
			);
		}

		@Test
		@DisplayName("The transactional iterator observes in-progress changes inside the transaction")
		void rangesIteratorReflectsInTransactionChanges() {
			final RangeIndex base = new RangeIndex();
			base.addRecord(5, 10, 1);

			assertStateAfterRollback(
				base,
				original -> {
					original.addRecord(1, 3, 2);
					final java.util.List<Long> thresholds = new java.util.ArrayList<>();
					final java.util.Iterator<TransactionalRangePoint> it = original.rangesIterator();
					while (it.hasNext()) {
						thresholds.add(it.next().getThreshold());
					}
					// the freshly added thresholds 1 and 3 are visible inside the transaction
					assertTrue(thresholds.contains(1L));
					assertTrue(thresholds.contains(3L));
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					// after rollback the original no longer carries the in-txn thresholds
					assertFalse(original.contains(2));
				}
			);
		}
	}

	@Nested
	@DisplayName("Non-transactional mutations")
	class NonTransactionalMutations {

		@Test
		@DisplayName("Outside a transaction, add/remove mutates this instance directly")
		void nonTransactionalAddAndRemoveMutatesDelegate() {
			RangeIndexTest.this.tested.addRecord(5, 10, 1);
			RangeIndexTest.this.tested.addRecord(5, 10, 2);
			assertTrue(RangeIndexTest.this.tested.contains(1));
			assertTrue(RangeIndexTest.this.tested.contains(2));

			RangeIndexTest.this.tested.removeRecord(5, 10, 1);
			assertFalse(RangeIndexTest.this.tested.contains(1));
			assertTrue(RangeIndexTest.this.tested.contains(2));
			assertArrayEquals(new int[]{2}, RangeIndexTest.this.tested.getAllRecords().getArray());
		}

		@Test
		@DisplayName("Adds then removes a record outside a transaction")
		void shouldAddAndRemoveRecord() {
			RangeIndexTest.this.tested.addRecord(Long.MIN_VALUE, Long.MAX_VALUE, 1);
			RangeIndexTest.this.tested.addRecord(Long.MIN_VALUE, timestampForDate(5, 5), 2);
			RangeIndexTest.this.tested.addRecord(timestampForDate(5, 5), Long.MAX_VALUE, 3);
			RangeIndexTest.this.tested.addRecord(timestampForDate(1, 4), timestampForDate(5, 5), 4);

			assertTrue(RangeIndexTest.this.tested.contains(1));
			assertTrue(RangeIndexTest.this.tested.contains(2));
			assertTrue(RangeIndexTest.this.tested.contains(3));
			assertTrue(RangeIndexTest.this.tested.contains(4));
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(1, 5)), new int[]{1, 2, 4});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(1, 6)), new int[]{1, 3});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(1, 1)), new int[]{1, 2});

			RangeIndexTest.this.tested.removeRecord(Long.MIN_VALUE, Long.MAX_VALUE, 1);
			RangeIndexTest.this.tested.removeRecord(timestampForDate(1, 4), timestampForDate(5, 5), 4);

			assertFalse(RangeIndexTest.this.tested.contains(1));
			assertTrue(RangeIndexTest.this.tested.contains(2));
			assertTrue(RangeIndexTest.this.tested.contains(3));
			assertFalse(RangeIndexTest.this.tested.contains(4));
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(1, 5)), new int[]{2});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(1, 6)), new int[]{3});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(1, 1)), new int[]{2});
		}
	}

	@Nested
	@DisplayName("Range queries")
	class RangeQueries {

		@Test
		@DisplayName("getRecordsFrom returns records valid from a threshold onward")
		void shouldPassSimpleValidFrom() {
			RangeIndexTest.this.tested.addRecord(Long.MIN_VALUE, Long.MAX_VALUE, 1);
			RangeIndexTest.this.tested.addRecord(Long.MIN_VALUE, timestampForDate(5, 5), 2);
			RangeIndexTest.this.tested.addRecord(timestampForDate(5, 5), Long.MAX_VALUE, 3);
			RangeIndexTest.this.tested.addRecord(timestampForDate(1, 4), timestampForDate(5, 5), 4);

			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(1, 5)), new int[]{1, 2, 4});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(1, 6)), new int[]{1, 3});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(1, 1)), new int[]{1, 2});
		}

		@Test
		@DisplayName("getRecordsFrom handles multiple non-overlapping ranges for a single record")
		void shouldPassValidFromWhenThereAreMultipleRangesForSingleRecord() {
			RangeIndexTest.this.tested.addRecord(Long.MIN_VALUE, Long.MAX_VALUE, 1);
			RangeIndexTest.this.tested.addRecord(Long.MIN_VALUE, timestampForDate(5, 5), 2);
			RangeIndexTest.this.tested.addRecord(timestampForDate(1, 7), Long.MAX_VALUE, 2);
			RangeIndexTest.this.tested.addRecord(timestampForDate(1, 1), timestampForDate(3, 3), 3);
			RangeIndexTest.this.tested.addRecord(timestampForDate(5, 5), Long.MAX_VALUE, 3);
			RangeIndexTest.this.tested.addRecord(timestampForDate(1, 4), timestampForDate(5, 7), 4);

			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(2, 2)), new int[]{1, 2, 3});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(10, 3)), new int[]{1, 2});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(1, 5)), new int[]{1, 2, 4});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(10, 5)), new int[]{1, 3, 4});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(2, 7)), new int[]{1, 2, 3, 4});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(6, 6)), new int[]{1, 3, 4});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(timestampForDate(10, 7)), new int[]{1, 2, 3});
		}

		@Test
		@DisplayName("getRecordsTo handles multiple non-overlapping ranges for a single record")
		void shouldPassValidToWhenThereAreMultipleRangesForSingleRecord() {
			RangeIndexTest.this.tested.addRecord(Long.MIN_VALUE, Long.MAX_VALUE, 1);
			RangeIndexTest.this.tested.addRecord(Long.MIN_VALUE, timestampForDate(5, 5), 2);
			RangeIndexTest.this.tested.addRecord(timestampForDate(1, 7), Long.MAX_VALUE, 2);
			RangeIndexTest.this.tested.addRecord(timestampForDate(1, 1), timestampForDate(3, 3), 3);
			RangeIndexTest.this.tested.addRecord(timestampForDate(5, 5), Long.MAX_VALUE, 3);
			RangeIndexTest.this.tested.addRecord(timestampForDate(1, 4), timestampForDate(5, 7), 4);

			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsTo(timestampForDate(2, 2)), new int[]{1, 2, 3});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsTo(timestampForDate(10, 3)), new int[]{1, 2});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsTo(timestampForDate(1, 5)), new int[]{1, 2, 4});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsTo(timestampForDate(10, 5)), new int[]{1, 3, 4});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsTo(timestampForDate(2, 7)), new int[]{1, 2, 3, 4});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsTo(timestampForDate(6, 6)), new int[]{1, 3, 4});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsTo(timestampForDate(10, 7)), new int[]{1, 2, 3});
		}

		@Test
		@DisplayName("getRecordsWithRangesOverlapping returns records whose range overlaps the query window")
		void shouldPassValidWithRangesOverlapping() {
			RangeIndexTest.this.tested.addRecord(1, 4, 1);
			RangeIndexTest.this.tested.addRecord(4, 7, 2);
			RangeIndexTest.this.tested.addRecord(7, 10, 3);
			RangeIndexTest.this.tested.addRecord(3, 5, 4);
			RangeIndexTest.this.tested.addRecord(6, 9, 5);

			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsWithRangesOverlapping(Long.MIN_VALUE, Long.MAX_VALUE), new int[]{1, 2, 3, 4, 5});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsWithRangesOverlapping(Long.MIN_VALUE, 2), new int[]{1});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsWithRangesOverlapping(9, Long.MAX_VALUE), new int[]{3, 5});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsWithRangesOverlapping(4, 7), new int[]{1, 2, 3, 4, 5});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsWithRangesOverlapping(1, 2), new int[]{1});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsWithRangesOverlapping(1, 1), new int[]{1});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsWithRangesOverlapping(1, 3), new int[]{1, 4});
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsWithRangesOverlapping(7, 7), new int[]{2, 3, 5});
		}

		@Test
		@DisplayName("getRecordsEnvelopingInclusive at an exact threshold and between thresholds")
		void shouldComputeEnvelopingInclusiveAtExactThresholdAndBetween() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);
			RangeIndexTest.this.tested.addRecord(150L, 300L, 2);

			// exactly on a threshold (150) - record 1 and 2 valid
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsEnvelopingInclusive(150L), new int[]{1, 2});
			// between thresholds (175) - both valid
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsEnvelopingInclusive(175L), new int[]{1, 2});
			// exactly on the start threshold of record 1 (100)
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsEnvelopingInclusive(100L), new int[]{1});
			// after record 1 ends but record 2 still valid (250)
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsEnvelopingInclusive(250L), new int[]{2});
			// outside every range
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsEnvelopingInclusive(50L), new int[0]);
		}

		@Test
		@DisplayName("getRecordsTo honors the inclusive boundary at and between thresholds")
		void shouldComputeRecordsToInclusiveBoundary() {
			RangeIndexTest.this.tested.addRecord(1, 4, 1);
			RangeIndexTest.this.tested.addRecord(5, 7, 2);
			RangeIndexTest.this.tested.addRecord(8, 10, 3);

			// exactly on threshold 5 - records starting at or before 5 with no earlier end (record 1 already ended at 4)
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsTo(5L), new int[]{2});
			// between thresholds (6) - same as 5 (inclusive -idx-2 boundary)
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsTo(6L), new int[]{2});
			// at threshold 10 every record has both started and ended -> none "valid until" this point
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsTo(10L), new int[0]);
			// at the first real threshold (1) only record 1 has started and not yet ended
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsTo(1L), new int[]{1});
			// between record 1 start and end (2) record 1 still valid
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsTo(2L), new int[]{1});
		}

		@Test
		@DisplayName("getRecordsFrom on an empty index returns no records")
		void shouldReturnEmptyFromQueryWhenIndexIsEmpty() {
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsFrom(100L), new int[0]);
		}

		@Test
		@DisplayName("getRecordsTo on an empty index returns no records")
		void shouldReturnEmptyToQueryWhenIndexIsEmpty() {
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsTo(100L), new int[0]);
		}

		@Test
		@DisplayName("getRecordsEnvelopingInclusive on an empty index returns no records")
		void shouldReturnEmptyEnvelopingWhenIndexIsEmpty() {
			assertFormulaResultsIn(RangeIndexTest.this.tested.getRecordsEnvelopingInclusive(100L), new int[0]);
		}

		@Test
		@DisplayName("getRecordsWithRangesOverlapping on an empty index returns no records")
		void shouldReturnEmptyOverlappingWhenIndexIsEmpty() {
			assertFormulaResultsIn(
				RangeIndexTest.this.tested.getRecordsWithRangesOverlapping(50L, 150L), new int[0]
			);
		}

		@Test
		@DisplayName("getRecordsFrom inside a transaction observes pending additions")
		void shouldReturnFromQueryReflectingInTransactionAddsWhenInsideTransaction() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);

			assertStateAfterRollback(
				RangeIndexTest.this.tested,
				original -> {
					// pending add inside the transaction
					original.addRecord(150L, 300L, 2);
					// MVCC view includes the pending record 2 as valid from 175
					assertFormulaResultsIn(original.getRecordsFrom(175L), new int[]{1, 2});
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					// after rollback only the pre-transaction record 1 is visible
					assertFormulaResultsIn(original.getRecordsFrom(175L), new int[]{1});
				}
			);
		}

		@Test
		@DisplayName("getRecordsTo inside a transaction observes pending additions")
		void shouldReturnToQueryReflectingInTransactionAddsWhenInsideTransaction() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);

			assertStateAfterRollback(
				RangeIndexTest.this.tested,
				original -> {
					// pending add inside the transaction; record 2 starts before and ends after 175
					original.addRecord(50L, 300L, 2);
					// MVCC view includes the pending record 2 as still valid at 175
					assertFormulaResultsIn(original.getRecordsTo(175L), new int[]{1, 2});
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					// after rollback only the pre-transaction record 1 is visible
					assertFormulaResultsIn(original.getRecordsTo(175L), new int[]{1});
				}
			);
		}

		@Test
		@DisplayName("getRecordsEnvelopingInclusive inside a transaction observes pending additions")
		void shouldReturnEnvelopingReflectingInTransactionAddsWhenInsideTransaction() {
			RangeIndexTest.this.tested.addRecord(100L, 200L, 1);

			assertStateAfterRollback(
				RangeIndexTest.this.tested,
				original -> {
					// pending add inside the transaction
					original.addRecord(150L, 300L, 2);
					// MVCC view envelopes 175 with both the committed record 1 and the pending record 2
					assertFormulaResultsIn(original.getRecordsEnvelopingInclusive(175L), new int[]{1, 2});
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					// after rollback only the pre-transaction record 1 envelopes 175
					assertFormulaResultsIn(original.getRecordsEnvelopingInclusive(175L), new int[]{1});
				}
			);
		}

		@Test
		@DisplayName("getRecordsWithRangesOverlapping inside a transaction observes pending additions")
		void shouldReturnOverlappingReflectingInTransactionAddsWhenInsideTransaction() {
			RangeIndexTest.this.tested.addRecord(1, 4, 1);

			assertStateAfterRollback(
				RangeIndexTest.this.tested,
				original -> {
					// pending add inside the transaction
					original.addRecord(3, 5, 2);
					// MVCC view sees both records overlapping the [3,4] window
					assertFormulaResultsIn(original.getRecordsWithRangesOverlapping(3, 4), new int[]{1, 2});
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					// after rollback only the pre-transaction record 1 overlaps the window
					assertFormulaResultsIn(original.getRecordsWithRangesOverlapping(3, 4), new int[]{1});
				}
			);
		}
	}

	@Nested
	@DisplayName("Structural inspection")
	class StructuralInspection {

		@Test
		@DisplayName("The (from, to, recordIds) constructor populates every record over a single range")
		void shouldBuildIndexFromRecordIdArrayOverSingleRange() {
			final RangeIndex built = new RangeIndex(5L, 10L, new int[]{1, 2, 3});

			assertTrue(built.contains(1));
			assertTrue(built.contains(2));
			assertTrue(built.contains(3));
			assertFalse(built.contains(4));
			assertArrayEquals(new int[]{1, 2, 3}, built.getAllRecords().getArray());
			assertEquals(3, built.size());
			// all three records share the same [5, 10] span, valid at 7
			assertFormulaResultsIn(built.getRecordsEnvelopingInclusive(7L), new int[]{1, 2, 3});
		}

		@Test
		@DisplayName("contains, getAllRecords and size over a non-trivial index")
		void shouldReportContainsAllRecordsAndSizeOverNonTrivialIndex() {
			// duplicate thresholds (two records sharing 5..10), same-record multi-range edges and sentinels
			RangeIndexTest.this.tested.addRecord(Long.MIN_VALUE, Long.MAX_VALUE, 1);
			RangeIndexTest.this.tested.addRecord(5, 10, 2);
			RangeIndexTest.this.tested.addRecord(5, 10, 3);
			RangeIndexTest.this.tested.addRecord(1, 5, 4);
			RangeIndexTest.this.tested.addRecord(10, 20, 4);

			assertTrue(RangeIndexTest.this.tested.contains(1));
			assertTrue(RangeIndexTest.this.tested.contains(2));
			assertTrue(RangeIndexTest.this.tested.contains(3));
			assertTrue(RangeIndexTest.this.tested.contains(4));
			assertFalse(RangeIndexTest.this.tested.contains(99));

			assertArrayEquals(new int[]{1, 2, 3, 4}, RangeIndexTest.this.tested.getAllRecords().getArray());
			assertEquals(4, RangeIndexTest.this.tested.size());
		}

		@Test
		@DisplayName("getRangePointCount counts thresholds including the MIN/MAX sentinels")
		void shouldExposeRangePointCountIncludingSentinels() {
			// empty index carries only the MIN/MAX sentinels
			assertEquals(2, RangeIndexTest.this.tested.getRangePointCount());
			RangeIndexTest.this.tested.addRecord(5, 10, 1);
			// two fresh thresholds added
			assertEquals(4, RangeIndexTest.this.tested.getRangePointCount());
			RangeIndexTest.this.tested.addRecord(5, 20, 2);
			// threshold 5 is shared, threshold 20 is new
			assertEquals(5, RangeIndexTest.this.tested.getRangePointCount());
		}

		@Test
		@DisplayName("rangesIterator walks range points in ascending threshold order")
		void shouldIterateRangesInThresholdOrder() {
			RangeIndexTest.this.tested.addRecord(5, 10, 1);
			RangeIndexTest.this.tested.addRecord(1, 7, 2);

			final java.util.Iterator<TransactionalRangePoint> it = RangeIndexTest.this.tested.rangesIterator();
			long previous = Long.MIN_VALUE;
			boolean first = true;
			final java.util.List<Long> thresholds = new java.util.ArrayList<>();
			while (it.hasNext()) {
				final TransactionalRangePoint point = it.next();
				if (!first) {
					assertTrue(point.getThreshold() > previous, "Range points must be sorted ascending by threshold!");
				}
				previous = point.getThreshold();
				first = false;
				thresholds.add(point.getThreshold());
			}
			assertEquals(
				List.of(Long.MIN_VALUE, 1L, 5L, 7L, 10L, Long.MAX_VALUE),
				thresholds
			);
		}

		@Test
		@DisplayName("getRanges returns the range-point array in ascending threshold order")
		void shouldReturnRangesArrayInThresholdOrder() {
			RangeIndexTest.this.tested.addRecord(5, 10, 1);
			RangeIndexTest.this.tested.addRecord(1, 7, 2);

			final RangePoint<?>[] ranges = RangeIndexTest.this.tested.getRanges();
			assertEquals(6, ranges.length);
			assertEquals(Long.MIN_VALUE, ranges[0].getThreshold());
			assertEquals(1L, ranges[1].getThreshold());
			assertEquals(5L, ranges[2].getThreshold());
			assertEquals(7L, ranges[3].getThreshold());
			assertEquals(10L, ranges[4].getThreshold());
			assertEquals(Long.MAX_VALUE, ranges[5].getThreshold());
		}
	}

	@Nested
	@DisplayName("Serialization (Kryo)")
	class Serialization {

		@Test
		@DisplayName("Round-trips through the Kryo serializer preserving content equality")
		void shouldSerializeAndDeserialize() {
			RangeIndexTest.this.tested.addRecord(5, 10, 1);
			RangeIndexTest.this.tested.addRecord(5, 10, 2);
			RangeIndexTest.this.tested.addRecord(7, 10, 3);
			RangeIndexTest.this.tested.addRecord(1, 5, 4);

			final Kryo kryo = new Kryo();

			kryo.register(RangeIndex.class, new IntRangeIndexSerializer());
			kryo.register(TransactionalRangePoint.class, new TransactionalIntRangePointSerializer());
			kryo.register(TransactionalBitmap.class, new TransactionalIntegerBitmapSerializer());
			kryo.register(int[].class);

			final Output output = new Output(1024, -1);
			kryo.writeObject(output, RangeIndexTest.this.tested);
			output.flush();

			byte[] bytes = output.getBuffer();

			final RangeIndex deserializedTested = kryo.readObject(new Input(bytes), RangeIndex.class);
			assertEquals(RangeIndexTest.this.tested, deserializedTested);
		}
	}

	@Nested
	@DisplayName("Structural sharing and identity preservation on commit")
	class StructuralSharing {

		@Test
		@DisplayName("An untouched loaded index keeps its identity across a commit")
		void untouchedIndexReturnsSameInstanceOnCommit() {
			// a freshly built (loaded) index that is NOT mutated inside the transaction must keep its identity across the
			// commit so the enclosing transactional map can structurally share it
			final RangeIndex base = new RangeIndex(
				new TransactionalRangePoint[]{
					new TransactionalRangePoint(Long.MIN_VALUE),
					new TransactionalRangePoint(5L, new int[]{1, 2}, new int[0]),
					new TransactionalRangePoint(10L, new int[0], new int[]{1, 2}),
					new TransactionalRangePoint(Long.MAX_VALUE)
				}
			);

			assertStateAfterCommit(
				base,
				original -> {
					// intentionally no mutation inside the transaction
				},
				Assertions::assertSame
			);
		}

		@Test
		@DisplayName("An empty untouched index keeps its identity across a commit")
		void emptyUntouchedIndexReturnsSameInstanceOnCommit() {
			// the no-arg constructor produces a clean index; an empty transaction over it must not rebuild it
			assertStateAfterCommit(
				new RangeIndex(),
				original -> {
					// intentionally no mutation inside the transaction
				},
				Assertions::assertSame
			);
		}

		@Test
		@DisplayName("An empty (read-only) transaction does not orphan a dirty layer")
		void emptyTransactionDoesNotOrphanDirtyLayer() {
			// committing a transaction in which the index was never touched must fully sweep every transactional layer;
			// assertStateAfterCommit invokes verifyLayerWasFullySwept, which fails the test if the dirty layer leaks
			final RangeIndex base = new RangeIndex(
				new TransactionalRangePoint[]{
					new TransactionalRangePoint(Long.MIN_VALUE),
					new TransactionalRangePoint(5L, new int[]{1}, new int[0]),
					new TransactionalRangePoint(10L, new int[0], new int[]{1}),
					new TransactionalRangePoint(Long.MAX_VALUE)
				}
			);

			assertStateAfterCommit(
				base,
				original -> {
					// read-only access inside the transaction must keep the index non-dirty
					assertTrue(original.contains(1));
				},
				(original, committedVersion) -> {
					// identity is preserved and the read-only view is intact
					assertSame(original, committedVersion);
					assertTrue(committedVersion.contains(1));
				}
			);
		}

		@Test
		@DisplayName("Any mutation inside the transaction produces a fresh instance on commit")
		void mutatedIndexReturnsFreshInstanceOnCommit() {
			// any mutation inside the transaction must produce a NEW instance distinct from the original, carrying the
			// post-commit contents
			final RangeIndex base = new RangeIndex();
			base.addRecord(5, 10, 1);

			assertStateAfterCommit(
				base,
				original -> original.addRecord(7, 12, 2),
				(original, committedVersion) -> {
					assertNotSame(original, committedVersion);
					assertTrue(committedVersion.contains(1));
					assertTrue(committedVersion.contains(2));
					assertArrayEquals(new int[]{1, 2}, committedVersion.getAllRecords().getArray());
				}
			);
		}

		@Test
		@DisplayName("A remove-only transaction also produces a fresh instance on commit")
		void removeOnlyMutationReturnsFreshInstanceOnCommit() {
			// removeRecord must raise the dirty flag exactly like addRecord, so a remove-only transaction also rebuilds
			final RangeIndex base = new RangeIndex();
			base.addRecord(5, 10, 1);
			base.addRecord(5, 10, 2);

			assertStateAfterCommit(
				base,
				original -> original.removeRecord(5, 10, 1),
				(original, committedVersion) -> {
					assertNotSame(original, committedVersion);
					assertFalse(committedVersion.contains(1));
					assertTrue(committedVersion.contains(2));
				}
			);
		}

		@Test
		@DisplayName("Identity is preserved across repeated untouched commits")
		void identityPreservedAcrossRepeatedUntouchedCommits() {
			// an untouched index committed through several successive transactions keeps the very same identity each time,
			// which is the structural-sharing guarantee the enclosing map relies on
			RangeIndex current = new RangeIndex(
				new TransactionalRangePoint[]{
					new TransactionalRangePoint(Long.MIN_VALUE),
					new TransactionalRangePoint(100L, new int[]{1}, new int[0]),
					new TransactionalRangePoint(200L, new int[0], new int[]{1}),
					new TransactionalRangePoint(Long.MAX_VALUE)
				}
			);

			for (int round = 0; round < 3; round++) {
				final RangeIndex expectedSame = current;
				final RangeIndex[] holder = new RangeIndex[1];
				assertStateAfterCommit(
					current,
					original -> {
						// no mutation in any round
					},
					(original, committedVersion) -> {
						assertSame(expectedSame, committedVersion);
						holder[0] = committedVersion;
					}
				);
				current = holder[0];
			}
		}

		@Test
		@DisplayName("The identity-preserved instance keeps its primed cache valid after an untouched commit")
		void retainedInstanceCacheStaysValidAfterUntouchedCommit() {
			// the identity-preserved instance keeps its "valid at now" memoization; because its data is unchanged the cache
			// must still serve the correct result after the commit
			final RangeIndex base = new RangeIndex(
				new TransactionalRangePoint[]{
					new TransactionalRangePoint(Long.MIN_VALUE),
					new TransactionalRangePoint(100L, new int[]{1}, new int[0]),
					new TransactionalRangePoint(150L, new int[]{2}, new int[0]),
					new TransactionalRangePoint(200L, new int[0], new int[]{1}),
					new TransactionalRangePoint(300L, new int[0], new int[]{2}),
					new TransactionalRangePoint(Long.MAX_VALUE)
				}
			);
			// prime the cache before the transaction
			base.getRecordsValidNowFormula(175L);
			final RangeIndex.EnvelopingNowCache primedCache = base.envelopingNowCache;
			assertNotNull(primedCache);

			assertStateAfterCommit(
				base,
				original -> {
					// no mutation - the index keeps its identity and its primed cache
				},
				(original, committedVersion) -> {
					assertSame(original, committedVersion);
					// the retained cache instance survives the commit untouched
					assertSame(primedCache, committedVersion.envelopingNowCache);
					// and still serves the correct records valid at 175
					assertArrayEquals(
						new int[]{1, 2},
						committedVersion.getRecordsValidNowFormula(175L).compute().getArray()
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Constructor validation")
	class ConstructorValidation {

		@Test
		@DisplayName("Rejects fewer than two range points")
		void shouldThrowWhenFewerThanTwoRanges() {
			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new RangeIndex(new TransactionalRangePoint[]{new TransactionalRangePoint(Long.MIN_VALUE)})
			);
			assertEquals("At least two ranges are expected!", ex.getMessage());
		}

		@Test
		@DisplayName("Rejects a first threshold other than Long.MIN_VALUE")
		void shouldThrowWhenFirstThresholdIsNotMinValue() {
			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new RangeIndex(
					new TransactionalRangePoint[]{
						new TransactionalRangePoint(0L),
						new TransactionalRangePoint(Long.MAX_VALUE)
					}
				)
			);
			assertEquals("First range should have threshold Long.MIN_VALUE!", ex.getMessage());
		}

		@Test
		@DisplayName("Rejects a last threshold other than Long.MAX_VALUE")
		void shouldThrowWhenLastThresholdIsNotMaxValue() {
			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new RangeIndex(
					new TransactionalRangePoint[]{
						new TransactionalRangePoint(Long.MIN_VALUE),
						new TransactionalRangePoint(0L)
					}
				)
			);
			assertEquals("Last range should have threshold Long.MAX_VALUE!", ex.getMessage());
		}

		@Test
		@DisplayName("Rejects non-monotonic (duplicate or descending) thresholds")
		void shouldThrowWhenThresholdsAreNotMonotonic() {
			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new RangeIndex(
					new TransactionalRangePoint[]{
						new TransactionalRangePoint(Long.MIN_VALUE),
						new TransactionalRangePoint(5L, new int[]{1}, new int[0]),
						new TransactionalRangePoint(5L, new int[0], new int[]{1}),
						new TransactionalRangePoint(Long.MAX_VALUE)
					}
				)
			);
			assertTrue(
				ex.getMessage().startsWith("Range values are not monotonic"),
				"Unexpected message: " + ex.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hash code")
	class EqualsHashCode {

		@Test
		@DisplayName("Two indexes with identical content are equal and share a hash code")
		void shouldBeEqualAndShareHashCodeWhenContentIdentical() {
			final RangeIndex a = new RangeIndex();
			a.addRecord(5, 10, 1);
			a.addRecord(7, 12, 2);
			final RangeIndex b = new RangeIndex();
			b.addRecord(5, 10, 1);
			b.addRecord(7, 12, 2);

			assertEquals(a, b);
			assertEquals(b, a);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("Indexes differing in record placement are not equal")
		void shouldNotBeEqualWhenStartsOrEndsDiffer() {
			final RangeIndex a = new RangeIndex();
			a.addRecord(5, 10, 1);
			final RangeIndex b = new RangeIndex();
			b.addRecord(5, 10, 2);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("Indexes differing in thresholds are not equal")
		void shouldNotBeEqualWhenThresholdsDiffer() {
			final RangeIndex a = new RangeIndex();
			a.addRecord(5, 10, 1);
			final RangeIndex b = new RangeIndex();
			b.addRecord(5, 11, 1);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("Indexes differing in range-point count are not equal")
		void shouldNotBeEqualWhenLengthsDiffer() {
			final RangeIndex a = new RangeIndex();
			a.addRecord(5, 10, 1);
			final RangeIndex b = new RangeIndex();
			b.addRecord(5, 10, 1);
			b.addRecord(20, 30, 2);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("Is not equal to null or to an instance of a different type")
		void shouldNotBeEqualToNullOrDifferentType() {
			final RangeIndex a = new RangeIndex();
			a.addRecord(5, 10, 1);

			assertNotEquals(null, a);
			assertNotEquals("not a range index", a);
		}

		@Test
		@DisplayName("Equality ignores the transient \"valid now\" cache")
		void shouldIgnoreEnvelopingNowCacheInEquality() {
			final RangeIndex primed = new RangeIndex();
			primed.addRecord(5, 10, 1);
			final RangeIndex unprimed = new RangeIndex();
			unprimed.addRecord(5, 10, 1);

			// prime the cache on one instance only
			primed.getRecordsValidNowFormula(7L);
			assertNotNull(primed.envelopingNowCache);
			assertNull(unprimed.envelopingNowCache);

			// the transient cache must not affect equality or hashCode
			assertEquals(primed, unprimed);
			assertEquals(primed.hashCode(), unprimed.hashCode());
		}
	}

	@Nested
	@DisplayName("B+ tree structural stress (cross-leaf splits and merges)")
	class BPlusTreeStress {

		/**
		 * Number of distinct thresholds to force the threshold → range-point B+ tree to split across many leaves.
		 * The production leaf block size is `512`; each record below uses a unique [from, to] pair, so this produces
		 * `2 * RECORD_COUNT` distinct thresholds — well over a single leaf — exercising leaf and internal-node splits.
		 */
		private static final int RECORD_COUNT = 2_000;

		@Test
		@DisplayName("Range queries and removals stay correct across leaf splits and merges")
		void shouldRemainConsistentAcrossLeafSplitsAndMerges() {
			final RangeIndex stress = new RangeIndex();
			// each record i occupies a unique, non-overlapping span [10*i, 10*i + 5]; thresholds are strictly ascending
			for (int i = 0; i < RECORD_COUNT; i++) {
				stress.addRecord(10L * i, 10L * i + 5L, i + 1);
			}

			assertEquals(RECORD_COUNT, stress.size());
			// 2 sentinels + 2 fresh thresholds per record
			assertEquals(2 + 2 * RECORD_COUNT, stress.getRangePointCount());

			// a record near the very end is reachable only after walking across every leaf boundary; each record's
			// closed span is enveloped at its own midpoint, isolating exactly that record
			final int lastRecordId = RECORD_COUNT;
			final long lastFrom = 10L * (RECORD_COUNT - 1);
			assertTrue(stress.contains(lastRecordId));
			assertFormulaResultsIn(
				stress.getRecordsEnvelopingInclusive(lastFrom + 2L), new int[]{lastRecordId}
			);
			// a record in the deep middle of the tree is likewise isolated by an envelope at its midpoint
			final int midRecordId = RECORD_COUNT / 2;
			final long midFrom = 10L * (midRecordId - 1);
			assertFormulaResultsIn(
				stress.getRecordsEnvelopingInclusive(midFrom + 2L), new int[]{midRecordId}
			);

			// bounded overlapping query whose window spans many leaves returns exactly the records in that window;
			// records firstWindowId..lastWindowId occupy [10*(id-1), 10*(id-1)+5]
			final int firstWindowId = RECORD_COUNT / 4 + 1;
			final int lastWindowId = 3 * RECORD_COUNT / 4;
			final long windowFrom = 10L * (firstWindowId - 1);
			final long windowTo = 10L * (lastWindowId - 1) + 5L;
			final int[] expectedWindow = new int[lastWindowId - firstWindowId + 1];
			for (int i = 0; i < expectedWindow.length; i++) {
				expectedWindow[i] = firstWindowId + i;
			}
			assertFormulaResultsIn(
				stress.getRecordsWithRangesOverlapping(windowFrom, windowTo), expectedWindow
			);

			// overlapping query over the full key range returns every record
			final int[] allIds = new int[RECORD_COUNT];
			for (int i = 0; i < RECORD_COUNT; i++) {
				allIds[i] = i + 1;
			}
			assertFormulaResultsIn(
				stress.getRecordsWithRangesOverlapping(Long.MIN_VALUE, Long.MAX_VALUE), allIds
			);

			// remove every other record - this deletes obsolete points and forces leaf merges/rebalancing
			for (int i = 0; i < RECORD_COUNT; i += 2) {
				stress.removeRecord(10L * i, 10L * i + 5L, i + 1);
			}

			final int remaining = RECORD_COUNT / 2;
			assertEquals(remaining, stress.size());
			assertFalse(stress.contains(1));
			assertTrue(stress.contains(2));
			assertFalse(stress.contains(RECORD_COUNT - 1));
			assertTrue(stress.contains(RECORD_COUNT));

			// the surviving (even-id) records are still queryable end-to-end after the merges
			final int[] survivors = new int[remaining];
			for (int i = 0; i < remaining; i++) {
				survivors[i] = 2 * (i + 1);
			}
			assertArrayEquals(survivors, stress.getAllRecords().getArray());
			assertFormulaResultsIn(
				stress.getRecordsWithRangesOverlapping(Long.MIN_VALUE, Long.MAX_VALUE), survivors
			);
		}
	}

	@Nested
	@DisplayName("Granular paged emission")
	class PagedEmission {

		/**
		 * Builds a range index with `recordCount` records, each a distinct `[from, to]` interval, so the threshold tree
		 * spans many leaves (block size 512 — two thresholds per record means a few hundred records suffice).
		 */
		@Nonnull
		private static RangeIndex multiLeafRange(int recordCount) {
			final RangeIndex index = new RangeIndex();
			for (int i = 0; i < recordCount; i++) {
				index.addRecord(i * 10L, i * 10L + 5, i);
			}
			return index;
		}

		@Test
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		@DisplayName("a small (single-leaf) range stays inline (SINGLE)")
		void shouldNotPageSingleLeafRange() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(10L, 20L, 1);
			index.addRecord(30L, 40L, 2);
			assertFalse(index.isPaged(), "A small (single-leaf) range must stay inline (SINGLE).");
		}

		@Test
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		@DisplayName("a multi-leaf range pages and its leaf pages reconstruct the full range-point array in order")
		void shouldPageMultiLeafRangeAndReconstruct() {
			final RangeIndex index = multiLeafRange(400);
			assertTrue(index.isPaged(), "A large (multi-leaf) range must be paged.");

			final PageEmission<RangeIndex.RangePage> emission = index.collectChangedPages();
			final int[] ordered = emission.orderedPageSequences();
			assertTrue(ordered.length >= 2, "A paged range must span multiple leaf pages.");
			for (int i = 0; i < ordered.length; i++) {
				assertEquals(i, ordered[i], "Fresh leaves must allocate a dense ascending page sequence.");
			}
			assertEquals(ordered.length - 1, emission.highWaterPageSequence(), "High-water must be the last page.");
			assertEquals(
				ordered.length, emission.changedPages().size(),
				"On the first emission (empty baseline) every leaf page is changed."
			);

			// concatenating the leaf pages in page-sequence order must equal the whole-tree materialization
			final Map<Integer, TransactionalRangePoint[]> byPageSequence = new HashMap<>();
			for (final RangeIndex.RangePage page : emission.changedPages()) {
				byPageSequence.put(page.pageSequence(), page.points());
			}
			final List<Long> reconstructedThresholds = new ArrayList<>();
			for (final int pageSequence : ordered) {
				final TransactionalRangePoint[] pagePoints = byPageSequence.get(pageSequence);
				assertNotNull(pagePoints, "Every ordered page must have been emitted.");
				for (final TransactionalRangePoint point : pagePoints) {
					reconstructedThresholds.add(point.getThreshold());
				}
			}
			final RangePoint<?>[] expected = index.getRanges();
			assertEquals(expected.length, reconstructedThresholds.size(), "Point count must match the whole tree.");
			for (int i = 0; i < expected.length; i++) {
				assertEquals(expected[i].getThreshold(), reconstructedThresholds.get(i), "threshold @ " + i);
			}
		}

		@Test
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		@DisplayName("after publishing the baseline, an unchanged range re-writes nothing and only a changed leaf re-emits")
		void shouldSuppressUnchangedLeavesAfterPublish() {
			final RangeIndex index = multiLeafRange(400);
			final PageEmission<RangeIndex.RangePage> first = index.collectChangedPages();
			assertEquals(
				first.orderedPageSequences().length, first.changedPages().size(), "First emission writes every leaf."
			);
			index.getPageStreamRegistry().publishStaged();

			final PageEmission<RangeIndex.RangePage> unchanged = index.collectChangedPages();
			assertTrue(
				unchanged.changedPages().isEmpty(), "An unchanged range must re-write no leaf pages after publish."
			);
			index.getPageStreamRegistry().publishStaged();

			// add a record on the smallest threshold → only the first leaf (page 0) is re-emitted
			index.addRecord(0L, 5L, 9999);
			final PageEmission<RangeIndex.RangePage> afterChange = index.collectChangedPages();
			assertEquals(1, afterChange.changedPages().size(), "Only the changed leaf is re-written.");
			assertEquals(
				0, afterChange.changedPages().get(0).pageSequence(), "The first leaf (holding the smallest threshold) changed."
			);
		}

		@Test
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		@DisplayName("removing a record from a surviving point re-emits its leaf (in-place value mutation is detected)")
		void shouldReemitLeafWhenRecordRemovedFromSurvivingPoint() {
			// a second record shares the smallest point's from/to thresholds, so its record set holds two ids and
			// survives a single removal — the removal mutates the range point's bitmap in place, leaving the holding
			// leaf's own columns untouched. The change-detection must still flag that leaf (the bug the dirty flag fixes).
			final RangeIndex index = multiLeafRange(400);
			assertTrue(index.isPaged(), "A multi-leaf range must be paged.");
			index.addRecord(0L, 5L, 9999);
			index.collectChangedPages();
			index.getPageStreamRegistry().publishStaged();
			assertTrue(
				index.collectChangedPages().changedPages().isEmpty(),
				"An unchanged range must re-write no leaf pages after publish."
			);
			index.getPageStreamRegistry().publishStaged();

			// remove the shared record; record 0 keeps the point alive (no delete, pure in-place bitmap edit)
			index.removeRecord(0L, 5L, 9999);
			final PageEmission<RangeIndex.RangePage> afterRemove = index.collectChangedPages();
			assertFalse(
				afterRemove.changedPages().isEmpty(),
				"Removing a record from a surviving point must re-emit its leaf (in-place value mutation)."
			);
		}

		@Test
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		@DisplayName("a leaf merge reports the dropped page as freed (so it can be removed, not leaked)")
		void shouldReportFreedPagesWhenLeafMergesAway() {
			final RangeIndex index = multiLeafRange(400);
			final int[] pagesBefore = index.collectChangedPages().orderedPageSequences();
			index.getPageStreamRegistry().publishStaged();
			final Set<Integer> baseline = new HashSet<>();
			for (final int sequence : pagesBefore) {
				baseline.add(sequence);
			}

			// drop a large contiguous block of records → many thresholds become obsolete and leaves merge away
			for (int i = 0; i < 250; i++) {
				index.removeRecord(i * 10L, i * 10L + 5, i);
			}

			final PageEmission<RangeIndex.RangePage> afterShrink = index.collectChangedPages();
			assertTrue(
				afterShrink.orderedPageSequences().length < pagesBefore.length, "Shrinking must drop at least one leaf page."
			);
			assertTrue(afterShrink.freedPageSequences().length > 0, "Dropped leaf pages must be reported as freed.");
			final Set<Integer> live = new HashSet<>();
			for (final int sequence : afterShrink.orderedPageSequences()) {
				live.add(sequence);
			}
			for (final int freed : afterShrink.freedPageSequences()) {
				assertFalse(live.contains(freed), "A freed page must not be live.");
				assertTrue(baseline.contains(freed), "A freed page must have been live in the prior baseline.");
			}
		}

		@Test
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		@DisplayName("a boundary-stable reload restores page identities and suppresses the first commit")
		void shouldReloadBoundaryStableAndSuppressFirstCommit() {
			final RangeIndex index = multiLeafRange(400);
			final PageEmission<RangeIndex.RangePage> emission = index.collectChangedPages();
			index.getPageStreamRegistry().publishStaged();
			final int[] orderedPageSequences = emission.orderedPageSequences();
			final int highWater = emission.highWaterPageSequence();
			final Map<Integer, TransactionalRangePoint[]> byPageSequence = new HashMap<>();
			for (final RangeIndex.RangePage page : emission.changedPages()) {
				byPageSequence.put(page.pageSequence(), page.points());
			}
			final TransactionalRangePoint[][] perPagePoints = new TransactionalRangePoint[orderedPageSequences.length][];
			for (int i = 0; i < orderedPageSequences.length; i++) {
				perPagePoints[i] = byPageSequence.get(orderedPageSequences[i]);
			}

			// reload boundary-stable (one leaf per page, page identities + change-detection baseline restored)
			final RangeIndex reloaded = RangeIndex.fromPersistedPages(
				"attribute `test`", orderedPageSequences, perPagePoints, highWater
			);
			assertTrue(reloaded.isPaged(), "Reloaded range must still be paged.");
			assertEquals(index, reloaded, "Reloaded range must be content-equal to the original.");

			// first post-reload commit must rewrite nothing and free nothing (identities + baseline survived the reload)
			final PageEmission<RangeIndex.RangePage> afterReload = reloaded.collectChangedPages();
			assertArrayEquals(
				orderedPageSequences, afterReload.orderedPageSequences(), "Reload must preserve every leaf's page sequence."
			);
			assertTrue(
				afterReload.changedPages().isEmpty(), "A boundary-stable reload must rewrite no leaf pages."
			);
			assertEquals(0, afterReload.freedPageSequences().length, "A boundary-stable reload must free no pages.");
			assertEquals(highWater, afterReload.highWaterPageSequence(), "Reload must restore the high-water.");
		}

	}

	private static long timestampForDate(int day, int month) {
		return LocalDate.of(2019, month, day).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
	}

	private static List<Bitmap> asListOfBitmaps(int[]... recordIds) {
		return Arrays.stream(recordIds)
			.map(RoaringBitmapBackedBitmap::fromArray)
			.map(BaseBitmap::new)
			.collect(Collectors.toList());
	}


}
