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

package io.evitadb.index.range;

import io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.index.range.LongRunningRangeIndexTest.RangeSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized backfill proof that {@link RangeIndex} snapshots and restores correctly under a per-entity
 * savepoint (Ref: #1252). Because the index is a
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional changes are snapshotable,
 * the proof drives the index directly and asserts its logical range-point contents (read via
 * {@link RangeIndex#rangesIterator()}).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of add/remove mutations (standing for *prior* entities in the same transaction —
 * these must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a
 * guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on
 * commit), and asserts the range contents against the oracle captured at savepoint open. The transaction then commits
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
@DisplayName("RangeIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningSavepointRangeIndexTest extends AbstractSavepointFuzzTest<RangeSnapshot> {
	private static final int MAX_OPS = 10;

	@Nonnull
	@Override
	protected FuzzGeneration<RangeSnapshot> newGeneration(@Nonnull Random random) {
		return new RangeState(random);
	}

	/**
	 * A {@link RangeIndex} paired with an in-test model of its contents (range → record id) so randomized mutations can
	 * be generated that keep the model and index in lockstep. Every record id maps to exactly one unique range, so no
	 * shared-border conflicts arise. The initial non-empty index is seeded outside any transaction; mutations are
	 * applied to the index (and mirrored in the model) within the framework's transaction.
	 */
	private static final class RangeState implements FuzzGeneration<RangeSnapshot> {
		private static final int OPTIMAL_COUNT = 100;

		private final RangeIndex index = new RangeIndex();
		private final Map<IntegerNumberRange, Integer> model = new HashMap<>();
		private final Set<Integer> usedRecords = new HashSet<>();
		private final Set<IntegerNumberRange> usedRanges = new HashSet<>();
		// reserved id/threshold sequence for guaranteed-new forced mutations, kept clear of the random range window
		private int forcedSeq = 1_000_000;

		RangeState(@Nonnull Random random) {
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
		public RangeSnapshot contents() {
			return LongRunningRangeIndexTest.snapshot(this.index);
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
		 * Applies one guaranteed-visible change: adds a record over a brand-new range and record id drawn from a
		 * reserved sequence that random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int recordId = ++this.forcedSeq;
			final int base = this.forcedSeq * 4;
			final IntegerNumberRange range = IntegerNumberRange.between(base, base + 1);
			this.index.addRecord(range.getFrom(), range.getTo(), recordId);
			this.model.put(range, recordId);
			this.usedRecords.add(recordId);
			this.usedRanges.add(range);
		}

		/**
		 * Adds a random record over a not-yet-used unique range with a not-yet-used record id; bounded retries avoid an
		 * infinite spin on a collision and give up silently as a harmless no-op.
		 */
		private void addRandomRecord(@Nonnull Random random) {
			for (int attempt = 0; attempt < 20; attempt++) {
				final int from = random.nextInt(OPTIMAL_COUNT * 2);
				final int to = random.nextInt(OPTIMAL_COUNT * 2);
				final IntegerNumberRange range = IntegerNumberRange.between(Math.min(from, to), Math.max(from, to));
				if (this.usedRanges.contains(range)) {
					continue;
				}
				final int recordId = random.nextInt(OPTIMAL_COUNT);
				if (this.usedRecords.contains(recordId)) {
					continue;
				}
				this.index.addRecord(range.getFrom(), range.getTo(), recordId);
				this.model.put(range, recordId);
				this.usedRecords.add(recordId);
				this.usedRanges.add(range);
				return;
			}
		}

		/**
		 * Removes a random present (range, record) pair, mirrored into the model; a no-op when the model is empty.
		 */
		private void removeRandomRecord(@Nonnull Random random) {
			if (this.model.isEmpty()) {
				return;
			}
			final List<Entry<IntegerNumberRange, Integer>> entries = new ArrayList<>(this.model.entrySet());
			final Entry<IntegerNumberRange, Integer> entry = entries.get(random.nextInt(entries.size()));
			this.index.removeRecord(entry.getKey().getFrom(), entry.getKey().getTo(), entry.getValue());
			this.model.remove(entry.getKey());
			this.usedRecords.remove(entry.getValue());
			this.usedRanges.remove(entry.getKey());
		}
	}

}
