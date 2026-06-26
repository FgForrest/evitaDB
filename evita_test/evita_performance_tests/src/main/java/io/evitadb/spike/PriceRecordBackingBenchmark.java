/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.spike;

import io.evitadb.index.array.TransactionalObjArray;
import io.evitadb.index.bPlusTree.TransactionalElementBPlusTree;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark comparing the in-heap read/write behaviour of the two backings of
 * {@code AbstractPriceListAndCurrencyPriceIndex#priceRecords}:
 *
 * - the **previous** representation — a contiguous, naturally sorted {@link TransactionalObjArray} of
 *   {@link PriceRecordContract} (positional lookup via binary search, `O(n)` mutation by array shift), and
 * - the **new** representation — the element-keyed {@link TransactionalElementBPlusTree} keyed on
 *   {@link PriceRecordContract#internalPriceId()} (`O(log n)` point lookup and mutation, leaf-paged persistence).
 *
 * The persistence write-amplification win of the tree (one changed leaf page per commit instead of one monolithic
 * record-array rewrite) is the migration's reason and is NOT measured here — it is a per-commit storage-bytes metric,
 * not an in-heap op latency. What this benchmark guards is exactly the migration's risk: that swapping a cache-friendly
 * contiguous array for a pointer-chasing tree must not materially regress the hot in-heap read paths (point lookup,
 * the filtered merge, full materialization) — while it is expected to IMPROVE mutation (`O(log n)` vs `O(n)`).
 *
 * Four op pairs are measured, each in an `array*` (baseline) and `tree*` (candidate) variant, at 100k records:
 *
 * 1. {@code getById*} — the `getPriceRecord(id)` probe (array = {@link Arrays#binarySearch}, tree =
 *    {@link TransactionalElementBPlusTree#search}).
 * 2. {@code filteredLookup*} — the `getPriceRecords(Bitmap)` resolve of a 1000-id ascending filter (array = per-id
 *    binary search, tree = one forward merge-join over {@code greaterOrEqualValueIterator}, the production override).
 * 3. {@code mutate*} — a net-zero insert-then-delete of a middle key (array = two `O(n)` shifts, tree = two
 *    `O(log n)` descents). The structure size is unchanged, so the op is repeatable across invocations.
 * 4. {@code toArray*} — full materialization (`getPriceRecords()` no-arg: array returns its backing, tree walks every
 *    leaf and allocates) — the one path where the tree is expected to cost more, isolated and reported honestly.
 *
 * Run through JMH's own runner:
 * {@code java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * io\.evitadb\.spike\.PriceRecordBackingBenchmark}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class PriceRecordBackingBenchmark {

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* =========================================================================================== */

	@Benchmark
	public PriceRecordContract getByIdArray(BackingState state, KeyCursor cursor) {
		final int key = state.lookupKeys[cursor.next(state.lookupKeys.length)];
		final int index = Arrays.binarySearch(state.flatSnapshot, probe(key), BackingState.BY_INTERNAL_PRICE_ID);
		return index < 0 ? null : state.flatSnapshot[index];
	}

	@Benchmark
	public PriceRecordContract getByIdTree(BackingState state, KeyCursor cursor) {
		final int key = state.lookupKeys[cursor.next(state.lookupKeys.length)];
		return state.tree.search(key);
	}

	@Benchmark
	public void filteredLookupArray(BackingState state, Blackhole bh) {
		// resolve every ascending filter id by a binary search over the contiguous sorted array (the positional path)
		final PriceRecordContract[] snapshot = state.flatSnapshot;
		for (final int key : state.filterKeys) {
			final int index = Arrays.binarySearch(snapshot, probe(key), BackingState.BY_INTERNAL_PRICE_ID);
			if (index >= 0) {
				bh.consume(snapshot[index]);
			}
		}
	}

	@Benchmark
	public void filteredLookupTree(BackingState state, Blackhole bh) {
		// resolve the same ascending filter ids by a single forward merge-join over the tree (the production override)
		final int[] filterKeys = state.filterKeys;
		final Iterator<PriceRecordContract> it = state.tree.greaterOrEqualValueIterator(filterKeys[0]);
		PriceRecordContract current = it.hasNext() ? it.next() : null;
		int idCursor = 0;
		while (current != null && idCursor < filterKeys.length) {
			final int recordKey = current.internalPriceId();
			final int wantedKey = filterKeys[idCursor];
			if (recordKey == wantedKey) {
				bh.consume(current);
				idCursor++;
				current = it.hasNext() ? it.next() : null;
			} else if (recordKey < wantedKey) {
				current = it.hasNext() ? it.next() : null;
			} else {
				idCursor++;
			}
		}
	}

	@Benchmark
	public void filteredLookupTreePerId(BackingState state, Blackhole bh) {
		// the production override's SPARSE branch: resolve each scattered filter id by a direct O(log n) tree search
		// instead of a full merge-join walk over every record
		final TransactionalElementBPlusTree<PriceRecordContract> tree = state.tree;
		for (final int key : state.filterKeys) {
			final PriceRecordContract record = tree.search(key);
			if (record != null) {
				bh.consume(record);
			}
		}
	}

	@Benchmark
	public void mutateArray(BackingState state) {
		// net-zero: insert the middle gap key (O(n) shift) then remove it (O(n) shift); size returns to N
		state.flatArray.add(state.mutateRecord);
		state.flatArray.remove(state.mutateRecord);
	}

	@Benchmark
	public void mutateTree(BackingState state) {
		// net-zero: insert the middle gap key (O(log n)) then delete it (O(log n)); size returns to N
		state.tree.insert(state.mutateRecord);
		state.tree.delete(state.mutateKey);
	}

	@Benchmark
	public PriceRecordContract[] toArrayArray(BackingState state) {
		return state.flatArray.getArray();
	}

	@Benchmark
	public PriceRecordContract[] toArrayTree(BackingState state) {
		return state.tree.toArray();
	}

	/* =========================================================================================== */

	/**
	 * Builds a lightweight probe record carrying only the {@code internalPriceId} the
	 * {@link BackingState#BY_INTERNAL_PRICE_ID} comparator binary-searches on.
	 *
	 * @param internalPriceId the key to probe
	 * @return the probe record
	 */
	@Nonnull
	private static PriceRecordContract probe(int internalPriceId) {
		return new PriceRecord(internalPriceId, 0, 0, 0, 0);
	}

	/**
	 * Holds both backings — the contiguous sorted {@link TransactionalObjArray} and the element-keyed
	 * {@link TransactionalElementBPlusTree} — built from the same `distinctValues` records (internal price ids spaced by
	 * 10 so the middle mutation key falls in a gap), plus the precomputed lookup keys, the ascending filter id set and
	 * the middle mutation record.
	 */
	@State(Scope.Benchmark)
	public static class BackingState {

		/** Orders price records by their internal price id — the array's natural / binary-search order. */
		static final Comparator<PriceRecordContract> BY_INTERNAL_PRICE_ID =
			Comparator.comparingInt(PriceRecordContract::internalPriceId);

		/** Internal price ids are spaced by this stride so a between-keys mutation lands in a real gap (a real insert). */
		private static final int KEY_STRIDE = 10;
		/** Number of ascending ids the {@code filteredLookup*} ops resolve — a realistic narrowed query result. */
		private static final int FILTER_SIZE = 1_000;

		@Param({"100000"})
		public int distinctValues;

		public TransactionalElementBPlusTree<PriceRecordContract> tree;
		public TransactionalObjArray<PriceRecordContract> flatArray;
		/** A stable snapshot of the sorted array for the (non-mutating) read ops, so `getArray()` cost is not folded in. */
		public PriceRecordContract[] flatSnapshot;
		public int[] lookupKeys;
		public int[] filterKeys;
		public int mutateKey;
		public PriceRecordContract mutateRecord;

		@Setup(Level.Trial)
		public void setUp() {
			final PriceRecordContract[] records = new PriceRecordContract[this.distinctValues];
			for (int i = 0; i < this.distinctValues; i++) {
				final int key = i * KEY_STRIDE;
				records[i] = new PriceRecord(key, key + 1, i, key * 100 + 21, key * 100);
			}

			this.flatArray = new TransactionalObjArray<>(records.clone(), BY_INTERNAL_PRICE_ID);
			this.flatSnapshot = records.clone();

			final TransactionalElementBPlusTree<PriceRecordContract> theTree = new TransactionalElementBPlusTree<>(
				PriceRecordContract.class, PriceRecordContract::internalPriceId
			);
			for (final PriceRecordContract record : records) {
				theTree.insert(record);
			}
			this.tree = theTree;

			// lookup keys: a deterministic spread of existing ids (rotated through by the per-thread cursor)
			final int lookupCount = Math.min(this.distinctValues, 200_000);
			this.lookupKeys = new int[lookupCount];
			for (int i = 0; i < lookupCount; i++) {
				// floor(i * N / lookupCount) keeps the probes spread across the whole id range, deterministically
				this.lookupKeys[i] = (int) ((long) i * this.distinctValues / lookupCount) * KEY_STRIDE;
			}

			// ascending filter ids: FILTER_SIZE existing ids spread across the range (already sorted)
			this.filterKeys = new int[FILTER_SIZE];
			for (int i = 0; i < FILTER_SIZE; i++) {
				this.filterKeys[i] = (int) ((long) i * this.distinctValues / FILTER_SIZE) * KEY_STRIDE;
			}

			// the mutation key: a gap strictly between the two middle records (not present → a real insert)
			this.mutateKey = (this.distinctValues / 2) * KEY_STRIDE + KEY_STRIDE / 2;
			this.mutateRecord = new PriceRecord(this.mutateKey, this.mutateKey + 1, 0, 0, 0);
		}
	}

	/**
	 * Per-thread rotating cursor over the lookup-key array, so successive invocations probe different keys without a
	 * fresh random draw inside the measured method.
	 */
	@State(Scope.Thread)
	public static class KeyCursor {
		private int position;

		public int next(int bound) {
			int next = this.position + 1;
			if (next >= bound) {
				next = 0;
			}
			this.position = next;
			return next;
		}
	}

}
