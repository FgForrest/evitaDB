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
import io.evitadb.index.bPlusTree.Wtf8;
import io.evitadb.index.invertedIndex.InvertedIndex.MatchedBuckets;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
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
	 * Padding placed on BOTH sides of the pattern to build a value that contains it but neither starts nor ends with
	 * it - the witness that separates a containment predicate from an anchored one when
	 * {@link #verificationIsRedundant} is about to act on the caller's declared {@link StringSearchShape}.
	 *
	 * `NUL` is used because it cannot appear in a normalized attribute value and therefore cannot make the witness
	 * accidentally resemble real data; flanking on both sides is what makes the test refuse `startsWith` and
	 * `endsWith` alike, whatever the pattern itself contains.
	 */
	private static final String FLANK = "\u0000";

	/**
	 * How much the index must narrow the field before the trigram path is worth taking: the candidate set it nominates
	 * must cover at most `1 / this` of the attribute's distinct values. A planner gate on whether the accelerator
	 * earns the work it adds - it sizes no structure and bounds no allocation.
	 *
	 * ## Why it is still `12`, and what is known against it
	 *
	 * `12` was measured against a crossover of 9.5% of distinct values, on code whose per-candidate cost has since
	 * fallen roughly six-fold. There is strong evidence that the crossover moved a long way out from under it, and
	 * therefore that `12` now refuses work worth taking - but not yet evidence good enough to move a planner gate on.
	 *
	 * **What is established.** On a production retail catalog (three identifier-like ASCII attributes, 86,455-118,772
	 * distinct values, 171 `attributeContains` patterns, both arms forced and compared bitmap-by-bitmap), the scan
	 * beats the accelerated path on NONE of them, and the widest pattern the corpus could produce - covering 34.23% of
	 * distinct values - still wins over 2x. Every one of the 14 patterns `12` declines would have been faster
	 * accelerated. That is a real cost, and it is why this constant should not be assumed correct merely because it is
	 * current.
	 *
	 * **Why that is not sufficient.** It measures one shape of workload: ASCII, identifier-like, `contains`, around
	 * 100,000 distinct values, single-index targets. Four exposures are unmeasured, and each is a way `4` could be
	 * worse than `12` rather than better:
	 *
	 * - **Fan-out breaks the "share of the corpus" reading entirely.** The gate compares the GLOBAL candidate bound
	 *   against `sumDistinctValuesUpTo`, which sums each target index's own bucket count - and those counts overlap,
	 *   because a value in twenty reduced indexes is counted twenty times. A fan-out whose sum reaches `k` times the
	 *   global distinct count admits a posting covering `k / factor` of the whole attribute, so the smaller the factor
	 *   the wider that hole. Whatever this constant becomes, this is the part that needs a second input rather than a
	 *   smaller scalar.
	 * - **A million distinct values.** The crossover falls as the corpus grows, and an earlier run at that size put it
	 *   at 5.6% on the pre-optimization code. Nothing has re-measured it since.
	 * - **The scalar cannot see pattern cost.** Admission reads only the cheapest posting's cardinality, so a long
	 *   boilerplate phrase whose many common trigrams intersect wide presents exactly the same gate input as a short
	 *   selective one while doing far more work.
	 * - **Localized and non-ASCII attributes**, where verification runs about twice as slow per candidate.
	 *
	 * ## What would settle it
	 *
	 * A retune needs a genuine sign change bracketed with confidence intervals - a width that WINS and a wider one
	 * that LOSES - at `n >= 1_000_000`, on a corpus that does not change between the arms, plus a fan-out case and a
	 * long multi-trigram pattern. Forcing the gate by lowering this constant is NOT a valid way to obtain it:
	 * `SubstringPatternClass.THRESHOLD` plants into `n / REQUIRED_NARROWING_FACTOR` values and every class plants into
	 * the one shared corpus, so lowering the constant lengthens every value and changes the very cost being measured.
	 * Force the arm instead, as `TrigramArmSweep` does, by supplying a counter that answers `Long.MAX_VALUE`.
	 *
	 * ## History, because this constant has been wrong in both directions
	 *
	 * It shipped as `4`, which admitted a band running 1.1-2.1x SLOWER than the scan, and was corrected to `12`. The
	 * reasoning that chose `4` had derived the right band and then taken the wrong end of it, calling `4`
	 * conservative when a LARGER factor is the strict one. Nothing in the code could surface that, because a too-eager
	 * gate returns slower correct answers rather than failures - which is exactly why the bar for moving it is a
	 * measured sign change and not a plausible argument.
	 *
	 * The asymmetry that argued for `12` still holds: a forfeited win is invisible, an introduced regression is a bug
	 * report against a query that used to be fine.
	 *
	 * This is a deliberately crude stand-in for a cost model. The query planner's own costing of the substring path is
	 * what replaces it, and would answer the fan-out case that no scalar can.
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
	 *                                    computation is amortized across, and free to stop counting at the threshold.
	 *                                    It MUST be a pure count: it is invoked between the read of the pattern's
	 *                                    postings and the intersection over them, so a counter that wrote to this
	 *                                    index would have its write silently excluded from the answer - the postings
	 *                                    already in hand are the ones intersected. Counting is all any caller needs,
	 *                                    and the alternative (re-reading the postings afterwards) would reinstate the
	 *                                    doubled lookup this seam exists to remove
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
		// ANCHORED is the conservative reading of an unstated shape: it verifies every candidate, which is what this
		// overload did before the shape existed, so no caller's behaviour changes by omitting it
		return match(
			trigramIndex, sharedValueTree, rawPattern, exactPredicate, scannedDistinctValueCounter,
			StringSearchShape.ANCHORED
		);
	}

	/**
	 * The {@link #match(TrigramIndex, InvertedIndex, String, BiPredicate, LongUnaryOperator)} above, told what the
	 * exact predicate needs from an occurrence of the pattern.
	 *
	 * The accelerator finds the values that contain the pattern *somewhere*; each string-search constraint then
	 * narrows that with its own predicate. Stating the shape lets the one case where that narrowing is provably empty
	 * skip it - see {@link #verificationIsRedundant}, which is where the reasoning lives.
	 *
	 * @param trigramIndex                the attribute's substring accelerator
	 * @param sharedValueTree             the value tree whose value ids `trigramIndex` posts against
	 * @param rawPattern                  the search term as the query supplied it, unnormalized
	 * @param exactPredicate              the exact test the scan path applies, given
	 *                                    `(normalizedValue, normalizedPattern)`
	 * @param scannedDistinctValueCounter how many distinct values the displaced scan would visit; see the overload
	 *                                    above for the purity this must observe
	 * @param shape                       what `exactPredicate` needs from an occurrence - it MUST describe that very
	 *                                    predicate, since a mismatch would skip a verification the answer depends on
	 * @return the matched buckets, empty when nothing matches, or `null` when the caller must take the scan instead
	 */
	@Nullable
	public static MatchedBuckets match(
		@Nonnull TrigramIndex trigramIndex,
		@Nonnull InvertedIndex sharedValueTree,
		@Nonnull String rawPattern,
		@Nonnull BiPredicate<String, String> exactPredicate,
		@Nonnull LongUnaryOperator scannedDistinctValueCounter,
		@Nonnull StringSearchShape shape
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
			// THE ESCALATING GATE, deliberately not implemented - measured worthless here, kept because the shape it
			// needs is one another corpus could easily have, and rediscovering it costs more than reading this.
			//
			// The bound above is the CHEAPEST posting's cardinality, which is pessimistic: a value must contain every
			// trigram, so the true candidate count can be far smaller. Rather than declining outright, this is where
			// a second, tighter estimate would be bought - intersect only the two cheapest postings and count the
			// result, then re-ask the gate with that. Safe by subset algebra: the true candidate set is contained in
			// that pairwise intersection, so the estimate can never be an UNDER-count and the gate can never be
			// tricked into accelerating something it should have refused. A zero would even answer NO_MATCH outright.
			// The cost is bounded and paid only here, on a path already committed to a full scan.
			//
			// Measured on a production retail catalog (three attributes, 171 patterns): it would have flipped ZERO of
			// the 14 declined patterns. Ten of them carry a single trigram, so there is no second posting to
			// intersect and the bound is already exact; the other four are 4-13% loose and still miss the threshold
			// by an order of magnitude. The reason is structural rather than accidental - a loose bound needs several
			// trigrams whose postings overlap poorly, but such a pattern is selective and has therefore ALREADY
			// passed the gate. Patterns that decline here decline because they are genuinely wide.
			//
			// Worth building when a workload appears whose DECLINED patterns are multi-trigram - each trigram common,
			// the combination rare. Until then it would be hot-path code paying for a population that does not exist.
			// Note this is not the fix for a gate that declines winnable patterns: on that same corpus all 14 would
			// have run 1.4x-4.8x faster accelerated, which is REQUIRED_NARROWING_FACTOR being mistuned, not the
			// bound being loose.
			return null;
		}
		final int[] candidates = trigramIndex.resolveCandidateValueIds(patternPostings);
		final boolean skipVerification = verificationIsRedundant(normalizedPattern, shape);
		// containment is the one shape the bucket tree can settle from a candidate's stored bytes, and a pattern that
		// does not survive UTF-8 encoding unchanged must not be offered for it - see `encodesWithoutLoss`
		final byte[] containsPatternUtf8 = !skipVerification
			&& shape == StringSearchShape.CONTAINMENT
			&& encodesWithoutLoss(normalizedPattern)
			? normalizedPattern.getBytes(StandardCharsets.UTF_8) : null;
		if (skipVerification || containsPatternUtf8 != null) {
			// `shape` is the caller's word about a predicate this class cannot introspect, and taking that word on
			// trust is the one way this optimization returns wrong answers. So the word is WITNESSED before it is
			// acted on: an occurrence flanked on both sides is the one value an anchored predicate must refuse and a
			// containment one must accept, so a refusal here proves the word wrong.
			//
			// It is a necessary condition, NOT a proof of containment. A predicate that tests containment AND
			// something else - a length bound, a locale rule - passes this witness and is still mis-served by
			// skipping verification. That is not a gap this check could close by testing harder: no finite number of
			// witnesses characterises an arbitrary `BiPredicate`. It is closed by `StringSearchShape` having no
			// member such a predicate could be declared under, so a caller reaching for one is already outside the
			// contract, whereas passing ANCHORED's predicate under CONTAINMENT is the plausible slip - and that is
			// exactly the slip this catches. One test per query, not per candidate, against a verification pass this
			// skips entirely, so the guard costs a rounding error of what it guards
			Assert.isPremiseValid(
				exactPredicate.test(FLANK + normalizedPattern + FLANK, normalizedPattern),
				"The exact predicate refused a value that merely CONTAINS the pattern, so it is not the containment " +
					"predicate `" + StringSearchShape.CONTAINMENT + "` declares it to be - neither skipping " +
					"verification nor answering it from the stored bytes is sound for it. Pass `" +
					StringSearchShape.ANCHORED + "` for any predicate that requires the pattern to sit at a " +
					"particular end of the value."
			);
		}
		return sharedValueTree.getRecordsOfValueIdsMatching(
			candidates, candidates.length,
			skipVerification ?
				null : normalizedValue -> exactPredicate.test(asString(normalizedValue), normalizedPattern),
			containsPatternUtf8
		);
	}

	/**
	 * Answers whether `pattern` survives a round trip through UTF-8 unchanged.
	 *
	 * A Java `String` is a sequence of UTF-16 code units, so a lone surrogate is a perfectly legal one - and
	 * {@link String#getBytes(java.nio.charset.Charset)} has no encoding for it and substitutes `0x3F` (`'?'`). A
	 * pattern carrying one would therefore be matched as though the user had typed a question mark, finding values
	 * that {@link String#contains} refuses. Such a pattern takes the predicate path, which compares UTF-16 code units
	 * and is unaffected.
	 *
	 * Checked on the pattern only. A stored VALUE needs no check, because the column stores one faithfully as WTF-8
	 * (see {@code Wtf8}) - a `'?'` in the pattern's bytes therefore does not match it, which is exactly the answer
	 * {@link String#contains} gives.
	 *
	 * **This guard is live, and became live when the column stopped losing unpaired surrogates.** It used to be
	 * unreachable by coincidence: a pattern's trigrams are cut from its code points, so a pattern carrying a lone
	 * surrogate produces trigrams carrying it too, and those can only intersect the postings of a value carrying one
	 * as well - which an attribute declaring this accelerator could not index at all, because the value-id sink's own
	 * premise failed first. That premise no longer fails, so such a value indexes normally and the branch below is
	 * now the only thing standing between a surrogate-bearing pattern and a `'?'`-matching byte comparison.
	 *
	 * @param pattern the normalized pattern
	 * @return whether every code unit of the pattern is representable in UTF-8
	 */
	private static boolean encodesWithoutLoss(@Nonnull String pattern) {
		// deliberately delegated rather than re-implemented: the column answers the same question about the VALUES it
		// stores, and two independent walks of the same subtle surrogate-pairing rule would be free to drift apart
		return !Wtf8.hasUnpairedSurrogate(pattern);
	}

	/**
	 * Answers whether the exact predicate would accept EVERY candidate the intersection just produced, and can
	 * therefore be skipped rather than run.
	 *
	 * ## Why one trigram's worth of pattern is self-verifying
	 *
	 * A pattern of exactly {@link TrigramCodec#MINIMAL_INDEXABLE_LENGTH} code points yields exactly one trigram, and
	 * that trigram IS the whole pattern. {@link TrigramCodec#pack} is injective - three bounds-checked 21-bit code
	 * point fields in a `long`, no hashing - so the posting under that key is precisely the set of values holding
	 * those three code points contiguously, which is precisely the set of values containing the pattern. The
	 * intersection over a single posting is that posting, so the candidate set already IS the answer and containment
	 * has nothing left to remove. Measured on a production catalog: across 98 such patterns the false-candidate rate
	 * was exactly zero, while patterns one code point longer reached 0.97.
	 *
	 * ## The two conditions, and why neither may be relaxed
	 *
	 * **The pattern is measured in CODE POINTS, never in trigrams.** "The pattern produced one trigram" is the
	 * tempting phrasing and it is wrong: {@link TrigramCodec#extractUniqueTrigrams} deduplicates, so `aaaa` is four
	 * code points that collapse to the single trigram `aaa`. Its posting holds every value containing `aaa`,
	 * including values that do not contain `aaa a`- so skipping verification there would return false matches.
	 *
	 * **The shape must be {@link StringSearchShape#CONTAINMENT}.** An anchored predicate is not satisfied by mere
	 * occurrence, so its candidates must be verified however narrow the pattern is.
	 *
	 * Two deliberate side effects, both on paths that only a corrupt index reaches. The value is not read at all here,
	 * so the type check inside {@link #asString} no longer runs - a backstop for an accelerator maintained over a
	 * non-`String` attribute, which {@link TrigramCodec#extractUniqueTrigramsOfValue} already refuses on the write path
	 * where the damage would originate. Nor is the key decoded, so the front-coded column's own corrupt-blob premise
	 * no longer fires incidentally for these queries: a slot whose key bytes are damaged but whose value id and record
	 * column are intact now answers instead of throwing. Neither check was ever this path's to make, and relying on a
	 * correctness probe for storage validation is what made the loss invisible until it was looked for.
	 *
	 * @param normalizedPattern the pattern as the tree's own normalizer produced it - the form the index was built in
	 * @param shape             what the caller's exact predicate needs from an occurrence
	 * @return whether every candidate is provably a match
	 */
	private static boolean verificationIsRedundant(
		@Nonnull String normalizedPattern,
		@Nonnull StringSearchShape shape
	) {
		return shape == StringSearchShape.CONTAINMENT
			&& normalizedPattern.codePointCount(0, normalizedPattern.length())
			== TrigramCodec.MINIMAL_INDEXABLE_LENGTH;
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
