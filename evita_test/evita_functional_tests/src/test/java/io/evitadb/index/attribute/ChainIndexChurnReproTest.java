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

import io.evitadb.dataType.Predecessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolated workload-validation harness for the {@link ChainIndex} used behind a `Predecessor`-ordered attribute.
 *
 * `ChainIndex` models a single-linked chain of elements ordered by their predecessor pointers; it is built to keep
 * an "unordered" ordering in a form where **moving an individual element perturbs only a constant number of
 * neighbours** (the moved element plus its old and new successors), never renumbering the whole tail. It is
 * therefore the wrong structure for purely-random insert/delete churn - random permanent deletes shatter the chain
 * into unbounded split subchains and stress the unrelated chain-collapse bookkeeping rather than the move path.
 *
 * This harness drives the index directly (no engine) with the **realistic** workload: phase 1 builds a single
 * chain `1..N`, phase 2 performs coherent local **moves** over a maintained doubly-linked order, keeping the chain
 * consistent. It asserts the live subchain count ({@link ChainIndex#chains}) stays bounded (units/tens), and
 * reports per-block timing so the move path can be observed to scale (no `O(ops*chains)` cliff).
 *
 * Tagged {@link TestTags#SLOW} - run explicitly, not part of the fast functional loop.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(TestTags.INDEXING)
@Tag(TestTags.ATTRIBUTE)
@Tag(TestTags.SLOW)
class ChainIndexChurnReproTest {

	private static final int INITIAL_RECORD_COUNT = 1_000_000;
	private static final int CHURN_OPERATIONS = 200_000;
	private static final int BLOCK = 10_000;
	/** the chain may fragment transiently while a move is applied, but must stay bounded - never thousands */
	private static final int MAX_REASONABLE_CHAINS = 100;

	@DisplayName("Coherent local moves keep the chain bounded and scale with no collapse cliff")
	@Test
	void shouldChurnViaCoherentMoves() {
		final ChainIndex index = new ChainIndex(new AttributeIndexKey(null, "order", null));

		// phase 1 - build a single chain 1..N (record i chained right after record i-1)
		final long buildStart = System.nanoTime();
		for (int i = 0; i < INITIAL_RECORD_COUNT; i++) {
			final int primaryKey = i + 1;
			index.upsertPredecessor(primaryKey == 1 ? Predecessor.HEAD : new Predecessor(primaryKey - 1), primaryKey);
		}
		System.out.printf(
			"Phase 1: built %d-element chain in %d ms; chain count = %d%n",
			INITIAL_RECORD_COUNT, (System.nanoTime() - buildStart) / 1_000_000L, index.chains.size()
		);

		// maintained doubly-linked order of the (all-live) records: pred[pk]/succ[pk], 0 == HEAD / none
		final int[] pred = new int[INITIAL_RECORD_COUNT + 1];
		final int[] succ = new int[INITIAL_RECORD_COUNT + 1];
		for (int pk = 1; pk <= INITIAL_RECORD_COUNT; pk++) {
			pred[pk] = pk - 1;
			succ[pk] = pk == INITIAL_RECORD_COUNT ? 0 : pk + 1;
		}

		// phase 2 - coherent local moves: relocate a random element after a random other element (or to HEAD),
		// updating exactly the three affected predecessors (moved element, old successor, new successor)
		final Random random = new Random(42);
		long blockStart = System.nanoTime();
		int maxChainsObserved = index.chains.size();
		int head = 1;
		for (int op = 0; op < CHURN_OPERATIONS; op++) {
			final int x = 1 + random.nextInt(INITIAL_RECORD_COUNT);
			// 10 % of moves promote the element to the chain head, otherwise relocate after a random anchor
			int anchor = random.nextInt(10) == 0 ? 0 : 1 + random.nextInt(INITIAL_RECORD_COUNT);
			if (anchor == x) {
				anchor = pred[x]; // avoid self-anchor; collapses to a no-op which we skip below
			}
			if (anchor == pred[x]) {
				continue; // element already sits right after the anchor - nothing to do
			}

			final int pOld = pred[x];
			final int sOld = succ[x];
			// detach x from its current position
			if (pOld == 0) {
				head = sOld; // x was the head; its successor becomes the new head
			} else {
				succ[pOld] = sOld;
			}
			if (sOld != 0) {
				pred[sOld] = pOld;
			}
			// insert x right after the anchor (anchor == 0 means promote to head)
			final int sNew = anchor == 0 ? head : succ[anchor];
			if (anchor == 0) {
				head = x;
			} else {
				succ[anchor] = x;
			}
			pred[x] = anchor;
			succ[x] = sNew;
			if (sNew != 0) {
				pred[sNew] = x;
			}

			// apply the move to the index as the three affected predecessor updates, in the natural "detach-first"
			// order: first reconnect x's old successor to x's old predecessor (so x stops dragging a suffix), then
			// relocate x, then attach x's new successor. This keeps every single mutation a true local move.
			if (sOld != 0 && sOld != x) {
				index.upsertPredecessor(pOld == 0 ? Predecessor.HEAD : new Predecessor(pOld), sOld);
			}
			index.upsertPredecessor(anchor == 0 ? Predecessor.HEAD : new Predecessor(anchor), x);
			if (sNew != 0 && sNew != x) {
				index.upsertPredecessor(new Predecessor(x), sNew);
			}

			maxChainsObserved = Math.max(maxChainsObserved, index.chains.size());
			if ((op + 1) % BLOCK == 0) {
				final long blockMs = (System.nanoTime() - blockStart) / 1_000_000L;
				System.out.printf(
					"Moves %d..%d: %d ms (%.3f ms/op); chains now = %d (max seen = %d)%n",
					op + 1 - BLOCK, op, blockMs, blockMs / (double) BLOCK, index.chains.size(), maxChainsObserved
				);
				blockStart = System.nanoTime();
			}
		}

		assertTrue(index.isConsistent(), "Index must stay consistent after coherent moves.");
		assertTrue(
			maxChainsObserved <= MAX_REASONABLE_CHAINS,
			"Coherent moves must keep the chain bounded, but observed up to " + maxChainsObserved + " subchains."
		);
	}
}
