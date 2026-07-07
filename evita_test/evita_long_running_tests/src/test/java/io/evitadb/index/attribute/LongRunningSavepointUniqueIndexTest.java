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

import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.Entities;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;

/**
 * Generational randomized backfill proof that {@link OwnerUniqueIndex} — its value tree and record-id bitmap — snapshots
 * and restores correctly under a per-entity savepoint (Ref: #1252). Because the index and all its children are
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}s whose transactional changes are `Snapshotable`,
 * the proof drives the {@link OwnerUniqueIndex} directly and asserts its logical content via the value-comparable
 * {@link LongRunningUniqueIndexTest#snapshot(UniqueIndex)} oracle (value → owning record id).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of register/unregister mutations (standing for *prior* entities in the same
 * transaction — these must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch
 * preceded by a guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on
 * rollback / KEPT on commit), and asserts the content against the oracle captured at savepoint open. The transaction
 * then commits so the commit-time layer-sweep verification proves the restore left no dangling layer. The run is
 * time-bounded; the random seed is echoed on failure for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("UniqueIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
class LongRunningSavepointUniqueIndexTest implements TimeBoundedTestSupport {
	private static final int MAX_OPS = 10;

	@ParameterizedTest(name = "Savepoint rollback restores the exact pre-savepoint unique contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint unique contents")
	void shouldRollBackUniqueIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final UniqueState state = new UniqueState(random);
			assertSavepointRollbackRestores(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningUniqueIndexTest::snapshot,
				tested -> {
					// a guaranteed-visible mutation makes the in-savepoint batch non-vacuous
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "Savepoint commit keeps the in-savepoint unique contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint commit keeps the in-savepoint unique contents")
	void shouldCommitUniqueIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final UniqueState state = new UniqueState(random);
			assertSavepointCommitKeeps(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningUniqueIndexTest::snapshot,
				tested -> {
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	/**
	 * An {@link OwnerUniqueIndex} paired with an in-test model of its value → owning record id mapping so randomized
	 * mutations can be generated that keep the model and index in lockstep. Values and record ids are drawn from a
	 * strictly monotonic sequence so every registered key is unique — no unique-value violation can ever arise. The
	 * initial non-empty index is seeded outside any transaction; mutations are applied to the index (and mirrored in the
	 * model) within the framework's transaction.
	 */
	private static final class UniqueState {
		private final OwnerUniqueIndex index =
			new OwnerUniqueIndex(Entities.PRODUCT, new AttributeIndexKey(null, "code", null), String.class);
		// value → owning record id
		private final Map<String, Integer> valueToRecord = new HashMap<>();
		// monotonic sequence for random unique values / record ids
		private int seq = 0;
		// reserved monotonic sequence for guaranteed-new forced mutations, kept clear of the `seq` range
		private int forcedSeq = 1_000_000;

		UniqueState(@Nonnull Random random) {
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandomUniqueKey();
			}
		}

		/**
		 * Applies `count` random register/unregister mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (this.valueToRecord.isEmpty() || random.nextBoolean()) {
					addRandomUniqueKey();
				} else {
					removeRandomUniqueKey(random);
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: registers a brand-new value / record id drawn from a reserved sequence
		 * that random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int id = ++this.forcedSeq;
			final String value = "F_" + id;
			this.index.registerUniqueKey(value, id);
			this.valueToRecord.put(value, id);
		}

		/**
		 * Registers the next strictly-fresh value / record id from the monotonic sequence, mirrored into the model.
		 */
		private void addRandomUniqueKey() {
			final int id = ++this.seq;
			final String value = "V_" + id;
			this.index.registerUniqueKey(value, id);
			this.valueToRecord.put(value, id);
		}

		/**
		 * Unregisters a random present value, mirrored into the model; a no-op when the model is empty.
		 */
		private void removeRandomUniqueKey(@Nonnull Random random) {
			if (this.valueToRecord.isEmpty()) {
				return;
			}
			final List<String> values = new ArrayList<>(this.valueToRecord.keySet());
			final String value = values.get(random.nextInt(values.size()));
			final int id = this.valueToRecord.remove(value);
			this.index.unregisterUniqueKey(value, id);
		}
	}

}
