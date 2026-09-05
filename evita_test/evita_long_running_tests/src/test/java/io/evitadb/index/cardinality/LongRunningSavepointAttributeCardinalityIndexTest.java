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

package io.evitadb.index.cardinality;

import io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import io.evitadb.index.cardinality.LongRunningAttributeCardinalityIndexTest.AttributeCardinalitySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized backfill proof that {@link AttributeCardinalityIndex} — a
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional cardinality map is
 * `Snapshotable` — snapshots and restores correctly under a per-entity savepoint (Ref: #1252). The proof drives the
 * index directly and asserts its logical `(recordId, value) → cardinality` contents (read via
 * {@link AttributeCardinalityIndex#getCardinalities()}).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of cardinality mutations (standing for *prior* entities in the same transaction —
 * these must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a
 * guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on
 * commit), and asserts the cardinality contents against the oracle captured at savepoint open. The transaction then
 * commits so the commit-time layer-sweep verification proves the restore left no dangling layer. The run is
 * time-bounded; the random seed is echoed on failure for deterministic reproduction.
 *
 * The scenario is declared once and run by {@link AbstractSavepointFuzzTest} in BOTH phases: the transactional
 * savepoint described above, and the WARM_UP savepoint where the same writes land straight on the delegate
 * structures and are rewound from the inverses they journal themselves. See that class for the shape of one
 * generation, for the mid-savepoint read every case is asserted through, and for why the warm-up half runs
 * exclusively.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("AttributeCardinalityIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
class LongRunningSavepointAttributeCardinalityIndexTest
	extends AbstractSavepointFuzzTest<AttributeCardinalitySnapshot> {
	private static final int MAX_OPS = 10;

	@Nonnull
	@Override
	protected FuzzGeneration<AttributeCardinalitySnapshot> newGeneration(@Nonnull Random random) {
		return new CardinalityState(random);
	}

	/**
	 * An {@link AttributeCardinalityIndex} paired with an in-test model of its `(recordId, value) → cardinality`
	 * contents so randomized mutations can be generated that keep the model and index in lockstep. The initial non-empty
	 * index is seeded outside any transaction; mutations are applied to the index (and mirrored in the model) within the
	 * framework's transaction.
	 */
	private static final class CardinalityState implements FuzzGeneration<AttributeCardinalitySnapshot> {
		private static final int MAX_VALUE = 8;
		private static final int MAX_RECORD_ID = 10;

		private final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
		private final Map<AttributeCardinalityKey, Integer> model = new HashMap<>();
		// reserved record-id sequence for guaranteed-new forced mutations, kept clear of the 1..MAX_RECORD_ID random range
		private int forcedRecordSeq = 1000;

		CardinalityState(@Nonnull Random random) {
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandomRecord(random);
			}
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.index;
		}

		@Nonnull
		@Override
		public AttributeCardinalitySnapshot contents() {
			return LongRunningAttributeCardinalityIndexTest.snapshot(this.index);
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
		 * Applies `count` random cardinality add/remove mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (this.model.isEmpty() || random.nextBoolean()) {
					addRandomRecord(random);
				} else {
					removeRandomRecord(random);
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: adds a record for a brand-new record id drawn from a reserved sequence
		 * that random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int recordId = ++this.forcedRecordSeq;
			final String value = "a";
			final AttributeCardinalityKey key = new AttributeCardinalityKey(recordId, value);
			this.index.addRecord(value, recordId);
			this.model.merge(key, 1, Integer::sum);
		}

		/**
		 * Adds one (value, recordId) occurrence — a brand-new entry or an increment of an existing one — mirrored into
		 * the model. Always a visible change (cardinality is per-record additive), so no retry loop is needed.
		 */
		private void addRandomRecord(@Nonnull Random random) {
			final String value = String.valueOf((char) ('a' + random.nextInt(MAX_VALUE)));
			final int recordId = random.nextInt(MAX_RECORD_ID) + 1;
			final AttributeCardinalityKey key = new AttributeCardinalityKey(recordId, value);
			this.index.addRecord(value, recordId);
			this.model.merge(key, 1, Integer::sum);
		}

		/**
		 * Removes one occurrence of a randomly chosen present entry (decrement, or full removal when its count reaches
		 * zero), mirrored into the model; a no-op when the model is empty.
		 */
		private void removeRandomRecord(@Nonnull Random random) {
			if (this.model.isEmpty()) {
				return;
			}
			final List<AttributeCardinalityKey> keys = new ArrayList<>(this.model.keySet());
			final AttributeCardinalityKey key = keys.get(random.nextInt(keys.size()));
			this.index.removeRecord((String) key.value(), key.recordId());
			final int newCount = this.model.get(key) - 1;
			if (newCount == 0) {
				this.model.remove(key);
			} else {
				this.model.put(key, newCount);
			}
		}
	}

}
