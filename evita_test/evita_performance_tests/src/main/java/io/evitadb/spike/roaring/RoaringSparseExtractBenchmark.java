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
 * Extraction at the densities a union actually produces.
 *
 * `Util.fillArray` is 5.0 % of facet-query CPU in the production profile, and it is reached almost entirely
 * through the lazy-union repair path - which is to say on bitmaps that are about to demote back to arrays, so
 * on bitmaps holding very few values. 57 % of repaired results hold 64 values or fewer out of 65,536, a density
 * of one tenth of one percent. That is three orders of magnitude sparser than the densities the VBMI2 compress
 * kernel was measured at in family 4, and the two kernels are expected to win opposite regimes.
 *
 * The third arm exists because of that. At one value in a thousand the scalar decoder is not the cost - the
 * 1024-word traversal looking for the one word that is set is. One vector compare rules out eight words at a
 * time and its mask names the ones that survive, which leaves the scalar decoder doing the little work there
 * actually is.
 *
 * Clustered shapes are carried alongside the uniform ones because a union of facet groups does not scatter its
 * values evenly: the values it produces tend to sit in one stretch of the key space, which is the best case for
 * block skipping and says nothing about the uniform case.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RoaringSparseExtractBenchmark {

	/**
	 * `"ROAC"` - the operand dump's magic number.
	 */
	private static final int OPERAND_MAGIC = 0x524F4143;
	/**
	 * `"RORP"` - the repaired-bitmap dump's magic number.
	 */
	private static final int REPAIRED_MAGIC = 0x524F5250;

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/**
	 * Today's extraction - `Util.fillArray`'s loop, one `tzcnt` step per set bit and one read per word.
	 *
	 * @param sparse    the fixture
	 * @param blackhole consumes the count and the destination
	 */
	@Benchmark
	public void sxScalar(@Nonnull final Sparse sparse, @Nonnull final Blackhole blackhole) {
		for (final long[] words : sparse.bitmaps) {
			blackhole.consume(ExtractKernels.scalarExtract(words, sparse.out));
		}
		blackhole.consume(sparse.out);
	}

	/**
	 * The empty-block skip: one vector compare rules out eight words, the scalar decoder handles the rest.
	 *
	 * @param sparse    the fixture
	 * @param blackhole consumes the count and the destination
	 */
	@Benchmark
	public void sxSkipping(@Nonnull final Sparse sparse, @Nonnull final Blackhole blackhole) {
		for (final long[] words : sparse.bitmaps) {
			blackhole.consume(ExtractKernels.skippingExtract(words, sparse.out));
		}
		blackhole.consume(sparse.out);
	}

	/**
	 * The VBMI2 compress kernel, which family 4 measured winning from about 6 % density upwards.
	 *
	 * @param sparse    the fixture
	 * @param blackhole consumes the count and the destination
	 */
	@Benchmark
	public void sxCompress(@Nonnull final Sparse sparse, @Nonnull final Blackhole blackhole) {
		for (final long[] words : sparse.bitmaps) {
			blackhole.consume(ExtractKernels.vectorExtractShortCompress(words, sparse.out));
		}
		blackhole.consume(sparse.out);
	}

	/**
	 * Today's 32-bit extraction - `fillLeastSignificant16bits`.
	 *
	 * @param sparse    the fixture
	 * @param blackhole consumes the count and the destination
	 */
	@Benchmark
	public void sx32Scalar(@Nonnull final Sparse sparse, @Nonnull final Blackhole blackhole) {
		for (final long[] words : sparse.bitmaps) {
			blackhole.consume(ExtractKernels.scalarExtract32(words, sparse.out32, sparse.base));
		}
		blackhole.consume(sparse.out32);
	}

	/**
	 * The empty-block skip applied to the 32-bit form.
	 *
	 * @param sparse    the fixture
	 * @param blackhole consumes the count and the destination
	 */
	@Benchmark
	public void sx32Skipping(@Nonnull final Sparse sparse, @Nonnull final Blackhole blackhole) {
		for (final long[] words : sparse.bitmaps) {
			blackhole.consume(ExtractKernels.skippingExtract32(words, sparse.out32, sparse.base));
		}
		blackhole.consume(sparse.out32);
	}

	/**
	 * The compress kernel applied to the 32-bit form.
	 *
	 * @param sparse    the fixture
	 * @param blackhole consumes the count and the destination
	 */
	@Benchmark
	public void sx32Compress(@Nonnull final Sparse sparse, @Nonnull final Blackhole blackhole) {
		for (final long[] words : sparse.bitmaps) {
			blackhole.consume(ExtractKernels.vectorExtract32(words, sparse.out32, sparse.base));
		}
		blackhole.consume(sparse.out32);
	}

	/**
	 * A bitmap holding a named number of values, spread over the whole key space or packed into one stretch.
	 */
	@State(Scope.Benchmark)
	public static class Sparse {

		/**
		 * `u<n>` - `n` values spread uniformly over 65,536; `c<n>` - `n` values inside a single 1024-value
		 * stretch, which is the shape a facet union tends to produce. Anything else is read as the path of a
		 * container operand dump, and every bitmap container in it becomes part of the batch - so a row can be
		 * read against the real container population rather than a generated one.
		 */
		@Param({
			"u1", "u4", "u16", "u64", "u256", "u1024", "u4096",
			"c16", "c64", "c256"
		})
		public String shape;

		/**
		 * The bitmaps being extracted. A generated shape holds one; a replayed dump holds as many as it
		 * carries, and one invocation extracts all of them.
		 */
		public long[][] bitmaps;
		/**
		 * Destination of the 16-bit forms.
		 */
		public char[] out;
		/**
		 * Destination of the 32-bit forms.
		 */
		public int[] out32;
		/**
		 * The container's high 16 bits.
		 */
		public int base;

		@Setup
		public void setUp() {
			this.out = new char[65536 + 64];
			this.out32 = new int[65536 + 64];
			this.base = 0x12340000;
			if (this.shape.matches("[uc]\\d+")) {
				this.bitmaps = new long[][]{generate()};
			} else {
				this.bitmaps = readBitmapContainers(Path.of(this.shape));
			}
			verify();
			long values = 0;
			int liveWords = 0;
			for (final long[] words : this.bitmaps) {
				for (final long word : words) {
					values += Long.bitCount(word);
					if (word != 0) {
						liveWords++;
					}
				}
			}
			System.out.printf(
				"# sparse: shape=%s bitmaps=%d values=%d (%.4f%% mean density) liveWords=%d/%d%n",
				this.shape, this.bitmaps.length, values,
				100.0 * values / (65536.0 * this.bitmaps.length), liveWords, 1024 * this.bitmaps.length
			);
		}

		/**
		 * Builds the generated fixture the shape names.
		 *
		 * @return the bitmap
		 */
		@Nonnull
		private long[] generate() {
			final boolean clustered = this.shape.charAt(0) == 'c';
			final int cardinality = Integer.parseInt(this.shape.substring(1));
			final Random random = new Random(20260918L);
			final long[] words = new long[BitmapWordKernels.WORDS];
			final TreeSet<Integer> values = new TreeSet<>();
			// a clustered container puts everything inside one 1024-value stretch, chosen away from both ends
			final int origin = clustered ? 30000 : 0;
			final int span = clustered ? 1024 : 65536;
			while (values.size() < cardinality) {
				values.add(origin + random.nextInt(span));
			}
			for (final Integer value : values) {
				words[value >>> 6] |= 1L << value;
			}
			return words;
		}

		/**
		 * Reads the bitmaps out of a dump, dispatching on its magic number.
		 *
		 * Two dumps qualify. `operands.bin` carries container operand pairs and its bitmap sides are bitmaps
		 * the engine was about to combine; `repaired.bin` carries bitmaps exactly as `repairAfterLazy` received
		 * them, which is the population this kernel is actually for - 97 % of them demote straight back to an
		 * array and their median holds twelve non-zero words out of 1024.
		 *
		 * @param path the dump
		 * @return the payloads
		 */
		@Nonnull
		private static long[][] readBitmapContainers(@Nonnull final Path path) {
			if (!Files.isReadable(path)) {
				throw new IllegalArgumentException("Shape is neither `u<n>`, `c<n>` nor a readable dump: " + path);
			}
			final List<long[]> found = new ArrayList<>(1024);
			try (
				final DataInputStream in = new DataInputStream(
					new BufferedInputStream(Files.newInputStream(path), 1 << 20)
				)
			) {
				final int magic = in.readInt();
				in.readInt();
				if (magic == OPERAND_MAGIC) {
					final int pairs = in.readInt();
					for (int i = 0; i < pairs; i++) {
						in.readByte();
						final int leftType = in.readByte();
						final int rightType = in.readByte();
						readSide(in, leftType, found);
						readSide(in, rightType, found);
					}
				} else if (magic == REPAIRED_MAGIC) {
					final int count = in.readInt();
					for (int i = 0; i < count; i++) {
						final long[] words = new long[BitmapWordKernels.WORDS];
						for (int w = 0; w < words.length; w++) {
							words[w] = in.readLong();
						}
						in.readByte();
						final int cardinality = in.readInt();
						// the file states the popcount the repair computed; if it disagrees with the words the
						// dump is being misread, and every number produced from it would be fiction
						int actual = 0;
						for (final long word : words) {
							actual += Long.bitCount(word);
						}
						if (actual != cardinality) {
							throw new IllegalStateException(
								"Record " + i + " of `" + path + "` states cardinality " + cardinality +
									" but its words hold " + actual + "!"
							);
						}
						found.add(words);
					}
				} else {
					throw new IllegalArgumentException(
						"`" + path + "` carries magic 0x" + Integer.toHexString(magic) +
							", which is neither an operand dump nor a repaired-bitmap dump!"
					);
				}
			} catch (final IOException e) {
				throw new UncheckedIOException("Cannot read dump `" + path + "`!", e);
			}
			if (found.isEmpty()) {
				throw new IllegalStateException("`" + path + "` holds no bitmap containers!");
			}
			return found.toArray(long[][]::new);
		}

		/**
		 * Reads one operand, keeping it only when it is a bitmap container.
		 *
		 * @param in    the stream positioned at an operand
		 * @param type  the operand's container type
		 * @param found where a bitmap container's payload is collected
		 * @throws IOException when the dump is truncated
		 */
		private static void readSide(
			@Nonnull final DataInputStream in, final int type, @Nonnull final List<long[]> found)
			throws IOException {
			in.readInt();
			if (type == 0) {
				final int values = in.readInt();
				for (int i = 0; i < values; i++) {
					in.readChar();
				}
			} else if (type == 1) {
				final long[] words = new long[BitmapWordKernels.WORDS];
				for (int i = 0; i < words.length; i++) {
					words[i] = in.readLong();
				}
				found.add(words);
			} else {
				final int runs = 2 * in.readInt();
				for (int i = 0; i < runs; i++) {
					in.readChar();
				}
			}
		}

		/**
		 * Checks every arm writes what `Util.fillArray`'s loop writes, on every bitmap of the fixture.
		 */
		private void verify() {
			final char[] reference = new char[this.out.length];
			final int[] reference32 = new int[this.out32.length];
			for (final long[] words : this.bitmaps) {
				final int count = ExtractKernels.scalarExtract(words, reference);
				same("skipping", reference, count, ExtractKernels.skippingExtract(words, this.out));
				same("compress", reference, count, ExtractKernels.vectorExtractShortCompress(words, this.out));
				final int count32 = ExtractKernels.scalarExtract32(words, reference32, this.base);
				same32("skipping 32", reference32, count32,
					ExtractKernels.skippingExtract32(words, this.out32, this.base));
				same32("compress 32", reference32, count32,
					ExtractKernels.vectorExtract32(words, this.out32, this.base));
			}
		}

		/**
		 * Asserts a 16-bit arm agrees with the reference.
		 *
		 * @param arm            the arm's name, for the message
		 * @param reference      what the reference wrote
		 * @param referenceCount how much of it it wrote
		 * @param actualCount    how much the arm wrote into `out`
		 */
		private void same(
			@Nonnull final String arm, @Nonnull final char[] reference, final int referenceCount,
			final int actualCount) {
			if (referenceCount != actualCount
				|| !Arrays.equals(reference, 0, referenceCount, this.out, 0, actualCount)) {
				throw new IllegalStateException("Arm `" + arm + "` disagrees on shape `" + this.shape + "`!");
			}
		}

		/**
		 * Asserts a 32-bit arm agrees with the reference.
		 *
		 * @param arm            the arm's name, for the message
		 * @param reference      what the reference wrote
		 * @param referenceCount how much of it it wrote
		 * @param actualCount    how much the arm wrote into `out32`
		 */
		private void same32(
			@Nonnull final String arm, @Nonnull final int[] reference, final int referenceCount,
			final int actualCount) {
			if (referenceCount != actualCount
				|| !Arrays.equals(reference, 0, referenceCount, this.out32, 0, actualCount)) {
				throw new IllegalStateException("Arm `" + arm + "` disagrees on shape `" + this.shape + "`!");
			}
		}
	}

}
