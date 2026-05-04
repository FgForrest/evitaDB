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
						RangeIndex.collectsStartsAndEnds(
							0, original.ranges.getLength() - 1,
							original.ranges
						)
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
						RangeIndex.collectsStartsAndEnds(
							0, original.ranges.getLength() - 1,
							original.ranges
						)
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
						RangeIndex.collectsStartsAndEnds(
							0, original.ranges.getLength() - 1,
							original.ranges
						)
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
						RangeIndex.collectsStartsAndEnds(
							0, committedVersion.ranges.getLength() - 1,
							committedVersion.ranges
						)
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
						RangeIndex.collectsStartsAndEnds(
							0, original.ranges.getLength() - 1,
							original.ranges
						)
					)
				);

				assertFalse(committedVersion.contains(1));
				assertFalse(committedVersion.contains(3));
				assertTrue(
					new StartsEndsDTO(
						asListOfBitmaps(new int[0], new int[0]),
						asListOfBitmaps(new int[0], new int[0])
					).effectivelyEquals(
						RangeIndex.collectsStartsAndEnds(
							0, committedVersion.ranges.getLength() - 1,
							committedVersion.ranges
						)
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
						RangeIndex.collectsStartsAndEnds(
							0, committedVersion.ranges.getLength() - 1,
							committedVersion.ranges
						)
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
