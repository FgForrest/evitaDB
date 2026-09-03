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
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.index.attribute.LongRunningFilterIndexTest.FilterSnapshot;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
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

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized backfill proof that {@link OwnerFilterIndex} — together with its inverted index and range
 * companion — snapshots and restores correctly under a per-entity savepoint (Ref: #1252). Because the index and all its
 * children are {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}s whose transactional changes are
 * `Snapshotable`, the proof drives the {@link OwnerFilterIndex} directly and asserts its logical content via the
 * value-comparable {@link LongRunningFilterIndexTest#snapshot(FilterIndex)} oracle (inverted index + range points).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of range mutations (standing for *prior* entities in the same transaction — these
 * must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a
 * guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on
 * commit), and asserts the content against the oracle captured at savepoint open. The transaction then commits so the
 * commit-time layer-sweep verification proves the restore left no dangling layer. The run is time-bounded; the random
 * seed is echoed on failure for deterministic reproduction.
 *
 * The scenario is declared once and run by {@link AbstractSavepointFuzzTest} in BOTH phases: the transactional
 * savepoint described above, and the WARM_UP savepoint where the same writes land straight on the delegate
 * structures and are rewound from the inverses they journal themselves. See that class for the shape of one
 * generation, for the mid-savepoint read every case is asserted through, and for why the warm-up half runs
 * exclusively.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("FilterIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(FILTER)
@Tag(TRANSACTION)
class LongRunningSavepointFilterIndexTest extends AbstractSavepointFuzzTest<FilterSnapshot> {
	private static final int MAX_OPS = 10;

	@Nonnull
	@Override
	protected FuzzGeneration<FilterSnapshot> newGeneration(@Nonnull Random random) {
		return new FilterState(random);
	}

	/**
	 * An {@link OwnerFilterIndex} paired with an in-test model of its logical content (range → owning record and record
	 * → its ranges) so randomized mutations can be generated that keep the model and index in lockstep. The initial
	 * non-empty index is seeded outside any transaction; mutations are applied to the index (and mirrored in the model)
	 * within the harness's savepoint.
	 */
	private static final class FilterState implements FuzzGeneration<FilterSnapshot> {
		private static final int MAX_VALUE = 100;

		private final OwnerFilterIndex index =
			new OwnerFilterIndex(new AttributeIndexKey(null, "c", null), IntegerNumberRange.class);
		// range → owning record id (a range is globally unique across records, mirroring the commit proof model)
		private final Map<IntegerNumberRange, Integer> rangeToRecord = new HashMap<>();
		// record id → its ranges
		private final Map<Integer, Set<IntegerNumberRange>> recordRanges = new HashMap<>();
		// reserved value sequence for guaranteed-new forced mutations, kept clear of the 0..MAX_VALUE random range
		private int forcedSeq = 1000;

		FilterState(@Nonnull Random random) {
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandomRange(random);
			}
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.index;
		}

		@Nonnull
		@Override
		public FilterSnapshot contents() {
			return LongRunningFilterIndexTest.snapshot(this.index);
		}

		@Override
		public void applyBaselineOperations(@Nonnull Random random) {
			applyRandomMutations(random, 1 + random.nextInt(MAX_OPS));
		}

		@Override
		public void applySavepointOperations(@Nonnull Random random) {
			applyRandomMutations(random, random.nextInt(MAX_OPS));
			// applied LAST: a marker added first enters the model and a later random removal can undo it
			forceMutation();
		}

		/**
		 * Applies `count` random range add/remove mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (this.rangeToRecord.isEmpty() || random.nextBoolean()) {
					addRandomRange(random);
				} else {
					removeRandomRange(random);
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: adds a brand-new single-point range for a brand-new record id drawn
		 * from a reserved sequence that random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int seq = ++this.forcedSeq;
			final IntegerNumberRange range = IntegerNumberRange.between(seq, seq);
			this.index.addRecord(seq, range);
			this.rangeToRecord.put(range, seq);
			this.recordRanges.computeIfAbsent(seq, k -> new HashSet<>()).add(range);
		}

		/**
		 * Adds a random range for a not-yet-present range key; a new record gets the range via `addRecord`, an existing
		 * one via `addRecordDelta`. Bounded retries avoid an infinite spin on a range collision and give up silently as
		 * a harmless no-op.
		 */
		private void addRandomRange(@Nonnull Random random) {
			for (int attempt = 0; attempt < 10; attempt++) {
				final int from = random.nextInt(MAX_VALUE);
				final int to = random.nextInt(MAX_VALUE);
				final IntegerNumberRange range = IntegerNumberRange.between(Math.min(from, to), Math.max(from, to));
				if (this.rangeToRecord.containsKey(range)) {
					continue;
				}
				final int recordId = random.nextInt(MAX_VALUE);
				final Set<IntegerNumberRange> existing = this.recordRanges.get(recordId);
				if (existing == null) {
					this.index.addRecord(recordId, range);
					this.recordRanges.computeIfAbsent(recordId, k -> new HashSet<>()).add(range);
				} else {
					this.index.addRecordDelta(recordId, new IntegerNumberRange[] { range });
					existing.add(range);
				}
				this.rangeToRecord.put(range, recordId);
				return;
			}
		}

		/**
		 * Removes a random present range, mirrored into the model; the record's last range is dropped via `removeRecord`,
		 * otherwise a single value is peeled off via `removeRecordDelta`. A no-op when the model is empty.
		 */
		private void removeRandomRange(@Nonnull Random random) {
			if (this.rangeToRecord.isEmpty()) {
				return;
			}
			final List<IntegerNumberRange> ranges = new ArrayList<>(this.rangeToRecord.keySet());
			final IntegerNumberRange range = ranges.get(random.nextInt(ranges.size()));
			final int recordId = this.rangeToRecord.get(range);
			final Set<IntegerNumberRange> recordValues = this.recordRanges.get(recordId);
			if (recordValues.size() > 1) {
				this.index.removeRecordDelta(recordId, new IntegerNumberRange[] { range });
				recordValues.remove(range);
			} else {
				this.index.removeRecord(recordId, new IntegerNumberRange[] { range });
				this.recordRanges.remove(recordId);
			}
			this.rangeToRecord.remove(range);
		}
	}

}
