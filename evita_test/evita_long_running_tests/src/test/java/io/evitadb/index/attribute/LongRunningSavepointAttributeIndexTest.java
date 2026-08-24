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

import io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.index.attribute.LongRunningAttributeIndexTest.AttributeSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized backfill proof that the {@link AttributeIndex} container's transactional changes
 * ({@code AttributeIndexChanges}) snapshot and restore correctly under a per-entity savepoint (Ref: #1252). Because the
 * container is itself a {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose nested maps
 * (standalone unique owner, shared filter value, sort owner, chain) are `Snapshotable`, the proof drives the parent
 * {@link AttributeIndex} directly and asserts its logical content — read via the shared
 * {@link LongRunningAttributeIndexTest#snapshot} oracle (unique / filter / sort / chain record ids).
 *
 * Each generation seeds a fresh random non-empty container outside any transaction, then within one real transaction
 * applies a random baseline batch of mutations (standing for *prior* entities in the same transaction — these must
 * SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a
 * guaranteed-visible marker record (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on
 * commit), and asserts the container content against the oracle captured at savepoint open. The transaction then commits
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
@DisplayName("AttributeIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
class LongRunningSavepointAttributeIndexTest extends AbstractSavepointFuzzTest<AttributeSnapshot> {
	private static final int MAX_OPS = 10;

	@Nonnull
	@Override
	protected FuzzGeneration<AttributeSnapshot> newGeneration(@Nonnull Random random) {
		return new AttributeState(random);
	}

	/**
	 * An {@link AttributeIndex} container paired with an in-test model of its logical content — the set of live record
	 * ids present in the unique + filter + sort sub-indexes and the ordered record ids of the single consistent
	 * predecessor chain — so randomized mutations keep the model and container in lockstep. The initial non-empty
	 * container is seeded outside any transaction; mutations are applied to the container (and mirrored in the model)
	 * within the framework's transaction. Record and chain sub-index writes are delegated to the shared helpers on
	 * {@link LongRunningAttributeIndexTest}.
	 */
	private static final class AttributeState implements FuzzGeneration<AttributeSnapshot> {
		private static final int RECORD_CAP = 40;
		private static final int CHAIN_CAP = 30;
		/** Record-id base for chain elements, kept clear of the record map's small ids. */
		private static final int CHAIN_BASE = 100_000;
		/** Reserved record-id sequence for guaranteed-new forced mutations, kept clear of the seeded/random id range. */
		private static final int FORCED_BASE = 1_000_000;

		private final AttributeIndex index = new EntityAttributeIndex(LongRunningAttributeIndexTest.ENTITY_TYPE);
		private final TreeSet<Integer> records = new TreeSet<>();
		private final List<Integer> chain = new ArrayList<>();
		private int forcedSeq = FORCED_BASE;

		AttributeState(@Nonnull Random random) {
			final int recordSeed = 12 + random.nextInt(12);
			for (int i = 0; i < recordSeed; i++) {
				addRandomRecord();
			}
			final int chainSeed = 6 + random.nextInt(10);
			for (int i = 0; i < chainSeed; i++) {
				appendChain();
			}
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.index;
		}

		@Nonnull
		@Override
		public AttributeSnapshot contents() {
			return LongRunningAttributeIndexTest.snapshot(this.index);
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
		 * Applies `count` random add/remove mutations, mirrored into the model. Roughly a quarter touch the chain (tail
		 * append / tail removal); the rest are record ops atomic over the three record sub-indexes.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (random.nextInt(4) == 0) {
					if (this.chain.isEmpty() || (this.chain.size() < CHAIN_CAP && random.nextBoolean())) {
						appendChain();
					} else {
						removeChainTail();
					}
				} else {
					if (this.records.isEmpty() || (this.records.size() < RECORD_CAP && random.nextBoolean())) {
						addRandomRecord();
					} else {
						removeRandomRecord(random);
					}
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: adds a record for a brand-new id drawn from a reserved sequence that
		 * seeded and random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int recordId = ++this.forcedSeq;
			LongRunningAttributeIndexTest.addRecord(this.index, recordId);
			this.records.add(recordId);
		}

		/**
		 * Adds a record with a fresh id (max + 1, always absent) to the three record sub-indexes.
		 */
		private void addRandomRecord() {
			final int recordId = this.records.isEmpty() ? 1 : this.records.last() + 1;
			LongRunningAttributeIndexTest.addRecord(this.index, recordId);
			this.records.add(recordId);
		}

		/**
		 * Removes a random present record from the three record sub-indexes, mirrored into the model.
		 */
		private void removeRandomRecord(@Nonnull Random random) {
			final List<Integer> present = new ArrayList<>(this.records);
			final int recordId = present.get(random.nextInt(present.size()));
			LongRunningAttributeIndexTest.removeRecord(this.index, recordId);
			this.records.remove(recordId);
		}

		/**
		 * Appends a fresh element at the chain tail, keeping the single chain consistent.
		 */
		private void appendChain() {
			final int predecessorId = this.chain.isEmpty() ? 0 : this.chain.get(this.chain.size() - 1);
			final int recordId = this.chain.isEmpty() ? CHAIN_BASE : this.chain.get(this.chain.size() - 1) + 1;
			LongRunningAttributeIndexTest.chainInsert(this.index, predecessorId, recordId);
			this.chain.add(recordId);
		}

		/**
		 * Removes the chain tail (the element with no successor), keeping the remaining chain consistent.
		 */
		private void removeChainTail() {
			final int recordId = this.chain.remove(this.chain.size() - 1);
			LongRunningAttributeIndexTest.chainRemove(this.index, recordId);
		}
	}

}
