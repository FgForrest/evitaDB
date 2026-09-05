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

package io.evitadb.index.bitmap;

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized proof test for {@link TransactionalBitmap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("TransactionalBitmap (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalBitmapTest implements TimeBoundedTestSupport {
	/**
	 * Number of independent writer / reader races the memoized-cardinality sweep runs. A round is a few hundred
	 * in-place adds against spinning readers, so the rounds repeat a narrow window rather than lengthen a wide one.
	 */
	private static final int MEMO_ROUNDS = 2_000;
	/**
	 * Number of record ids the writer adds per round. Set to the count the escape was originally measured at, so a
	 * re-measured counterfactual is directly comparable with the figure the calibration section quotes.
	 */
	private static final int MEMO_RECORDS_PER_ROUND = 512;
	/**
	 * Number of readers spinning on {@link TransactionalBitmap#size()} while the writer fills the bitmap. Three is
	 * enough to keep at least one reader inside the window on an ordinary box without starving the writer.
	 */
	private static final int MEMO_READER_THREADS = 3;
	/**
	 * Bound on a single round of the memoized-cardinality sweep. A round is a few hundred adds against spinning
	 * readers, so this only has to exceed scheduling noise on a loaded box.
	 */
	private static final int MEMO_ROUND_TIMEOUT_SECONDS = 30;


	@ParameterizedTest(name = "TransactionalBitmap should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final int[] initialState =
			generateRandomInitialBitmap(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			10_000,
			new TestState(initialState),
			(random, testState) -> {
				final TransactionalBitmap transactionalBitmap =
					new TransactionalBitmap(testState.initialBitmap());
				final AtomicReference<int[]> nextBitmapToCompare =
					new AtomicReference<>(testState.initialBitmap());

				assertStateAfterCommit(
					transactionalBitmap,
					original -> {
						final int operationsInTransaction = random.nextInt(100);
						for (int i = 0; i < operationsInTransaction; i++) {
							final int length = transactionalBitmap.size();
							if (random.nextBoolean() || length < 10) {
								// insert new item
								final int newRecId =
									random.nextInt(initialCount * 2);
								transactionalBitmap.add(newRecId);
								nextBitmapToCompare.set(
									ArrayUtils.insertIntIntoOrderedArray(
										newRecId, nextBitmapToCompare.get()
									)
								);
							} else {
								// remove existing item
								final int removedRecId =
									transactionalBitmap.get(
										random.nextInt(length)
									);
								transactionalBitmap.remove(removedRecId);
								nextBitmapToCompare.set(
									ArrayUtils.removeIntFromOrderedArray(
										removedRecId,
										nextBitmapToCompare.get()
									)
								);
							}
						}

						assertTransactionalBitmapIs(
							nextBitmapToCompare.get(), transactionalBitmap
						);
					},
					(original, committed) -> {
						assertArrayEquals(
							nextBitmapToCompare.get(),
							committed.getArray()
						);
					}
				);

				return new TestState(
					nextBitmapToCompare.get()
				);
			}
		);
	}

	private int[] generateRandomInitialBitmap(Random rnd, int count) {
		final Set<Integer> uniqueSet = new HashSet<>();
		final int[] initialBitmap = new int[count];
		for (int i = 0; i < count; i++) {
			boolean added;
			do {
				final int recId = rnd.nextInt(count * 2);
				added = uniqueSet.add(recId);
				if (added) {
					initialBitmap[i] = recId;
				}
			} while (!added);
		}
		Arrays.sort(initialBitmap);
		return initialBitmap;
	}

	private static void assertTransactionalBitmapIs(
		int[] expectedResult,
		TransactionalBitmap bitmap
	) {
		if (ArrayUtils.isEmpty(expectedResult)) {
			assertTrue(bitmap.isEmpty());
		} else {
			assertFalse(bitmap.isEmpty());
		}

		for (int recordId : expectedResult) {
			assertTrue(
				bitmap.contains(recordId),
				"IntegerBitmap should contain " + recordId + ", but does not!"
			);
		}

		assertArrayEquals(expectedResult, bitmap.getArray());
		assertEquals(expectedResult.length, bitmap.size());

		final OfInt it = bitmap.iterator();
		int index = -1;
		while (it.hasNext()) {
			final int nextInt = it.next();
			assertTrue(expectedResult.length > index + 1);
			assertEquals(expectedResult[++index], nextInt);
		}
		assertEquals(
			expectedResult.length, index + 1,
			"There are more expected ints than int bitmap produced by iterator!"
		);
	}

	/**
	 * Sweeps the one write to {@link TransactionalBitmap}'s memoized cardinality that a reader must never make.
	 *
	 * ## What is guarded
	 *
	 * {@code size()} answers from a memo that only the mutating thread maintains: single-record {@code add} and
	 * {@code remove} carry it forward by one, the bulk mutators recompute it once, and no reader stores anything.
	 * A reader that stored its own answer could lose a writer's update outright - compute N, be overtaken by a
	 * writer that adds a record, then store N over the writer's mark - leaving the memo holding a count that is
	 * permanently and silently one or more short. That count reaches disk: {@code OwnerSortIndex} persists
	 * {@code bucket.size()} into {@code SortIndexStoragePart}, and the load path slices its record array by those
	 * counts, so a stale-low count leaves records unassigned and a stale-high one refuses to open the catalog.
	 *
	 * ## Why this is a sweep and not a fast-loop test
	 *
	 * There is no seam between the reader's compute and its store, so no deterministic interleaving can be staged -
	 * but unlike the bucket tree's torn-pair sweeps this is **not** a memory-ordering hazard. It is a plain
	 * read-modify-write lost update, reachable by ordinary interleaving on any hardware including x86, so a stress
	 * loop here really is a regression detector rather than a structural demonstration. The exactness of the memo
	 * under every single-threaded mutator shape is pinned separately and deterministically in the fast loop by
	 * {@code TransactionalBitmapTest.MemoizedCardinalityTest}; this sweep adds only the concurrent half.
	 *
	 * ## Calibration
	 *
	 * The counterfactual is the reader's store restored, which means re-introducing the invalidated state the memo
	 * no longer has: give the copy a {@code -1} marker that {@code add} and {@code remove} store instead of carrying
	 * the memo forward, and let {@code size()} recompute <em>and write back</em> {@code this.memoizedCardinality = n}
	 * whenever it reads that marker. Build it by copying {@code TransactionalBitmap.java} into a
	 * scratch directory, editing the copy, compiling it with {@code javac --release 17} against this module's test
	 * classpath and <strong>prepending</strong> the output directory to that classpath so it shadows the installed
	 * engine class - never by editing the shared source, which {@code .claude/rules/testing.md} warns would erase
	 * whatever a concurrent agent wrote to that file.
	 *
	 * The escape it produces is the quiescent assertion below: the memo answers fewer records than the writer wrote.
	 * That shape was measured at <strong>510 records recorded against 512 written</strong> by the overflow sweep of
	 * {@code LongRunningBucketBPlusTreeConcurrentReadTest}, whose reader reached this same memo through
	 * {@code recordCount()} - which is why {@link #MEMO_RECORDS_PER_ROUND} is 512 here, so a re-measurement is
	 * directly comparable. The counterfactual has not been re-measured against this sweep's own budget; whoever
	 * restores a reader-side store, or narrows the window by making the memo cheaper to read, owes that measurement
	 * and should record the round it first escapes at and the wall time here.
	 *
	 * Green side: {@value #MEMO_ROUNDS} rounds of {@value #MEMO_RECORDS_PER_ROUND} adds against
	 * {@value #MEMO_READER_THREADS} spinning readers, each round bounded by a {@value #MEMO_ROUND_TIMEOUT_SECONDS}
	 * second join so a hung round fails rather than stalling the weekly workflow.
	 *
	 * @throws Exception when a reader cannot be joined within the round budget
	 */
	@Test
	@Tag(SLOW)
	@DisplayName("size() stays exact while a warm-up writer fills the bitmap under spinning readers")
	void shouldKeepTheMemoizedCardinalityExactUnderSpinningReaders() throws Exception {
		final ExecutorService readers = Executors.newFixedThreadPool(
			MEMO_READER_THREADS, daemonFactory("bitmap-memo-reader")
		);
		try {
			for (int round = 0; round < MEMO_ROUNDS; round++) {
				runOneMemoRound(readers, round);
			}
		} finally {
			readers.shutdownNow();
		}
	}

	/**
	 * Runs one writer / reader race over a freshly built bitmap and asserts both halves of the memo's contract: no
	 * reader may ever observe a count outside the range the writer can possibly have produced, and once the writer
	 * has stopped the memo must answer exactly what was written.
	 *
	 * The writer runs on the calling thread and adds outside any transaction, exactly as a warm-up bulk ingest
	 * writes. Readers only ever call {@code size()} - the observed value is legitimately stale while the writer is
	 * running, so the in-flight assertion is a range rather than an equality.
	 *
	 * @param readers the executor the readers run on
	 * @param round   the round's index, reported on failure so a re-measured calibration can say how deep into the
	 *                budget the escape lies
	 * @throws Exception when a reader cannot be joined within the round budget
	 */
	private static void runOneMemoRound(@Nonnull ExecutorService readers, int round) throws Exception {
		final TransactionalBitmap bitmap = new TransactionalBitmap();
		final AtomicBoolean writing = new AtomicBoolean(true);
		final CountDownLatch readersStarted = new CountDownLatch(MEMO_READER_THREADS);
		final List<Future<Throwable>> reading = new ArrayList<>(MEMO_READER_THREADS);

		for (int reader = 0; reader < MEMO_READER_THREADS; reader++) {
			reading.add(
				readers.submit(() -> {
					readersStarted.countDown();
					try {
						while (writing.get()) {
							final int observed = bitmap.size();
							if (observed < 0 || observed > MEMO_RECORDS_PER_ROUND) {
								return new IllegalStateException(
									"size() answered " + observed + ", which the writer can never have produced"
								);
							}
						}
						return null;
					} catch (Throwable ex) {
						return ex;
					}
				})
			);
		}

		assertTrue(
			readersStarted.await(MEMO_ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS),
			"The readers never started - the round would have measured nothing"
		);
		// the warm-up load: in place, outside any transaction, exactly as a bulk ingest writes
		for (int recordId = 1; recordId <= MEMO_RECORDS_PER_ROUND; recordId++) {
			bitmap.add(recordId);
		}
		writing.set(false);

		for (final Future<Throwable> reader : reading) {
			assertNull(
				reader.get(MEMO_ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS),
				"A reader observed an impossible cardinality in round " + round
			);
		}
		assertEquals(
			MEMO_RECORDS_PER_ROUND, bitmap.size(),
			"The memo lost a writer's update in round " + round
		);
		assertEquals(
			bitmap.getArray().length, bitmap.size(),
			"The memo drifted away from the materialized array in round " + round
		);
	}

	/**
	 * Builds a thread factory producing daemon threads, so a fixture that fails to shut down cannot keep the
	 * surefire JVM alive and pollute assertions made by sibling classes in the same fork.
	 *
	 * @param namePrefix prefix every produced thread's name carries
	 * @return the factory, never NULL
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

	private record TestState(
		int[] initialBitmap
	) {}
}
