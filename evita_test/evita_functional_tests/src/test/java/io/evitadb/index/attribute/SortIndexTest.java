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
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static java.text.Normalizer.Form;
import static java.text.Normalizer.normalize;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * This test verifies contract of {@link SortIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class SortIndexTest {

	private static final Locale CZECH_LOCALE = new Locale("cs");

	@Test
	void shouldCreateIndexWithDifferentCardinalities() {
		final SortIndex sortIndex = createIndexWithBaseCardinalities();
		assertTrue(sortIndex.getRecordsEqualTo("Z").isEmpty());
		assertEquals(1, sortIndex.getValueCardinality("A"));
		assertEquals(2, sortIndex.getValueCardinality("B"));
		assertEquals(4, sortIndex.getValueCardinality("C"));
		assertArrayEquals(new String[]{"A", "B", "C", "E"}, sortIndex.getSortedRecordValues());
		assertArrayEquals(new int[]{6, 4, 5, 1, 2, 3, 7, 9}, sortIndex.sortedRecords.getArray());
	}

	@Test
	void shouldCreateCompoundIndexWithDifferentCardinalities() {
		final SortIndex sortIndex = createCompoundIndexWithBaseCardinalities();
		assertTrue(sortIndex.getRecordsEqualTo(new Serializable[]{"Z", 1}).isEmpty());
		assertTrue(sortIndex.getRecordsEqualTo(new Serializable[]{"A", 2}).isEmpty());
		assertEquals(2, sortIndex.getValueCardinality(new ComparableArray(new Serializable[]{"B", 1})));
		assertEquals(2, sortIndex.getValueCardinality(new ComparableArray(new Serializable[]{"C", 9})));
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
			sortIndex.getSortedRecordValues()
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
		assertTrue(sortIndex.getRecordsEqualTo("Z").isEmpty());
		assertTrue(sortIndex.getRecordsEqualTo("A").isEmpty());
		assertEquals(1, sortIndex.getValueCardinality("B"));
		assertEquals(3, sortIndex.getValueCardinality("C"));
		assertArrayEquals(new String[]{"B", "C", "E"}, sortIndex.getSortedRecordValues());
		assertArrayEquals(new int[]{5, 2, 3, 7, 9}, sortIndex.sortedRecords.getArray());
	}

	@Test
	void shouldIndexRecordsAndReturnInAscendingOrder() {
		final SortIndex sortIndex = new OwnerSortIndex(Integer.class, new AttributeIndexKey(null, "a", null));
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
		final SortIndex sortIndex = new OwnerSortIndex(Integer.class, new AttributeIndexKey(null, "a", null));
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
		final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", CZECH_LOCALE));
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
		// values are pre-normalized (stripTrailingZeros) as they would be by EntityIndexLocalMutationExecutor
		final SortIndex sortIndex = new OwnerSortIndex(BigDecimal.class, new AttributeIndexKey(null, "a", CZECH_LOCALE));
		sortIndex.addRecord(new BigDecimal("0"), 1);
		sortIndex.addRecord(new BigDecimal("0"), 2);
		sortIndex.addRecord(new BigDecimal("0"), 3);
		sortIndex.addRecord(new BigDecimal("1.1"), 4);
		sortIndex.addRecord(new BigDecimal("1.1"), 5);
		sortIndex.addRecord(new BigDecimal("2"), 6);
		assertArrayEquals(
			new int[]{1, 2, 3, 4, 5, 6},
			sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds()
		);

		sortIndex.removeRecord(new BigDecimal("0"), 2);
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
		final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));
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
		final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", CZECH_LOCALE));
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
			sortIndex.getSortedRecordValues()
		);
		assertArrayEquals(new int[]{1, 4, 7, 6, 2, 3, 5}, sortIndex.sortedRecords.getArray());
	}

	@Nonnull
	private static SortIndex createIndexWithBaseCardinalities() {
		final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", Locale.ENGLISH));
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
		final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", Locale.ENGLISH));
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
		final SortIndex sortIndex = new OwnerSortIndex(
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
			final SortIndex first = new OwnerSortIndex(Integer.class, new AttributeIndexKey(null, "x", null));
			final SortIndex second = new OwnerSortIndex(Integer.class, new AttributeIndexKey(null, "y", null));

			assertNotEquals(first.getId(), second.getId());
		}

		@Test
		@DisplayName("should return same instance when no mutations applied")
		void shouldReturnSameInstanceWhenNoMutationsApplied() {
			final SortIndex sortIndex = new OwnerSortIndex(Integer.class, new AttributeIndexKey(null, "a", null));

			assertStateAfterCommit(
				sortIndex,
				original -> {
					// no mutations
				},
				Assertions::assertSame
			);
		}

		@Test
		@DisplayName("should return new instance when dirty after commit")
		void shouldReturnNewInstanceWhenDirtyAfterCommit() {
			final SortIndex sortIndex = new OwnerSortIndex(Integer.class, new AttributeIndexKey(null, "a", null));

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
			final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));
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
			final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));
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
			final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));
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
			final SortIndex sortIndex = new OwnerSortIndex(Integer.class, new AttributeIndexKey(null, "a", null));

			assertTrue(sortIndex.isEmpty());
			assertEquals(0, sortIndex.size());
		}

		@Test
		@DisplayName("should append nothing when not dirty")
		void shouldAppendNothingWhenNotDirty() {
			final SortIndex sortIndex = new OwnerSortIndex(Integer.class, new AttributeIndexKey(null, "a", null));

			final TrappedChanges sink = new TrappedChanges();
			sortIndex.appendStorageParts(1, sink);
			assertEquals(0, sink.getTrappedChangesCount());
		}

		@Test
		@DisplayName("should append SortIndexStoragePart when dirty")
		void shouldAppendStoragePartWhenDirty() {
			final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "name", null));
			sortIndex.addRecord("Alpha", 1);
			sortIndex.addRecord("Beta", 2);

			final TrappedChanges sink = new TrappedChanges();
			sortIndex.appendStorageParts(42, sink);
			assertEquals(1, sink.getTrappedChangesCount());

			final StoragePart storagePart = sink.getTrappedChangesIterator().next();
			assertInstanceOf(SortIndexStoragePart.class, storagePart);

			final SortIndexStoragePart part = (SortIndexStoragePart) storagePart;
			assertEquals(42, part.getEntityIndexPrimaryKey());
			assertEquals(new AttributeIndexKey(null, "name", null), part.getAttributeIndexKey());
			assertArrayEquals(new int[]{1, 2}, part.getSortedRecords());

			// dirty is still true (only resetDirty() clears it) so a subsequent append emits the part again
			final TrappedChanges secondSink = new TrappedChanges();
			sortIndex.appendStorageParts(42, secondSink);
			assertEquals(1, secondSink.getTrappedChangesCount());
		}

		@Test
		@DisplayName("should reset dirty flag via resetDirty()")
		void shouldResetDirtyFlag() {
			final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));
			sortIndex.addRecord("X", 1);

			// dirty after add
			final TrappedChanges beforeReset = new TrappedChanges();
			sortIndex.appendStorageParts(1, beforeReset);
			assertEquals(1, beforeReset.getTrappedChangesCount());

			sortIndex.resetDirty();

			// not dirty anymore
			final TrappedChanges afterReset = new TrappedChanges();
			sortIndex.appendStorageParts(1, afterReset);
			assertEquals(0, afterReset.getTrappedChangesCount());
		}

		@Test
		@DisplayName("should cache sortIndexChanges in non-transactional mode")
		void shouldCacheSortIndexChangesNonTransactional() {
			final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));
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
			final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));
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
			final SortIndex sortIndex = new OwnerSortIndex(
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
			final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));

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
			final SortIndex sortIndex = new OwnerSortIndex(Integer.class, new AttributeIndexKey(null, "a", null));

			final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> sortIndex.addRecord("not-an-int", 1)
			);
			assertTrue(ex.getMessage().contains("must be of type"));
		}

		@Test
		@DisplayName("should throw when removing non-existent scalar value")
		void shouldThrowOnRemoveNonExistentScalarValue() {
			final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));
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
			final SortIndex sortIndex = new OwnerSortIndex(
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

		@Test
		@DisplayName("should throw when cardinality requested for value absent from owned tree")
		void shouldThrowWhenCardinalityRequestedForValueAbsentFromOwnedTree() {
			final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));
			sortIndex.addRecord("A", 1);

			// owner mode owns every present value in its tree, so a cardinality miss is a broken invariant, not a query
			// for an absent value (which callers must avoid) - it surfaces as a hard internal error
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> sortIndex.getValueCardinality("Z")
			);
			assertTrue(ex.getMessage().contains("Unexpected cardinality"));
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
			final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));
			sortIndex.addRecord("X", 1);
			sortIndex.addRecord("X", 2);

			// cardinality is 2
			assertEquals(2, sortIndex.getValueCardinality("X"));

			// remove one -- cardinality drops to 1 (still present, just no longer multi-record)
			sortIndex.removeRecord("X", 1);
			assertEquals(1, sortIndex.getValueCardinality("X"));

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
		@DisplayName("should not scale BigDecimal in the query-comparator normalizer (raw natural order is correct)")
		void shouldNotCreateNormalizerForBigDecimal() {
			final ComparatorSource source =
				new ComparatorSource(BigDecimal.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST);
			// the query-side overload leaves BigDecimal unscaled - sorting result rows by raw BigDecimal is order-correct
			assertTrue(SortIndex.createNormalizerFor(source).isEmpty());
		}

		@Test
		@DisplayName("should scale BigDecimal to a scaled int in the places-aware normalizer")
		void shouldScaleBigDecimalInPlacesAwareNormalizer() {
			final ComparatorSource source =
				new ComparatorSource(BigDecimal.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST);
			final UnaryOperator<Serializable> normalizer = SortIndex.createNormalizerFor(source, 2).orElseThrow();

			// 1.50 scaled by 2 decimal places becomes the order-preserving int 150
			assertEquals(150, normalizer.apply(new BigDecimal("1.50")));
			// the normalizer is idempotent: an already-scaled Integer (and null) passes through unchanged
			assertEquals(150, normalizer.apply(150));
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

			final Serializable result = normalizer.apply("é"); // e-acute
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

		@SuppressWarnings({"rawtypes", "unchecked"})
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

		@SuppressWarnings("unchecked")
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

		@SuppressWarnings({"rawtypes", "unchecked"})
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

		@SuppressWarnings({"rawtypes", "unchecked"})
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
				() -> new OwnerSortIndex(
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

			final SortIndex sortIndex = new OwnerSortIndex(
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
			final SortIndex sortIndex = new OwnerSortIndex(
				String.class, refKey, new AttributeIndexKey(null, "name", null)
			);

			assertNotNull(sortIndex.getReferenceKey());
			assertSame(refKey, sortIndex.getReferenceKey());
		}

		@Test
		@DisplayName("should return null referenceKey for non-reference constructor")
		void shouldReturnNullReferenceKeyForNonReference() {
			final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "name", null));

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

	@Nested
	@DisplayName("Supplier array memoization")
	class SupplierArrayMemoizationTest {

		@Test
		@DisplayName("descending supplier reflects an added record after the memo was materialized")
		void shouldReflectAddedRecordInDescendingSupplierAfterMemoization() {
			final SortIndex sortIndex = createIndexWithBaseCardinalities();

			// materialize the descending memo so a subsequent add must invalidate it
			assertArrayEquals(
				new int[]{9, 7, 3, 2, 1, 5, 4, 6},
				sortIndex.getDescendingOrderRecordsSupplier().getSortedRecordIds()
			);

			// add a second record into the "A" block - record ids inside a block stay in natural integer order
			sortIndex.addRecord("A", 8);

			// the descending supplier must expose the NEW absolute order, not the stale memoized array
			assertArrayEquals(
				new int[]{9, 7, 3, 2, 1, 5, 4, 8, 6},
				sortIndex.getDescendingOrderRecordsSupplier().getSortedRecordIds()
			);
		}

		@Test
		@DisplayName("both suppliers reflect a removed record after both memos were materialized")
		void shouldReflectRemovedRecordInBothSuppliersAfterMemoization() {
			final SortIndex sortIndex = createIndexWithBaseCardinalities();

			// materialize BOTH per-direction memos
			assertArrayEquals(
				new int[]{6, 4, 5, 1, 2, 3, 7, 9},
				sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds()
			);
			assertArrayEquals(
				new int[]{9, 7, 3, 2, 1, 5, 4, 6},
				sortIndex.getDescendingOrderRecordsSupplier().getSortedRecordIds()
			);

			// a single mutation must invalidate BOTH direction holders, not just the one that triggered it
			sortIndex.removeRecord("C", 2);

			assertArrayEquals(
				new int[]{6, 4, 5, 1, 3, 7, 9},
				sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds()
			);
			assertArrayEquals(
				new int[]{9, 7, 3, 1, 5, 4, 6},
				sortIndex.getDescendingOrderRecordsSupplier().getSortedRecordIds()
			);
		}

		@Test
		@DisplayName("repeated calls reuse the memoized arrays but mint a fresh seeker and provider each time")
		void shouldReturnConsistentArraysAndFreshSeekerAcrossRepeatedAscendingCalls() {
			final SortIndex sortIndex = createIndexWithBaseCardinalities();

			final SortedRecordsProvider first = sortIndex.getAscendingOrderRecordsSupplier();
			final SortedRecordsProvider second = sortIndex.getAscendingOrderRecordsSupplier();

			// the expensive memoized arrays/bitmap are stable across calls (no mutation in between)
			assertArrayEquals(first.getSortedRecordIds(), second.getSortedRecordIds());
			assertArrayEquals(first.getRecordPositions(), second.getRecordPositions());
			assertEquals(first.getAllRecords(), second.getAllRecords());

			// the stateful, monotonic seeker and the provider wrapper must be freshly built per call so concurrent
			// queries never share a cursor
			assertNotSame(first, second);
			assertNotSame(
				first.getSortedComparableForwardSeeker(),
				second.getSortedComparableForwardSeeker()
			);

			// the same fresh-seeker contract holds for the descending direction
			final SortedRecordsProvider firstDescending = sortIndex.getDescendingOrderRecordsSupplier();
			final SortedRecordsProvider secondDescending = sortIndex.getDescendingOrderRecordsSupplier();
			assertNotSame(firstDescending, secondDescending);
			assertNotSame(
				firstDescending.getSortedComparableForwardSeeker(),
				secondDescending.getSortedComparableForwardSeeker()
			);
		}

		@Test
		@DisplayName("deriving the descending arrays does not mutate the shared ascending arrays")
		void shouldNotMutateSharedAscendingArraysWhenDerivingDescending() {
			final SortIndex sortIndex = createIndexWithBaseCardinalities();

			final int[] ascendingIds = sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds();
			final int[] ascendingPositions = sortIndex.getAscendingOrderRecordsSupplier().getRecordPositions();
			final int[] ascendingIdsCopy = ascendingIds.clone();
			final int[] ascendingPositionsCopy = ascendingPositions.clone();

			// the descending arrays are reverse()/invert() derivations - both must allocate fresh and leave the shared
			// ascending arrays untouched
			final SortedRecordsProvider descending = sortIndex.getDescendingOrderRecordsSupplier();
			final int[] descendingIds = descending.getSortedRecordIds();
			final int[] descendingPositions = descending.getRecordPositions();

			// re-read the ascending arrays: they must be byte-for-byte what they were before descending was derived
			assertArrayEquals(
				ascendingIdsCopy,
				sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds()
			);
			assertArrayEquals(
				ascendingPositionsCopy,
				sortIndex.getAscendingOrderRecordsSupplier().getRecordPositions()
			);

			// and the descending arrays are exactly the reverse / inversion of the ascending ones
			assertArrayEquals(ArrayUtils.reverse(ascendingIdsCopy), descendingIds);
			assertArrayEquals(SortIndex.invert(ascendingPositionsCopy), descendingPositions);
		}
	}

}
