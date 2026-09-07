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
 * Sweeps the one read of a bucket tree that shares no lock, no volatile and no transaction with its writer: the
 * management and statistics API, which walks leaves on a request thread with no session and no catalog-state guard
 * while a warm-up load mutates the very leaves it is walking. {@code recordCount()} is its entry point.
 *
 * ## What can go wrong, and why bounding by `size()` is not enough
 *
 * A leaf column grows by two plain field stores - the longer backing array is published first, the live count is
 * raised second - with no happens-before edge to any reader. A reader may therefore observe the **new** count against
 * the **old**, shorter array and index off the end of it. The cursors take
 * {@code ValueColumn#observableLiveRun()} instead, which is {@code min(size, physical length)} read from the reader's
 * own view, and every column is asked rather than just the key column: the four are grown by four independent
 * reallocations, so a torn reader can catch any one of them behind the others.
 *
 * The sweep asserts what the API promises: the reader may under-count by whatever the writer has not finished, and it
 * may never fail.
 *
 * ## Three sweeps, three structures - and why the first one stays inside ONE leaf
 *
 * The hazard has three independent instances in this tree, so it gets three sweeps rather than one, each shaped to
 * reach exactly one of them:
 *
 * 1. {@link #shouldNeverFailASessionFreeWalkWhileAWarmUpLoadGrowsTheColumns()} - the **leaf columns**. Its block size
 *    and key count are equal, so no round ever splits and the tree never grows an internal node. That is deliberate:
 *    an internal node grows its `children` array and raises its `peek` through the same unordered pair, and letting
 *    that failure land in this sweep would let it be attributed to the columns. Every reallocation the leaf columns
 *    perform on the way from four slots to {@value #BLOCK_SIZE} happens inside that one leaf.
 * 2. {@link #shouldNeverFailASessionFreeWalkWhileAWarmUpLoadGrowsTheInternalNodes()} - the **internal nodes**, which
 *    size their `keys` and `children` arrays to the live content and grow them by the same
 *    array-first / `peek`-second pair. It uses a small block size and enough keys to build a three-level tree, so
 *    every internal node grows its arrays repeatedly and splits, under a reader taking all four session-free entry
 *    points (`recordCount()`, a forward walk, a keyed walk and a reverse walk) plus the heap-size walk that recurses
 *    through the internal nodes themselves.
 * 3. {@link #shouldNeverFailASessionFreeWalkWhileAWarmUpLoadPromotesMultiRecordBuckets()} - the **overflow column**,
 *    which neither of the other two ever materializes, because both write exactly one record per key and a bucket is
 *    promoted only by its second. Only a leaf's heap-size walk indexes that column by a separately-read count.
 *
 * ## What sweeps 2 and 3 do NOT assert, and why
 *
 * Sweep 1 can bound the count it observes, because a single leaf has no parent to shift. Sweeps 2 and 3 cannot, and
 * asserting it would make them flaky for a reason that is not a defect: a leaf split inserts the new right sibling
 * into its parent's `children` array **in place**, and the shift that makes room transiently leaves one child
 * pointer duplicated. A concurrent walk that crosses that instant legitimately visits one subtree twice and counts
 * more buckets than the writer has written. That is the same advisory-read staleness the API documents, so both
 * assert only what the API actually promises - the walk must not fail - plus a quiescent count once the writer has
 * stopped. Sweep 2 adds a non-negativity check on the fly; sweep 3 does not even call `recordCount()`, for the
 * separate reason its own javadoc gives.
 *
 * ## Calibration (measured, not estimated)
 *
 * The counterfactual is the cursors' bound reverted to {@code Math.min(leaf.getPeek(), keys.size() - 1)} - the key
 * column alone, read through `size()` rather than through `observableLiveRun()`. Built by copying
 * `TransactionalBucketBPlusTree.java` into a scratch directory, editing the copy, compiling it with
 * `javac --release 17` against this module's test classpath and **prepending** the output directory to that classpath
 * so it shadows the installed engine class - never by editing the shared source, which
 * `.claude/rules/testing.md` warns would erase whatever a concurrent agent wrote to that file.
 *
 * **The counterfactual did NOT fail, and the reason is the CPU rather than the budget.** 500 000 rounds - ten times
 * what the green side runs - produced no {@link ArrayIndexOutOfBoundsException} and no other escape, on both
 * OpenJDK 17.0.20 (37.9 s) and OpenJDK 21.0.12 (20.5 s). That is expected on this box and is reported rather than
 * papered over: it is **x86_64**, whose total-store-order model forbids exactly the reordering the bound guards.
 * The writer stores the longer array before it raises the count, and a reader that loads the count before the array
 * cannot see the second store without the first on TSO. So the failure this bound exists for is unreachable on any
 * x86 machine, and a stress loop here can never become a regression detector for it however long it is run.
 *
 * It is reachable elsewhere. Both pairs are plain fields with no happens-before edge between them, so the Java
 * memory model permits the reordering outright, and hardware that does not enforce store-store ordering - AArch64,
 * the platform evitaDB is also built for - allows it in silicon. A JIT that sinks the array store past the count
 * store would reopen it on x86 too.
 *
 * This test is therefore kept as a **no-exception sweep with a bounded-count assertion**: it proves the walk
 * survives a live warm-up load, which is what the API promises, and it would catch a bound that stopped being taken
 * at all. The bound's own arithmetic is pinned deterministically instead, in the fast loop, by `ColumnSizingTest`
 * and `OverflowColumnTest` - which assert that an aligned column observes its whole live run, so an
 * `observableLiveRun()` that under-reported would fail there - and by
 * `TransactionalBucketBPlusTreeTest#shouldBoundTheCursorByTheColumnLiveRunWhenALeafPeekRunsAhead`, which drives a
 * cursor over a leaf whose `peek` really has run ahead of its columns.
 *
 * **Re-measure the counterfactual on an AArch64 box** before concluding anything about the bound from a run here.
 * Green side, measured on a 24-core x86_64 Linux box, OpenJDK 17.0.20, otherwise idle: all {@value #ROUNDS} rounds
 * pass, in 2.4 s on an idle box and 3.4 s under a concurrent build.
 *
 * ## Sweeps 2 and 3 - and why only ONE of them has a counterfactual on this hardware
 *
 * **A green run on x86 is NOT proof that either bound is correct, and it must never be read as one.** But the two
 * sweeps sit on opposite sides of that, and conflating them would waste the one real signal here:
 *
 * - **Sweep 2 is calibrated, and its counterfactual fails on x86.** With the internal-node bound absent - the engine
 *   built from this branch's parent commit, which has `observableLeafPeek` but no `observableInternalPeek` - the
 *   sweep failed **3 runs out of 3**, at rounds **308, 284 and 41** of the 1 000 the budget then stood at (it was
 *   raised to {@value #MULTILEVEL_ROUNDS} afterwards, for margin), in 0.10-0.23 s,
 *   every time with `ArrayIndexOutOfBoundsException: Index 8 out of bounds for length 8` thrown from
 *   `ForwardBucketCursor.moveToNextLeaf` - the line that dereferences the `(children, peek)` pair the cursor stored
 *   one call earlier. Measured on a 24-core x86_64 Linux box, OpenJDK 17. The green side passes all
 *   {@value #MULTILEVEL_ROUNDS} rounds, 3 runs of 3.
 *
 *   That it fails on x86 at all is the point, and it contradicts the assumption carried over from sweep 1. Sweep 1's
 *   reader loads the **count before the array**, and total store order forbids seeing the second store without the
 *   first. The internal-node sites reached here load the **array before the count** - `moveToNextLeaf`,
 *   `moveToPrevLeaf`, `addLeftmostCursorLevels`, `searchIndex` - and an array loaded before the writer's grow paired
 *   with a `peek` loaded after the writer's increment is a plain interleaving that needs no reordering at all. So
 *   this one really is a regression detector on ordinary hardware, not only on AArch64.
 *
 * - **Sweep 3 is not calibrated and cannot be on this box.** Every read it exercises - the overflow column's
 *   `observableLiveRun()` and `bitmapAt` - loads the count first, so TSO forbids the escape here exactly as it does
 *   in sweep 1. It is a structural demonstration: it shows the heap walk survives a live promotion load, which
 *   nothing else exercises, and it would catch the bound being dropped altogether. Re-measure its counterfactual on
 *   AArch64 before concluding anything about that bound from a green run here.
 *
 * Both sweeps' bounds are pinned deterministically in the fast loop rather than by this budget.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@Tag(SLOW)
@Tag(INDEXING)
@DisplayName("Long-running session-free bucket tree walk under a concurrent warm-up load")
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
	 * Stride between the keys the multi-level reader point-probes. Coprime with nothing in particular - it only has
	 * to spread the probes across the whole key range, so that some descend the leftmost spine and some the
	 * rightmost, where the writer is growing. Kept coarse deliberately: the reader has to spin fast enough to land
	 * inside a window a few instructions wide, and probing all 1 024 keys per pass would slow it far more than the
	 * extra coverage is worth.
	 */
	private static final int POINT_PROBE_STRIDE = 37;
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
	@DisplayName("A session-free walk never fails while a warm-up load grows the leaf columns underneath it")
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
	@DisplayName("A session-free walk never fails while a warm-up load grows and splits the internal nodes")
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
	@DisplayName("A session-free heap walk never fails while a warm-up load promotes multi-record buckets")
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
	 * The reader takes every session-free entry point that descends through an internal node - `recordCount()` and
	 * its forward walk, a keyed walk (the `searchIndex` descent), a reverse walk (the rightmost descent and
	 * `moveToPrevLeaf`) and the heap-size walk that recurses through the internal nodes themselves - so a bound
	 * missing at any one of them surfaces here rather than only in whichever the statistics API happens to call.
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
					final int counted = tree.recordCount();
					if (counted < 0) {
						return new IllegalStateException("A session-free count answered " + counted);
					}
					walkAllThreeCursors(tree);
					// the point descent - `findLeafNode` - which no cursor reaches; see the method javadoc
					probeEveryPointLookup(tree);
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
	 * leaf's overflow column and then widening it - which is the only way this tree ever grows that column, and the
	 * only path on which its heap walk indexes it by a separately-read count.
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
					// the leaf heap walk indexes the overflow column slot by slot to price each bitmap
					tree.getHeapSizeInBytes(FREE_ELEMENT_SIZER);
					// `records()` resolves every bucket through OverflowColumn.bitmapAt - which is the read under
					// test. `recordCount()` is deliberately NOT called here; see the method javadoc
					walkAllThreeCursors(tree);
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
	 * Drives the three session-free cursor shapes over the whole tree, touching each bucket's value and records so
	 * the leaf columns and the overflow column are actually indexed rather than merely stepped over.
	 *
	 * Nothing is asserted about how many buckets the walks yield - see the class javadoc for why a concurrent count
	 * has no upper bound worth asserting here. The walks are driven for their **failure** behaviour: whatever escapes
	 * them propagates to the reader's own catch and is reported out of the round.
	 *
	 * @param tree the tree to walk
	 */
	private static void walkAllThreeCursors(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
		// the leftmost descent plus moveToNextLeaf
		walkOneCursor(tree.cursor());
		// the searchIndex descent - the same one the write path takes, reached here with no session at all
		walkOneCursor(tree.cursor(0));
		// the rightmost descent plus moveToPrevLeaf
		walkOneCursor(tree.reverseCursor());
	}

	/**
	 * Takes every session-free **point** lookup the tree offers, so the allocation-free descent behind them is
	 * exercised alongside the cursor descents.
	 *
	 * This exists because the cursors do not reach it. `TransactionalBucketBPlusTree#findLeafNode` is a separate
	 * descent from the one {@code cursor(key)} takes - it captures no path, and it chooses each child by reading the
	 * children array and the search index as two independent loads. That is the same array-first / count-second pair
	 * the rest of this class sweeps for, and it went unguarded through the round of fixes that bound the seven cursor
	 * sites precisely because no reader here ever called it. Every point API in the tree descends through it:
	 * `contains`, `cardinalityOf`, `valueIdOf`, `getRecordsEqualTo` and `computePreviousRecord`.
	 *
	 * Only the int-payload APIs are taken, because the sweeps build an int tree; the long-payload sibling asserts its
	 * way out on such a tree rather than descending. Return values are discarded on purpose - a concurrent warm-up
	 * load makes any answer legitimately stale, and what is under test is that the descent does not throw.
	 *
	 * @param tree the tree to probe
	 */
	private static void probeEveryPointLookup(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
		for (int key = 0; key < MULTILEVEL_KEYS; key += POINT_PROBE_STRIDE) {
			tree.contains(key);
			tree.cardinalityOf(key);
			tree.valueIdOf(key);
			tree.getRecordsEqualTo(key);
			tree.computePreviousRecord(key, key * 10);
		}
	}

	/**
	 * Walks one cursor to exhaustion, touching every bucket it yields so the leaf's key, record and overflow columns
	 * are all indexed.
	 *
	 * @param cursor the cursor to drain
	 */
	private static void walkOneCursor(@Nonnull BucketCursor<Integer> cursor) {
		while (cursor.next()) {
			cursor.value();
			cursor.records();
		}
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
			log.error("A session-free bucket tree walk failed under a concurrent warm-up load.", escaped);
		}
		assertNull(
			escaped,
			() -> "A session-free walk must never fail while a warm-up load mutates the tree underneath it - it may " +
				"only report a stale count. Round " + round + " failed with: " + escaped
		);
	}

	/**
	 * Runs one writer / reader race to completion and asserts the reader's outcome.
	 *
	 * The writer is this thread, so the round ends exactly when the load does. The reader spins on
	 * {@code recordCount()} and a forward walk until told to stop, and reports the first throwable that escaped it
	 * along with the widest count it ever saw.
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
					// the statistics entry point: one whole-tree walk with no session behind it
					final int counted = tree.recordCount();
					widestCount.accumulateAndGet(counted, Math::max);
					if (counted < 0 || counted > BLOCK_SIZE) {
						return new IllegalStateException(
							"A session-free count answered " + counted + ", outside [0, " + BLOCK_SIZE + "]"
						);
					}
					// ...and the same leaves walked bucket by bucket, which is what a management read does
					int walked = 0;
					final BucketCursor<Integer> cursor = tree.cursor();
					while (cursor.next()) {
						cursor.value();
						cursor.records();
						walked++;
					}
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
			log.error("A session-free bucket tree walk failed under a concurrent warm-up load.", escaped);
		}
		assertNull(
			escaped,
			() -> "A session-free walk must never fail while a warm-up load grows the leaf columns - it may only " +
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
