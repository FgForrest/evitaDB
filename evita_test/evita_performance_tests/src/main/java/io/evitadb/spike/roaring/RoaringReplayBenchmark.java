/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.spike.roaring;

import io.evitadb.roaringbitmap.Util;
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

import javax.annotation.Nonnull;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The same kernels as {@link RoaringKernelBenchmark}, replayed over the container operand pairs a census of the
 * production retail catalog captured while a realistic query mix ran.
 *
 * The synthetic grid explains the mechanism; this one says what the mechanism is worth. The two answer different
 * questions and a winner in one is not a winner in the other - the census found a median of **four** values on
 * the small side of an array intersection, which is below the block size of every vector kernel in the family.
 *
 * ## What the census measured, and what is replayed here
 *
 * - `lazyIOR(Bitmap, Array)` is **92.7 %** of all container operations, a scatter of a median-4-value array into
 *   an already-populated bitmap. Group 3.
 * - `iand(Array, Array)` is 3.4 %, with the smaller side at 16 values or fewer in 76 % of cases and at exactly
 *   one value in 28 %. Group 1.
 * - array-against-bitmap probes are 3.0 %, a median of 30 values against a bitmap of about 12,000. Group 2.
 * - `iand(Bitmap, Bitmap)` is 0.23 %. Group 4.
 *
 * ## Two things to read carefully before quoting a number
 *
 * **Group 2 merges three combinations that share a shape, not an implementation.** `iand(Array, Bitmap)`,
 * `iand(Bitmap, Array)` and `andNot(Bitmap, Array)` all present one sorted `char[]` and one 1024-word bitmap, and
 * that is the fixture. What the engine does with them differs - `andNot` clears bits rather than counting them -
 * so the arms below price **the probe kernel on real operand shapes**, not the three operations end to end.
 *
 * **Group 3 accumulates in place.** A scatter that ran against a pristine bitmap every invocation would spend
 * most of its time in the 2.4 MB copy rather than in the kernel. The bitmaps are restored from a pristine copy
 * once per iteration instead, so bits accumulate within an iteration. That changes the data, not the work: the
 * read-modify-write executes whether or not the bit was already set.
 *
 * Each benchmark processes **every pair of its group** in one invocation, so a score is the batch and the report
 * divides it by the pair count. A per-pair benchmark would have measured JMH's own loop.
 *
 * Run:
 * {@code java --add-modules jdk.incubator.vector -jar .../benchmarks.jar RoaringReplayBenchmark
 * -jvmArgsAppend "--add-modules=jdk.incubator.vector"}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class RoaringReplayBenchmark {

	/**
	 * `"ROAC"` - the dump's magic number.
	 */
	private static final int MAGIC = 0x524F4143;
	/**
	 * Operation id of `iand` in the census hook's numbering.
	 */
	private static final int OP_IAND = 1;
	/**
	 * Operation id of `lazyIOR`.
	 */
	private static final int OP_LAZY_IOR = 4;
	/**
	 * Operation id of `andNot`.
	 */
	private static final int OP_AND_NOT = 6;
	/**
	 * Container type id of an array container.
	 */
	private static final int TYPE_ARRAY = 0;
	/**
	 * Container type id of a bitmap container.
	 */
	private static final int TYPE_BITMAP = 1;

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* ====================================================== group 1: iand(Array, Array), 3.4 % ============ */

	/**
	 * Today's path: `Util.unsignedIntersect2by2` materialising into a buffer, which is what
	 * `ArrayContainer.iand(ArrayContainer)` calls, galloping dispatch included.
	 *
	 * @param corpus the replayed operands
	 * @return the summed intersection cardinality, so nothing can be optimised away
	 */
	@Benchmark
	public int aaTodayMaterialise(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.arrayPairs; i++) {
			final char[] left = corpus.arrayLeft[i];
			final char[] right = corpus.arrayRight[i];
			total += Util.unsignedIntersect2by2(left, left.length, right, right.length, corpus.intersectOut);
		}
		return total;
	}

	/**
	 * Today's counting path: `ArrayContainer.andCardinality(ArrayContainer)`, which never consults the galloping
	 * dispatcher.
	 *
	 * @param corpus the replayed operands
	 * @return the summed intersection cardinality
	 */
	@Benchmark
	public int aaTodayCount(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.arrayPairs; i++) {
			final char[] left = corpus.arrayLeft[i];
			final char[] right = corpus.arrayRight[i];
			total += Util.unsignedLocalIntersect2by2Cardinality(left, left.length, right, right.length);
		}
		return total;
	}

	/**
	 * Variant H counting, with the shorter side walked.
	 *
	 * @param corpus the replayed operands
	 * @return the summed intersection cardinality
	 */
	@Benchmark
	public int aaBroadcastCount(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.arrayPairs; i++) {
			final char[] left = corpus.arrayLeft[i];
			final char[] right = corpus.arrayRight[i];
			total += ArrayIntersectKernels.broadcastProbeOrientedCardinality(
				left, left.length, right, right.length
			);
		}
		return total;
	}

	/**
	 * Variant H materialising, with the shorter side walked.
	 *
	 * @param corpus the replayed operands
	 * @return the summed number of values written
	 */
	@Benchmark
	public int aaBroadcastMaterialise(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.arrayPairs; i++) {
			final char[] left = corpus.arrayLeft[i];
			final char[] right = corpus.arrayRight[i];
			total += ArrayIntersectKernels.broadcastProbeOrientedIntersect(
				left, left.length, right, right.length, corpus.intersectOut
			);
		}
		return total;
	}

	/**
	 * Variant H512b counting - the reloading formulation, with the shorter side walked.
	 *
	 * @param corpus the replayed operands
	 * @return the summed intersection cardinality
	 */
	@Benchmark
	public int aaBroadcastReloadingCount(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.arrayPairs; i++) {
			final char[] left = corpus.arrayLeft[i];
			final char[] right = corpus.arrayRight[i];
			total += ArrayIntersectKernels.broadcastProbeReloadingOrientedCardinality(
				left, left.length, right, right.length
			);
		}
		return total;
	}

	/**
	 * Variant H512b materialising.
	 *
	 * @param corpus the replayed operands
	 * @return the summed number of values written
	 */
	@Benchmark
	public int aaBroadcastReloadingMaterialise(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.arrayPairs; i++) {
			final char[] left = corpus.arrayLeft[i];
			final char[] right = corpus.arrayRight[i];
			total += ArrayIntersectKernels.broadcastProbeReloadingOrientedIntersect(
				left, left.length, right, right.length, corpus.intersectOut
			);
		}
		return total;
	}

	/**
	 * Variant H512c counting - the cached formulation, with the shorter side walked.
	 *
	 * @param corpus the replayed operands
	 * @return the summed intersection cardinality
	 */
	@Benchmark
	public int aaBroadcastCachedCount(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.arrayPairs; i++) {
			final char[] left = corpus.arrayLeft[i];
			final char[] right = corpus.arrayRight[i];
			total += ArrayIntersectKernels.broadcastProbeCachedOrientedCardinality(
				left, left.length, right, right.length
			);
		}
		return total;
	}

	/**
	 * Variant H512c materialising.
	 *
	 * @param corpus the replayed operands
	 * @return the summed number of values written
	 */
	@Benchmark
	public int aaBroadcastCachedMaterialise(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.arrayPairs; i++) {
			final char[] left = corpus.arrayLeft[i];
			final char[] right = corpus.arrayRight[i];
			total += ArrayIntersectKernels.broadcastProbeCachedOrientedIntersect(
				left, left.length, right, right.length, corpus.intersectOut
			);
		}
		return total;
	}

	/**
	 * Variant Ds counting - the all-pairs kernel that degrades to the scalar merge below eight elements, which
	 * is where most of this corpus lives.
	 *
	 * @param corpus the replayed operands
	 * @return the summed intersection cardinality
	 */
	@Benchmark
	public int aaSummedCount(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.arrayPairs; i++) {
			final char[] left = corpus.arrayLeft[i];
			final char[] right = corpus.arrayRight[i];
			total += ArrayIntersectKernels.allPairsSummedCardinality(left, left.length, right, right.length);
		}
		return total;
	}

	/**
	 * Variant B counting - the branch-free merge, the best scalar alternative.
	 *
	 * @param corpus the replayed operands
	 * @return the summed intersection cardinality
	 */
	@Benchmark
	public int aaBranchFreeCount(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.arrayPairs; i++) {
			final char[] left = corpus.arrayLeft[i];
			final char[] right = corpus.arrayRight[i];
			total += ArrayIntersectKernels.branchFreeArithmeticCardinality(
				left, left.length, right, right.length
			);
		}
		return total;
	}

	/**
	 * The hybrid policy: the broadcast probe while the shorter side is small, the summed all-pairs kernel
	 * otherwise.
	 *
	 * @param corpus the replayed operands
	 * @return the summed intersection cardinality
	 */
	@Benchmark
	public int aaHybridCount(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.arrayPairs; i++) {
			final char[] left = corpus.arrayLeft[i];
			final char[] right = corpus.arrayRight[i];
			total += ArrayIntersectKernels.hybridCardinality(left, left.length, right, right.length);
		}
		return total;
	}

	/* ====================================================== group 2: array against bitmap, 3.0 % ========== */

	/**
	 * Today's probe - one branch-free bit test per value.
	 *
	 * @param corpus the replayed operands
	 * @return the summed number of probed values found set
	 */
	@Benchmark
	public int probeTodayScalar(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.probePairs; i++) {
			final char[] values = corpus.probeValues[i];
			total += ProbeKernels.scalarProbeCardinality(corpus.probeWords[i], values, values.length);
		}
		return total;
	}

	/**
	 * The gather probe - eight values per step through one `VPGATHERQQ`.
	 *
	 * @param corpus the replayed operands
	 * @return the summed number of probed values found set
	 */
	@Benchmark
	public int probeGather(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.probePairs; i++) {
			final char[] values = corpus.probeValues[i];
			total += ProbeKernels.gatherProbeCardinality(
				corpus.probeWords[i], values, values.length, corpus.gatherIndices
			);
		}
		return total;
	}

	/* ====================================================== group 3: lazyIOR(Bitmap, Array), 92.7 % ======= */

	/**
	 * Today's scatter - one read-modify-write per value.
	 *
	 * @param corpus the replayed operands
	 * @return the number of values scattered, so the loop cannot be elided
	 */
	@Benchmark
	public int scatterTodayPerValue(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.scatterPairs; i++) {
			final char[] values = corpus.scatterValues[i];
			ScatterKernels.scatterPerValue(corpus.scatterWords[i], values, values.length);
			total += values.length;
		}
		return total;
	}

	/**
	 * The word-batched scatter - one read-modify-write per distinct word.
	 *
	 * @param corpus the replayed operands
	 * @return the number of values scattered
	 */
	@Benchmark
	public int scatterPerWord(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.scatterPairs; i++) {
			final char[] values = corpus.scatterValues[i];
			ScatterKernels.scatterPerWord(corpus.scatterWords[i], values, values.length);
			total += values.length;
		}
		return total;
	}

	/* ====================================================== group 4: iand(Bitmap, Bitmap), 0.23 % ========= */

	/**
	 * Today's two passes - count, then store.
	 *
	 * @param corpus the replayed operands
	 * @return the summed result cardinality
	 */
	@Benchmark
	public int bbTodayTwoPass(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.bitmapPairs; i++) {
			total += BitmapWordKernels.scalarAndStoreCountTwoPass(
				corpus.bitmapLeft[i], corpus.bitmapRight[i], corpus.bitmapOut
			);
		}
		return total;
	}

	/**
	 * One fused scalar pass.
	 *
	 * @param corpus the replayed operands
	 * @return the summed result cardinality
	 */
	@Benchmark
	public int bbScalarFused(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.bitmapPairs; i++) {
			total += BitmapWordKernels.scalarAndStoreCountFused(
				corpus.bitmapLeft[i], corpus.bitmapRight[i], corpus.bitmapOut
			);
		}
		return total;
	}

	/**
	 * One fused vector pass.
	 *
	 * @param corpus the replayed operands
	 * @return the summed result cardinality
	 */
	@Benchmark
	public int bbVectorFused(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.bitmapPairs; i++) {
			total += BitmapWordKernels.vectorAndStoreCountFused(
				corpus.bitmapLeft[i], corpus.bitmapRight[i], corpus.bitmapOut
			);
		}
		return total;
	}

	/**
	 * The count-first policy the design note keeps: the vector count decides the container type without
	 * allocating, and the fused vector store then produces the result.
	 *
	 * @param corpus the replayed operands
	 * @return the summed result cardinality
	 */
	@Benchmark
	public int bbVectorCountThenFused(@Nonnull final Corpus corpus) {
		int total = 0;
		for (int i = 0; i < corpus.bitmapPairs; i++) {
			final long[] left = corpus.bitmapLeft[i];
			final long[] right = corpus.bitmapRight[i];
			// the count is what decides whether an array or a bitmap container is allocated, and it has to
			// happen before the allocation - the store then runs only on the dense branch
			final int cardinality = BitmapWordKernels.vectorAndCardinality(left, right);
			if (cardinality > 4096) {
				total += BitmapWordKernels.vectorAndStoreCountFused(left, right, corpus.bitmapOut);
			} else {
				total += cardinality;
			}
		}
		return total;
	}

	/* ====================================================== the corpus ==================================== */

	/**
	 * The replayed operand pairs, grouped by the four combinations the census says carry the work.
	 */
	@State(Scope.Benchmark)
	public static class Corpus {

		/**
		 * Path of the operand dump. It is a `@Param` rather than a system property so it is part of the
		 * benchmark's identity, reaches the forked JVM and is written into the result JSON - a property would
		 * do none of those, and a replay that silently measured something else would look entirely plausible.
		 *
		 * The default is deliberately not a path: the dump is captured from a running engine and is not
		 * carried in the tree, so there is nothing this could point at that would be right for anyone.
		 */
		@Param({"<path-to>/operands.bin"})
		public String dump;

		/**
		 * Left operands of `iand(Array, Array)`.
		 */
		public char[][] arrayLeft;
		/**
		 * Right operands of `iand(Array, Array)`.
		 */
		public char[][] arrayRight;
		/**
		 * How many array pairs were replayed.
		 */
		public int arrayPairs;
		/**
		 * Array sides of the three array-against-bitmap combinations.
		 */
		public char[][] probeValues;
		/**
		 * Bitmap sides of the same.
		 */
		public long[][] probeWords;
		/**
		 * How many probe pairs were replayed.
		 */
		public int probePairs;
		/**
		 * Bitmap destinations of `lazyIOR(Bitmap, Array)`, restored from `scatterPristine` once per iteration.
		 */
		public long[][] scatterWords;
		/**
		 * The pristine copies those destinations are restored from.
		 */
		public long[][] scatterPristine;
		/**
		 * Array sides of `lazyIOR(Bitmap, Array)`.
		 */
		public char[][] scatterValues;
		/**
		 * How many scatter pairs were replayed.
		 */
		public int scatterPairs;
		/**
		 * Left operands of `iand(Bitmap, Bitmap)`.
		 */
		public long[][] bitmapLeft;
		/**
		 * Right operands of `iand(Bitmap, Bitmap)`.
		 */
		public long[][] bitmapRight;
		/**
		 * How many bitmap pairs were replayed.
		 */
		public int bitmapPairs;
		/**
		 * Destination of the materialising array intersections.
		 */
		public char[] intersectOut;
		/**
		 * Destination of the bitmap intersections.
		 */
		public long[] bitmapOut;
		/**
		 * Scratch the gather probe writes its word indices into.
		 */
		public int[] gatherIndices;

		@Setup(Level.Trial)
		public void setUp() {
			final Path path = Path.of(this.dump);
			if (!Files.isReadable(path)) {
				throw new IllegalArgumentException(
					"Operand dump `" + this.dump + "` is not readable! The dump is captured from a running " +
						"engine and is not carried in the tree - point `-p dump=` at one you captured yourself."
				);
			}
			final List<char[]> leftArrays = new ArrayList<>(512);
			final List<char[]> rightArrays = new ArrayList<>(512);
			final List<char[]> probeSides = new ArrayList<>(1024);
			final List<long[]> probeBitmaps = new ArrayList<>(1024);
			final List<long[]> scatterTargets = new ArrayList<>(512);
			final List<char[]> scatterSources = new ArrayList<>(512);
			final List<long[]> bitmapLefts = new ArrayList<>(512);
			final List<long[]> bitmapRights = new ArrayList<>(512);
			int longest = 1;
			try (
				final DataInputStream in = new DataInputStream(
					new BufferedInputStream(Files.newInputStream(path), 1 << 20)
				)
			) {
				if (in.readInt() != MAGIC) {
					throw new IllegalArgumentException("`" + this.dump + "` is not a container operand dump!");
				}
				in.readInt();
				final int pairs = in.readInt();
				for (int i = 0; i < pairs; i++) {
					final int operation = in.readByte();
					final int leftType = in.readByte();
					final int rightType = in.readByte();
					final Object left = readSide(in, leftType);
					final Object right = readSide(in, rightType);
					if (operation == OP_IAND && leftType == TYPE_ARRAY && rightType == TYPE_ARRAY) {
						leftArrays.add((char[]) left);
						rightArrays.add((char[]) right);
						longest = Math.max(longest, Math.min(((char[]) left).length, ((char[]) right).length));
					} else if (operation == OP_IAND && leftType == TYPE_ARRAY && rightType == TYPE_BITMAP) {
						probeSides.add((char[]) left);
						probeBitmaps.add((long[]) right);
					} else if ((operation == OP_IAND || operation == OP_AND_NOT)
						&& leftType == TYPE_BITMAP && rightType == TYPE_ARRAY) {
						probeSides.add((char[]) right);
						probeBitmaps.add((long[]) left);
					} else if (operation == OP_LAZY_IOR && leftType == TYPE_BITMAP && rightType == TYPE_ARRAY) {
						scatterTargets.add((long[]) left);
						scatterSources.add((char[]) right);
					} else if (operation == OP_IAND && leftType == TYPE_BITMAP && rightType == TYPE_BITMAP) {
						bitmapLefts.add((long[]) left);
						bitmapRights.add((long[]) right);
					}
				}
			} catch (final IOException e) {
				throw new UncheckedIOException("Cannot read operand dump `" + this.dump + "`!", e);
			}
			this.arrayLeft = leftArrays.toArray(char[][]::new);
			this.arrayRight = rightArrays.toArray(char[][]::new);
			this.arrayPairs = this.arrayLeft.length;
			this.probeValues = probeSides.toArray(char[][]::new);
			this.probeWords = probeBitmaps.toArray(long[][]::new);
			this.probePairs = this.probeValues.length;
			this.scatterPristine = scatterTargets.toArray(long[][]::new);
			this.scatterWords = new long[this.scatterPristine.length][];
			this.scatterValues = scatterSources.toArray(char[][]::new);
			this.scatterPairs = this.scatterValues.length;
			this.bitmapLeft = bitmapLefts.toArray(long[][]::new);
			this.bitmapRight = bitmapRights.toArray(long[][]::new);
			this.bitmapPairs = this.bitmapLeft.length;
			// the branch-free and compress kernels both store speculatively, so the destination carries slack
			this.intersectOut = new char[longest + 8];
			this.bitmapOut = new long[BitmapWordKernels.WORDS];
			this.gatherIndices = new int[ProbeKernels.PROBE_BLOCK];
			if (this.arrayPairs == 0 || this.probePairs == 0 || this.scatterPairs == 0 || this.bitmapPairs == 0) {
				throw new IllegalStateException(
					"The dump is missing a group: array=" + this.arrayPairs + " probe=" + this.probePairs +
						" scatter=" + this.scatterPairs + " bitmap=" + this.bitmapPairs + "!"
				);
			}
			verify();
			restore();
			System.out.printf(
				"# replay: dump=%s arrayPairs=%d probePairs=%d scatterPairs=%d bitmapPairs=%d " +
					"medianShorterArray=%d medianProbed=%d medianScattered=%d%n",
				this.dump, this.arrayPairs, this.probePairs, this.scatterPairs, this.bitmapPairs,
				medianShorter(this.arrayLeft, this.arrayRight), medianLength(this.probeValues),
				medianLength(this.scatterValues)
			);
		}

		/**
		 * Restores the scatter destinations, so an iteration always starts from the state the census captured.
		 * Runs between iterations, never inside a measurement.
		 */
		@Setup(Level.Iteration)
		public void restore() {
			for (int i = 0; i < this.scatterPristine.length; i++) {
				this.scatterWords[i] = this.scatterPristine[i].clone();
			}
		}

		/**
		 * Checks every replayed variant against the arm that runs in production, on the real operands, before
		 * any of them is timed.
		 */
		private void verify() {
			for (int i = 0; i < this.arrayPairs; i++) {
				final char[] left = this.arrayLeft[i];
				final char[] right = this.arrayRight[i];
				final int expected = Util.unsignedLocalIntersect2by2Cardinality(
					left, left.length, right, right.length
				);
				agree("A materialising", i, expected,
					Util.unsignedIntersect2by2(left, left.length, right, right.length, this.intersectOut));
				agree("B", i, expected, ArrayIntersectKernels.branchFreeArithmeticCardinality(
					left, left.length, right, right.length));
				agree("Ds", i, expected, ArrayIntersectKernels.allPairsSummedCardinality(
					left, left.length, right, right.length));
				agree("H", i, expected, ArrayIntersectKernels.broadcastProbeOrientedCardinality(
					left, left.length, right, right.length));
				agree("H materialising", i, expected, ArrayIntersectKernels.broadcastProbeOrientedIntersect(
					left, left.length, right, right.length, this.intersectOut));
				agree("H512b", i, expected, ArrayIntersectKernels.broadcastProbeReloadingOrientedCardinality(
					left, left.length, right, right.length));
				agree("H512b materialising", i, expected,
					ArrayIntersectKernels.broadcastProbeReloadingOrientedIntersect(
						left, left.length, right, right.length, this.intersectOut));
				agree("H512c", i, expected, ArrayIntersectKernels.broadcastProbeCachedOrientedCardinality(
					left, left.length, right, right.length));
				agree("H512c materialising", i, expected,
					ArrayIntersectKernels.broadcastProbeCachedOrientedIntersect(
						left, left.length, right, right.length, this.intersectOut));
				agree("hybrid", i, expected, ArrayIntersectKernels.hybridCardinality(
					left, left.length, right, right.length));
			}
			for (int i = 0; i < this.probePairs; i++) {
				final char[] values = this.probeValues[i];
				agree("gather probe", i,
					ProbeKernels.scalarProbeCardinality(this.probeWords[i], values, values.length),
					ProbeKernels.gatherProbeCardinality(
						this.probeWords[i], values, values.length, this.gatherIndices));
			}
			for (int i = 0; i < this.bitmapPairs; i++) {
				final long[] left = this.bitmapLeft[i];
				final long[] right = this.bitmapRight[i];
				final int expected = BitmapWordKernels.scalarAndStoreCountTwoPass(left, right, this.bitmapOut);
				agree("bitmap fused scalar", i, expected,
					BitmapWordKernels.scalarAndStoreCountFused(left, right, this.bitmapOut));
				agree("bitmap fused vector", i, expected,
					BitmapWordKernels.vectorAndStoreCountFused(left, right, this.bitmapOut));
				agree("bitmap vector count", i, expected,
					BitmapWordKernels.vectorAndCardinality(left, right));
			}
			// the two scatter kernels have to leave the same bitmap behind, which is checked on a copy so the
			// pristine operands stay pristine
			for (int i = 0; i < this.scatterPairs; i++) {
				final char[] values = this.scatterValues[i];
				final long[] perValue = this.scatterPristine[i].clone();
				final long[] perWord = this.scatterPristine[i].clone();
				ScatterKernels.scatterPerValue(perValue, values, values.length);
				ScatterKernels.scatterPerWord(perWord, values, values.length);
				if (!Arrays.equals(perValue, perWord)) {
					throw new IllegalStateException("The two scatter kernels disagree on pair " + i + "!");
				}
			}
		}

		/**
		 * Asserts one variant agrees with the production arm on one replayed pair.
		 *
		 * @param variant  the variant name, for the message
		 * @param pair     index of the pair, for the message
		 * @param expected what the production arm computed
		 * @param actual   what the variant computed
		 */
		private static void agree(
			@Nonnull final String variant, final int pair, final int expected, final int actual) {
			if (expected != actual) {
				throw new IllegalStateException(
					"Variant " + variant + " disagrees on replayed pair " + pair + ": expected " + expected +
						", got " + actual + "!"
				);
			}
		}

		/**
		 * Reads one operand.
		 *
		 * @param in   the stream positioned at an operand
		 * @param type the operand's container type
		 * @return a `char[]` for an array or run container, a `long[1024]` for a bitmap container
		 * @throws IOException when the dump is truncated
		 */
		@Nonnull
		private static Object readSide(@Nonnull final DataInputStream in, final int type) throws IOException {
			in.readInt();
			if (type == TYPE_ARRAY) {
				final char[] values = new char[in.readInt()];
				for (int i = 0; i < values.length; i++) {
					values[i] = in.readChar();
				}
				return values;
			} else if (type == TYPE_BITMAP) {
				final long[] words = new long[BitmapWordKernels.WORDS];
				for (int i = 0; i < words.length; i++) {
					words[i] = in.readLong();
				}
				return words;
			} else {
				final char[] runs = new char[2 * in.readInt()];
				for (int i = 0; i < runs.length; i++) {
					runs[i] = in.readChar();
				}
				return runs;
			}
		}

		/**
		 * Median length of a set of arrays, so the fixture can state the shape it replays.
		 *
		 * @param arrays the arrays
		 * @return their median length
		 */
		private static int medianLength(@Nonnull final char[][] arrays) {
			final int[] lengths = new int[arrays.length];
			for (int i = 0; i < arrays.length; i++) {
				lengths[i] = arrays[i].length;
			}
			Arrays.sort(lengths);
			return lengths.length == 0 ? 0 : lengths[lengths.length / 2];
		}

		/**
		 * Median of the shorter side of a set of operand pairs - the number that decides which kernel can help.
		 *
		 * @param left  left operands
		 * @param right right operands
		 * @return the median shorter length
		 */
		private static int medianShorter(@Nonnull final char[][] left, @Nonnull final char[][] right) {
			final int[] lengths = new int[left.length];
			for (int i = 0; i < left.length; i++) {
				lengths[i] = Math.min(left[i].length, right[i].length);
			}
			Arrays.sort(lengths);
			return lengths.length == 0 ? 0 : lengths[lengths.length / 2];
		}
	}

}
