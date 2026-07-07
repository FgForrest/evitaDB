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

package io.evitadb.index.hierarchy;

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;

/**
 * Generational randomized backfill proof that {@link HierarchyIndex} snapshots and restores correctly under a
 * per-entity savepoint (Ref: #1252). Because the index is a
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional structures (roots,
 * level-index, item-index and orphans) are `Snapshotable`, the proof drives the {@link HierarchyIndex} directly and
 * asserts its logical tree contents via the value-comparable oracle {@link LongRunningHierarchyIndexTest#snapshot}.
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction applies
 * a random baseline batch of add/move/remove mutations (standing for *prior* entities in the same transaction — these
 * must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a
 * guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on
 * commit), and asserts the tree contents against the oracle captured at savepoint open. The transaction then commits so
 * the commit-time layer-sweep verification proves the restore left no dangling layer. The run is time-bounded; the
 * random seed is echoed on failure for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("HierarchyIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(HIERARCHY)
@Tag(TRANSACTION)
class LongRunningSavepointHierarchyIndexTest implements TimeBoundedTestSupport {
	private static final int MAX_OPS = 10;

	@ParameterizedTest(name = "Savepoint rollback restores the exact pre-savepoint hierarchy contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint hierarchy contents")
	void shouldRollBackHierarchyIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final HierarchyState state = new HierarchyState(random);
			assertSavepointRollbackRestores(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningHierarchyIndexTest::snapshot,
				tested -> {
					// a guaranteed-visible mutation makes the in-savepoint batch non-vacuous
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "Savepoint commit keeps the in-savepoint hierarchy contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint commit keeps the in-savepoint hierarchy contents")
	void shouldCommitHierarchyIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final HierarchyState state = new HierarchyState(random);
			assertSavepointCommitKeeps(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningHierarchyIndexTest::snapshot,
				tested -> {
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	/**
	 * A {@link HierarchyIndex} paired with an in-test model of the set of node ids currently present, so randomized
	 * add/move/remove mutations can be generated that keep the model and index in lockstep. The oracle read is taken from
	 * the index itself (via {@link LongRunningHierarchyIndexTest#snapshot}), so the model only needs to track which node
	 * ids exist to pick valid move/remove targets and valid parents. The initial non-empty index is seeded outside any
	 * transaction; subsequent mutations are applied within the framework's transaction.
	 */
	private static final class HierarchyState {
		private static final int MAX_NODE_ID = 50;

		private final HierarchyIndex index = new HierarchyIndex();
		private final Set<Integer> nodes = new HashSet<>();
		// reserved node-id sequence for guaranteed-new forced mutations, kept clear of the 1..MAX_NODE_ID random range
		private int forcedNodeSeq = 1000;

		HierarchyState(@Nonnull Random random) {
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandomNode(random);
			}
		}

		/**
		 * Applies `count` random add/move/remove hierarchy mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (this.nodes.isEmpty() || random.nextInt(3) == 0) {
					addRandomNode(random);
				} else if (random.nextBoolean()) {
					moveRandomNode(random);
				} else {
					removeRandomNode(random);
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: adds a root node for a brand-new node id drawn from a reserved sequence
		 * that random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int nodeId = ++this.forcedNodeSeq;
			this.index.addNode(nodeId, null);
			this.nodes.add(nodeId);
		}

		/**
		 * Adds a node with a not-yet-present id under a random parent (root or an existing node); bounded retries avoid an
		 * infinite spin when a random pick collides, and give up silently as a harmless no-op.
		 */
		private void addRandomNode(@Nonnull Random random) {
			for (int attempt = 0; attempt < 10; attempt++) {
				final int nodeId = random.nextInt(MAX_NODE_ID) + 1;
				if (this.nodes.contains(nodeId)) {
					continue;
				}
				this.index.addNode(nodeId, pickParent(random, nodeId));
				this.nodes.add(nodeId);
				return;
			}
		}

		/**
		 * Re-parents an existing node under a random parent — an `addNode` on an already-present id, which the index
		 * treats as a move (the node keeps its id but changes placement).
		 */
		private void moveRandomNode(@Nonnull Random random) {
			final int nodeId = pickExisting(random);
			this.index.addNode(nodeId, pickParent(random, nodeId));
		}

		/**
		 * Removes a random present node, mirrored into the model. Its children become orphans but remain present, so only
		 * the removed id leaves the model.
		 */
		private void removeRandomNode(@Nonnull Random random) {
			final int nodeId = pickExisting(random);
			this.index.removeNode(nodeId);
			this.nodes.remove(nodeId);
		}

		/**
		 * Picks a random parent for `selfId`: either the root (`null`) or an existing node that is not `selfId` itself.
		 */
		@Nullable
		private Integer pickParent(@Nonnull Random random, int selfId) {
			if (this.nodes.isEmpty() || random.nextBoolean()) {
				return null;
			}
			for (int attempt = 0; attempt < 10; attempt++) {
				final int parentId = pickExisting(random);
				if (parentId != selfId) {
					return parentId;
				}
			}
			return null;
		}

		/**
		 * Returns a random node id currently present in the model.
		 */
		private int pickExisting(@Nonnull Random random) {
			final List<Integer> ids = new ArrayList<>(this.nodes);
			return ids.get(random.nextInt(ids.size()));
		}
	}

}
