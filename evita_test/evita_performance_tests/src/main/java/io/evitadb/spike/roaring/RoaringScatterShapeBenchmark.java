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
import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * `lazyIOR(Bitmap, Array)` scatter, measured **per operation** rather than per batch.
 *
 * ## Why this benchmark exists
 *
 * The batch replay put the word-batched scatter at 1.42x over today's per-value loop, and an end-to-end run on
 * a quiet box then read the catalog-wide facet summary 7.3 % *slower* with that change in the tree. Both cannot
 * describe the same operation, and the batch replay is the one with two candidate defects:
 *
 * **It is value-weighted where the engine is operation-weighted.** One invocation of the replay scatters all
 * 300 sampled arrays, so its time is dominated by the long tail - median 4 values but mean 42.2, p90 78, max
 * 2,250 - and across all values 81.3 % share a word with their predecessor, which is where the per-word loop's
 * 5.35x reduction in read-modify-writes comes from. The engine performs 48.6 million *operations* whose median
 * is 4 values, and 121 of the 187 sampled arrays holding 8 values or fewer have **every value in its own
 * word**. On those the per-word loop performs exactly the same read-modify-writes as the per-value loop and
 * adds a data-dependent branch per value.
 *
 * **It replays the same operands in the same order every invocation**, so the word-change branch is trained on
 * a fixed sequence that the engine never sees.
 *
 * So this benchmark measures **one scatter per invocation**, never a batch, and every shape advances through a
 * pool so no invocation repeats its predecessor's operands. `repeated_n4_distinct` is the control that does
 * replay one fixed array every invocation, so the training effect is measured rather than argued.
 *
 * ## Reading it
 *
 * A score is nanoseconds for one `lazyIOR(Bitmap, Array)` at the named shape. The interesting comparison is
 * `perWord` against `perValue` at `n4_distinct` and at `real_opweighted`, because those are the shapes the
 * engine actually performs; `n4_clustered` and `n64_clustered8` are the regime the batch replay was measuring.
 *
 * **The destinations accumulate within an iteration** and are restored from pristine copies between
 * iterations, outside the timed region. That changes the data, not the work: the read-modify-write executes
 * whether or not the bit was already set, and the per-word loop's branch reads the *values*, never the bitmap,
 * so a saturated destination cannot flatter or punish either arm.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class RoaringScatterShapeBenchmark {

	/**
	 * `"ROAC"` - the operand dump's magic number.
	 */
	private static final int OPERAND_MAGIC = 0x524F4143;
	/**
	 * Operation id of `lazyIOR` in the census hook's numbering.
	 */
	private static final int OP_LAZY_IOR = 4;
	/**
	 * Container type id of an array container.
	 */
	private static final int TYPE_ARRAY = 0;
	/**
	 * Container type id of a bitmap container.
	 */
	private static final int TYPE_BITMAP = 1;
	/**
	 * Distinct operand arrays a generated shape draws from. Large enough that the word-change branch cannot be
	 * trained on the sequence, and a power of two only so the pool is easy to reason about - the index advance
	 * below uses a compare, not a division, so the size need not be one.
	 */
	private static final int POOL = 4096;
	/**
	 * Cardinality of a generated destination bitmap - the median the census measured for the bitmap side of a
	 * real `lazyIOR(Bitmap, Array)`.
	 */
	private static final int DESTINATION_CARDINALITY = 4308;
	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/**
	 * Today's scatter: one read-modify-write per value.
	 *
	 * @param shapes    the fixture
	 * @param blackhole consumes the destination
	 */
	@Benchmark
	public void perValue(@Nonnull final Shapes shapes, @Nonnull final Blackhole blackhole) {
		final char[] values = shapes.arrays[shapes.advanceArray()];
		final long[] destination = shapes.destinations[shapes.advanceDestination()];
		ScatterKernels.scatterPerValue(destination, values, values.length);
		blackhole.consume(destination);
	}

	/**
	 * The word-batched scatter: one read-modify-write per distinct word, at the cost of a data-dependent
	 * branch per value.
	 *
	 * @param shapes    the fixture
	 * @param blackhole consumes the destination
	 */
	@Benchmark
	public void perWord(@Nonnull final Shapes shapes, @Nonnull final Blackhole blackhole) {
		final char[] values = shapes.arrays[shapes.advanceArray()];
		final long[] destination = shapes.destinations[shapes.advanceDestination()];
		ScatterKernels.scatterPerWord(destination, values, values.length);
		blackhole.consume(destination);
	}

	/**
	 * An operand pool, a destination pool and two cursors that advance independently.
	 */
	@State(Scope.Benchmark)
	public static class Shapes {

		/**
		 * `n<values>_distinct` - every value in its own word; `n4_clustered` - all four in one word;
		 * `n64_clustered8` - sixty-four values in eight words; `real_opweighted` - the 300 real
		 * `lazyIOR(Bitmap, Array)` arrays, one per invocation, against the 256 real bitmaps they were scattered
		 * into; `repeated_n4_distinct` - the `n4_distinct` pool with the cursor frozen, which is the control
		 * that measures what replaying one fixed operand is worth.
		 */
		@Param({
			"n4_distinct", "n4_clustered",
			"n64_distinct", "n64_clustered8",
			"real_opweighted", "repeated_n4_distinct"
		})
		public String shape;

		/**
		 * How many destination bitmaps the shape cycles through, independently of the operand pool.
		 *
		 * This is a `@Param` rather than a constant because it is a hypothesis under test, not a detail. 256
		 * bitmaps of 8 KiB is 2 MB, which spills L2 and makes a read-modify-write a likely cache miss -
		 * flattering whichever loop performs fewer of them. In the engine one key's bitmap receives a long run
		 * of arrays in succession and stays L1-hot, where a saved read-modify-write is worth much less.
		 * Running the same shapes at a pool of one measures that difference instead of assuming it.
		 */
		@Param({"256"})
		public String destinationPool;

		/**
		 * Where the `real_opweighted` shape replays its operands from. It is a `@Param` rather than a constant
		 * so it is part of the benchmark's identity and is written into the result JSON - a row measured
		 * against a different corpus is then told apart by reading the result, not by remembering.
		 *
		 * The default is deliberately not a path: the dump is captured from a running engine and is not
		 * carried in the tree, so there is nothing this could point at that would be right for anyone. Every
		 * shape but `real_opweighted` generates its operands and ignores this entirely.
		 */
		@Param({"<path-to>/operands.bin"})
		public String dump;

		/**
		 * The operand arrays, ascending and duplicate-free.
		 */
		public char[][] arrays;
		/**
		 * The destination bitmaps, restored from `pristine` between iterations.
		 */
		public long[][] destinations;
		/**
		 * The copies the destinations are restored from.
		 */
		public long[][] pristine;
		/**
		 * Cursor into `arrays`.
		 */
		public int arrayIndex;
		/**
		 * How far the operand cursor moves per invocation - one normally, zero for the repeated control.
		 */
		public int arrayStride;
		/**
		 * Cursor into `destinations`.
		 */
		public int destinationIndex;

		/**
		 * Advances the operand cursor and returns the index to use. A compare rather than a modulo, so the two
		 * arms carry identical bookkeeping; a stride of zero freezes the cursor at the first operand.
		 *
		 * @return index of the operand for this invocation
		 */
		public int advanceArray() {
			int next = this.arrayIndex + this.arrayStride;
			if (next >= this.arrays.length) {
				next = 0;
			}
			this.arrayIndex = next;
			return next;
		}

		/**
		 * Advances the destination cursor, always by one, so operand and destination pair differently for
		 * `lcm(pool, 256)` invocations.
		 *
		 * @return index of the destination for this invocation
		 */
		public int advanceDestination() {
			int next = this.destinationIndex + 1;
			if (next >= this.destinations.length) {
				next = 0;
			}
			this.destinationIndex = next;
			return next;
		}

		@Setup(Level.Trial)
		public void setUp() {
			final Random random = new Random(20260919L);
			this.arrayStride = 1;
			switch (this.shape) {
				case "n4_distinct" -> this.arrays = generate(random, POOL, 4, 1);
				case "n4_clustered" -> this.arrays = generate(random, POOL, 4, 4);
				case "n64_distinct" -> this.arrays = generate(random, POOL, 64, 1);
				case "n64_clustered8" -> this.arrays = generate(random, POOL, 64, 8);
				case "repeated_n4_distinct" -> {
					this.arrays = generate(random, POOL, 4, 1);
					// the control: the same pool, but every invocation replays arrays[0]
					this.arrayStride = 0;
				}
				case "real_opweighted" -> this.arrays = readRealArrays(this.dump);
				default -> throw new IllegalArgumentException(
					"Shape `" + this.shape + "` is not one of n4_distinct, n4_clustered, n64_distinct, " +
						"n64_clustered8, real_opweighted, repeated_n4_distinct!"
				);
			}
			final int pool;
			try {
				pool = Integer.parseInt(this.destinationPool);
			} catch (final NumberFormatException e) {
				throw new IllegalArgumentException(
					"Destination pool `" + this.destinationPool + "` is not a number!", e
				);
			}
			if (pool < 1) {
				throw new IllegalArgumentException("Destination pool must hold at least one bitmap!");
			}
			this.pristine = "real_opweighted".equals(this.shape)
				? readRealDestinations(pool, this.dump)
				: generateDestinations(random, pool);
			this.destinations = new long[this.pristine.length][];
			restore();
			verify();
			describe();
		}

		/**
		 * Restores every destination from its pristine copy. Runs between iterations, never inside a
		 * measurement.
		 */
		@Setup(Level.Iteration)
		public void restore() {
			for (int i = 0; i < this.pristine.length; i++) {
				this.destinations[i] = this.pristine[i].clone();
			}
		}

		/**
		 * Builds a pool of operand arrays with the named within-word structure.
		 *
		 * Each array places `valuesPerWord` values in each of `values / valuesPerWord` consecutive words, and
		 * the whole pattern is re-based to a random starting word - which is a random multiple of 64 added to
		 * every value, so the within-word clustering the shape is named for survives the re-basing.
		 *
		 * @param random        the source of randomness
		 * @param count         how many arrays the pool holds
		 * @param values        values per array
		 * @param valuesPerWord how many of them share a word
		 * @return the pool
		 */
		@Nonnull
		private static char[][] generate(
			@Nonnull final Random random, final int count, final int values, final int valuesPerWord) {
			if (values % valuesPerWord != 0) {
				throw new IllegalArgumentException(values + " values do not divide into groups of " + valuesPerWord);
			}
			final int words = values / valuesPerWord;
			final char[][] pool = new char[count][];
			for (int i = 0; i < count; i++) {
				final int startWord = random.nextInt(BitmapWordKernels.WORDS - words);
				final char[] array = new char[values];
				int at = 0;
				for (int w = 0; w < words; w++) {
					final TreeSet<Integer> bits = new TreeSet<>();
					while (bits.size() < valuesPerWord) {
						bits.add(random.nextInt(64));
					}
					for (final Integer bit : bits) {
						array[at++] = (char) (((startWord + w) << 6) + bit);
					}
				}
				pool[i] = array;
			}
			return pool;
		}

		/**
		 * Builds the destination pool at the cardinality the census measured for a real destination.
		 *
		 * @param random the source of randomness
		 * @param size   how many bitmaps the pool holds
		 * @return the pool
		 */
		@Nonnull
		private static long[][] generateDestinations(@Nonnull final Random random, final int size) {
			final long[][] pool = new long[size][];
			for (int i = 0; i < size; i++) {
				final long[] words = new long[BitmapWordKernels.WORDS];
				int set = 0;
				while (set < DESTINATION_CARDINALITY) {
					final int value = random.nextInt(65536);
					if ((words[value >>> 6] & (1L << value)) == 0) {
						words[value >>> 6] |= 1L << value;
						set++;
					}
				}
				pool[i] = words;
			}
			return pool;
		}

		/**
		 * Reads the array sides of the real `lazyIOR(Bitmap, Array)` pairs.
		 *
		 * @return the operand arrays
		 */
		@Nonnull
		private static char[][] readRealArrays(@Nonnull final String dump) {
			final List<char[]> arrays = new ArrayList<>(512);
			read(arrays, null, dump);
			if (arrays.isEmpty()) {
				throw new IllegalStateException("`" + dump + "` holds no lazyIOR(Bitmap, Array) pairs!");
			}
			return arrays.toArray(char[][]::new);
		}

		/**
		 * Reads the bitmap sides of the same pairs, capped at the requested pool size.
		 *
		 * @param requested how many bitmaps the pool should hold
		 * @return the destination bitmaps
		 */
		@Nonnull
		private static long[][] readRealDestinations(final int requested, @Nonnull final String dump) {
			final List<long[]> bitmaps = new ArrayList<>(512);
			read(null, bitmaps, dump);
			if (bitmaps.isEmpty()) {
				throw new IllegalStateException("`" + dump + "` holds no lazyIOR(Bitmap, Array) pairs!");
			}
			return bitmaps.subList(0, Math.min(requested, bitmaps.size())).toArray(long[][]::new);
		}

		/**
		 * Walks the operand dump, collecting the sides of every `lazyIOR(Bitmap, Array)` pair.
		 *
		 * @param arrays  where the array sides go, or `null` to discard them
		 * @param bitmaps where the bitmap sides go, or `null` to discard them
		 */
		private static void read(
			@Nullable final List<char[]> arrays, @Nullable final List<long[]> bitmaps,
			@Nonnull final String dump) {
			final Path path = Path.of(dump);
			if (!Files.isReadable(path)) {
				throw new IllegalArgumentException(
					"Operand dump `" + dump + "` is not readable! The dump is captured from a running engine " +
						"and is not carried in the tree - point `-p dump=` at one you captured yourself."
				);
			}
			try (
				final DataInputStream in = new DataInputStream(
					new BufferedInputStream(Files.newInputStream(path), 1 << 20)
				)
			) {
				if (in.readInt() != OPERAND_MAGIC) {
					throw new IllegalArgumentException("`" + dump + "` is not a container operand dump!");
				}
				in.readInt();
				final int pairs = in.readInt();
				for (int i = 0; i < pairs; i++) {
					final int operation = in.readByte();
					final int leftType = in.readByte();
					final int rightType = in.readByte();
					final Object left = readSide(in, leftType);
					final Object right = readSide(in, rightType);
					if (operation == OP_LAZY_IOR && leftType == TYPE_BITMAP && rightType == TYPE_ARRAY) {
						if (arrays != null) {
							arrays.add((char[]) right);
						}
						if (bitmaps != null) {
							bitmaps.add((long[]) left);
						}
					}
				}
			} catch (final IOException e) {
				throw new UncheckedIOException("Cannot read operand dump `" + dump + "`!", e);
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
			}
			final char[] runs = new char[2 * in.readInt()];
			for (int i = 0; i < runs.length; i++) {
				runs[i] = in.readChar();
			}
			return runs;
		}

		/**
		 * Checks every operand is ascending and duplicate-free, and that the two kernels leave the identical
		 * bitmap behind for each of them. A kernel that disagrees never gets to produce a timing.
		 */
		private void verify() {
			final long[] reference = this.pristine[0];
			for (int i = 0; i < this.arrays.length; i++) {
				final char[] values = this.arrays[i];
				for (int k = 1; k < values.length; k++) {
					if (values[k - 1] >= values[k]) {
						throw new IllegalStateException(
							"Operand " + i + " of shape `" + this.shape + "` is not strictly ascending at " + k + "!"
						);
					}
				}
				final long[] byValue = reference.clone();
				final long[] byWord = reference.clone();
				ScatterKernels.scatterPerValue(byValue, values, values.length);
				ScatterKernels.scatterPerWord(byWord, values, values.length);
				if (!Arrays.equals(byValue, byWord)) {
					throw new IllegalStateException(
						"The two scatter kernels disagree on operand " + i + " of shape `" + this.shape + "`!"
					);
				}
			}
		}

		/**
		 * States what the fixture actually holds, including the one property the whole question turns on: how
		 * often a value shares a word with the value before it.
		 */
		private void describe() {
			long values = 0;
			long distinctWords = 0;
			long sharing = 0;
			int shortest = Integer.MAX_VALUE;
			int longest = 0;
			for (final char[] array : this.arrays) {
				values += array.length;
				shortest = Math.min(shortest, array.length);
				longest = Math.max(longest, array.length);
				int previousWord = -1;
				for (final char value : array) {
					final int word = value >>> 6;
					if (word == previousWord) {
						sharing++;
					} else {
						distinctWords++;
					}
					previousWord = word;
				}
			}
			System.out.printf(
				"# scatter: shape=%s source=%s operands=%d stride=%d destinations=%d values=%d (min %d, max %d) "
					+ "distinctWords=%d sharingWithPredecessor=%.1f%% rmwReduction=%.2fx%n",
				this.shape, "real_opweighted".equals(this.shape) ? this.dump : "generated",
				this.arrays.length, this.arrayStride, this.destinations.length, values,
				shortest, longest, distinctWords, 100.0 * sharing / values,
				(double) values / distinctWords
			);
		}
	}

}
