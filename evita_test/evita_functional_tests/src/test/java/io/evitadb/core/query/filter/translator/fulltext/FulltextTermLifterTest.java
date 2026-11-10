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

package io.evitadb.core.query.filter.translator.fulltext;

import io.evitadb.core.query.algebra.fulltext.FulltextFormula;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.core.query.filter.translator.fulltext.FulltextPostingsBudget.PreparedTerm;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.fulltext.FulltextScoringPolicy;
import io.evitadb.index.fulltext.FulltextTermExactness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises projection, lazy maxima, complete int-key support, fan-out pricing and formula dependencies. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@DisplayName("Fulltext target terms lifted to owners")
class FulltextTermLifterTest {
	/** Shared owners get the largest unsigned impact; zero and negative targets remain ordinary primary keys. */
	@Test
	@DisplayName("Lifts complete int keys and takes the maximum impact over referenced items")
	void shouldLiftPostingsAndMaximumImpacts() {
		final AtomicInteger lookups = new AtomicInteger();
		final AtomicInteger impactReads = new AtomicInteger();
		final FulltextTermLifter lifter = new FulltextTermLifter(target -> {
			lookups.incrementAndGet();
			return target == 0 ? new BaseBitmap(1, 2) : new BaseBitmap(2, 3);
		});
		final ExpandedTerm source = term("winter", new BaseBitmap(0, -1),
			ImpactView.deferred(2, () -> {
				impactReads.incrementAndGet();
				return new byte[]{12, (byte) 240};
			}));

		final PreparedTerm prepared = lifter.prepare(source, -1, Long.MAX_VALUE);

		assertNotNull(prepared);
		assertArrayEquals(new int[]{1, 2, 3}, prepared.term().postings().getArray());
		assertEquals(6, prepared.cost());
		assertEquals(2, lookups.get());
		assertEquals(0, impactReads.get(), "Membership and impact cardinality checks do not read impacts");
		assertArrayEquals(new byte[]{12, (byte) 240, (byte) 240}, prepared.term().impacts().toArray());
		assertEquals(1, impactReads.get());
		assertEquals(-1, prepared.term().placeId());
		assertEquals(2, lookups.get(), "Scoring uses the retained owner mapping");
		assertSame(prepared, lifter.prepare(source, -1, Long.MAX_VALUE));
	}

	/** A distinct expansion never repeats the target partition lookup, including an empty answer. */
	@Test
	@DisplayName("Looks up each distinct target only once across terms")
	void shouldMemoizeOwnerLookupIncludingEmptyAndVirtualPartitions() {
		final AtomicInteger lookups = new AtomicInteger();
		final FulltextTermLifter lifter = new FulltextTermLifter(target -> {
			lookups.incrementAndGet();
			return target == 0 ? EmptyBitmap.INSTANCE : new BaseBitmap(7);
		});
		final Bitmap targets = new BaseBitmap(0, -1);
		for (final String word : new String[]{"winter", "jacket", "leather"}) {
			final PreparedTerm prepared = lifter.prepare(term(word, targets, ImpactView.of(new byte[]{1, 2})),
				-2, Long.MAX_VALUE);
			assertNotNull(prepared);
			assertArrayEquals(new int[]{7}, prepared.term().postings().getArray());
		}
		assertEquals(2, lookups.get());
	}

	/** Hashing reference provenance must be independent of the number of expanded terms. */
	@Test
	@DisplayName("Hashes each target's owner bitmap once across terms and repricing")
	void shouldHashOwnersOncePerTarget() {
		final Bitmap owners = spy(new BaseBitmap(7, 8));
		final FulltextTermLifter lifter = new FulltextTermLifter(target -> owners);
		for (final String word : new String[]{"winter", "jacket", "leather"}) {
			final ExpandedTerm term = term(word, new BaseBitmap(0, -1), ImpactView.of(new byte[]{1, 2}));
			assertNull(lifter.prepare(term, -2, 3));
			final PreparedTerm prepared = lifter.prepare(term, -2, Long.MAX_VALUE);
			assertNotNull(prepared);
			assertSame(prepared, lifter.prepare(term, -2, Long.MAX_VALUE));
			assertArrayEquals(new int[]{7, 8}, prepared.term().postings().getArray());
		}
		verify(owners, times(2)).getContentHash(any());
	}

	/** Unreferenced targets contribute no owner and no impact allocation. */
	@Test
	@DisplayName("Keeps an empty lifted posting list empty")
	void shouldLiftEmptyOwners() {
		final FulltextTermLifter lifter = new FulltextTermLifter(target -> EmptyBitmap.INSTANCE);
		final PreparedTerm prepared = lifter.prepare(term("winter", new BaseBitmap(1), ImpactView.of(new byte[]{5})),
			-1, 1);
		assertNotNull(prepared);
		assertTrue(prepared.term().postings().isEmpty());
		assertSame(ImpactView.EMPTY, prepared.term().impacts());
	}

	/** Checks the allowance before the union, and avoids all target lookups when even the input cannot fit. */
	@Test
	@DisplayName("Stops a lift before owner fan-out exceeds the remaining allowance")
	void shouldBoundFanOut() {
		final AtomicInteger lookups = new AtomicInteger();
		final FulltextTermLifter lifter = new FulltextTermLifter(target -> {
			lookups.incrementAndGet();
			return new BaseBitmap(1, 2, 3);
		});
		final ExpandedTerm source = term("winter", new BaseBitmap(1, 2), ImpactView.of(new byte[]{5, 6}));
		assertNull(lifter.prepare(source, -1, 1));
		assertEquals(0, lookups.get());
		assertNull(lifter.prepare(source, -1, 4));
		assertEquals(1, lookups.get(), "Stops at the first target's overflowing fan-out");
		assertNotNull(lifter.prepare(source, -1, 8));
		assertEquals(2, lookups.get());
	}

	/** Reserves exact fan-out before an earlier prefix and stops every later inexact term deterministically. */
	@Test
	@DisplayName("Reserves uncapped lifted exacts and cuts inexact work globally")
	void shouldBudgetLiftedTerms() {
		final AtomicInteger lookups = new AtomicInteger();
		final FulltextTermLifter lifter = new FulltextTermLifter(target -> {
			lookups.incrementAndGet();
			return new BaseBitmap(1, 2, 3, 4);
		});
		final ExpandedTerm exact = term("winter", new BaseBitmap(1, 2), ImpactView.of(new byte[]{5, 6}));
		final ExpandedTerm prefix = new ExpandedTerm(0, "winters", new BaseBitmap(3),
			ImpactView.of(new byte[]{7}), 0, FulltextTermExactness.PREFIX, 0, 1f);
		final ExpandedTerm laterPrefix = new ExpandedTerm(0, "wintery", new BaseBitmap(4),
			ImpactView.of(new byte[]{8}), 0, FulltextTermExactness.PREFIX, 0, 1f);
		final ExpandedTerm[][][] words = {{{prefix, exact, laterPrefix}}};
		final FulltextPostingsBudget.TermPreparation[] preparations = {
			(term, remaining) -> lifter.prepare(term, -1, remaining)
		};
		final ExpandedTerm[][][] retained = FulltextPostingsBudget.retainLifted(words, 0, preparations, new int[]{1});
		assertEquals(1, retained[0][0].length);
		assertEquals("winter", retained[0][0][0].term());
		assertEquals(2, lookups.get(), "Already-reserved exacts prevent all inexact owner lookups");
		assertArrayEquals(new int[]{1, 2, 3, 4},
			FulltextPostingsBudget.toFormula(retained, new int[]{1}, new FulltextScoringPolicy(Map.of())).compute()
				.getArray());
		assertEquals(1, FulltextPostingsBudget.retainLifted(words, 0, preparations, new int[]{1})[0][0].length);
	}

	/** Target changes can leave the owner union identical; both structural and bitmap dependencies still change. */
	@Test
	@DisplayName("Changes formula and bitmap identity when target text or reference membership changes")
	void shouldRetainTargetAndOwnerDependencies() {
		final ExpandedTerm first = term("winter", new BaseBitmap(1), ImpactView.of(new byte[]{12}));
		final ExpandedTerm changed = term("winter", new BaseBitmap(2), ImpactView.of(new byte[]{24}));
		final FulltextFormula a = formula(first, new BaseBitmap(7));
		final FulltextFormula b = formula(changed, new BaseBitmap(7));
		final FulltextFormula c = formula(changed, new BaseBitmap(7, 8));
		final FulltextFormula d = formula(changed, new BaseBitmap(8));
		assertNotEquals(a.getHash(), b.getHash());
		assertFalse(Arrays.equals(a.gatherTransactionalIds(), b.gatherTransactionalIds()));
		assertNotEquals(b.getHash(), c.getHash());
		assertFalse(Arrays.equals(b.gatherTransactionalIds(), c.gatherTransactionalIds()));
		assertNotEquals(c.getHash(), d.getHash());
		assertFalse(Arrays.equals(c.gatherTransactionalIds(), d.gatherTransactionalIds()));
	}

	/** Reports uncapped common-word work and verifies filtering does no impact aggregation. */
	@Test
	@DisplayName("Keeps a common exact beyond the budget and never lifts the following inexact")
	void shouldKeepCommonExactAndReportItsLiftWork() {
		final int targetCount = 1_000;
		final int ownerCount = 4_000;
		final AtomicInteger lookups = new AtomicInteger();
		final AtomicInteger reads = new AtomicInteger();
		final Bitmap owners = new BaseBitmap(IntStream.rangeClosed(1, ownerCount).toArray());
		final Bitmap targets = new BaseBitmap(IntStream.range(0, targetCount).toArray());
		final ImpactView impacts = ImpactView.deferred(targetCount, () -> {
			reads.incrementAndGet();
			final byte[] bytes = new byte[targetCount];
			Arrays.fill(bytes, (byte) 128);
			return bytes;
		});
		final FulltextTermLifter lifter = new FulltextTermLifter(target -> {
			lookups.incrementAndGet();
			return owners;
		});
		final ExpandedTerm exact = term("mahogany", targets, impacts);
		final ExpandedTerm prefix = new ExpandedTerm(0, "mahoganies", new BaseBitmap(10_000),
			ImpactView.of(new byte[]{12}), 0, FulltextTermExactness.PREFIX, 0, 1f);
		final long allowance = FulltextPostingsBudget.limit(1, ownerCount);
		final FulltextPostingsBudget.TermPreparation[] preparations = {
			(term, remaining) -> lifter.prepare(term, -1, remaining)
		};
		final ExpandedTerm[][][] retained = FulltextPostingsBudget.retainLifted(
			new ExpandedTerm[][][]{{{exact, prefix}}}, allowance, preparations, new int[]{1});
		final PreparedTerm lifted = lifter.prepare(exact, -1, Long.MAX_VALUE);
		assertNotNull(lifted);
		assertEquals(4_001_000L, lifted.cost(), "1,000 targets and 4,000,000 owner edges produced");
		assertTrue(lifted.cost() > allowance);
		assertEquals(targetCount, lookups.get());
		assertEquals(1, retained[0][0].length);
		final FulltextFormula formula = FulltextPostingsBudget.toFormula(retained, new int[]{1},
			new FulltextScoringPolicy(Map.of()));
		assertArrayEquals(owners.getArray(), formula.compute().getArray());
		assertEquals(0, reads.get());
		assertEquals(ownerCount, formula.getFulltextScores(owners.getArray()).matchedDocuments());
		assertArrayEquals(owners.getArray(), formula.compute().getArray());
		assertEquals(1, reads.get());
		assertEquals(targetCount, lookups.get(), "Scoring never repeats an owner lookup");
	}

	/** Builds a real formula over the projection, preserving the supplied source dependency. */
	private static FulltextFormula formula(ExpandedTerm term, Bitmap owners) {
		final PreparedTerm lifted = new FulltextTermLifter(target -> owners).prepare(term, -1, Long.MAX_VALUE);
		assertNotNull(lifted);
		return new FulltextFormula(new ExpandedTerm[][]{{lifted.term()}}, null, new FulltextScoringPolicy(Map.of()));
	}

	/** Creates a target expansion with independent posting and impact views. */
	private static ExpandedTerm term(String word, Bitmap postings, ImpactView impacts) {
		return new ExpandedTerm(0, word, postings, impacts, 0, FulltextTermExactness.EXACT, 0, 1f);
	}
}
