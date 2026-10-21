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

package io.evitadb.index.bPlusTree;

import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sweeps the reads of a bucket tree that share no lock, no volatile and no transaction with its writer: the
 * management and statistics API, which reads the tree on a request thread with no session while a warm-up load
 * mutates it in place.
 *
 * ## The contract under test
 *
 * A warm-up catalog admits one session, which is the only writer and the only reader that sees the tree mutated in
 * place; it reads its own writes in program order. A live catalog publishes versions that are never mutated in place.
 * So the only reader that can meet a torn tree is a management read, and it reaches the tree through three entry
 * points only: the counters ({@code bucketCount()}), the forward walk behind {@code cursor()} - which the record count
 * and every per-index cardinality statistic take - and the heap walk. **Management reads are best-effort and must not
 * fail**: they may answer a stale or partly wrong figure, but they must return. **Key lookups are never
 * session-free**: no point lookup, keyed walk or reverse walk runs off the writer's thread, so this sweep drives none
 * of them and the tree carries no bound for them - a torn state reaching one of those is a bug and is meant to fail
 * loudly.
 *
 * ## Three sweeps, three structures
 *
 * A writer grows a column or a node by publishing a longer array and then raising its count, as two plain stores, so
 * a reader can pair the raised count with the shorter array, and a slot inside the count can still read `null`. The
 * walks bound every index by the array they actually hold - {@code ValueColumn#observableLiveRun()} on the leaves,
 * {@code observableInternalPeek} on the spine - and step over a child they cannot see into. Each sweep is shaped to
 * reach exactly one structure that grows that way:
 *
 * 1. {@link #shouldNeverFailASessionFreeWalkWhileAWarmUpLoadGrowsTheColumns()} - the **leaf columns**. Its block size
 *    and key count are equal, so no round ever splits and the tree never grows an internal node: every reallocation
 *    the four leaf columns perform on the way from four slots to {@value #BLOCK_SIZE} happens inside that one leaf,
 *    and a failure here cannot be attributed to the spine.
 * 2. {@link #shouldNeverFailASessionFreeWalkWhileAWarmUpLoadGrowsTheInternalNodes()} - the **internal nodes**, which
 *    size their `keys` and `children` arrays to the live content and grow them by the same array-first /
 *    `peek`-second pair. A small block size and enough keys build a three-level tree, so every internal node grows its
 *    arrays repeatedly and splits, under a reader taking the counters, the record count, the forward walk and the
 *    heap walk that recurses through the internal nodes themselves.
 * 3. {@link #shouldNeverFailASessionFreeWalkWhileAWarmUpLoadPromotesMultiRecordBuckets()} - the **overflow column**,
 *    which neither of the other two ever materializes, because both write exactly one record per key and a bucket is
 *    promoted only by its second.
 *
 * ## What sweeps 2 and 3 do NOT assert, and why
 *
 * Sweep 1 can bound the count it observes, because a single leaf has no parent to shift. Sweeps 2 and 3 cannot: a
 * leaf split inserts the new right sibling into its parent's `children` array **in place**, and the shift that makes
 * room transiently leaves one child pointer duplicated, so a walk that crosses that instant legitimately visits one
 * subtree twice and counts more buckets than the writer has written. That is the best-effort contract above, so both
 * assert only that the reads never fail, plus a quiescent count once the writer has stopped.
 *
 * ## Calibration (measured, not estimated)
 *
 * **A green run on x86 is NOT proof that the bounds are correct**, and the sweeps sit on opposite sides of that:
 *
 * - **Sweeps 1 and 3 cannot fail on x86.** Their readers load the count before the array, and x86's total store
 *   order forbids seeing the raised count without the longer array the writer stored first. With the leaf bound
 *   reverted to the key column's `size()`, 500 000 rounds of sweep 1 - ten times the budget - produced no escape on
 *   OpenJDK 17.0.20 (37.9 s) or 21.0.12 (20.5 s). The Java memory model permits the reordering regardless and AArch64
 *   reaches it in silicon, so re-measure the counterfactual on an AArch64 box before concluding anything about the
 *   bound from a run here. Green side of sweep 1, 24-core x86_64 Linux, OpenJDK 17.0.20: all {@value #ROUNDS} rounds
 *   in 2.4 s idle, 3.4 s under a concurrent build.
 * - **Sweep 2 is calibrated on x86.** Its spine sites load the array **before** `peek`, a plain interleaving that
 *   needs no reordering. With the internal-node bound absent it failed **3 runs out of 3**, at rounds **308, 284 and
 *   41** of the 1 000 the budget then stood at (raised to {@value #MULTILEVEL_ROUNDS} for margin), in 0.10-0.23 s,
 *   every time with `ArrayIndexOutOfBoundsException: Index 8 out of bounds for length 8` from
 *   `ForwardBucketCursor.moveToNextLeaf`. The green side passes all {@value #MULTILEVEL_ROUNDS} rounds, 3 runs of 3.
 *   Re-measured on 2026-10-19 once the reader was narrowed to the management entry points, with
 *   `observableInternalPeek` reduced to `return peek`: 3 runs of 3 failed, at rounds 13, 737 and 38, in 0.10-1.11 s,
 *   again from `moveToNextLeaf`, on the same 24-core x86_64 box under a load average of about 15.
 *
 * The bounds are pinned deterministically in the fast loop rather than by these budgets: `ColumnSizingTest` and
 * `OverflowColumnTest` (an aligned column observes its whole live run),
 * `TransactionalBucketBPlusTreeTest#shouldBoundTheCursorByTheColumnLiveRunWhenALeafPeekRunsAhead` (a cursor over a
 * leaf whose `peek` really has run ahead of its columns), and `TransactionalBucketBPlusTreeTest.UnpublishedChildSlot`
 * (a child slot inside the bound that still reads `null`).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@Tag(SLOW)
@Tag(INDEXING)
@DisplayName("Long-running session-free management reads of a bucket tree under a concurrent warm-up load")
class LongRunningBucketBPlusTreeConcurrentReadTest {
	/**
	 * Number of independent writer / reader races. Each round builds a fresh tree and drives its single leaf's
	 * columns through every reallocation from the four-slot floor up to the block size, so the rounds are there to
	 * repeat a narrow window rather than to lengthen a wide one.
	 */
	private static final int ROUNDS = 50_000;
	/**
	 * The leaf block size, and also the number of keys each round writes - equal on purpose, so the tree stays a
	 * single leaf and never grows an internal node. See the class javadoc.
	 */
	private static final int BLOCK_SIZE = 255;
	/**
	 * Bound on a single round. A round is a couple of hundred inserts against a spinning reader, so this only has to
	 * exceed scheduling noise on a loaded box.
	 */
	private static final int ROUND_TIMEOUT_SECONDS = 30;
	/**
	 * Number of writer / reader races in the multi-level sweep. A round here builds a whole three-level tree rather
	 * than one leaf, so it is some thirty times the work of a sweep-1 round and the count is lowered to match - but
	 * it is still set an order of magnitude above the deepest round the counterfactual has been seen to survive
	 * (308 of 1 000, over three runs), for the margin the class javadoc's calibration section quotes.
	 */
	private static final int MULTILEVEL_ROUNDS = 5_000;
	/**
	 * The leaf block size of the multi-level sweep - small on purpose, so a thousand keys are enough to build a tree
	 * three levels deep and every internal node on the way grows its arrays and eventually splits.
	 */
	private static final int MULTILEVEL_VALUE_BLOCK_SIZE = 15;
	/**
	 * The internal-node block size of the multi-level sweep. The tree accepts only an **odd** value here, and one no
	 * greater than the leaf block size, so this is the largest legal choice - which matters, because it has to sit
	 * well above {@code ColumnSizing.MIN_PHYSICAL_LENGTH} (4), where an internal node's arrays now start. An internal
	 * node therefore reallocates its separators 4 → 8 → 15 and its children 4 → 8 → 16 before it fills, and each of
	 * those reallocations is one instance of the array-first / `peek`-second pair under test.
	 */
	private static final int MULTILEVEL_INTERNAL_BLOCK_SIZE = 15;
	/**
	 * Minimum occupancy of a leaf in the multi-level sweep. The tree refuses anything above
	 * {@code ceil(blockSize / 2) - 1}, so the two minima below are computed by that rule rather than guessed - the
	 * sweeps never delete, so the value only has to be accepted by the constructor.
	 */
	private static final int MULTILEVEL_MIN_VALUE_BLOCK_SIZE =
		(int) (Math.ceil(MULTILEVEL_VALUE_BLOCK_SIZE / 2.0) - 1);
	/**
	 * Minimum occupancy of an internal node in the multi-level sweep, by the same rule as
	 * {@link #MULTILEVEL_MIN_VALUE_BLOCK_SIZE}.
	 */
	private static final int MULTILEVEL_MIN_INTERNAL_BLOCK_SIZE =
		(int) (Math.ceil(MULTILEVEL_INTERNAL_BLOCK_SIZE / 2.0) - 1);
	/**
	 * Number of keys the multi-level sweep writes per round. Enough that the tree passes through a root split into a
	 * third level rather than merely growing one internal node.
	 */
	private static final int MULTILEVEL_KEYS = 1_024;
	/**
	 * Number of writer / reader races in the overflow sweep. Its rounds are the cheapest of the three - the tree is
	 * small and the work is per record rather than per key - so it runs the most of them.
	 */
	private static final int OVERFLOW_ROUNDS = 5_000;
	/**
	 * Number of distinct keys the overflow sweep writes. Above {@link #MULTILEVEL_VALUE_BLOCK_SIZE} so the tree still
	 * carries internal nodes, but small enough that every leaf holds several multi-record buckets.
	 */
	private static final int OVERFLOW_KEYS = 64;
	/**
	 * Number of records the overflow sweep writes per key. The first promotes the bucket to multi-record and
	 * materializes the leaf's overflow column; the rest widen the bitmap the heap walk prices.
	 */
	private static final int OVERFLOW_RECORDS_PER_KEY = 8;
	/**
	 * Prices a boxed key for the heap-size walk. The walk itself is what these sweeps exercise, not its arithmetic,
	 * so every key is free and the figure is never compared against anything.
	 */
	private static final ToLongFunction<Object> FREE_ELEMENT_SIZER = element -> 0L;

	@Test
	@DisplayName("A management read never fails while a warm-up load grows the leaf columns underneath it")
	void shouldNeverFailASessionFreeWalkWhileAWarmUpLoadGrowsTheColumns() throws Exception {
		final ExecutorService readers = Executors.newSingleThreadExecutor(daemonFactory("bucket-tree-reader"));
		try {
			final Random random = new Random(42L);
			for (int round = 0; round < ROUNDS; round++) {
				runOneRound(readers, shuffledKeys(random, BLOCK_SIZE));
			}
		} finally {
			readers.shutdownNow();
		}
	}

	@Test
	@DisplayName("A management read never fails while a warm-up load grows and splits the internal nodes")
	void shouldNeverFailASessionFreeWalkWhileAWarmUpLoadGrowsTheInternalNodes() throws Exception {
		final ExecutorService readers = Executors.newSingleThreadExecutor(daemonFactory("bucket-tree-spine-reader"));
		try {
			final Random random = new Random(42L);
			for (int round = 0; round < MULTILEVEL_ROUNDS; round++) {
				runOneMultiLevelRound(readers, shuffledKeys(random, MULTILEVEL_KEYS), round);
			}
		} finally {
			readers.shutdownNow();
		}
	}

	@Test
	@DisplayName("A management read never fails while a warm-up load promotes multi-record buckets")
	void shouldNeverFailASessionFreeWalkWhileAWarmUpLoadPromotesMultiRecordBuckets() throws Exception {
		final ExecutorService readers = Executors.newSingleThreadExecutor(daemonFactory("bucket-tree-overflow-reader"));
		try {
			final Random random = new Random(42L);
			for (int round = 0; round < OVERFLOW_ROUNDS; round++) {
				runOneOverflowRound(readers, shuffledKeys(random, OVERFLOW_KEYS), round);
			}
		} finally {
			readers.shutdownNow();
		}
	}

	/**
	 * Runs one writer / reader race over a tree deep enough to carry internal nodes, and asserts the reader's
	 * outcome.
	 *
	 * The reader takes every management entry point - the bucket counter, `recordCount()`, the forward walk the
	 * cardinality statistics take, and the heap-size walk that recurses through the internal nodes themselves - so a
	 * bound missing from any one of them surfaces here.
	 *
	 * It asserts only non-negativity on the fly, for the reason the class javadoc gives: an in-place parent shift can
	 * legitimately make a concurrent walk visit one subtree twice, so the count has no upper bound worth asserting.
	 * `recordCount()` is safe to call here in a way it is not in the overflow sweep, because this tree writes one
	 * record per key and so never reaches `TransactionalBitmap#size()`.
	 *
	 * @param readers the executor the reader runs on
	 * @param keys    the keys this round writes, in the order it writes them
	 * @param round   the round's index, reported on failure so a re-measured calibration can say how deep into the
	 *                budget the escape lies
	 */
	private static void runOneMultiLevelRound(
		@Nonnull ExecutorService readers,
		@Nonnull int[] keys,
		int round
	) throws Exception {
		final TransactionalBucketBPlusTree<Integer> tree = newMultiLevelTree();
		final AtomicBoolean writing = new AtomicBoolean(true);
		final CountDownLatch readerStarted = new CountDownLatch(1);

		final Future<Throwable> reading = readers.submit(() -> {
			readerStarted.countDown();
			try {
				while (writing.get()) {
					final int buckets = tree.bucketCount();
					if (buckets < 0) {
						return new IllegalStateException("A session-free bucket count answered " + buckets);
					}
					final int counted = tree.recordCount();
					if (counted < 0) {
						return new IllegalStateException("A session-free count answered " + counted);
					}
					walkLikeTheCardinalityStatistics(tree);
					// recurses through every internal node, charging both of its arrays and every child under them
					tree.getHeapSizeInBytes(FREE_ELEMENT_SIZER);
				}
				return null;
			} catch (Throwable ex) {
				return ex;
			}
		});

		assertTrue(
			readerStarted.await(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS),
			"The reader never started - the round would have measured nothing"
		);
		// the warm-up load: in place, outside any transaction, exactly as a bulk ingest writes
		for (final int key : keys) {
			tree.addRecord(key, key * 10);
		}
		writing.set(false);

		assertReaderSurvived(reading, round);
		assertEquals(
			MULTILEVEL_KEYS, tree.recordCount(),
			"The writer must have landed every record it wrote, whatever the reader saw on the way"
		);
	}

	/**
	 * Runs one writer / reader race in which the writer promotes buckets to multi-record while the reader prices the
	 * leaves' overflow columns, and asserts the reader's outcome.
	 *
	 * The records are written key by key so that each key's second record promotes its bucket, materializing the
	 * leaf's overflow column and then widening it - which is the only way this tree ever grows that column.
	 *
	 * **The reader deliberately never calls `recordCount()` here, and the round asserts `size()` rather than a record
	 * total.** Summing bucket cardinalities means calling `TransactionalBitmap#size()` on a bitmap a warm-up writer
	 * is adding to, and that memoizes: a reader that computes a cardinality, is overtaken by a writer that mutates
	 * and invalidates, and only then stores its own answer leaves the memo permanently stale. Measured here at 510
	 * records recorded against 512 written. That is a defect in the bitmap's memo, not in the overflow column this
	 * sweep is about, and asserting a record total would keep re-reporting it in the wrong place - see
	 * `TransactionalBitmap#size()`, whose javadoc records the race. The bucket count is unaffected by it.
	 *
	 * @param readers the executor the reader runs on
	 * @param keys    the keys this round writes, in the order it writes them
	 * @param round   the round's index, reported on failure - see {@link #runOneMultiLevelRound}
	 */
	private static void runOneOverflowRound(
		@Nonnull ExecutorService readers,
		@Nonnull int[] keys,
		int round
	) throws Exception {
		final TransactionalBucketBPlusTree<Integer> tree = newMultiLevelTree();
		final AtomicBoolean writing = new AtomicBoolean(true);
		final CountDownLatch readerStarted = new CountDownLatch(1);

		final Future<Throwable> reading = readers.submit(() -> {
			readerStarted.countDown();
			try {
				while (writing.get()) {
					// the leaf heap walk indexes the overflow column slot by slot to price each record set
					tree.getHeapSizeInBytes(FREE_ELEMENT_SIZER);
					// `isSingle()` and `records()` resolve every bucket through OverflowColumn#recordsAt - which is the
					// read under test. `recordCount()` is deliberately NOT called here; see the method javadoc
					walkLikeTheCardinalityStatistics(tree);
				}
				return null;
			} catch (Throwable ex) {
				return ex;
			}
		});

		assertTrue(
			readerStarted.await(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS),
			"The reader never started - the round would have measured nothing"
		);
		for (final int key : keys) {
			for (int record = 0; record < OVERFLOW_RECORDS_PER_KEY; record++) {
				tree.addRecord(key, key * OVERFLOW_RECORDS_PER_KEY + record);
			}
		}
		writing.set(false);

		assertReaderSurvived(reading, round);
		assertEquals(
			OVERFLOW_KEYS, tree.size(),
			"Every key must have landed in exactly one bucket, whatever the reader saw on the way"
		);
	}

	/**
	 * Creates the tree both the multi-level and the overflow sweep write into: small leaves and a comfortably large
	 * internal-node block, so a few hundred keys build a spine three levels deep whose internal nodes reallocate
	 * their arrays several times on the way.
	 *
	 * @return a fresh empty tree, never NULL
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree<Integer> newMultiLevelTree() {
		return new TransactionalBucketBPlusTree<>(
			MULTILEVEL_VALUE_BLOCK_SIZE, MULTILEVEL_MIN_VALUE_BLOCK_SIZE,
			MULTILEVEL_INTERNAL_BLOCK_SIZE, MULTILEVEL_MIN_INTERNAL_BLOCK_SIZE,
			Integer.class, null
		);
	}

	/**
	 * Walks the whole tree forward the way the per-index cardinality statistics do - `FilterIndex#size()` reads each
	 * bucket's lone record id or its record set - so the leaf columns and the overflow column are actually indexed
	 * rather than merely stepped over. Keys are never decoded: no management read does.
	 *
	 * Nothing is asserted about how many buckets the walk yields - see the class javadoc for why a concurrent count
	 * has no upper bound worth asserting here. The walk is driven for its **failure** behaviour: whatever escapes it
	 * propagates to the reader's own catch and is reported out of the round.
	 *
	 * @param tree the tree to walk
	 * @return the number of buckets the walk visited
	 */
	private static int walkLikeTheCardinalityStatistics(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
		int walked = 0;
		final BucketCursor<Integer> cursor = tree.cursor();
		while (cursor.next()) {
			if (cursor.isSingle()) {
				cursor.singleRecordId();
			} else {
				cursor.records();
			}
			walked++;
		}
		return walked;
	}

	/**
	 * Collects the reader's outcome and fails the round when anything escaped it.
	 *
	 * @param reading the reader's pending outcome
	 * @param round   the round's index, for the failure message
	 */
	private static void assertReaderSurvived(@Nonnull Future<Throwable> reading, int round) throws Exception {
		final Throwable escaped = reading.get(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		if (escaped != null) {
			log.error("A session-free bucket tree read failed under a concurrent warm-up load.", escaped);
		}
		assertNull(
			escaped,
			() -> "A management read must never fail while a warm-up load mutates the tree underneath it - it may " +
				"only report a stale figure. Round " + round + " failed with: " + escaped
		);
	}

	/**
	 * Runs one writer / reader race to completion and asserts the reader's outcome.
	 *
	 * The writer is this thread, so the round ends exactly when the load does. The reader spins on the bucket
	 * counter, {@code recordCount()} and the forward walk until told to stop, and reports the first throwable that
	 * escaped it along with the widest count it ever saw.
	 *
	 * @param readers the executor the reader runs on
	 * @param keys    the keys this round writes, in the order it writes them
	 */
	private static void runOneRound(@Nonnull ExecutorService readers, @Nonnull int[] keys) throws Exception {
		final TransactionalBucketBPlusTree<Integer> tree =
			new TransactionalBucketBPlusTree<>(BLOCK_SIZE, Integer.class);
		final AtomicBoolean writing = new AtomicBoolean(true);
		final AtomicInteger widestCount = new AtomicInteger();
		final CountDownLatch readerStarted = new CountDownLatch(1);

		final Future<Throwable> reading = readers.submit(() -> {
			readerStarted.countDown();
			try {
				while (writing.get()) {
					final int buckets = tree.bucketCount();
					if (buckets < 0 || buckets > BLOCK_SIZE) {
						return new IllegalStateException(
							"A session-free bucket count answered " + buckets + ", outside [0, " + BLOCK_SIZE + "]"
						);
					}
					// the statistics entry point: one whole-tree walk with no session behind it
					final int counted = tree.recordCount();
					widestCount.accumulateAndGet(counted, Math::max);
					if (counted < 0 || counted > BLOCK_SIZE) {
						return new IllegalStateException(
							"A session-free count answered " + counted + ", outside [0, " + BLOCK_SIZE + "]"
						);
					}
					// ...and the same leaves walked bucket by bucket, which is what a cardinality statistic does
					final int walked = walkLikeTheCardinalityStatistics(tree);
					if (walked < 0 || walked > BLOCK_SIZE) {
						return new IllegalStateException(
							"A session-free walk visited " + walked + " buckets, outside [0, " + BLOCK_SIZE + "]"
						);
					}
				}
				return null;
			} catch (Throwable ex) {
				return ex;
			}
		});

		assertTrue(
			readerStarted.await(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS),
			"The reader never started - the round would have measured nothing"
		);
		// the warm-up load: in place, outside any transaction, exactly as a bulk ingest writes
		for (final int key : keys) {
			tree.addRecord(key, key * 10);
		}
		writing.set(false);

		final Throwable escaped = reading.get(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		if (escaped != null) {
			log.error("A session-free bucket tree read failed under a concurrent warm-up load.", escaped);
		}
		assertNull(
			escaped,
			() -> "A management read must never fail while a warm-up load grows the leaf columns - it may only " +
				"under-count. It failed with: " + escaped
		);
		assertEquals(
			BLOCK_SIZE, tree.recordCount(),
			"The writer must have landed every record it wrote, whatever the reader saw on the way"
		);
		assertTrue(
			widestCount.get() <= BLOCK_SIZE,
			"The widest count the reader saw (" + widestCount.get() + ") ran past what the writer had written"
		);
	}

	/**
	 * Builds the round's keys: every value in {@code [0, count)} exactly once, in random order, so the inserts land
	 * at random positions and shift the live tail rather than only appending to it - and, in the multi-level sweep,
	 * so leaves split all over the tree rather than only at its right edge.
	 *
	 * @param random the source of the shuffle
	 * @param count  the number of keys to produce
	 * @return the shuffled keys, never NULL
	 */
	@Nonnull
	private static int[] shuffledKeys(@Nonnull Random random, int count) {
		final int[] keys = new int[count];
		for (int i = 0; i < keys.length; i++) {
			keys[i] = i;
		}
		for (int i = keys.length - 1; i > 0; i--) {
			final int j = random.nextInt(i + 1);
			final int swap = keys[i];
			keys[i] = keys[j];
			keys[j] = swap;
		}
		return keys;
	}

	/**
	 * Builds a daemon thread factory so a hung round can never keep the surefire JVM alive.
	 *
	 * @param namePrefix prefix of the created thread names, to keep a thread dump readable
	 * @return thread factory producing daemon threads, never NULL
	 */
	@Nonnull
	private static ThreadFactory daemonFactory(@Nonnull String namePrefix) {
		final AtomicInteger sequence = new AtomicInteger();
		return runnable -> {
			final Thread thread = new Thread(runnable, namePrefix + "-" + sequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}
}
