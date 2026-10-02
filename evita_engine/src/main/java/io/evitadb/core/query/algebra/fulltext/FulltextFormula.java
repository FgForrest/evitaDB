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

package io.evitadb.core.query.algebra.fulltext;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Expansion;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;

/**
 * The formula a fulltext condition translates into: it computes the entities matching every token of the query, and
 * ranks them on request through {@link FulltextScoreAccessor}.
 *
 * ## What it matches
 *
 * A query is a sequence of **tokens**; each token has expanded, before this formula was built, into the
 * {@link ExpandedTerm terms} of the {@link FulltextIndex} dictionary it stands for — its stem variants, prefix
 * completions and typo neighbours, in every searched field. The match set is **strict**: an entity matches when it
 * contains, for every token, at least one of that token's terms - the intersection over tokens of the union over each
 * token's posting lists. A token that expanded into no term therefore matches nothing, and neither does the query;
 * loosening that is the opt-in relaxation the query design reserves for a later child of the constraint, never a
 * silent default, because facets, histograms and totals all stand on the match set.
 *
 * ## What it ranks
 *
 * {@link #getFulltextScores(int[], int)} runs {@link FulltextPhaseOneScorer} over the same terms and the impact bytes
 * aligned with their postings, for whatever candidates the caller passes - typically the final result of the whole
 * filter, which this formula's own result only bounds from above. A token counts once however many of its terms hit
 * a candidate, whichever field they came from.
 *
 * ## Why it is never cached
 *
 * The formula implements {@link NonCacheableFormula}, which keeps it and every formula above it out of the result
 * cache. A cached sub-tree is flattened into a bitmap and the flattened form would keep the match set but drop the
 * terms and impacts the scores are computed from, so relevance would silently stop ordering anything on a cache
 * hit. Caching a fulltext result means a flattened form that carries the scores, as the price formulas' flattened
 * forms carry their price records.
 *
 * The terms are read, never copied or written: the posting lists and impact views are the dictionary's own read-only
 * views, so the index must not be written while the formula is in use - which the transactional snapshot a query
 * runs against already guarantees.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextFormula extends AbstractFormula implements NonCacheableFormula, FulltextScoreAccessor {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = 3355763462417010376L;
	/**
	 * The query tokens, each as the terms it expanded into.
	 */
	@Nonnull private final ExpandedTerm[][] tokens;
	/**
	 * The tokens in the shape the scorer reads, built on the first scoring request.
	 */
	@Nullable private Expansion[][] memoizedExpansions;

	/**
	 * One term of the dictionary a query token expanded into, in one field.
	 *
	 * @param fieldId  id of the field the term was found in
	 * @param term     the analyzed term, without the field prefix
	 * @param postings primary keys of the entities whose field contains the term, ascending
	 * @param impacts  impact byte of each posting, the i-th belonging to the i-th posting
	 * @param distance edit distance at which the term matched the token, `0` for an exact match
	 */
	public record ExpandedTerm(
		int fieldId,
		@Nonnull String term,
		@Nonnull Bitmap postings,
		@Nonnull ImpactView impacts,
		int distance
	) {

		/**
		 * Validates the term.
		 */
		public ExpandedTerm {
			Assert.isPremiseValid(
				postings.size() == impacts.size(),
				() -> "Term `" + term + "` carries " + postings.size() + " postings but " + impacts.size() + " impacts!"
			);
			Assert.isPremiseValid(
				distance >= 0 && distance <= FulltextPhaseOneScorer.MAX_DISTANCE,
				() -> "Edit distance " + distance + " is outside 0.." + FulltextPhaseOneScorer.MAX_DISTANCE + "!"
			);
		}

		@Nonnull
		@Override
		public String toString() {
			return this.fieldId + ":" + this.term + (this.distance == 0 ? "" : "~" + this.distance) +
				" (" + this.postings.size() + ")";
		}

	}

	/**
	 * Creates the formula over the query's tokens.
	 *
	 * @param tokens the query tokens in query order, each as the terms it expanded into; a token may carry no term,
	 *               in which case nothing matches. The arrays are adopted, not copied, and must not be written after
	 *               the call.
	 * @throws io.evitadb.exception.GenericEvitaInternalError when there is no token, or more tokens than the scorer's
	 *                                                        matched-token lane can count
	 */
	public FulltextFormula(@Nonnull ExpandedTerm[][] tokens) {
		Assert.isPremiseValid(tokens.length > 0, "A fulltext formula needs at least one query token!");
		Assert.isPremiseValid(
			tokens.length <= FulltextPhaseOneScorer.MAX_QUERY_TOKENS,
			() -> "A query may carry at most " + FulltextPhaseOneScorer.MAX_QUERY_TOKENS + " tokens, " +
				tokens.length + " were passed!"
		);
		this.tokens = tokens;
		this.initFields();
	}

	/**
	 * Returns the query tokens, each as the terms it expanded into.
	 *
	 * @return the tokens, in query order; the arrays are this formula's own and must not be written
	 */
	@Nonnull
	public ExpandedTerm[][] getTokens() {
		return this.tokens;
	}

	@Override
	public boolean providesFulltextScores() {
		return true;
	}

	@Nonnull
	@Override
	public Result getFulltextScores(@Nonnull int[] candidates, int topN) {
		if (this.memoizedExpansions == null) {
			this.memoizedExpansions = toExpansions(this.tokens);
		}
		return FulltextPhaseOneScorer.score(candidates, this.memoizedExpansions, topN);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException("Fulltext formula cannot have inner formulas!");
	}

	@Override
	public int getEstimatedCardinality() {
		// a strict match cannot exceed its narrowest token, and a token cannot exceed the sum of its postings
		long narrowest = Integer.MAX_VALUE;
		for (final ExpandedTerm[] token : this.tokens) {
			narrowest = Math.min(narrowest, postingCount(token));
		}
		return (int) narrowest;
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Nonnull
	@Override
	public String toString() {
		return "FULLTEXT MATCH OF " + this.tokens.length + " TOKENS";
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		final StringBuilder sb = new StringBuilder(toString()).append(':');
		for (final ExpandedTerm[] token : this.tokens) {
			sb.append(' ').append(Arrays.toString(token));
		}
		return sb.toString();
	}

	@Override
	protected long getEstimatedCostInternal() {
		// every posting of every term is read once - the union per token, then the intersection of the unions
		long postings = 0L;
		for (final ExpandedTerm[] token : this.tokens) {
			postings += postingCount(token);
		}
		return postings * getOperationCost();
	}

	@Override
	protected long getCostInternal() {
		return getEstimatedCostInternal();
	}

	@Nonnull
	@Override
	protected long[] gatherBitmapIdsInternal() {
		int termCount = 0;
		for (final ExpandedTerm[] token : this.tokens) {
			termCount += token.length;
		}
		final long[] result = new long[termCount];
		int index = 0;
		for (final ExpandedTerm[] token : this.tokens) {
			for (final ExpandedTerm term : token) {
				result[index++] = bitmapIdentityToken(term.postings(), HASH_FUNCTION);
			}
		}
		return result;
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		// neither the order of the tokens nor the order of a token's terms changes the match set or the scores, so
		// both levels are hashed order-independently; the distance is included because it changes the scores
		final long[] tokenHashes = new long[this.tokens.length];
		for (int i = 0; i < this.tokens.length; i++) {
			final ExpandedTerm[] token = this.tokens[i];
			final long[] termHashes = new long[token.length];
			for (int j = 0; j < token.length; j++) {
				termHashes[j] = hashFunction.hashLongs(
					new long[]{bitmapIdentityToken(token[j].postings(), hashFunction), token[j].distance()}
				);
			}
			Arrays.sort(termHashes);
			tokenHashes[i] = hashFunction.hashLongs(termHashes);
		}
		Arrays.sort(tokenHashes);
		return hashFunction.hashLongs(tokenHashes);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final PersistentRoaringBitmap[] unions = new PersistentRoaringBitmap[this.tokens.length];
		for (int i = 0; i < this.tokens.length; i++) {
			final ExpandedTerm[] token = this.tokens[i];
			if (token.length == 0) {
				// a token that matched no term cannot be satisfied, so neither can the query
				return EmptyBitmap.INSTANCE;
			}
			final PersistentRoaringBitmap union;
			if (token.length == 1) {
				union = RoaringBitmapBackedBitmap.getRoaringBitmap(token[0].postings());
			} else {
				final PersistentRoaringBitmap[] postings = new PersistentRoaringBitmap[token.length];
				for (int j = 0; j < token.length; j++) {
					postings[j] = RoaringBitmapBackedBitmap.getRoaringBitmap(token[j].postings());
				}
				union = PersistentRoaringBitmap.or(postings);
			}
			if (union.isEmpty()) {
				return EmptyBitmap.INSTANCE;
			}
			unions[i] = union;
		}
		// intersect the narrowest unions first
		Arrays.sort(unions, Comparator.comparingLong(PersistentRoaringBitmap::getLongCardinality));
		return computeConjunctionResult(unions);
	}

	/**
	 * Returns the summed posting count of a token's terms.
	 *
	 * @param token the terms of one token
	 * @return the number of postings, which bounds the size of the token's union from above
	 */
	private static long postingCount(@Nonnull ExpandedTerm[] token) {
		long count = 0L;
		for (final ExpandedTerm term : token) {
			count += term.postings().size();
		}
		return count;
	}

	/**
	 * Converts the tokens to the shape the scorer reads: each posting list as an ascending array.
	 *
	 * @param tokens the tokens
	 * @return the scorer's expansions, token by token
	 */
	@Nonnull
	private static Expansion[][] toExpansions(@Nonnull ExpandedTerm[][] tokens) {
		final Expansion[][] expansions = new Expansion[tokens.length][];
		for (int i = 0; i < tokens.length; i++) {
			final ExpandedTerm[] token = tokens[i];
			final Expansion[] tokenExpansions = new Expansion[token.length];
			for (int j = 0; j < token.length; j++) {
				final ExpandedTerm term = token[j];
				tokenExpansions[j] = new Expansion(term.postings().getArray(), term.impacts(), term.distance());
			}
			expansions[i] = tokenExpansions;
		}
		return expansions;
	}

}
