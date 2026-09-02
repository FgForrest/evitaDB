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
 * ## Why the sweep stays inside ONE leaf
 *
 * The block size and the key count are equal, so no round ever splits and the tree never grows an internal node. That
 * is deliberate: an internal node grows its `children` array and raises its `peek` through the same unordered pair,
 * so a cursor holding a stale `children` array against a fresh `peek` has a torn read of its own - a **separate**
 * hazard, in different code, that this sweep is not measuring and must not be allowed to attribute to the columns.
 * Every reallocation the leaf columns perform on the way from four slots to {@value #BLOCK_SIZE} is exercised inside
 * that one leaf, which is the whole of what the bound under test covers.
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

	@Test
	@DisplayName("A session-free walk never fails while a warm-up load grows the leaf columns underneath it")
	void shouldNeverFailASessionFreeWalkWhileAWarmUpLoadGrowsTheColumns() throws Exception {
		final ExecutorService readers = Executors.newSingleThreadExecutor(daemonFactory("bucket-tree-reader"));
		try {
			final Random random = new Random(42L);
			for (int round = 0; round < ROUNDS; round++) {
				runOneRound(readers, shuffledKeys(random));
			}
		} finally {
			readers.shutdownNow();
		}
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
	 * Builds the round's keys: every value in {@code [0, BLOCK_SIZE)} exactly once, in random order, so the leaf's
	 * inserts land at random positions and shift the live tail rather than only appending to it.
	 *
	 * @param random the source of the shuffle
	 * @return the shuffled keys, never NULL
	 */
	@Nonnull
	private static int[] shuffledKeys(@Nonnull Random random) {
		final int[] keys = new int[BLOCK_SIZE];
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
