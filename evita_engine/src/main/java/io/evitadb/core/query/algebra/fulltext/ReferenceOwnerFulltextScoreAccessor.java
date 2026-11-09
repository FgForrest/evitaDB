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
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import io.evitadb.index.fulltext.FulltextScoringPolicy;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Carries the fulltext scores of an `entityHaving` (or `groupHaving`) nested query over to the owners of the matched
 * entities, so that `relevanceText()` of the owner query orders by them.
 *
 * ## Why it is not a node of the filter tree
 *
 * The nested query computes primary keys of the **target** collection. Its formula never stays visible in the owner's
 * filter tree: the owner-side path either materializes it into the reduced indexes of the matching targets, or hides
 * it behind a terminal `DeferredFormula`, on purpose - the planned tree of another collection's namespace must not be
 * walked by the dozens of owner-side lookups that match nodes by reference name, facet or accessor type. So the
 * `relevanceText()` sorter cannot find it with {@link FormulaFinder}, and must not: a target score applied to the
 * owner of the same primary key would be a score of the wrong entity. This accessor is registered on the owner's
 * filter visitor instead (`FilterByVisitor#registerReferencedFulltextScores`), and it answers in the **owner**
 * primary-key space.
 *
 * ## How it scores
 *
 * Each target first combines its own places and sources with the query's tie breaker. Targets of this reference
 * place then collapse to their best strength; the owner receives that strength weighted by the engine default 0.5.
 * Quality remains the best raw target quality, independently of strength and weight. Multiple targets therefore
 * never accumulate extra strength. Every candidate owner receives an aligned composite, including zero for no hit;
 * the sorter combines this reference place with the owner's other sources before selecting the page.
 * Entity and group mappings of one reference are separate places, as are distinct originating nested constraints.
 * Only scope copies of the same constraint and mapping collapse, after each copy applies its own 0.5 weight.
 * Only the target's own `textMatches` sources combine here. Its registered `entityHaving` / `groupHaving`
 * sources are not carried through a second reference level: for Product -> Brand -> Country, Country scores
 * do not reach Product through Brand.
 * The cost is proportional to the matched targets times their owners, on top of scoring the targets.
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
	/** Query coefficient shared with target-place and owner-source combination. */
	@Nonnull private final FulltextScoringPolicy scoringPolicy;

	/**
	 * Creates the accessor over a nested query's filter, or returns null when the filter holds no score source - a
	 * nested query without `textMatches` has nothing to carry over.
	 *
	 * @param targetFilter the nested query's filter, computing target primary keys
	 * @param ownerLookup  expands a target primary key into the owners referencing it
	 * @param scoringPolicy supplies the effective policy only after a score source is found
	 * @return the accessor, or null when there is nothing to score
	 */
	@Nullable
	public static ReferenceOwnerFulltextScoreAccessor create(
		@Nonnull Formula targetFilter,
		@Nonnull IntFunction<Bitmap> ownerLookup,
		@Nonnull Supplier<FulltextScoringPolicy> scoringPolicy
	) {
		final List<FulltextScoreAccessor> accessors = new ArrayList<>(2);
		for (final FulltextScoreAccessor accessor : FormulaFinder.find(
			targetFilter, FulltextScoreAccessor.class, LookUp.SHALLOW
		)) {
			if (accessor.providesFulltextScores()) {
				accessors.add(accessor);
			}
		}
		return accessors.isEmpty() ?
			null :
			new ReferenceOwnerFulltextScoreAccessor(
				targetFilter, accessors.toArray(FulltextScoreAccessor[]::new), ownerLookup, scoringPolicy.get()
			);
	}

	/**
	 * Creates the accessor.
	 *
	 * @param targetFilter    the nested query's filter, computing target primary keys
	 * @param targetAccessors the score sources of the nested filter, never empty
	 * @param ownerLookup     expands a target primary key into the owners referencing it
	 * @param scoringPolicy effective query coefficient
	 */
	private ReferenceOwnerFulltextScoreAccessor(
		@Nonnull Formula targetFilter,
		@Nonnull FulltextScoreAccessor[] targetAccessors,
		@Nonnull IntFunction<Bitmap> ownerLookup,
		@Nonnull FulltextScoringPolicy scoringPolicy
	) {
		Assert.isPremiseValid(targetAccessors.length > 0, "At least one target score source is expected!");
		this.targetFilter = targetFilter;
		this.targetAccessors = targetAccessors;
		this.ownerLookup = ownerLookup;
		this.scoringPolicy = scoringPolicy;
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
	public Result getFulltextScores(@Nonnull int[] candidates) {
		final long[] composites = new long[candidates.length];
		final int[] targets = this.targetFilter.compute().getArray();
		final Result targetScores = FulltextScoreCombiner.combine(
			targets, this.targetAccessors, this.scoringPolicy
		);
		for (int i = 0; i < targets.length; i++) {
			final long composite = targetScores.composites()[i];
			if (composite == 0) {
				continue;
			}
			final OfInt owners = this.ownerLookup.apply(targets[i]).iterator();
			while (owners.hasNext()) {
				final int index = Arrays.binarySearch(candidates, owners.nextInt());
				if (index >= 0) {
					composites[index] = FulltextScoreCombiner.mergeBest(composite, composites[index]);
				}
			}
		}
		int matched = 0;
		for (int i = 0; i < composites.length; i++) {
			if (composites[i] != 0) {
				matched++;
				// Multiplication by the engine default 0.5, rounded half up in fixed-point units.
				final int strength = FulltextScoringPolicy.weightReferenceStrength(
					FulltextScoringPolicy.strength(composites[i])
				);
				composites[i] = (composites[i] & FulltextScoringPolicy.QUALITY_MASK) |
					((long) strength << FulltextScoringPolicy.STRENGTH_SHIFT);
			}
		}
		return new Result(candidates, composites, matched, targetScores.postingsWalked());
	}

	@Nonnull
	@Override
	public String toString() {
		return "FULLTEXT SCORES OF REFERENCED ENTITIES: " + this.targetFilter;
	}

}
