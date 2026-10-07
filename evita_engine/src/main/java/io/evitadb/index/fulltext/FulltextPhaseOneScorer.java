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

import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
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
 * ## The merge, and why the impact offset stays free
 *
 * Each expansion's postings are merged against the sorted candidate array by whichever of two strategies is cheaper
 * for its length: a linear two-cursor walk costing `candidates + postings`, or a galloping search that moves only
 * the candidate cursor and costs about `postings × log2(candidates / postings)`. Both visit the postings **in order,
 * one by one, never going back** - each stops early only once the candidates run out - so the posting index is always
 * the offset of its impact byte — no rank computation is ever needed, and the impact reader only ever moves forward,
 * crossing each chunk boundary of a chunked bucket at most once. The
 * galloping strategy is what lets a short posting list against a large candidate set stay within the phase-1 budget:
 * it lifted the cells meeting the budget from 25 to 32 of 36 in the index-core measurements of the fulltext decision
 * record, up to 45× faster on the widest.
 *
 * The accumulators are allocated per call, beside the candidate array; the scorer holds no state between calls and
 * is safe to use from any number of threads.
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
	 * Selects how each expansion is merged against the candidates.
	 */
	enum MergeStrategy {

		/**
		 * Take the cheaper of the linear and the galloping merge per expansion — the production setting.
		 */
		ADAPTIVE,
		/**
		 * Always merge linearly. For tests asserting both strategies agree.
		 */
		LINEAR,
		/**
		 * Always gallop. For tests asserting both strategies agree.
		 */
		GALLOPING

	}

	private FulltextPhaseOneScorer() {
		// utility class
	}

	/**
	 * One term a query token expanded into, with its posting list and the impact bytes aligned to it.
	 *
	 * The impacts are an {@link ImpactView}, so they can come straight from the dictionary cursor
	 * ({@link io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor#impacts()}) without a copy. The
	 * postings are adopted without a copy too, so neither may be written while the expansion is in use.
	 *
	 * @param postings primary keys of the entities containing the term, ascending
	 * @param impacts  impact byte of each posting, the i-th belonging to `postings[i]`
	 * @param distance edit distance at which the term matched the token, `0` for an exact match
	 */
	public record Expansion(@Nonnull int[] postings, @Nonnull ImpactView impacts, int distance) {

		/**
		 * Validates the expansion.
		 */
		public Expansion {
			Assert.isPremiseValid(
				postings.length == impacts.size(),
				() -> "An expansion carries " + postings.length + " postings but " + impacts.size() + " impacts!"
			);
			Assert.isPremiseValid(
				distance >= 0 && distance <= MAX_DISTANCE,
				() -> "Edit distance " + distance + " is outside 0.." + MAX_DISTANCE + "!"
			);
		}

		/**
		 * Creates an expansion from impacts already held as one array, which is adopted, not copied.
		 *
		 * @param postings primary keys of the entities containing the term, ascending
		 * @param impacts  impact byte of each posting, `impacts[i]` belonging to `postings[i]`
		 * @param distance edit distance at which the term matched the token, `0` for an exact match
		 */
		public Expansion(@Nonnull int[] postings, @Nonnull byte[] impacts, int distance) {
			this(postings, ImpactView.of(impacts), distance);
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
	 *                         counted whether or not a merge stopped early because the candidates ran out
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
	 * The candidates and every expansion's postings must ascend in the same, signed `int` order, which is what the
	 * merges walk them in. The expansions are only read, never copied or written, for the duration of the call.
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
		long postingsWalked = 0L;

		for (final Expansion[] expansions : tokens) {
			boolean anyHit = false;
			for (final Expansion expansion : expansions) {
				final int[] postings = expansion.postings();
				// stored one-based, so `0` in the typo lane means "this token did not hit the candidate"
				final byte distance = (byte) (expansion.distance() + 1);
				final ImpactView.Reader impacts = expansion.impacts().reader();
				if (useGallopingMerge(strategy, candidateCount, postings.length)) {
					anyHit |= gallopingMerge(candidates, postings, impacts, distance, tokenImpact, tokenTypo);
				} else {
					anyHit |= linearMerge(candidates, postings, impacts, distance, tokenImpact, tokenTypo);
				}
				postingsWalked += postings.length;
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
	 * Decides the merge strategy for one expansion. A galloping merge costs one gallop per posting over the average
	 * gap between hits; a linear merge costs every candidate plus every posting.
	 *
	 * @param strategy       the requested strategy
	 * @param candidateCount number of candidates
	 * @param postingCount   number of postings of the expansion
	 * @return true to gallop
	 */
	private static boolean useGallopingMerge(@Nonnull MergeStrategy strategy, int candidateCount, int postingCount) {
		return switch (strategy) {
			case LINEAR -> false;
			case GALLOPING -> true;
			case ADAPTIVE -> {
				if (postingCount == 0) {
					yield false;
				}
				final int gap = candidateCount / postingCount;
				// bits needed to gallop across the average gap: 32 - numberOfLeadingZeros(gap - 1) is ceil(log2(gap))
				final int gapBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(gap, 2) - 1));
				final long gallopCost = (long) postingCount * (1 + gapBits);
				yield gallopCost < (long) candidateCount + postingCount;
			}
		};
	}

	/**
	 * Merges one expansion by walking both sorted arrays with two cursors.
	 *
	 * @param candidates  the candidates, ascending
	 * @param postings    the expansion's postings, ascending
	 * @param impacts     reader of the impact bytes aligned to the postings
	 * @param distance    the expansion's one-based edit distance
	 * @param tokenImpact the per-token impact lane, by candidate
	 * @param tokenTypo   the per-token typo lane, by candidate
	 * @return true when at least one candidate was hit
	 */
	private static boolean linearMerge(
		@Nonnull int[] candidates,
		@Nonnull int[] postings,
		@Nonnull ImpactView.Reader impacts,
		byte distance,
		@Nonnull byte[] tokenImpact,
		@Nonnull byte[] tokenTypo
	) {
		boolean hit = false;
		int candidateIndex = 0;
		int postingIndex = 0;
		while (candidateIndex < candidates.length && postingIndex < postings.length) {
			final int candidate = candidates[candidateIndex];
			final int posting = postings[postingIndex];
			if (candidate < posting) {
				candidateIndex++;
			} else if (candidate > posting) {
				postingIndex++;
			} else {
				// the posting cursor IS the impact offset
				recordHit(candidateIndex, impacts.impactAt(postingIndex), distance, tokenImpact, tokenTypo);
				hit = true;
				candidateIndex++;
				postingIndex++;
			}
		}
		return hit;
	}

	/**
	 * Merges one expansion by visiting every posting in order and galloping the candidate cursor to it — exponential
	 * steps from the current position, then a binary search inside the last step.
	 *
	 * @param candidates  the candidates, ascending
	 * @param postings    the expansion's postings, ascending
	 * @param impacts     reader of the impact bytes aligned to the postings
	 * @param distance    the expansion's one-based edit distance
	 * @param tokenImpact the per-token impact lane, by candidate
	 * @param tokenTypo   the per-token typo lane, by candidate
	 * @return true when at least one candidate was hit
	 */
	private static boolean gallopingMerge(
		@Nonnull int[] candidates,
		@Nonnull int[] postings,
		@Nonnull ImpactView.Reader impacts,
		byte distance,
		@Nonnull byte[] tokenImpact,
		@Nonnull byte[] tokenTypo
	) {
		final int candidateCount = candidates.length;
		boolean hit = false;
		int from = 0;
		for (int postingIndex = 0; postingIndex < postings.length && from < candidateCount; postingIndex++) {
			final int posting = postings[postingIndex];
			int low = from;
			if (candidates[low] < posting) {
				// gallop: the answer is usually just ahead of the cursor, so search outward from it rather than
				// over the whole remaining range
				int step = 1;
				int high = low + 1;
				while (high < candidateCount && candidates[high] < posting) {
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
					if (candidates[mid] < posting) {
						low = mid + 1;
					} else {
						high = mid;
					}
				}
			}
			from = low;
			if (from < candidateCount && candidates[from] == posting) {
				recordHit(from, impacts.impactAt(postingIndex), distance, tokenImpact, tokenTypo);
				hit = true;
				from++;
			}
		}
		return hit;
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

}
