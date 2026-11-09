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

package io.evitadb.index.fulltext;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PeekableIntRankIterator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Arrays;

/**
 * Phase 1 of fulltext ranking: scores every candidate entity against the query's tokens in one pass over the
 * posting lists, into a vector aligned with the candidates. Selecting a page from such a vector is the separate
 * {@link #selectTopN(int[], long[], int, long)}, run once every score source of the query has been combined.
 *
 * ## What is scored
 *
 * A query is a sequence of **tokens**; each token expands into one or more **terms** of the dictionary — its stem
 * variants, its prefix completions, its typo neighbours — and each expansion carries its posting list, the impact
 * bytes aligned with it, its edit distance and matching class. For every candidate the walk keeps four lanes:
 *
 * | lane | meaning | best is |
 * |---|---|---|
 * | matched words | matched query words, or covered parts of a split word | more |
 * | TYPO | `255 - sum(best distance per matched word)`, saturated at zero | larger |
 * | EXACTNESS | `255 - count(matched words whose best class is not EXACT)` | larger |
 * | strength | best place word-sum + t × the other place word-sums | larger |
 *
 * EXACTNESS counts non-exact query words (split groups count once), independently of their distances. At equal
 * total typo cost this orders exactly like summed class ranks: EXACT has rank 0, PREFIX rank 1, FUZZY(d) rank
 * `d + 1`, so the class sum equals the distance sum plus the non-exact count. Counting the non-exact words keeps
 * exact-before-prefix at equal TYPO without carrying the class sum itself, which could outgrow a byte. The count
 * cannot overflow: at most 255 query words contribute. Only TYPO saturates at zero; EXACTNESS reaches zero at exactly
 * 255 non-exact words and is independent of the accepted maximum distance. Each word takes its smallest distance
 * and whether any exact expansion hit across every profile group, expansion leg and dictionary.
 *
 * The unsigned 64-bit composite orders matched words at bit 56, TYPO at bit 48, EXACTNESS at bit 40 and 16-bit strength
 * at bit 24. TYPO sums the raw best distances, which is the typo-tolerance specification's default typo-lane
 * weighting `w = (0, 1, 2)`; the specification leaves the weights open until judged queries can tune them, and no
 * other weighting exists. The low 24 bits reserve context and stay zero. See {@link FulltextScoringPolicy} for
 * scale and saturation. Compare with `Long.compareUnsigned`: 128 or more matched words set the sign bit.
 *
 * ## The counting rule
 *
 * A query word counts once however many expansions hit. A split word instead contributes its number of covered
 * parts, with an original hit covering every part; its quality penalties still contribute only once. The optional
 * part ends ({@link #validatePartEnds}) lay a split word's expansions out as the original's, then each part's,
 * across all profile groups. Only a query with split words allocates a last-part marker per candidate: consecutive
 * expansions of one part count once, and an original hit covers every part and suppresses the later part hits. A
 * split word raises the matched-word lane during the merge, at the first hit of each part (`PartCoverage`); an
 * ordinary word raises it by one when its lanes are folded, and allocates no coverage state. The matched-word count
 * saturates at 255. Each word's expansions merge into reusable primitive per-word lanes; no hit allocates or boxes
 * an object. For strength, each place sums each query word's best weighted impact, saturating at 65535 fixed-point
 * units. Repeated expansions and a split word's original and parts contribute one maximum per word and place.
 *
 * ## The merge, and where the impact offset comes from
 *
 * Each expansion's postings are merged against the sorted candidate array by whichever of three strategies is
 * cheapest for the two lengths:
 *
 * | strategy | walks | skips | costs about | wins when |
 * |---|---|---|---|---|
 * | linear | both, step by step | nothing | `candidates + postings` | the lengths are comparable |
 * | galloping | every posting | candidates | `postings × log2(candidates / postings)` | postings ≪ candidates |
 * | skipping | every candidate | postings | `candidates × log2(postings / candidates)` | candidates ≪ postings |
 *
 * The linear and the galloping merge read a posting list **in order**: a roaring list is decoded whole, once, by its
 * own bulk extraction into a decode buffer the thread reuses, and the small tiers of a bucket are read through one
 * small reusable buffer, so the position of a posting in its list - the offset of its impact byte - is simply
 * counted. The skipping merge reads the list where it is stored: it jumps over postings, so it takes the position
 * from the rank the posting list's own iterator keeps while it skips. Either way positions only grow, so the impact
 * reader only ever moves forward, crossing each chunk boundary of a chunked bucket at most once.
 *
 * The galloping merge is what lets a short posting list against a large candidate set stay within the phase-1
 * budget: it lifted the cells meeting the budget from 25 to 32 of 36 in the index-core measurements of the fulltext
 * decision record, up to 45× faster on the widest. The skipping merge is its mirror for a common word against a
 * candidate set that the other query words, or the other constraints of the query, have already narrowed: without
 * it, a merge would walk every posting of the word to score a few hundred candidates.
 *
 * The accumulators are allocated per call, beside the candidate array. The only state kept between calls is each
 * thread's posting feed - its decode buffer, which the next call on the same thread decodes into again - so the
 * scorer is safe to use from any number of threads, as long as a scoring call is never re-entered on one thread (see
 * {@link #POSTING_FEED}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public final class FulltextPhaseOneScorer {

	/**
	 * Most query words a single query may carry: the EXACTNESS lane counts non-exact words in one byte, and this bound
	 * keeps that count from wrapping. The matched-word lane saturates instead, because a split word adds one count per
	 * covered part.
	 */
	public static final int MAX_QUERY_TOKENS = 255;

	/**
	 * Largest edit distance an expansion may carry: the typo lane stores `distance + 1`, so `0` can mean "not hit".
	 */
	public static final int MAX_DISTANCE = 254;

	/**
	 * How many postings of a small tier - a single record, a sorted array of a few records - the posting feed reads
	 * into its reusable buffer at once. Small enough for the buffer to stay in the L1 cache; it holds a whole
	 * array-tier bucket.
	 */
	private static final int POSTING_BATCH_SIZE = 256;
	/**
	 * Cost of one step of a galloping search over the candidates, in steps of the linear merge. A galloping step is a
	 * dependent load and a hard-to-predict branch, where a linear step's branches follow the data, so it is priced at
	 * two: galloping then pays off from about eight candidates per posting.
	 */
	private static final long GALLOP_STEP_COST = 2L;
	/**
	 * Cost of one step of the skipping merge, in steps of the linear merge. Skipping goes through the posting list's
	 * iterator, which dispatches on the container kind and, inside a bitmap container, counts the bits of every word
	 * it passes for the rank, so it is priced at four: skipping then pays off from about twenty-five postings per
	 * candidate.
	 */
	private static final long SKIP_STEP_COST = 4L;
	/**
	 * The posting feed of the scoring calls on this thread. Its decode buffer outlives the call, so a roaring list is
	 * decoded into memory the thread has decoded into before rather than into a fresh array per expansion: on ranked
	 * queries of a production CMS catalog that removed 18-50 % of a query's allocation at no measurable cost in time.
	 *
	 * The buffer grows to the longest list the thread has decoded and stays that long - 4 bytes per posting, so at
	 * most 4 bytes per entity of the largest collection, held once per thread that has scored. Requests served
	 * through the server's APIs run on its request executor, a bounded pool of platform threads, which bounds the
	 * total; an embedded application queries on its own threads, so there the bound is however many threads it
	 * queries from. Under a thread per request the buffer would simply be allocated per request again.
	 *
	 * One feed per thread is safe only because a scoring call is never re-entered on its thread: the expansions are
	 * resolved before the call and the merges call nothing that could query again. A change that lets the scorer
	 * call back into a query must give the inner call a feed of its own.
	 */
	private static final ThreadLocal<PostingFeed> POSTING_FEED = ThreadLocal.withInitial(PostingFeed::new);

	/**
	 * Selects how each expansion is merged against the candidates.
	 */
	enum MergeStrategy {

		/**
		 * Take the cheapest of the three merges per expansion — the production setting.
		 */
		ADAPTIVE,
		/**
		 * Always merge linearly. For tests asserting the strategies agree.
		 */
		LINEAR,
		/**
		 * Always walk the postings and gallop the candidates. For tests asserting the strategies agree.
		 */
		GALLOPING,
		/**
		 * Always walk the candidates and skip through the postings. For tests asserting the strategies agree.
		 */
		SKIPPING

	}

	/** Prevents instantiation of this stateless scoring utility. */
	private FulltextPhaseOneScorer() {
		// utility class
	}

	/**
	 * One term a query token expanded into, with its posting list and the impact bytes aligned to it.
	 *
	 * Both come straight from the dictionary cursor
	 * ({@link io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor#records()} and
	 * {@link io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor#impacts()}). The impacts are read in
	 * place; the postings are read in place too, except that a linear or galloping merge first decodes a roaring
	 * posting list into a buffer its scoring thread reuses. Neither may be written while the expansion is in use. The
	 * impacts follow the posting list's own record order, which is unsigned; entity primary keys are never negative, so
	 * it is their ascending order too.
	 *
	 * @param postings primary keys of the entities containing the term, ascending
	 * @param impacts  impact byte of each posting, the i-th belonging to the i-th posting
	 * @param distance edit distance at which the term matched the token, `0` for exact and prefix matches
	 * @param exactness matching class, EXACT when this word contributes no EXACTNESS penalty
	 * @param placeId query-local place identity, shared by all items of the same place
	 * @param weight effective rank-time weight of the place
	 */
	public record Expansion(
		@Nonnull Bitmap postings, @Nonnull ImpactView impacts, int distance, @Nonnull FulltextTermExactness exactness,
		int placeId, float weight
	) {

		/**
		 * Validates the expansion.
		 * @param postings primary keys containing the term
		 * @param impacts impacts aligned with the postings
		 * @param distance edit distance
		 * @param exactness matching class independent of distance
		 * @param placeId query-local place identity
		 * @param weight finite nonnegative effective place weight
		 */
		public Expansion {
			Assert.isPremiseValid(Float.isFinite(weight) && weight >= 0, "Invalid fulltext place weight!");
			Assert.isPremiseValid(
				postings.size() == impacts.size(),
				() -> "An expansion carries " + postings.size() + " postings but " + impacts.size() + " impacts!"
			);
			Assert.isPremiseValid(
				distance >= 0 && distance <= MAX_DISTANCE,
				() -> "Edit distance " + distance + " is outside 0.." + MAX_DISTANCE + "!"
			);
		}

	}

	/**
	 * The result of one scoring pass: a complete candidate-aligned vector, or a selected page in descending rank order.
	 *
	 * @param primaryKeys      candidate keys for scoreAll, or selected keys best first for selectTopN
	 * @param composites       the composite of each, parallel to `primaryKeys`; compare them unsigned (see the
	 *                         class documentation)
	 * @param matchedDocuments how many candidates matched at least one token
	 * @param postingsWalked   the total length of every expansion's posting list - the cost model's dominant term,
	 *                         counted whichever merge ran, and whether or not it stopped early because the
	 *                         candidates ran out
	 */
	public record Result(
		@Nonnull int[] primaryKeys,
		@Nonnull long[] composites,
		int matchedDocuments,
		long postingsWalked
	) {
	}

	/**
	 * Validates shared split metadata using only expansion-array lengths, without reading posting contents.
	 * A null outer array denotes an unsplit query. Otherwise every word has a non-null row, empty for an ordinary
	 * word. Nonempty rows contain monotonic exclusive ends for the original followed by its parts; empty ranges
	 * are valid, and the final end must equal the word's expansion count. Cost is linear in words and range ends.
	 *
	 * @param expansions expansions grouped by mandatory query word
	 * @param partEnds optional exclusive range ends aligned with the words
	 * @param <T> expansion representation used by the formula or scorer; only array lengths are inspected
	 * @throws io.evitadb.exception.GenericEvitaInternalError when rows are null, misaligned or malformed
	 */
	public static <T> void validatePartEnds(@Nonnull T[][] expansions, @Nullable int[][] partEnds) {
		if (partEnds == null) {
			return;
		}
		Assert.isPremiseValid(partEnds.length == expansions.length, "Split coverage must align with query words!");
		for (int word = 0; word < expansions.length; word++) {
			final int[] ends = partEnds[word];
			Assert.isPremiseValid(ends != null, "Split coverage requires non-null rows; use an empty row for a word!");
			int previous = 0;
			for (final int end : ends) {
				Assert.isPremiseValid(end >= previous && end <= expansions[word].length,
					"Split-part expansion boundaries must be nonnegative, monotonic and within their word!");
				previous = end;
			}
			Assert.isPremiseValid(ends.length == 0 || previous == expansions[word].length,
				"Split-part expansion boundaries must cover their word!");
		}
	}

	/**
	 * Merges each word's postings, then folds its best quality and covered-part count into the candidate lanes.
	 * The contract is {@link #scoreAll(int[], Expansion[][], int[][], FulltextScoringPolicy)}'s; the strategy only
	 * decides how each posting list is merged, never the result.
	 *
	 * @param candidates sorted distinct candidate primary keys
	 * @param tokens expansions grouped by query word
	 * @param strategy posting merge strategy
	 * @param partEnds optional original/part expansion ends aligned with words; null when the query has no split word
	 * @param policy effective coefficient used to combine place strengths
	 * @return all candidates and their aligned composites
	 */
	@Nonnull
	static Result scoreAll(
		@Nonnull int[] candidates, @Nonnull Expansion[][] tokens, @Nonnull MergeStrategy strategy,
		@Nullable int[][] partEnds, @Nonnull FulltextScoringPolicy policy
	) {
		Assert.isPremiseValid(
			tokens.length <= MAX_QUERY_TOKENS,
			() -> "A query may carry at most " + MAX_QUERY_TOKENS + " tokens, " + tokens.length + " were passed!"
		);
		validatePartEnds(tokens, partEnds);
		final int candidateCount = candidates.length;
		final byte[] matchedTokens = new byte[candidateCount];
		final byte[] exactnessPenalty = new byte[candidateCount];
		final byte[] typoPenalty = new byte[candidateCount];
		// Word-major traversal preserves split coverage and reusable quality lanes. Only raw per-place maxima
		// are reset after each word; saturating weighted place sums survive until final composition.
		final int[] places = placeIds(tokens);
		final byte[][] placeImpacts = new byte[places.length][candidateCount];
		final char[][] placeSums = new char[places.length][candidateCount];
		final char[][] weightedImpacts = new char[places.length][];
		final int[] wordPlaces = new int[places.length];
		final int[] placeWordStamps = new int[places.length];
		// the per-word typo and exactness lanes: every expansion of one word writes here, and only the merged
		// result reaches the accumulators above - the cheapest implementation of the counting rule
		final byte[] tokenTypo = new byte[candidateCount];
		final byte[] tokenExactness = new byte[candidateCount];
		final PartCoverage coverage = partEnds == null ? null : new PartCoverage(candidateCount, matchedTokens);
		final PostingFeed feed = POSTING_FEED.get();
		long postingsWalked = 0L;

		try {
			for (int token = 0; token < tokens.length; token++) {
				final Expansion[] expansions = tokens[token];
				final int[] ends = partEnds == null ? null : partEnds[token];
				final int partCount = partEnds == null ? 0 : Math.max(0, ends.length - 1);
				final PartCoverage wordCoverage = partCount == 0 ? null : coverage;
				if (wordCoverage != null) {
					wordCoverage.partCount = partCount;
				}
				int part = 0;
				boolean anyHit = false;
				int wordPlaceCount = 0;
				for (int e = 0; e < expansions.length; e++) {
					final Expansion expansion = expansions[e];
					if (wordCoverage != null) {
						while (e >= ends[part]) {
							part++;
						}
						wordCoverage.part = part;
					}
					final int place = Arrays.binarySearch(places, expansion.placeId());
					final byte[] placeImpact = placeImpacts[place];
					if (weightedImpacts[place] == null) {
						weightedImpacts[place] = policy.getWeightedImpacts(expansion.weight());
					}
					final Bitmap postings = expansion.postings();
					final int postingCount = postings.size();
					if (postingCount == 0) {
						continue;
					}
					// stored one-based, so `0` in the typo lane means "this token did not hit the candidate"
					final byte distance = (byte) (expansion.distance() + 1);
					final ImpactView.Reader impacts = expansion.impacts().reader();
					final boolean expansionHit = switch (chooseMerge(strategy, candidateCount, postingCount)) {
						case LINEAR -> linearMerge(
							candidates, feed.reset(postings), impacts, distance, placeImpact, tokenTypo,
							expansion.exactness() == FulltextTermExactness.EXACT, tokenExactness, wordCoverage
						);
						case GALLOPING -> gallopingMerge(
							candidates, feed.reset(postings), impacts, distance, placeImpact, tokenTypo,
							expansion.exactness() == FulltextTermExactness.EXACT, tokenExactness, wordCoverage
						);
						case SKIPPING -> skippingMerge(candidates, postings, impacts, distance, placeImpact, tokenTypo,
							expansion.exactness() == FulltextTermExactness.EXACT, tokenExactness, wordCoverage);
						case ADAPTIVE -> throw new GenericEvitaInternalError("The merge choice never yields ADAPTIVE!");
					};
					anyHit |= expansionHit;
					if (expansionHit && placeWordStamps[place] != token + 1) {
						placeWordStamps[place] = token + 1;
						wordPlaces[wordPlaceCount++] = place;
					}
					postingsWalked += postingCount;
				}
				if (anyHit) {
					// Fold quality once per word; split words additionally contribute their covered-part count.
					for (int i = 0; i < candidateCount; i++) {
						final byte typo = tokenTypo[i];
						if (typo == 0) {
							continue;
						}
						if (wordCoverage == null) {
							matchedTokens[i] = (byte) Math.min(255, Byte.toUnsignedInt(matchedTokens[i]) + 1);
						}
						typoPenalty[i] = (byte) Math.min(255,
							Byte.toUnsignedInt(typoPenalty[i]) + Byte.toUnsignedInt(typo) - 1);
						if (tokenExactness[i] == 0) {
							exactnessPenalty[i]++;
						}
					}
					// Fold only places this word hit, once after the original and its parts supplied their raw maxima.
					for (int touched = 0; touched < wordPlaceCount; touched++) {
						final int place = wordPlaces[touched];
						final byte[] impacts = placeImpacts[place];
						final char[] sums = placeSums[place];
						final char[] table = weightedImpacts[place];
						for (int i = 0; i < candidateCount; i++) {
							sums[i] = (char) Math.min(FulltextScoringPolicy.MAX_STRENGTH,
								sums[i] + table[Byte.toUnsignedInt(impacts[i])]);
						}
						Arrays.fill(impacts, (byte) 0);
					}
					Arrays.fill(tokenTypo, (byte) 0);
					Arrays.fill(tokenExactness, (byte) 0);
					if (wordCoverage != null) {
						wordCoverage.reset();
					}
				}
			}
		} finally {
			// the feed stays with the thread, so it must not keep the last posting list alive until the next call
			feed.release();
		}

		// compose once, at the end, so a later ranking phase can enter between the accumulation and the composite
		final long[] composites = new long[candidateCount];
		int matchedDocuments = 0;
		for (int i = 0; i < candidateCount; i++) {
			if (matchedTokens[i] == 0) {
				continue;
			}
			matchedDocuments++;
			int best = 0;
			long sum = 0;
			for (int place = 0; place < places.length; place++) {
				final int strength = placeSums[place][i];
				best = Math.max(best, strength);
				sum += strength;
			}
			composites[i] = ((long) Byte.toUnsignedInt(matchedTokens[i]) << 56)
				| ((long) (255 - Byte.toUnsignedInt(typoPenalty[i])) << 48)
				| ((long) (255 - Byte.toUnsignedInt(exactnessPenalty[i])) << 40)
				| ((long) policy.combine(best, sum) << FulltextScoringPolicy.STRENGTH_SHIFT);
		}
		return new Result(candidates, composites, matchedDocuments, postingsWalked);
	}

	/**
	 * Scores every candidate without selection, returning composites aligned with the input primary keys.
	 * Candidates ascend in signed integer order; postings ascend in unsigned order. These agree because entity
	 * primary keys are never negative. Expansion arrays and their borrowed postings/impacts are only read.
	 *
	 * @param candidates sorted distinct primary keys
	 * @param tokens the query words, each as the expansions it produced across every profile group; a word without
	 *               expansions matches nothing but still counts toward the word limit
	 * @param partEnds `null` for a query without split words; otherwise one row per word - empty for an ordinary
	 *                 word, and the exclusive expansion ends `[original, part 1, ...]` for a split one (see
	 *                 {@link #validatePartEnds})
	 * @param policy the query's policy, supplying the weight tables and the tie breaker
	 * @return all candidates, including zero composites for candidates matching no word
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the query carries more than
	 *                                                        {@link #MAX_QUERY_TOKENS} words, or `partEnds` is
	 *                                                        malformed
	 */
	@Nonnull
	public static Result scoreAll(
		@Nonnull int[] candidates, @Nonnull Expansion[][] tokens, @Nullable int[][] partEnds,
		@Nonnull FulltextScoringPolicy policy
	) {
		return scoreAll(candidates, tokens, MergeStrategy.ADAPTIVE, partEnds, policy);
	}

	/**
	 * Collects distinct place identities without a candidate-by-place map or boxed keys.
	 * @param tokens expansions grouped by query word
	 * @return sorted distinct place ids
	 */
	@Nonnull
	private static int[] placeIds(@Nonnull Expansion[][] tokens) {
		int count = 0;
		for (final Expansion[] token : tokens) {
			count += token.length;
		}
		final int[] ids = new int[count];
		int index = 0;
		for (final Expansion[] token : tokens) {
			for (final Expansion expansion : token) {
				ids[index++] = expansion.placeId();
			}
		}
		Arrays.sort(ids);
		int distinct = 0;
		for (final int id : ids) {
			if (distinct == 0 || id != ids[distinct - 1]) {
				ids[distinct++] = id;
			}
		}
		return Arrays.copyOf(ids, distinct);
	}

	/**
	 * Selects the top N of a candidate-aligned score vector, comparing composites unsigned, best first, with ties
	 * broken by ascending primary key. The vector is one
	 * {@link #scoreAll(int[], Expansion[][], int[][], FulltextScoringPolicy)} produced, or one combined from several
	 * score sources of the query.
	 *
	 * @param candidates     primary keys of the candidates, distinct
	 * @param composites     the composite of each candidate, parallel to `candidates`; `0` for a candidate not scored
	 * @param topN           how many entities to return, at least one
	 * @param postingsWalked the postings the composites were computed from, reported in the result
	 * @return the selected entities in descending rank order
	 * @throws io.evitadb.exception.GenericEvitaInternalError when `topN` is not positive or the arrays differ in length
	 */
	@Nonnull
	public static Result selectTopN(
		@Nonnull int[] candidates,
		@Nonnull long[] composites,
		int topN,
		long postingsWalked
	) {
		Assert.isPremiseValid(topN > 0, () -> "The number of results must be positive, " + topN + " was requested!");
		Assert.isPremiseValid(
			candidates.length == composites.length,
			() -> candidates.length + " candidates carry " + composites.length + " composites!"
		);
		int matchedDocuments = 0;
		for (long composite : composites) {
			if (composite != 0L) {
				matchedDocuments++;
			}
		}
		return selectTopN(candidates, composites, Math.min(topN, matchedDocuments), matchedDocuments, postingsWalked);
	}

	/**
	 * Decides how one expansion is merged. Each merge is priced in steps of the linear merge: the linear merge takes
	 * one step per candidate and per posting, the galloping merge one search over the average gap between postings per
	 * posting, the skipping merge one skip over the average gap between candidates per candidate. A search or a skip
	 * over a gap of `g` takes `1 + ceil(log2(g))` probes, each priced at {@link #GALLOP_STEP_COST} or
	 * {@link #SKIP_STEP_COST} linear steps.
	 *
	 * Galloping is weighed only for fewer postings than candidates and skipping only for more; either runs only when
	 * priced strictly below the linear merge, which takes every tie and equal lengths. A strategy other than
	 * {@link MergeStrategy#ADAPTIVE} is returned as it is.
	 *
	 * @param strategy       the requested strategy
	 * @param candidateCount number of candidates
	 * @param postingCount   number of postings of the expansion, positive
	 * @return the merge to run, never {@link MergeStrategy#ADAPTIVE}
	 */
	@Nonnull
	static MergeStrategy chooseMerge(@Nonnull MergeStrategy strategy, int candidateCount, int postingCount) {
		if (strategy != MergeStrategy.ADAPTIVE) {
			return strategy;
		}
		final long linearCost = (long) candidateCount + postingCount;
		if (postingCount < candidateCount) {
			final long gallopCost = GALLOP_STEP_COST * postingCount * probesPerGap(candidateCount, postingCount);
			return gallopCost < linearCost ? MergeStrategy.GALLOPING : MergeStrategy.LINEAR;
		} else if (candidateCount < postingCount) {
			final long skipCost = SKIP_STEP_COST * candidateCount * probesPerGap(postingCount, candidateCount);
			return skipCost < linearCost ? MergeStrategy.SKIPPING : MergeStrategy.LINEAR;
		} else {
			return MergeStrategy.LINEAR;
		}
	}

	/**
	 * Returns how many probes an exponential search takes to cross the average gap between the elements of the shorter
	 * sequence spread over the longer one: `1 + ceil(log2(ceil(longer / shorter)))`. The gap is rounded up, so a gap
	 * just above one already costs two probes - rounding it down would price a search over a gap of 1.9 like a linear
	 * step and choose it where the linear merge is cheaper.
	 *
	 * @param longer  length of the longer sequence
	 * @param shorter length of the shorter sequence; an empty one is priced like one of a single element, which keeps
	 *                its merge free all the same, because the probes are multiplied by its length
	 * @return the probes per element of the shorter sequence, at least one
	 */
	private static long probesPerGap(int longer, int shorter) {
		final int divisor = Math.max(shorter, 1);
		final int gap = (int) (((long) longer + divisor - 1) / divisor);
		// 32 - numberOfLeadingZeros(gap - 1) is ceil(log2(gap)) for every gap of at least one
		return 1L + (32 - Integer.numberOfLeadingZeros(gap - 1));
	}

	/**
	 * Merges one expansion by walking the candidates and the postings with two cursors.
	 *
	 * @param candidates     the candidates, ascending
	 * @param postings       the expansion's postings, fed in batches
	 * @param impacts        reader of the impact bytes aligned to the postings
	 * @param distance       the expansion's one-based edit distance
	 * @param placeImpact    the persistent raw maximum of the expansion's place, by candidate
	 * @param tokenTypo      the per-token typo lane, by candidate
	 * @param exact          whether this expansion is EXACT
	 * @param tokenExactness whether any exact expansion of this word hit, by candidate
	 * @param coverage       the split-word coverage to forward each hit to, `null` for an ordinary word
	 * @return true when at least one candidate was hit
	 */
	private static boolean linearMerge(
		@Nonnull int[] candidates,
		@Nonnull PostingFeed postings,
		@Nonnull ImpactView.Reader impacts,
		byte distance,
		@Nonnull byte[] placeImpact,
		@Nonnull byte[] tokenTypo,
		boolean exact,
		@Nonnull byte[] tokenExactness,
		@Nullable PartCoverage coverage
	) {
		final int candidateCount = candidates.length;
		final int[] batch = postings.buffer();
		boolean hit = false;
		int candidateIndex = 0;
		// position of the batch's first posting in the posting list - the offset of its impact byte
		int batchStart = 0;
		while (candidateIndex < candidateCount) {
			final int batchLength = postings.next();
			if (batchLength == 0) {
				break;
			}
			int batchIndex = 0;
			while (batchIndex < batchLength && candidateIndex < candidateCount) {
				final int candidate = candidates[candidateIndex];
				final int posting = batch[batchIndex];
				if (candidate < posting) {
					candidateIndex++;
				} else if (candidate > posting) {
					batchIndex++;
				} else {
					recordHit(
						candidateIndex, impacts.impactAt(batchStart + batchIndex), distance, placeImpact, tokenTypo,
						exact, tokenExactness, coverage
					);
					hit = true;
					candidateIndex++;
					batchIndex++;
				}
			}
			batchStart += batchLength;
		}
		return hit;
	}

	/**
	 * Merges one expansion by visiting every posting in order and galloping the candidate cursor to it.
	 *
	 * @param candidates     the candidates, ascending
	 * @param postings       the expansion's postings, fed in batches
	 * @param impacts        reader of the impact bytes aligned to the postings
	 * @param distance       the expansion's one-based edit distance
	 * @param placeImpact    the persistent raw maximum of the expansion's place, by candidate
	 * @param tokenTypo      the per-token typo lane, by candidate
	 * @param exact          whether this expansion is EXACT
	 * @param tokenExactness whether any exact expansion of this word hit, by candidate
	 * @param coverage       the split-word coverage to forward each hit to, `null` for an ordinary word
	 * @return true when at least one candidate was hit
	 */
	private static boolean gallopingMerge(
		@Nonnull int[] candidates,
		@Nonnull PostingFeed postings,
		@Nonnull ImpactView.Reader impacts,
		byte distance,
		@Nonnull byte[] placeImpact,
		@Nonnull byte[] tokenTypo,
		boolean exact,
		@Nonnull byte[] tokenExactness,
		@Nullable PartCoverage coverage
	) {
		final int candidateCount = candidates.length;
		final int[] batch = postings.buffer();
		boolean hit = false;
		int from = 0;
		// position of the batch's first posting in the posting list - the offset of its impact byte
		int batchStart = 0;
		while (from < candidateCount) {
			final int batchLength = postings.next();
			if (batchLength == 0) {
				break;
			}
			for (int batchIndex = 0; batchIndex < batchLength && from < candidateCount; batchIndex++) {
				final int posting = batch[batchIndex];
				from = gallopTo(candidates, from, posting);
				if (from < candidateCount && candidates[from] == posting) {
					recordHit(from, impacts.impactAt(batchStart + batchIndex), distance, placeImpact, tokenTypo,
						exact, tokenExactness, coverage);
					hit = true;
					from++;
				}
			}
			batchStart += batchLength;
		}
		return hit;
	}

	/**
	 * Merges one expansion by visiting every candidate in order and skipping the posting list to it. The skip is the
	 * posting list's own: a roaring list skips whole containers and searches inside one, and its rank iterator keeps
	 * the position of the posting it stands on, which is the offset of the posting's impact byte. When the posting
	 * found lies beyond the candidate, nothing in between is in the list, so the candidate cursor gallops to the
	 * posting instead of probing every candidate it passes.
	 *
	 * The small tiers of a bucket - a single record, or a sorted array of a few records - have no iterator to skip
	 * with; they are searched per candidate, which answers the position as well.
	 *
	 * @param candidates     the candidates, ascending
	 * @param postings       the expansion's postings
	 * @param impacts        reader of the impact bytes aligned to the postings
	 * @param distance       the expansion's one-based edit distance
	 * @param placeImpact    the persistent raw maximum of the expansion's place, by candidate
	 * @param tokenTypo      the per-token typo lane, by candidate
	 * @param exact          whether this expansion is EXACT
	 * @param tokenExactness whether any exact expansion of this word hit, by candidate
	 * @param coverage       the split-word coverage to forward each hit to, `null` for an ordinary word
	 * @return true when at least one candidate was hit
	 */
	private static boolean skippingMerge(
		@Nonnull int[] candidates,
		@Nonnull Bitmap postings,
		@Nonnull ImpactView.Reader impacts,
		byte distance,
		@Nonnull byte[] placeImpact,
		@Nonnull byte[] tokenTypo,
		boolean exact,
		@Nonnull byte[] tokenExactness,
		@Nullable PartCoverage coverage
	) {
		final int candidateCount = candidates.length;
		boolean hit = false;
		if (postings instanceof final RoaringBitmapBackedBitmap roaringBacked) {
			final PeekableIntRankIterator cursor = roaringBacked.getRoaringBitmap().getIntRankIterator();
			int candidateIndex = 0;
			while (candidateIndex < candidateCount) {
				final int candidate = candidates[candidateIndex];
				cursor.advanceIfNeeded(candidate);
				if (!cursor.hasNext()) {
					break;
				}
				final int posting = cursor.peekNext();
				if (posting == candidate) {
					// the rank counts from one, the impact offset from zero
					recordHit(
						candidateIndex, impacts.impactAt(cursor.peekNextRank() - 1), distance, placeImpact, tokenTypo,
						exact, tokenExactness, coverage
					);
					hit = true;
					candidateIndex++;
				} else if (++candidateIndex < candidateCount) {
					candidateIndex = gallopTo(candidates, candidateIndex, posting);
				}
			}
		} else {
			for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
				final int position = postings.indexOf(candidates[candidateIndex]);
				if (position >= 0) {
					recordHit(candidateIndex, impacts.impactAt(position), distance, placeImpact, tokenTypo,
						exact, tokenExactness, coverage);
					hit = true;
				}
			}
		}
		return hit;
	}

	/**
	 * Finds the first candidate at or above the target, searching outward from the cursor - the answer is usually just
	 * ahead of it - in exponential steps, then binary inside the last step.
	 *
	 * @param candidates the candidates, ascending
	 * @param from       index to search from, below the candidate count
	 * @param target     the value to find
	 * @return index of the first candidate at or above `target` from `from` on, or the candidate count when there is
	 * none
	 */
	private static int gallopTo(@Nonnull int[] candidates, int from, int target) {
		final int candidateCount = candidates.length;
		int low = from;
		if (candidates[low] >= target) {
			return low;
		}
		int step = 1;
		int high = low + 1;
		while (high < candidateCount && candidates[high] < target) {
			low = high;
			step <<= 1;
			high = low + step;
		}
		if (high > candidateCount) {
			high = candidateCount;
		}
		low++;
		while (low < high) {
			final int mid = (low + high) >>> 1;
			if (candidates[mid] < target) {
				low = mid + 1;
			} else {
				high = mid;
			}
		}
		return low;
	}

	/**
	 * Records one hit of the current word on a candidate, keeping the best lanes across the word's expansions, and
	 * forwards it to the split-word coverage, if any, which counts the part it covers.
	 *
	 * @param candidateIndex index of the candidate hit
	 * @param impact         the impact byte of the posting
	 * @param distance       the expansion's one-based edit distance
	 * @param placeImpact    the persistent raw maximum of the expansion's place, by candidate
	 * @param tokenTypo      the per-token typo lane, by candidate
	 * @param exact          whether this expansion is EXACT
	 * @param tokenExactness whether any exact expansion of this word hit, by candidate
	 * @param coverage       the split-word coverage to forward the hit to, `null` for an ordinary word
	 */
	private static void recordHit(
		int candidateIndex,
		byte impact,
		byte distance,
		@Nonnull byte[] placeImpact,
		@Nonnull byte[] tokenTypo,
		boolean exact,
		@Nonnull byte[] tokenExactness,
		@Nullable PartCoverage coverage
	) {
		if (exact) {
			tokenExactness[candidateIndex] = 1;
		}
		if (coverage != null) {
			coverage.recordHit(candidateIndex);
		}
		if (Byte.toUnsignedInt(impact) > Byte.toUnsignedInt(placeImpact[candidateIndex])) {
			placeImpact[candidateIndex] = impact;
		}
		if (
			tokenTypo[candidateIndex] == 0
				|| Byte.toUnsignedInt(distance) < Byte.toUnsignedInt(tokenTypo[candidateIndex])
		) {
			tokenTypo[candidateIndex] = distance;
		}
	}

	/**
	 * Counts coverage using one last-part marker per candidate and the existing matched-word accumulator.
	 * Expansions are contiguous by part, with the original first, so a marker suffices to deduplicate hits without
	 * per-part candidate arrays. An original hit marks every part covered and suppresses all later part hits.
	 *
	 * The scoring loop drives it: it sets {@link #partCount} for each split word and {@link #part} before each of the
	 * word's expansions, and calls {@link #reset()} after each word that hit.
	 */
	private static final class PartCoverage {
		/** Last positive part id counted, zero before a hit, or -1 when the original covered all parts. */
		private final int[] lastPart;
		/** Existing query-wide matched-word lane, incremented directly on the first hit of each part. */
		private final byte[] matchedWords;
		/** Part whose consecutive expansions are being merged, set before each expansion; zero is the original. */
		private int part;
		/** Number of parts of the current split word, set for each split word. */
		private int partCount;

		/**
		 * Allocates candidate-sized markers only when a scoring query contains split words.
		 * @param candidateCount number of candidates actually being ranked
		 * @param matchedWords existing matched-word accumulator receiving the coverage contribution
		 */
		private PartCoverage(int candidateCount, @Nonnull byte[] matchedWords) {
			this.lastPart = new int[candidateCount];
			this.matchedWords = matchedWords;
		}

		/**
		 * Counts this part once; the original comes first and covers every part at once.
		 * @param candidateIndex candidate hit by an expansion of the current part
		 */
		private void recordHit(int candidateIndex) {
			final int last = this.lastPart[candidateIndex];
			if (last != -1 && (this.part == 0 || last != this.part)) {
				final int increment = this.part == 0 ? this.partCount : 1;
				this.matchedWords[candidateIndex] = (byte) Math.min(
					255, Byte.toUnsignedInt(this.matchedWords[candidateIndex]) + increment
				);
				this.lastPart[candidateIndex] = this.part == 0 ? -1 : this.part;
			}
		}

		/** Clears the markers after a word that hit, before the next query word, keeping the candidate-sized array. */
		private void reset() {
			Arrays.fill(this.lastPart, 0);
		}
	}

	/**
	 * Selects the `selected` best candidates through a min-heap of candidate indexes, then orders them best first.
	 *
	 * @param candidates       the candidates
	 * @param composites       the composite of each candidate, `0` for one that matched nothing
	 * @param selected         how many to select; never more than the matched candidates
	 * @param matchedDocuments how many candidates matched
	 * @param postingsWalked   postings the merges stepped over
	 * @return the result
	 */
	@Nonnull
	private static Result selectTopN(
		@Nonnull int[] candidates,
		@Nonnull long[] composites,
		int selected,
		int matchedDocuments,
		long postingsWalked
	) {
		final int[] heap = new int[selected];
		int size = 0;
		if (selected > 0) {
			for (int i = 0; i < candidates.length; i++) {
				if (composites[i] == 0L) {
					continue;
				}
				if (size < selected) {
					heap[size++] = i;
					if (size == selected) {
						for (int start = selected / 2 - 1; start >= 0; start--) {
							siftDown(heap, start, selected, candidates, composites);
						}
					}
				} else if (ranksAbove(i, heap[0], candidates, composites)) {
					heap[0] = i;
					siftDown(heap, 0, selected, candidates, composites);
				}
			}
		}
		// drain the min-heap from the back, so the best candidate lands first
		final int[] primaryKeys = new int[size];
		final long[] selectedComposites = new long[size];
		for (int end = size - 1; end >= 0; end--) {
			final int worst = heap[0];
			primaryKeys[end] = candidates[worst];
			selectedComposites[end] = composites[worst];
			heap[0] = heap[end];
			siftDown(heap, 0, end, candidates, composites);
		}
		return new Result(primaryKeys, selectedComposites, matchedDocuments, postingsWalked);
	}

	/**
	 * Returns whether the first candidate ranks strictly above the second: higher composite in unsigned order, or the
	 * same composite and a smaller primary key.
	 *
	 * @param first      index of the first candidate
	 * @param second     index of the second candidate
	 * @param candidates the candidates
	 * @param composites their composites
	 * @return true when the first ranks above
	 */
	private static boolean ranksAbove(int first, int second, @Nonnull int[] candidates, @Nonnull long[] composites) {
		final long firstComposite = composites[first];
		final long secondComposite = composites[second];
		// unsigned: the matched-token lane fills the top byte, so 128 or more matched tokens set the sign bit
		final int comparison = Long.compareUnsigned(firstComposite, secondComposite);
		return comparison > 0 || (comparison == 0 && candidates[first] < candidates[second]);
	}

	/**
	 * Restores the min-heap property — the worst-ranked candidate at the root — downwards from one position.
	 *
	 * @param heap       candidate indexes
	 * @param from       position to sift down from
	 * @param size       live size of the heap
	 * @param candidates the candidates
	 * @param composites their composites
	 */
	private static void siftDown(
		@Nonnull int[] heap,
		int from,
		int size,
		@Nonnull int[] candidates,
		@Nonnull long[] composites
	) {
		int parent = from;
		while (true) {
			final int left = 2 * parent + 1;
			if (left >= size) {
				return;
			}
			final int right = left + 1;
			final int worse = right < size && ranksAbove(heap[left], heap[right], candidates, composites)
				? right : left;
			if (!ranksAbove(heap[parent], heap[worse], candidates, composites)) {
				return;
			}
			final int swap = heap[parent];
			heap[parent] = heap[worse];
			heap[worse] = swap;
			parent = worse;
		}
	}

	/**
	 * Hands an expansion's postings to the posting-driven merges batch by batch, in the posting list's record order -
	 * the order the impacts are aligned with. A roaring list is decoded whole, once, by its own bulk extraction -
	 * container by container, in its unsigned record order (never {@link Bitmap#getArray()}, which moves negative ids
	 * first and would misalign the impacts) - into one decode buffer that grows to the longest list decoded and is
	 * reused for every shorter one, and handed out as a single batch; the small tiers of a bucket (a single record, a
	 * sorted array of a few records) are read by position through one reusable buffer.
	 *
	 * The copy is measured, not assumed: feeding a roaring list through its batch iterator instead - no copy, one
	 * small buffer - was 5-7 % slower on posting-dense queries of a production CMS catalog and faster on none. The
	 * decode buffer outlives the call for the same reason it is reused within one: a buffer of each call's own still
	 * allocated the call's longest list once per query, most of what a fresh array per list costs.
	 *
	 * One feed per thread ({@link #POSTING_FEED}) serves every expansion of every scoring call on that thread,
	 * re-targeted by {@link #reset(Bitmap)} and let go of the last list by {@link #release()}.
	 */
	private static final class PostingFeed {
		/**
		 * The buffer the batches of a small tier are written to.
		 */
		private final int[] buffer = new int[POSTING_BATCH_SIZE];
		/**
		 * The array the batches of the current posting list sit in: {@link #buffer}, or {@link #decoded}.
		 */
		private int[] batches = this.buffer;
		/**
		 * The buffer roaring lists are decoded into, as long as the longest one decoded so far; the current list
		 * occupies its prefix of {@link #decodedLength} postings, and what lies past it is left over from longer lists.
		 */
		private int[] decoded = new int[0];
		/**
		 * How many postings of {@link #decoded} the current roaring list fills, `-1` when the current list is a small
		 * tier or the feed points at no list.
		 */
		private int decodedLength = -1;
		/**
		 * Whether the decoded current list has been handed out as its single batch already.
		 */
		private boolean decodedServed;
		/**
		 * The current posting list when it is a small tier, read by position; `null` for a roaring list.
		 */
		@Nullable private Bitmap positionalPostings;
		/**
		 * Position of the next posting to read from {@link #positionalPostings}.
		 */
		private int nextPosition;

		/** Creates a reusable posting feed for one scoring thread. */
		private PostingFeed() { }

		/**
		 * Points the feed at the start of a posting list.
		 *
		 * @param postings the posting list to feed, not empty
		 * @return this feed
		 */
		@Nonnull
		PostingFeed reset(@Nonnull Bitmap postings) {
			if (postings instanceof final RoaringBitmapBackedBitmap roaringBacked) {
				final int postingCount = postings.size();
				if (this.decoded.length < postingCount) {
					this.decoded = new int[postingCount];
				}
				this.decodedLength = roaringBacked.getRoaringBitmap().decodeInto(this.decoded);
				this.batches = this.decoded;
				this.decodedServed = false;
				this.positionalPostings = null;
			} else {
				this.decodedLength = -1;
				this.batches = this.buffer;
				this.positionalPostings = postings;
				this.nextPosition = 0;
			}
			return this;
		}

		/**
		 * Lets go of the current posting list at the end of a scoring call, so a feed kept for the thread holds no
		 * index structure alive between calls; the decode buffer stays for the next call. Until the next
		 * {@link #reset(Bitmap)} the feed points at no list.
		 */
		void release() {
			this.positionalPostings = null;
			this.decodedLength = -1;
			this.batches = this.buffer;
		}

		/**
		 * Returns the array {@link #next()} hands each batch out in; the batch occupies its prefix. It changes with
		 * {@link #reset(Bitmap)}, so it is read after the feed is pointed at a posting list.
		 *
		 * @return the batch array
		 */
		@Nonnull
		int[] buffer() {
			return this.batches;
		}

		/**
		 * Hands out the next batch of postings in {@link #buffer()}.
		 *
		 * @return how many postings the batch holds, `0` once the posting list is exhausted
		 */
		int next() {
			final int decodedLength = this.decodedLength;
			if (decodedLength >= 0) {
				if (this.decodedServed) {
					return 0;
				}
				this.decodedServed = true;
				return decodedLength;
			}
			final Bitmap postings = this.positionalPostings;
			if (postings == null) {
				throw new GenericEvitaInternalError("The posting feed was read before it was reset!");
			}
			final int end = Math.min(postings.size(), this.nextPosition + this.buffer.length);
			final int batchLength = end - this.nextPosition;
			for (int i = 0; i < batchLength; i++) {
				this.buffer[i] = postings.get(this.nextPosition + i);
			}
			this.nextPosition = end;
			return batchLength;
		}

	}

}
