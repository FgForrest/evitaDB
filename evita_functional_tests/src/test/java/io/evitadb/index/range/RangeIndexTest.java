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
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertFormulaResultsIn;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link RangeIndex}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
class RangeIndexTest implements TimeBoundedTestSupport {
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

	@ParameterizedTest(name = "RangeIndex should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int optimalCount = 100;
		final Map<IntegerNumberRange, Integer> initialState = new HashMap<>();
		final Set<Integer> currentRecordSet = new HashSet<>();
		final Set<IntegerNumberRange> uniqueValues = new HashSet<>();

		runFor(
			input,
			100,
			new TestState(new StringBuilder(), new RangeIndex()),
			(random, testState) -> {
				final RangeIndex intRangeIndex = testState.rangeIndex();
				final AtomicReference<RangeIndex> committedResult = new AtomicReference<>();

				final StringBuilder codeBuffer = testState.code();
				codeBuffer
					.append("final RangeIndex intRangeIndex = new RangeIndex();\n")
					.append(initialState.entrySet().stream().map(it -> "intRangeIndex.addRecord(" + it.getKey().getFrom() + "," + it.getKey().getTo() + "," + it.getValue() + ");").collect(Collectors.joining("\n")))
					.append("\nOps:\n");

				assertStateAfterCommit(
					intRangeIndex,
					original -> {
						try {
							final int operationsInTransaction = random.nextInt(100);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = currentRecordSet.size();
								if ((random.nextBoolean() || length < 10) && length < 50) {
									// insert new item
									IntegerNumberRange range;
									do {
										final int from = random.nextInt(optimalCount * 2);
										final int to = random.nextInt(optimalCount * 2);
										range = IntegerNumberRange.between(Math.min(from, to), Math.max(from, to));
									} while (uniqueValues.contains(range));

									int newRecId;
									do {
										newRecId = random.nextInt(optimalCount);
									} while (currentRecordSet.contains(newRecId));
									initialState.put(range, newRecId);
									currentRecordSet.add(newRecId);
									uniqueValues.add(range);

									codeBuffer.append("intRangeIndex.addRecord(").append(range.getFrom()).append(",").append(range.getTo()).append(",").append(newRecId).append(");\n");
									intRangeIndex.addRecord(range.getFrom(), range.getTo(), newRecId);
								} else {
									// remove existing item
									final Iterator<Entry<IntegerNumberRange, Integer>> it = initialState.entrySet().iterator();
									Entry<IntegerNumberRange, Integer> valueToRemove = null;
									final int itemToRemove = random.nextInt(length);
									for (int j = 0; j < itemToRemove + 1; j++) {
										valueToRemove = it.next();
									}
									it.remove();
									currentRecordSet.remove(valueToRemove.getValue());
									uniqueValues.remove(valueToRemove.getKey());

									codeBuffer.append("intRangeIndex.removeRecord(").append(valueToRemove.getKey().getFrom()).append(",").append(valueToRemove.getKey().getTo()).append(",").append(valueToRemove.getValue()).append(");\n");
									intRangeIndex.removeRecord(valueToRemove.getKey().getFrom(), valueToRemove.getKey().getTo(), valueToRemove.getValue());
								}
							}
						} catch (Exception ex) {
							fail("\n" + codeBuffer, ex);
						}
					},
					(original, committed) -> {
						final int[] expected = currentRecordSet.stream().mapToInt(it -> it).sorted().toArray();
						assertArrayEquals(
							expected,
							committed.getAllRecords().getArray(),
							"\nExpected: " + Arrays.toString(expected) + "\n" +
								"Actual:  " + Arrays.toString(committed.getAllRecords().getArray()) + "\n\n" +
								codeBuffer
						);

						committedResult.set(
							new RangeIndex(committed.ranges.getArray())
						);
					}
				);

				return new TestState(
					new StringBuilder(),
					committedResult.get()
				);
			},
			(testState, throwable) -> System.out.println(testState.code())
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

	private record TestState(
		StringBuilder code,
		RangeIndex rangeIndex
	) {}

}
