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

package io.evitadb.core.transaction.memory;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest.FuzzGeneration;
import io.evitadb.index.array.TransactionalIntArray;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.index.set.TransactionalSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized backfill proof that the simple leaf data deltas snapshot and restore correctly under
 * a per-entity savepoint: {@code BooleanChanges} ({@link TransactionalBoolean}), {@code ReferenceChanges}
 * ({@link TransactionalReference}), {@code SetChanges} ({@link TransactionalSet}) and {@code IntArrayChanges}
 * ({@link TransactionalIntArray}). It complements {@code LongRunningSavepointFuzzFrameworkTest}, which covers
 * {@code MapChanges} and {@code BitmapChanges}.
 *
 * Each case rebuilds a fresh structure from a random reference, then within one real transaction applies a random
 * baseline batch (must survive the savepoint rollback) and a random in-savepoint batch (must revert on rollback / be
 * kept on commit). The framework asserts the structure's logical content against the oracle captured at savepoint open,
 * then commits the transaction so the layer-sweep verification proves the restore left no dangling layer. Every
 * in-savepoint batch is made non-vacuous (a marker value the baseline range cannot produce / a guaranteed flip) so a
 * no-op rollback cannot pass by accident. The run is time-bounded; the random seed is echoed on failure for
 * deterministic reproduction.
 *
 * The scenario is declared once and run by {@link AbstractSavepointFuzzTest} in BOTH phases: the transactional
 * savepoint described above, and the WARM_UP savepoint where the same writes land straight on the delegate
 * structures and are rewound from the inverses they journal themselves. See that class for the shape of one
 * generation, for the mid-savepoint read every case is asserted through, and for why the warm-up half runs
 * exclusively.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Leaf-delta savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(TRANSACTION)
class LongRunningSavepointLeafDeltaTest {
	private static final int KEY_SPACE = 64;
	private static final int MARKER = 100_000;
	private static final int MAX_OPS = 8;

	// ---------------------------------------------------------------------------------------------------------------
	// BooleanChanges (TransactionalBoolean)
	// ---------------------------------------------------------------------------------------------------------------

	@Nested
	@DisplayName("TransactionalBoolean")
	class BooleanCase extends AbstractSavepointFuzzTest<Boolean> {

		@Nonnull
		@Override
		protected FuzzGeneration<Boolean> newGeneration(@Nonnull Random random) {
			return new BooleanState(random);
		}

	}

	/**
	 * One generation's fixture for {@link TransactionalBoolean} — a boolean flag read back through its own
	 * public surface, so the harness's mid-savepoint read goes through the same view a caller would use.
	 */
	private static final class BooleanState implements FuzzGeneration<Boolean> {
		private final TransactionalBoolean value;

		BooleanState(@Nonnull Random random) {
			this.value = new TransactionalBoolean(random.nextBoolean());
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.value;
		}

		@Nonnull
		@Override
		public Boolean contents() {
			return this.value.isTrue();
		}

		@Override
		public void applyBaselineOperations(@Nonnull Random random) {
			applyRandomBooleanOps(this.value, random, 1 + random.nextInt(MAX_OPS));
		}

		@Override
		public void applySavepointOperations(@Nonnull Random random) {
			// ONLY the guaranteed flip: with just two states, a random batch applied after it could land back on the
			// pre-savepoint value and make the batch vacuous
			if (this.value.isTrue()) {
				this.value.setToFalse();
			} else {
				this.value.setToTrue();
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// ReferenceChanges (TransactionalReference)
	// ---------------------------------------------------------------------------------------------------------------

	@Nested
	@DisplayName("TransactionalReference")
	class ReferenceCase extends AbstractSavepointFuzzTest<Integer> {

		@Nonnull
		@Override
		protected FuzzGeneration<Integer> newGeneration(@Nonnull Random random) {
			return new ReferenceState(random);
		}

	}

	/**
	 * One generation's fixture for {@link TransactionalReference} — a single reference slot read back through its own
	 * public surface, so the harness's mid-savepoint read goes through the same view a caller would use.
	 */
	private static final class ReferenceState implements FuzzGeneration<Integer> {
		private final TransactionalReference<Integer> value;

		ReferenceState(@Nonnull Random random) {
			this.value = new TransactionalReference<>(random.nextInt(KEY_SPACE));
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.value;
		}

		@Nonnull
		@Override
		public Integer contents() {
			return this.value.get();
		}

		@Override
		public void applyBaselineOperations(@Nonnull Random random) {
			applyRandomReferenceOps(this.value, random, 1 + random.nextInt(MAX_OPS));
		}

		@Override
		public void applySavepointOperations(@Nonnull Random random) {
			applyRandomReferenceOps(this.value, random, 1 + random.nextInt(MAX_OPS));
			// applied LAST: MARKER lies outside the random range, so nothing after it can overwrite it back
			this.value.set(MARKER);
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// SetChanges (TransactionalSet)
	// ---------------------------------------------------------------------------------------------------------------

	@Nested
	@DisplayName("TransactionalSet")
	class SetCase extends AbstractSavepointFuzzTest<Set<Integer>> {

		@Nonnull
		@Override
		protected FuzzGeneration<Set<Integer>> newGeneration(@Nonnull Random random) {
			return new SetState(random);
		}

	}

	/**
	 * One generation's fixture for {@link TransactionalSet} — a hash-set-backed wrapper read back through its own
	 * public surface, so the harness's mid-savepoint read goes through the same view a caller would use.
	 */
	private static final class SetState implements FuzzGeneration<Set<Integer>> {
		private final TransactionalSet<Integer> set;

		SetState(@Nonnull Random random) {
			this.set = newSeededSet(random);
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.set;
		}

		@Nonnull
		@Override
		public Set<Integer> contents() {
			return new HashSet<>(this.set);
		}

		@Override
		public void applyBaselineOperations(@Nonnull Random random) {
			applyRandomSetOps(this.set, random, 1 + random.nextInt(MAX_OPS));
		}

		@Override
		public void applySavepointOperations(@Nonnull Random random) {
			applyRandomSetOps(this.set, random, 1 + random.nextInt(MAX_OPS));
			// applied LAST: a marker added first can be taken out again by a later removeAll / retainAll
			this.set.add(MARKER);
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// IntArrayChanges (TransactionalIntArray)
	// ---------------------------------------------------------------------------------------------------------------

	@Nested
	@DisplayName("TransactionalIntArray")
	class IntArrayCase extends AbstractSavepointFuzzTest<List<Integer>> {

		@Nonnull
		@Override
		protected FuzzGeneration<List<Integer>> newGeneration(@Nonnull Random random) {
			return new IntArrayState(random);
		}

	}

	/**
	 * One generation's fixture for {@link TransactionalIntArray} — a primitive-array wrapper read back through its own
	 * public surface, so the harness's mid-savepoint read goes through the same view a caller would use.
	 */
	private static final class IntArrayState implements FuzzGeneration<List<Integer>> {
		private final TransactionalIntArray array;

		IntArrayState(@Nonnull Random random) {
			this.array = newSeededIntArray(random);
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.array;
		}

		@Nonnull
		@Override
		public List<Integer> contents() {
			return intArrayContents(this.array);
		}

		@Override
		public void applyBaselineOperations(@Nonnull Random random) {
			applyRandomIntArrayOps(this.array, random, 1 + random.nextInt(MAX_OPS));
		}

		@Override
		public void applySavepointOperations(@Nonnull Random random) {
			applyRandomIntArrayOps(this.array, random, 1 + random.nextInt(MAX_OPS));
			// applied LAST: a marker added first can be removed again by a later random removal
			this.array.add(MARKER);
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// seeding + op helpers
	// ---------------------------------------------------------------------------------------------------------------

	private static void applyRandomBooleanOps(@Nonnull TransactionalBoolean value, @Nonnull Random random, int count) {
		for (int i = 0; i < count; i++) {
			if (random.nextBoolean()) {
				value.setToTrue();
			} else {
				value.setToFalse();
			}
		}
	}

	private static void applyRandomReferenceOps(@Nonnull TransactionalReference<Integer> value, @Nonnull Random random, int count) {
		for (int i = 0; i < count; i++) {
			value.set(random.nextInt(KEY_SPACE));
		}
	}

	@Nonnull
	private static TransactionalSet<Integer> newSeededSet(@Nonnull Random random) {
		final Set<Integer> seed = new HashSet<>();
		final int size = random.nextInt(KEY_SPACE);
		for (int i = 0; i < size; i++) {
			seed.add(random.nextInt(KEY_SPACE));
		}
		return new TransactionalSet<>(seed);
	}

	private static void applyRandomSetOps(@Nonnull TransactionalSet<Integer> set, @Nonnull Random random, int count) {
		for (int i = 0; i < count; i++) {
			// view-iterator / bulk-view ops (choices >= 2) mutate the diff layer through the merged iterator, so they
			// require the layer to already exist; when it does not yet, fall back to the direct add/remove that creates
			// it (this is the write path the maintainer's first-touch snapshotting relies on)
			final boolean hasLayer = Transaction.getTransactionalMemoryLayerIfExists(set) != null;
			switch (random.nextInt(hasLayer ? 5 : 2)) {
				case 0 -> set.remove(random.nextInt(KEY_SPACE));
				case 1 -> set.add(random.nextInt(KEY_SPACE));
				case 2 -> removeOneViaIterator(set, random);              // Iterator#remove on the merged view
				case 3 -> set.removeAll(randomKeySubset(random));         // AbstractSet#removeAll -> merged iterator remove
				case 4 -> set.retainAll(randomKeySubset(random));         // AbstractSet#retainAll -> merged iterator remove
				default -> throw new IllegalStateException("unreachable set op choice");
			}
		}
	}

	/**
	 * Removes a single (randomly positioned) element from the set through its merged iterator, exercising the
	 * collection-view removal path that bypasses the direct mutators.
	 */
	private static void removeOneViaIterator(@Nonnull TransactionalSet<Integer> set, @Nonnull Random random) {
		final int size = set.size();
		if (size == 0) {
			return;
		}
		int target = random.nextInt(size);
		final Iterator<Integer> it = set.iterator();
		while (it.hasNext()) {
			it.next();
			if (target-- == 0) {
				it.remove();
				return;
			}
		}
	}

	/**
	 * Builds a small random subset of the key space to drive {@code removeAll} / {@code retainAll} through the views.
	 */
	@Nonnull
	private static Set<Integer> randomKeySubset(@Nonnull Random random) {
		final Set<Integer> subset = new HashSet<>();
		final int n = 1 + random.nextInt(4);
		for (int i = 0; i < n; i++) {
			subset.add(random.nextInt(KEY_SPACE));
		}
		return subset;
	}

	@Nonnull
	private static TransactionalIntArray newSeededIntArray(@Nonnull Random random) {
		final Set<Integer> seed = new HashSet<>();
		final int size = random.nextInt(KEY_SPACE);
		for (int i = 0; i < size; i++) {
			seed.add(1 + random.nextInt(KEY_SPACE));
		}
		final int[] values = seed.stream().mapToInt(Integer::intValue).sorted().toArray();
		return new TransactionalIntArray(values);
	}

	private static void applyRandomIntArrayOps(@Nonnull TransactionalIntArray array, @Nonnull Random random, int count) {
		for (int i = 0; i < count; i++) {
			final int recordId = 1 + random.nextInt(KEY_SPACE);
			if (random.nextInt(3) == 0) {
				array.remove(recordId);
			} else {
				array.add(recordId);
			}
		}
	}

	@Nonnull
	private static List<Integer> intArrayContents(@Nonnull TransactionalIntArray array) {
		final int[] values = array.getArray();
		final List<Integer> contents = new ArrayList<>(values.length);
		for (final int value : values) {
			contents.add(value);
		}
		return contents;
	}
}
