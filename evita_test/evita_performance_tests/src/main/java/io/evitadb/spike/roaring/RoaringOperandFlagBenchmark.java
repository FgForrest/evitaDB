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

package io.evitadb.spike.roaring;

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

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Cost of the copy-on-write flags a non-mutating operation leaves on its operands, measured before and after the
 * `lent` model replaced per-slot operand flags.
 *
 * Before, an operation that borrowed some of an operand's containers raised the operand's `shared[]` flag on exactly
 * those slots, reallocating the flag array when it was shorter than the container array. After, it writes one field,
 * `lent`, and the operand expands it to "every slot shared" at its next mutation. The operation itself gets cheaper
 * (no flag-array writes or reallocation on the operand), but an operand that lent anything clones **every** container
 * it later writes, where before it cloned only the borrowed ones. Both sides are measured:
 *
 * - **operation only** - the in-place `or` that borrows the operand's chunks, the range-bounded static `and` that
 *   borrows the chunks inside the range, and the static `or`/`xor`/`andNot`, each on fresh private copies so every
 *   invocation starts from the same flag state;
 * - **operation, then the operand is written** - the same borrowing operation followed by one `add` into every chunk
 *   of the operand, which is where the coarser flag costs clones.
 *
 * Every arm starts with deep copies made by {@link PersistentRoaringBitmap#fromBitmap(PersistentRoaringBitmap)}, so
 * each invocation pays the same copy in both builds; the difference between the builds is the quantity of interest,
 * not the absolute numbers. Run under `-prof gc` and compare `gc.alloc.rate.norm` as well as the time.
 *
 * The receiver holds chunks on even keys and the operand on all keys in `[0, 2 * chunks)`, so half of the operand's
 * chunks are disjoint from the receiver (borrowed by the in-place `or`) and half overlap (combined into private
 * containers). The shape parameter picks sparse array chunks or dense bitmap chunks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RoaringOperandFlagBenchmark {

	/**
	 * Operands shared by every arm, built once per trial and never mutated by a benchmark method: each method works on
	 * deep copies of them.
	 */
	@State(Scope.Benchmark)
	public static class Operands {

		/**
		 * Number of chunks of the receiver; the operand holds twice as many.
		 */
		@Param({"64"})
		public int chunks;

		/**
		 * `array` fills each chunk with 200 values (an array container), `bitmap` with 20,000 (a bitmap container).
		 */
		@Param({"array", "bitmap"})
		public String shape;

		/**
		 * Receiver template: chunks on even keys only.
		 */
		PersistentRoaringBitmap receiver;
		/**
		 * Operand template: chunks on every key in `[0, 2 * chunks)`.
		 */
		PersistentRoaringBitmap operand;
		/**
		 * Lower bound of the range-bounded `and`: the first value of the operand's second quarter of chunks.
		 */
		long rangeStart;
		/**
		 * Upper bound of the range-bounded `and`: the first value past the operand's third quarter of chunks.
		 */
		long rangeEnd;

		/**
		 * Builds both templates from a fixed seed, so every fork and both builds measure identical inputs.
		 */
		@Setup
		public void setUp() {
			final int perChunk = "bitmap".equals(this.shape) ? 20_000 : 200;
			final Random random = new Random(42);
			this.receiver = new PersistentRoaringBitmap();
			this.operand = new PersistentRoaringBitmap();
			for (int key = 0; key < 2 * this.chunks; key++) {
				fill(this.operand, key, perChunk, random);
				if (key % 2 == 0) {
					fill(this.receiver, key, perChunk, random);
				}
			}
			this.rangeStart = (long) (this.chunks / 2) << 16;
			this.rangeEnd = (long) (this.chunks + this.chunks / 2) << 16;
		}

		/**
		 * Adds `count` random values to the chunk `key`.
		 */
		private static void fill(
			final PersistentRoaringBitmap bitmap, final int key, final int count, final Random random
		) {
			for (int i = 0; i < count; i++) {
				bitmap.add((key << 16) | random.nextInt(1 << 16));
			}
		}
	}

	/**
	 * Writes one value into every chunk of the bitmap, which clones each container whose slot is flagged shared.
	 */
	private static void writeEveryChunk(final PersistentRoaringBitmap bitmap, final int keys) {
		for (int key = 0; key < keys; key++) {
			bitmap.add((key << 16) | 0xFFFF);
		}
	}

	/**
	 * In-place `or` of a private receiver copy with a private operand copy; the operand lends its disjoint chunks.
	 */
	@Benchmark
	public PersistentRoaringBitmap inPlaceOr(final Operands operands) {
		final PersistentRoaringBitmap receiver = PersistentRoaringBitmap.fromBitmap(operands.receiver);
		final PersistentRoaringBitmap operand = PersistentRoaringBitmap.fromBitmap(operands.operand);
		receiver.or(operand);
		return operand;
	}

	/**
	 * The in-place `or` above, after which the operand writes into every one of its chunks.
	 */
	@Benchmark
	public PersistentRoaringBitmap inPlaceOrThenWriteOperand(final Operands operands) {
		final PersistentRoaringBitmap receiver = PersistentRoaringBitmap.fromBitmap(operands.receiver);
		final PersistentRoaringBitmap operand = PersistentRoaringBitmap.fromBitmap(operands.operand);
		receiver.or(operand);
		writeEveryChunk(operand, 2 * operands.chunks);
		return operand;
	}

	/**
	 * The in-place `or` above, after which the receiver - which now co-owns the borrowed chunks - writes into every
	 * one of its chunks. Its flags are precise in both builds, so this arm is the control for the two above.
	 */
	@Benchmark
	public PersistentRoaringBitmap inPlaceOrThenWriteReceiver(final Operands operands) {
		final PersistentRoaringBitmap receiver = PersistentRoaringBitmap.fromBitmap(operands.receiver);
		final PersistentRoaringBitmap operand = PersistentRoaringBitmap.fromBitmap(operands.operand);
		receiver.or(operand);
		writeEveryChunk(receiver, 2 * operands.chunks);
		return receiver;
	}

	/**
	 * Range-bounded static `and` of two private copies; both operands lend the chunks inside the range.
	 */
	@Benchmark
	public PersistentRoaringBitmap rangeAnd(final Operands operands) {
		final PersistentRoaringBitmap receiver = PersistentRoaringBitmap.fromBitmap(operands.receiver);
		final PersistentRoaringBitmap operand = PersistentRoaringBitmap.fromBitmap(operands.operand);
		return PersistentRoaringBitmap.and(
			List.of(receiver, operand).iterator(), operands.rangeStart, operands.rangeEnd
		);
	}

	/**
	 * The range-bounded `and` above, after which the second operand writes into every one of its chunks.
	 */
	@Benchmark
	public PersistentRoaringBitmap rangeAndThenWriteOperand(final Operands operands) {
		final PersistentRoaringBitmap receiver = PersistentRoaringBitmap.fromBitmap(operands.receiver);
		final PersistentRoaringBitmap operand = PersistentRoaringBitmap.fromBitmap(operands.operand);
		final PersistentRoaringBitmap result = PersistentRoaringBitmap.and(
			List.of(receiver, operand).iterator(), operands.rangeStart, operands.rangeEnd
		);
		writeEveryChunk(operand, 2 * operands.chunks);
		return result;
	}

	/**
	 * Static `or` of two private copies; both operands were already marked wholesale before the change.
	 */
	@Benchmark
	public PersistentRoaringBitmap staticOr(final Operands operands) {
		final PersistentRoaringBitmap receiver = PersistentRoaringBitmap.fromBitmap(operands.receiver);
		final PersistentRoaringBitmap operand = PersistentRoaringBitmap.fromBitmap(operands.operand);
		return PersistentRoaringBitmap.or(receiver, operand);
	}

	/**
	 * Static `xor` of two private copies.
	 */
	@Benchmark
	public PersistentRoaringBitmap staticXor(final Operands operands) {
		final PersistentRoaringBitmap receiver = PersistentRoaringBitmap.fromBitmap(operands.receiver);
		final PersistentRoaringBitmap operand = PersistentRoaringBitmap.fromBitmap(operands.operand);
		return PersistentRoaringBitmap.xor(receiver, operand);
	}

	/**
	 * Static `andNot` of two private copies.
	 */
	@Benchmark
	public PersistentRoaringBitmap staticAndNot(final Operands operands) {
		final PersistentRoaringBitmap receiver = PersistentRoaringBitmap.fromBitmap(operands.receiver);
		final PersistentRoaringBitmap operand = PersistentRoaringBitmap.fromBitmap(operands.operand);
		return PersistentRoaringBitmap.andNot(operand, receiver);
	}

	/**
	 * The two deep copies every arm starts with, and nothing else: the floor the other arms are read against.
	 */
	@Benchmark
	public PersistentRoaringBitmap copiesOnly(final Operands operands) {
		final PersistentRoaringBitmap receiver = PersistentRoaringBitmap.fromBitmap(operands.receiver);
		final PersistentRoaringBitmap operand = PersistentRoaringBitmap.fromBitmap(operands.operand);
		return receiver.getCardinality() > operand.getCardinality() ? receiver : operand;
	}
}
