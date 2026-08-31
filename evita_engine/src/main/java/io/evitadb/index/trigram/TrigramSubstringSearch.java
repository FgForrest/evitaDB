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
import java.util.function.LongUnaryOperator;

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
 * - the attribute keeps no trigram index at all - resolved by the caller, before it reaches here. A plan targeting
 *   reduced indexes does reach here, but with the GLOBAL index's accelerator and tree: the accelerator is hosted once
 *   per collection and the caller composes its answer with each target index's own primary keys;
 * - the pattern is not selective enough for the intersection to beat the scan, see below.
 *
 * # The A/B decision
 *
 * The scan visits every distinct value of the attribute and applies the predicate to each. This path visits only the
 * candidates and applies the SAME predicate to each, then pays one tree descent per value that actually matched. Per
 * unit of work the two are therefore comparable, and the decision reduces to how much of the corpus the candidate set
 * covers - which {@link PatternPostings#candidateUpperBound} bounds from above, off the postings the intersection
 * would go on to use anyway.
 *
 * "The corpus" is whatever scan the one intersection displaces, and that is the CALLER's to state - see the
 * `scannedDistinctValueCounter` overload of {@link #match}.
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
	 * How much the index must narrow the field before the trigram path is worth taking: the candidate set it nominates
	 * must cover at most `1 / this` of the attribute's distinct values. A planner gate on whether the accelerator
	 * earns the work it adds - it sizes no structure and bounds no allocation.
	 *
	 * Measured end to end against the scan it displaces, on a real embedded instance, one corpus per run
	 * (`SubstringQueryBenchmark`, seven planted posting widths from 1% to 25%, `-f 3`). Speedup is monotone in width
	 * and changes sign between 8% and 12%: at 100,000 distinct values the 8% pattern still wins 1.15x while the 12%
	 * pattern LOSES 1.34x, and interpolating between them puts the crossover at **9.5% of distinct values**, i.e. a
	 * required narrowing of 10.5x. The whole curve is one invariant - trigram visits `share * n` candidates where the
	 * scan visits `n` and both run the same predicate, so `share * speedup` is the break-even share, and all seven
	 * classes agree on it within 15% across a 25-fold range of width.
	 *
	 * `12` is therefore the measured 10.5 plus a deliberate margin, and the margin is bought by three things the
	 * benchmark could not measure, all pushing the true crossover LOWER:
	 *
	 * - its corpus produces no false candidates at all - every candidate the intersection nominated survived the
	 *   predicate. Real text does not do that, and every rejected candidate is verification charged solely to this
	 *   path. At 30% false candidates the 100,000-value crossover moves from 9.5% to roughly 7%;
	 * - it is all-ASCII, and verification runs about twice as slow per candidate on non-ASCII values;
	 * - its trigram dictionary saturates, giving tighter candidate sets than real text would.
	 *
	 * The crossover also falls as the catalog grows - 12.75% at 10,000 distinct values against 9.5% at 100,000 - so a
	 * single scalar must be chosen from the large end to stay correct at scale.
	 *
	 * What `12` gives up against `10.5` is patterns covering 8.33-10% of the values, worth 1.28-1.53x at 10,000 values
	 * and roughly break-even at 100,000. That asymmetry is the point: a forfeited 1.4x is invisible, while an
	 * introduced 1.4x regression is a bug report against a query that used to be fine.
	 *
	 * **This constant was previously `4`, which admitted a band where the accelerator was 1.1-2.1x SLOWER than the
	 * scan.** The reasoning that chose it had derived the right band - "between a third and a tenth" - and then took
	 * the wrong end of it, calling `4` conservative when a LARGER factor is the strict one. Nothing in the code could
	 * surface that, because a too-eager gate returns slower correct answers rather than failures.
	 *
	 * This is a deliberately crude stand-in for a cost model. The query planner's own costing of the substring path is
	 * the increment after this one; when it lands, this constant is what it replaces.
	 */
	public static final int REQUIRED_NARROWING_FACTOR = 12;

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
		// the tree's own bucket count is a field read, so there is nothing for the threshold to cut short
		return match(
			trigramIndex, sharedValueTree, rawPattern, exactPredicate,
			threshold -> sharedValueTree.getBucketCount()
		);
	}

	/**
	 * The {@link #match(TrigramIndex, InvertedIndex, String, BiPredicate)} above, with the size of the scan this path
	 * is being weighed against supplied by the caller instead of read off `sharedValueTree`.
	 *
	 * The two coincide only when the answer is consumed by the very index the tree belongs to. A plan whose targets are
	 * reduced indexes hoists ONE computation over the global tree and amortizes it across the whole fan-out, so what it
	 * displaces is the sum of the target set's own scans - each over its own, far smaller tree - and pricing that plan
	 * against the global tree's bucket count would take the accelerated path precisely where the scan is cheapest.
	 * The caller therefore owns the comparison; see
	 * {@link io.evitadb.core.query.filter.translator.attribute.AbstractAttributeStringSearchTranslator}.
	 *
	 * ## Why the count arrives as a function of a threshold
	 *
	 * A caller summing a fan-out is answering a threshold question, not producing a total, and the fan-out can run to
	 * hundreds of thousands of indexes. The counter is therefore handed {@link #accelerationThreshold} and may stop as
	 * soon as its running total reaches it - anything at or above the threshold decides the comparison identically, so
	 * a truncated total and the true one are interchangeable HERE and nowhere else. The counter is also not invoked at
	 * all when the decision is already settled: an open transaction, a pattern with no trigram, or a pattern the index
	 * proves nothing contains all return before it is consulted.
	 *
	 * @param trigramIndex                the attribute's substring accelerator
	 * @param sharedValueTree             the value tree whose value ids `trigramIndex` posts against - it MUST be the
	 *                                    very tree the index was built from, or the ids name different values
	 * @param rawPattern                  the search term as the query supplied it, unnormalized
	 * @param exactPredicate              the exact test the scan path applies, given
	 *                                    `(normalizedValue, normalizedPattern)`
	 * @param scannedDistinctValueCounter given the threshold the total is compared against, how many distinct values
	 *                                    the scan this path replaces would visit - summed over every index the one
	 *                                    computation is amortized across, and free to stop counting at the threshold
	 * @return the matched buckets, empty when nothing matches, or `null` when the caller must take the scan instead
	 */
	@Nullable
	public static MatchedBuckets match(
		@Nonnull TrigramIndex trigramIndex,
		@Nonnull InvertedIndex sharedValueTree,
		@Nonnull String rawPattern,
		@Nonnull BiPredicate<String, String> exactPredicate,
		@Nonnull LongUnaryOperator scannedDistinctValueCounter
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
		// ONE read of the pattern's postings serves both the pricing below and the intersection further down - the
		// carrier the gate decides on IS the one the intersection consumes
		final PatternPostings patternPostings = trigramIndex.pricePattern(trigrams);
		if (patternPostings == null) {
			// a trigram of the pattern posts against no value at all, so no value contains the pattern. This bypasses
			// the selectivity gate on purpose: the answer is already known and cost nothing but the postings read up to
			// the empty trigram, which is the cheapest outcome either path can produce
			return NO_MATCH;
		}
		final long threshold = accelerationThreshold(patternPostings.candidateUpperBound());
		if (scannedDistinctValueCounter.applyAsLong(threshold) < threshold) {
			return null;
		}
		final int[] candidates = trigramIndex.resolveCandidateValueIds(patternPostings);
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
	 * `distinctValueCount` is the size of the scan the ONE intersection displaces, which is not always one tree's
	 * bucket count: when a single computation over the global tree is amortized across a fan-out of reduced indexes,
	 * it is the sum over that fan-out. Below {@link #MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT} the displaced scan is at
	 * most one contiguous leaf block and unbeatable, whichever way the total was arrived at.
	 *
	 * @param candidateUpperBound the most candidates the intersection could produce, i.e. the cheapest posting's
	 *                            cardinality
	 * @param distinctValueCount  how many distinct values the scan would have to visit
	 * @return whether the trigram path should be taken
	 */
	public static boolean isWorthAccelerating(int candidateUpperBound, int distinctValueCount) {
		return distinctValueCount >= accelerationThreshold(candidateUpperBound);
	}

	/**
	 * The number of distinct values the displaced scan must reach for {@link #isWorthAccelerating} to say yes - the
	 * floor and the selectivity ratio folded into ONE target, so that a caller summing a fan-out can stop the moment
	 * its running total reaches it.
	 *
	 * The fold is exact rather than approximate. For non-negative integers and a positive factor,
	 * `candidateUpperBound <= distinctValueCount / REQUIRED_NARROWING_FACTOR` (floor division) holds exactly when
	 * `candidateUpperBound * REQUIRED_NARROWING_FACTOR <= distinctValueCount`, so requiring the total to reach the
	 * larger of that product and {@link #MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT} is the same predicate written as a
	 * single comparison. The product is computed in `long` because a pathological cardinality could overflow `int`
	 * where the original division could not.
	 *
	 * @param candidateUpperBound the most candidates the intersection could produce
	 * @return the smallest displaced-scan size at which the trigram path is taken
	 */
	public static long accelerationThreshold(int candidateUpperBound) {
		return Math.max(
			MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT,
			(long) candidateUpperBound * REQUIRED_NARROWING_FACTOR
		);
	}

	/**
	 * Reads a candidate's stored value as the `String` the exact predicate needs.
	 *
	 * A value of any other type means the SUBSTRING accelerator was maintained for an attribute the schema accepts it
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
				"` - the SUBSTRING filter accelerator is accepted by the schema on String and String[] attributes " +
				"only, so this attribute should never have been given a trigram index."
		);
		return (String) normalizedValue;
	}

}
