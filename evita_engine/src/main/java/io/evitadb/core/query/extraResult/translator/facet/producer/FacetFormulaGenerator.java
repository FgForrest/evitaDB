/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.query.extraResult.translator.facet.producer;

import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.query.require.FacetRelationType;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.FacetGroupAndFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupOrFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Map;

import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;

/**
 * This implementation contains the heavy part of {@link FacetCalculator} interface implementation. It computes how many
 * entities posses the specified facet respecting current {@link EvitaRequest} filtering query except contents
 * of the {@link UserFilter}. It means that it respects all mandatory filtering constraints which gets enriched by
 * additional query that represents single facet. The result of the query represents the number of products having
 * such facet.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class FacetFormulaGenerator extends AbstractFacetFormulaGenerator {
	/**
	 * Contains cache for already generated formulas indexed by a {@link FacetRelationType} that distinguishes the key
	 * situations where the formulas have to have different shape and structure.
	 */
	private final Map<FacetRelationType, Formula> cache = CollectionUtils.createHashMap(64);

	public FacetFormulaGenerator(
		@Nonnull FacetGroupRelationTypeResolver isFacetGroupConjunction,
		@Nonnull FacetGroupRelationTypeResolver isFacetGroupDisjunction,
		@Nonnull FacetGroupRelationTypeResolver isFacetGroupNegation,
		@Nonnull FacetGroupRelationTypeResolver isFacetGroupExclusive
	) {
		super(isFacetGroupConjunction, isFacetGroupDisjunction, isFacetGroupNegation, isFacetGroupExclusive);
	}

	@Nonnull
	@Override
	public Formula generateFormula(
		@Nonnull Formula baseFormula,
		@Nonnull Formula baseFormulaWithoutUserFilter,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer facetGroupId,
		int facetId,
		@Nonnull Bitmap[] facetEntityIds
	) {
		final FacetRelationType relationType = getFacetRelationType(
			referenceSchema, WITH_DIFFERENT_FACETS_IN_GROUP, FacetRelationType.DISJUNCTION, facetGroupId
		);
		return this.cache.compute(
			relationType,
			(cacheKey, formula) -> {
				if (formula == null) {
					return super.generateFormula(baseFormula, baseFormulaWithoutUserFilter, referenceSchema, facetGroupId, facetId, facetEntityIds);
				} else {
					final Bitmap facetEntityIdsBitmap = getBaseEntityIds(facetEntityIds);
					final MutableFormulaFinderAndReplacer mutableFormulaFinderAndReplacer = new MutableFormulaFinderAndReplacer(
						() -> cacheKey == FacetRelationType.CONJUNCTION ?
							new FacetGroupAndFormula(referenceSchema.getName(), facetGroupId, new BaseBitmap(facetId), facetEntityIdsBitmap) :
							new FacetGroupOrFormula(referenceSchema.getName(), facetGroupId, new BaseBitmap(facetId), facetEntityIdsBitmap)
					);
					formula.accept(mutableFormulaFinderAndReplacer);
					Assert.isPremiseValid(mutableFormulaFinderAndReplacer.isTargetFound(), "Expected single MutableFormula in the formula tree!");
					return formula;
				}
			}
		);
	}

	@Override
	protected boolean shouldIncludeChildren(boolean isUserFilter) {
		// this implementation skips former contents of the user filter formula
		// (it just adds single new facet formula for the computation
		return !isUserFilter;
	}

	@Nonnull
	@Override
	protected Formula getResult(@Nonnull Formula baseFormula) {
		Assert.isPremiseValid(this.result != null, "Result formula must be set!");
		// if the output is same as input, it means the input didn't contain UserFilterFormula
		if (this.result == baseFormula) {
			// so we need to change it here adding new facet group formula
			if (this.isFacetGroupNegation.test(this.referenceSchema, this.facetGroupId, WITH_DIFFERENT_FACETS_IN_GROUP)) {
				return FormulaFactory.not(
					createNewFacetGroupFormula(),
					baseFormula
				);
			} else {
				return FormulaFactory.and(
					baseFormula,
					createNewFacetGroupFormula()
				);
			}
		} else {
			// output changed - just propagate it
			return this.result;
		}
	}

}
