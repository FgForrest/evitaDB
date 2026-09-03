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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
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
 * Generational randomized backfill proof that {@code SortIndexChanges} — the derived-cache diff layer of
 * {@link SortIndex} — snapshots and restores correctly under a per-entity savepoint. The memento drops a
 * lazily-rebuilt value-location cache (rebuilt from the parent index on demand), so the proof drives the parent
 * {@link OwnerSortIndex} directly and asserts its sorted record output, exercising the cache layer together with the
 * index's own inner transactional structures (sorted records array + value-cardinality map).
 *
 * Each generation builds a fresh index seeded with random `value → recordId` pairs (single-letter values, so values
 * cluster and the cardinality map is exercised), then within one real transaction applies a random baseline batch of
 * add/remove operations (must survive the savepoint rollback) and a random in-savepoint batch with a guaranteed-new
 * marker record (must revert on rollback / be kept on commit). The framework asserts the ascending sorted record ids
 * against the oracle captured at savepoint open, then commits so the layer-sweep verification proves the restore left no
 * dangling layer. A model mirrors the contents so removals always target an existing pair. The run is time-bounded; the
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
@DisplayName("SortIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
class LongRunningSavepointSortIndexTest extends AbstractSavepointFuzzTest<List<Integer>> {
	private static final int INITIAL_SIZE = 24;
	private static final int VALUE_SPACE = 12;
	private static final int RECORD_SPACE = 200;
	private static final int MARKER = 1_000_000;
	private static final int MAX_OPS = 10;

	@Nonnull
	@Override
	protected FuzzGeneration<List<Integer>> newGeneration(@Nonnull Random random) {
		return new SortState(random);
	}

	/**
	 * Reads the index's ascending sorted record ids into an `.equals`-comparable list.
	 */
	@Nonnull
	private static List<Integer> sortedContents(@Nonnull SortIndex index) {
		final int[] array = index.getAscendingOrderRecordsSupplier().getSortedRecordIds();
		final List<Integer> contents = new ArrayList<>(array.length);
		for (final int value : array) {
			contents.add(value);
		}
		return contents;
	}

	/**
	 * A {@link SortIndex} paired with an in-test model of its `value → recordId` pairs so randomized add/remove ops stay
	 * valid (unique record ids on add, an existing pair on remove). The initial contents are seeded outside any
	 * transaction; ops are applied within the framework's transaction.
	 */
	private static final class SortState implements FuzzGeneration<List<Integer>> {
		private final SortIndex index = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));
		private final TreeSet<ValueRecord> model = new TreeSet<>();

		SortState(@Nonnull Random random) {
			final int size = random.nextInt(INITIAL_SIZE);
			for (int i = 0; i < size; i++) {
				final int recordId = 1 + random.nextInt(RECORD_SPACE);
				// a record id maps to exactly one value in an owner sort index — never seed a duplicate id
				if (containsRecord(recordId)) {
					continue;
				}
				final String value = randomValue(random);
				this.model.add(new ValueRecord(value, recordId));
				this.index.addRecord(value, recordId);
			}
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.index;
		}

		@Nonnull
		@Override
		public List<Integer> contents() {
			return LongRunningSavepointSortIndexTest.sortedContents(this.index);
		}

		@Override
		public void applyBaselineOperations(@Nonnull Random random) {
			applyRandomOps(random, 1 + random.nextInt(MAX_OPS));
		}

		@Override
		public void applySavepointOperations(@Nonnull Random random) {
			applyRandomOps(random, 1 + random.nextInt(MAX_OPS));
			// applied LAST: a marker applied first enters the model and a later random operation can undo it
			addMarker();
		}

		/**
		 * Applies `count` random add (unique record id) / remove (existing pair) operations.
		 */
		void applyRandomOps(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (this.model.isEmpty() || random.nextInt(3) != 0) {
					int recordId;
					do {
						recordId = 1 + random.nextInt(RECORD_SPACE);
					} while (containsRecord(recordId));
					final String value = randomValue(random);
					this.model.add(new ValueRecord(value, recordId));
					this.index.addRecord(value, recordId);
				} else {
					final ValueRecord toRemove = pick(random);
					this.model.remove(toRemove);
					this.index.removeRecord(toRemove.value(), toRemove.recordId());
				}
			}
		}

		/**
		 * Adds a record with a record id outside the random range, guaranteeing the sorted set changes.
		 */
		void addMarker() {
			this.model.add(new ValueRecord("A", MARKER));
			this.index.addRecord("A", MARKER);
		}

		private boolean containsRecord(int recordId) {
			for (final ValueRecord record : this.model) {
				if (record.recordId() == recordId) {
					return true;
				}
			}
			return false;
		}

		@Nonnull
		private ValueRecord pick(@Nonnull Random random) {
			final int index = random.nextInt(this.model.size());
			int i = 0;
			for (final ValueRecord record : this.model) {
				if (i++ == index) {
					return record;
				}
			}
			throw new IllegalStateException("unreachable");
		}

		@Nonnull
		private static String randomValue(@Nonnull Random random) {
			return Character.toString('A' + random.nextInt(VALUE_SPACE));
		}
	}

	/**
	 * Model entry mirroring an index `value → recordId` pair, ordered by value then record id.
	 *
	 * @param value    the attribute value
	 * @param recordId the record id
	 */
	private record ValueRecord(@Nonnull String value, int recordId) implements Comparable<ValueRecord> {
		@Override
		public int compareTo(@Nonnull ValueRecord o) {
			final int byValue = this.value.compareTo(o.value);
			return byValue == 0 ? Integer.compare(this.recordId, o.recordId) : byValue;
		}
	}

}
