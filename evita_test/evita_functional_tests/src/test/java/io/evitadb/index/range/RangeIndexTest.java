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
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.range.RangeIndex.StartsEndsDTO;
import io.evitadb.index.range.RangePoint;
import io.evitadb.store.index.serializer.IntRangeIndexSerializer;
import io.evitadb.store.index.serializer.TransactionalIntRangePointSerializer;
import io.evitadb.store.index.serializer.TransactionalIntegerBitmapSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
class RangeIndexTest {
	private final RangeIndex tested = new RangeIndex();

	@Test
	void cacheFieldStartsNull() {
		assertNull(this.tested.envelopingNowCache);
	}

	@Test
	void getRecordsValidNowReturnsSameAsEnvelopingInclusiveForBetweenBucketCase() {
		this.tested.addRecord(100L, 200L, 1);
		this.tested.addRecord(150L, 300L, 2);
		final long now = 175L;

		final var expected = this.tested.getRecordsEnvelopingInclusive(now);
		final var actual = this.tested.getRecordsValidNowFormula(now);

		assertArrayEquals(expected.compute().getArray(), actual.compute().getArray());
	}

	@Test
	void getRecordsValidNowPopulatesCacheWithFlankingBoundsOnMiss() {
		this.tested.addRecord(100L, 200L, 1);
		this.tested.addRecord(150L, 300L, 2);

		this.tested.getRecordsValidNowFormula(175L);

		final var cache = this.tested.envelopingNowCache;
		assertNotNull(cache);
		assertEquals(151L, cache.validFromInclusive());
		assertEquals(199L, cache.validToInclusive());
		assertArrayEquals(new int[]{1, 2}, cache.result().getArray());
	}

	@Test
	void getRecordsValidNowReusesCachedBitmapForSecondCallInSameBucket() {
		this.tested.addRecord(100L, 200L, 1);
		this.tested.addRecord(150L, 300L, 2);

		this.tested.getRecordsValidNowFormula(160L);
		final var snapshot = this.tested.envelopingNowCache;
		assertNotNull(snapshot);

		this.tested.getRecordsValidNowFormula(180L);
		assertSame(snapshot, this.tested.envelopingNowCache);
	}

	@Test
	void getRecordsValidNowRecomputesAfterNowCrossesBoundary() {
		this.tested.addRecord(100L, 200L, 1);
		this.tested.addRecord(150L, 300L, 2);

		this.tested.getRecordsValidNowFormula(175L);
		final var first = this.tested.envelopingNowCache;

		this.tested.getRecordsValidNowFormula(250L);
		final var second = this.tested.envelopingNowCache;

		assertNotSame(first, second);
		assertEquals(201L, second.validFromInclusive());
		assertEquals(299L, second.validToInclusive());
		assertArrayEquals(new int[]{2}, second.result().getArray());
	}

	@Test
	void getRecordsValidNowReturnsExactThresholdBoundariesOnHit() {
		this.tested.addRecord(100L, 200L, 1);
		this.tested.addRecord(150L, 300L, 2);

		this.tested.getRecordsValidNowFormula(150L);

		final var cache = this.tested.envelopingNowCache;
		assertNotNull(cache);
		assertEquals(150L, cache.validFromInclusive());
		assertEquals(150L, cache.validToInclusive());
		assertArrayEquals(new int[]{1, 2}, cache.result().getArray());
	}

	@Test
	void getRecordsValidNowRecomputesWhenLeavingExactThresholdBucket() {
		this.tested.addRecord(100L, 200L, 1);
		this.tested.addRecord(150L, 300L, 2);

		this.tested.getRecordsValidNowFormula(150L);
		final var first = this.tested.envelopingNowCache;

		this.tested.getRecordsValidNowFormula(151L);
		assertNotSame(first, this.tested.envelopingNowCache);
	}

	@Test
	void getRecordsValidNowOnEmptyIndexReturnsEmptyAndCachesMaxInterval() {
		final var formula = this.tested.getRecordsValidNowFormula(0L);

		assertTrue(formula.compute().isEmpty());
		final var cache = this.tested.envelopingNowCache;
		assertNotNull(cache);
		assertEquals(Long.MIN_VALUE + 1, cache.validFromInclusive());
		assertEquals(Long.MAX_VALUE - 1, cache.validToInclusive());
		assertTrue(cache.result().isEmpty());
	}

	@Test
	void getRecordsValidNowBeforeFirstThresholdReturnsEmpty() {
		this.tested.addRecord(100L, 200L, 1);

		final var formula = this.tested.getRecordsValidNowFormula(50L);

		assertTrue(formula.compute().isEmpty());
		final var cache = this.tested.envelopingNowCache;
		assertNotNull(cache);
		assertEquals(Long.MIN_VALUE + 1, cache.validFromInclusive());
		assertEquals(99L, cache.validToInclusive());
	}

	@Test
	void getRecordsValidNowAfterLastThresholdReturnsEmpty() {
		this.tested.addRecord(100L, 200L, 1);

		final var formula = this.tested.getRecordsValidNowFormula(500L);

		assertTrue(formula.compute().isEmpty());
		final var cache = this.tested.envelopingNowCache;
		assertNotNull(cache);
		assertEquals(201L, cache.validFromInclusive());
		assertEquals(Long.MAX_VALUE - 1, cache.validToInclusive());
	}

	@Test
	void nonTransactionalAddRecordInvalidatesCache() {
		this.tested.addRecord(100L, 200L, 1);
		this.tested.getRecordsValidNowFormula(150L);
		assertNotNull(this.tested.envelopingNowCache);

		this.tested.addRecord(120L, 180L, 2);

		assertNull(this.tested.envelopingNowCache);
	}

	@Test
	void nonTransactionalRemoveRecordInvalidatesCache() {
		this.tested.addRecord(100L, 200L, 1);
		this.tested.addRecord(120L, 180L, 2);
		this.tested.getRecordsValidNowFormula(150L);
		assertNotNull(this.tested.envelopingNowCache);

		this.tested.removeRecord(120L, 180L, 2);

		assertNull(this.tested.envelopingNowCache);
	}

	@Test
	void recomputeAfterInvalidationReflectsNewState() {
		this.tested.addRecord(100L, 200L, 1);
		this.tested.getRecordsValidNowFormula(150L);

		this.tested.addRecord(140L, 160L, 2);
		final var formula = this.tested.getRecordsValidNowFormula(150L);

		assertArrayEquals(new int[]{1, 2}, formula.compute().getArray());
	}

	@Test
	void transactionalAddDoesNotInvalidateCommittedCache() {
		this.tested.addRecord(100L, 200L, 1);
		this.tested.getRecordsValidNowFormula(150L);
		final var before = this.tested.envelopingNowCache;
		assertNotNull(before);

		assertStateAfterRollback(
			this.tested,
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
	void transactionalReadBypassesCacheAndSeesTxLayer() {
		this.tested.addRecord(100L, 200L, 1);
		this.tested.getRecordsValidNowFormula(150L);
		final var before = this.tested.envelopingNowCache;
		assertNotNull(before);
		assertArrayEquals(new int[]{1}, before.result().getArray());

		assertStateAfterRollback(
			this.tested,
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
	void commitProducesFreshRangeIndexWithNullCache() {
		this.tested.addRecord(100L, 200L, 1);
		this.tested.getRecordsValidNowFormula(150L);
		assertNotNull(this.tested.envelopingNowCache);

		assertStateAfterCommit(
			this.tested,
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

	@Test
	void concurrentReadersAgreeOnResult() throws Exception {
		for (int i = 0; i < 1000; i++) {
			this.tested.addRecord(i * 10L, i * 10L + 5L, i + 1);
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
					return this.tested.getRecordsValidNowFormula(now).compute().getArray();
				}));
			}
			final int[] expected = this.tested.getRecordsEnvelopingInclusive(now).compute().getArray();
			for (java.util.concurrent.Future<int[]> f : futures) {
				assertArrayEquals(expected, f.get());
			}
		} finally {
			pool.shutdown();
		}
	}

	@Test
	void shouldAddTransactionalItemsAndRollback() {
		assertStateAfterRollback(
			this.tested,
			original -> {
				original.addRecord(5, 10, 1);
				original.addRecord(5, 10, 2);
				original.addRecord(7, 10, 3);
				original.addRecord(1, 5, 4);

				assertTrue(this.tested.contains(1));
				assertTrue(this.tested.contains(2));
				assertTrue(this.tested.contains(3));
				assertTrue(this.tested.contains(4));
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
	void shouldAddTransactionalItemsAndCommit() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.addRecord(5, 10, 1);
				original.addRecord(5, 10, 2);
				original.addRecord(7, 10, 3);
				original.addRecord(1, 5, 4);

				assertTrue(this.tested.contains(1));
				assertTrue(this.tested.contains(2));
				assertTrue(this.tested.contains(3));
				assertTrue(this.tested.contains(4));
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
	void shouldPassSimpleValidFrom() {
		this.tested.addRecord(Long.MIN_VALUE, Long.MAX_VALUE, 1);
		this.tested.addRecord(Long.MIN_VALUE, timestampForDate(5, 5), 2);
		this.tested.addRecord(timestampForDate(5, 5), Long.MAX_VALUE, 3);
		this.tested.addRecord(timestampForDate(1, 4), timestampForDate(5, 5), 4);

		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(1, 5)), new int[]{1, 2, 4});
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(1, 6)), new int[]{1, 3});
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(1, 1)), new int[]{1, 2});
	}

	@Test
	void shouldPassValidFromWhenThereAreMultipleRangesForSingleRecord() {
		this.tested.addRecord(Long.MIN_VALUE, Long.MAX_VALUE, 1);
		this.tested.addRecord(Long.MIN_VALUE, timestampForDate(5, 5), 2);
		this.tested.addRecord(timestampForDate(1, 7), Long.MAX_VALUE, 2);
		this.tested.addRecord(timestampForDate(1, 1), timestampForDate(3, 3), 3);
		this.tested.addRecord(timestampForDate(5, 5), Long.MAX_VALUE, 3);
		this.tested.addRecord(timestampForDate(1, 4), timestampForDate(5, 7), 4);

		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(2, 2)), new int[]{1, 2, 3});
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(10, 3)), new int[]{1, 2});
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(1, 5)), new int[]{1, 2, 4});
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(10, 5)), new int[]{1, 3, 4});
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(2, 7)), new int[]{1, 2, 3, 4});
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(6, 6)), new int[]{1, 3, 4});
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(10, 7)), new int[]{1, 2, 3});
	}

	@Test
	void shouldAddAndRemoveRecord() {
		this.tested.addRecord(Long.MIN_VALUE, Long.MAX_VALUE, 1);
		this.tested.addRecord(Long.MIN_VALUE, timestampForDate(5, 5), 2);
		this.tested.addRecord(timestampForDate(5, 5), Long.MAX_VALUE, 3);
		this.tested.addRecord(timestampForDate(1, 4), timestampForDate(5, 5), 4);

		assertTrue(this.tested.contains(1));
		assertTrue(this.tested.contains(2));
		assertTrue(this.tested.contains(3));
		assertTrue(this.tested.contains(4));
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(1, 5)), new int[]{1, 2, 4});
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(1, 6)), new int[]{1, 3});
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(1, 1)), new int[]{1, 2});

		this.tested.removeRecord(Long.MIN_VALUE, Long.MAX_VALUE, 1);
		this.tested.removeRecord(timestampForDate(1, 4), timestampForDate(5, 5), 4);

		assertFalse(this.tested.contains(1));
		assertTrue(this.tested.contains(2));
		assertTrue(this.tested.contains(3));
		assertFalse(this.tested.contains(4));
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(1, 5)), new int[]{2});
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(1, 6)), new int[]{3});
		assertFormulaResultsIn(this.tested.getRecordsFrom(timestampForDate(1, 1)), new int[]{2});
	}

	@Test
	void shouldPassValidToWhenThereAreMultipleRangesForSingleRecord() {
		this.tested.addRecord(Long.MIN_VALUE, Long.MAX_VALUE, 1);
		this.tested.addRecord(Long.MIN_VALUE, timestampForDate(5, 5), 2);
		this.tested.addRecord(timestampForDate(1, 7), Long.MAX_VALUE, 2);
		this.tested.addRecord(timestampForDate(1, 1), timestampForDate(3, 3), 3);
		this.tested.addRecord(timestampForDate(5, 5), Long.MAX_VALUE, 3);
		this.tested.addRecord(timestampForDate(1, 4), timestampForDate(5, 7), 4);

		assertFormulaResultsIn(this.tested.getRecordsTo(timestampForDate(2, 2)), new int[]{1, 2, 3});
		assertFormulaResultsIn(this.tested.getRecordsTo(timestampForDate(10, 3)), new int[]{1, 2});
		assertFormulaResultsIn(this.tested.getRecordsTo(timestampForDate(1, 5)), new int[]{1, 2, 4});
		assertFormulaResultsIn(this.tested.getRecordsTo(timestampForDate(10, 5)), new int[]{1, 3, 4});
		assertFormulaResultsIn(this.tested.getRecordsTo(timestampForDate(2, 7)), new int[]{1, 2, 3, 4});
		assertFormulaResultsIn(this.tested.getRecordsTo(timestampForDate(6, 6)), new int[]{1, 3, 4});
		assertFormulaResultsIn(this.tested.getRecordsTo(timestampForDate(10, 7)), new int[]{1, 2, 3});
	}

	@Test
	void shouldPassValidWithRangesOverlapping() {
		this.tested.addRecord(1, 4, 1);
		this.tested.addRecord(4, 7, 2);
		this.tested.addRecord(7, 10, 3);
		this.tested.addRecord(3, 5, 4);
		this.tested.addRecord(6, 9, 5);

		assertFormulaResultsIn(this.tested.getRecordsWithRangesOverlapping(Long.MIN_VALUE, Long.MAX_VALUE), new int[]{1, 2, 3, 4, 5});
		assertFormulaResultsIn(this.tested.getRecordsWithRangesOverlapping(Long.MIN_VALUE, 2), new int[]{1});
		assertFormulaResultsIn(this.tested.getRecordsWithRangesOverlapping(9, Long.MAX_VALUE), new int[]{3, 5});
		assertFormulaResultsIn(this.tested.getRecordsWithRangesOverlapping(4, 7), new int[]{1, 2, 3, 4, 5});
		assertFormulaResultsIn(this.tested.getRecordsWithRangesOverlapping(1, 2), new int[]{1});
		assertFormulaResultsIn(this.tested.getRecordsWithRangesOverlapping(1, 1), new int[]{1});
		assertFormulaResultsIn(this.tested.getRecordsWithRangesOverlapping(1, 3), new int[]{1, 4});
		assertFormulaResultsIn(this.tested.getRecordsWithRangesOverlapping(7, 7), new int[]{2, 3, 5});
	}

	@Test
	void shouldSerializeAndDeserialize() {
		this.tested.addRecord(5, 10, 1);
		this.tested.addRecord(5, 10, 2);
		this.tested.addRecord(7, 10, 3);
		this.tested.addRecord(1, 5, 4);

		final Kryo kryo = new Kryo();

		kryo.register(RangeIndex.class, new IntRangeIndexSerializer());
		kryo.register(TransactionalRangePoint.class, new TransactionalIntRangePointSerializer());
		kryo.register(TransactionalBitmap.class, new TransactionalIntegerBitmapSerializer());
		kryo.register(int[].class);

		final Output output = new Output(1024, -1);
		kryo.writeObject(output, this.tested);
		output.flush();

		byte[] bytes = output.getBuffer();

		final RangeIndex deserializedTested = kryo.readObject(new Input(bytes), RangeIndex.class);
		assertEquals(this.tested, deserializedTested);
	}

	@Test
	void shouldReportContainsAllRecordsAndSizeOverNonTrivialIndex() {
		// duplicate thresholds (two records sharing 5..10), same-record multi-range edges and sentinels
		this.tested.addRecord(Long.MIN_VALUE, Long.MAX_VALUE, 1);
		this.tested.addRecord(5, 10, 2);
		this.tested.addRecord(5, 10, 3);
		this.tested.addRecord(1, 5, 4);
		this.tested.addRecord(10, 20, 4);

		assertTrue(this.tested.contains(1));
		assertTrue(this.tested.contains(2));
		assertTrue(this.tested.contains(3));
		assertTrue(this.tested.contains(4));
		assertFalse(this.tested.contains(99));

		assertArrayEquals(new int[]{1, 2, 3, 4}, this.tested.getAllRecords().getArray());
		assertEquals(4, this.tested.size());
	}

	@Test
	void shouldExposeRangePointCountIncludingSentinels() {
		// empty index carries only the MIN/MAX sentinels
		assertEquals(2, this.tested.getRangePointCount());
		this.tested.addRecord(5, 10, 1);
		// two fresh thresholds added
		assertEquals(4, this.tested.getRangePointCount());
		this.tested.addRecord(5, 20, 2);
		// threshold 5 is shared, threshold 20 is new
		assertEquals(5, this.tested.getRangePointCount());
	}

	@Test
	void shouldIterateRangesInThresholdOrder() {
		this.tested.addRecord(5, 10, 1);
		this.tested.addRecord(1, 7, 2);

		final java.util.Iterator<TransactionalRangePoint> it = this.tested.rangesIterator();
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
	void shouldReturnRangesArrayInThresholdOrder() {
		this.tested.addRecord(5, 10, 1);
		this.tested.addRecord(1, 7, 2);

		final RangePoint<?>[] ranges = this.tested.getRanges();
		assertEquals(6, ranges.length);
		assertEquals(Long.MIN_VALUE, ranges[0].getThreshold());
		assertEquals(1L, ranges[1].getThreshold());
		assertEquals(5L, ranges[2].getThreshold());
		assertEquals(7L, ranges[3].getThreshold());
		assertEquals(10L, ranges[4].getThreshold());
		assertEquals(Long.MAX_VALUE, ranges[5].getThreshold());
	}

	@Test
	void shouldComputeEnvelopingInclusiveAtExactThresholdAndBetween() {
		this.tested.addRecord(100L, 200L, 1);
		this.tested.addRecord(150L, 300L, 2);

		// exactly on a threshold (150) - record 1 and 2 valid
		assertFormulaResultsIn(this.tested.getRecordsEnvelopingInclusive(150L), new int[]{1, 2});
		// between thresholds (175) - both valid
		assertFormulaResultsIn(this.tested.getRecordsEnvelopingInclusive(175L), new int[]{1, 2});
		// exactly on the start threshold of record 1 (100)
		assertFormulaResultsIn(this.tested.getRecordsEnvelopingInclusive(100L), new int[]{1});
		// after record 1 ends but record 2 still valid (250)
		assertFormulaResultsIn(this.tested.getRecordsEnvelopingInclusive(250L), new int[]{2});
		// outside every range
		assertFormulaResultsIn(this.tested.getRecordsEnvelopingInclusive(50L), new int[0]);
	}

	@Test
	void shouldComputeRecordsToInclusiveBoundary() {
		this.tested.addRecord(1, 4, 1);
		this.tested.addRecord(5, 7, 2);
		this.tested.addRecord(8, 10, 3);

		// exactly on threshold 5 - records starting at or before 5 with no earlier end (record 1 already ended at 4)
		assertFormulaResultsIn(this.tested.getRecordsTo(5L), new int[]{2});
		// between thresholds (6) - same as 5 (inclusive -idx-2 boundary)
		assertFormulaResultsIn(this.tested.getRecordsTo(6L), new int[]{2});
		// at threshold 10 every record has both started and ended -> none "valid until" this point
		assertFormulaResultsIn(this.tested.getRecordsTo(10L), new int[0]);
		// at the first real threshold (1) only record 1 has started and not yet ended
		assertFormulaResultsIn(this.tested.getRecordsTo(1L), new int[]{1});
		// between record 1 start and end (2) record 1 still valid
		assertFormulaResultsIn(this.tested.getRecordsTo(2L), new int[]{1});
	}

	@Test
	void nonTransactionalAddAndRemoveMutatesDelegate() {
		// T8 - outside a transaction, add/remove mutates this instance directly
		this.tested.addRecord(5, 10, 1);
		this.tested.addRecord(5, 10, 2);
		assertTrue(this.tested.contains(1));
		assertTrue(this.tested.contains(2));

		this.tested.removeRecord(5, 10, 1);
		assertFalse(this.tested.contains(1));
		assertTrue(this.tested.contains(2));
		assertArrayEquals(new int[]{2}, this.tested.getAllRecords().getArray());
	}

	@Test
	void transactionalAddCommitsAtomically() {
		// T2/T7 - committed version reflects the change, original is untouched until commit
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
	void transactionalAddRollbackLeavesOriginalUntouched() {
		// T7 - rollback discards all changes
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
	void modifyingPointBitmapCommitsAtomically() {
		// T5 - mutate a point's bitmap (add a second record sharing an existing threshold) commits with the parent
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
	void removeToEmptyInsideTransactionSweepsCleanly() {
		// remove-to-empty inside a txn - modifies then removes the range points, must sweep cleanly
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
	void rangesIteratorReflectsInTransactionChanges() {
		// T9 - the transactional iterator observes in-progress state inside the transaction
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
