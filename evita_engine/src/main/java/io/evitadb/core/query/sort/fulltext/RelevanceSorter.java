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

import com.carrotsearch.hppc.IntLongHashMap;
import com.carrotsearch.hppc.cursors.IntLongCursor;
import io.evitadb.core.query.algebra.fulltext.FulltextScoreAccessor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Orders the entities by the fulltext relevance the `textMatches` filter computes - the sorter of `relevance()`.
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
 * The page ends at `endIndex`, so the accessors are asked for the best `endIndex` entities (counted from where this
 * sorter starts) instead of a sorted match set: the first page of twenty out of a million matches selects twenty.
 * Entities preceding the page are reported to the skipped-records consumer, the page is written, and the entities
 * no accessor ranked - the ones the filter admitted without a fulltext match, e.g. through another branch of an `or` -
 * are handed on unsorted to the next sorter. When the accessors matched more entities than the page reaches, the page
 * is full and nothing is handed on.
 *
 * ## Several accessors
 *
 * Each scope's `textMatches` yields its own formula, so a LIVE + ARCHIVED query has two accessors over disjoint
 * entities, and two `textMatches` in one filter - or a `textMatches` beside an `entityHaving` carrying one - have two
 * accessors over overlapping ones. Each accessor ranks its own top N, an entity ranked by several keeps its best
 * composite, and the union is ordered by composite (unsigned, best first), ties by ascending primary key - the
 * scorer's own rule. The merge is exact: an entity in the true top N of the merged order is in the top N of the
 * accessor that gave it its best composite.
 *
 * ## Scope restriction
 *
 * Inside `inScope(...)` of the ordering only the entities of the named scopes are ranked; every other entity is handed
 * on unsorted. An entity lives in exactly one scope, so the restriction is the union of the primary keys of the global
 * indexes of those scopes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class RelevanceSorter implements Sorter {
	/**
	 * The accessors providing fulltext scores, distinct by identity, never empty.
	 */
	@Nonnull private final FulltextScoreAccessor[] accessors;
	/**
	 * Global indexes of the scopes whose entities are ranked, or null when the entities of every scope are.
	 */
	@Nullable private final EntityIndex[] scopeIndexes;

	/**
	 * Creates the sorter.
	 *
	 * @param accessors    the accessors providing fulltext scores - each must answer
	 *                     {@link FulltextScoreAccessor#providesFulltextScores()} with true
	 * @param scopeIndexes global indexes of the scopes whose entities are ranked, or null to rank the entities of every
	 *                     scope
	 */
	public RelevanceSorter(@Nonnull FulltextScoreAccessor[] accessors, @Nullable EntityIndex[] scopeIndexes) {
		Assert.isPremiseValid(accessors.length > 0, "The relevance sorter needs at least one score accessor!");
		this.accessors = accessors;
		this.scopeIndexes = scopeIndexes;
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
		if (this.accessors.length == 1) {
			final Result scores = this.accessors[0].getFulltextScores(candidates, topN);
			return new Ranking(scores.primaryKeys(), scores.matchedDocuments() == scores.primaryKeys().length);
		}
		// an entity ranked by several accessors keeps its best composite
		final IntLongHashMap best = new IntLongHashMap(Math.min(topN, candidates.length) * this.accessors.length);
		boolean complete = true;
		for (FulltextScoreAccessor accessor : this.accessors) {
			final Result scores = accessor.getFulltextScores(candidates, topN);
			final int[] primaryKeys = scores.primaryKeys();
			final long[] composites = scores.composites();
			for (int i = 0; i < primaryKeys.length; i++) {
				final int index = best.indexOf(primaryKeys[i]);
				if (index < 0) {
					best.indexInsert(index, primaryKeys[i], composites[i]);
				} else if (Long.compareUnsigned(composites[i], best.indexGet(index)) > 0) {
					best.indexReplace(index, composites[i]);
				}
			}
			complete &= scores.matchedDocuments() == primaryKeys.length;
		}
		final RankedEntity[] merged = new RankedEntity[best.size()];
		int index = 0;
		for (IntLongCursor cursor : best) {
			merged[index++] = new RankedEntity(cursor.key, cursor.value);
		}
		Arrays.sort(merged);
		final int[] primaryKeys = new int[Math.min(topN, merged.length)];
		for (int i = 0; i < primaryKeys.length; i++) {
			primaryKeys[i] = merged[i].primaryKey();
		}
		// the union of complete rankings is complete only while it still fits the page
		return new Ranking(primaryKeys, complete && merged.length <= topN);
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

	/**
	 * An entity with its composite score, ordered best first: by composite compared unsigned, as
	 * {@link FulltextPhaseOneScorer} defines it, descending, ties by ascending primary key.
	 *
	 * @param primaryKey primary key of the entity
	 * @param composite  its best composite
	 */
	private record RankedEntity(int primaryKey, long composite) implements Comparable<RankedEntity> {

		@Override
		public int compareTo(@Nonnull RankedEntity other) {
			final int byComposite = Long.compareUnsigned(other.composite, this.composite);
			return byComposite == 0 ? Integer.compare(this.primaryKey, other.primaryKey) : byComposite;
		}

	}

}
