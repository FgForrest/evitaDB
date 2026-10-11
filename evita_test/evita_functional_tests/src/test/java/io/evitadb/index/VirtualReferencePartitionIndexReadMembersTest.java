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

import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.Set;

import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.INTEGER_ASC;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.ORDER;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.REFERENCE_NAME;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the read members of {@link VirtualReferencePartitionIndex} a closed-world check is built on: the entry
 * count across every slot, and the declared sort and chain keys.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition index read members")
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualReferencePartitionIndexReadMembersTest {
	/**
	 * A chain key of the fixture.
	 */
	private static final AttributeIndexKey CHAIN = new AttributeIndexKey(REFERENCE_NAME, "chain", null);

	@Test
	@DisplayName("an empty index has no entries and declares no keys")
	void shouldReportNothingForAnEmptyIndex() {
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();

		assertEquals(0, VirtualReferencePartitionIndexEntries.count(index));
		assertEquals(Set.of(), index.getDeclaredSortKeys());
		assertEquals(Set.of(), index.getDeclaredChainKeys());
	}

	@Test
	@DisplayName("every slot counts its entries, and declaring a key adds none")
	void shouldCountTheEntriesOfEverySlot() {
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		index.putPartitionOwner(1, 10, 100);
		index.putPartitionOwner(2, 20, 200);
		assertEquals(2, VirtualReferencePartitionIndexEntries.count(index));

		index.putRepresentativeTuple(2, new Serializable[]{"detail"});
		assertEquals(3, VirtualReferencePartitionIndexEntries.count(index));

		index.declareSortKey(ORDER, INTEGER_ASC, 0);
		index.declareChainKey(CHAIN);
		assertEquals(3, VirtualReferencePartitionIndexEntries.count(index), "declaring a key adds no entry");
		assertEquals(Set.of(ORDER), index.getDeclaredSortKeys());
		assertEquals(Set.of(CHAIN), index.getDeclaredChainKeys());

		index.putSortValue(ORDER, 1, 5);
		index.putSortValue(ORDER, 2, 5);
		index.putChainPredecessor(CHAIN, 1, -1);
		assertEquals(6, VirtualReferencePartitionIndexEntries.count(index));

		// replacing a value or a predecessor replaces the entry
		index.putSortValue(ORDER, 1, 7);
		index.putChainPredecessor(CHAIN, 1, 2);
		index.putPartitionOwner(1, 11, 100);
		assertEquals(6, VirtualReferencePartitionIndexEntries.count(index));

		index.removeSortValue(ORDER, 2);
		index.removeChainPredecessor(CHAIN, 1);
		index.removeRepresentativeTuple(2);
		assertEquals(3, VirtualReferencePartitionIndexEntries.count(index));
	}

	@Test
	@DisplayName("retiring a key drops its entries and its declaration")
	void shouldForgetARetiredKey() {
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		index.putPartitionOwner(1, 10, 100);
		index.declareSortKey(ORDER, INTEGER_ASC, 0);
		index.declareChainKey(CHAIN);
		index.putSortValue(ORDER, 1, 5);
		index.putChainPredecessor(CHAIN, 1, -1);

		index.retireSortKey(ORDER);
		assertEquals(Set.of(), index.getDeclaredSortKeys());
		assertEquals(2, VirtualReferencePartitionIndexEntries.count(index));

		index.retireChainKey(CHAIN);
		assertEquals(Set.of(), index.getDeclaredChainKeys());
		assertEquals(1, VirtualReferencePartitionIndexEntries.count(index));

		// the values of a retired key can no longer be read - the index is empty only when none of them is left
		index.removePartitionOwner(1);
		assertTrue(index.isEmpty(), "a retired key must leave none of its entries behind");
	}

	@Test
	@DisplayName("the declared keys are a copy the index does not change afterwards")
	void shouldReturnACopyOfTheDeclaredKeys() {
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		index.declareSortKey(ORDER, INTEGER_ASC, 0);
		final Set<AttributeIndexKey> declared = index.getDeclaredSortKeys();

		index.retireSortKey(ORDER);

		assertEquals(Set.of(ORDER), declared);
		assertThrows(UnsupportedOperationException.class, () -> declared.add(CHAIN));
	}

	@Test
	@Tag(TRANSACTION)
	@DisplayName("an open transaction reads its own entries and keys, the committed index keeps its own")
	void shouldReadTheTransactionsOwnState() {
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		index.putPartitionOwner(1, 10, 100);
		index.declareSortKey(ORDER, INTEGER_ASC, 0);
		index.putSortValue(ORDER, 1, 5);

		assertStateAfterCommit(
			index,
			original -> {
				original.putPartitionOwner(2, 20, 200);
				original.declareChainKey(CHAIN);
				original.putChainPredecessor(CHAIN, 2, -1);
				original.retireSortKey(ORDER);

				assertEquals(3, VirtualReferencePartitionIndexEntries.count(original));
				assertEquals(Set.of(), original.getDeclaredSortKeys());
				assertEquals(Set.of(CHAIN), original.getDeclaredChainKeys());
			},
			(original, committed) -> {
				assertEquals(2, VirtualReferencePartitionIndexEntries.count(original));
				assertEquals(Set.of(ORDER), original.getDeclaredSortKeys());
				assertEquals(Set.of(), original.getDeclaredChainKeys());
				assertEquals(3, VirtualReferencePartitionIndexEntries.count(committed));
				assertEquals(Set.of(), committed.getDeclaredSortKeys());
				assertEquals(Set.of(CHAIN), committed.getDeclaredChainKeys());
			}
		);
	}
}
