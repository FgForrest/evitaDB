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

package io.evitadb.index.attribute;

import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link ChainIndex#getPredecessorPrimaryKey(int)}: an element reports the predecessor it declares - a head
 * its head marker - wherever the materialized order has placed it, an element the index does not hold reports absence,
 * and an open transaction reads its own writes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("ChainIndex predecessor of an element")
class ChainIndexPredecessorPrimaryKeyTest {
	private static final AttributeIndexKey KEY = new AttributeIndexKey(null, "order", null);

	@Test
	@DisplayName("an empty index holds no element")
	void shouldReturnAbsenceFromAnEmptyIndex() {
		final ChainIndex chainIndex = new ChainIndex(KEY);

		assertEquals(OptionalInt.empty(), chainIndex.getPredecessorPrimaryKey(1));
	}

	@Test
	@DisplayName("every element reports the predecessor it declares, a head its head marker")
	void shouldReturnTheDeclaredPredecessor() {
		final ChainIndex chainIndex = new ChainIndex(KEY);
		chainIndex.upsertPredecessor(Predecessor.HEAD, 1);
		chainIndex.upsertPredecessor(new Predecessor(1), 2);
		chainIndex.upsertPredecessor(new Predecessor(2), 3);

		assertEquals(OptionalInt.of(ChainableType.HEAD_PK), chainIndex.getPredecessorPrimaryKey(1));
		assertEquals(OptionalInt.of(1), chainIndex.getPredecessorPrimaryKey(2));
		assertEquals(OptionalInt.of(2), chainIndex.getPredecessorPrimaryKey(3));
		assertEquals(OptionalInt.empty(), chainIndex.getPredecessorPrimaryKey(4));
	}

	@Test
	@DisplayName("an element pointing at a missing predecessor reports it, though the order cannot tell")
	void shouldReturnAPredecessorTheOrderDoesNotShow() {
		final ChainIndex chainIndex = new ChainIndex(KEY);
		chainIndex.upsertPredecessor(Predecessor.HEAD, 1);
		chainIndex.upsertPredecessor(new Predecessor(1), 2);
		chainIndex.upsertPredecessor(new Predecessor(99), 3);

		// the element of a dangling chain is placed after the head chain, where its true successor would sit too
		assertArrayEquals(new int[]{1, 2, 3}, chainIndex.getUnorderedLookup().getArray());
		assertEquals(OptionalInt.of(99), chainIndex.getPredecessorPrimaryKey(3));
	}

	@Test
	@DisplayName("a referenced entity predecessor is reported as the chain index received it")
	void shouldReturnAReferencedEntityPredecessor() {
		final ChainIndex chainIndex = new ChainIndex(KEY);
		chainIndex.upsertPredecessor(ReferencedEntityPredecessor.HEAD, 7);
		chainIndex.upsertPredecessor(new ReferencedEntityPredecessor(7), 8);

		assertEquals(OptionalInt.of(ChainableType.HEAD_PK), chainIndex.getPredecessorPrimaryKey(7));
		assertEquals(OptionalInt.of(7), chainIndex.getPredecessorPrimaryKey(8));
	}

	@Test
	@DisplayName("a relocated element reports its new predecessor, a removed one absence")
	void shouldFollowRelocationAndRemoval() {
		final ChainIndex chainIndex = new ChainIndex(KEY);
		chainIndex.upsertPredecessor(Predecessor.HEAD, 1);
		chainIndex.upsertPredecessor(new Predecessor(1), 2);
		chainIndex.upsertPredecessor(new Predecessor(2), 3);

		chainIndex.upsertPredecessor(new Predecessor(1), 3);
		chainIndex.removePredecessor(2);

		assertEquals(OptionalInt.of(1), chainIndex.getPredecessorPrimaryKey(3));
		assertEquals(OptionalInt.empty(), chainIndex.getPredecessorPrimaryKey(2));
	}

	@Test
	@Tag(TRANSACTION)
	@DisplayName("an open transaction reads its own writes, the committed index keeps its own predecessors")
	void shouldReadTheTransactionsOwnWrites() {
		final ChainIndex chainIndex = new ChainIndex(KEY);
		chainIndex.upsertPredecessor(Predecessor.HEAD, 1);
		chainIndex.upsertPredecessor(new Predecessor(1), 2);

		assertStateAfterCommit(
			chainIndex,
			original -> {
				original.upsertPredecessor(new Predecessor(2), 3);
				original.upsertPredecessor(new Predecessor(3), 2);
				original.removePredecessor(1);

				assertEquals(OptionalInt.empty(), original.getPredecessorPrimaryKey(1));
				assertEquals(OptionalInt.of(3), original.getPredecessorPrimaryKey(2));
				assertEquals(OptionalInt.of(2), original.getPredecessorPrimaryKey(3));
			},
			(original, committed) -> {
				assertEquals(OptionalInt.of(ChainableType.HEAD_PK), original.getPredecessorPrimaryKey(1));
				assertEquals(OptionalInt.of(1), original.getPredecessorPrimaryKey(2));
				assertEquals(OptionalInt.empty(), original.getPredecessorPrimaryKey(3));
				assertEquals(OptionalInt.empty(), committed.getPredecessorPrimaryKey(1));
				assertEquals(OptionalInt.of(3), committed.getPredecessorPrimaryKey(2));
				assertEquals(OptionalInt.of(2), committed.getPredecessorPrimaryKey(3));
			}
		);
	}
}
