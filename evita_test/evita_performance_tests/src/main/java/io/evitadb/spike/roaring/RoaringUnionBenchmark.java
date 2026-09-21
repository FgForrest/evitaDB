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

import io.evitadb.roaringbitmap.ArrayContainer;
import io.evitadb.roaringbitmap.Container;
import io.evitadb.roaringbitmap.FastAggregation;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Union strategy A/B - the change a JFR profile of the production facet workload says is worth the most.
 *
 * Roaring is 40.9 % of facet-query CPU and the lazy-OR machinery is 26.4 % of it, spread over
 * `BitmapContainer.ilazyor` (11.8 % self), `FastAggregation.naivelazyor` (5.1 %), `Util.fillArray` (5.0 %) and
 * the demotion path. The reason it costs that much is not that any one of those is slow: it is that **95 % of
 * the 1.93 million bitmaps that get lazily unioned demote straight back to an array container**, having paid an
 * 8 KiB allocation, a scatter, a population count and a 1024-word scan on the way. 60 % of unions have between
 * two and four inputs and 87 % have sixteen or fewer; 57 % of repaired results hold 64 values or fewer.
 *
 * ## The branch is already there, and nothing reaches it
 *
 * CRoaring keeps an array-array lazy union as an array while the summed cardinality stays under
 * `ARRAY_LAZY_LOWERBOUND` (`mixed_union.c:247`). **So does the vendored Java port**: `ArrayContainer.lazyor`
 * promotes to a bitmap only above `ARRAY_LAZY_LOWERBOUND`, and that constant is 1024 here too. The claim that
 * the port never had the branch is wrong.
 *
 * What bypasses it is `PersistentRoaringBitmap.naivelazyor`, which calls `toBitmapContainer()` on the
 * accumulator *before* merging and then `lazyIOR` on the bitmap. A multi-way union therefore promotes on the
 * first merge of every key and never reaches the array branch that already exists.
 *
 * ## The arms, and which baseline each belongs to
 *
 * - **today, end to end** - `FastAggregation.naive_or`, exactly as vendored, bookkeeping included.
 * - **today, per key** - what `naivelazyor` does to one key: promote the accumulator on the first merge, then
 *   fold in place. This is the baseline the policy arms are compared against; the gap to the end-to-end arm is
 *   what the `PersistentRoaringBitmap` bookkeeping costs.
 * - **existing array lazy union** - an empty `ArrayContainer` folded through `Container.lazyIOR`, which is
 *   what already happens when the accumulator is *not* force-promoted. Not a new policy: it is the vendored
 *   `lazyor` branch, reachable only because this arm does not promote first. Its threshold is the vendored
 *   1024.
 * - **array first at T** - the same idea with the merge done by a ping-pong pair of scratch buffers rather
 *   than an allocation per fold, and with T chosen rather than inherited. Measured at 64, 256 and 1024.
 * - **array first, guarded** - the same, falling back to the today-per-key arm above 64 inputs.
 *
 * ## Why the array path loses badly on many inputs
 *
 * Every fold on the array path copies the whole accumulator, so folding `k` inputs of `s` values each costs
 * about `k^2 * s / 2` element copies while the accumulator stays under the bound - quadratic in the input
 * count. The bitmap path costs one 8 KiB allocation and `k * s` scatter writes, which is linear. A bound on
 * *cardinality* does not cap the quadratic term; only a bound on the *input count* does. That is what the
 * guard is for, and it is why the two bounds are not interchangeable.
 *
 * Both the time and the allocation matter here, so this family is also run under `-prof gc`: the whole point of
 * the array path is the 8 KiB that never gets allocated.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RoaringUnionBenchmark {

	/**
	 * Input count above which the guarded policy declines to fold on the array path.
	 */
	private static final int GUARD = 64;

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/**
	 * Today's path, whole: `FastAggregation.naive_or` over real bitmaps.
	 *
	 * @param unions the fixture
	 * @return the union, returned so it cannot be optimised away
	 */
	@Benchmark
	public PersistentRoaringBitmap unionTodayEndToEnd(@Nonnull final Unions unions) {
		return FastAggregation.naive_or(unions.bitmaps);
	}

	/**
	 * Today's path at the container level - the accumulator promoted on the first merge, exactly as
	 * `naivelazyor` does it, then folded in place. This is the baseline for every policy arm.
	 *
	 * @param unions the fixture
	 * @return the unioned container
	 */
	@Benchmark
	public Container unionTodayPerKey(@Nonnull final Unions unions) {
		return todayPerKey(unions);
	}

	/**
	 * The array lazy union the vendored `ArrayContainer.lazyor` already implements, reached by simply not
	 * promoting the accumulator first. Its threshold is the vendored `ARRAY_LAZY_LOWERBOUND` of 1024 and it
	 * allocates a fresh container on every fold.
	 *
	 * @param unions the fixture
	 * @return the unioned container
	 */
	@Benchmark
	public Container unionExistingArrayLazy(@Nonnull final Unions unions) {
		return existingArrayLazy(unions);
	}

	/**
	 * The array-first policy with the threshold at 64.
	 *
	 * @param unions the fixture
	 * @return the unioned container
	 */
	@Benchmark
	public Container unionArrayFirst64(@Nonnull final Unions unions) {
		return arrayFirst(unions, 64);
	}

	/**
	 * The array-first policy with the threshold at 256.
	 *
	 * @param unions the fixture
	 * @return the unioned container
	 */
	@Benchmark
	public Container unionArrayFirst256(@Nonnull final Unions unions) {
		return arrayFirst(unions, 256);
	}

	/**
	 * The array-first policy with the threshold at 1024 - CRoaring's `ARRAY_LAZY_LOWERBOUND`.
	 *
	 * @param unions the fixture
	 * @return the unioned container
	 */
	@Benchmark
	public Container unionArrayFirst1024(@Nonnull final Unions unions) {
		return arrayFirst(unions, 1024);
	}

	/**
	 * The array-first policy at 1024, falling back to today's per-key fold above 64 inputs.
	 *
	 * @param unions the fixture
	 * @return the unioned container
	 */
	@Benchmark
	public Container unionArrayFirstGuarded(@Nonnull final Unions unions) {
		return unions.inputs.length > GUARD ? todayPerKey(unions) : arrayFirst(unions, 1024);
	}

	/**
	 * What `naivelazyor` does to a single key: the accumulator is promoted to a bitmap before the first merge
	 * and every later input is scattered into it in place, so the fold is linear and allocates once.
	 *
	 * @param unions the fixture
	 * @return the unioned container
	 */
	@Nonnull
	private static Container todayPerKey(@Nonnull final Unions unions) {
		return todayFrom(unions, 0, null);
	}

	/**
	 * The same fold from a given position, optionally continuing from an accumulator the caller already built.
	 *
	 * @param unions      the fixture
	 * @param from        index of the first input to fold
	 * @param accumulator what to fold into, or `null` to start from the first input
	 * @return the unioned container
	 */
	@Nonnull
	private static Container todayFrom(
		@Nonnull final Unions unions, final int from, final Container accumulator) {
		Container answer = accumulator;
		for (int i = from; i < unions.inputs.length; i++) {
			final ArrayContainer input = new ArrayContainer(unions.lengths[i], unions.inputs[i]);
			answer = answer == null ? input.clone() : answer.toBitmapContainer().lazyIOR(input);
		}
		return answer == null ? new ArrayContainer() : answer.repairAfterLazy();
	}

	/**
	 * The fold that reaches the vendored array lazy union: no forced promotion, so `Container.lazyIOR`
	 * dispatches array against array to `ArrayContainer.lazyor` and its 1024 bound decides.
	 *
	 * @param unions the fixture
	 * @return the unioned container
	 */
	@Nonnull
	private static Container existingArrayLazy(@Nonnull final Unions unions) {
		Container answer = new ArrayContainer();
		for (int i = 0; i < unions.inputs.length; i++) {
			answer = answer.lazyIOR(new ArrayContainer(unions.lengths[i], unions.inputs[i]));
		}
		return answer.repairAfterLazy();
	}

	/**
	 * The array-first policy: merge on the array path while the accumulator stays small, then finish exactly
	 * as today.
	 *
	 * The two scratch buffers are allocated here rather than held in the fixture, because their allocation is
	 * the cost being weighed against the 8 KiB one and hiding it would decide the question by construction.
	 *
	 * @param unions    the fixture
	 * @param threshold the largest accumulated cardinality that stays on the array path
	 * @return the unioned container
	 */
	@Nonnull
	private static Container arrayFirst(@Nonnull final Unions unions, final int threshold) {
		final int capacity = threshold + unions.longestInput;
		final char[] first = new char[capacity];
		final char[] second = new char[capacity];
		final long state = UnionKernels.foldWhileSmall(
			unions.inputs, unions.lengths, unions.inputs.length, threshold, first, second
		);
		final int stopped = UnionKernels.stoppedAt(state);
		final int accumulated = UnionKernels.accumulated(state);
		final char[] folded = UnionKernels.inSecond(state) ? second : first;
		// the remainder finishes exactly as `naivelazyor` would: promote once, then scatter in place
		return todayFrom(unions, stopped, new ArrayContainer(accumulated, folded));
	}

	/**
	 * A set of single-key bitmaps to be unioned, in the input count and per-input cardinality the shape names.
	 */
	@State(Scope.Benchmark)
	public static class Unions {

		/**
		 * `n<count>_s<size>` - `count` inputs of `size` values each; `n<count>_mixed` - half the inputs hold one
		 * value and half hold sixteen, which is closer to what a facet union actually sees.
		 */
		@Param({
			"n2_s1", "n2_s4", "n2_s16", "n2_s64",
			"n4_s1", "n4_s4", "n4_s16", "n4_s64",
			"n8_s1", "n8_s4", "n8_s16", "n8_s64",
			"n16_s1", "n16_s4", "n16_s16", "n16_s64",
			"n64_s1", "n64_s4", "n64_s16", "n64_s64",
			"n256_s1", "n256_s4", "n256_s16", "n256_s64",
			"n4_mixed", "n16_mixed", "n64_mixed"
		})
		public String shape;

		/**
		 * The inputs' payloads, ascending.
		 */
		public char[][] inputs;
		/**
		 * How many entries of each payload are live.
		 */
		public int[] lengths;
		/**
		 * The same inputs as whole bitmaps, for the end-to-end arm.
		 */
		public PersistentRoaringBitmap[] bitmaps;
		/**
		 * Longest single input, which bounds the scratch the array path needs.
		 */
		public int longestInput;
		/**
		 * Cardinality of the union - printed so a row can be read against the census's result-size histogram.
		 */
		public int unionCardinality;

		@Setup
		public void setUp() {
			final int count;
			final int size;
			final boolean mixed = this.shape.endsWith("_mixed");
			if (mixed) {
				count = Integer.parseInt(this.shape.substring(1, this.shape.indexOf('_')));
				size = -1;
			} else if (this.shape.matches("n\\d+_s\\d+")) {
				final int underscore = this.shape.indexOf('_');
				count = Integer.parseInt(this.shape.substring(1, underscore));
				size = Integer.parseInt(this.shape.substring(underscore + 2));
			} else {
				throw new IllegalArgumentException(
					"Shape `" + this.shape + "` is neither `n<count>_s<size>` nor `n<count>_mixed`!"
				);
			}
			final Random random = new Random(20260918L);
			this.inputs = new char[count][];
			this.lengths = new int[count];
			this.bitmaps = new PersistentRoaringBitmap[count];
			this.longestInput = 0;
			for (int i = 0; i < count; i++) {
				final int cardinality = mixed ? (i % 2 == 0 ? 1 : 16) : size;
				final TreeSet<Integer> values = new TreeSet<>();
				while (values.size() < cardinality) {
					values.add(random.nextInt(65536));
				}
				final char[] payload = new char[values.size()];
				final int[] asInts = new int[values.size()];
				int index = 0;
				for (final Integer value : values) {
					payload[index] = (char) value.intValue();
					asInts[index] = value;
					index++;
				}
				this.inputs[i] = payload;
				this.lengths[i] = payload.length;
				this.longestInput = Math.max(this.longestInput, payload.length);
				this.bitmaps[i] = PersistentRoaringBitmap.bitmapOf(asInts);
			}
			verify();
			System.out.printf(
				"# unions: shape=%s inputs=%d perInput=%s union=%d longest=%d%n",
				this.shape, count, mixed ? "1/16" : String.valueOf(size), this.unionCardinality, this.longestInput
			);
		}

		/**
		 * Runs every arm once, checks they all produce the same set, and checks that none of them mutated the
		 * fixture - a lazy union that quietly took ownership of an input would make every later invocation
		 * measure a different problem.
		 */
		private void verify() {
			final char[][] pristine = new char[this.inputs.length][];
			for (int i = 0; i < this.inputs.length; i++) {
				pristine[i] = this.inputs[i].clone();
			}
			final TreeSet<Integer> expected = new TreeSet<>();
			for (final char[] input : this.inputs) {
				for (final char value : input) {
					expected.add((int) value);
				}
			}
			this.unionCardinality = expected.size();
			sameSet("today, end to end", expected, FastAggregation.naive_or(this.bitmaps).getCardinality());
			sameSet("today, per key", expected, todayPerKey(this).getCardinality());
			sameSet("existing array lazy", expected, existingArrayLazy(this).getCardinality());
			for (final int threshold : new int[]{64, 256, 1024}) {
				sameSet("array first " + threshold, expected, arrayFirst(this, threshold).getCardinality());
			}
			sameSet("array first, guarded", expected,
				this.inputs.length > GUARD ? todayPerKey(this).getCardinality()
					: arrayFirst(this, 1024).getCardinality());
			for (int i = 0; i < this.inputs.length; i++) {
				if (!Arrays.equals(pristine[i], this.inputs[i])) {
					throw new IllegalStateException("An arm mutated input " + i + " of shape `" + this.shape + "`!");
				}
			}
		}

		/**
		 * Asserts one arm produced the expected cardinality.
		 *
		 * @param arm      the arm's name, for the message
		 * @param expected the values the union should hold
		 * @param actual   the cardinality the arm produced
		 */
		private void sameSet(
			@Nonnull final String arm, @Nonnull final TreeSet<Integer> expected, final int actual) {
			if (expected.size() != actual) {
				throw new IllegalStateException(
					"Arm `" + arm + "` produced " + actual + " values on shape `" + this.shape + "`, expected " +
						expected.size() + "!"
				);
			}
		}
	}

}
