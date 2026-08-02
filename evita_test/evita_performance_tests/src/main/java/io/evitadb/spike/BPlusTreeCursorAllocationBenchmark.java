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
import io.evitadb.index.bPlusTree.TransactionalElementBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalIntToLongBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalLongBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
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
 * Allocation census for the whole transactional B+ tree family, measuring what a single insert and a single read
 * descent cost in bytes on the warm-up (non-transactional) bulk path.
 *
 * It exists to gate the port of the lazy-cursor-capture optimization (already landed on
 * {@link TransactionalIntToLongBPlusTree}) to the four remaining trees, by answering two questions the family's
 * existing benchmarks cannot:
 *
 * 1. **How large is the cursor path, measured rather than modelled?** `descendIntToLongWithCursor` and
 *    `descendIntToLongCursorFree` walk the SAME tree to the SAME leaf with the SAME comparisons — `search(int)`
 *    captures a {@code Cursor} path, `searchOrDefault(int, long)` descends via the allocation-free
 *    {@code findLeafNode}. Both probe an ABSENT key, so the returned {@code OptionalLong} is the
 *    {@code OptionalLong.empty()} singleton in the one case and a primitive in the other, and neither result
 *    allocates. Their {@code gc.alloc.rate.norm} difference is therefore the cursor object graph and nothing else —
 *    an in-production-code A/B needing no engine edit.
 *
 * 2. **How much of an insert is the cursor, and does that depend on key order?** This is the crux. The boundary
 *    asserts on `Bucket`, `Element` and `Long` fire when the inserted key becomes the leaf's first or last key.
 *    Under RANDOM keys that is roughly `2 / leafOccupancy` of inserts — nearly never. Under ASCENDING keys **every**
 *    insert is a tail insert, so the assert fires every time and (as the trees stand) needs the cursor every time.
 *    Bulk ingest, the workload the family's allocation figure was taken from, is the ascending regime. A single-key-
 *    order measurement would therefore generalize to nothing, so every insert op is swept over both.
 *
 * The insert ops build a whole tree from empty per invocation ({@code TREE_SIZE} inserts), because that IS the bulk
 * ingest shape: outside a transaction the leaves are mutated in place, so the per-insert allocation is the descent
 * plus the amortized split, with no diff-layer noise on top. Divide {@code gc.alloc.rate.norm} by {@code TREE_SIZE}
 * for bytes per insert. The read ops replay {@code DESCENT_BATCH} descents against a tree built once per trial;
 * divide by {@code DESCENT_BATCH} for bytes per descent.
 *
 * Everything the measured methods consume — boxed keys, elements, the shared payload value — is materialized in
 * {@code @Setup}, so no benchmark-harness allocation is attributed to the tree.
 *
 * Run through JMH's own runner (the benchmarks jar uses a custom main):
 * {@code java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * io\.evitadb\.spike\.BPlusTreeCursorAllocationBenchmark -prof gc}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BPlusTreeCursorAllocationBenchmark {

	/** Entries inserted per insert-op invocation, and the size of the trees the read ops descend. */
	private static final int TREE_SIZE = 200_000;

	/** Descents replayed per read-op invocation — large enough that the per-descent figure is not rounding noise. */
	private static final int DESCENT_BATCH = 10_000;

	/** Fresh keys inserted per transactional-insert invocation — a wide ALIVE-mode write touching distinct leaves. */
	private static final int TX_MUTATIONS = 5_000;

	/** Payload shared by every entry of the value-carrying trees, so no payload allocation lands in the measurement. */
	private static final Object SHARED_VALUE = new Object();

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* =========================================================================================== */
	/*  INSERT — bytes per insert on the warm-up bulk path, swept over key order                    */
	/* =========================================================================================== */

	@Benchmark
	@SuppressWarnings({"rawtypes", "unchecked"})
	public Object insertBucket(InsertState state) {
		final TransactionalBucketBPlusTree tree = newBucketTree(state.blockSize);
		final Integer[] keys = state.boxedKeys;
		for (int i = 0; i < keys.length; i++) {
			tree.addRecord((Comparable) keys[i], i);
		}
		return tree;
	}

	@Benchmark
	public Object insertElement(InsertState state) {
		final TransactionalElementBPlusTree<KeyedElement> tree = newElementTree(state.blockSize);
		for (final KeyedElement element : state.elements) {
			tree.insert(element);
		}
		return tree;
	}

	@Benchmark
	public Object insertLong(InsertState state) {
		final TransactionalLongBPlusTree<Object> tree = newLongTree(state.blockSize);
		for (final int key : state.keys) {
			tree.insert(key, SHARED_VALUE);
		}
		return tree;
	}

	@Benchmark
	public Object insertObject(InsertState state) {
		final TransactionalObjectBPlusTree<Integer, Object> tree = newObjectTree(state.blockSize);
		for (final Integer key : state.boxedKeys) {
			tree.insert(key, SHARED_VALUE);
		}
		return tree;
	}

	/**
	 * The already-optimized tree — the reference arm showing what an insert costs once the cursor is captured lazily.
	 */
	@Benchmark
	public Object insertIntToLong(InsertState state) {
		final TransactionalIntToLongBPlusTree tree = newIntToLongTree(state.blockSize);
		for (final int key : state.keys) {
			tree.insert(key, key);
		}
		return tree;
	}

	/**
	 * The same bucket inserts, but inside a transaction and rolled back — the ALIVE-mode denominator. Rolling back
	 * rather than committing is deliberate: it leaves the insert-side allocation (the per-leaf diff layers) in the
	 * measurement and keeps the commit-time merge, whose cost scales with the number of dirtied leaves rather than
	 * with the number of inserts, out of it. The base tree is therefore also left pristine for the next invocation.
	 *
	 * The cursor is the same size in both regimes; what changes is what it is a fraction OF. Bulk ingest (warm-up)
	 * and ALIVE writes are different denominators, and the family's 2.25 GB figure came from the former.
	 */
	@Benchmark
	@SuppressWarnings({"rawtypes", "unchecked"})
	public Object insertBucketInTransaction(DescentState state) {
		Transaction.executeInTransactionIfProvided(
			new Transaction(state.txId, NoOpTransactionHandler.INSTANCE, false),
			() -> {
				final Transaction tx = Transaction.getTransaction().orElseThrow();
				try {
					// fresh ODD keys spread across the whole tree so the inserts land in distinct leaves, exactly the
					// shape of a wide ALIVE-mode write
					final Integer[] probes = state.boxedAbsentKeys;
					for (int i = 0; i < TX_MUTATIONS; i++) {
						state.bucketTree.addRecord((Comparable) probes[i], i);
					}
				} finally {
					// discard rather than merge - see the javadoc
					tx.setRollbackOnly();
					tx.close();
				}
			}
		);
		return state.bucketTree;
	}

	/* =========================================================================================== */
	/*  DESCENT — bytes per read descent; the cursor A/B lives here                                 */
	/* =========================================================================================== */

	/**
	 * Cursor-capturing descent on the int-keyed tree. Paired with {@link #descendIntToLongCursorFree} this isolates
	 * the cursor object graph: identical tree, identical descent, the only difference is that this one captures the
	 * path. The probed keys are absent, so {@code OptionalLong.empty()} is a singleton and contributes nothing.
	 */
	@Benchmark
	public void descendIntToLongWithCursor(DescentState state, Blackhole bh) {
		final int[] probes = state.absentKeys;
		for (int i = 0; i < DESCENT_BATCH; i++) {
			bh.consume(state.intToLongTree.search(probes[i]).isPresent());
		}
	}

	/** Allocation-free descent on the same tree — the control arm of the cursor A/B. */
	@Benchmark
	public void descendIntToLongCursorFree(DescentState state, Blackhole bh) {
		final int[] probes = state.absentKeys;
		for (int i = 0; i < DESCENT_BATCH; i++) {
			bh.consume(state.intToLongTree.searchOrDefault(probes[i], Long.MIN_VALUE));
		}
	}

	/**
	 * Cursor-capturing descent on the int-keyed tree whose internal nodes route allocation-free
	 * ({@code AbstractIntKeyedInternalNode.searchIndex} folds {@code Arrays.binarySearch} into the child index). Its
	 * distance from {@link #descendBucket} / {@link #descendObject} / {@link #descendLong} is the per-descent cost of
	 * the {@code InsertionPosition} records those three still allocate on every routed level.
	 */
	@Benchmark
	public void descendElement(DescentState state, Blackhole bh) {
		final int[] probes = state.absentKeys;
		for (int i = 0; i < DESCENT_BATCH; i++) {
			bh.consume(state.elementTree.search(probes[i]));
		}
	}

	@Benchmark
	@SuppressWarnings({"rawtypes", "unchecked"})
	public void descendBucket(DescentState state, Blackhole bh) {
		final Integer[] probes = state.boxedAbsentKeys;
		for (int i = 0; i < DESCENT_BATCH; i++) {
			bh.consume(state.bucketTree.cardinalityOf((Comparable) probes[i]));
		}
	}

	@Benchmark
	public void descendObject(DescentState state, Blackhole bh) {
		final Integer[] probes = state.boxedAbsentKeys;
		for (int i = 0; i < DESCENT_BATCH; i++) {
			bh.consume(state.objectTree.search(probes[i]).isPresent());
		}
	}

	@Benchmark
	public void descendLong(DescentState state, Blackhole bh) {
		final int[] probes = state.absentKeys;
		for (int i = 0; i < DESCENT_BATCH; i++) {
			bh.consume(state.longTree.search(probes[i]).isPresent());
		}
	}

	/* =========================================================================================== */
	/*  Tree factories — uniform sizing so every family member is measured at the same fan-out       */
	/* =========================================================================================== */

	@Nonnull
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static TransactionalBucketBPlusTree newBucketTree(int blockSize) {
		// the raw locals mirror the existing BucketBPlusTreePayloadBenchmark: the tree is keyed by a raw Comparable, so
		// the factory has to reach the constructor as a raw argument for the invocation to stay unchecked
		final ValueColumnFactory factory =
			ValueColumnFactory.forKey(Integer.class, (Comparator) Comparator.naturalOrder());
		final TransactionalBucketBPlusTree tree = new TransactionalBucketBPlusTree<>(
			blockSize, minBlock(blockSize), minBlock(blockSize), minInternal(blockSize),
			Comparable.class, (Comparator) Comparator.naturalOrder(), factory
		);
		return tree;
	}

	@Nonnull
	private static TransactionalElementBPlusTree<KeyedElement> newElementTree(int blockSize) {
		return new TransactionalElementBPlusTree<>(
			blockSize, minBlock(blockSize), minBlock(blockSize), minInternal(blockSize),
			KeyedElement.class, KeyedElement::key
		);
	}

	@Nonnull
	private static TransactionalLongBPlusTree<Object> newLongTree(int blockSize) {
		return new TransactionalLongBPlusTree<>(
			blockSize, minBlock(blockSize), minBlock(blockSize), minInternal(blockSize),
			Object.class
		);
	}

	@Nonnull
	private static TransactionalObjectBPlusTree<Integer, Object> newObjectTree(int blockSize) {
		return new TransactionalObjectBPlusTree<>(
			blockSize, minBlock(blockSize), minBlock(blockSize), minInternal(blockSize),
			Integer.class, Object.class
		);
	}

	@Nonnull
	private static TransactionalIntToLongBPlusTree newIntToLongTree(int blockSize) {
		return new TransactionalIntToLongBPlusTree(
			blockSize, minBlock(blockSize), minBlock(blockSize), minInternal(blockSize)
		);
	}

	/** Mirrors the sizing {@code InvertedIndex} / {@code RangeIndex} derive from their leaf block size. */
	private static int minBlock(int blockSize) {
		return blockSize / 2 - 1;
	}

	private static int minInternal(int blockSize) {
		return (int) (Math.ceil(minBlock(blockSize) / 2.0) - 1);
	}

	/**
	 * Builds the EVEN key sequence `0, 2, 4, …` the read trees are loaded with, leaving every ODD key absent so the
	 * descent probes reach a leaf without ever finding their key (an absent key makes the result allocation-free in
	 * every tree, which is what lets the descent ops measure the descent alone).
	 */
	@Nonnull
	private static int[] evenKeys(int count) {
		final int[] keys = new int[count];
		for (int i = 0; i < count; i++) {
			keys[i] = 2 * i;
		}
		return keys;
	}

	/* =========================================================================================== */

	/** Element payload of the {@link TransactionalElementBPlusTree} arm — an int key and nothing else. */
	public record KeyedElement(int key) {
	}

	/**
	 * Insert-op fixture: the key sequence in the requested order, plus its boxed and element-wrapped projections. The
	 * ordering is materialized ONCE per trial; the measured method only walks the array, so the shuffle never lands in
	 * {@code gc.alloc.rate.norm}.
	 */
	@State(Scope.Benchmark)
	public static class InsertState {

		/** Leaf block size — 256 is the production {@code InvertedIndex} value, 64 the family default. */
		@Param({"64", "256"})
		public int blockSize;

		/**
		 * ASCENDING is bulk ingest: every insert lands at the leaf tail, so every boundary assert fires. RANDOM is the
		 * update workload: a boundary insert is roughly `2 / leafOccupancy` of inserts.
		 */
		@Param({"ASCENDING", "RANDOM"})
		public String keyOrder;

		public int[] keys;
		public Integer[] boxedKeys;
		public KeyedElement[] elements;

		@Setup(Level.Trial)
		public void setUp() {
			this.keys = evenKeys(TREE_SIZE);
			if ("RANDOM".equals(this.keyOrder)) {
				// Fisher-Yates over the same key set, so both arms insert exactly the same keys and end with exactly
				// the same tree contents - only the arrival order (and therefore the boundary-assert fire rate and the
				// split pattern) differs
				final Random random = new Random(42);
				for (int i = this.keys.length - 1; i > 0; i--) {
					final int j = random.nextInt(i + 1);
					final int swap = this.keys[i];
					this.keys[i] = this.keys[j];
					this.keys[j] = swap;
				}
			}
			this.boxedKeys = new Integer[this.keys.length];
			this.elements = new KeyedElement[this.keys.length];
			for (int i = 0; i < this.keys.length; i++) {
				this.boxedKeys[i] = this.keys[i];
				this.elements[i] = new KeyedElement(this.keys[i]);
			}
		}
	}

	/**
	 * Descent-op fixture: one tree per family member, all loaded with the same EVEN keys at the same block size, plus
	 * the ODD probe keys that are absent from all of them. The read ops never mutate, so the trees survive every
	 * invocation unchanged.
	 */
	@State(Scope.Benchmark)
	public static class DescentState {

		@Param({"64", "256"})
		public int blockSize;

		@SuppressWarnings("rawtypes")
		public TransactionalBucketBPlusTree bucketTree;
		public TransactionalElementBPlusTree<KeyedElement> elementTree;
		public TransactionalLongBPlusTree<Object> longTree;
		public TransactionalObjectBPlusTree<Integer, Object> objectTree;
		public TransactionalIntToLongBPlusTree intToLongTree;
		public int[] absentKeys;
		public Integer[] boxedAbsentKeys;
		public UUID txId;

		@Setup(Level.Trial)
		@SuppressWarnings({"rawtypes", "unchecked"})
		public void setUp() {
			final int[] keys = evenKeys(TREE_SIZE);
			this.bucketTree = newBucketTree(this.blockSize);
			this.elementTree = newElementTree(this.blockSize);
			this.longTree = newLongTree(this.blockSize);
			this.objectTree = newObjectTree(this.blockSize);
			this.intToLongTree = newIntToLongTree(this.blockSize);
			for (int i = 0; i < keys.length; i++) {
				final int key = keys[i];
				this.bucketTree.addRecord((Comparable) Integer.valueOf(key), i);
				this.elementTree.insert(new KeyedElement(key));
				this.longTree.insert(key, SHARED_VALUE);
				this.objectTree.insert(key, SHARED_VALUE);
				this.intToLongTree.insert(key, key);
			}
			// DISTINCT ODD keys spread across the whole key space - absent from every tree, but each still routes to a
			// real leaf, so the descent is the full-height one a present key would take. Distinctness matters for the
			// transactional arm: a repeated key would join an existing bucket instead of creating one.
			final int stride = TREE_SIZE / DESCENT_BATCH;
			this.absentKeys = new int[DESCENT_BATCH];
			for (int i = 0; i < DESCENT_BATCH; i++) {
				this.absentKeys[i] = 2 * (i * stride) + 1;
			}
			// shuffled so successive probes hit unrelated leaves rather than walking the tree in order
			final Random random = new Random(42);
			for (int i = this.absentKeys.length - 1; i > 0; i--) {
				final int j = random.nextInt(i + 1);
				final int swap = this.absentKeys[i];
				this.absentKeys[i] = this.absentKeys[j];
				this.absentKeys[j] = swap;
			}
			this.boxedAbsentKeys = new Integer[DESCENT_BATCH];
			for (int i = 0; i < DESCENT_BATCH; i++) {
				this.boxedAbsentKeys[i] = this.absentKeys[i];
			}
			// fixed transaction id reused by every transactional op - avoids SecureRandom cost inside the measured
			// method
			this.txId = new UUID(7_601L, 1L);
		}
	}

	/**
	 * Minimal {@link TransactionHandler} for the transactional insert arm. The arm rolls back, so neither
	 * {@code commit} nor {@code rollback} has anything to capture.
	 */
	private static final class NoOpTransactionHandler implements TransactionHandler {
		private static final NoOpTransactionHandler INSTANCE = new NoOpTransactionHandler();

		@Override
		public void registerMutation(@Nonnull Mutation mutation) {
		}

		@Override
		public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		}

		@Override
		public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
		}
	}

}
