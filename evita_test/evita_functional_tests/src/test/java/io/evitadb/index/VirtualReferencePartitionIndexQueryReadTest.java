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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.VirtualReferencePartitionIndex.PartitionOwnerCursor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;

import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.INTEGER_ASC;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.ORDER;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.REFERENCE_NAME;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the members of {@link VirtualReferencePartitionIndex} a query reads a virtual partition through: the value
 * id of a partition's sort value and the value it names, and the cursor over the owner rows a split of candidate
 * partitions merges with.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition index query reads")
@Tag(INDEXING)
@Tag(QUERY)
@Tag(REFERENCE)
class VirtualReferencePartitionIndexQueryReadTest {
	/**
	 * A sort key the fixture never declares.
	 */
	private static final AttributeIndexKey UNDECLARED = new AttributeIndexKey(REFERENCE_NAME, "undeclared", null);

	@Nested
	@DisplayName("sort value ids")
	class SortValueIds {

		@Test
		@DisplayName("partitions holding one value share its id, which resolves to the normalized value")
		void shouldNameAValueByOneIdAcrossPartitions() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 1, 5);
			index.putSortValue(ORDER, 2, 5);
			index.putSortValue(ORDER, 3, 7);

			final int five = index.getSortValueId(ORDER, 1);
			assertEquals(five, index.getSortValueId(ORDER, 2));
			final int seven = index.getSortValueId(ORDER, 3);
			assertNotEquals(five, seven);
			assertTrue(five > 0 && seven > 0, "Value ids in use are positive");
			assertEquals(5, index.getNormalizedSortValue(ORDER, five));
			assertEquals(7, index.getNormalizedSortValue(ORDER, seven));
		}

		@Test
		@DisplayName("a partition without a value, or a key not declared, has no value id")
		void shouldReportNoValueId() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 1, 5);

			assertEquals(VirtualReferencePartitionIndex.NO_SORT_VALUE_ID, index.getSortValueId(ORDER, 2));
			assertEquals(VirtualReferencePartitionIndex.NO_SORT_VALUE_ID, index.getSortValueId(UNDECLARED, 1));
		}

		@Test
		@DisplayName("an id not in use, or a key not declared, cannot be resolved to a value")
		void shouldRejectAnUnknownValueId() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 1, 5);
			final int five = index.getSortValueId(ORDER, 1);

			assertThrows(GenericEvitaInternalError.class, () -> index.getNormalizedSortValue(ORDER, five + 1));
			assertThrows(GenericEvitaInternalError.class, () -> index.getNormalizedSortValue(UNDECLARED, five));
			// the last holder of the value leaves - its id is no longer in use
			index.removeSortValue(ORDER, 1);
			assertThrows(GenericEvitaInternalError.class, () -> index.getNormalizedSortValue(ORDER, five));
		}

		@Test
		@Tag(TRANSACTION)
		@DisplayName("an open transaction reads the value ids of its own writes")
		void shouldReadTheTransactionsOwnValueIds() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 1, 5);

			assertStateAfterCommit(
				index,
				original -> {
					original.putSortValue(ORDER, 2, 9);
					final int nine = original.getSortValueId(ORDER, 2);
					assertNotEquals(VirtualReferencePartitionIndex.NO_SORT_VALUE_ID, nine);
					assertEquals(9, original.getNormalizedSortValue(ORDER, nine));
				},
				(original, committed) -> {
					assertEquals(VirtualReferencePartitionIndex.NO_SORT_VALUE_ID, original.getSortValueId(ORDER, 2));
					assertEquals(9, committed.getNormalizedSortValue(ORDER, committed.getSortValueId(ORDER, 2)));
				}
			);
		}

	}

	@Nested
	@DisplayName("owner row cursor")
	class OwnerRowCursor {

		@Test
		@DisplayName("the cursor walks the owner rows from its start in ascending order and stops at the slot's end")
		void shouldWalkTheOwnerRowsInOrder() {
			final VirtualReferencePartitionIndex index = indexWithRows(3, 5, 9, 12);
			final PartitionOwnerCursor cursor = index.seekPartitionOwner(4);

			assertTrue(cursor.next());
			assertEquals(5, cursor.partitionPrimaryKey());
			assertEquals(NumberUtils.pack(-5, 50), cursor.ownerAndTarget());
			assertTrue(cursor.next());
			assertEquals(9, cursor.partitionPrimaryKey());
			assertTrue(cursor.next());
			assertEquals(12, cursor.partitionPrimaryKey());
			// the representative tuples are the next slot - the cursor must not read into it
			assertFalse(cursor.next());
			assertFalse(cursor.next());
		}

		@Test
		@DisplayName("advancing reaches the first row at or after the key, stays on it, and never moves back")
		void shouldAdvanceToTheFirstRowAtOrAfterTheKey() {
			final int[] rows = new int[100];
			for (int i = 0; i < rows.length; i++) {
				rows[i] = 2 * (i + 1);
			}
			final VirtualReferencePartitionIndex index = indexWithRows(rows);
			final PartitionOwnerCursor cursor = index.seekPartitionOwner(1);

			assertTrue(cursor.advanceTo(3));
			assertEquals(4, cursor.partitionPrimaryKey(), "A step reaches the next row");
			assertTrue(cursor.advanceTo(4));
			assertEquals(4, cursor.partitionPrimaryKey(), "A cursor on the key stays");
			assertTrue(cursor.advanceTo(2));
			assertEquals(4, cursor.partitionPrimaryKey(), "A cursor never moves back");
			assertTrue(cursor.advanceTo(150));
			assertEquals(150, cursor.partitionPrimaryKey(), "A far row is reached by a descent");
			assertTrue(cursor.advanceTo(151));
			assertEquals(152, cursor.partitionPrimaryKey(), "The row right after a descent is not skipped");
			assertTrue(cursor.advanceTo(200));
			assertEquals(200, cursor.partitionPrimaryKey());
			assertFalse(cursor.advanceTo(201), "No row lies after the last one");
		}

		@Test
		@DisplayName("a cursor over an index without rows finds nothing")
		void shouldFindNothingWithoutRows() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			assertFalse(index.seekPartitionOwner(1).next());
			assertFalse(index.seekPartitionOwner(1).advanceTo(5));
		}

		/**
		 * Creates an index with an owner row - and a representative tuple after it in the next slot - for each
		 * partition.
		 *
		 * @param partitions the partitions with a row
		 * @return the index
		 */
		private static VirtualReferencePartitionIndex indexWithRows(int... partitions) {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			for (final int partition : partitions) {
				index.putPartitionOwner(partition, -partition, 10 * partition);
				index.putRepresentativeTuple(partition, new Serializable[]{"role"});
			}
			return index;
		}

	}

}
