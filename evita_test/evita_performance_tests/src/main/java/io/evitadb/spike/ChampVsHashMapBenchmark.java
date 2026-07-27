/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import io.evitadb.dataType.champ.ChampMap;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark comparing {@link java.util.HashMap} against the persistent {@link ChampMap} for the
 * access patterns of {@code OffsetIndex#promoteNonFlushedValuesToSharedState}. The key/value types
 * mirror the real `RecordKey` (a `byte` record type + a `long` primary key) and `FileLocation`
 * (a `long` start position + an `int` length) so hash distribution and entry size are representative.
 *
 * Scenarios measured across a range of base-map sizes `N` (and, for the flush, change-counts `M`):
 *
 * 1. {@code get*} — random successful lookups (the read-heavy steady state). HashMap is expected to
 *    win by a small constant; the question is how small.
 * 2. {@code flush*} — the hotspot, and a faithful proxy for the **transactional commit** of a plain-valued
 *    STM map: produce a *new* map that applies `M ≪ N` changes to an `N`-entry base. This is exactly what
 *    `TransactionalMap.createMergedMap` (the plain map — copy all `N` entries first, `O(N)` time + `O(N)`
 *    garbage + a possibly humongous backing array, then apply the `M` diff entries) versus
 *    `PersistentTransactionalMap.createCopyWithMergedTransactionalMemory` (the {@link ChampMap} — apply the
 *    `M` changes by path-copying in `O(M·log₃₂ N)` while structurally sharing the untouched bulk) do on
 *    commit. The change-count `M` is swept ∈ {1, 10, 100} to chart the win as a function of Δ.
 * 3. {@code build*} / {@code warmUp*} — the **non-transactional warm-up / bulk-import** path. `buildWithHashMap`
 *    is the `HashMap.put` baseline; `buildWithChampMap` seals an `N`-entry map through the CHAMP builder in
 *    one `O(N)` pass (the *staging-buffer mitigation*); {@code warmUpWithChampMapUpdated} is the honest cost
 *    of `PersistentTransactionalMap.put` outside a transaction — one immutable snapshot-replace
 *    (`snapshot = snapshot.updated(k, v)`, `O(log₃₂ N)`) **per write**, which is the regression this path
 *    risks if warm-up is not routed through the builder.
 *
 * The benchmarks jar uses a custom main class, so run through JMH's own runner:
 * {@code java -cp evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * io\.evitadb\.spike\.ChampVsHashMapBenchmark}.
 *
 * Indicative results (AverageTime, µs/op, lower is better — quick 1-fork/3-iteration run, so the
 * error bars are wide; the asymptotic shape is the point, not the absolute constants):
 *
 * ```
 * Benchmark                          (size)  Mode  Cnt     Score       Error  Units
 * flushWithChampMap                   10000  avgt    3    10.307 ±     4.327  us/op
 * flushWithChampMap                  100000  avgt    3    10.520 ±     0.473  us/op   ← flat in N
 * flushWithHashMap                    10000  avgt    3   101.855 ±    19.435  us/op
 * flushWithHashMap                   100000  avgt    3  1465.207 ±  6803.542  us/op   ← grows ~O(N)
 * getFromChampMap                     10000  avgt    3     0.044 ±     0.022  us/op
 * getFromChampMap                    100000  avgt    3     0.054 ±     0.044  us/op
 * getFromHashMap                      10000  avgt    3     0.007 ±     0.005  us/op
 * getFromHashMap                     100000  avgt    3     0.010 ±     0.014  us/op
 * buildWithChampMap                   10000  avgt    3   666.652 ±  4524.569  us/op
 * buildWithChampMap                  100000  avgt    3  8330.209 ± 20764.385  us/op
 * buildWithHashMap                    10000  avgt    3    86.785 ±    32.708  us/op
 * buildWithHashMap                   100000  avgt    3  2375.096 ± 21692.543  us/op
 * ```
 *
 * Reading the table: the flush cost of {@link ChampMap} is essentially constant (~10 µs for 100
 * changes) regardless of base size, while the plain-map flush grows linearly with N (≈14× for a 10×
 * size increase) — exactly the `O(N)` copy this work removes. Reads are a few × slower on the trie
 * but both are tens of nanoseconds. From-scratch build via the builder is ~3.5× slower than filling
 * a {@code HashMap}, but that is a one-time catalog-load cost (not the per-flush hotspot).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class ChampVsHashMapBenchmark {

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* =========================================================================================== */

	@Benchmark
	public Integer getFromHashMap(MapState state, KeyCursor cursor) {
		final RecordKey key = state.lookupKeys[cursor.next(state.lookupKeys.length)];
		final FileLocation location = state.hashMapBase.get(key);
		return location == null ? null : location.recordLength();
	}

	@Benchmark
	public Integer getFromChampMap(MapState state, KeyCursor cursor) {
		final RecordKey key = state.lookupKeys[cursor.next(state.lookupKeys.length)];
		final FileLocation location = state.champMapBase.get(key);
		return location == null ? null : location.recordLength();
	}

	/* =========================================================================================== */

	@Benchmark
	public Map<RecordKey, FileLocation> flushWithHashMap(MapState state) {
		// the plain-map flush: copy every entry, then apply the M changes
		final Map<RecordKey, FileLocation> next = new HashMap<>(state.hashMapBase.size() + state.changesPerFlush);
		next.putAll(state.hashMapBase);
		final ChangeOp[] changes = state.changes;
		for (int i = 0; i < changes.length; i++) {
			final ChangeOp change = changes[i];
			if (change.remove()) {
				next.remove(change.key());
			} else {
				next.put(change.key(), change.value());
			}
		}
		return next;
	}

	@Benchmark
	public ChampMap<RecordKey, FileLocation> flushWithChampMap(MapState state) {
		// the persistent flush: path-copy the M changes over the shared base, no bulk copy
		ChampMap<RecordKey, FileLocation> next = state.champMapBase;
		final ChangeOp[] changes = state.changes;
		for (int i = 0; i < changes.length; i++) {
			final ChangeOp change = changes[i];
			if (change.remove()) {
				next = next.removed(change.key());
			} else {
				next = next.updated(change.key(), change.value());
			}
		}
		return next;
	}

	/* =========================================================================================== */

	@Benchmark
	public Map<RecordKey, FileLocation> buildWithHashMap(MapState state) {
		final RecordKey[] keys = state.lookupKeys;
		final FileLocation[] values = state.values;
		final Map<RecordKey, FileLocation> map = new HashMap<>(keys.length);
		for (int i = 0; i < keys.length; i++) {
			map.put(keys[i], values[i]);
		}
		return map;
	}

	@Benchmark
	public ChampMap<RecordKey, FileLocation> buildWithChampMap(MapState state) {
		final RecordKey[] keys = state.lookupKeys;
		final FileLocation[] values = state.values;
		final ChampMap.Builder<RecordKey, FileLocation> builder = ChampMap.builder();
		for (int i = 0; i < keys.length; i++) {
			builder.add(keys[i], values[i]);
		}
		return builder.build();
	}

	@Benchmark
	public ChampMap<RecordKey, FileLocation> warmUpWithChampMapUpdated(MapState state) {
		// the NAIVE non-transactional path the staging buffer avoids: a fresh immutable snapshot per write
		// (O(log N) each), so the whole warm-up is O(N·log N) and allocates N intermediate maps
		final RecordKey[] keys = state.lookupKeys;
		final FileLocation[] values = state.values;
		ChampMap<RecordKey, FileLocation> map = ChampMap.empty();
		for (int i = 0; i < keys.length; i++) {
			map = map.updated(keys[i], values[i]);
		}
		return map;
	}

	@Benchmark
	public ChampMap<RecordKey, FileLocation> warmUpWithStagingBuffer(MapState state) {
		// the ACTUAL PersistentTransactionalMap warm-up cost with the staging buffer: O(1) HashMap.put per
		// write while thawed, then a single O(M) seal to a ChampMap on the first transactional touch
		final RecordKey[] keys = state.lookupKeys;
		final FileLocation[] values = state.values;
		final Map<RecordKey, FileLocation> buffer = new HashMap<>(keys.length);
		for (int i = 0; i < keys.length; i++) {
			buffer.put(keys[i], values[i]);
		}
		return ChampMap.from(buffer);
	}

	/* =========================================================================================== */

	/**
	 * Holds the pre-built maps, the distinct keys/values populating them and a fixed set of changes
	 * used by the flush benchmark.
	 */
	@State(Scope.Benchmark)
	public static class MapState {

		@Param({"1000", "10000", "100000", "1000000"})
		public int size;

		/** Number of changes applied per simulated flush/commit — swept to chart the win as a function of Δ. */
		@Param({"1", "10", "100"})
		public int changesPerFlush;

		public RecordKey[] lookupKeys;
		public FileLocation[] values;
		public ChangeOp[] changes;
		public Map<RecordKey, FileLocation> hashMapBase;
		public ChampMap<RecordKey, FileLocation> champMapBase;

		@Setup(Level.Trial)
		public void setUp() {
			final Random random = new Random(42);
			this.lookupKeys = new RecordKey[this.size];
			this.values = new FileLocation[this.size];
			this.hashMapBase = new HashMap<>(this.size);
			final ChampMap.Builder<RecordKey, FileLocation> builder = ChampMap.builder();

			long position = 0;
			for (int i = 0; i < this.size; i++) {
				final RecordKey key = new RecordKey((byte) (i % 8), i);
				final FileLocation value = new FileLocation(position, 64 + (i % 256));
				position += value.recordLength();
				this.lookupKeys[i] = key;
				this.values[i] = value;
				this.hashMapBase.put(key, value);
				builder.add(key, value);
			}
			this.champMapBase = builder.build();

			// half of the changes update existing keys, half insert brand-new ones; a few removals
			this.changes = new ChangeOp[this.changesPerFlush];
			for (int i = 0; i < this.changesPerFlush; i++) {
				final boolean existing = (i % 2) == 0;
				final boolean remove = (i % 10) == 0;
				final RecordKey key = existing
					? this.lookupKeys[random.nextInt(this.size)]
					: new RecordKey((byte) (i % 8), (long) this.size + i);
				final FileLocation value = new FileLocation(position, 64 + (i % 256));
				position += value.recordLength();
				this.changes[i] = new ChangeOp(key, value, remove && existing);
			}
		}
	}

	/**
	 * Thread-local rotating cursor over the lookup-key array, so successive `get` invocations hit
	 * different keys without per-invocation randomness.
	 */
	@State(Scope.Thread)
	public static class KeyCursor {
		private int index;

		public int next(int bound) {
			int next = this.index + 1;
			if (next >= bound) {
				next = 0;
			}
			this.index = next;
			return next;
		}
	}

	/** A single pending change applied during a simulated flush. */
	public record ChangeOp(RecordKey key, FileLocation value, boolean remove) {
	}

	/** Mirrors `io.evitadb.store.offsetIndex.model.RecordKey` — a record type plus a primary key. */
	public record RecordKey(byte recordType, long primaryKey) {

		@Override
		public int hashCode() {
			int result = this.recordType;
			result = 31 * result + Long.hashCode(this.primaryKey);
			return result;
		}
	}

	/** Mirrors `io.evitadb.store.offsetIndex.model.FileLocation` — a start position plus a length. */
	public record FileLocation(long startingPosition, int recordLength) {
	}
}
