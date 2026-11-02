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
 * posting lists, and selects the top N.
 *
 * ## What is scored
 *
 * A query is a sequence of **tokens**; each token expands into one or more **terms** of the dictionary — its stem
 * variants, its prefix completions, its typo neighbours — and each expansion carries its posting list, the impact
 * bytes aligned with it, and the edit distance at which it matched. For every candidate the walk keeps four lanes:
 *
 * | lane | meaning | best is |
 * |---|---|---|
 * | matched tokens | how many query tokens the candidate contains | more |
 * | exactness | whether some token matched without a typo | 1 |
 * | typo distance | the smallest edit distance any token matched at | smaller |
 * | impact | the largest impact byte any matched expansion carries | larger |
 *
 * and composes them, once, into a 64-bit value whose **unsigned** order is the ranking:
 * `matchedTokens << 56 | exactness << 48 | (0xFE - distance) << 40 | impact << 32`, where `distance` is the smallest
 * edit distance - the typo lane holds `distance + 1` while it accumulates, so `0` can mean "not hit", and the
 * composite stores `0xFF` minus that. The matched-token lane fills the top byte, so 128 or more matched tokens set the
 * sign bit: compare composites with `Long.compareUnsigned`, never as signed longs. The low 32 bits are reserved for the
 * contextual lanes of later ranking phases and are left zero here.
 *
 * ## The counting rule
 *
 * **A query token counts once however many of its expansions hit a candidate**, and the lanes it contributes are the
 * best across those expansions. Counting expansions instead would rank a document higher for containing two stem
 * variants of one word than for containing two different words of the query. The rule is implemented by merging each
 * token's expansions into per-token lanes before they reach the accumulators.
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
	 * Most query tokens a single query may carry: the matched-token lane is one byte wide.
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
	 * @param distance edit distance at which the term matched the token, `0` for an exact match
	 */
	public record Expansion(@Nonnull Bitmap postings, @Nonnull ImpactView impacts, int distance) {

		/**
		 * Validates the expansion.
		 */
		public Expansion {
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
	 * The result of one scoring pass: the selected entities in descending rank order.
	 *
	 * @param primaryKeys      primary keys of the top entities, best first
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
	 * Scores the candidates against the query and returns the top N.
	 *
	 * The candidates ascend in signed `int` order and every posting list is read in its own record order, which is
	 * unsigned; the two agree because entity primary keys are never negative. The expansions are only read, never
	 * written, for the duration of the call.
	 *
	 * @param candidates primary keys of the candidate entities, ascending and distinct
	 * @param tokens     the query tokens, each as the expansions it produced; a token without expansions matches
	 *                   nothing but still counts toward the token limit
	 * @param topN       how many entities to return, at least one
	 * @return the selected entities in descending rank order; ties are broken by ascending primary key
	 * @throws io.evitadb.exception.GenericEvitaInternalError when `topN` is not positive, or the query carries more
	 *                                                        than {@link #MAX_QUERY_TOKENS} tokens
	 */
	@Nonnull
	public static Result score(@Nonnull int[] candidates, @Nonnull Expansion[][] tokens, int topN) {
		return score(candidates, tokens, topN, MergeStrategy.ADAPTIVE);
	}

	/**
	 * Scores the candidates against the query with the chosen merge strategy and returns the top N.
	 *
	 * @param candidates primary keys of the candidate entities, ascending and distinct
	 * @param tokens     the query tokens, each as the expansions it produced
	 * @param topN       how many entities to return, at least one
	 * @param strategy   how each expansion is merged
	 * @return the selected entities in descending rank order; ties are broken by ascending primary key
	 * @throws io.evitadb.exception.GenericEvitaInternalError when `topN` is not positive, or the query carries more
	 *                                                        than {@link #MAX_QUERY_TOKENS} tokens
	 */
	@Nonnull
	static Result score(
		@Nonnull int[] candidates,
		@Nonnull Expansion[][] tokens,
		int topN,
		@Nonnull MergeStrategy strategy
	) {
		Assert.isPremiseValid(topN > 0, () -> "The number of results must be positive, " + topN + " was requested!");
		Assert.isPremiseValid(
			tokens.length <= MAX_QUERY_TOKENS,
			() -> "A query may carry at most " + MAX_QUERY_TOKENS + " tokens, " + tokens.length + " were passed!"
		);
		final int candidateCount = candidates.length;
		final byte[] matchedTokens = new byte[candidateCount];
		final byte[] bestExactness = new byte[candidateCount];
		final byte[] bestTypo = new byte[candidateCount];
		final byte[] maxImpact = new byte[candidateCount];
		// the per-token lanes: every expansion of one token writes here, and only the merged result reaches the
		// accumulators above - the cheapest implementation of the counting rule
		final byte[] tokenImpact = new byte[candidateCount];
		final byte[] tokenTypo = new byte[candidateCount];
		final PostingFeed feed = POSTING_FEED.get();
		long postingsWalked = 0L;

		try {
			for (final Expansion[] expansions : tokens) {
				boolean anyHit = false;
				for (final Expansion expansion : expansions) {
					final Bitmap postings = expansion.postings();
					final int postingCount = postings.size();
					if (postingCount == 0) {
						continue;
					}
					// stored one-based, so `0` in the typo lane means "this token did not hit the candidate"
					final byte distance = (byte) (expansion.distance() + 1);
					final ImpactView.Reader impacts = expansion.impacts().reader();
					anyHit |= switch (chooseMerge(strategy, candidateCount, postingCount)) {
						case LINEAR -> linearMerge(
							candidates, feed.reset(postings), impacts, distance, tokenImpact, tokenTypo
						);
						case GALLOPING -> gallopingMerge(
							candidates, feed.reset(postings), impacts, distance, tokenImpact, tokenTypo
						);
						case SKIPPING -> skippingMerge(candidates, postings, impacts, distance, tokenImpact, tokenTypo);
						case ADAPTIVE -> throw new GenericEvitaInternalError("The merge choice never yields ADAPTIVE!");
					};
					postingsWalked += postingCount;
				}
				if (anyHit) {
					// ONE increment per query token, however many of its expansions hit
					for (int i = 0; i < candidateCount; i++) {
						final byte typo = tokenTypo[i];
						if (typo == 0) {
							continue;
						}
						matchedTokens[i]++;
						if (Byte.toUnsignedInt(tokenImpact[i]) > Byte.toUnsignedInt(maxImpact[i])) {
							maxImpact[i] = tokenImpact[i];
						}
						// the lane is one-based up to MAX_DISTANCE + 1 = 255, so it compares unsigned
						if (bestTypo[i] == 0 || Byte.toUnsignedInt(typo) < Byte.toUnsignedInt(bestTypo[i])) {
							bestTypo[i] = typo;
						}
						if (typo == 1) {
							bestExactness[i] = 1;
						}
					}
					Arrays.fill(tokenImpact, (byte) 0);
					Arrays.fill(tokenTypo, (byte) 0);
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
			composites[i] = ((long) Byte.toUnsignedInt(matchedTokens[i]) << 56)
				| ((long) Byte.toUnsignedInt(bestExactness[i]) << 48)
				| ((long) (0xFF - Byte.toUnsignedInt(bestTypo[i])) << 40)
				| ((long) Byte.toUnsignedInt(maxImpact[i]) << 32);
		}
		return selectTopN(candidates, composites, Math.min(topN, matchedDocuments), matchedDocuments, postingsWalked);
	}

	/**
	 * Selects the top N of candidates scored elsewhere, in the order {@link #score} selects its own: composite
	 * compared unsigned, best first, ties by ascending primary key. It lets a score source that derives composites
	 * rather than computing them - the owners of scored referenced entities - rank exactly as the scorer does.
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
	 * @param candidates  the candidates, ascending
	 * @param postings    the expansion's postings, fed in batches
	 * @param impacts     reader of the impact bytes aligned to the postings
	 * @param distance    the expansion's one-based edit distance
	 * @param tokenImpact the per-token impact lane, by candidate
	 * @param tokenTypo   the per-token typo lane, by candidate
	 * @return true when at least one candidate was hit
	 */
	private static boolean linearMerge(
		@Nonnull int[] candidates,
		@Nonnull PostingFeed postings,
		@Nonnull ImpactView.Reader impacts,
		byte distance,
		@Nonnull byte[] tokenImpact,
		@Nonnull byte[] tokenTypo
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
						candidateIndex, impacts.impactAt(batchStart + batchIndex), distance, tokenImpact, tokenTypo
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
	 * @param candidates  the candidates, ascending
	 * @param postings    the expansion's postings, fed in batches
	 * @param impacts     reader of the impact bytes aligned to the postings
	 * @param distance    the expansion's one-based edit distance
	 * @param tokenImpact the per-token impact lane, by candidate
	 * @param tokenTypo   the per-token typo lane, by candidate
	 * @return true when at least one candidate was hit
	 */
	private static boolean gallopingMerge(
		@Nonnull int[] candidates,
		@Nonnull PostingFeed postings,
		@Nonnull ImpactView.Reader impacts,
		byte distance,
		@Nonnull byte[] tokenImpact,
		@Nonnull byte[] tokenTypo
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
					recordHit(from, impacts.impactAt(batchStart + batchIndex), distance, tokenImpact, tokenTypo);
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
	 * @param candidates  the candidates, ascending
	 * @param postings    the expansion's postings
	 * @param impacts     reader of the impact bytes aligned to the postings
	 * @param distance    the expansion's one-based edit distance
	 * @param tokenImpact the per-token impact lane, by candidate
	 * @param tokenTypo   the per-token typo lane, by candidate
	 * @return true when at least one candidate was hit
	 */
	private static boolean skippingMerge(
		@Nonnull int[] candidates,
		@Nonnull Bitmap postings,
		@Nonnull ImpactView.Reader impacts,
		byte distance,
		@Nonnull byte[] tokenImpact,
		@Nonnull byte[] tokenTypo
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
						candidateIndex, impacts.impactAt(cursor.peekNextRank() - 1), distance, tokenImpact, tokenTypo
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
					recordHit(candidateIndex, impacts.impactAt(position), distance, tokenImpact, tokenTypo);
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
	 * Records one hit of the current token on a candidate, keeping the best lanes across the token's expansions.
	 *
	 * @param candidateIndex index of the candidate hit
	 * @param impact         the impact byte of the posting
	 * @param distance       the expansion's one-based edit distance
	 * @param tokenImpact    the per-token impact lane, by candidate
	 * @param tokenTypo      the per-token typo lane, by candidate
	 */
	private static void recordHit(
		int candidateIndex,
		byte impact,
		byte distance,
		@Nonnull byte[] tokenImpact,
		@Nonnull byte[] tokenTypo
	) {
		if (Byte.toUnsignedInt(impact) > Byte.toUnsignedInt(tokenImpact[candidateIndex])) {
			tokenImpact[candidateIndex] = impact;
		}
		if (
			tokenTypo[candidateIndex] == 0
				|| Byte.toUnsignedInt(distance) < Byte.toUnsignedInt(tokenTypo[candidateIndex])
		) {
			tokenTypo[candidateIndex] = distance;
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
