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
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.comparator.NullsFirstComparatorWrapper;
import io.evitadb.comparator.NullsLastComparatorWrapper;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
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
		final SortIndex sortIndex = new SortIndex(Integer.class, new AttributeIndexKey(null, "a", null));
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
		assertArrayEquals(
			new int[]{8, 6, 4, 5, 1, 7, 3, 2, 9},
			sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds()
		);
	}

	@Test
	void shouldIndexRecordsAndReturnInDescendingOrder() {
		final SortIndex sortIndex = new SortIndex(Integer.class, new AttributeIndexKey(null, "a", null));
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
		final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", CZECH_LOCALE));
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
		final SortIndex sortIndex = new SortIndex(BigDecimal.class, new AttributeIndexKey(null, "a", CZECH_LOCALE));
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
		assertArrayEquals(
			new int[]{9, 2, 3, 7, 1, 5, 4, 6, 8},
			sortIndex.getDescendingOrderRecordsSupplier().getSortedRecordIds()
		);
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
		final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", null));
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
		final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", CZECH_LOCALE));
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
			new TestState(
				new StringBuilder(256),
				new SortIndex(String.class, new AttributeIndexKey(null, "whatever", null))
			),
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

									ops.append("sortIndex.addRecord(\"")
										.append(newValue).append("\",")
										.append(newRecId).append(");\n");
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

									ops.append("sortIndex.removeRecord(\"")
										.append(valueToRemove.value()).append("\",")
										.append(valueToRemove.recordId()).append(");\n");
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
								"Actual:  " + Arrays.toString(
								committed.getAscendingOrderRecordsSupplier().getSortedRecordIds()
							) + "\n\n" + ops
						);

						committedResult.set(
							new SortIndex(
								committed.comparatorBase,
								null,
								committed.getAttributeIndexKey(),
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
		final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", Locale.ENGLISH));
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
		final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", Locale.ENGLISH));
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
			new AttributeIndexKey(null, "a", Locale.ENGLISH)
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

	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName("should assign unique id to each instance")
		void shouldAssignUniqueIdToEachInstance() {
			final SortIndex first = new SortIndex(Integer.class, new AttributeIndexKey(null, "x", null));
			final SortIndex second = new SortIndex(Integer.class, new AttributeIndexKey(null, "y", null));

			assertNotEquals(first.getId(), second.getId());
		}

		@Test
		@DisplayName("should return same instance when no mutations applied")
		void shouldReturnSameInstanceWhenNoMutationsApplied() {
			final SortIndex sortIndex = new SortIndex(Integer.class, new AttributeIndexKey(null, "a", null));

			assertStateAfterCommit(
				sortIndex,
				original -> {
					// no mutations
				},
				(original, committed) -> assertSame(original, committed)
			);
		}

		@Test
		@DisplayName("should return new instance when dirty after commit")
		void shouldReturnNewInstanceWhenDirtyAfterCommit() {
			final SortIndex sortIndex = new SortIndex(Integer.class, new AttributeIndexKey(null, "a", null));

			assertStateAfterCommit(
				sortIndex,
				original -> original.addRecord(42, 1),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(1, committed.size());
					assertArrayEquals(new int[]{1}, committed.getSortedRecords());
				}
			);
		}

		@Test
		@DisplayName("should leave original unchanged after commit")
		void shouldLeaveOriginalUnchangedAfterCommit() {
			final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", null));
			sortIndex.addRecord("A", 1);

			assertStateAfterCommit(
				sortIndex,
				original -> {
					original.addRecord("B", 2);
					original.addRecord("C", 3);
				},
				(original, committed) -> {
					// original should still have only record 1
					assertEquals(1, original.size());
					assertArrayEquals(new int[]{1}, original.getSortedRecords());
					// committed should have all 3
					assertEquals(3, committed.size());
				}
			);
		}

		@Test
		@DisplayName("should discard changes after rollback")
		void shouldDiscardChangesAfterRollback() {
			final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", null));
			sortIndex.addRecord("X", 10);

			assertStateAfterRollback(
				sortIndex,
				original -> {
					original.addRecord("Y", 20);
					original.addRecord("Z", 30);
				},
				(original, committed) -> {
					// committed is null on rollback
					assertNull(committed);
					// original stays unchanged
					assertEquals(1, original.size());
					assertArrayEquals(new int[]{10}, original.getSortedRecords());
				}
			);
		}

		@Test
		@DisplayName("should deterministically commit add and remove")
		void shouldDeterministicallyCommitAddAndRemove() {
			final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", null));
			sortIndex.addRecord("A", 1);
			sortIndex.addRecord("B", 2);
			sortIndex.addRecord("C", 3);

			assertStateAfterCommit(
				sortIndex,
				original -> {
					original.addRecord("D", 4);
					original.removeRecord("B", 2);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertArrayEquals(
						new int[]{1, 3, 4},
						committed.getAscendingOrderRecordsSupplier().getSortedRecordIds()
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Functional gaps")
	class FunctionalGapsTest {

		@Test
		@DisplayName("should report empty index correctly")
		void shouldReportEmptyIndexCorrectly() {
			final SortIndex sortIndex = new SortIndex(Integer.class, new AttributeIndexKey(null, "a", null));

			assertTrue(sortIndex.isEmpty());
			assertEquals(0, sortIndex.size());
		}

		@Test
		@DisplayName("should return null from createStoragePart when not dirty")
		void shouldReturnNullFromCreateStoragePartWhenNotDirty() {
			final SortIndex sortIndex = new SortIndex(Integer.class, new AttributeIndexKey(null, "a", null));

			final StoragePart storagePart = sortIndex.createStoragePart(1);
			assertNull(storagePart);
		}

		@Test
		@DisplayName("should return SortIndexStoragePart when dirty")
		void shouldReturnStoragePartWhenDirty() {
			final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "name", null));
			sortIndex.addRecord("Alpha", 1);
			sortIndex.addRecord("Beta", 2);

			final StoragePart storagePart = sortIndex.createStoragePart(42);
			assertNotNull(storagePart);
			assertInstanceOf(SortIndexStoragePart.class, storagePart);

			final SortIndexStoragePart part = (SortIndexStoragePart) storagePart;
			assertEquals(42, part.getEntityIndexPrimaryKey());
			assertEquals(new AttributeIndexKey(null, "name", null), part.getAttributeIndexKey());
			assertArrayEquals(new int[]{1, 2}, part.getSortedRecords());

			// dirty is still true so subsequent call returns part
			final StoragePart secondPart = sortIndex.createStoragePart(42);
			assertNotNull(secondPart);
		}

		@Test
		@DisplayName("should reset dirty flag via resetDirty()")
		void shouldResetDirtyFlag() {
			final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", null));
			sortIndex.addRecord("X", 1);

			// dirty after add
			assertNotNull(sortIndex.createStoragePart(1));

			sortIndex.resetDirty();

			// not dirty anymore
			assertNull(sortIndex.createStoragePart(1));
		}

		@Test
		@DisplayName("should cache sortIndexChanges in non-transactional mode")
		void shouldCacheSortIndexChangesNonTransactional() {
			final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", null));
			sortIndex.addRecord("A", 1);

			// first call to ascending supplier creates changes
			final SortedRecordsProvider first = sortIndex.getAscendingOrderRecordsSupplier();
			assertNotNull(first);

			// second call also works, uses cached changes
			final SortedRecordsProvider second = sortIndex.getAscendingOrderRecordsSupplier();
			assertNotNull(second);

			// both return same record order
			assertArrayEquals(first.getSortedRecordIds(), second.getSortedRecordIds());
		}
	}

	@Nested
	@DisplayName("Error guards")
	class ErrorGuardsTest {

		@Test
		@DisplayName("should throw on duplicate recordId in scalar addRecord")
		void shouldThrowOnDuplicateRecordIdScalar() {
			final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", null));
			sortIndex.addRecord("A", 1);

			final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> sortIndex.addRecord("B", 1)
			);
			assertTrue(ex.getMessage().contains("already present"));
		}

		@Test
		@DisplayName("should throw on duplicate recordId in array addRecord")
		void shouldThrowOnDuplicateRecordIdArray() {
			final SortIndex sortIndex = new SortIndex(
				new ComparatorSource[]{
					new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
					new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				},
				new AttributeIndexKey(null, "a", null)
			);
			sortIndex.addRecord(new Serializable[]{"A", 1}, 10);

			final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> sortIndex.addRecord(new Serializable[]{"B", 2}, 10)
			);
			assertTrue(ex.getMessage().contains("already present"));
		}

		@Test
		@DisplayName("should throw when array passed as scalar value")
		void shouldThrowWhenArrayPassedAsScalarValue() {
			final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", null));

			// cast to Serializable to force the scalar overload
			final Serializable arrayValue = new String[]{"A", "B"};
			final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> sortIndex.addRecord(arrayValue, 1)
			);
			assertTrue(ex.getMessage().contains("must not be an array"));
		}

		@Test
		@DisplayName("should throw when wrong type passed to addRecord")
		void shouldThrowWhenWrongTypePassed() {
			final SortIndex sortIndex = new SortIndex(Integer.class, new AttributeIndexKey(null, "a", null));

			final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> sortIndex.addRecord("not-an-int", 1)
			);
			assertTrue(ex.getMessage().contains("must be of type"));
		}

		@Test
		@DisplayName("should throw when removing non-existent scalar value")
		void shouldThrowOnRemoveNonExistentScalarValue() {
			final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", null));
			sortIndex.addRecord("A", 1);

			final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> sortIndex.removeRecord("Z", 1)
			);
			assertTrue(ex.getMessage().contains("not present"));
		}

		@Test
		@DisplayName("should throw when removing non-existent array value")
		void shouldThrowOnRemoveNonExistentArrayValue() {
			final SortIndex sortIndex = new SortIndex(
				new ComparatorSource[]{
					new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
					new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				},
				new AttributeIndexKey(null, "a", null)
			);
			sortIndex.addRecord(new Serializable[]{"A", 1}, 10);

			final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> sortIndex.removeRecord(new Serializable[]{"Z", 99}, 10)
			);
			assertTrue(ex.getMessage().contains("not present"));
		}
	}

	@Nested
	@DisplayName("Query edge cases")
	class QueryEdgeCasesTest {

		@Test
		@DisplayName("should return EmptyBitmap for absent scalar value")
		void shouldReturnEmptyBitmapForAbsentScalarValue() {
			final SortIndex sortIndex = createIndexWithBaseCardinalities();

			final Bitmap result = sortIndex.getRecordsEqualTo("Z");
			assertSame(EmptyBitmap.INSTANCE, result);
		}

		@Test
		@DisplayName("should return EmptyBitmap for absent compound value")
		void shouldReturnEmptyBitmapForAbsentCompoundValue() {
			final SortIndex sortIndex = createCompoundIndexWithBaseCardinalities();

			final Bitmap result = sortIndex.getRecordsEqualTo(new Serializable[]{"Z", 999});
			assertSame(EmptyBitmap.INSTANCE, result);
		}

		@Test
		@DisplayName("should handle cardinality exactly 2 removal correctly")
		void shouldHandleCardinalityExactly2Removal() {
			final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "a", null));
			sortIndex.addRecord("X", 1);
			sortIndex.addRecord("X", 2);

			// cardinality is 2
			assertEquals(2, sortIndex.valueCardinalities.get("X"));

			// remove one -- cardinality drops to 1, entry removed
			sortIndex.removeRecord("X", 1);
			assertNull(sortIndex.valueCardinalities.get("X"));

			// still one record remains
			assertEquals(1, sortIndex.size());
			assertEquals(new BaseBitmap(2), sortIndex.getRecordsEqualTo("X"));
		}
	}

	@Nested
	@DisplayName("Seekers")
	class SeekersTest {

		@Test
		@DisplayName("should reset and re-traverse forward seeker")
		void shouldResetAndReTraverseForwardSeeker() {
			final SortIndex sortIndex = createIndexWithBaseCardinalities();
			final SortedComparableForwardSeeker seeker = sortIndex.createSortedComparableForwardSeeker();

			// first traversal
			final String[] firstPass = new String[sortIndex.size()];
			for (int i = 0; i < sortIndex.size(); i++) {
				firstPass[i] = (String) seeker.getValueToCompareOn(i);
			}

			// reset
			seeker.reset();

			// second traversal
			final String[] secondPass = new String[sortIndex.size()];
			for (int i = 0; i < sortIndex.size(); i++) {
				secondPass[i] = (String) seeker.getValueToCompareOn(i);
			}

			assertArrayEquals(firstPass, secondPass);
			assertArrayEquals(
				new String[]{"A", "B", "B", "C", "C", "C", "C", "E"},
				firstPass
			);
		}

		@Test
		@DisplayName("should throw on out-of-bounds for forward seeker")
		void shouldThrowOnOutOfBoundsForwardSeeker() {
			final SortIndex sortIndex = createIndexWithBaseCardinalities();
			final SortedComparableForwardSeeker seeker = sortIndex.createSortedComparableForwardSeeker();

			assertThrows(ArrayIndexOutOfBoundsException.class, () -> seeker.getValueToCompareOn(-1));
		}

		@Test
		@DisplayName("should reset and re-traverse reversed seeker")
		void shouldResetAndReTraverseReversedSeeker() {
			final SortIndex sortIndex = createIndexWithBaseCardinalities();
			final SortedComparableForwardSeeker seeker =
				sortIndex.createReversedSortedComparableForwardSeeker();

			// first traversal
			final String[] firstPass = new String[sortIndex.size()];
			for (int i = 0; i < sortIndex.size(); i++) {
				firstPass[i] = (String) seeker.getValueToCompareOn(i);
			}

			// reset
			seeker.reset();

			// second traversal
			final String[] secondPass = new String[sortIndex.size()];
			for (int i = 0; i < sortIndex.size(); i++) {
				secondPass[i] = (String) seeker.getValueToCompareOn(i);
			}

			assertArrayEquals(firstPass, secondPass);
			assertArrayEquals(
				new String[]{"E", "C", "C", "C", "C", "B", "B", "A"},
				firstPass
			);
		}

		@Test
		@DisplayName("should throw on out-of-bounds for reversed seeker")
		void shouldThrowOnOutOfBoundsReversedSeeker() {
			final SortIndex sortIndex = createIndexWithBaseCardinalities();
			final SortedComparableForwardSeeker seeker =
				sortIndex.createReversedSortedComparableForwardSeeker();

			assertThrows(ArrayIndexOutOfBoundsException.class, () -> seeker.getValueToCompareOn(-1));
		}

		@Test
		@DisplayName("should create seeker via factory methods on SortIndex")
		void shouldCreateSeekerViaFactoryMethods() {
			final SortIndex sortIndex = createIndexWithBaseCardinalities();

			final SortedComparableForwardSeeker forward = sortIndex.createSortedComparableForwardSeeker();
			assertNotNull(forward);

			final SortedComparableForwardSeeker reversed =
				sortIndex.createReversedSortedComparableForwardSeeker();
			assertNotNull(reversed);
		}
	}

	@Nested
	@DisplayName("Construction and configuration")
	class ConstructionTest {

		@Test
		@DisplayName("should invert positions correctly")
		void shouldInvertPositionsCorrectly() {
			final int[] original = {0, 1, 2, 3, 4};
			final int[] inverted = SortIndex.invert(original);

			assertArrayEquals(new int[]{4, 3, 2, 1, 0}, inverted);
		}

		@Test
		@DisplayName("should invert single-element array")
		void shouldInvertSingleElementArray() {
			final int[] original = {0};
			final int[] inverted = SortIndex.invert(original);

			assertArrayEquals(new int[]{0}, inverted);
		}

		@Test
		@DisplayName("should create normalizer for BigDecimal type")
		void shouldCreateNormalizerForBigDecimal() {
			final ComparatorSource source =
				new ComparatorSource(BigDecimal.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST);
			final UnaryOperator<Serializable> normalizer = SortIndex.createNormalizerFor(source).orElseThrow();

			// BigDecimal "1.10" and "1.1" normalize to same value
			final Serializable normalized1 = normalizer.apply(new BigDecimal("1.10"));
			final Serializable normalized2 = normalizer.apply(new BigDecimal("1.1"));
			assertEquals(normalized1, normalized2);

			// null input returns null
			assertNull(normalizer.apply(null));
		}

		@Test
		@DisplayName("should create normalizer for Locale type")
		void shouldCreateNormalizerForLocale() {
			final ComparatorSource source =
				new ComparatorSource(Locale.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST);
			final UnaryOperator<Serializable> normalizer = SortIndex.createNormalizerFor(source).orElseThrow();

			final Serializable result = normalizer.apply(Locale.ENGLISH);
			assertInstanceOf(ComparableLocale.class, result);
			assertNull(normalizer.apply(null));
		}

		@Test
		@DisplayName("should create normalizer for Currency type")
		void shouldCreateNormalizerForCurrency() {
			final ComparatorSource source =
				new ComparatorSource(Currency.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST);
			final UnaryOperator<Serializable> normalizer = SortIndex.createNormalizerFor(source).orElseThrow();

			final Serializable result = normalizer.apply(Currency.getInstance("USD"));
			assertInstanceOf(ComparableCurrency.class, result);
			assertNull(normalizer.apply(null));
		}

		@Test
		@DisplayName("should create normalizer for String type")
		void shouldCreateNormalizerForString() {
			final ComparatorSource source =
				new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST);
			final UnaryOperator<Serializable> normalizer = SortIndex.createNormalizerFor(source).orElseThrow();

			final Serializable result = normalizer.apply("\u00e9"); // e-acute
			assertNotNull(result);
			assertNull(normalizer.apply(null));
		}

		@Test
		@DisplayName("should return empty normalizer for Integer type")
		void shouldReturnEmptyNormalizerForInteger() {
			final ComparatorSource source =
				new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST);
			assertTrue(SortIndex.createNormalizerFor(source).isEmpty());
		}

		@SuppressWarnings("rawtypes")
		@Test
		@DisplayName("should create NULLS_FIRST ASC comparator")
		void shouldCreateNullsFirstAscComparator() {
			final ComparatorSource source =
				new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_FIRST);
			final Comparator comparator = SortIndex.createComparatorFor(null, source);

			assertInstanceOf(NullsFirstComparatorWrapper.class, comparator);
			// null should come before any value
			assertTrue(comparator.compare(null, "A") < 0);
		}

		@SuppressWarnings("rawtypes")
		@Test
		@DisplayName("should create NULLS_LAST DESC comparator")
		void shouldCreateNullsLastDescComparator() {
			final ComparatorSource source =
				new ComparatorSource(String.class, OrderDirection.DESC, OrderBehaviour.NULLS_LAST);
			final Comparator<String> comparator = SortIndex.createComparatorFor(null, source);

			assertInstanceOf(NullsLastComparatorWrapper.class, comparator);
			// null should come after any value (last)
			assertTrue(comparator.compare(null, "A") > 0);
			// DESC: "B" < "A" (reversed)
			assertTrue(comparator.compare("B", "A") < 0);
		}

		@SuppressWarnings("rawtypes")
		@Test
		@DisplayName("should create NULLS_FIRST DESC comparator")
		void shouldCreateNullsFirstDescComparator() {
			final ComparatorSource source =
				new ComparatorSource(String.class, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST);
			final Comparator comparator = SortIndex.createComparatorFor(null, source);

			assertInstanceOf(NullsFirstComparatorWrapper.class, comparator);
			// null should come first
			assertTrue(comparator.compare(null, "A") < 0);
			// DESC: "B" < "A" (reversed)
			assertTrue(comparator.compare("B", "A") < 0);
		}

		@SuppressWarnings("rawtypes")
		@Test
		@DisplayName("should create NULLS_LAST ASC comparator")
		void shouldCreateNullsLastAscComparator() {
			final ComparatorSource source =
				new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST);
			final Comparator comparator = SortIndex.createComparatorFor(null, source);

			assertInstanceOf(NullsLastComparatorWrapper.class, comparator);
			// null should come last
			assertTrue(comparator.compare(null, "A") > 0);
			// ASC: "A" < "B"
			assertTrue(comparator.compare("A", "B") < 0);
		}

		@Test
		@DisplayName("should throw for non-Comparable type in ComparatorSource")
		void shouldThrowForNonComparableType() {
			assertThrows(
				IllegalArgumentException.class,
				() -> new ComparatorSource(Object.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			);
		}

		@Test
		@DisplayName("should throw when multi-field constructor receives single comparator")
		void shouldThrowWhenSingleComparatorPassedToMultiField() {
			assertThrows(
				IllegalArgumentException.class,
				() -> new SortIndex(
					new ComparatorSource[]{
						new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					},
					new AttributeIndexKey(null, "a", null)
				)
			);
		}

		@Test
		@DisplayName("should construct via 6-arg deserialization constructor")
		void shouldConstructViaDeserializationConstructor() {
			final ComparatorSource[] base = new ComparatorSource[]{
				new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			};
			final Map<Serializable, Integer> cardinalities = new HashMap<>(4);
			cardinalities.put("B", 2);

			final SortIndex sortIndex = new SortIndex(
				base, null,
				new AttributeIndexKey(null, "a", null),
				new int[]{1, 2, 3},
				new String[]{"A", "B"},
				cardinalities
			);

			assertEquals(3, sortIndex.size());
			assertFalse(sortIndex.isEmpty());
			assertArrayEquals(new int[]{1, 2, 3}, sortIndex.getSortedRecords());
			assertArrayEquals(new String[]{"A", "B"}, sortIndex.getSortedRecordValues());
		}

		@Test
		@DisplayName("should return reference key from referenceKey constructor")
		void shouldReturnReferenceKeyFromConstructor() {
			final RepresentativeReferenceKey refKey = new RepresentativeReferenceKey(new ReferenceKey("brand", 1));
			final SortIndex sortIndex = new SortIndex(
				String.class, refKey, new AttributeIndexKey(null, "name", null)
			);

			assertNotNull(sortIndex.getReferenceKey());
			assertSame(refKey, sortIndex.getReferenceKey());
		}

		@Test
		@DisplayName("should return null referenceKey for non-reference constructor")
		void shouldReturnNullReferenceKeyForNonReference() {
			final SortIndex sortIndex = new SortIndex(String.class, new AttributeIndexKey(null, "name", null));

			assertNull(sortIndex.getReferenceKey());
		}
	}

	@Nested
	@DisplayName("ComparableArray contract")
	class ComparableArrayTest {

		@Test
		@DisplayName("should have consistent equals for same arrays")
		void shouldHaveConsistentEquals() {
			final ComparableArray a = new ComparableArray(new Serializable[]{"A", 1});
			final ComparableArray b = new ComparableArray(new Serializable[]{"A", 1});

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not equal for different arrays")
		void shouldNotEqualForDifferentArrays() {
			final ComparableArray a = new ComparableArray(new Serializable[]{"A", 1});
			final ComparableArray b = new ComparableArray(new Serializable[]{"B", 2});

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final ComparableArray arr = new ComparableArray(new Serializable[]{"Hello", 42});

			final String result = arr.toString();
			assertTrue(result.contains("Hello"));
			assertTrue(result.contains("42"));
		}

		@Test
		@DisplayName("should handle reflexive equals")
		void shouldHandleReflexiveEquals() {
			final ComparableArray a = new ComparableArray(new Serializable[]{"X"});

			assertEquals(a, a);
		}

		@Test
		@DisplayName("should handle null and different type in equals")
		void shouldHandleNullAndDifferentTypeInEquals() {
			final ComparableArray a = new ComparableArray(new Serializable[]{"X"});

			assertNotEquals(null, a);
			assertNotEquals("not-a-comparable-array", a);
		}
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
