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

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.TransactionHandler;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.index.bPlusTree.TransactionalLongBPlusTree;
import io.evitadb.index.range.TransactionalRangePoint;
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
import java.util.Iterator;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Block-size sensitivity benchmark for the B+ tree that backs {@link io.evitadb.index.range.RangeIndex} (the range
 * companion of every {@link io.evitadb.index.attribute.FilterIndex} over a {@link io.evitadb.dataType.Range} type):
 * a {@link TransactionalLongBPlusTree} keyed by the `long` threshold with a {@link TransactionalRangePoint} value
 * (carrying the `starts` / `ends` record bitmaps at that threshold).
 *
 * The range index is already tree-backed, so - like {@link InvertedIndexBlockSizeBenchmark} - this sweeps the **leaf
 * block size** to pick it on data rather than the inherited guess (64). The range workload leans on **full ordered
 * sweeps** (the range-histogram / `rangesIterator` path) and threshold probes, plus a two-points-per-record write on
 * every `addRecord`, so the read-vs-write block-size trade-off is its own question, distinct from the SortIndex one.
 *
 * It uses the **real** {@link TransactionalRangePoint} value (a transactional producer) through the tree's
 * wrapper-aware constructor, and measures the tree directly with faithful replicas of the RangeIndex access paths:
 *
 * - {@code pointLookup} - a single {@link TransactionalLongBPlusTree#search} for a present threshold.
 * - {@code rangeScan} - a bounded forward value-iteration from a random start threshold (partial range query).
 * - {@code fullScan} - the `rangesIterator` / `getRangeHistogramOfAllRecords` full ordered sweep accumulating the
 *   active set sizes.
 * - {@code bulkLoad} - deserialization / restore: build the whole tree from `distinctThresholds` inserts.
 * - {@code commit} - `addRecord` + transaction commit: insert a spread-out batch of fresh thresholds in a transaction
 *   and commit, measuring the `O(touched leaves · block size)` path-copy that grows with block size.
 *
 * Run through JMH's own runner (the benchmarks jar uses a custom main):
 * {@code java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * io\.evitadb\.spike\.RangeIndexBlockSizeBenchmark}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class RangeIndexBlockSizeBenchmark {

	/** Mirrors `RangeIndex.RANGE_POINT_WRAPPER` - adapts a committed value back into the range-point type. */
	private static final Function<Object, TransactionalRangePoint> WRAPPER = o -> (TransactionalRangePoint) o;

	/** Threshold points a single `rangeScan` op walks - keeps the op cost bounded and block-size comparable. */
	private static final int RANGE_SCAN_WINDOW = 2_000;

	/** Fresh thresholds inserted per `commit` op - a representative mid-size transaction touching ~this many leaves. */
	private static final int COMMIT_MUTATIONS = 100;

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* =========================================================================================== */

	@Benchmark
	public int pointLookup(IndexState state, KeyCursor cursor) {
		final long threshold = state.lookupThresholds[cursor.next(state.lookupThresholds.length)];
		final TransactionalRangePoint point = state.tree.search(threshold).orElse(null);
		return point == null ? 0 : point.getStarts().size();
	}

	@Benchmark
	public void rangeScan(IndexState state, KeyCursor cursor, Blackhole bh) {
		final long startThreshold = state.lookupThresholds[cursor.next(state.lookupThresholds.length)];
		final Iterator<TransactionalRangePoint> it = state.tree.greaterOrEqualValueIterator(startThreshold);
		int consumed = 0;
		while (it.hasNext() && consumed < RANGE_SCAN_WINDOW) {
			final TransactionalRangePoint point = it.next();
			bh.consume(point.getStarts().size() + point.getEnds().size());
			consumed++;
		}
	}

	@Benchmark
	public long fullScan(IndexState state) {
		// mirrors the range-histogram sweep: walk every threshold point in order, rolling the active set size
		long activeSet = 0;
		final Iterator<TransactionalRangePoint> it = state.tree.valueIterator();
		while (it.hasNext()) {
			final TransactionalRangePoint point = it.next();
			activeSet += point.getStarts().size();
			activeSet -= point.getEnds().size();
		}
		return activeSet;
	}

	@Benchmark
	public int bulkLoad(IndexState state) {
		return buildTree(state.blockSize, state.distinctThresholds, state.recordsPerPoint).size();
	}

	@Benchmark
	public Object commit(IndexState state) {
		final CommitCapture handler = new CommitCapture(state.tree);
		Transaction.executeInTransactionIfProvided(
			new Transaction(state.txId, handler, false),
			() -> {
				final Transaction tx = Transaction.getTransaction().orElseThrow();
				try {
					// spread COMMIT_MUTATIONS fresh ODD thresholds across the whole tree so the inserts land in
					// distinct leaves (the base tree uses EVEN thresholds, so these never collide); each commit
					// path-copies the touched leaf - the cost that scales with block size
					final int stride = state.distinctThresholds / COMMIT_MUTATIONS;
					for (int j = 0; j < COMMIT_MUTATIONS; j++) {
						final long threshold = 2L * (j * stride) + 1L;
						state.tree.insert(
							threshold,
							new TransactionalRangePoint(threshold, new int[] {(int) threshold}, new int[0])
						);
					}
				} finally {
					// commit happens on close (the handler captures the merged copy); the layers are then discarded,
					// so the base tree is left pristine and the next invocation does identical work
					tx.close();
				}
			}
		);
		return handler.committed;
	}

	/* =========================================================================================== */

	/**
	 * Builds a fresh range-index tree at the given block size with `distinctThresholds` points keyed by EVEN longs
	 * `0, 2, 4, ...` (leaving the odd thresholds free for the `commit` benchmark's spread inserts), each point holding
	 * `recordsPerPoint` distinct record ids in both its `starts` and `ends` bitmaps.
	 */
	@Nonnull
	private static TransactionalLongBPlusTree<TransactionalRangePoint> buildTree(
		int blockSize, int distinctThresholds, int recordsPerPoint
	) {
		final int minBlock = blockSize / 2 - 1;
		final int minInternal = (int) (Math.ceil((float) minBlock / 2.0) - 1);
		final TransactionalLongBPlusTree<TransactionalRangePoint> tree = new TransactionalLongBPlusTree<>(
			blockSize, minBlock, minBlock, minInternal, TransactionalRangePoint.class, WRAPPER
		);
		for (int i = 0; i < distinctThresholds; i++) {
			final long threshold = 2L * i;
			final int[] starts = new int[recordsPerPoint];
			final int[] ends = new int[recordsPerPoint];
			final int base = i * recordsPerPoint;
			for (int r = 0; r < recordsPerPoint; r++) {
				starts[r] = base + r;
				ends[r] = base + r;
			}
			tree.insert(threshold, new TransactionalRangePoint(threshold, starts, ends));
		}
		return tree;
	}

	/* =========================================================================================== */

	/**
	 * Holds the pre-built range-index tree (read + commit base) plus the precomputed random threshold probes. The
	 * read benchmarks never mutate the base; `commit` mutates only inside a transaction (discarded on close), so the
	 * base survives every invocation unchanged.
	 */
	@State(Scope.Benchmark)
	public static class IndexState {

		/** The variable under study: leaf block size of the range-index tree. */
		@Param({"32", "64", "128", "256", "512"})
		public int blockSize;

		/** Number of distinct thresholds (range endpoints). Block size matters at scale. */
		@Param({"100000", "1000000"})
		public int distinctThresholds;

		/** Records per threshold point - low vs moderate fan-out into the starts/ends bitmaps. */
		@Param({"1", "16"})
		public int recordsPerPoint;

		public TransactionalLongBPlusTree<TransactionalRangePoint> tree;
		public long[] lookupThresholds;
		public UUID txId;

		@Setup(Level.Trial)
		public void setUp() {
			this.tree = buildTree(this.blockSize, this.distinctThresholds, this.recordsPerPoint);
			// fixed transaction id reused by every commit op - avoids SecureRandom cost inside the measured method
			this.txId = new UUID(7_602L, 1L);
			// random present (EVEN) thresholds to probe, capped so the array stays bounded
			final Random random = new Random(42);
			this.lookupThresholds = new long[Math.min(this.distinctThresholds, 200_000)];
			for (int i = 0; i < this.lookupThresholds.length; i++) {
				this.lookupThresholds[i] = 2L * random.nextInt(this.distinctThresholds);
			}
		}
	}

	/**
	 * Per-thread rotating cursor over the probe array, so successive ops probe different thresholds without a fresh
	 * random draw inside the measured method.
	 */
	@State(Scope.Thread)
	public static class KeyCursor {
		private int position;

		public int next(int bound) {
			int next = this.position + 1;
			if (next >= bound) {
				next = 0;
			}
			this.position = next;
			return next;
		}
	}

	/**
	 * Minimal {@link TransactionHandler} that captures the committed (merged) tree on commit. Mirrors the test
	 * harness used by {@code AssertionUtils.assertStateAfterCommit}; mutations are not persisted (no WAL), only the
	 * structural commit is driven.
	 */
	private static final class CommitCapture implements TransactionHandler {
		private final TransactionalLongBPlusTree<TransactionalRangePoint> tested;
		private TransactionalLongBPlusTree<TransactionalRangePoint> committed;

		CommitCapture(@Nonnull TransactionalLongBPlusTree<TransactionalRangePoint> tested) {
			this.tested = tested;
		}

		@Override
		public void registerMutation(@Nonnull Mutation mutation) {
		}

		@Override
		public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.committed = transactionalLayer.getStateCopyWithCommittedChanges(this.tested);
		}

		@Override
		public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
		}
	}

}
