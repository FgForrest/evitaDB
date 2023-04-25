/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.extraResult.translator.facet.producer;

import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * This implementation contains the heavy part of {@link ImpactCalculator} interface implementation. It computes
 * {@link RequestImpact} data for each facet that is assigned to any of the product matching current
 * {@link EvitaRequest}. The impact captures the situation how many entities would be added/removed if the facet had
 * been selected.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class ImpactFormulaGenerator extends AbstractFacetFormulaGenerator {
	/**
	 * Contains true if visitor found {@link FacetGroupFormula} in the existing formula that matches the same facet
	 * `entityType` and `facetGroupId`.
	 */
	private boolean foundTargetInUserFilter;

	public ImpactFormulaGenerator(
		@Nonnull BiPredicate<ReferenceSchemaContract, Integer> isFacetGroupConjunction,
		@Nonnull BiPredicate<ReferenceSchemaContract, Integer> isFacetGroupDisjunction,
		@Nonnull BiPredicate<ReferenceSchemaContract, Integer> isFacetGroupNegation
	) {
		super(isFacetGroupConjunction, isFacetGroupDisjunction, isFacetGroupNegation);
	}

	@Override
	public Formula generateFormula(
		@Nonnull Formula baseFormula,
		@Nonnull Formula baseFormulaWithoutUserFilter,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer facetGroupId,
		int facetId,
		@Nonnull Bitmap[] facetEntityIds
	) {
		try {
			return super.generateFormula(
				baseFormula, baseFormulaWithoutUserFilter, referenceSchema, facetGroupId, facetId, facetEntityIds
			);
		} finally {
			// clear the flag upon leaving this method in a safe manner
			this.foundTargetInUserFilter = false;
		}
	}

	@Override
	protected boolean handleFormula(@Nonnull Formula formula) {
		// if the examined formula is facet group formula matching the same facet `entityType` and `facetGroupId`
		if (isInsideUserFilter() && formula instanceof FacetGroupFormula &&
			Objects.equals(referenceSchema.getName(), ((FacetGroupFormula) formula).getReferenceName()) &&
			Objects.equals(facetGroupId, ((FacetGroupFormula) formula).getFacetGroupId())
		) {
			// we found the facet group formula - we need to enrich it with new facet
			storeFormula(
				((FacetGroupFormula) formula).getCloneWithFacet(facetId, facetEntityIds)
			);
			// switch the signalization flag
			foundTargetInUserFilter = true;
			// we've stored the formula - instruct super method to skip it's handling
			return true;
		}
		// let the upper implementation handle the formula
		return false;
	}

	@Override
	protected boolean handleUserFilter(@Nonnull Formula formula, @Nonnull Formula[] updatedChildren) {
		if (!foundTargetInUserFilter) {
			// there was no FacetGroupFormula inside - we have to create brand new one and add it before leaving user filter
			return super.handleUserFilter(formula, updatedChildren);
		} else {
			// we've already enriched existing formula with new formula - let the logic continue without modification
			return false;
		}
	}

}
