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

package io.evitadb.index.attribute;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static java.text.Normalizer.Form;
import static java.text.Normalizer.normalize;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link SortIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class SortIndexTest implements TimeBoundedTestSupport {

	private static final Locale CZECH_LOCALE = new Locale("cs");

	@Test
	void shouldCreateIndexWithDifferentCardinalities() {
		final SortIndex sortIndex = createIndexWithBaseCardinalities();
		assertNull(sortIndex.valueCardinalities.get("Z"));
		assertNull(sortIndex.valueCardinalities.get("A"));
		assertEquals(2, sortIndex.valueCardinalities.get("B"));
		assertEquals(4, sortIndex.valueCardinalities.get("C"));
		assertArrayEquals(new String[]{"A", "B", "C", "E"}, sortIndex.sortedRecordsValues.getArray());
		assertArrayEquals(new int[]{6, 4, 5, 1, 2, 3, 7, 9}, sortIndex.sortedRecords.getArray());
	}

	@Test
	void shouldCreateCompoundIndexWithDifferentCardinalities() {
		final SortIndex sortIndex = createCompoundIndexWithBaseCardinalities();
		assertNull(sortIndex.valueCardinalities.get(new ComparableArray(new Serializable[]{"Z", 1})));
		assertNull(sortIndex.valueCardinalities.get(new ComparableArray(new Serializable[]{"A", 2})));
		assertEquals(2, sortIndex.valueCardinalities.get(new ComparableArray(new Serializable[]{"B", 1})));
		assertEquals(2, sortIndex.valueCardinalities.get(new ComparableArray(new Serializable[]{"C", 9})));
		assertArrayEquals(
			new ComparableArray[]{
				new ComparableArray(new Serializable[]{null, 3}),
				new ComparableArray(new Serializable[]{"A", 4}),
				new ComparableArray(new Serializable[]{"B", 1}),
				new ComparableArray(new Serializable[]{"C", 9}),
				new ComparableArray(new Serializable[]{"C", 6}),
				new ComparableArray(new Serializable[]{"C", null}),
				new ComparableArray(new Serializable[]{"E", null})
			},
			sortIndex.sortedRecordsValues.getArray()
		);
		assertArrayEquals(new int[]{8, 6, 4, 5, 1, 7, 3, 2, 9}, sortIndex.sortedRecords.getArray());
	}

	@Test
	void shouldReturnCorrectBitmapForCardinalityOne() {
		final SortIndex sortIndex = createIndexWithBaseCardinalities();
		assertEquals(new BaseBitmap(9), sortIndex.getRecordsEqualTo("E"));
	}

	@Test
	void shouldReturnCorrectBitmapForCardinalityOneAndCompoundIndex() {
		final SortIndex sortIndex = createCompoundIndexWithBaseCardinalities();
		assertEquals(new BaseBitmap(9), sortIndex.getRecordsEqualTo(new Serializable[]{"E", null}));
		assertTrue(sortIndex.getRecordsEqualTo(new Serializable[]{"E", 1}).isEmpty());
	}

	@Test
	void shouldReturnCorrectBitmapForCardinalityMoreThanOne() {
		final SortIndex sortIndex = createIndexWithBaseCardinalities();
		assertEquals(new BaseBitmap(1, 2, 3, 7), sortIndex.getRecordsEqualTo("C"));
	}

	@Test
	void shouldReturnCorrectBitmapForCardinalityMoreThanOneAndCompoundIndex() {
		final SortIndex sortIndex = createCompoundIndexWithBaseCardinalities();
		assertEquals(new BaseBitmap(1, 7), sortIndex.getRecordsEqualTo(new Serializable[]{"C", 9}));
	}

	@Test
	void shouldAlterIndexWithDifferentCardinalities() {
		final SortIndex sortIndex = createIndexWithBaseCardinalities();
		sortIndex.removeRecord("A", 6);
		sortIndex.removeRecord("B", 4);
		sortIndex.removeRecord("C", 1);
		assertNull(sortIndex.valueCardinalities.get("Z"));
		assertNull(sortIndex.valueCardinalities.get("A"));
		assertNull(sortIndex.valueCardinalities.get("B"));
		assertEquals(3, sortIndex.valueCardinalities.get("C"));
		assertArrayEquals(new String[]{"B", "C", "E"}, sortIndex.sortedRecordsValues.getArray());
		assertArrayEquals(new int[]{5, 2, 3, 7, 9}, sortIndex.sortedRecords.getArray());
	}

	@Test
	void shouldIndexRecordsAndReturnInAscendingOrder() {
		final SortIndex sortIndex = new SortIndex(Integer.class, new AttributeKey("a"));
		sortIndex.addRecord(7, 2);
		sortIndex.addRecord(3, 4);
		sortIndex.addRecord(4, 3);
		sortIndex.addRecord(9, 1);
		sortIndex.addRecord(1, 5);
		final SortedRecordsProvider ascendingOrderRecordsSupplier = sortIndex.getAscendingOrderRecordsSupplier();
		assertArrayEquals(
			new int[]{5, 4, 3, 2, 1},
			ascendingOrderRecordsSupplier.getSortedRecordIds()
		);
	}

	@Test
	void shouldIndexCompoundRecordsAndReturnInAscendingOrder() {
		final SortIndex sortIndex = createCompoundIndexWithBaseCardinalities();
		assertArrayEquals(new int[]{8, 6, 4, 5, 1, 7, 3, 2, 9}, sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds());
	}

	@Test
	void shouldIndexRecordsAndReturnInDescendingOrder() {
		final SortIndex sortIndex = new SortIndex(Integer.class, new AttributeKey("a"));
		sortIndex.addRecord(7, 2);
		sortIndex.addRecord(3, 4);
		sortIndex.addRecord(4, 3);
		sortIndex.addRecord(9, 1);
		sortIndex.addRecord(1, 5);
		final SortedRecordsProvider ascendingOrderRecordsSupplier = sortIndex.getDescendingOrderRecordsSupplier();
		assertArrayEquals(
			new int[]{1, 2, 3, 4, 5},
			ascendingOrderRecordsSupplier.getSortedRecordIds()
		);
	}

	@Test
	void shouldCorrectlyOrderLocalizedStrings() {
		final SortIndex sortIndex = new SortIndex(String.class, new AttributeKey("a", new Locale("cs", "CZ")));
		sortIndex.addRecord("c", 2);
		sortIndex.addRecord("č", 3);
		sortIndex.addRecord("a", 1);
		sortIndex.addRecord("ch", 5);
		sortIndex.addRecord("ž", 6);
		sortIndex.addRecord("h", 4);
		assertArrayEquals(
			new int[]{1, 2, 3, 4, 5, 6},
			sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds()
		);

		sortIndex.removeRecord("č", 2);
		sortIndex.removeRecord("h", 3);

		assertArrayEquals(
			new int[]{1, 4, 5, 6},
			sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds()
		);
	}

	@Test
	void shouldCorrectlyOrderBigDecimals() {
		final SortIndex sortIndex = new SortIndex(BigDecimal.class, new AttributeKey("a", new Locale("cs", "CZ")));
		sortIndex.addRecord(new BigDecimal("0.00"), 1);
		sortIndex.addRecord(new BigDecimal("0"), 2);
		sortIndex.addRecord(new BigDecimal("0.000"), 3);
		sortIndex.addRecord(new BigDecimal("1.1"), 4);
		sortIndex.addRecord(new BigDecimal("01.10"), 5);
		sortIndex.addRecord(new BigDecimal("00002"), 6);
		assertArrayEquals(
			new int[]{1, 2, 3, 4, 5, 6},
			sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds()
		);

		sortIndex.removeRecord(new BigDecimal("0.00"), 2);
		sortIndex.removeRecord(new BigDecimal("0"), 3);

		assertArrayEquals(
			new int[]{1, 4, 5, 6},
			sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds()
		);
	}

	@Test
	void shouldIndexCompoundRecordsAndReturnInDescendingOrder() {
		final SortIndex sortIndex = createCompoundIndexWithBaseCardinalities();
		assertArrayEquals(new int[]{9, 2, 3, 7, 1, 5, 4, 6, 8}, sortIndex.getDescendingOrderRecordsSupplier().getSortedRecordIds());
	}

	@Test
	void shouldTraverseAllComparableValuesInForwardFashion() {
		final SortIndex sortIndex = createIndexWithBaseCardinalities();
		final SortedRecordsProvider sortedRecordsSupplier = sortIndex.getAscendingOrderRecordsSupplier();
		final SortedComparableForwardSeeker seeker = sortedRecordsSupplier.getSortedComparableForwardSeeker();
		final String[] values = new String[sortIndex.size()];
		for (int i = 0; i < sortIndex.size(); i++) {
			values[i] = (String) seeker.getValueToCompareOn(i);
		}
		assertArrayEquals(
			new String[] { "A", "B", "B", "C", "C", "C", "C", "E" },
			values
		);
	}

	@Test
	void shouldTraverseAllComparableValuesInForwardFashionWithSingleCardinalityIndex() {
		final SortIndex sortIndex = createIndexWithSingleCardinality();
		final SortedRecordsProvider sortedRecordsSupplier = sortIndex.getAscendingOrderRecordsSupplier();
		final SortedComparableForwardSeeker seeker = sortedRecordsSupplier.getSortedComparableForwardSeeker();
		final String[] values = new String[sortIndex.size()];
		for (int i = 0; i < sortIndex.size(); i++) {
			values[i] = (String) seeker.getValueToCompareOn(i);
		}
		assertArrayEquals(
			new String[] { "A", "A", "A", "A", "A", "A", "A", "A" },
			values
		);
	}

	@Test
	void shouldTraverseAllComparableValuesInReverseFashion() {
		final SortIndex sortIndex = createIndexWithBaseCardinalities();
		final SortedRecordsProvider sortedRecordsSupplier = sortIndex.getDescendingOrderRecordsSupplier();
		final SortedComparableForwardSeeker seeker = sortedRecordsSupplier.getSortedComparableForwardSeeker();
		final String[] values = new String[sortIndex.size()];
		for (int i = 0; i < sortIndex.size(); i++) {
			values[i] = (String) seeker.getValueToCompareOn(i);
		}
		assertArrayEquals(
			new String[] { "E", "C", "C", "C", "C", "B", "B", "A" },
			values
		);
	}

	@Test
	void shouldTraverseAllComparableValuesInReverseFashionWithSingleCardinalityIndex() {
		final SortIndex sortIndex = createIndexWithSingleCardinality();
		final SortedRecordsProvider sortedRecordsSupplier = sortIndex.getDescendingOrderRecordsSupplier();
		final SortedComparableForwardSeeker seeker = sortedRecordsSupplier.getSortedComparableForwardSeeker();
		final String[] values = new String[sortIndex.size()];
		for (int i = 0; i < sortIndex.size(); i++) {
			values[i] = (String) seeker.getValueToCompareOn(i);
		}
		assertArrayEquals(
			new String[] { "A", "A", "A", "A", "A", "A", "A", "A" },
			values
		);
	}

	@Test
	void shouldPassGenerationalTest1() {
		final SortIndex sortIndex = new SortIndex(String.class, new AttributeKey("a"));
		sortIndex.addRecord("W", 49);
		sortIndex.addRecord("Z", 150);
		sortIndex.addRecord("[", 175);
		sortIndex.addRecord("E", 26);
		sortIndex.addRecord("I", 141);
		sortIndex.addRecord("T", 131);
		sortIndex.addRecord("G", 186);
		sortIndex.addRecord("X", 139);
		sortIndex.addRecord("C", 177);
		sortIndex.addRecord("L", 126);

		assertArrayEquals(
			new int[]{177, 26, 186, 141, 126, 131, 49, 139, 150, 175},
			sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds()
		);
	}

	@Test
	void shouldSortNationalCharactersCorrectly() {
		final SortIndex sortIndex = new SortIndex(String.class, new AttributeKey("a", CZECH_LOCALE));
		sortIndex.addRecord("A", 1);
		sortIndex.addRecord("Š", 2);
		sortIndex.addRecord("T", 3);
		sortIndex.addRecord("B", 4);
		sortIndex.addRecord("Ž", 5);
		sortIndex.addRecord("Ř", 6);
		sortIndex.addRecord("Ň", 7);

		assertArrayEquals(
			new String[]{
				normalize("A", Form.NFD),
				normalize("B", Form.NFD),
				normalize("Ň", Form.NFD),
				normalize("Ř", Form.NFD),
				normalize("Š", Form.NFD),
				normalize("T", Form.NFD),
				normalize("Ž", Form.NFD)
			},
			sortIndex.sortedRecordsValues.getArray()
		);
		assertArrayEquals(new int[]{1, 4, 7, 6, 2, 3, 5}, sortIndex.sortedRecords.getArray());
	}

	@ParameterizedTest(name = "SortIndex should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final Random rnd = new Random();
		final int initialCount = 100;
		final TreeSet<ValueRecord> setToCompare = new TreeSet<>();
		final Set<Integer> currentRecordSet = new HashSet<>();

		runFor(
			input,
			1_000,
			new TestState(new StringBuilder(256), new SortIndex(String.class, new AttributeKey("whatever"))),
			(random, testState) -> {
				final StringBuilder ops = testState.code();
				ops.append("final SortIndex sortIndex = new SortIndex(String.class);\n")
					.append(
						setToCompare.stream()
							.map(it -> "sortIndex.addRecord(\"" + it.value() + "\"," + it.recordId() + ");")
							.collect(Collectors.joining("\n"))
					)
					.append("\nOps:\n");

				final SortIndex sortIndex = testState.sortIndex();
				final AtomicReference<SortIndex> committedResult = new AtomicReference<>();

				assertStateAfterCommit(
					sortIndex,
					original -> {
						try {
							final int operationsInTransaction = rnd.nextInt(100);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = sortIndex.size();
								if ((rnd.nextBoolean() || length < 10) && length < 50) {
									// insert new item
									final String newValue = Character.toString(65 + rnd.nextInt(28));
									int newRecId;
									do {
										newRecId = rnd.nextInt(initialCount * 2);
									} while (currentRecordSet.contains(newRecId));
									setToCompare.add(new ValueRecord(newValue, newRecId));
									currentRecordSet.add(newRecId);

									ops.append("sortIndex.addRecord(\"").append(newValue).append("\",").append(newRecId).append(");\n");
									sortIndex.addRecord(newValue, newRecId);
								} else {
									// remove existing item
									final Iterator<ValueRecord> it = setToCompare.iterator();
									ValueRecord valueToRemove = null;
									for (int j = 0; j < rnd.nextInt(length) + 1; j++) {
										valueToRemove = it.next();
									}
									it.remove();
									currentRecordSet.remove(valueToRemove.recordId());

									ops.append("sortIndex.removeRecord(\"").append(valueToRemove.value()).append("\",").append(valueToRemove.recordId()).append(");\n");
									sortIndex.removeRecord(valueToRemove.value(), valueToRemove.recordId());
								}
							}
						} catch (Exception ex) {
							fail("\n" + ops, ex);
						}
					},
					(original, committed) -> {
						final int[] expected = setToCompare.stream().mapToInt(ValueRecord::recordId).toArray();
						assertArrayEquals(
							expected,
							committed.getAscendingOrderRecordsSupplier().getSortedRecordIds(),
							"\nExpected: " + Arrays.toString(expected) + "\n" +
								"Actual:  " + Arrays.toString(committed.getAscendingOrderRecordsSupplier().getSortedRecordIds()) + "\n\n" +
								ops
						);

						committedResult.set(
							new SortIndex(
								committed.comparatorBase,
								null,
								committed.getAttributeKey(),
								committed.sortedRecords.getArray(),
								committed.sortedRecordsValues.getArray(),
								new HashMap<>(committed.valueCardinalities)
							)
						);
					}
				);

				return new TestState(
					new StringBuilder(512),
					committedResult.get()
				);
			}
		);
	}

	@Nonnull
	private static SortIndex createIndexWithBaseCardinalities() {
		final SortIndex sortIndex = new SortIndex(String.class, new AttributeKey("a", Locale.ENGLISH));
		sortIndex.addRecord("B", 5);
		sortIndex.addRecord("A", 6);
		sortIndex.addRecord("C", 3);
		sortIndex.addRecord("C", 2);
		sortIndex.addRecord("B", 4);
		sortIndex.addRecord("C", 1);
		sortIndex.addRecord("E", 9);
		sortIndex.addRecord("C", 7);
		return sortIndex;
	}

	@Nonnull
	private static SortIndex createIndexWithSingleCardinality() {
		final SortIndex sortIndex = new SortIndex(String.class, new AttributeKey("a", Locale.ENGLISH));
		sortIndex.addRecord("A", 5);
		sortIndex.addRecord("A", 6);
		sortIndex.addRecord("A", 3);
		sortIndex.addRecord("A", 2);
		sortIndex.addRecord("A", 4);
		sortIndex.addRecord("A", 1);
		sortIndex.addRecord("A", 9);
		sortIndex.addRecord("A", 7);
		return sortIndex;
	}

	@Nonnull
	private static SortIndex createCompoundIndexWithBaseCardinalities() {
		final SortIndex sortIndex = new SortIndex(
			new ComparatorSource[]{
				new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
				new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
			},
			new AttributeKey("a", Locale.ENGLISH)
		);

		sortIndex.addRecord(new Serializable[]{"B", 1}, 5);
		sortIndex.addRecord(new Serializable[]{"A", 4}, 6);
		sortIndex.addRecord(new Serializable[]{"C", 6}, 3);
		sortIndex.addRecord(new Serializable[]{"C", null}, 2);
		sortIndex.addRecord(new Serializable[]{"B", 1}, 4);
		sortIndex.addRecord(new Serializable[]{"C", 9}, 1);
		sortIndex.addRecord(new Serializable[]{"E", null}, 9);
		sortIndex.addRecord(new Serializable[]{"C", 9}, 7);
		sortIndex.addRecord(new Serializable[]{null, 3}, 8);
		return sortIndex;
	}

	private record TestState(
		StringBuilder code,
		SortIndex sortIndex
	) {

	}

	private record ValueRecord(String value, int recordId) implements Comparable<ValueRecord> {
		@Override
		public int compareTo(ValueRecord o) {
			final int cmp1 = this.value.compareTo(o.value);
			return cmp1 == 0 ? Integer.compare(this.recordId, o.recordId) : cmp1;
		}

	}
}
