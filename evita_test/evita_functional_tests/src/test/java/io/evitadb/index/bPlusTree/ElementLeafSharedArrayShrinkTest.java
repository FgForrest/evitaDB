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

package io.evitadb.index.bPlusTree;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.function.ToIntFunction;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the transactional invariants that let {@link TransactionalElementBPlusTree.BPlusLeafTreeNode#setPeek(int)} blank
 * a shrunk leaf's vacated tail in place — safely — rather than pay a copy-on-write allocation for it.
 *
 * A leaf hands its transactional diff layer the very same value array (see `createLayer`), and merge / steal shrink one
 * holder while another still reports its own, larger peek. The historical defect was that the dirty-scope registry
 * retained the OTHER holder as a node token, so blanking the shrunk holder's tail reached through into a token the
 * pre-commit validation later dereferenced — a bare `NullPointerException` aborting the commit. The registry now keeps
 * boundary KEYS, not node objects, so no observer ever reads a shrunk holder's tail and the in-place blank is harmless.
 *
 * These tests guard that contract from three angles: the registry never holds a node object (the whole failure class is
 * forbidden), a root leaf deleted to empty commits without a spurious corruption error (the residual delete-to-empty
 * hole the copy-on-write shrink left open is closed), and a split-then-merge churn — the exact op mix that originally
 * NPE'd — commits cleanly with a structurally consistent tree.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Element leaf - shared value-array shrink is registry-safe")
@Tag(DATA_TYPE)
@Tag(INDEXING)
@Tag(TRANSACTION)
class ElementLeafSharedArrayShrinkTest {

	/**
	 * The key extractor used by the trees under test: the element's internal price id.
	 */
	private static final ToIntFunction<PriceRecordContract> KEY = PriceRecordContract::internalPriceId;

	/**
	 * Builds a price record whose fields are derived from the given key.
	 *
	 * @param key the internal price id
	 * @return a content-stable price record for the key
	 */
	@Nonnull
	private static PriceRecordContract rec(int key) {
		return new PriceRecord(key, key + 1, key / 3, key * 100 + 21, key * 100);
	}

	/**
	 * Creates an empty element tree with the smallest legal leaf block size (3), so a handful of inserts split the root
	 * and a handful of deletes drive underflow rebalances / merges — the shrink seams under test.
	 *
	 * @return a fresh empty tree with block size 3
	 */
	@Nonnull
	private static TransactionalElementBPlusTree<PriceRecordContract> smallTree() {
		return new TransactionalElementBPlusTree<>(3, 1, 3, 1, PriceRecordContract.class, KEY);
	}

	@Test
	@DisplayName("dirty-scope tokens are boundary probe keys, never leaf nodes")
	void shouldRegisterProbeKeysNeverNodeObjects() {
		// churn that dirties several leaves through inserts (with splits) and a delete; the registry that feeds the
		// pre-commit / post-replay validation must hold only boundary keys — retaining a node object is exactly what
		// pinned a leaf whose array a sibling later blanked, and it is the failure class this test forbids outright
		final TransactionalElementBPlusTree<PriceRecordContract> tree = smallTree();
		assertStateAfterCommit(
			tree,
			t -> {
				for (int i = 1; i <= 8; i++) {
					t.insert(rec(i));
				}
				t.delete(2);
				final Set<Object> tokens = Transaction.getTransactionalLayerMaintainer().getDirtyScopeTokens(t);
				assertFalse(tokens.isEmpty(), "the churn must have dirtied at least one leaf");
				for (final Object token : tokens) {
					assertFalse(
						token instanceof BPlusTreeNode,
						"a dirty-scope token must never be a B+ tree node — a node token pins the node, its array and " +
							"its elements until commit and reintroduces the stale-observer bug class; got " +
							token.getClass().getName()
					);
					assertTrue(
						token instanceof Integer,
						"a dirty-scope token must be an Integer boundary probe key; got " + token.getClass().getName()
					);
				}
			},
			(t, committed) -> assertNotNull(committed)
		);
	}

	@Test
	@DisplayName("deleting a root leaf down to empty commits without a spurious corruption error")
	void shouldNotRejectWhenRootLeafDeletedToEmpty() {
		// a single-leaf (root) tree deleted to empty: delete() blanks slot 0 in place, and the emptied leaf carries no
		// boundary key so it is never registered — the commit must not raise a false corruption error. This is the
		// delete-to-empty-root hole the copy-on-write shrink in setPeek could not close (delete never routes through
		// setPeek), now closed by the key-based registry.
		final TransactionalElementBPlusTree<PriceRecordContract> tree = smallTree();
		tree.insert(rec(1));
		assertStateAfterCommit(
			tree,
			t -> {
				t.delete(1);
				assertEquals(0, t.size(), "the tree must be empty after deleting its only element");
			},
			(t, committed) -> assertNotNull(committed)
		);
	}

	@Test
	@DisplayName("split-then-merge churn commits cleanly with a consistent tree")
	void shouldCommitSplitThenMergeChain() {
		// the original defect's op mix: inserts split the root (a right leaf adopts the origin array in place), then
		// deletes underflow leaves into steal / merge, emptying donors via setPeek(-1) — the in-place blank that used to
		// reach through a registered node token. With key-based registration it is harmless; the commit's post-replay
		// validation must pass and the surviving tree must be structurally consistent.
		final TransactionalElementBPlusTree<PriceRecordContract> tree = smallTree();
		assertStateAfterCommit(
			tree,
			t -> {
				for (int i = 1; i <= 12; i++) {
					t.insert(rec(i));
				}
				for (int i = 2; i <= 11; i++) {
					t.delete(i);
				}
				assertEquals(2, t.size(), "only the two boundary elements must remain");
				assertEquals(
					ConsistencyState.CONSISTENT, t.getConsistencyReport().state(),
					"the churned tree must be structurally consistent before commit"
				);
			},
			(t, committed) -> assertNotNull(committed)
		);
	}
}
