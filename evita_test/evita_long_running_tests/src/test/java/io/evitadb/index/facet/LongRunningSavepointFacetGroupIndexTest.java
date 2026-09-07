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

package io.evitadb.index.facet;

import io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized backfill proof that {@link FacetGroupIndex} — together with its nested {@link FacetIdIndex}
 * children — snapshots and restores correctly under a per-entity savepoint (Ref: #1252). Because the index is a
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional changes are `Snapshotable`,
 * the proof drives the index directly and asserts its logical facet contents (read via
 * {@link LongRunningFacetGroupIndexTest#snapshot(FacetGroupIndex)}).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of facet mutations (standing for *prior* entities in the same transaction — these
 * must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a
 * guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on
 * commit), and asserts the facet contents against the oracle captured at savepoint open. The transaction then commits
 * so the commit-time layer-sweep verification proves the restore left no dangling layer. The run is time-bounded; the
 * random seed is echoed on failure for deterministic reproduction.
 *
 * The scenario is declared once and run by {@link AbstractSavepointFuzzTest} in BOTH phases: the transactional
 * savepoint described above, and the WARM_UP savepoint where the same writes land straight on the delegate
 * structures and are rewound from the inverses they journal themselves. See that class for the shape of one
 * generation, for the mid-savepoint read every case is asserted through, and for why the warm-up half runs
 * exclusively.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("FacetGroupIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(FACET)
@Tag(TRANSACTION)
class LongRunningSavepointFacetGroupIndexTest extends AbstractSavepointFuzzTest<Map<Integer, List<Integer>>> {
	private static final int MAX_OPS = 10;

	@Nonnull
	@Override
	protected FuzzGeneration<Map<Integer, List<Integer>>> newGeneration(@Nonnull Random random) {
		return new FacetGroupState(random);
	}

	/**
	 * A {@link FacetGroupIndex} paired with an in-test model of its facet contents (facetId → entity ids) so randomized
	 * mutations can be generated that keep the model and index in lockstep. The initial non-empty index is seeded
	 * outside any transaction; mutations are applied to the index (and mirrored in the model) within the framework's
	 * transaction.
	 */
	private static final class FacetGroupState implements FuzzGeneration<Map<Integer, List<Integer>>> {
		private static final int MAX_FACET_ID = 10;
		private static final int MAX_ENTITY_ID = 50;

		private final FacetGroupIndex index = new FacetGroupIndex();
		private final Map<Integer, Set<Integer>> facets = new HashMap<>();
		// reserved entity-id sequence for guaranteed-new forced mutations, kept clear of the 1..MAX_ENTITY_ID random range
		private int forcedEntitySeq = 1000;

		FacetGroupState(@Nonnull Random random) {
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandomFacet(random);
			}
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.index;
		}

		@Nonnull
		@Override
		public Map<Integer, List<Integer>> contents() {
			return LongRunningFacetGroupIndexTest.snapshot(this.index);
		}

		@Override
		public void applyBaselineOperations(@Nonnull Random random) {
			applyRandomMutations(random, 1 + random.nextInt(MAX_OPS));
		}

		@Override
		public void applySavepointOperations(@Nonnull Random random) {
			applyRandomMutations(random, random.nextInt(MAX_OPS));
			// applied LAST: a marker applied first enters the model and a later random operation can undo it
			forceMutation();
		}

		/**
		 * Applies `count` random facet add/remove mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (this.facets.isEmpty() || random.nextBoolean()) {
					addRandomFacet(random);
				} else {
					removeRandomFacet(random);
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: adds a facet for a brand-new entity id drawn from a reserved sequence
		 * that random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int entityId = ++this.forcedEntitySeq;
			this.index.addFacet(1, entityId);
			this.facets.computeIfAbsent(1, k -> new HashSet<>()).add(entityId);
		}

		/**
		 * Adds a random not-yet-present (facet, entity) pair; bounded retries avoid an infinite spin when a random pick
		 * collides, and give up silently as a harmless no-op.
		 */
		private void addRandomFacet(@Nonnull Random random) {
			for (int attempt = 0; attempt < 10; attempt++) {
				final int facetId = random.nextInt(MAX_FACET_ID) + 1;
				final int entityId = random.nextInt(MAX_ENTITY_ID) + 1;
				final Set<Integer> entities = this.facets.computeIfAbsent(facetId, k -> new HashSet<>());
				if (entities.add(entityId)) {
					this.index.addFacet(facetId, entityId);
					return;
				}
			}
		}

		/**
		 * Removes a random present (facet, entity) pair, mirrored into the model; a no-op when the model is empty.
		 */
		private void removeRandomFacet(@Nonnull Random random) {
			if (this.facets.isEmpty()) {
				return;
			}
			final List<Integer> facetIds = new ArrayList<>(this.facets.keySet());
			final int facetId = facetIds.get(random.nextInt(facetIds.size()));
			final Set<Integer> entities = this.facets.get(facetId);
			final List<Integer> entityIds = new ArrayList<>(entities);
			final int entityId = entityIds.get(random.nextInt(entityIds.size()));
			this.index.removeFacet(facetId, entityId);
			entities.remove(entityId);
			if (entities.isEmpty()) {
				this.facets.remove(facetId);
			}
		}
	}

}
