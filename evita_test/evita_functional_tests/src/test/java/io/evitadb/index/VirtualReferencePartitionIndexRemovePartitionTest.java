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

import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.INTEGER_ASC;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.ORDER;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.REFERENCE_NAME;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link VirtualReferencePartitionIndex#removePartition(int)}, which forgets everything recorded about one
 * partition, and {@link VirtualReferencePartitionIndex#retireAllKeysIfNoPartition()}, which keeps the index from
 * declaring keys once it has no owner row left.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition index partition removal")
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualReferencePartitionIndexRemovePartitionTest {
	/**
	 * A chain key of the fixture.
	 */
	private static final AttributeIndexKey CHAIN = new AttributeIndexKey(REFERENCE_NAME, "chain", null);

	/**
	 * Creates an index with two partitions, each with an owner row, a tuple, a sort value and a predecessor.
	 *
	 * @return the index
	 */
	private static VirtualReferencePartitionIndex twoPartitions() {
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		index.declareSortKey(ORDER, INTEGER_ASC, 0, () -> SortIndex.createTypeLevelNormalizerFor(INTEGER_ASC, 0));
		index.declareChainKey(CHAIN);
		for (int partition = 1; partition <= 2; partition++) {
			index.putPartitionOwner(partition, partition * 10, partition * 100);
			index.putRepresentativeTuple(partition, new Serializable[]{"role" + partition});
			index.putSortValue(ORDER, partition, partition * 5);
			index.putChainPredecessor(CHAIN, partition, -1);
		}
		return index;
	}

	@Test
	@DisplayName("removing a partition forgets its row, tuple, sort values and predecessors and nothing else")
	void shouldForgetEverythingAboutOnePartition() {
		final VirtualReferencePartitionIndex index = twoPartitions();
		assertEquals(8, VirtualReferencePartitionIndexEntries.count(index));

		assertTrue(index.removePartition(1));

		assertTrue(index.getPartitionOwner(1).isEmpty());
		assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, index.getRepresentativeTupleId(1));
		assertEquals(OptionalInt.empty(), index.getChainPredecessor(CHAIN, 1));
		assertEquals(List.of(2), VirtualReferencePartitionIndexSortValueTest.snapshotOf(index, ORDER).records());
		assertEquals(
			4, VirtualReferencePartitionIndexEntries.count(index, 1), "only the four entries of partition 2 remain"
		);
		// the tuple of partition 1 had no other user and leaves the dictionary
		assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, index.getTupleId(new Serializable[]{"role1"}));
		assertEquals(Set.of(ORDER), index.getDeclaredSortKeys(), "the keys stay declared");
		assertEquals(Set.of(CHAIN), index.getDeclaredChainKeys(), "the keys stay declared");
	}

	@Test
	@DisplayName("removing a partition the index knows nothing about changes nothing")
	void shouldReportNothingRemovedForAnUnknownPartition() {
		final VirtualReferencePartitionIndex index = twoPartitions();

		assertFalse(index.removePartition(3));

		assertEquals(8, VirtualReferencePartitionIndexEntries.count(index));
	}

	@Test
	@DisplayName("a partition with a row only loses just the row")
	void shouldRemoveAPartitionWithARowOnly() {
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		index.putPartitionOwner(1, 10, 100);

		assertTrue(index.removePartition(1));

		assertTrue(index.isEmpty());
	}

	@Test
	@DisplayName("the keys are retired only once no partition has an owner row")
	void shouldRetireTheKeysOnlyWithTheLastOwnerRow() {
		final VirtualReferencePartitionIndex index = twoPartitions();

		index.removePartition(1);
		assertFalse(index.retireAllKeysIfNoPartition(), "partition 2 still has its row");
		assertEquals(Set.of(ORDER), index.getDeclaredSortKeys());
		assertEquals(Set.of(CHAIN), index.getDeclaredChainKeys());

		index.removePartition(2);
		assertTrue(index.retireAllKeysIfNoPartition());
		assertEquals(Set.of(), index.getDeclaredSortKeys());
		assertEquals(Set.of(), index.getDeclaredChainKeys());
		assertEquals(0, index.getDistinctSortValueCount(ORDER));
		assertTrue(index.isEmpty(), "an index without rows and keys is empty and persists nothing");

		assertFalse(index.retireAllKeysIfNoPartition(), "nothing is left to retire");
	}

	@Test
	@DisplayName("keys declared before any row exists are retired as well")
	void shouldRetireKeysOfAnIndexWithoutRows() {
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		index.declareSortKey(ORDER, INTEGER_ASC, 0, () -> SortIndex.createTypeLevelNormalizerFor(INTEGER_ASC, 0));

		assertTrue(index.retireAllKeysIfNoPartition());

		assertTrue(index.isEmpty());
	}

	@Test
	@DisplayName("a removal committed in a transaction is in the committed copy")
	@Tag(TRANSACTION)
	void shouldCommitARemoval() {
		assertStateAfterCommit(
			twoPartitions(),
			original -> {
				original.removePartition(1);
				original.removePartition(2);
				original.retireAllKeysIfNoPartition();
				assertTrue(original.isEmpty());
			},
			(original, committed) -> {
				assertEquals(
					8, VirtualReferencePartitionIndexEntries.count(original),
					"the original is untouched by the transaction"
				);
				assertTrue(committed.isEmpty());
				assertEquals(Set.of(), committed.getDeclaredSortKeys());
			}
		);
	}

	@Test
	@DisplayName("removing a partition the index knows nothing about in a transaction leaves no layer behind")
	@Tag(TRANSACTION)
	void shouldCommitARemovalOfAnUnknownPartition() {
		// a removal of an absent key must not open a layer of the leaf it searched, because nothing marks the index
		// dirty then and its commit would leave that layer unmerged - the commit verifies every layer was swept
		final VirtualReferencePartitionIndex index = twoPartitions();
		// as after a flush or a load: clean, so only a write of this transaction can make the commit merge the tree
		index.resetDirty();
		assertStateAfterCommit(
			index,
			original -> {
				assertFalse(original.removePartition(3));
				assertFalse(original.removePartitionOwner(3));
				assertFalse(original.removeChainPredecessor(CHAIN, 3));
			},
			(original, committed) -> assertEquals(8, VirtualReferencePartitionIndexEntries.count(committed))
		);
	}

	@Test
	@DisplayName("a removal rolled back with its transaction leaves the index as it was")
	@Tag(TRANSACTION)
	void shouldRollBackARemoval() {
		assertStateAfterRollback(
			twoPartitions(),
			original -> {
				original.removePartition(1);
				original.removePartition(2);
				original.retireAllKeysIfNoPartition();
			},
			(original, committed) -> {
				assertNull(committed);
				assertEquals(8, VirtualReferencePartitionIndexEntries.count(original));
				assertEquals(Set.of(ORDER), original.getDeclaredSortKeys());
				assertTrue(original.getPartitionOwner(1).isPresent());
			}
		);
	}
}
