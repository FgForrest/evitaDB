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

package io.evitadb.core.query.sort.fulltext;

import io.evitadb.core.query.algebra.fulltext.FulltextScoreAccessor;
import io.evitadb.core.query.algebra.fulltext.FulltextScoreCombiner;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import io.evitadb.index.fulltext.FulltextScoringPolicy;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.IntConsumer;

/**
 * Orders the entities by the fulltext relevance the `textMatches` filter computes - the sorter of `relevanceText()`.
 *
 * ## Where the scores come from
 *
 * The scores are not entity content: the planner found the {@link FulltextScoreAccessor}s in the filter tree, and
 * the filter visitor carried over those of the `entityHaving` / `groupHaving` nested queries, already translated to
 * the owners ({@link io.evitadb.core.query.algebra.fulltext.ReferenceOwnerFulltextScoreAccessor}), whose formulas are
 * not part of the tree. This sorter asks them to rank the entities it is handed only when it sorts. Because the score
 * is keyed by primary key, the same sorter serves the index path and the prefetch path alike, so there is no prefetch
 * twin - the prefetch wrappers delegate the accessor inwards (see {@link FulltextScoreAccessor}).
 *
 * ## Top N, not a full sort
 *
 * Every accessor scores all candidates and returns a vector aligned with those primary keys. Quality comes from
 * the lexicographically best source; strength is best source strength + t × other source strengths. Each source
 * has already combined its own places by the same rule and coefficient. Scopes have disjoint entities, so their
 * vectors never add two scope contributions for one entity. Reference sources distinguish entity versus group
 * mappings and the value of the originating nested constraint; equal constraints and scope/index copies collapse.
 * Each reference place contributes its own 0.5-weighted best target strength.
 *
 * Only after combining does a heap select the best `endIndex` entities, in unsigned composite order with ascending
 * primary keys breaking ties. Per-source top N would lose candidates whose combined evidence earns them a page.
 * Entities before the page are reported as skipped; entities matching no source pass to the next sorter.
 *
 * ## Scope restriction
 *
 * Inside `inScope(...)` of the ordering only the entities of the named scopes are ranked; every other entity is handed
 * on unsorted. An entity lives in exactly one scope, so the restriction is the union of the primary keys of the global
 * indexes of those scopes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class RelevanceTextSorter implements Sorter {
	/**
	 * The accessors providing fulltext scores, distinct by identity, never empty.
	 */
	@Nonnull private final FulltextScoreAccessor[] accessors;
	/**
	 * Global indexes of the scopes whose entities are ranked, or null when the entities of every scope are.
	 */
	@Nullable private final EntityIndex[] scopeIndexes;
	/** Effective query coefficient shared with the score sources. */
	@Nonnull private final FulltextScoringPolicy scoringPolicy;

	/**
	 * Creates the sorter.
	 *
	 * @param accessors    the accessors providing fulltext scores - each must answer
	 *                     {@link FulltextScoreAccessor#providesFulltextScores()} with true
	 * @param scopeIndexes global indexes of the scopes whose entities are ranked, or null to rank the entities of every
	 *                     scope
	 * @param scoringPolicy effective query coefficient
	 */
	public RelevanceTextSorter(
		@Nonnull FulltextScoreAccessor[] accessors, @Nullable EntityIndex[] scopeIndexes,
		@Nonnull FulltextScoringPolicy scoringPolicy
	) {
		Assert.isPremiseValid(accessors.length > 0, "The relevance sorter needs at least one score accessor!");
		this.accessors = accessors;
		this.scopeIndexes = scopeIndexes;
		this.scoringPolicy = scoringPolicy;
	}

	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final Bitmap nonSortedKeys = sortingContext.nonSortedKeys();
		final int recomputedStartIndex = sortingContext.recomputedStartIndex();
		final int recomputedEndIndex = sortingContext.recomputedEndIndex();
		if (nonSortedKeys.isEmpty() || recomputedEndIndex <= 0) {
			// the page has been filled by the sorters before this one - hand everything on untouched
			return sortingContext.createResultContext(nonSortedKeys, 0, 0);
		}

		final Bitmap rankable = restrictToScopes(nonSortedKeys);
		final Ranking ranking = rankable.isEmpty() ?
			Ranking.EMPTY : rank(rankable.getArray(), recomputedEndIndex);
		final int[] ranked = ranking.primaryKeys();

		// the ranking stops at the end of the page, so everything before the page start is skipped and the rest written
		final int skippedRecords = Math.min(recomputedStartIndex, ranked.length);
		if (skippedRecordsConsumer != null) {
			for (int i = 0; i < skippedRecords; i++) {
				skippedRecordsConsumer.accept(ranked[i]);
			}
		}
		final int writtenRecords = ranked.length - skippedRecords;
		System.arraycopy(ranked, skippedRecords, result, sortingContext.peak(), writtenRecords);

		final Bitmap remainder;
		if (!ranking.complete()) {
			// more entities matched than the page reaches - the page is full and nothing follows it
			remainder = EmptyBitmap.INSTANCE;
		} else if (ranked.length == 0) {
			remainder = nonSortedKeys;
		} else {
			final PersistentRoaringBitmap rankedBitmap = PersistentRoaringBitmap.bitmapOfUnordered(ranked);
			final PersistentRoaringBitmap unranked = PersistentRoaringBitmap.andNot(
				RoaringBitmapBackedBitmap.getRoaringBitmap(nonSortedKeys), rankedBitmap
			);
			remainder = unranked.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(unranked);
		}
		return sortingContext.createResultContext(remainder, writtenRecords, skippedRecords);
	}

	/**
	 * Narrows the keys to the entities of the scopes this sorter ranks.
	 *
	 * @param nonSortedKeys the keys handed to this sorter
	 * @return the keys to rank
	 */
	@Nonnull
	private Bitmap restrictToScopes(@Nonnull Bitmap nonSortedKeys) {
		if (this.scopeIndexes == null) {
			return nonSortedKeys;
		}
		final PersistentRoaringBitmap[] scopeKeys = new PersistentRoaringBitmap[this.scopeIndexes.length];
		for (int i = 0; i < this.scopeIndexes.length; i++) {
			scopeKeys[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(this.scopeIndexes[i].getAllPrimaryKeys());
		}
		final PersistentRoaringBitmap restricted = PersistentRoaringBitmap.and(
			RoaringBitmapBackedBitmap.getRoaringBitmap(nonSortedKeys),
			scopeKeys.length == 1 ? scopeKeys[0] : PersistentRoaringBitmap.or(scopeKeys)
		);
		return restricted.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(restricted);
	}

	/**
	 * Ranks the candidates and returns the best `topN` of them, best first.
	 *
	 * @param candidates the candidates, ascending
	 * @param topN       how many entities the page needs from this sorter, at least one
	 * @return the ranking
	 */
	@Nonnull
	private Ranking rank(@Nonnull int[] candidates, int topN) {
		final Result combined = FulltextScoreCombiner.combine(candidates, this.accessors, this.scoringPolicy);
		final Result selected = FulltextPhaseOneScorer.selectTopN(
			candidates, combined.composites(), topN, combined.postingsWalked()
		);
		return new Ranking(selected.primaryKeys(), selected.matchedDocuments() == selected.primaryKeys().length);
	}

	/**
	 * The outcome of ranking the candidates.
	 *
	 * @param primaryKeys the ranked entities, best first, at most the requested number
	 * @param complete    true when `primaryKeys` holds every candidate any accessor matched, false when more matched
	 *                    than were requested
	 */
	private record Ranking(@Nonnull int[] primaryKeys, boolean complete) {
		/**
		 * The ranking of no candidate.
		 */
		static final Ranking EMPTY = new Ranking(new int[0], true);
	}

}
