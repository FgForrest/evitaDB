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

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import java.util.Locale;
import java.util.Random;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the defining property of the sort index's first-touch cost: **it must not grow with the number of distinct
 * values**.
 *
 * Both the write anchor and the equality read are answered by a single descent over the value tree, so quadrupling
 * the distinct-value count must leave their cost essentially unchanged. A regression that reintroduces a
 * whole-structure rebuild on first touch after a commit — the historical failure mode, where a single-entity write
 * against a large localized attribute cost seconds — would restore super-linear growth and trip this test.
 *
 * The assertions compare a **ratio measured inside one JVM**, never an absolute duration, so the test is
 * independent of machine speed and of CI load. The historical rebuild grew roughly 8x per 4x of distinct values;
 * a descent grows by well under 2x. The threshold sits between those regimes with generous headroom for noise.
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(SLOW)
@DisplayName("Sort index first-touch cost does not scale with distinct value count")
class SortIndexRankScalingTest {
	private static final Locale CZECH = new Locale("cs");
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
	 * cost grows sub-2x; the historical rebuild grew ~8x. Anything at or above this threshold means the cost has
	 * become a function of the value count again.
	 */
	private static final double MAX_GROWTH = 3.0;

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
	 * Builds a localized owner sort index holding `distinctValues` distinct values, then measures the two
	 * first-touch operations — the ones that used to rebuild the whole rank structure after a commit discarded the
	 * per-transaction helper.
	 *
	 * @param distinctValues the number of distinct values to index
	 * @return the measured `[writeAnchorNanos, firstReadNanos]` pair
	 */
	@Nonnull
	private static long[] measureFirstTouch(int distinctValues) {
		final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", CZECH));
		for (int i = 1; i <= distinctValues; i++) {
			sortIndex.addRecord(valueFor(i), i);
		}

		// discard the per-transaction helper exactly as the commit / flush path does, so the following operation is
		// the transaction's FIRST touch of this index
		sortIndex.appendStorageParts(1, new TrappedChanges());
		final int freshId = distinctValues + 1;
		final long writeStart = System.nanoTime();
		sortIndex.addRecord(valueFor(freshId), freshId);
		final long writeNanos = System.nanoTime() - writeStart;

		sortIndex.appendStorageParts(1, new TrappedChanges());
		final long readStart = System.nanoTime();
		sortIndex.getRecordsEqualTo(valueFor(distinctValues / 2));
		final long readNanos = System.nanoTime() - readStart;

		return new long[]{writeNanos, readNanos};
	}

	@Test
	@DisplayName("quadrupling distinct values leaves write-anchor and first-read cost flat")
	void shouldNotScaleFirstTouchWithDistinctValueCount() {
		// warm the JIT and the collation cache on a small index, so the measured points are not dominated by
		// first-execution compilation of the descent path
		measureFirstTouch(2_000);

		final long[] small = measureFirstTouch(SMALL);
		final long[] large = measureFirstTouch(LARGE);

		final double writeGrowth = (double) large[0] / Math.max(1L, small[0]);
		final double readGrowth = (double) large[1] / Math.max(1L, small[1]);
		System.out.printf(
			"SortIndex first-touch: write %.3f ms -> %.3f ms (%.2fx), read %.3f ms -> %.3f ms (%.2fx) for %dx values%n",
			small[0] / 1e6, large[0] / 1e6, writeGrowth,
			small[1] / 1e6, large[1] / 1e6, readGrowth,
			LARGE / SMALL
		);

		assertTrue(
			writeGrowth < MAX_GROWTH,
			() -> "First write after a commit grew " + writeGrowth + "x for a 4x increase in distinct values (limit "
				+ MAX_GROWTH + "x) - the write anchor appears to depend on the value count again."
		);
		assertTrue(
			readGrowth < MAX_GROWTH,
			() -> "First read after a commit grew " + readGrowth + "x for a 4x increase in distinct values (limit "
				+ MAX_GROWTH + "x) - the equality read appears to depend on the value count again."
		);
	}
}
