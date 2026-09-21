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

import javax.annotation.Nonnull;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * `ArrayContainer.hashCode` - 3.8 % of facet-query CPU, self time, in the production profile.
 *
 * The four arms answer two different questions. The vector arms answer "how much faster can the loop be made";
 * {@link HashKernels#lastSeven} answers "how much of the loop is needed at all", and the answer is seven
 * characters, because the shipped recurrence is base 32 and `32^7` is zero in an int. See {@link HashKernels}
 * for the proof and for why the value is unchanged.
 *
 * Both are worth measuring. A reader who only sees the constant-time arm win by three orders of magnitude will
 * not know whether the vector formulation was ever a reasonable idea, and the record should say.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RoaringHashBenchmark {

	/**
	 * `"ROAC"` - the operand dump's magic number.
	 */
	private static final int MAGIC = 0x524F4143;

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/**
	 * The shipped loop over every container in the fixture.
	 *
	 * @param hashes the fixture
	 * @return the summed hash, so nothing can be optimised away
	 */
	@Benchmark
	public int hashToday(@Nonnull final Hashes hashes) {
		int total = 0;
		for (int i = 0; i < hashes.contents.length; i++) {
			total += HashKernels.today(hashes.contents[i], hashes.contents[i].length);
		}
		return total;
	}

	/**
	 * The constant-time form that returns the identical value.
	 *
	 * @param hashes the fixture
	 * @return the summed hash
	 */
	@Benchmark
	public int hashLastSeven(@Nonnull final Hashes hashes) {
		int total = 0;
		for (int i = 0; i < hashes.contents.length; i++) {
			total += HashKernels.lastSeven(hashes.contents[i], hashes.contents[i].length);
		}
		return total;
	}

	/**
	 * Sixteen characters per vector step.
	 *
	 * @param hashes the fixture
	 * @return the summed hash
	 */
	@Benchmark
	public int hashVector16(@Nonnull final Hashes hashes) {
		int total = 0;
		for (int i = 0; i < hashes.contents.length; i++) {
			total += HashKernels.vector16(hashes.contents[i], hashes.contents[i].length);
		}
		return total;
	}

	/**
	 * Thirty-two characters per vector step.
	 *
	 * @param hashes the fixture
	 * @return the summed hash
	 */
	@Benchmark
	public int hashVector32(@Nonnull final Hashes hashes) {
		int total = 0;
		for (int i = 0; i < hashes.contents.length; i++) {
			total += HashKernels.vector32(hashes.contents[i], hashes.contents[i].length);
		}
		return total;
	}

	/**
	 * Container payloads to hash: either one generated container of a named cardinality, or every array
	 * container in a replayed operand dump.
	 */
	@State(Scope.Benchmark)
	public static class Hashes {

		/**
		 * `c<n>` - one generated container of `n` values; anything else is read as the path of an operand dump
		 * whose array containers are hashed, so a row can be read against the real container population.
		 */
		@Param({"c4", "c16", "c64", "c256", "c1024", "c4096"})
		public String shape;

		/**
		 * The payloads being hashed.
		 */
		public char[][] contents;

		@Setup
		public void setUp() {
			if (this.shape.matches("c\\d+")) {
				final int cardinality = Integer.parseInt(this.shape.substring(1));
				final Random random = new Random(20260918L);
				final TreeSet<Integer> values = new TreeSet<>();
				while (values.size() < cardinality) {
					values.add(random.nextInt(65536));
				}
				final char[] payload = new char[values.size()];
				int index = 0;
				for (final Integer value : values) {
					payload[index++] = (char) value.intValue();
				}
				this.contents = new char[][]{payload};
			} else {
				this.contents = readArrayContainers(Path.of(this.shape));
			}
			verify();
			long total = 0;
			for (final char[] content : this.contents) {
				total += content.length;
			}
			System.out.printf(
				"# hashes: shape=%s containers=%d values=%d mean=%.1f%n",
				this.shape, this.contents.length, total, (double) total / this.contents.length
			);
		}

		/**
		 * Checks all four arms produce the identical `int` on every payload. They must: the constant-time form
		 * is an algebraic identity, not an approximation, and a vector formulation that rounded differently
		 * would change a value callers already depend on.
		 */
		private void verify() {
			for (final char[] content : this.contents) {
				final int expected = HashKernels.today(content, content.length);
				agree("lastSeven", expected, HashKernels.lastSeven(content, content.length));
				agree("vector16", expected, HashKernels.vector16(content, content.length));
				agree("vector32", expected, HashKernels.vector32(content, content.length));
			}
		}

		/**
		 * Asserts one arm produced the shipped value.
		 *
		 * @param arm      the arm's name, for the message
		 * @param expected what the shipped loop returns
		 * @param actual   what the arm returned
		 */
		private void agree(@Nonnull final String arm, final int expected, final int actual) {
			if (expected != actual) {
				throw new IllegalStateException(
					"Arm `" + arm + "` returned " + actual + " where the shipped loop returns " + expected +
						" on shape `" + this.shape + "`!"
				);
			}
		}

		/**
		 * Reads every array-container payload out of an operand dump.
		 *
		 * @param path the dump
		 * @return the payloads
		 */
		@Nonnull
		private static char[][] readArrayContainers(@Nonnull final Path path) {
			if (!Files.isReadable(path)) {
				throw new IllegalArgumentException("Shape is neither `c<n>` nor a readable operand dump: " + path);
			}
			final List<char[]> payloads = new ArrayList<>(4096);
			try (
				final DataInputStream in = new DataInputStream(
					new BufferedInputStream(Files.newInputStream(path), 1 << 20)
				)
			) {
				if (in.readInt() != MAGIC) {
					throw new IllegalArgumentException("`" + path + "` is not a container operand dump!");
				}
				in.readInt();
				final int pairs = in.readInt();
				for (int i = 0; i < pairs; i++) {
					in.readByte();
					final int leftType = in.readByte();
					final int rightType = in.readByte();
					readSide(in, leftType, payloads);
					readSide(in, rightType, payloads);
				}
			} catch (final IOException e) {
				throw new UncheckedIOException("Cannot read operand dump `" + path + "`!", e);
			}
			if (payloads.isEmpty()) {
				throw new IllegalStateException("`" + path + "` holds no array containers!");
			}
			return payloads.toArray(char[][]::new);
		}

		/**
		 * Reads one operand, keeping it only when it is an array container.
		 *
		 * @param in       the stream positioned at an operand
		 * @param type     the operand's container type
		 * @param payloads where an array container's payload is collected
		 * @throws IOException when the dump is truncated
		 */
		private static void readSide(
			@Nonnull final DataInputStream in, final int type, @Nonnull final List<char[]> payloads)
			throws IOException {
			in.readInt();
			if (type == 0) {
				final char[] values = new char[in.readInt()];
				for (int i = 0; i < values.length; i++) {
					values[i] = in.readChar();
				}
				payloads.add(values);
			} else if (type == 1) {
				for (int i = 0; i < BitmapWordKernels.WORDS; i++) {
					in.readLong();
				}
			} else {
				final int runs = 2 * in.readInt();
				for (int i = 0; i < runs; i++) {
					in.readChar();
				}
			}
		}
	}

}
