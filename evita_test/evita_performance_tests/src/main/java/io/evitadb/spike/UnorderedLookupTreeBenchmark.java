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

package io.evitadb.spike;

import io.evitadb.index.array.OrderKeyConsumer;
import io.evitadb.index.array.UnorderedLookupTree;
import io.evitadb.index.bPlusTree.TransactionalIntToLongBPlusTree;
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
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark of the **write** behaviour of the two-tree backing introduced under issue #760 — the count-augmented
 * position tree {@link UnorderedLookupTree} paired with the no-boxing `int → long` value index
 * {@link TransactionalIntToLongBPlusTree}, exactly the pair that sits behind `TransactionalUnorderedIntArray`.
 *
 * The single property this benchmark guards is **asymptotic write cost**: the array delegate the two-tree backing
 * replaces renumbers an `O(N)` suffix on every positional insert / move, so building and churning a long chain costs
 * `O(N²)`. The tree touches only the `O(log N)` cursor path, so both phases must scale **linearly**. This is a write
 * hot-path benchmark; read addressing and the correctness of the structure are covered by the functional oracle suite
 * and the generational long-running soak, not here.
 *
 * Two phases are measured, each in {@link Mode#SingleShotTime} (the natural unit is "time to apply the whole batch"):
 *
 * 1. {@code buildChain} — builds a single chain `1 → 2 → … → N` via `recordCount` individual `insertAfter` writes,
 *    starting from an empty backing each invocation. Measures the per-write cost as the chain grows.
 * 2. {@code churnChain} — over a pre-built chain, repeatedly moves a random record to sit after another random record
 *    (a predecessor update = remove + re-insert), `churnOperations` times. Measures steady-state move cost at a fixed
 *    chain length.
 *
 * The chain length ({@code recordCount}) and the churn batch size ({@code churnOperations}) are {@link Param}s so the
 * linear (or non-linear) trend can be read directly by comparing successive sizes; doubling `recordCount` should at
 * most double `buildChain` if the backing is genuinely `O(N)`.
 *
 * The benchmarks jar uses a custom main class, so run through JMH's own runner:
 * {@code java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * io\.evitadb\.spike\.UnorderedLookupTreeBenchmark}.
 *
 * Recorded results and the scaling decision this benchmark drives live in this benchmark's results folder
 * `documentation/performance/individual/UnorderedLookupTreeBenchmark/`. Keep the two in sync: when the tree's write
 * path or block size changes, re-run this benchmark and update that document.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
public class UnorderedLookupTreeBenchmark {

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* =========================================================================================== */

	@Benchmark
	public int buildChain(@Nonnull BuildParams params, @Nonnull Blackhole bh) {
		final CompositeIndex index = new CompositeIndex();
		index.addHead(1);
		for (int recordId = 2; recordId <= params.recordCount; recordId++) {
			index.addAfter(recordId - 1, recordId);
		}
		final int size = index.size();
		bh.consume(size);
		return size;
	}

	@Benchmark
	public int churnChain(@Nonnull ChurnState state, @Nonnull Blackhole bh) {
		final CompositeIndex index = state.index;
		final int recordCount = state.recordCount;
		final Random random = state.random;
		for (int op = 0; op < state.churnOperations; op++) {
			final int moved = 1 + random.nextInt(recordCount);
			int anchor = 1 + random.nextInt(recordCount);
			if (anchor == moved) {
				anchor = moved == recordCount ? moved - 1 : moved + 1;
			}
			index.remove(moved);
			index.addAfter(anchor, moved);
		}
		final int size = index.size();
		bh.consume(size);
		return size;
	}

	/**
	 * P — the READ cost the sort-attribute insert path actually pays. `SortIndexChanges.computePreviousRecord`
	 * binary-searches a value's record block through positional reads, and every probe is a fresh root-to-leaf
	 * order-statistic descent — `UnorderedLookupTree.getRecordAt`, the 19.4 % self-time frame of the WARM_UP profile
	 * behind issue #1332, which no benchmark in this suite reached before (`buildChain` / `churnChain` are both
	 * write-only).
	 *
	 * The probe sequence is pre-generated in `@Setup`, never inside the measured method, so the measurement contains
	 * the tree descents and nothing else. Parameterising on `blockWidth` is what lets a descent-COUNT fix (a
	 * re-seekable cursor) be told apart from a per-level COST fix (prefix-sum child counts): the former moves only
	 * wide blocks, the latter moves every width including 1.
	 */
	@Benchmark
	public int positionalRead(@Nonnull PositionalReadState state, @Nonnull Blackhole bh) {
		final UnorderedLookupTree tree = state.index.positionTree;
		final int[] probes = state.probes;
		int sum = 0;
		for (int i = 0; i < probes.length; i++) {
			sum += tree.getRecordAt(probes[i]);
		}
		bh.consume(sum);
		return sum;
	}

	/* =========================================================================================== */

	/**
	 * Supplies the chain length for {@link #buildChain(BuildParams, Blackhole)}. No pre-built state - the build phase
	 * starts from an empty backing on every invocation, which is the whole point of measuring it.
	 */
	@State(Scope.Benchmark)
	public static class BuildParams {
		/** Number of records appended to grow the chain; doubling it should at most double a linear build. */
		@Param({"1000000", "10000000"})
		public int recordCount;
	}

	/**
	 * Holds a pre-built chain of {@link #recordCount} records (built once per trial, outside the measured method) plus
	 * the fixed-seed random sequence that drives {@link #churnChain(ChurnState, Blackhole)} so the churn workload is
	 * reproducible across runs.
	 */
	@State(Scope.Benchmark)
	public static class ChurnState {
		/** Length of the pre-built chain the churn operates over. */
		@Param({"1000000", "10000000"})
		public int recordCount;
		/** Number of predecessor-move operations applied per measured invocation. */
		@Param({"1000000"})
		public int churnOperations;

		CompositeIndex index;
		Random random;

		@Setup(Level.Trial)
		public void setUp() {
			final CompositeIndex theIndex = new CompositeIndex();
			theIndex.addHead(1);
			for (int recordId = 2; recordId <= this.recordCount; recordId++) {
				theIndex.addAfter(recordId - 1, recordId);
			}
			this.index = theIndex;
			this.random = new Random(42);
		}
	}

	/**
	 * Pre-built chain plus a pre-generated probe sequence for {@link UnorderedLookupTreeBenchmark#positionalRead}.
	 *
	 * The probe sequence replays exactly the positions a binary search over a block of {@link #blockWidth} records
	 * issues — the access pattern `SortIndexChanges.computePreviousRecord` produces on every sort-attribute insert.
	 * Each simulated insert picks a random block and a random target offset inside it, then records the midpoints the
	 * search visits. Short searches are padded so every simulated insert contributes the same probe count, keeping
	 * `ns/op` directly comparable across `blockWidth` values.
	 */
	@State(Scope.Benchmark)
	public static class PositionalReadState {
		/**
		 * Number of simulated sort-attribute inserts whose probe sequences are concatenated into {@link #probes}.
		 */
		private static final int SIMULATED_INSERTS = 10_000;

		/** Length of the pre-built chain the probes read from. */
		@Param({"1000000", "10000000"})
		public int recordCount;
		/** Width of the simulated value block the binary search runs over. */
		@Param({"1", "10", "100", "1000", "10000"})
		public int blockWidth;

		CompositeIndex index;
		/** Flattened probe positions: `ceil(log2(blockWidth))` consecutive probes per simulated insert. */
		int[] probes;

		@Setup(Level.Trial)
		public void setUp() {
			final CompositeIndex theIndex = new CompositeIndex();
			theIndex.addHead(1);
			for (int recordId = 2; recordId <= this.recordCount; recordId++) {
				theIndex.addAfter(recordId - 1, recordId);
			}
			this.index = theIndex;
			this.probes = generateProbes(this.recordCount, this.blockWidth);
		}

		/**
		 * Generates the concatenated probe sequences of {@link #SIMULATED_INSERTS} binary searches, each over a block
		 * of `blockWidth` consecutive positions starting at a pseudo-random offset.
		 *
		 * @param recordCount total number of positions available in the tree
		 * @param blockWidth  width of the simulated value block
		 * @return the flattened probe positions, `SIMULATED_INSERTS * ceil(log2(blockWidth))` of them
		 */
		@Nonnull
		private static int[] generateProbes(int recordCount, int blockWidth) {
			final Random random = new Random(42);
			// ceil(log2(blockWidth)) - the number of probes a binary search over `blockWidth` slots issues
			final int probesPerSearch = Math.max(1, 32 - Integer.numberOfLeadingZeros(blockWidth));
			final int[] result = new int[SIMULATED_INSERTS * probesPerSearch];
			int cursor = 0;
			for (int i = 0; i < SIMULATED_INSERTS; i++) {
				final int blockStart = blockWidth >= recordCount ? 0 : random.nextInt(recordCount - blockWidth);
				// the position the inserted record id would occupy inside the block - the search target
				final int target = blockStart + random.nextInt(blockWidth);
				int low = blockStart;
				int high = blockStart + blockWidth - 1;
				int emitted = 0;
				while (low <= high && emitted < probesPerSearch) {
					final int middle = (low + high) >>> 1;
					result[cursor++] = middle;
					emitted++;
					if (middle < target) {
						low = middle + 1;
					} else {
						high = middle - 1;
					}
				}
				// pad a short search so every simulated insert contributes the same probe count
				while (emitted < probesPerSearch) {
					result[cursor++] = target;
					emitted++;
				}
			}
			return result;
		}
	}

	/* =========================================================================================== */

	/**
	 * Pairs the position tree with the real `int → long` value index, mirroring the non-transactional coordination the
	 * composite façade performs. The order-key consumer writes every assignment into the value index (overwrite
	 * semantics), so the position tree and the value index stay coherent across splits and re-spacing.
	 */
	private static final class CompositeIndex implements OrderKeyConsumer {
		@Nonnull final UnorderedLookupTree positionTree = new UnorderedLookupTree();
		@Nonnull final TransactionalIntToLongBPlusTree valueIndex = new TransactionalIntToLongBPlusTree();

		@Override
		public void accept(int recordId, long orderKey) {
			this.valueIndex.insert(recordId, orderKey);
		}

		void addHead(int recordId) {
			this.positionTree.insertAtPosition(0, recordId, this);
		}

		void addAfter(int previousRecordId, int recordId) {
			this.positionTree.insertAfter(this.valueIndex.search(previousRecordId).orElseThrow(), previousRecordId, recordId, this);
		}

		void remove(int recordId) {
			this.positionTree.removeByOrderKey(this.valueIndex.search(recordId).orElseThrow(), recordId, this);
			this.valueIndex.delete(recordId);
		}

		int size() {
			return this.positionTree.size();
		}
	}

}
