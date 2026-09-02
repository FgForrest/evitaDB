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

package io.evitadb.index.invertedIndex;

import io.evitadb.index.attribute.FilterIndex;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stress verification of the one thing about {@link InvertedIndex#getValueById(int)} that no deterministic test can
 * reach: that several query threads meeting a stale value id directory rebuild it exactly once between them.
 *
 * The reverse lookup is a READ that may perform a WRITE. The warm-up path mutates the index outside any transaction
 * and never reaches a commit merge, so the directory is caught up lazily on the first read that follows a write. The
 * rebuild behind that catch-up is not re-entrant: it advances the tree's plain leaf-id counter and calls
 * `assignLeafId`, whose premise refuses a second assignment outright. Two readers that both observe the stale flag
 * therefore either fail that premise — an internal error raised on a query — or interleave their writes into the
 * shared location array and leave live values resolving to `null`, which is exactly the silent under-report that
 * `getValueById`'s own transaction premise exists to rule out.
 *
 * That interleaving is a handful of statements wide and nothing can place a second reader inside it on demand, so
 * `ValueIdTest` in the functional module deliberately does not try. It pins the neighbouring, reachable cases in the
 * fast loop — "resolution survives a commit that re-shells the leaves" and "a commit leaves the previous version
 * resolving against its own directory" — and this test covers the remainder the only way it can be covered: by
 * releasing many readers onto the same stale directory at once, over enough rounds that the race is hit repeatedly.
 *
 * **It lives here, and it runs.** A probabilistic test in the fast loop fails once every few hundred CI runs and
 * trains everyone to press re-run - but this module is not the fast loop. It is reached only by the weekly
 * `long-running-tests` workflow, which is the isolation that concern actually asks for; disabling it on top of that
 * bought nothing except invisibility, and a run costs ~2 s.
 *
 * **What running it weekly does and does not buy.** It catches the regression that matters most and is easiest to
 * commit: someone removing or weakening the `synchronized` on `InvertedIndex#refreshValueIdDirectory`. It cannot
 * catch this test going BLUNT - a narrowed race window passes just as green as a healthy one - and nothing automatic
 * can, short of mutation-testing that one method. So the calibration below is a human obligation, and it is stated
 * at both guarded methods rather than only here: change either of them and re-run the COUNTERFACTUAL, not just the
 * test.
 *
 * **Calibration (measured, not estimated).** With `synchronized` removed from
 * `InvertedIndex#refreshValueIdDirectory` the run fails 3 times out of 3, latest observed at round 417 of
 * {@link #ROUNDS} — either on a live value resolving to `null`, or on `assignLeafId`'s "assigned once and never
 * reassigned" premise, which are the two shapes the same race takes. With the guard in place all {@link #ROUNDS}
 * rounds pass in ~2 s, 3 times out of 3. Re-measure after changing either method: if the counterfactual stops
 * failing, this test has quietly become decorative, and {@link #READER_THREADS} or {@link #ROUNDS} must be raised
 * until it fails again before a green run means anything.
 *
 * **That has already happened once, which is why the numbers above are the second set.** At the original 500 rounds
 * the counterfactual passed outright: the race window had narrowed under an unrelated optimization — the
 * directory's leaf table moved from a boxed `Map<Long, …>` to a primitive one, which makes the rebuild this test
 * races against measurably shorter. Across a recalibration sweep the failing round clustered at 267-450 whatever
 * the reader count was, so the window opens with tree SIZE rather than with the number of lottery tickets drawn;
 * 2000 rounds is that worst case with a margin of about four. Raising {@link #READER_THREADS} to 16 or 24 also
 * restores the failure and is the knob to reach for next, but it did not move the failing round — it buys overlap,
 * not margin.
 *
 * **What this test does NOT cover, and why no test does.** A reader that already passed the staleness check — having
 * seen it `false` — can still be inside the tree's `valueOf` while a later reader rebuilds. That window is closed
 * STRUCTURALLY rather than by a lock: the directory is one immutable `ValueIdDirectory` behind a single volatile
 * field, filled into a fresh location array and published whole, so such a reader resolves through the generation it
 * read (see `TransactionalBucketBPlusTree.ValueIdDirectory`).
 *
 * No stress test accompanies that change, and the reason is a measurement rather than an opinion. A harness running
 * eight readers against a stable key range while a single writer appended and forced rebuilds was run against BOTH
 * implementations: ~1.9M reads over 4.3K rebuilds on the fixed shape and ~10.5M reads over ~15K rebuilds across three
 * runs of the PRE-FIX three-field shape, with no failure on either. That is the expected outcome, because the two are
 * observationally equivalent through `valueOf`: every hit is validated against the slot it lands on
 * (`leaf.valueIdAt(slot) == valueId`), so any mixture of two generations — and any torn `long[]` read — resolves to
 * `null` rather than to a wrong value, and `null` is also what a *consistent* read of a stale generation returns for
 * a value that has moved. A test able to tell them apart would have to observe the mixture itself, which is not
 * reachable through the public surface.
 *
 * What the fix therefore buys is **safe publication**: without it, `leafById` is a freshly built map assigned
 * to a non-volatile field, and a reader with no happens-before edge to that write may observe it partially
 * constructed — a hazard that does not manifest on x86 and cannot be provoked deliberately. Shipping a stress test
 * that passes on the broken implementation would have been decorative, which is worse than none. What IS pinned
 * deterministically is the invariant the fix rests on: `ValueIdTest`'s "a commit leaves the previous version
 * resolving against its own directory" fails the moment a rebuild writes into an array a published directory handed
 * out.
 *
 * Every reader here is released against a directory that is already stale, so they contend on the rebuild rather
 * than on a rebuild-versus-read — which is the half a lock really does own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@Tag(SLOW)
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("Long-running value id directory concurrency tests")
class LongRunningValueIdDirectoryConcurrencyTest {

	/**
	 * Names the registration that switches the id column on; only its presence matters, never its value.
	 */
	private static final String TEST_CONSUMER = "value-id-directory-concurrency-test";
	/**
	 * The leaf block size of an {@link InvertedIndex}. Each round inserts more than this many NEW distinct values, so
	 * every round leaves at least one freshly split leaf carrying no leaf id yet — which is what makes two concurrent
	 * rebuilds collide on `assignLeafId` rather than merely duplicating harmless work.
	 */
	private static final int LEAF_BLOCK_SIZE = 256;
	/**
	 * Distinct values the index starts with, spanning many leaves so a rebuild is a real walk rather than one node.
	 */
	private static final int SEED_VALUES = LEAF_BLOCK_SIZE * 8;
	/**
	 * Independent races. Each round dirties the directory afresh and releases a new wave of readers at it.
	 */
	private static final int ROUNDS = 2000;
	/**
	 * Readers released simultaneously per round. More than the core count on purpose: the readers must genuinely
	 * overlap rather than take turns.
	 */
	private static final int READER_THREADS = 8;
	/**
	 * How long a round waits for its readers. A healthy round resolves in microseconds; this bound only has to exceed
	 * scheduling noise on a loaded box, and a positive wait can only fail spuriously if it is too tight.
	 */
	private static final long ROUND_TIMEOUT_SECONDS = 30L;

	@Test
	@DisplayName("Concurrent reverse lookups after a warm-up write all resolve every live value")
	void shouldResolveEveryLiveValueWhenReadersRebuildTheDirectoryConcurrently() throws Exception {
		final ExecutorService readers = Executors.newFixedThreadPool(
			READER_THREADS, daemonFactory("value-id-directory-reader")
		);
		try {
			final InvertedIndex index = new InvertedIndex(
				Integer.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
			);
			index.attachValueIdConsumer(TEST_CONSUMER);
			int nextValue = 0;
			for (; nextValue < SEED_VALUES; nextValue++) {
				index.addRecord(nextValue, nextValue + 1);
			}

			for (int round = 0; round < ROUNDS; round++) {
				// insert a whole leaf block of NEW values, so this round's rebuild meets at least one split-born leaf
				// that still carries no leaf id - and mark the directory stale in the process
				final int roundFirstValue = nextValue;
				for (int i = 0; i <= LEAF_BLOCK_SIZE; i++, nextValue++) {
					index.addRecord(nextValue, nextValue + 1);
				}
				// the ids are read on this thread, before any reader starts, so the expectations below are taken from
				// the forward lookup rather than from the directory the readers are about to rebuild
				final int[] expectedIds = new int[nextValue - roundFirstValue];
				for (int i = 0; i < expectedIds.length; i++) {
					expectedIds[i] = index.getValueId(roundFirstValue + i);
				}

				final CountDownLatch startLine = new CountDownLatch(1);
				final List<Future<?>> probes = new ArrayList<>(READER_THREADS);
				for (int reader = 0; reader < READER_THREADS; reader++) {
					probes.add(
						readers.submit(() -> {
							startLine.await();
							for (int i = 0; i < expectedIds.length; i++) {
								assertEquals(
									roundFirstValue + i, index.getValueById(expectedIds[i]),
									"value " + (roundFirstValue + i) + " did not resolve for a reader that met the "
										+ "directory while another reader was rebuilding it"
								);
							}
							return null;
						})
					);
				}
				startLine.countDown();
				for (final Future<?> probe : probes) {
					// generous by design: the latch returns the instant the work completes, so a wide bound costs a
					// passing run nothing and still fails a genuine hang
					probe.get(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
				}
			}
		} finally {
			readers.shutdownNow();
		}
	}

	/**
	 * Builds a daemon thread factory, so a fixture that fails to shut down cannot keep the surefire JVM alive and
	 * pollute assertions made by sibling classes in the same fork.
	 *
	 * @param name the thread name prefix
	 * @return the daemon-producing factory
	 */
	@Nonnull
	private static ThreadFactory daemonFactory(@Nonnull String name) {
		return runnable -> {
			final Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		};
	}

}
