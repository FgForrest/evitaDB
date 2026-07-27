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
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
import io.evitadb.index.bitmap.Bitmap;
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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Allocation / time A/B benchmark for the {@link TransactionalBucketBPlusTree} that backs
 * {@link io.evitadb.index.invertedIndex.InvertedIndex} and {@link io.evitadb.index.attribute.OwnerUniqueIndex}.
 *
 * It exists to gate a single refactor: generalizing the leaf's single-record payload from a raw {@code int[]} column to a
 * pluggable {@code ValueColumn} payload (so a {@code long} payload can back the global-unique value→entity tree). Because
 * the change is internal to the tree (its public API is unchanged), the very same compiled benchmark links against both
 * the pre-refactor ({@code int[]}) and post-refactor ({@code ValueColumn}) engine via the classpath-shadow recipe — the
 * deterministic {@code gc.alloc.rate.norm} (alloc/op) is the regression oracle, {@code avgt} the secondary signal.
 *
 * The benchmark measures the tree directly (faithful access-pattern replicas of the index hot paths) rather than the
 * surrounding index, because the payload column is a property of the tree. Both payload-cardinality regimes are swept via
 * {@code recordsPerValue}: the single-record regime (`= 1`, the unique-index path the payload column serves directly) and
 * the multi-record / overflow regime (`> 1`, the inverted-index path whose lazy overflow bitmaps are untouched by the
 * refactor). Keys are EVEN integers `0, 2, 4, …` so the ODD keys stay free for the mutation / commit ops.
 *
 * Ops, each a faithful replica of an index hot path that reads or writes the single-record column:
 *
 * - {@code pointLookup} — `getRecordsEqualTo`: a single descent reading one bucket's payload.
 * - {@code rangeScan} — a bounded positioned-cursor forward walk reading payloads.
 * - {@code fullScan} — a full ordered cursor sweep reading every payload.
 * - {@code mutate} — a balanced `addRecord` + `removeRecord` of a fresh ODD key: the most refactor-sensitive path, it
 *   stresses single-slot `insertKeyAt` / `removeKeyAt` (and a transient bucket create + delete) and leaves the tree
 *   pristine for the next invocation.
 * - {@code bulkLoad} — build the whole tree from `distinctValues` inserts (the split path-copying the payload column).
 * - {@code commit} — spread fresh ODD-key inserts in a transaction and commit, measuring the per-leaf payload path-copy.
 *
 * Run through JMH's own runner (the benchmarks jar uses a custom main):
 * {@code java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * io\.evitadb\.spike\.BucketBPlusTreePayloadBenchmark}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BucketBPlusTreePayloadBenchmark {

	/** Number of buckets a single `rangeScan` op walks — keeps the op cost bounded and comparable across regimes. */
	private static final int RANGE_SCAN_WINDOW = 2_000;

	/** Fresh keys inserted per `commit` op — a representative mid-size transaction touching ~this many leaves. */
	private static final int COMMIT_MUTATIONS = 100;

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* =========================================================================================== */

	@Benchmark
	public int pointLookup(IndexState state, KeyCursor cursor) {
		final Integer key = state.lookupKeys[cursor.next(state.lookupKeys.length)];
		//noinspection unchecked
		final Bitmap records = state.tree.getRecordsEqualTo((Comparable) key);
		return records.size();
	}

	@Benchmark
	@SuppressWarnings({"rawtypes", "unchecked"})
	public void rangeScan(IndexState state, KeyCursor cursor, Blackhole bh) {
		final Integer startKey = state.lookupKeys[cursor.next(state.lookupKeys.length)];
		final BucketCursor it = state.tree.cursor((Comparable) startKey);
		int consumed = 0;
		while (it.next() && consumed < RANGE_SCAN_WINDOW) {
			// consume the bucket's payload cardinality (reads the single-record column), not the key, so the op is
			// sensitive to the payload-column refactor and free of key-boxing allocation noise
			bh.consume(it.size());
			consumed++;
		}
	}

	@Benchmark
	@SuppressWarnings({"rawtypes", "unchecked"})
	public long fullScan(IndexState state) {
		long accumulator = 0;
		final BucketCursor it = state.tree.cursor();
		while (it.next()) {
			accumulator += it.size();
		}
		return accumulator;
	}

	@Benchmark
	@SuppressWarnings({"rawtypes", "unchecked"})
	public int mutate(IndexState state, KeyCursor cursor) {
		// pick a free ODD key spread across the whole key space; add then remove it so the base tree is left pristine.
		// This isolates single-slot insertKeyAt / removeKeyAt on the single-record payload column (plus a transient
		// bucket create + delete) — the path most sensitive to the int[]→ValueColumn refactor.
		final int slot = cursor.next(state.distinctValues);
		final Integer key = 2 * slot + 1;
		state.tree.addRecord((Comparable) key, slot);
		state.tree.removeRecord((Comparable) key, slot);
		return key;
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
					// path-copies the touched leaf's payload column — the cost that scales with block size
					final int stride = state.distinctValues / COMMIT_MUTATIONS;
					for (int j = 0; j < COMMIT_MUTATIONS; j++) {
						final int key = 2 * (j * stride) + 1;
						state.tree.addRecord((Comparable) Integer.valueOf(key), key);
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
	 * Builds a fresh bucket tree at the given block size with `distinctValues` buckets keyed by EVEN integers
	 * `0, 2, 4, …` (leaving the odd keys free for the `mutate` / `commit` ops), each bucket holding `recordsPerValue`
	 * distinct record ids (a single record when `recordsPerValue == 1`, exercising the bare payload column; more than one
	 * promotes the bucket to the lazy overflow bitmap, untouched by the refactor).
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static TransactionalBucketBPlusTree buildTree(int blockSize, int distinctValues, int recordsPerValue) {
		final int minBlock = blockSize / 2 - 1;
		final int minInternal = (int) (Math.ceil((float) minBlock / 2.0) - 1);
		final ValueColumnFactory factory = ValueColumnFactory.forKey(Integer.class, (Comparator) Comparator.naturalOrder());
		final TransactionalBucketBPlusTree tree = new TransactionalBucketBPlusTree<>(
			blockSize, minBlock, minBlock, minInternal,
			Comparable.class, (Comparator) Comparator.naturalOrder(), factory
		);
		for (int i = 0; i < distinctValues; i++) {
			final Integer key = 2 * i;
			final int base = i * recordsPerValue;
			if (recordsPerValue == 1) {
				tree.addRecord((Comparable) key, base);
			} else {
				final int[] recordIds = new int[recordsPerValue];
				for (int r = 0; r < recordsPerValue; r++) {
					recordIds[r] = base + r;
				}
				tree.addRecord((Comparable) key, recordIds);
			}
		}
		return tree;
	}

	/* =========================================================================================== */

	/**
	 * Holds the pre-built bucket tree (read + mutate + commit base) plus the precomputed random point-lookup keys. The
	 * read benchmarks never mutate the base; `mutate` adds then removes a fresh key (net-zero); `commit` mutates only
	 * inside a transaction (discarded on close) — so the base survives every invocation unchanged.
	 */
	@State(Scope.Benchmark)
	public static class IndexState {

		/** Leaf block size of the tree — the production value for InvertedIndex / OwnerUnique. */
		@Param({"256"})
		public int blockSize;

		/** Number of distinct keys (buckets). The payload column matters at scale. */
		@Param({"100000", "1000000"})
		public int distinctValues;

		/** Records per bucket — single-record (bare payload column) vs multi-record (overflow bitmap). */
		@Param({"1", "16"})
		public int recordsPerValue;

		@SuppressWarnings("rawtypes")
		public TransactionalBucketBPlusTree tree;
		public Integer[] lookupKeys;
		public UUID txId;

		@Setup(Level.Trial)
		public void setUp() {
			this.tree = buildTree(this.blockSize, this.distinctValues, this.recordsPerValue);
			// fixed transaction id reused by every commit op — avoids SecureRandom cost inside the measured method
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
	 * Per-thread rotating cursor over a bounded index space, so successive ops probe different keys without a fresh
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
	 * Minimal {@link TransactionHandler} that captures the committed (merged) tree on commit. Mirrors the test harness
	 * used by {@code AssertionUtils.assertStateAfterCommit}; mutations are not persisted (no WAL), only the structural
	 * commit is driven.
	 */
	@SuppressWarnings("rawtypes")
	private static final class CommitCapture implements TransactionHandler {
		private final TransactionalBucketBPlusTree tested;
		private TransactionalBucketBPlusTree committed;

		CommitCapture(@Nonnull TransactionalBucketBPlusTree tested) {
			this.tested = tested;
		}

		@Override
		public void registerMutation(@Nonnull Mutation mutation) {
		}

		@Override
		@SuppressWarnings("unchecked")
		public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.committed = (TransactionalBucketBPlusTree) transactionalLayer.getStateCopyWithCommittedChanges(this.tested);
		}

		@Override
		public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
		}
	}

}
