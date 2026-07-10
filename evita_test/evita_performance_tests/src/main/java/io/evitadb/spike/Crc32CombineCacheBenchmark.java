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

import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Microbenchmark that settles the design questions for the CRC32C combine power-matrix cache
 * (plan `docs/plans/optimizations/crc32c-combine-instance-and-matrix-cache.md`). It reproduces the
 * exact GF(2) math of {@link io.evitadb.utils.Crc32CWrapper#combine(long, long, long)} (cross-checked
 * against the production method in {@link #verifyCorrectness()}), then measures the per-record combine
 * cost of every candidate storage strategy on a fully-warmed cache — i.e. the steady-state hot path of
 * `ObservableOutput.finishRecord`, where every distinct record length has already been seen.
 *
 * Candidates measured (all produce the bit-identical CRC32C value):
 *
 * 1. `recompute`             — the CURRENT static `combine`: allocate `int[32]×2`, rebuild the power
 *                              matrix via binary exponentiation on every call. The baseline the plan
 *                              removes.
 * 2. `ladderNoCache`         — NO per-length cache at all: a single workload-independent static
 *                              "doubling ladder" `L[j] = M^(8·2^j)`, applied to `crc1` bit-by-bit.
 *                              Per call = `popcount(len2)` matrix×vector products, zero map lookups,
 *                              zero per-length memory, zero unbounded growth. This benchmark uses a
 *                              32-rung ladder (4 KB, covers int lengths); production should use 64
 *                              rungs (8 KB) for full `long` `len2` coverage.
 * 3. `hashMapLong`           — the plan's design: per-instance `HashMap<Long, int[]>` (boxes a `Long`
 *                              key on every call).
 * 4. `hashMapInt`            — per-instance `HashMap<Integer, int[]>` (boxes an `Integer`).
 * 5. `openIntMap`           — per-instance open-addressing `int`→`int[]` map (no boxing, self-contained).
 * 6. `denseArray`            — per-instance `int[][]` indexed directly by length (no hash, no boxing);
 *                              the fastest possible hit path.
 * 7. `chmSharedInt`          — a single process-wide static `ConcurrentHashMap<Integer, int[]>`
 *                              (the "single static shared" option Johnny asked about).
 * 8. `denseArraySharedAtomic`— a single process-wide static `AtomicReferenceArray<int[]>` (shared,
 *                              no hash, no boxing, safe publication).
 *
 * The `(distinctLengths)` param sweeps how many distinct record sizes the workload cycles through
 * (1 = pure fixed-size records; 2048 = a wide size spread that stresses map size and hit-path
 * lookup). For every strategy except `recompute` the cache is fully pre-populated in {@link Setup#setUp()},
 * so the measured op is always a cache HIT — isolating pure lookup + `gf2MatrixTimes` overhead.
 *
 * Reading the results:
 * - `recompute` vs everything else = the headline CPU win (the 9 %→~1.5 % claim, as a ratio).
 * - `hashMapLong` vs `hashMapInt` vs `openIntMap` vs `denseArray` = the cost of boxing + hashing on
 *   the hit path (Q2: does the container choice matter?).
 * - `hashMapInt`/`openIntMap` (per-instance) vs `chmSharedInt` (static shared) = the CPU cost of a
 *   single shared `ConcurrentHashMap` vs a thread-confined map (Q2, the real number).
 * - `ladderNoCache` vs the cache hits = whether the cache is even worth its memory: if the ladder is
 *   within a few ns of a hit, the best solution keeps ZERO per-length state (settles Q3 by construction).
 *
 * Run standalone (single-thread hot path):
 * {@code java -cp evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 *   io\.evitadb\.spike\.Crc32CombineCacheBenchmark}
 *
 * Add {@code -prof gc} for the allocation dimension, and {@code -t 4} to observe shared-cache
 * (`chmSharedInt` / `denseArraySharedAtomic`) read scaling across threads.
 *
 * Measured results (JDK 21, AverageTime, ns/op + gc.alloc.rate.norm B/op, 2 forks × 6 iterations;
 * `distinctLengths` = number of distinct record sizes cycled, cache 100 % warm):
 *
 * ```
 * strategy                    ns/op (d=1)   ns/op (d=128)   B/op (d=128)   per-length memory
 * recompute (TODAY)              1112.5         1903.7          288         none
 * ladderNoCache                    39.3           89.0            0         NONE (4-8 KB static)
 * denseArray (per-instance)        13.6           13.6            0         O(maxLen) / instance
 * denseArraySharedAtomic           14.2           14.6            0         O(maxLen) shared
 * openIntMap (per-instance)        13.9           16.9            0         unbounded / instance
 * hashMapInt (per-instance)        14.1           16.8         14.5         unbounded / instance
 * chmSharedInt (static shared)     14.4           16.9         14.5         unbounded shared
 * hashMapLong (the plan's design)  14.3           17.7        21.75         unbounded / instance
 * ```
 *
 * Conclusions that drive the design:
 * 1. ANY strategy beats `recompute` by 20–130× and drops 288 B/op → ~0. The combine hotspot is
 *    removed regardless of which is chosen.
 * 2. The container choice is a ~0–4 ns effect on a ~15 ns op — the `gf2MatrixTimes` floor (~13.6 ns,
 *    the `denseArray` number) dominates. A static shared `ConcurrentHashMap` (16.9 ns) is NOT slower
 *    than the plan's per-instance `HashMap` (16.8–17.7 ns): the plan's stated reason for rejecting
 *    the shared cache does not hold.
 * 3. Boxing is the real cost, and it is not free: under a realistic distinct-length count the plan's
 *    `HashMap<Long,int[]>` allocates 21.75 B/op and `HashMap<Integer>`/CHM 14.5 B/op — reintroducing
 *    per-record churn into a 31 %-GC-bound path. Box-free options (`ladderNoCache`, `denseArray`,
 *    `openIntMap`, `AtomicReferenceArray`) allocate zero.
 * 4. `ladderNoCache` keeps ZERO per-length state (a fixed 8 KB static immutable ladder), allocates
 *    zero, handles arbitrary/large `len2` without a dense-array blow-up, and is still 20–48× faster
 *    than today. It is only 3–6× slower than a warm cache hit — worth ~0.4 % of the ALIVE run's
 *    wall-clock (est. below) — so it captures essentially the whole win with none of the cache's
 *    boxing / eviction / per-instance-vs-static / unbounded-memory design surface. THIS is the
 *    recommended solution; the length-indexed / open-int-map cache is the "squeeze the last few ns"
 *    fallback, and the plan's `HashMap<Long>` is dominated on every axis.
 *
 * ALIVE wall-clock estimate: combine is ~11.8 % CPU of the unique/ALIVE run (~57 s of a ~480 s run
 * at ~1500 ns/call ≈ 38 M calls). `ladderNoCache` ≈ 65 ns/call → ~2.5 s (0.5 % of run); a warm cache
 * ≈ 15 ns/call → ~0.6 s (0.12 %). The cache's edge over the ladder is ~1.9 s ≈ 0.4 % of wall-clock.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class Crc32CombineCacheBenchmark {

	/** Reflected CRC-32C (Castagnoli) polynomial — mirrors {@code Crc32CWrapper.CRC32C_POLY}. */
	private static final int CRC32C_POLY = 0x82F63B78;

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* =============================== the eight candidate strategies ================================ */

	@Benchmark
	public long recompute(WorkloadState workload, Cursor cursor) {
		final int i = cursor.next(workload.lengths.length);
		return combineRecompute(cursor.crc(), CRC_2, workload.lengths[i]);
	}

	@Benchmark
	public long ladderNoCache(WorkloadState workload, Cursor cursor) {
		final int i = cursor.next(workload.lengths.length);
		return combineViaLadder(cursor.crc(), CRC_2, workload.lengths[i]);
	}

	@Benchmark
	public long hashMapLong(WorkloadState workload, PerInstanceState instance, Cursor cursor) {
		final int i = cursor.next(workload.lengths.length);
		final long len = workload.lengths[i];
		final int[] matrix = instance.hashMapLong.get(len);
		return (gf2MatrixTimes(matrix, cursor.crc()) ^ CRC_2) & 0xFFFFFFFFL;
	}

	@Benchmark
	public long hashMapInt(WorkloadState workload, PerInstanceState instance, Cursor cursor) {
		final int i = cursor.next(workload.lengths.length);
		final int len = (int) workload.lengths[i];
		final int[] matrix = instance.hashMapInt.get(len);
		return (gf2MatrixTimes(matrix, cursor.crc()) ^ CRC_2) & 0xFFFFFFFFL;
	}

	@Benchmark
	public long openIntMap(WorkloadState workload, PerInstanceState instance, Cursor cursor) {
		final int i = cursor.next(workload.lengths.length);
		final int len = (int) workload.lengths[i];
		final int[] matrix = instance.openIntMap.get(len);
		return (gf2MatrixTimes(matrix, cursor.crc()) ^ CRC_2) & 0xFFFFFFFFL;
	}

	@Benchmark
	public long denseArray(WorkloadState workload, PerInstanceState instance, Cursor cursor) {
		final int i = cursor.next(workload.lengths.length);
		final int len = (int) workload.lengths[i];
		final int[] matrix = instance.denseArray[len];
		return (gf2MatrixTimes(matrix, cursor.crc()) ^ CRC_2) & 0xFFFFFFFFL;
	}

	@Benchmark
	public long chmSharedInt(WorkloadState workload, Cursor cursor) {
		final int i = cursor.next(workload.lengths.length);
		final int len = (int) workload.lengths[i];
		final int[] matrix = SHARED_CHM.get(len);
		return (gf2MatrixTimes(matrix, cursor.crc()) ^ CRC_2) & 0xFFFFFFFFL;
	}

	@Benchmark
	public long denseArraySharedAtomic(WorkloadState workload, Cursor cursor) {
		final int i = cursor.next(workload.lengths.length);
		final int len = (int) workload.lengths[i];
		final int[] matrix = SHARED_ATOMIC.get(len);
		return (gf2MatrixTimes(matrix, cursor.crc()) ^ CRC_2) & 0xFFFFFFFFL;
	}

	/* ============================== shared static caches (Scope.Benchmark) ========================= */

	/** A single process-wide combine cache — the "single static shared ConcurrentHashMap" candidate. */
	private static final ConcurrentHashMap<Integer, int[]> SHARED_CHM = new ConcurrentHashMap<>();
	/** A single process-wide combine cache indexed directly by length — shared, hash-free, box-free. */
	private static AtomicReferenceArray<int[]> SHARED_ATOMIC;

	/* ===================================== workload + state ======================================== */

	/**
	 * Holds the set of distinct record lengths the workload cycles through and pre-populates the two
	 * process-wide static caches (which are shared across all threads of the trial).
	 */
	@State(Scope.Benchmark)
	public static class WorkloadState {

		/** How many distinct record sizes the workload cycles through (cache hit rate is 100 %). */
		@Param({"1", "8", "128", "2048"})
		public int distinctLengths;

		/** The distinct record lengths, laid out in a fixed pseudo-random cycle order. */
		public long[] lengths;
		/** Maximum length + 1 — the size of the dense arrays. */
		public int maxLenExclusive;

		@Setup(Level.Trial)
		public void setUp() {
			verifyCorrectness();

			// Generate `distinctLengths` distinct sizes spread over a realistic range: a floor of small
			// record sizes (entity bodies ~44 B, attributes ~84 B) plus a spread up toward leaf-page
			// sizes. Step keeps the dense-array skeleton bounded (~8× distinctLengths at most).
			final Random random = new Random(42);
			this.lengths = new long[Math.max(distinctLengths, 1)];
			int maxLen = 0;
			for (int i = 0; i < this.lengths.length; i++) {
				final int len = 32 + i * 8 + random.nextInt(8);
				this.lengths[i] = len;
				if (len >= maxLen) {
					maxLen = len + 1;
				}
			}
			this.maxLenExclusive = maxLen;
			// shuffle so successive combine calls don't hit a monotonic length pattern
			for (int i = this.lengths.length - 1; i > 0; i--) {
				final int j = random.nextInt(i + 1);
				final long tmp = this.lengths[i];
				this.lengths[i] = this.lengths[j];
				this.lengths[j] = tmp;
			}

			// (re)build the process-wide shared caches for THIS param value (JMH runs params in the
			// same JVM sequentially, so clear first to avoid cross-param contamination).
			SHARED_CHM.clear();
			SHARED_ATOMIC = new AtomicReferenceArray<>(this.maxLenExclusive);
			for (final long len : this.lengths) {
				final int[] matrix = computePowerMatrix(len);
				SHARED_CHM.put((int) len, matrix);
				SHARED_ATOMIC.set((int) len, matrix);
			}
		}
	}

	/**
	 * Per-thread combine caches (mirrors production: each {@code Crc32CWrapper} is thread-confined).
	 * Pre-populated so every measured op is a cache hit.
	 */
	@State(Scope.Thread)
	public static class PerInstanceState {

		public HashMap<Long, int[]> hashMapLong;
		public HashMap<Integer, int[]> hashMapInt;
		public IntMatrixMap openIntMap;
		public int[][] denseArray;

		@Setup(Level.Trial)
		public void setUp(WorkloadState workload) {
			this.hashMapLong = new HashMap<>();
			this.hashMapInt = new HashMap<>();
			this.openIntMap = new IntMatrixMap(workload.lengths.length);
			this.denseArray = new int[workload.maxLenExclusive][];
			for (final long len : workload.lengths) {
				final int[] matrix = computePowerMatrix(len);
				this.hashMapLong.put(len, matrix);
				this.hashMapInt.put((int) len, matrix);
				this.openIntMap.put((int) len, matrix);
				this.denseArray[(int) len] = matrix;
			}
		}
	}

	/**
	 * Minimal box-free open-addressing {@code int}→{@code int[]} map (linear probing, power-of-two
	 * capacity). Self-contained so the benchmark needs no external primitive-collection dependency
	 * (the codebase does not use fastutil). Only {@code put}/{@code get} are implemented — enough for
	 * the warm-cache read path measured here. Keys are record lengths (never 0 in the hot path), so a
	 * 0 key slot doubles as "empty".
	 */
	static final class IntMatrixMap {
		private final int[] keys;
		private final int[][] values;
		private final int mask;

		IntMatrixMap(int expected) {
			int cap = Integer.highestOneBit(Math.max(4, expected) * 2 - 1) << 1;
			this.keys = new int[cap];
			this.values = new int[cap][];
			this.mask = cap - 1;
		}

		void put(int key, int[] value) {
			int i = key & this.mask;
			while (this.keys[i] != 0 && this.keys[i] != key) {
				i = (i + 1) & this.mask;
			}
			this.keys[i] = key;
			this.values[i] = value;
		}

		int[] get(int key) {
			int i = key & this.mask;
			int k;
			while ((k = this.keys[i]) != 0) {
				if (k == key) {
					return this.values[i];
				}
				i = (i + 1) & this.mask;
			}
			return null;
		}
	}

	/** Fixed second operand + a rotating first operand, so each op feeds a fresh, realistic crc1. */
	private static final int CRC_2 = 0x1A2B3C4D;

	@State(Scope.Thread)
	public static class Cursor {
		private int index;
		private int crc = 0x89ABCDEF;

		public int next(int bound) {
			int next = this.index + 1;
			if (next >= bound) {
				next = 0;
			}
			this.index = next;
			return next;
		}

		/** Advances a cheap xorshift so successive combine calls see varying crc1 popcounts. */
		public int crc() {
			int x = this.crc;
			x ^= x << 13;
			x ^= x >>> 17;
			x ^= x << 5;
			this.crc = x;
			return x;
		}
	}

	/* ================================= GF(2) primitives (self-contained) =========================== */

	/**
	 * The current production behaviour: allocate two scratch matrices and rebuild the power matrix via
	 * binary exponentiation on every call. Byte-for-byte identical logic to
	 * {@code Crc32CWrapper.combineInternal}.
	 */
	private static long combineRecompute(long crc1v, long crc2v, long len2) {
		if (len2 <= 0) {
			return crc1v & 0xFFFFFFFFL;
		}
		int crc1 = (int) crc1v;
		final int crc2 = (int) crc2v;
		final int[] odd = new int[32];
		final int[] even = new int[32];
		odd[0] = CRC32C_POLY;
		int row = 1;
		for (int i = 1; i < 32; i++) {
			odd[i] = row;
			row <<= 1;
		}
		gf2MatrixSquare(even, odd);
		gf2MatrixSquare(odd, even);
		do {
			gf2MatrixSquare(even, odd);
			if ((len2 & 1L) != 0) {
				crc1 = gf2MatrixTimes(even, crc1);
			}
			len2 >>= 1;
			if (len2 == 0) {
				break;
			}
			gf2MatrixSquare(odd, even);
			if ((len2 & 1L) != 0) {
				crc1 = gf2MatrixTimes(odd, crc1);
			}
			len2 >>= 1;
		} while (len2 != 0);
		return (crc1 ^ crc2) & 0xFFFFFFFFL;
	}

	/** The doubling ladder L[j] = M^(8·2^j) — workload-independent, immutable, ~4 KB. */
	private static final int[][] LADDER = buildLadder();

	private static int[][] buildLadder() {
		// M = operator for one zero bit
		final int[] m = new int[32];
		m[0] = CRC32C_POLY;
		int row = 1;
		for (int i = 1; i < 32; i++) {
			m[i] = row;
			row <<= 1;
		}
		// L[0] = M^8 (three squarings), L[j] = square(L[j-1]) = M^(8·2^j)
		final int[][] ladder = new int[32][];
		final int[] m2 = new int[32];
		final int[] m4 = new int[32];
		final int[] m8 = new int[32];
		gf2MatrixSquare(m2, m);
		gf2MatrixSquare(m4, m2);
		gf2MatrixSquare(m8, m4);
		ladder[0] = m8;
		for (int j = 1; j < 32; j++) {
			final int[] next = new int[32];
			gf2MatrixSquare(next, ladder[j - 1]);
			ladder[j] = next;
		}
		return ladder;
	}

	/** Combine using the shared ladder, no per-length cache: popcount(len2) matrix×vector products. */
	private static long combineViaLadder(long crc1v, long crc2v, long len2) {
		if (len2 <= 0) {
			return crc1v & 0xFFFFFFFFL;
		}
		int v = (int) crc1v;
		int j = 0;
		long remaining = len2;
		while (remaining != 0) {
			if ((remaining & 1L) != 0) {
				v = gf2MatrixTimes(LADDER[j], v);
			}
			remaining >>= 1;
			j++;
		}
		return (v ^ (int) crc2v) & 0xFFFFFFFFL;
	}

	/**
	 * Builds the standalone power matrix M^(8·len2) (the value cached by every cache strategy). Uses
	 * the ladder for a fast, allocation-light build; the value is bit-identical regardless of build
	 * path (powers of M commute).
	 */
	private static int[] computePowerMatrix(long len2) {
		// identity matrix
		final int[] result = new int[32];
		int row = 1;
		for (int i = 0; i < 32; i++) {
			result[i] = row;
			row <<= 1;
		}
		int j = 0;
		long remaining = len2;
		while (remaining != 0) {
			if ((remaining & 1L) != 0) {
				gf2MatrixMultiplyInto(result, LADDER[j]);
			}
			remaining >>= 1;
			j++;
		}
		return result;
	}

	/** In-place {@code result = operand × result} (32×32 GF(2) matrix multiply). */
	private static void gf2MatrixMultiplyInto(int[] result, int[] operand) {
		for (int i = 0; i < 32; i++) {
			result[i] = gf2MatrixTimes(operand, result[i]);
		}
	}

	private static int gf2MatrixTimes(int[] mat, int vec) {
		int sum = 0;
		while (vec != 0) {
			sum ^= mat[Integer.numberOfTrailingZeros(vec)];
			vec &= vec - 1;
		}
		return sum;
	}

	private static void gf2MatrixSquare(int[] square, int[] mat) {
		for (int i = 0; i < 32; i++) {
			square[i] = gf2MatrixTimes(mat, mat[i]);
		}
	}

	/**
	 * Fails loudly at trial setup if any of this benchmark's combine paths diverges from the production
	 * {@link io.evitadb.utils.Crc32CWrapper#combine(long, long, long)} — the empirical guarantee that we
	 * are benchmarking the correct math (and, incidentally, the §4.1/§4.4 cache-correctness proof).
	 */
	private static void verifyCorrectness() {
		final Random random = new Random(7);
		for (int t = 0; t < 20_000; t++) {
			final long crc1 = random.nextInt() & 0xFFFFFFFFL;
			final long crc2 = random.nextInt() & 0xFFFFFFFFL;
			final long len = random.nextInt(65_536);
			final long expected = io.evitadb.utils.Crc32CWrapper.combine(crc1, crc2, len);
			check("recompute", expected, combineRecompute(crc1, crc2, len), crc1, crc2, len);
			check("ladder", expected, combineViaLadder(crc1, crc2, len), crc1, crc2, len);
			if (len > 0) {
				final int[] matrix = computePowerMatrix(len);
				final long cached = (gf2MatrixTimes(matrix, (int) crc1) ^ (int) crc2) & 0xFFFFFFFFL;
				check("matrix", expected, cached, crc1, crc2, len);
			}
		}
	}

	private static void check(String path, long expected, long actual, long crc1, long crc2, long len) {
		if (expected != actual) {
			throw new IllegalStateException(
				"combine mismatch (" + path + "): crc1=" + crc1 + " crc2=" + crc2 + " len=" + len +
					" expected=" + expected + " actual=" + actual
			);
		}
	}
}
