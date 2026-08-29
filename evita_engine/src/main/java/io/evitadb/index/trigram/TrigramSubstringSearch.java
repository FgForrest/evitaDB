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

package io.evitadb.index.trigram;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.InvertedIndex.MatchedBuckets;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.function.BiPredicate;

/**
 * Answers one `attributeContains` / `attributeEndsWith` from a {@link TrigramIndex} instead of from a scan over every
 * distinct value of the attribute - or declines to, and says so, when the scan is the better bet.
 *
 * # The shape of an answer
 *
 * ```text
 * 1. refuse while a transaction is open           5. intersect their postings into candidate value ids
 * 2. extract the pattern's distinct trigrams      6. resolve each candidate back to its value
 * 3. a trigram nobody posts against -> empty      7. run the EXACT predicate over it
 * 4. price the cheapest posting, decide           8. hand the matched buckets back
 * ```
 *
 * Steps 6-8 belong to the shared value tree ({@link InvertedIndex#getRecordsOfValueIdsMatching}), because the buckets
 * and the leaf-version tokens beside them are the tree's to hand out.
 *
 * It stops at the buckets rather than at a `Formula`: how they are folded into one - eagerly, or deferred behind a
 * `BitmapSupplier` - is the caller's decision, and nothing on this path depends on which is chosen.
 *
 * The index is a CANDIDATE GENERATOR, never an answer: it holds trigram membership only, so a value containing every
 * trigram of the pattern in the wrong arrangement reaches step 7 and is rejected there. That exactness is why the
 * result is interchangeable with the scan's, which is what `TrigramSubstringSearchTest` pins at this level and
 * `AttributeSubstringIndexFunctionalTest` pins through the query engine.
 *
 * # Declining
 *
 * {@link #match} returns `null` rather than an empty answer when the caller must take the scan instead - four
 * distinct situations, all of them ordinary rather than exceptional:
 *
 * - a transaction is open on the calling thread, so the reverse lookup would silently under-report
 *   ({@link InvertedIndex#getValueById(int)} states why);
 * - the pattern is shorter than {@link TrigramCodec#MINIMAL_INDEXABLE_LENGTH} code points and has no trigram at all;
 * - the attribute keeps no trigram index, or the query targets a reduced index whose accelerator lives elsewhere -
 *   both resolved by the caller, before it reaches here;
 * - the pattern is not selective enough for the intersection to beat the scan, see below.
 *
 * # The A/B decision
 *
 * The scan visits every distinct value of the attribute and applies the predicate to each. This path visits only the
 * candidates and applies the SAME predicate to each, then pays one tree descent per value that actually matched. Per
 * unit of work the two are therefore comparable, and the decision reduces to how much of the corpus the candidate set
 * covers - which {@link TrigramIndex#minimumCardinalityOf} bounds from above without materializing anything.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class TrigramSubstringSearch {

	/**
	 * Distinct values below which the scan is taken however selective the pattern looks.
	 *
	 * Two independent readings put the floor in the same place. Measured (brief §35.4, on the flagship `title`
	 * attribute), the corpus size at which the trigram path starts beating the scan is 58-353 entities for a
	 * medium-selectivity pattern - and `title` is very nearly all-distinct, so entities and distinct values coincide
	 * there. Structurally, a tree of at most one leaf block is scanned as a single contiguous array with no descent at
	 * all, which no candidate-generation path can beat; `256` is that block size (`InvertedIndex.VALUE_BLOCK_SIZE`),
	 * restated here rather than imported because this is a query-cost threshold that merely happens to coincide with a
	 * storage parameter, and it must not silently follow that parameter if it is ever retuned.
	 */
	public static final int MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT = 256;

	/**
	 * The candidate set must cover at most `1 / this` of the attribute's distinct values for the trigram path to be
	 * taken.
	 *
	 * Derived from the same measurements. Brief §35.4's crossovers move by four orders of magnitude across the pattern
	 * classes - 17,500-68,500 entities for a common pattern, 58-353 for a medium one, 1-2 for a rare one - and they do
	 * so precisely as the pattern's posting width moves, which is what says the crossover is a RATIO rather than an
	 * absolute size. Reading the widest of them back: a "common" trigram on that corpus posts against roughly 10-30% of
	 * the values, and it crosses over at ~68,500 of them, putting the break-even ratio somewhere between a third and a
	 * tenth. `4` is the conservative end of that band, and deliberately so - being too cautious costs a speedup that
	 * was never guaranteed, while being too eager costs a regression on a query that used to be fine. Verification is
	 * 55-87% of this path's cost and runs about twice as slow per candidate on non-ASCII values, so the band's
	 * pessimistic end is the honest one to sit at.
	 *
	 * This is a deliberately crude stand-in for a cost model. The query planner's own costing of the substring path is
	 * the increment after this one; when it lands, this constant is what it replaces.
	 */
	public static final int CANDIDATE_SELECTIVITY_DIVISOR = 4;

	/**
	 * The answer to a pattern the index can already prove no value contains - distinguished from `null`, which means
	 * "this path declines, use the scan", by being an answer rather than a refusal.
	 */
	private static final MatchedBuckets NO_MATCH = new MatchedBuckets(
		MatchedBuckets.NO_RECORD_SETS, ArrayUtils.EMPTY_LONG_ARRAY
	);

	private TrigramSubstringSearch() {
		throw new UnsupportedOperationException(
			"TrigramSubstringSearch is a static utility and must not be instantiated!"
		);
	}

	/**
	 * Resolves the buckets whose value satisfies `exactPredicate` against `rawPattern`, using `trigramIndex` to narrow
	 * the values that have to be tested.
	 *
	 * The pattern is normalized HERE, exactly once, through the shared value tree's own normalizer - the same instance
	 * that normalized every key the tree holds and every value the trigram index was built from. Normalizing anywhere
	 * else, or a second time, is what would make the query path and the write path disagree about which value contains
	 * what; {@link TrigramCodec} refuses to normalize for exactly this reason.
	 *
	 * Stops at the matched buckets and hands them back rather than folding them into a
	 * {@link io.evitadb.core.query.algebra.Formula}: whether the fold is eager or deferred is the caller's decision,
	 * and nothing above is affected by it. See {@link InvertedIndex#getRecordsOfValueIdsMatching}.
	 *
	 * @param trigramIndex    the attribute's substring accelerator
	 * @param sharedValueTree the value tree whose value ids `trigramIndex` posts against - it MUST be the very tree the
	 *                        index was built from, or the ids name different values
	 * @param rawPattern      the search term as the query supplied it, unnormalized
	 * @param exactPredicate  the exact test the scan path applies, given `(normalizedValue, normalizedPattern)`
	 * @return the matched buckets, empty when nothing matches, or `null` when the caller must take the scan instead
	 */
	@Nullable
	public static MatchedBuckets match(
		@Nonnull TrigramIndex trigramIndex,
		@Nonnull InvertedIndex sharedValueTree,
		@Nonnull String rawPattern,
		@Nonnull BiPredicate<String, String> exactPredicate
	) {
		// pre-flight rather than catch: the reverse lookup REFUSES inside a transaction, and the answer to that refusal
		// is this fallback, so the condition is tested before anything commits to the accelerated path
		if (Transaction.isTransactionAvailable()) {
			return null;
		}
		final String normalizedPattern = (String) sharedValueTree.getNormalizer().apply(rawPattern);
		final long[] trigrams = TrigramCodec.extractUniqueTrigrams(normalizedPattern);
		if (trigrams.length == 0) {
			// under three code points after normalization - the index holds nothing that could bound the search
			return null;
		}
		final int candidateUpperBound = trigramIndex.minimumCardinalityOf(trigrams);
		if (candidateUpperBound == 0) {
			// a trigram of the pattern posts against no value at all, so no value contains the pattern. This bypasses
			// the selectivity gate on purpose: the answer is already known and cost nothing but the cardinality probes,
			// which is the cheapest outcome either path can produce
			return NO_MATCH;
		}
		if (!isWorthAccelerating(candidateUpperBound, sharedValueTree.getBucketCount())) {
			return null;
		}
		final int[] candidates = trigramIndex.resolveCandidateValueIds(trigrams);
		return sharedValueTree.getRecordsOfValueIdsMatching(
			candidates, candidates.length,
			normalizedValue -> exactPredicate.test(asString(normalizedValue), normalizedPattern)
		);
	}

	/**
	 * The staleness tokens an answer produced by {@link #match} depends on BEYOND the leaf pages it read - the trigram
	 * index's own identity, which decides WHICH buckets were verified in the first place and which no leaf token can
	 * express. A mutated index is a fresh instance with a fresh id, so a cached answer cannot survive a write that
	 * changed the postings it was narrowed by.
	 *
	 * @param trigramIndex the accelerator the answer was narrowed by
	 * @return the extra staleness tokens, for a caller assembling a cacheable result
	 */
	@Nonnull
	public static long[] versionIdsOf(@Nonnull TrigramIndex trigramIndex) {
		return new long[]{trigramIndex.getId()};
	}

	/**
	 * Decides whether an intersection bounded at `candidateUpperBound` candidates is worth running against a scan over
	 * `distinctValueCount` values. Exposed so the threshold can be exercised without building a corpus around it.
	 *
	 * @param candidateUpperBound the most candidates the intersection could produce, i.e. the cheapest posting's
	 *                            cardinality
	 * @param distinctValueCount  how many distinct values the scan would have to visit
	 * @return whether the trigram path should be taken
	 */
	public static boolean isWorthAccelerating(int candidateUpperBound, int distinctValueCount) {
		return distinctValueCount >= MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT
			&& candidateUpperBound <= distinctValueCount / CANDIDATE_SELECTIVITY_DIVISOR;
	}

	/**
	 * Reads a candidate's stored value as the `String` the exact predicate needs.
	 *
	 * A value of any other type means the SUBSTRING capability was maintained for an attribute the schema accepts it
	 * on only as `String` / `String[]`, so the accelerator was built over something it cannot describe -
	 * {@link TrigramCodec#extractUniqueTrigramsOfValue} refuses the same divergence on the write side.
	 *
	 * @param normalizedValue the value the shared value tree holds
	 * @return that value as a `String`
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the value is not a `String`
	 */
	@Nonnull
	private static String asString(@Nonnull Serializable normalizedValue) {
		Assert.isPremiseValid(
			normalizedValue instanceof String,
			() -> "The trigram substring path resolved a candidate to a `" + normalizedValue.getClass().getName() +
				"` - the SUBSTRING filter capability is accepted by the schema on String and String[] attributes " +
				"only, so this attribute should never have been given a trigram index."
		);
		return (String) normalizedValue;
	}

}
