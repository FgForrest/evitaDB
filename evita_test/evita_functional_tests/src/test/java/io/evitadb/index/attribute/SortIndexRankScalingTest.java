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

package io.evitadb.index.attribute;

import com.sun.management.ThreadMXBean;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the defining property of the sort index's first-touch cost: **it must not grow with the number of distinct
 * values**.
 *
 * Both the write anchor and the equality read are answered by a single descent over the value tree, so quadrupling
 * the distinct-value count must leave their cost essentially unchanged. A regression that reintroduces a
 * whole-structure rebuild on first touch after a commit — the historical failure mode, where a single-entity write
 * against a large localized attribute cost seconds — would restore super-linear growth and trip this test.
 *
 * ## Why this measures allocated bytes rather than elapsed time
 *
 * The cost being guarded is real CPU work, but **wall-clock time cannot measure it in this suite**. Surefire runs
 * `<parallel>all</parallel>` with `useUnlimitedThreads` in a single reused fork, so this test shares a JVM with
 * however many sibling classes are booting embedded servers at that moment. Each first-touch operation takes tens
 * of microseconds, and one scheduling delay or stop-the-world pause landing inside such a window is worth two
 * orders of magnitude more than the signal. The earlier timing form of this test failed exactly that way, at
 * `write 4.734 ms -> 0.203 ms (0.04x), read 0.088 ms -> 2.362 ms (26.90x)` — note the *small* measurement being
 * the slow one in the write row, which is noise, not scaling. Isolated, the same run passes in 5.8 s.
 *
 * `getThreadAllocatedBytes` has none of that exposure: it counts bytes this thread allocated, so neither a busy
 * machine, nor a GC pause, nor a sibling test's own allocation can change that number. It is also a *faithful*
 * stand-in here rather than a loose correlate — the regression this test exists to catch was a
 * `CumulativeWeightBPlusTree` rebuilt over every distinct value, and a tree cannot be built without allocating it.
 *
 * ## The one thing per-thread allocation does not survive, and what is done about it
 *
 * A **shared cache that siblings evict** reaches this measurement anyway, because recomputing an evicted entry is
 * charged — correctly — to the thread that needed it. {@link io.evitadb.comparator.CollationKeyCache} is exactly
 * that: a JVM-static per-locale registry of fixed-size slot arrays, consulted on every comparison of the descent
 * being measured. Two mitigations, both in this class:
 *
 * - the measurement runs under a **private {@link Locale} variant** ({@link #CZECH}), so no sibling shares its
 *   cache instance;
 * - each sample **descends to its own probe once before the commit** that discards the per-transaction helper, so
 *   the keys along the path are present regardless of what `CollationKeyCache#sweepAll` — which is static and
 *   reaches every locale, private ones included — did in the meantime.
 *
 * The warm-up cannot make the assertion decorative: `appendStorageParts` nulls `sortIndexChanges`
 * unconditionally, so the *measured* operation is still the first touch after a commit.
 *
 * ## Calibration
 *
 * Measured on OpenJDK 17, medians of nine samples, reproduced bit-for-bit across runs:
 *
 * | median allocated bytes | 50 000 values | 200 000 values | growth |
 * |---|---|---|---|
 * | write anchor (this code) | 20 472 | 19 600 | 0.96x |
 * | first read (this code)   | 6 088  | 6 704  | 1.10x |
 * | rebuild-on-first-touch   | 6 436 192 | 116 413 600 | **18.1x** |
 *
 * The counterfactual row is the pre-`f193d7b83` behaviour, reconstructed by building a fresh
 * `CumulativeWeightBPlusTree` over every distinct value exactly as `SortIndexChanges#getValueTree` did once per
 * transaction. It exceeds {@link #MAX_GROWTH} by six-fold, and its absolute allocation is ~300x this code's at
 * 50 000 values and ~5 900x at 200 000 — so the threshold below discriminates with enormous headroom in both
 * directions. **If this test is ever changed, re-run that counterfactual**: an assertion that no longer fails when
 * the rebuild returns is decorative, and nothing else here would reveal that.
 *
 * ### Why the cache state has to be controlled, in numbers
 *
 * Median allocated bytes for the first read, same commit and same machine, 2026-08-10:
 *
 * | collation-key cache state | 50 000 values | 200 000 values | growth |
 * |---|---|---|---|
 * | warm and private (this code) | 3 856 | 4 224 | 1.10x |
 * | disabled outright (`-Devita.collationKeyCache.size=0`) | 45 616 | 68 888 | 1.51x |
 * | **partially evicted by siblings** (full suite, before this hardening) | 13 368 | 61 672 | **4.61x** |
 *
 * Note that the *worst* ratio is neither extreme. A cold cache charges both points alike and stays well inside
 * the threshold; it is **differential** eviction that breaks the test, because the larger index has four times as
 * many keys to lose and therefore loses proportionally more of them. That is why disabling the cache is not a
 * valid substitute for isolating it, and why a green run in isolation proved nothing before the private locale
 * existed — the failure needed sibling classes to exist at all.
 *
 * Re-measured under 48 busy spinners on a 24-core box (load average ~50), the allocation figures reproduced
 * *unchanged* across three rounds — spinners allocate, but they do not touch this cache, which is precisely the
 * blind spot the row above fills. The elapsed times printed alongside them did not: the same operations timed
 * 2.2x–2.85x under that load **after** median-of-nine smoothing, against the 3.0x limit the earlier form of this
 * test applied to a single unsmoothed sample. Some of that is real — a larger tree misses cache more on descent,
 * and contention amplifies the difference — which is the point: no wall-clock threshold both survives a loaded
 * machine and still catches an 18x regression. Sampling harder does not fix it; the instrument has to change.
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(SLOW)
@DisplayName("Sort index first-touch cost does not scale with distinct value count")
class SortIndexRankScalingTest {
	/**
	 * Czech collation rules under a **private locale variant**, so this test gets a
	 * {@link io.evitadb.comparator.CollationKeyCache} instance no other test can reach.
	 *
	 * The cache registry is a JVM-static `ConcurrentHashMap<Locale, CollationKeyCache>` and each cache is a
	 * fixed-size slot array, so under `parallel=all` in one reused fork every sibling class sorting plain `cs`
	 * strings competes for the same slots. That competition lands on the two measurement points unevenly - the
	 * 200 000-value index has four times as many keys to lose - which inflates the *ratio* this test asserts on.
	 * A variant keys a separate registry entry while `Collator` still falls back to Czech rules, so the cache
	 * state now depends on this test alone.
	 */
	private static final Locale CZECH = new Locale("cs", "CZ", "sortIndexRankScaling");
	private static final String CZECH_ALPHABET = "aábcčdďeéěfghiíjklmnňoóprřsštťuúůvyýzž";
	/**
	 * Distinct-value count of the smaller measurement point.
	 */
	private static final int SMALL = 50_000;
	/**
	 * Distinct-value count of the larger measurement point — deliberately 4x {@link #SMALL}.
	 */
	private static final int LARGE = 4 * SMALL;
	/**
	 * Largest tolerated growth of a first-touch operation across a 4x increase in distinct values. A per-descent
	 * cost measures 0.96x / 1.10x; the rebuild this replaced measures 18.1x. The threshold sits between those
	 * regimes — see the calibration table in the class javadoc.
	 */
	private static final double MAX_GROWTH = 3.0;
	/**
	 * Samples taken per measurement point. The median of these is reported, so a one-off outlier — a lazily
	 * populated cache on the very first call, a TLAB refill landing mid-window — cannot decide the verdict.
	 */
	private static final int SAMPLES = 9;

	/**
	 * Builds a deterministic pseudo-random Czech value for a record, so every run indexes the identical corpus and
	 * pays the identical collation cost.
	 *
	 * @param i the record id
	 * @return a distinct Czech-alphabet value
	 */
	@Nonnull
	private static String valueFor(int i) {
		final Random rnd = new Random(i * 0x9E3779B97F4A7C15L);
		final int length = 8 + rnd.nextInt(12);
		final StringBuilder sb = new StringBuilder(length + 12);
		for (int j = 0; j < length; j++) {
			sb.append(CZECH_ALPHABET.charAt(rnd.nextInt(CZECH_ALPHABET.length())));
		}
		return sb.append(' ').append(i).toString();
	}

	/**
	 * Reads the number of bytes this thread has allocated since it started.
	 *
	 * @param threads the platform thread bean, already known to support allocation accounting
	 * @return monotonically growing allocated-byte count for the calling thread
	 */
	private static long allocatedBytes(@Nonnull ThreadMXBean threads) {
		return threads.getThreadAllocatedBytes(Thread.currentThread().getId());
	}

	/**
	 * Returns the median of the passed samples, sorting a copy so the caller's array keeps its measurement order.
	 *
	 * @param samples the measured values
	 * @return the median sample
	 */
	private static long medianOf(@Nonnull long[] samples) {
		final long[] sorted = samples.clone();
		Arrays.sort(sorted);
		return sorted[sorted.length / 2];
	}

	/**
	 * Builds a localized owner sort index holding `distinctValues` distinct values, then measures the two
	 * first-touch operations — the ones that used to rebuild the whole rank structure after a commit discarded the
	 * per-transaction helper.
	 *
	 * Each sample is preceded by `appendStorageParts`, which discards the per-transaction helper exactly as the
	 * commit / flush path does, so every sample is a genuine *first* touch rather than a repeat of a warm one.
	 *
	 * @param threads        the platform thread bean used to read allocation counters
	 * @param distinctValues the number of distinct values to index
	 * @return the measured `[writeAnchorBytes, firstReadBytes]` medians
	 */
	@Nonnull
	private static long[] measureFirstTouch(@Nonnull ThreadMXBean threads, int distinctValues) {
		final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", CZECH));
		for (int i = 1; i <= distinctValues; i++) {
			sortIndex.addRecord(valueFor(i), i);
		}

		final long[] writeBytes = new long[SAMPLES];
		final long[] writeNanos = new long[SAMPLES];
		for (int sample = 0; sample < SAMPLES; sample++) {
			final int freshId = distinctValues + 1 + sample;
			final String value = valueFor(freshId);
			// Descend to this value once BEFORE the commit, so the collation keys the measured descent will compare
			// against are in the cache whatever the sweeper did to them. This cannot weaken the signal: the discard
			// below sets `sortIndexChanges` to null unconditionally, so the measured operation is still the first
			// touch after a commit - the rebuild this test exists to catch happens there and nowhere else, and it
			// allocates three orders of magnitude more than every collation key on the path put together.
			sortIndex.getRecordsEqualTo(value);
			sortIndex.appendStorageParts(1, new TrappedChanges());
			final long allocatedBefore = allocatedBytes(threads);
			final long startedAt = System.nanoTime();
			sortIndex.addRecord(value, freshId);
			writeNanos[sample] = System.nanoTime() - startedAt;
			writeBytes[sample] = allocatedBytes(threads) - allocatedBefore;
		}

		final long[] readBytes = new long[SAMPLES];
		final long[] readNanos = new long[SAMPLES];
		for (int sample = 0; sample < SAMPLES; sample++) {
			final String value = valueFor(distinctValues / 2 + sample);
			// warmed before the commit for the same reason as the write loop above - see the comment there
			sortIndex.getRecordsEqualTo(value);
			sortIndex.appendStorageParts(1, new TrappedChanges());
			final long allocatedBefore = allocatedBytes(threads);
			final long startedAt = System.nanoTime();
			sortIndex.getRecordsEqualTo(value);
			readNanos[sample] = System.nanoTime() - startedAt;
			readBytes[sample] = allocatedBytes(threads) - allocatedBefore;
		}

		// elapsed time is reported but never asserted on - it is the first thing a human wants to see when this
		// test fails, and the last thing that can be trusted to decide it
		System.out.printf(
			"SortIndex first-touch at %d values: write %d B / %.3f ms, read %d B / %.3f ms (medians of %d)%n",
			distinctValues,
			medianOf(writeBytes), medianOf(writeNanos) / 1e6,
			medianOf(readBytes), medianOf(readNanos) / 1e6,
			SAMPLES
		);
		return new long[]{medianOf(writeBytes), medianOf(readBytes)};
	}

	@Test
	@DisplayName("quadrupling distinct values leaves write-anchor and first-read allocation flat")
	void shouldNotScaleFirstTouchWithDistinctValueCount() {
		final java.lang.management.ThreadMXBean platformThreads = ManagementFactory.getThreadMXBean();
		assumeTrue(
			platformThreads instanceof ThreadMXBean,
			"per-thread allocation accounting is a HotSpot extension - this guard cannot run on this JVM"
		);
		final ThreadMXBean threads = (ThreadMXBean) platformThreads;
		assumeTrue(
			threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled(),
			"per-thread allocation accounting is disabled - this guard cannot run"
		);

		// warm the JIT and the collation cache on a small index, so the measured points are not charged for
		// one-time initialisation of the descent path
		measureFirstTouch(threads, 2_000);

		final long[] small = measureFirstTouch(threads, SMALL);
		final long[] large = measureFirstTouch(threads, LARGE);

		final double writeGrowth = (double) large[0] / Math.max(1L, small[0]);
		final double readGrowth = (double) large[1] / Math.max(1L, small[1]);

		assertTrue(
			writeGrowth < MAX_GROWTH,
			() -> "First write after a commit allocated " + writeGrowth + "x more for a 4x increase in distinct "
				+ "values (limit " + MAX_GROWTH + "x) - the write anchor appears to depend on the value count again."
		);
		assertTrue(
			readGrowth < MAX_GROWTH,
			() -> "First read after a commit allocated " + readGrowth + "x more for a 4x increase in distinct "
				+ "values (limit " + MAX_GROWTH + "x) - the equality read appears to depend on the value count again."
		);
	}
}
