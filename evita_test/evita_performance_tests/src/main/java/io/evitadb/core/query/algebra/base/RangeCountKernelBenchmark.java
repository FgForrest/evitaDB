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

package io.evitadb.core.query.algebra.base;

import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.roaringbitmap.IntIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;
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
import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * A/B of the range-index signed-multiplicity computation: the `JoinFormula` -> `DisentangleFormula` pair against
 * the two kernels that replace it.
 *
 * ## Why the fixture is shaped the way it is
 *
 * The shapes come from a census of a production e-commerce catalog rather than from the assumption in the design
 * note. Measured there: of 388,225 range-query formula trees only two reach `JoinFormula` at all, and those sit at
 * **k = 7,602 operands over N = 29,159 endpoints** - about 3.8 record ids per operand. A fixture built around the
 * "handful of large operands" intuition measures the wrong thing entirely, which is why `k4` is included only as
 * the *inexpensive* control and `k7600` is the shape that decides anything.
 *
 * A shape may instead name a **dump file** of operand families captured from a real catalog, so a measured
 * workload can be replayed rather than approximated. Pass it as the parameter value, never as a system property:
 * a `@Param` is part of the benchmark's identity, so it reaches the forked JVM and is written into the result
 * JSON, which makes the result say which operands produced it. An earlier version took the path from
 * `-Drange.operands` and fell back to the generated shape when it could not read it - and the property never
 * reached the fork, so every "replay" silently re-measured the generated shape and looked entirely plausible.
 * Hence the two rules encoded below: **the fixture is named in the result, and an unusable fixture is an error.**
 *
 * Run:
 * {@code java -jar evita_test/evita_performance_tests/target/benchmarks.jar
 * io\.evitadb\.core\.query\.algebra\.base\.RangeCountKernelBenchmark}
 *
 * Replay a dump:
 * {@code ... RangeCountKernelBenchmark -p shape=/path/to/operands.bin}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class RangeCountKernelBenchmark {

	/**
	 * Arbitrary index id; never affects the computed result.
	 */
	private static final long INDEX_ID = 42L;
	/**
	 * Operands in the production shape the census measured on a real e-commerce catalog.
	 */
	private static final int PRODUCTION_OPERANDS = 7602;
	/**
	 * Endpoints in that shape - held constant across the k sweep so only the operand count varies.
	 */
	private static final int PRODUCTION_ENDPOINTS = 29159;
	/**
	 * Record-id span measured on the same catalog.
	 */
	private static final int ID_SPACE = 1_434_000;

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* =========================================================================================== */

	/**
	 * The algorithm this issue replaced, frozen here so the A/B stays runnable after `JoinFormula` and
	 * `DisentangleFormula` were deleted from the engine.
	 *
	 * It is a faithful copy of what those two did together: a k-way merge per family that KEEPS duplicates,
	 * then a two-cursor walk in which each control occurrence cancels one main occurrence. Keeping it means the
	 * headline number in the decision record can be re-measured rather than merely cited.
	 *
	 * @param operands the fixture
	 * @return the records whose signed count is strictly positive
	 */
	@Benchmark
	public Bitmap frozenBaselinePair(Operands operands) {
		final int[] main = mergeKeepingDuplicates(operands.plus);
		final int[] control = mergeKeepingDuplicates(operands.minus);
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		int c = 0;
		for (int m = 0; m < main.length; m++) {
			final int candidate = main[m];
			while (c < control.length && control[c] < candidate) {
				c++;
			}
			if (c < control.length && control[c] == candidate) {
				// swallowed by one control occurrence, which is then spent
				c++;
			} else {
				writer.add(candidate);
			}
		}
		return new BaseBitmap(writer.get());
	}

	/**
	 * The full replacement path: constructs a {@link RangeCountFormula} and computes it, exercising both the
	 * formula wrapper and the kernel it delegates to.
	 *
	 * @param operands the fixture
	 * @return the records whose signed count is strictly positive
	 */
	@Benchmark
	public Bitmap rangeCountFormula(Operands operands) {
		return new RangeCountFormula(INDEX_ID, operands.plus, operands.minus).compute();
	}

	/**
	 * The kernel alone, with the {@link RangeCountFormula} wrapper skipped, isolating the counting kernel's cost
	 * from formula construction and caching overhead.
	 *
	 * @param operands the fixture
	 * @return the records whose signed count is strictly positive
	 */
	@Benchmark
	public Bitmap scatterKernelOnly(Operands operands) {
		return RangeCountKernel.compute(operands.plus, operands.minus);
	}

	// The bit-sliced plane kernel was benchmarked here and LOST (2.6x slower, 87.6 MB/op against this kernel's
	// 2.1 MB) - see documentation/adr/2026-09-18-range-index-counting-kernel.md, Option B. It now lives in test
	// sources as a third independent implementation for the differential test, so it is no longer reachable from
	// this module and its arm is gone with it.

	/**
	 * The k-way merge the deleted `JoinFormula` performed: ascending order, duplicates preserved.
	 *
	 * @param family the operands to merge
	 * @return the ascending, duplicate-carrying merge of the family
	 */
	@Nonnull
	private static int[] mergeKeepingDuplicates(@Nonnull Bitmap[] family) {
		final PriorityQueue<int[]> queue = new PriorityQueue<>(
			Math.max(1, family.length), (a, b) -> Integer.compare(a[0], b[0])
		);
		final IntIterator[] iterators = new IntIterator[family.length];
		for (int i = 0; i < family.length; i++) {
			// the one change under test: min(256, size) rather than a flat 256
			iterators[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(family[i])
				.getBatchIterator()
				.asIntIterator(new int[Math.min(256, Math.max(1, family[i].size()))]);
			if (iterators[i].hasNext()) {
				queue.offer(new int[]{iterators[i].next(), i});
			}
		}
		final CompositeIntArray result = new CompositeIntArray();
		while (!queue.isEmpty()) {
			final int[] head = queue.poll();
			result.add(head[0]);
			final IntIterator it = iterators[head[1]];
			if (it.hasNext()) {
				queue.offer(new int[]{it.next(), head[1]});
			}
		}
		return result.toArray();
	}

	/**
	 * Construction only for the replacement.
	 *
	 * @param operands the fixture
	 * @return the constructed formula, returned so it cannot be optimised away
	 */
	@Benchmark
	public Object rangeCountPlanOnly(Operands operands) {
		return new RangeCountFormula(INDEX_ID, operands.plus, operands.minus);
	}

	/**
	 * The operand families under test.
	 */
	@State(Scope.Benchmark)
	public static class Operands {

		/**
		 * Which measured shape to build: `k<n>` generates `n` operands at the census-measured N and id span
		 * (`k7602` is the production shape, `k4` the cheap control), and anything else is read as the path of an
		 * operand dump to replay.
		 */
		@Param({"k7602", "k4"})
		public String shape;

		/**
		 * Operands contributing `+1`.
		 */
		public Bitmap[] plus;

		/**
		 * Operands contributing `-1`.
		 */
		public Bitmap[] minus;

		@Setup
		public void setUp() {
			// `k<n>` names an operand count; N and the id span are held at the measured production values so the
			// sweep isolates k, which is the axis the crossover lives on. Anything else is a dump to replay - and
			// it is loaded or the run fails, never quietly substituted, because a substituted fixture produces a
			// believable number for a workload nobody measured.
			if (this.shape.matches("k\\d+")) {
				final int operandCount = Integer.parseInt(this.shape.substring(1));
				this.plus = generate(operandCount, PRODUCTION_ENDPOINTS, ID_SPACE, 20260917L);
				this.minus = generate(operandCount, PRODUCTION_ENDPOINTS, ID_SPACE, 20260918L);
			} else {
				final Path dump = Path.of(this.shape);
				if (!Files.isReadable(dump)) {
					throw new IllegalArgumentException(
						"Shape `" + this.shape + "` is neither `k<n>` nor a readable operand dump!"
					);
				}
				load(dump);
			}
			// the run says out loud which families it measured, so a result can be traced back to its fixture
			System.out.printf(
				"# operands: shape=%s k+=%d k-=%d N+=%d N-=%d%n",
				this.shape, this.plus.length, this.minus.length, totalOf(this.plus), totalOf(this.minus)
			);
		}

		/**
		 * Sums the record ids held by an operand family.
		 *
		 * @param family the family to measure
		 * @return total number of record ids across all its bitmaps
		 */
		private static long totalOf(@Nonnull Bitmap[] family) {
			long total = 0;
			for (final Bitmap bitmap : family) {
				total += bitmap.size();
			}
			return total;
		}

		/**
		 * Builds one operand family with the requested operand count and total endpoint count, spreading record
		 * ids uniformly over `idSpace`.
		 *
		 * @param operandCount how many bitmaps the family holds
		 * @param totalSize    how many record ids in total across them
		 * @param idSpace      exclusive upper bound on a record id
		 * @param seed         RNG seed, so a shape is reproducible
		 * @return the family
		 */
		@Nonnull
		private static Bitmap[] generate(int operandCount, int totalSize, int idSpace, long seed) {
			final Random random = new Random(seed);
			final Bitmap[] family = new Bitmap[operandCount];
			// distribute `totalSize` ids across the operands so the family's total is EXACTLY the measured N -
			// rounding per operand would have produced 22,806 where the census measured 29,159
			int remaining = totalSize;
			for (int i = 0; i < operandCount; i++) {
				final int operandsLeft = operandCount - i;
				final int fair = remaining / operandsLeft;
				final int size = Math.max(1, operandsLeft == 1 ? remaining : fair + (random.nextInt(3) - 1));
				remaining -= size;
				final TreeSet<Integer> values = new TreeSet<>();
				while (values.size() < size) {
					values.add(random.nextInt(idSpace) + 1);
				}
				final int[] array = new int[values.size()];
				int index = 0;
				for (final Integer value : values) {
					array[index++] = value;
				}
				// MUST be a TransactionalBitmap, not a BaseBitmap: JoinFormula.includeAdditionalHash takes the
				// cheap getId() path only for a TransactionalLayerProducer and otherwise falls back to hashing
				// getArray() of every operand - a path production never takes, and one that would have made the
				// baseline arm look far slower than it is
				family[i] = new TransactionalBitmap(array);
			}
			return family;
		}

		/**
		 * Replays a dumped operand pair.
		 *
		 * @param file the dump written by the operand-dump probe
		 */
		private void load(@Nonnull Path file) {
			try (final DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
				this.plus = readFamily(in);
				this.minus = readFamily(in);
			} catch (final IOException e) {
				throw new UncheckedIOException("Cannot read operand dump `" + file + "`!", e);
			}
		}

		/**
		 * Reads one family from the dump.
		 *
		 * @param in the stream positioned at a family
		 * @return the family
		 * @throws IOException when the dump is truncated
		 */
		@Nonnull
		private static Bitmap[] readFamily(@Nonnull DataInputStream in) throws IOException {
			final int count = in.readInt();
			final Bitmap[] family = new Bitmap[count];
			for (int i = 0; i < count; i++) {
				final int[] values = new int[in.readInt()];
				for (int j = 0; j < values.length; j++) {
					values[j] = in.readInt();
				}
				family[i] = new TransactionalBitmap(values);
			}
			return family;
		}
	}

}
