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
import io.evitadb.roaringbitmap.BitmapContainer;
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
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Replay of 300 whole multi-way unions captured from the production retail catalog - the fixture that decides
 * the lazy-OR strategy question, which no pairwise fixture can answer.
 *
 * The profile says the lazy-OR machinery is 26.4 % of facet-query CPU and that 95 % of the 1.93 million bitmaps
 * it produces demote straight back to an array. The census says why: **99.95 % of the 253,170 containers being
 * folded are already array containers** (123 are not), so `naivelazyor` promotes to an 8 KiB bitmap on almost
 * every first fold of a key, scatters into it, counts it and scans it back out - to produce an array again.
 *
 * The branch that would avoid it already exists: `ArrayContainer.lazyor` keeps an array-array lazy union as an
 * array below `ARRAY_LAZY_LOWERBOUND`, which is 1024 here as in CRoaring. Nothing reaches it, because
 * `naivelazyor` calls `toBitmapContainer()` on the accumulator before every merge. So the arms below are not
 * asking whether to write a new kernel; they are asking what the existing one is worth once the forced
 * promotion is removed, and at what input count its per-fold copy stops paying.
 *
 * ## Reading a number off this benchmark
 *
 * **One invocation folds every union in its stratum**, so a score has to be divided by the union count the
 * fixture prints. A per-union benchmark would have measured JMH's loop rather than the fold.
 *
 * **The strata are not the workload.** The dump samples 80/70/60/50/40 unions from five input-count buckets
 * whose real populations are 176,178 / 80,825 / 19,598 / 10,771 / 6,892. A number for the workload is the
 * per-union cost of each stratum weighted by those counts, never the average across the file.
 *
 * **Two baselines, on purpose.** `todayEndToEnd` is the real `FastAggregation.naive_or`, bookkeeping included.
 * `todayPerKey` is the same fold expressed the way the policy arms express it, so the policy is compared with
 * something built the same way and the difference between the two baselines says what the bookkeeping costs.
 * Quoting a policy arm against `todayEndToEnd` alone would credit the policy with work it did not remove.
 *
 * Every arm is checked against the per-key result cardinalities the dump recorded after `repairAfterLazy`,
 * before any of them is timed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class RoaringUnionReplayBenchmark {

	/**
	 * `"ROUC"` - the union dump's magic number.
	 */
	private static final int MAGIC = 0x524F5543;
	/**
	 * Container type id of an array container.
	 */
	private static final byte TYPE_ARRAY = 0;
	/**
	 * Container type id of a bitmap container.
	 */
	private static final byte TYPE_BITMAP = 1;
	/**
	 * Input count above which the guarded policy declines to fold on the array path.
	 */
	private static final int GUARD = 64;

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/**
	 * Today's path, whole: `FastAggregation.naive_or` over the reconstructed input bitmaps.
	 *
	 * @param corpus the replayed unions
	 * @return the summed result cardinality, so nothing can be optimised away
	 */
	@Benchmark
	public int todayEndToEnd(@Nonnull final UnionCorpus corpus) {
		int total = 0;
		for (final ReplayedUnion union : corpus.unions) {
			total += FastAggregation.naive_or(union.bitmaps).getCardinality();
		}
		return total;
	}

	/**
	 * Today's fold expressed per key, without the bitmap bookkeeping.
	 *
	 * @param corpus the replayed unions
	 * @return the summed result cardinality
	 */
	@Benchmark
	public int todayPerKey(@Nonnull final UnionCorpus corpus) {
		int total = 0;
		for (final ReplayedUnion union : corpus.unions) {
			for (final KeyFold fold : union.keys) {
				total += foldToday(fold).getCardinality();
			}
		}
		return total;
	}

	/**
	 * The array lazy union the vendored code already carries, with its forced promotion removed.
	 *
	 * @param corpus the replayed unions
	 * @return the summed result cardinality
	 */
	@Benchmark
	public int existingArrayLazy(@Nonnull final UnionCorpus corpus) {
		int total = 0;
		for (final ReplayedUnion union : corpus.unions) {
			for (final KeyFold fold : union.keys) {
				total += foldExistingArrayLazy(fold).getCardinality();
			}
		}
		return total;
	}

	/**
	 * The array-first policy with the threshold at 64.
	 *
	 * @param corpus the replayed unions
	 * @return the summed result cardinality
	 */
	@Benchmark
	public int arrayFirst64(@Nonnull final UnionCorpus corpus) {
		return foldAll(corpus, 64, Integer.MAX_VALUE);
	}

	/**
	 * The array-first policy with the threshold at 256.
	 *
	 * @param corpus the replayed unions
	 * @return the summed result cardinality
	 */
	@Benchmark
	public int arrayFirst256(@Nonnull final UnionCorpus corpus) {
		return foldAll(corpus, 256, Integer.MAX_VALUE);
	}

	/**
	 * The array-first policy with the threshold at 1024 - CRoaring's `ARRAY_LAZY_LOWERBOUND`.
	 *
	 * @param corpus the replayed unions
	 * @return the summed result cardinality
	 */
	@Benchmark
	public int arrayFirst1024(@Nonnull final UnionCorpus corpus) {
		return foldAll(corpus, 1024, Integer.MAX_VALUE);
	}

	/**
	 * The array-first policy at 1024, declining to fold on the array path when the union has more than 64
	 * inputs.
	 *
	 * @param corpus the replayed unions
	 * @return the summed result cardinality
	 */
	@Benchmark
	public int arrayFirst1024Guarded(@Nonnull final UnionCorpus corpus) {
		return foldAll(corpus, 1024, GUARD);
	}

	/**
	 * Runs the policy over every union of the corpus.
	 *
	 * @param corpus    the replayed unions
	 * @param threshold the largest accumulated cardinality that stays on the array path
	 * @param guard     input count above which the policy declines to fold on the array path
	 * @return the summed result cardinality
	 */
	private static int foldAll(
		@Nonnull final UnionCorpus corpus, final int threshold, final int guard) {
		int total = 0;
		for (final ReplayedUnion union : corpus.unions) {
			final boolean arrayPath = union.inputCount <= guard;
			for (final KeyFold fold : union.keys) {
				total += (arrayPath ? foldArrayFirst(fold, threshold) : foldToday(fold)).getCardinality();
			}
		}
		return total;
	}

	/**
	 * Today's fold for one key, faithfully: the accumulator is promoted to a bitmap before the first merge and
	 * every later container is scattered into it in place, which is what `naivelazyor` does.
	 *
	 * @param fold the key's containers, in fold order
	 * @return the resulting container
	 */
	@Nonnull
	private static Container foldToday(@Nonnull final KeyFold fold) {
		return foldFrom(fold, 0, null);
	}

	/**
	 * The same fold from a given position, optionally continuing from an accumulator the caller already built.
	 *
	 * @param fold        the key's containers, in fold order
	 * @param from        index of the first container to fold
	 * @param accumulator what to fold into, or `null` to start from the first container
	 * @return the resulting container
	 */
	@Nonnull
	private static Container foldFrom(
		@Nonnull final KeyFold fold, final int from, final Container accumulator) {
		Container answer = accumulator;
		for (int i = from; i < fold.types.length; i++) {
			final Container input = fold.container(i);
			answer = answer == null ? input : answer.toBitmapContainer().lazyIOR(input);
		}
		return answer == null ? new ArrayContainer() : answer.repairAfterLazy();
	}

	/**
	 * The array lazy union the vendored `ArrayContainer.lazyor` already implements, reached by not promoting
	 * the accumulator first. Its threshold is the vendored 1024 and it allocates on every fold.
	 *
	 * @param fold the key's containers, in fold order
	 * @return the resulting container
	 */
	@Nonnull
	private static Container foldExistingArrayLazy(@Nonnull final KeyFold fold) {
		Container answer = new ArrayContainer();
		for (int i = 0; i < fold.types.length; i++) {
			answer = answer.lazyIOR(fold.container(i));
		}
		return answer.repairAfterLazy();
	}

	/**
	 * The array-first fold for one key: merge the leading run of array containers on the array path while the
	 * accumulated cardinality stays within the threshold, then finish exactly as today.
	 *
	 * The scratch pair is allocated here because its allocation is the cost being weighed against the 8 KiB
	 * one, and holding it in the fixture would decide the question by construction.
	 *
	 * @param fold      the key's containers, in fold order
	 * @param threshold the largest accumulated cardinality that stays on the array path
	 * @return the resulting container
	 */
	@Nonnull
	private static Container foldArrayFirst(@Nonnull final KeyFold fold, final int threshold) {
		if (fold.leadingArrays == 0) {
			return foldToday(fold);
		}
		final int capacity = threshold + fold.longestArray;
		final char[] first = new char[capacity];
		final char[] second = new char[capacity];
		final long state = UnionKernels.foldWhileSmall(
			fold.arrays, fold.lengths, fold.leadingArrays, threshold, first, second
		);
		final int stopped = UnionKernels.stoppedAt(state);
		final int accumulated = UnionKernels.accumulated(state);
		final char[] folded = UnionKernels.inSecond(state) ? second : first;
		// the remainder finishes exactly as `naivelazyor` would: promote once, then scatter in place
		return foldFrom(fold, stopped, new ArrayContainer(accumulated, folded));
	}

	/**
	 * One key's worth of a union: the containers folded into it, in fold order, plus the cardinality the dump
	 * recorded for the repaired result.
	 */
	public static final class KeyFold {

		/**
		 * The container types, in fold order.
		 */
		public final byte[] types;
		/**
		 * Array payloads, in fold order; the entry is `null` where the container is not an array.
		 */
		public final char[][] arrays;
		/**
		 * Lengths of those payloads, so the fold kernel needs no null checks over its leading run.
		 */
		public final int[] lengths;
		/**
		 * Bitmap payloads, in fold order; `null` where the container is not a bitmap.
		 */
		public final long[][] bitmaps;
		/**
		 * How many containers at the head of the fold order are array containers - the run the array path can
		 * consume without meeting a container it cannot merge.
		 */
		public final int leadingArrays;
		/**
		 * Longest array payload, which bounds the scratch the array path needs.
		 */
		public final int longestArray;
		/**
		 * Cardinality the dump recorded for this key after `repairAfterLazy`.
		 */
		public final int expected;

		KeyFold(
			@Nonnull final byte[] types, @Nonnull final char[][] arrays, @Nonnull final int[] lengths,
			@Nonnull final long[][] bitmaps, final int leadingArrays, final int longestArray, final int expected) {
			this.types = types;
			this.arrays = arrays;
			this.lengths = lengths;
			this.bitmaps = bitmaps;
			this.leadingArrays = leadingArrays;
			this.longestArray = longestArray;
			this.expected = expected;
		}

		/**
		 * Wraps one of the key's payloads in a fresh container, which both arms do equally so the object
		 * allocation does not favour either.
		 *
		 * @param index position in the fold order
		 * @return the container
		 */
		@Nonnull
		Container container(final int index) {
			if (this.types[index] == TYPE_ARRAY) {
				return new ArrayContainer(this.lengths[index], this.arrays[index]);
			}
			return new BitmapContainer(this.bitmaps[index].clone(), -1);
		}
	}

	/**
	 * One replayed union.
	 */
	public static final class ReplayedUnion {

		/**
		 * How many bitmaps the union folded.
		 */
		public final int inputCount;
		/**
		 * The union's keys, each with the containers folded into it.
		 */
		public final KeyFold[] keys;
		/**
		 * The same inputs as whole bitmaps, for the end-to-end arm.
		 */
		public final PersistentRoaringBitmap[] bitmaps;
		/**
		 * Summed result cardinality the dump recorded.
		 */
		public final int expected;

		ReplayedUnion(
			final int inputCount, @Nonnull final KeyFold[] keys,
			@Nonnull final PersistentRoaringBitmap[] bitmaps, final int expected) {
			this.inputCount = inputCount;
			this.keys = keys;
			this.bitmaps = bitmaps;
			this.expected = expected;
		}
	}

	/**
	 * The unions of one input-count stratum.
	 */
	@State(Scope.Benchmark)
	public static class UnionCorpus {

		/**
		 * Path of the union dump.
		 */
		@Param({"/www/oss/evita/evitaDB-worktrees/1541-kernel-bench/specifications/1541-simd-roaring/fixtures/unions.bin"})
		public String dump;

		/**
		 * Which input-count stratum to replay. The strata are the dump's own, and they are not proportional to
		 * the workload - see the class javadoc.
		 */
		@Param({"s2_4", "s5_16", "s17_64", "s65_256", "s257plus"})
		public String stratum;

		/**
		 * The unions of that stratum.
		 */
		public ReplayedUnion[] unions;

		@Setup
		public void setUp() {
			final int low;
			final int high;
			switch (this.stratum) {
				case "s2_4" -> { low = 2; high = 4; }
				case "s5_16" -> { low = 5; high = 16; }
				case "s17_64" -> { low = 17; high = 64; }
				case "s65_256" -> { low = 65; high = 256; }
				case "s257plus" -> { low = 257; high = Integer.MAX_VALUE; }
				default -> throw new IllegalArgumentException("Unknown stratum `" + this.stratum + "`!");
			}
			this.unions = read(Path.of(this.dump), low, high);
			if (this.unions.length == 0) {
				throw new IllegalStateException(
					"Stratum `" + this.stratum + "` holds no unions in `" + this.dump + "`!"
				);
			}
			verify();
			long containers = 0;
			long inputs = 0;
			for (final ReplayedUnion union : this.unions) {
				inputs += union.inputCount;
				containers += union.keys.length;
			}
			System.out.printf(
				"# unions: stratum=%s unions=%d inputs=%d keys=%d meanInputs=%.1f%n",
				this.stratum, this.unions.length, inputs, containers,
				(double) inputs / this.unions.length
			);
		}

		/**
		 * Runs every arm once and refuses the fixture unless all of them reproduce the per-key cardinalities
		 * the dump recorded after `repairAfterLazy`. A replay that computed a different union would produce a
		 * perfectly plausible number for an operation nobody performs.
		 */
		private void verify() {
			for (final ReplayedUnion union : this.unions) {
				int endToEnd = FastAggregation.naive_or(union.bitmaps).getCardinality();
				if (endToEnd != union.expected) {
					throw new IllegalStateException(
						"Replayed union produced " + endToEnd + " values where the dump recorded " +
							union.expected + "!"
					);
				}
				for (final KeyFold fold : union.keys) {
					agree("today, per key", fold.expected, foldToday(fold).getCardinality());
					agree("existing array lazy", fold.expected,
						foldExistingArrayLazy(fold).getCardinality());
					for (final int threshold : new int[]{64, 256, 1024}) {
						agree("array first " + threshold, fold.expected,
							foldArrayFirst(fold, threshold).getCardinality());
					}
				}
			}
		}

		/**
		 * Asserts one arm reproduced a key's recorded cardinality.
		 *
		 * @param arm      the arm's name, for the message
		 * @param expected what the dump recorded
		 * @param actual   what the arm produced
		 */
		private static void agree(@Nonnull final String arm, final int expected, final int actual) {
			if (expected != actual) {
				throw new IllegalStateException(
					"Arm `" + arm + "` produced " + actual + " values where the dump recorded " + expected + "!"
				);
			}
		}

		/**
		 * Reads the unions of one stratum out of the dump.
		 *
		 * @param path the dump
		 * @param low  smallest input count of the stratum, inclusive
		 * @param high largest input count of the stratum, inclusive
		 * @return the unions
		 */
		@Nonnull
		private static ReplayedUnion[] read(@Nonnull final Path path, final int low, final int high) {
			if (!Files.isReadable(path)) {
				throw new IllegalArgumentException("Union dump `" + path + "` is not readable!");
			}
			final List<ReplayedUnion> collected = new ArrayList<>(128);
			try (
				final DataInputStream in = new DataInputStream(
					new BufferedInputStream(Files.newInputStream(path), 1 << 20)
				)
			) {
				if (in.readInt() != MAGIC) {
					throw new IllegalArgumentException("`" + path + "` is not a union dump!");
				}
				in.readInt();
				final int unionCount = in.readInt();
				for (int u = 0; u < unionCount; u++) {
					final int inputCount = in.readInt();
					// containers are grouped by key across inputs, preserving the fold order within a key
					final Map<Integer, List<Object>> payloads = new LinkedHashMap<>();
					final Map<Integer, List<Byte>> types = new LinkedHashMap<>();
					final PersistentRoaringBitmap[] bitmaps = new PersistentRoaringBitmap[inputCount];
					for (int i = 0; i < inputCount; i++) {
						final int containerCount = in.readInt();
						final List<int[]> values = new ArrayList<>(containerCount);
						int total = 0;
						for (int c = 0; c < containerCount; c++) {
							final int key = in.readChar();
							final byte type = in.readByte();
							final Object payload = readSide(in, type);
							payloads.computeIfAbsent(key, k -> new ArrayList<>()).add(payload);
							types.computeIfAbsent(key, k -> new ArrayList<>()).add(type);
							final int[] absolute = expand(key, type, payload);
							values.add(absolute);
							total += absolute.length;
						}
						final int[] flat = new int[total];
						int at = 0;
						for (final int[] chunk : values) {
							System.arraycopy(chunk, 0, flat, at, chunk.length);
							at += chunk.length;
						}
						bitmaps[i] = PersistentRoaringBitmap.bitmapOf(flat);
					}
					final int resultKeys = in.readInt();
					final Map<Integer, Integer> expected = new LinkedHashMap<>();
					int expectedTotal = 0;
					for (int r = 0; r < resultKeys; r++) {
						final int key = in.readChar();
						final int cardinality = in.readInt();
						expected.put(key, cardinality);
						expectedTotal += cardinality;
					}
					if (inputCount >= low && inputCount <= high) {
						collected.add(new ReplayedUnion(
							inputCount, buildKeys(payloads, types, expected), bitmaps, expectedTotal
						));
					}
				}
			} catch (final IOException e) {
				throw new UncheckedIOException("Cannot read union dump `" + path + "`!", e);
			}
			return collected.toArray(ReplayedUnion[]::new);
		}

		/**
		 * Turns the per-key payload lists into the fold descriptors the arms use.
		 *
		 * @param payloads containers per key, in fold order
		 * @param types    their types, in the same order
		 * @param expected the recorded result cardinality per key
		 * @return one descriptor per key
		 */
		@Nonnull
		private static KeyFold[] buildKeys(
			@Nonnull final Map<Integer, List<Object>> payloads, @Nonnull final Map<Integer, List<Byte>> types,
			@Nonnull final Map<Integer, Integer> expected) {
			final KeyFold[] folds = new KeyFold[payloads.size()];
			int index = 0;
			for (final Map.Entry<Integer, List<Object>> entry : payloads.entrySet()) {
				final int key = entry.getKey();
				final List<Object> list = entry.getValue();
				final List<Byte> typeList = types.get(key);
				final int size = list.size();
				final byte[] typeArray = new byte[size];
				final char[][] arrays = new char[size][];
				final int[] lengths = new int[size];
				final long[][] bitmaps = new long[size][];
				int leading = 0;
				int longest = 1;
				boolean stillLeading = true;
				for (int i = 0; i < size; i++) {
					typeArray[i] = typeList.get(i);
					if (typeArray[i] == TYPE_ARRAY) {
						arrays[i] = (char[]) list.get(i);
						lengths[i] = arrays[i].length;
						longest = Math.max(longest, lengths[i]);
						if (stillLeading) {
							leading++;
						}
					} else {
						stillLeading = false;
						if (typeArray[i] == TYPE_BITMAP) {
							bitmaps[i] = (long[]) list.get(i);
						} else {
							throw new IllegalStateException("Run containers are not replayed by this harness!");
						}
					}
				}
				final Integer recorded = expected.get(key);
				if (recorded == null) {
					throw new IllegalStateException("The dump records no result for key " + key + "!");
				}
				folds[index++] = new KeyFold(typeArray, arrays, lengths, bitmaps, leading, longest, recorded);
			}
			return folds;
		}

		/**
		 * Expands one container into absolute 32-bit values, so an input bitmap can be rebuilt from it.
		 *
		 * @param key     the container's high 16 bits
		 * @param type    the container's type
		 * @param payload its payload
		 * @return the absolute values it holds
		 */
		@Nonnull
		private static int[] expand(final int key, final byte type, @Nonnull final Object payload) {
			final int high = key << 16;
			if (type == TYPE_ARRAY) {
				final char[] values = (char[]) payload;
				final int[] result = new int[values.length];
				for (int i = 0; i < values.length; i++) {
					result[i] = high | values[i];
				}
				return result;
			}
			final long[] words = (long[]) payload;
			int count = 0;
			for (final long word : words) {
				count += Long.bitCount(word);
			}
			final int[] result = new int[count];
			int at = 0;
			for (int w = 0; w < words.length; w++) {
				long bits = words[w];
				final int base = w << 6;
				while (bits != 0) {
					result[at++] = high | (base + Long.numberOfTrailingZeros(bits));
					bits &= bits - 1;
				}
			}
			return result;
		}

		/**
		 * Reads one container payload.
		 *
		 * @param in   the stream positioned at a payload
		 * @param type the container's type
		 * @return a `char[]` for an array container, a `long[1024]` for a bitmap container
		 * @throws IOException when the dump is truncated
		 */
		@Nonnull
		private static Object readSide(@Nonnull final DataInputStream in, final byte type) throws IOException {
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
			}
			final char[] runs = new char[2 * in.readInt()];
			for (int i = 0; i < runs.length; i++) {
				runs[i] = in.readChar();
			}
			return runs;
		}
	}

}
