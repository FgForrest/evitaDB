/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized backfill proof that {@code ChainIndexChanges} — the derived-cache diff layer of
 * {@link ChainIndex} — snapshots and restores correctly under a per-entity savepoint. The memento is a
 * cheap cache-invalidation (the chain's ordering cache is lazily rebuilt from the parent index), so the proof drives the
 * parent {@link ChainIndex} directly and asserts its logical ordering, exercising both the cache layer and the chain's
 * own inner transactional structures.
 *
 * Each generation builds a fresh consistent single chain `1..N`, then within one real transaction applies a random
 * baseline batch of **coherent local moves** (relocate one element after another — the realistic chain workload that
 * keeps the chain consistent, mirroring {@code LongRunningChainIndexTest#shouldChurnViaCoherentMoves}) that must survive
 * the savepoint rollback, then a random in-savepoint batch (with a guaranteed-reordering marker move) that must revert
 * on rollback / be kept on commit. The framework asserts the chain's order (read via
 * {@link ChainIndex#getUnorderedLookup()}) against the oracle captured at savepoint open, then commits so the layer-sweep
 * verification proves the restore left no dangling layer. The run is time-bounded; the random seed is echoed on failure
 * for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ChainIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
class LongRunningSavepointChainIndexTest implements TimeBoundedTestSupport {
	private static final int CHAIN_SIZE = 24;
	private static final int MAX_OPS = 10;

	@ParameterizedTest(name = "Savepoint rollback restores the exact pre-savepoint chain order")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint chain order")
	void shouldRollBackChainIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final ChainState state = new ChainState(CHAIN_SIZE);
			assertSavepointRollbackRestores(
				state.index,
				tested -> state.applyRandomMoves(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningSavepointChainIndexTest::chainContents,
				tested -> {
					// a guaranteed reordering move makes the in-savepoint batch non-vacuous
					state.forceReorder();
					state.applyRandomMoves(random, 1 + random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "Savepoint commit keeps the in-savepoint chain order")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint commit keeps the in-savepoint chain order")
	void shouldCommitChainIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final ChainState state = new ChainState(CHAIN_SIZE);
			assertSavepointCommitKeeps(
				state.index,
				tested -> state.applyRandomMoves(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningSavepointChainIndexTest::chainContents,
				tested -> state.applyRandomMoves(random, 1 + random.nextInt(MAX_OPS))
			);
			return iteration + 1;
		});
	}

	@Test
	@DisplayName("Savepoint rollback/commit is exact over a large PAGED chain (per-leaf page state rides the tree memento)")
	void shouldRollBackAndCommitPagedChainIndex() {
		// > leaf capacity (1024) so the element tree is multi-leaf (PAGED): opening a savepoint must snapshot, and a
		// rollback restore, each leaf node's per-leaf pageSequence / dirty (they ride the tree's own memento) alongside
		// the chain order - the small (SINGLE) fuzz above never exercises the multi-leaf memento path
		final int pagedSize = 1500;

		final Random rollbackRandom = new Random(0xC0FFEEL);
		final ChainState rollbackState = new ChainState(pagedSize);
		assertTrue(rollbackState.index.elements.isRootInternal(), "the chain must be PAGED (multi-leaf) before the savepoint");
		assertSavepointRollbackRestores(
			rollbackState.index,
			tested -> rollbackState.applyRandomMoves(rollbackRandom, 1 + rollbackRandom.nextInt(MAX_OPS)),
			LongRunningSavepointChainIndexTest::chainContents,
			tested -> {
				rollbackState.forceReorder();
				rollbackState.applyRandomMoves(rollbackRandom, 1 + rollbackRandom.nextInt(MAX_OPS));
			}
		);

		final Random commitRandom = new Random(0xBEEFL);
		final ChainState commitState = new ChainState(pagedSize);
		assertTrue(commitState.index.elements.isRootInternal(), "the chain must be PAGED (multi-leaf) before the savepoint");
		assertSavepointCommitKeeps(
			commitState.index,
			tested -> commitState.applyRandomMoves(commitRandom, 1 + commitRandom.nextInt(MAX_OPS)),
			LongRunningSavepointChainIndexTest::chainContents,
			tested -> {
				commitState.forceReorder();
				commitState.applyRandomMoves(commitRandom, 1 + commitRandom.nextInt(MAX_OPS));
			}
		);
	}

	/**
	 * Reads the chain's logical order into an `.equals`-comparable list.
	 */
	@Nonnull
	private static List<Integer> chainContents(@Nonnull ChainIndex index) {
		final int[] array = index.getUnorderedLookup().getArray();
		final List<Integer> contents = new ArrayList<>(array.length);
		for (final int value : array) {
			contents.add(value);
		}
		return contents;
	}

	/**
	 * A consistent {@link ChainIndex} over the fixed primary-key set `1..N`, paired with an in-test doubly-linked order
	 * model ({@code pred}/{@code succ}/{@code head}, `0` = HEAD / none) so randomized **coherent local moves** can be
	 * generated that keep the chain consistent. The initial single chain `1..N` is built outside any transaction; moves
	 * are applied to the index (and mirrored in the model) within the framework's transaction.
	 */
	private static final class ChainState {
		private final int size;
		private final ChainIndex index = new ChainIndex(new AttributeIndexKey(null, "a", null));
		private final int[] pred;
		private final int[] succ;
		private int head;

		ChainState(int size) {
			this.size = size;
			this.pred = new int[size + 1];
			this.succ = new int[size + 1];
			this.head = 1;
			for (int pk = 1; pk <= size; pk++) {
				this.pred[pk] = pk - 1;
				this.succ[pk] = pk == size ? 0 : pk + 1;
				this.index.upsertPredecessor(pk == 1 ? Predecessor.HEAD : new Predecessor(pk - 1), pk);
			}
		}

		/**
		 * Applies `count` random coherent moves (relocate a random element after a random anchor, or to HEAD).
		 */
		void applyRandomMoves(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				final int x = 1 + random.nextInt(this.size);
				int anchor = random.nextInt(10) == 0 ? 0 : 1 + random.nextInt(this.size);
				if (anchor == x) {
					anchor = this.pred[x];
				}
				if (anchor == this.pred[x]) {
					continue; // already sits right after the anchor — nothing to do
				}
				move(x, anchor);
			}
		}

		/**
		 * Performs one guaranteed-reordering move: relocate the tail element to the chain head.
		 */
		void forceReorder() {
			int tail = this.head;
			while (tail != 0 && this.succ[tail] != 0) {
				tail = this.succ[tail];
			}
			if (tail != 0 && this.pred[tail] != 0) {
				move(tail, 0);
			}
		}

		/**
		 * Relocates element `x` to sit right after `anchor` (`anchor == 0` promotes it to HEAD), updating the model and
		 * applying the three affected predecessor updates to the index in detach-first order — each a true local move.
		 */
		private void move(int x, int anchor) {
			final int pOld = this.pred[x];
			final int sOld = this.succ[x];
			// detach x from its current position
			if (pOld == 0) {
				this.head = sOld;
			} else {
				this.succ[pOld] = sOld;
			}
			if (sOld != 0) {
				this.pred[sOld] = pOld;
			}
			// insert x right after the anchor
			final int sNew = anchor == 0 ? this.head : this.succ[anchor];
			if (anchor == 0) {
				this.head = x;
			} else {
				this.succ[anchor] = x;
			}
			this.pred[x] = anchor;
			this.succ[x] = sNew;
			if (sNew != 0) {
				this.pred[sNew] = x;
			}
			// apply to the index as the three affected predecessor updates
			if (sOld != 0 && sOld != x) {
				this.index.upsertPredecessor(pOld == 0 ? Predecessor.HEAD : new Predecessor(pOld), sOld);
			}
			this.index.upsertPredecessor(anchor == 0 ? Predecessor.HEAD : new Predecessor(anchor), x);
			if (sNew != 0 && sNew != x) {
				this.index.upsertPredecessor(new Predecessor(x), sNew);
			}
		}
	}

}
