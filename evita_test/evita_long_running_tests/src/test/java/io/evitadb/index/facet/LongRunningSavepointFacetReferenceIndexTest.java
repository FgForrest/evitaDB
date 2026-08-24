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
import io.evitadb.index.facet.LongRunningFacetReferenceIndexTest.FacetSnapshot;
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
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized backfill proof that {@code FacetReferenceIndex.FacetEntityTypeIndexChanges} — the diff layer
 * of {@link FacetReferenceIndex} — snapshots and restores correctly under a per-entity savepoint (Ref: #1252). Because
 * the index itself is a {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional
 * changes are `Snapshotable`, the proof drives the parent {@link FacetReferenceIndex} directly and asserts its logical
 * facet contents (no-group and grouped, read via {@link FacetReferenceIndex#getGroupsAsMap()} /
 * {@link FacetReferenceIndex#getNotGroupedFacetsAsMap()}).
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
@DisplayName("FacetReferenceIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(FACET)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class LongRunningSavepointFacetReferenceIndexTest extends AbstractSavepointFuzzTest<FacetSnapshot> {
	private static final int MAX_OPS = 10;

	@Nonnull
	@Override
	protected FuzzGeneration<FacetSnapshot> newGeneration(@Nonnull Random random) {
		return new FacetState(random);
	}

	/**
	 * A {@link FacetReferenceIndex} paired with an in-test model of its facet contents (no-group: facetId → entity ids;
	 * grouped: groupId → facetId → entity ids) so randomized mutations can be generated that keep the model and index in
	 * lockstep. The initial non-empty index is seeded outside any transaction; mutations are applied to the index (and
	 * mirrored in the model) within the framework's transaction.
	 */
	private static final class FacetState implements FuzzGeneration<FacetSnapshot> {
		private static final int MAX_FACET_ID = 5;
		private static final int MAX_GROUP_ID = 3;
		private static final int MAX_ENTITY_ID = 30;

		private final FacetReferenceIndex index = new FacetReferenceIndex("ref");
		private final Map<Integer, Set<Integer>> noGroup = new HashMap<>();
		private final Map<Integer, Map<Integer, Set<Integer>>> grouped = new HashMap<>();
		// reserved entity-id sequence for guaranteed-new forced mutations, kept clear of the 1..MAX_ENTITY_ID random range
		private int forcedEntitySeq = 1000;

		FacetState(@Nonnull Random random) {
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
		public FacetSnapshot contents() {
			return LongRunningFacetReferenceIndexTest.snapshot(this.index);
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
				if (isEmpty() || random.nextBoolean()) {
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
			this.index.addFacet(1, null, entityId);
			this.noGroup.computeIfAbsent(1, k -> new HashSet<>()).add(entityId);
		}

		private boolean isEmpty() {
			return this.noGroup.isEmpty() && this.grouped.isEmpty();
		}

		/**
		 * Adds a random facet (null-group or grouped) for a not-yet-present (facet, entity) pair; bounded retries avoid
		 * an infinite spin when a random pick collides, and give up silently as a harmless no-op.
		 */
		private void addRandomFacet(@Nonnull Random random) {
			for (int attempt = 0; attempt < 10; attempt++) {
				final int facetId = random.nextInt(MAX_FACET_ID) + 1;
				final int entityId = random.nextInt(MAX_ENTITY_ID) + 1;
				if (random.nextBoolean()) {
					final Set<Integer> entities = this.noGroup.computeIfAbsent(facetId, k -> new HashSet<>());
					if (entities.add(entityId)) {
						this.index.addFacet(facetId, null, entityId);
						return;
					}
				} else {
					final int groupId = random.nextInt(MAX_GROUP_ID) + 1;
					final Set<Integer> entities = this.grouped
						.computeIfAbsent(groupId, k -> new HashMap<>())
						.computeIfAbsent(facetId, k -> new HashSet<>());
					if (entities.add(entityId)) {
						this.index.addFacet(facetId, groupId, entityId);
						return;
					}
				}
			}
		}

		/**
		 * Removes a random present (facet, entity) pair, mirrored into the model; a no-op when the model is empty.
		 */
		private void removeRandomFacet(@Nonnull Random random) {
			if (!this.noGroup.isEmpty() && (this.grouped.isEmpty() || random.nextBoolean())) {
				final List<Integer> facetIds = new ArrayList<>(this.noGroup.keySet());
				final int facetId = facetIds.get(random.nextInt(facetIds.size()));
				final Set<Integer> entities = this.noGroup.get(facetId);
				final List<Integer> entityIds = new ArrayList<>(entities);
				final int entityId = entityIds.get(random.nextInt(entityIds.size()));
				this.index.removeFacet(facetId, null, entityId);
				entities.remove(entityId);
				if (entities.isEmpty()) {
					this.noGroup.remove(facetId);
				}
			} else if (!this.grouped.isEmpty()) {
				final List<Integer> groupIds = new ArrayList<>(this.grouped.keySet());
				final int groupId = groupIds.get(random.nextInt(groupIds.size()));
				final Map<Integer, Set<Integer>> facetMap = this.grouped.get(groupId);
				final List<Integer> facetIds = new ArrayList<>(facetMap.keySet());
				final int facetId = facetIds.get(random.nextInt(facetIds.size()));
				final Set<Integer> entities = facetMap.get(facetId);
				final List<Integer> entityIds = new ArrayList<>(entities);
				final int entityId = entityIds.get(random.nextInt(entityIds.size()));
				this.index.removeFacet(facetId, groupId, entityId);
				entities.remove(entityId);
				if (entities.isEmpty()) {
					facetMap.remove(facetId);
					if (facetMap.isEmpty()) {
						this.grouped.remove(groupId);
					}
				}
			}
		}
	}

}
