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
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
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
import java.util.Comparator;
import java.util.Iterator;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Block-size sensitivity benchmark for the B+ tree that backs {@link io.evitadb.index.invertedIndex.InvertedIndex}
 * (and therefore every {@link io.evitadb.index.attribute.FilterIndex}): a comparator-ordered
 * {@link TransactionalObjectBPlusTree} keyed by the (normalized) attribute value with a
 * {@link ValueToRecordBitmap} value (the bucket holding all record ids for that value).
 *
 * Unlike {@code SortIndexArrayVsBPlusTreeBenchmark} (which compares an array vs a tree), the inverted index is already
 * tree-backed - so this benchmark sweeps the **leaf block size** to answer a single question with data rather than a
 * guess: *which block size is best for the filter-index workload?* That workload is a different mix from the SortIndex
 * ORDER BY full sweep that motivated `SortIndex.VALUE_BLOCK_SIZE = 256`: it is point-lookup + bounded-range + write
 * heavy. Block size is a read-vs-write trade-off (bigger leaves = fewer, more-sequential scans, but a larger array to
 * copy on every in-leaf insert and on every commit path-copy), so the optimum is not assumed.
 *
 * The benchmark exercises the **real value type** ({@link ValueToRecordBitmap}, a transactional producer) through the
 * tree's wrapper-aware constructor, so the commit measurement includes the genuine per-value layer merge - not a
 * stand-in. It measures the tree directly (faithful access-pattern replicas of the InvertedIndex methods) rather than
 * the full index, because block size is a property of the tree; the surrounding index logic (formula building,
 * normalization) is block-size invariant.
 *
 * Five patterns, each a faithful replica of an InvertedIndex hot path:
 *
 * - {@code pointLookup} - `getRecordsEqualTo`: a single {@link TransactionalObjectBPlusTree#search} for a present value.
 * - {@code rangeScan} - `getSortedRecords(from, to)`: a bounded forward value-iteration from a random start key.
 * - {@code fullScan} - `getValueToRecordBitmap` / `getHistogramOfAllRecords` / persist: a full ordered value sweep.
 * - {@code bulkLoad} - deserialization / restore: build the whole tree from `distinctValues` inserts.
 * - {@code commit} - `addRecord` + transaction commit: insert a spread-out batch of fresh keys in a transaction and
 *   commit, measuring the `O(touched leaves · block size)` path-copy that grows with block size.
 *
 * Run through JMH's own runner (the benchmarks jar uses a custom main):
 * {@code java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * io\.evitadb\.spike\.InvertedIndexBlockSizeBenchmark}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class InvertedIndexBlockSizeBenchmark {

	/** Mirrors `InvertedIndex.VALUE_TO_RECORD_BITMAP_WRAPPER` - adapts a committed value back into the bucket type. */
	private static final Function<Object, ValueToRecordBitmap> WRAPPER = o -> (ValueToRecordBitmap) o;

	/** Number of buckets a single `rangeScan` op walks - keeps the op cost bounded and block-size comparable. */
	private static final int RANGE_SCAN_WINDOW = 2_000;

	/** Fresh keys inserted per `commit` op - a representative mid-size transaction touching ~this many leaves. */
	private static final int COMMIT_MUTATIONS = 100;

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* =========================================================================================== */

	@Benchmark
	public int pointLookup(IndexState state, KeyCursor cursor) {
		final Integer key = state.lookupKeys[cursor.next(state.lookupKeys.length)];
		//noinspection unchecked
		final ValueToRecordBitmap bucket = (ValueToRecordBitmap) state.tree.search((Comparable) key).orElse(null);
		return bucket == null ? 0 : bucket.getRecordIds().size();
	}

	@Benchmark
	@SuppressWarnings("unchecked")
	public void rangeScan(IndexState state, KeyCursor cursor, Blackhole bh) {
		final Integer startKey = state.lookupKeys[cursor.next(state.lookupKeys.length)];
		final Iterator<ValueToRecordBitmap> it = state.tree.greaterOrEqualValueIterator((Comparable) startKey);
		int consumed = 0;
		while (it.hasNext() && consumed < RANGE_SCAN_WINDOW) {
			bh.consume(it.next().getRecordIds().size());
			consumed++;
		}
	}

	@Benchmark
	@SuppressWarnings("unchecked")
	public long fullScan(IndexState state) {
		long accumulator = 0;
		final Iterator<ValueToRecordBitmap> it = state.tree.valueIterator();
		while (it.hasNext()) {
			accumulator += it.next().getRecordIds().size();
		}
		return accumulator;
	}

	@Benchmark
	public int bulkLoad(IndexState state) {
		return buildTree(state.blockSize, state.distinctValues, state.recordsPerValue).size();
	}

	@Benchmark
	@SuppressWarnings({"rawtypes", "unchecked"})
	public Object commit(IndexState state) {
		final CommitCapture handler = new CommitCapture(state.tree);
		Transaction.executeInTransactionIfProvided(
			new Transaction(state.txId, handler, false),
			() -> {
				final Transaction tx = Transaction.getTransaction().orElseThrow();
				try {
					// spread COMMIT_MUTATIONS fresh ODD keys across the whole tree so the inserts land in distinct
					// leaves (the base tree uses EVEN keys, so these never collide); each insert layers and the commit
					// path-copies the touched leaf - the cost that scales with block size
					final int stride = state.distinctValues / COMMIT_MUTATIONS;
					for (int j = 0; j < COMMIT_MUTATIONS; j++) {
						final int key = 2 * (j * stride) + 1;
						state.tree.insert((Comparable) Integer.valueOf(key), new ValueToRecordBitmap(key, key));
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
	 * Builds a fresh inverted-index tree at the given block size with `distinctValues` buckets keyed by EVEN integers
	 * `0, 2, 4, ...` (leaving the odd keys free for the `commit` benchmark's spread inserts), each bucket holding
	 * `recordsPerValue` distinct record ids.
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static TransactionalObjectBPlusTree buildTree(int blockSize, int distinctValues, int recordsPerValue) {
		final int minBlock = blockSize / 2 - 1;
		final int minInternal = (int) (Math.ceil((float) minBlock / 2.0) - 1);
		final TransactionalObjectBPlusTree tree = new TransactionalObjectBPlusTree<>(
			blockSize, minBlock, minBlock, minInternal,
			Comparable.class, ValueToRecordBitmap.class, WRAPPER, (Comparator) Comparator.naturalOrder()
		);
		for (int i = 0; i < distinctValues; i++) {
			final int key = 2 * i;
			final int[] recordIds = new int[recordsPerValue];
			final int base = i * recordsPerValue;
			for (int r = 0; r < recordsPerValue; r++) {
				recordIds[r] = base + r;
			}
			tree.insert((Comparable) Integer.valueOf(key), new ValueToRecordBitmap(key, recordIds));
		}
		return tree;
	}

	/* =========================================================================================== */

	/**
	 * Holds the pre-built inverted-index tree (read + commit base) plus the precomputed random point-lookup keys. The
	 * read benchmarks never mutate the base; `commit` mutates only inside a transaction (discarded on close), so the
	 * base survives every invocation unchanged.
	 */
	@State(Scope.Benchmark)
	public static class IndexState {

		/** The variable under study: leaf block size of the inverted-index tree. */
		@Param({"32", "64", "128", "256", "512"})
		public int blockSize;

		/** Number of distinct attribute values (buckets). Block size matters at scale. */
		@Param({"100000", "1000000"})
		public int distinctValues;

		/** Records per bucket - low (single-record, high-cardinality attr) vs moderate. */
		@Param({"1", "16"})
		public int recordsPerValue;

		@SuppressWarnings("rawtypes")
		public TransactionalObjectBPlusTree tree;
		public Integer[] lookupKeys;
		public UUID txId;

		@Setup(Level.Trial)
		public void setUp() {
			this.tree = buildTree(this.blockSize, this.distinctValues, this.recordsPerValue);
			// fixed transaction id reused by every commit op - avoids SecureRandom cost inside the measured method
			this.txId = new UUID(7_601L, 1L);
			// random present (EVEN) keys to probe, capped so the array stays bounded
			final Random random = new Random(42);
			this.lookupKeys = new Integer[Math.min(this.distinctValues, 200_000)];
			for (int i = 0; i < this.lookupKeys.length; i++) {
				this.lookupKeys[i] = 2 * random.nextInt(this.distinctValues);
			}
		}
	}

	/**
	 * Per-thread rotating cursor over the point-lookup key array, so successive ops probe different keys without a
	 * fresh random draw inside the measured method.
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
	@SuppressWarnings("rawtypes")
	private static final class CommitCapture implements TransactionHandler {
		private final TransactionalObjectBPlusTree tested;
		private TransactionalObjectBPlusTree committed;

		CommitCapture(@Nonnull TransactionalObjectBPlusTree tested) {
			this.tested = tested;
		}

		@Override
		public void registerMutation(@Nonnull Mutation mutation) {
		}

		@Override
		@SuppressWarnings("unchecked")
		public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.committed = (TransactionalObjectBPlusTree) transactionalLayer.getStateCopyWithCommittedChanges(this.tested);
		}

		@Override
		public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
		}
	}

}
