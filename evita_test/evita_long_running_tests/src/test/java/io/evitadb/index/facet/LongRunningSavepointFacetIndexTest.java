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

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
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

import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;

/**
 * Generational randomized backfill proof that {@link FacetIndex} — together with its nested {@link FacetReferenceIndex}
 * / {@link FacetGroupIndex} / {@link FacetIdIndex} children — snapshots and restores correctly under a per-entity
 * savepoint (Ref: #1252). Because the index is a {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}
 * whose transactional changes are `Snapshotable`, the proof drives the index directly and asserts its logical facet
 * contents per reference name (read via {@link LongRunningFacetIndexTest#snapshot(FacetIndex)}).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of facet mutations (standing for *prior* entities in the same transaction — these
 * must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a
 * guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on
 * commit), and asserts the facet contents against the oracle captured at savepoint open. The transaction then commits
 * so the commit-time layer-sweep verification proves the restore left no dangling layer. The run is time-bounded; the
 * random seed is echoed on failure for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("FacetIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(FACET)
@Tag(TRANSACTION)
class LongRunningSavepointFacetIndexTest implements TimeBoundedTestSupport {
	private static final int MAX_OPS = 10;

	@ParameterizedTest(name = "Savepoint rollback restores the exact pre-savepoint facet contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint facet contents")
	void shouldRollBackFacetIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final FacetIndexState state = new FacetIndexState(random);
			assertSavepointRollbackRestores(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningFacetIndexTest::snapshot,
				tested -> {
					// a guaranteed-visible mutation makes the in-savepoint batch non-vacuous
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "Savepoint commit keeps the in-savepoint facet contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint commit keeps the in-savepoint facet contents")
	void shouldCommitFacetIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final FacetIndexState state = new FacetIndexState(random);
			assertSavepointCommitKeeps(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningFacetIndexTest::snapshot,
				tested -> {
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	/**
	 * A {@link FacetIndex} paired with an in-test model of its facet contents (a set of `(reference name, group id,
	 * facet id, entity id)` tuples) so randomized mutations can be generated that keep the model and index in lockstep.
	 * The reference schema argument is irrelevant to {@link FacetIndex} — its add/remove operations key purely on the
	 * {@link ReferenceKey} — so `null` is passed. The initial non-empty index is seeded outside any transaction;
	 * mutations are applied to the index (and mirrored in the model) within the framework's transaction.
	 */
	private static final class FacetIndexState {
		private static final int MAX_FACET_ID = 5;
		private static final int MAX_GROUP_ID = 3;
		private static final int MAX_ENTITY_ID = 30;
		private static final String[] REFERENCE_NAMES = {"BRAND", "CATEGORY", "PARAMETER"};

		private final FacetIndex index = new FacetIndex();
		private final Set<FacetKey> present = new HashSet<>();
		// reserved entity-id sequence for guaranteed-new forced mutations, kept clear of the 1..MAX_ENTITY_ID random range
		private int forcedEntitySeq = 1000;

		FacetIndexState(@Nonnull Random random) {
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandomFacet(random);
			}
		}

		/**
		 * Applies `count` random facet add/remove mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (this.present.isEmpty() || random.nextBoolean()) {
					addRandomFacet(random);
				} else {
					removeRandomFacet(random);
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: adds a null-group facet for a brand-new entity id drawn from a reserved
		 * sequence that random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int entityId = ++this.forcedEntitySeq;
			this.index.addFacet(null, new ReferenceKey(REFERENCE_NAMES[0], 1), null, entityId);
			this.present.add(new FacetKey(REFERENCE_NAMES[0], null, 1, entityId));
		}

		/**
		 * Adds a random not-yet-present `(reference, group, facet, entity)` tuple (null-group or grouped); bounded
		 * retries avoid an infinite spin when a random pick collides, and give up silently as a harmless no-op.
		 */
		private void addRandomFacet(@Nonnull Random random) {
			for (int attempt = 0; attempt < 10; attempt++) {
				final String referenceName = REFERENCE_NAMES[random.nextInt(REFERENCE_NAMES.length)];
				final int facetId = random.nextInt(MAX_FACET_ID) + 1;
				final int entityId = random.nextInt(MAX_ENTITY_ID) + 1;
				final Integer groupId = random.nextBoolean() ? null : random.nextInt(MAX_GROUP_ID) + 1;
				final FacetKey key = new FacetKey(referenceName, groupId, facetId, entityId);
				if (this.present.add(key)) {
					this.index.addFacet(null, new ReferenceKey(referenceName, facetId), groupId, entityId);
					return;
				}
			}
		}

		/**
		 * Removes a random present tuple, mirrored into the model; a no-op when the model is empty.
		 */
		private void removeRandomFacet(@Nonnull Random random) {
			if (this.present.isEmpty()) {
				return;
			}
			final List<FacetKey> keys = new ArrayList<>(this.present);
			final FacetKey key = keys.get(random.nextInt(keys.size()));
			final ReferenceKey referenceKey = new ReferenceKey(key.referenceName(), key.facetId());
			this.index.removeFacet(null, referenceKey, key.groupId(), key.entityId());
			this.present.remove(key);
		}

		/**
		 * Value-comparable identity of a single facet relation inside a {@link FacetIndex}: the reference name, the
		 * (nullable) group id, the facet primary key and the entity primary key.
		 *
		 * @param referenceName the reference name keying the {@link FacetReferenceIndex}
		 * @param groupId       the group id, or `null` for a facet with no group assignment
		 * @param facetId       the facet primary key
		 * @param entityId      the entity primary key referring to the facet
		 */
		private record FacetKey(
			@Nonnull String referenceName,
			@Nullable Integer groupId,
			int facetId,
			int entityId
		) {}
	}

}
