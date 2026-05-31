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

import io.evitadb.dataType.Predecessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization test that pins the **current** transient ordering produced by {@link ChainIndex} while the index is
 * in a committed inconsistent (`isConsistent() == false`) state.
 *
 * Motivation: the #760 redesign plans to change a single `upsertPredecessor(x, P_new)` from "drag x's whole suffix" to
 * "move only x and heal the gap". That change alters ONLY the shape of the transient inconsistent window; the
 * eventually-consistent single-chain order is byte-identical. This test records the current (drag) behaviour so that
 * any future change to the transient-window semantics is detected and consciously rebaselined here, rather than
 * silently regressing some other test.
 *
 * The order captured here is implementation-defined per the contract docs ({@link io.evitadb.dataType.ChainableType}
 * "semi-consistent" wording and {@link ChainIndex} class JavaDoc), and is NOT a client-visible API contract. Queries
 * over a `Predecessor`-ordered attribute always observe the eventually-consistent single chain in the engine tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class ChainIndexInconsistentWindowCharacterizationTest {

	/**
	 * Builds a consistent chain `[1, 2, 3, 4, 5]`, then re-points element `3` at an absent predecessor (`999`), which
	 * is the classic "forward reference / dangling predecessor" situation: the referenced predecessor is not (yet)
	 * present in the index. This is exactly the committed-inconsistent window that a query may observe before the
	 * missing predecessor arrives.
	 *
	 * Captured CURRENT (drag) behaviour: re-pointing `3` drags its whole suffix `[3, 4, 5]` into a new orphan chain,
	 * leaving the head chain as `[1, 2]`. The combined unordered lookup is therefore `[1, 2, 3, 4, 5]` — head chain
	 * first, then the longer orphan chain.
	 */
	@DisplayName("Capture current drag ordering when an element is re-pointed at an absent (forward) predecessor")
	@Test
	void shouldCaptureDragOrderingForDanglingForwardPredecessor() {
		final ChainIndex index = new ChainIndex(new AttributeIndexKey(null, "order", null));
		// build a fully consistent chain 1 -> 2 -> 3 -> 4 -> 5
		index.upsertPredecessor(new Predecessor(), 1);
		index.upsertPredecessor(new Predecessor(1), 2);
		index.upsertPredecessor(new Predecessor(2), 3);
		index.upsertPredecessor(new Predecessor(3), 4);
		index.upsertPredecessor(new Predecessor(4), 5);

		assertTrue(index.isConsistent(), "Pre-condition: the freshly built chain must be consistent.");
		assertArrayEquals(new int[] {1, 2, 3, 4, 5}, index.getUnorderedLookup().getArray());

		// now re-point element 3 at a predecessor (999) that is NOT present in the index (dangling / forward ref)
		index.upsertPredecessor(new Predecessor(999), 3);

		// the index is now in a committed INCONSISTENT window (more than one chain)
		assertFalse(
			index.isConsistent(),
			"Re-pointing an element at an absent predecessor must leave the index inconsistent."
		);

		// CURRENT (drag) behaviour anchor: the whole suffix [3, 4, 5] is dragged into the orphan chain, head stays [1, 2]
		assertArrayEquals(
			new int[] {1, 2, 3, 4, 5},
			index.getUnorderedLookup().getArray(),
			"Current drag semantics: head chain [1,2] then orphan suffix chain [3,4,5]."
		);
		// document the internal fragmentation that the redesign will reshape (head chain + orphan suffix chain);
		// `chains` is package-private and readable from this same-package test
		assertEquals(2, index.chains.size());
	}

	/**
	 * Forces a true reordering inside the chain that leaves a multi-chain inconsistent window, capturing the current
	 * (drag) arrangement of the fragments. Mirrors the in-memory inconsistent-state assertions already present in
	 * {@code ChainIndexTest} (e.g. `shouldIntroduceReconnectSplitChainsFavouringLongerOne`) but isolated here as the
	 * explicit regression anchor for the transient-window redesign.
	 *
	 * Captured CURRENT behaviour: inserting `6` and `7,8` as new sub-chains hanging off `3` (which already has
	 * successor `4`) produces three chains `[1,2,3,4,5]`, `[6]`, `[7,8]`, combined as `[1,2,3,4,5,7,8,6]`
	 * (head chain, then the longer orphan chain `[7,8]`, then the shorter orphan `[6]`).
	 */
	@DisplayName("Capture current drag ordering when split sub-chains hang off a mid-chain element")
	@Test
	void shouldCaptureDragOrderingForSplitSubChains() {
		final ChainIndex index = new ChainIndex(new AttributeIndexKey(null, "order", null));
		// build a fully consistent chain 1 -> 2 -> 3 -> 4 -> 5
		index.upsertPredecessor(new Predecessor(), 1);
		index.upsertPredecessor(new Predecessor(1), 2);
		index.upsertPredecessor(new Predecessor(2), 3);
		index.upsertPredecessor(new Predecessor(3), 4);
		index.upsertPredecessor(new Predecessor(4), 5);

		// hang two new sub-chains off element 3 (which already owns successor 4) -> split / inconsistent window
		index.upsertPredecessor(new Predecessor(3), 6);
		index.upsertPredecessor(new Predecessor(3), 7);
		index.upsertPredecessor(new Predecessor(7), 8);

		assertFalse(index.isConsistent(), "The split must leave the index inconsistent.");
		assertArrayEquals(
			new int[] {1, 2, 3, 4, 5, 7, 8, 6},
			index.getUnorderedLookup().getArray(),
			"Current drag semantics: head chain, then longer orphan [7,8], then shorter orphan [6]."
		);
		assertEquals(3, index.chains.size());
	}

}
