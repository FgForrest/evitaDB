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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.IntFunction;

/**
 * Carries the fulltext scores of an `entityHaving` (or `groupHaving`) nested query over to the owners of the matched
 * entities, so that `relevance()` of the owner query orders by them.
 *
 * ## Why it is not a node of the filter tree
 *
 * The nested query computes primary keys of the **target** collection. Its formula never stays visible in the owner's
 * filter tree: the owner-side path either materializes it into the reduced indexes of the matching targets, or hides
 * it behind a terminal `DeferredFormula`, on purpose - the planned tree of another collection's namespace must not be
 * walked by the dozens of owner-side lookups that match nodes by reference name, facet or accessor type. So the
 * `relevance()` sorter cannot find it with {@link FormulaFinder}, and must not: a target score applied to the owner of
 * the same primary key would be a score of the wrong entity. This accessor is registered on the owner's filter visitor
 * instead (`FilterByVisitor#registerReferencedFulltextScores`), and it answers in the **owner** primary-key space.
 *
 * ## How it scores
 *
 * {@link #getFulltextScores(int[], int)} computes the nested query's result - every matched target - and has each
 * score source of the nested filter rank all of them: the top N of the owners cannot be derived from a top N of the
 * targets, because the target order says nothing about how many candidate owners each target has. Every scored target
 * is expanded into its owners, the owners among the candidates keep the best composite of the targets they reference,
 * and the top N of them is selected in the scorer's own order (unsigned composite descending, ties by ascending
 * primary key). The cost is proportional to the matched targets times their owners, on top of scoring the targets.
 *
 * ## The composite stays a target composite
 *
 * The composites are passed through unchanged, in the layout {@link FulltextPhaseOneScorer} defines, so a score from
 * this source competes with the owner's own fulltext scores on equal terms - the sorter keeps the best composite per
 * owner. That is also the seam a later per-source weight hooks into: this source can be weighted like one more
 * field, presumably below the owner's own fields, without changing what it computes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReferenceOwnerFulltextScoreAccessor implements FulltextScoreAccessor {
	/**
	 * The nested query's filter, computing primary keys of the target collection; initialized by its planner.
	 */
	@Nonnull private final Formula targetFilter;
	/**
	 * The score sources of the nested filter, all providing scores, in the target primary-key space.
	 */
	@Nonnull private final FulltextScoreAccessor[] targetAccessors;
	/**
	 * Expands a target primary key into the primary keys of the owners referencing it.
	 */
	@Nonnull private final IntFunction<Bitmap> ownerLookup;

	/**
	 * Creates the accessor over a nested query's filter, or returns null when the filter holds no score source - a
	 * nested query without `textMatches` has nothing to carry over.
	 *
	 * @param targetFilter the nested query's filter, computing target primary keys
	 * @param ownerLookup  expands a target primary key into the owners referencing it
	 * @return the accessor, or null when there is nothing to score
	 */
	@Nullable
	public static ReferenceOwnerFulltextScoreAccessor create(
		@Nonnull Formula targetFilter,
		@Nonnull IntFunction<Bitmap> ownerLookup
	) {
		final List<FulltextScoreAccessor> accessors = new ArrayList<>(2);
		for (FulltextScoreAccessor accessor : FormulaFinder.find(
			targetFilter, FulltextScoreAccessor.class, LookUp.SHALLOW
		)) {
			if (accessor.providesFulltextScores()) {
				accessors.add(accessor);
			}
		}
		return accessors.isEmpty() ?
			null :
			new ReferenceOwnerFulltextScoreAccessor(
				targetFilter, accessors.toArray(FulltextScoreAccessor[]::new), ownerLookup
			);
	}

	/**
	 * Creates the accessor.
	 *
	 * @param targetFilter    the nested query's filter, computing target primary keys
	 * @param targetAccessors the score sources of the nested filter, never empty
	 * @param ownerLookup     expands a target primary key into the owners referencing it
	 */
	private ReferenceOwnerFulltextScoreAccessor(
		@Nonnull Formula targetFilter,
		@Nonnull FulltextScoreAccessor[] targetAccessors,
		@Nonnull IntFunction<Bitmap> ownerLookup
	) {
		Assert.isPremiseValid(targetAccessors.length > 0, "At least one target score source is expected!");
		this.targetFilter = targetFilter;
		this.targetAccessors = targetAccessors;
		this.ownerLookup = ownerLookup;
	}

	/**
	 * Returns the nested query's filter this accessor carries the scores of.
	 *
	 * @return the target filter
	 */
	@Nonnull
	public Formula getTargetFilter() {
		return this.targetFilter;
	}

	@Override
	public boolean providesFulltextScores() {
		return true;
	}

	@Nonnull
	@Override
	public Result getFulltextScores(@Nonnull int[] candidates, int topN) {
		Assert.isPremiseValid(topN > 0, () -> "The number of results must be positive, " + topN + " was requested!");
		final long[] composites = new long[candidates.length];
		long postingsWalked = 0L;
		final int[] targets = this.targetFilter.compute().getArray();
		if (targets.length > 0 && candidates.length > 0) {
			for (FulltextScoreAccessor targetAccessor : this.targetAccessors) {
				// every matched target is ranked - see the class documentation for why a top N would not do
				final Result targetScores = targetAccessor.getFulltextScores(targets, targets.length);
				postingsWalked += targetScores.postingsWalked();
				final int[] scoredTargets = targetScores.primaryKeys();
				final long[] targetComposites = targetScores.composites();
				for (int i = 0; i < scoredTargets.length; i++) {
					final long composite = targetComposites[i];
					final OfInt owners = this.ownerLookup.apply(scoredTargets[i]).iterator();
					while (owners.hasNext()) {
						final int index = Arrays.binarySearch(candidates, owners.nextInt());
						// an owner keeps the best composite of the targets it references
						if (index >= 0 && Long.compareUnsigned(composite, composites[index]) > 0) {
							composites[index] = composite;
						}
					}
				}
			}
		}
		return FulltextPhaseOneScorer.selectTopN(candidates, composites, topN, postingsWalked);
	}

	@Nonnull
	@Override
	public String toString() {
		return "FULLTEXT SCORES OF REFERENCED ENTITIES: " + this.targetFilter;
	}

}
