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

package io.evitadb.index.invertedIndex;

import io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.LongRunningInvertedIndexTest.InvertedIndexSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized backfill proof that {@link InvertedIndex} snapshots and restores correctly under a per-entity
 * savepoint (Ref: #1252). Because the index is a
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional changes are snapshotable,
 * the proof drives the index directly and asserts its logical bucket contents (value → sorted record ids, read via
 * {@link InvertedIndex#getValueToRecordBitmap()}).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of add/remove mutations (standing for *prior* entities in the same transaction —
 * these must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a
 * guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on
 * commit), and asserts the bucket contents against the oracle captured at savepoint open. The transaction then commits
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
@DisplayName("InvertedIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
class LongRunningSavepointInvertedIndexTest extends AbstractSavepointFuzzTest<InvertedIndexSnapshot> {
	private static final int MAX_OPS = 10;

	@Nonnull
	@Override
	protected FuzzGeneration<InvertedIndexSnapshot> newGeneration(@Nonnull Random random) {
		return new InvertedIndexState(random);
	}

	/**
	 * An {@link InvertedIndex} paired with an in-test model of its bucket contents (value → record ids) so randomized
	 * mutations can be generated that keep the model and index in lockstep. Every record id is assigned to exactly one
	 * bucket (the histogram invariant that a record id must not appear in multiple buckets). The initial non-empty index
	 * is seeded outside any transaction; mutations are applied to the index (and mirrored in the model) within the
	 * framework's transaction.
	 */
	private static final class InvertedIndexState implements FuzzGeneration<InvertedIndexSnapshot> {
		private static final int MAX_VALUE = 200;
		private static final int MAX_RECORD = 1000;
		private static final long FORCED_VALUE = -1L;

		private final InvertedIndex index = new InvertedIndex(FilterIndex.NO_NORMALIZATION, Comparator.<Long>naturalOrder());
		private final Map<Long, Set<Integer>> model = new HashMap<>();
		private final Set<Integer> usedRecords = new HashSet<>();
		// reserved record-id sequence for guaranteed-new forced mutations, kept clear of the 0..MAX_RECORD random range
		private int forcedRecordSeq = 100_000;

		InvertedIndexState(@Nonnull Random random) {
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
		public InvertedIndexSnapshot contents() {
			return LongRunningInvertedIndexTest.snapshot(this.index);
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
		 * Applies `count` random add/remove mutations, mirrored into the model.
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
		 * Applies one guaranteed-visible change: adds a brand-new record id (drawn from a reserved sequence random ops
		 * never touch) to a reserved value bucket, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int recordId = ++this.forcedRecordSeq;
			this.index.addRecord(FORCED_VALUE, recordId);
			this.model.computeIfAbsent(FORCED_VALUE, k -> new HashSet<>()).add(recordId);
			this.usedRecords.add(recordId);
		}

		/**
		 * Adds a random record with a not-yet-used record id to a random value bucket; bounded retries avoid an infinite
		 * spin on a collision and give up silently as a harmless no-op.
		 */
		private void addRandomRecord(@Nonnull Random random) {
			for (int attempt = 0; attempt < 20; attempt++) {
				final long value = random.nextInt(MAX_VALUE);
				final int recordId = random.nextInt(MAX_RECORD);
				if (this.usedRecords.contains(recordId)) {
					continue;
				}
				this.index.addRecord(value, recordId);
				this.model.computeIfAbsent(value, k -> new HashSet<>()).add(recordId);
				this.usedRecords.add(recordId);
				return;
			}
		}

		/**
		 * Removes a random present (value, record) pair, mirrored into the model; a no-op when the model is empty.
		 */
		private void removeRandomRecord(@Nonnull Random random) {
			if (this.model.isEmpty()) {
				return;
			}
			final List<Long> values = new ArrayList<>(this.model.keySet());
			final long value = values.get(random.nextInt(values.size()));
			final Set<Integer> records = this.model.get(value);
			final List<Integer> recordIds = new ArrayList<>(records);
			final int recordId = recordIds.get(random.nextInt(recordIds.size()));
			this.index.removeRecord(value, recordId);
			records.remove(recordId);
			this.usedRecords.remove(recordId);
			if (records.isEmpty()) {
				this.model.remove(value);
			}
		}
	}

}
