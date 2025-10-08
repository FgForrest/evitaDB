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

import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.extraResult.translator.facet.FilterFormulaFacetOptimizeVisitor;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Arrays;

/**
 * Single implementation of both interfaces {@link FacetCalculator} and {@link ImpactCalculator}. The class computes
 * facet counts of the entities returned for current {@link EvitaRequest}. The implementation tries to memoize all
 * possible intermediate calculations to save machine ticks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class MemoizingFacetCalculator implements FacetCalculator, ImpactCalculator {
	/**
	 * The query execution context to provide access to the schema and other necessary data.
	 */
	private final QueryExecutionContext executionContext;
	/**
	 * Contains filtering formula that was used to compute result of the {@link EvitaRequest}.
	 * The formula is converted to more optimal form that produces the same result but allows to memoize more
	 * intermediate calculations in the formula tree.
	 *
	 * @see FilterFormulaFacetOptimizeVisitor for more information
	 */
	private final Formula baseFormula;
	/**
	 * Contains filtering formula that has been stripped of user-defined filter.
	 */
	private final Formula baseFormulaWithoutUserFilter;
	/**
	 * Contains current match count (the base-line).
	 */
	private final int baseMatchCount;
	/**
	 * Contains instance of {@link FacetFormulaGenerator} that is reused for all calls. Visitors instances are usually
	 * created for single use and then thrown away but here we expect a lot of repeated computations for facets and
	 * reusing the same instance saves a little work for GC.
	 */
	private final FacetFormulaGenerator facetFormulaGenerator;
	/**
	 * Contains instance of {@link ImpactFormulaGenerator} that is reused for all calls. Visitors instances are usually
	 * created for single use and then thrown away but here we expect a lot of repeated computations for facets and
	 * reusing the same instance saves a little work for GC.
	 */
	private final ImpactFormulaGenerator impactFormulaGenerator;

	public MemoizingFacetCalculator(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Formula baseFormula,
		@Nonnull Formula baseFormulaWithoutUserFilter
	) {
		// first optimize formula to a form that utilizes memoization the most while adding new facet filters
		final Formula optimizedFormula = FilterFormulaFacetOptimizeVisitor.optimize(baseFormula);
		// now replace common parts of the formula with cached counterparts
		this.executionContext = queryContext;
		this.baseFormula = queryContext.analyse(optimizedFormula);
		this.baseFormulaWithoutUserFilter = baseFormulaWithoutUserFilter;
		this.baseMatchCount = baseFormula.compute().size();
		this.facetFormulaGenerator = new FacetFormulaGenerator(
			queryContext::isFacetGroupConjunction,
			queryContext::isFacetGroupDisjunction,
			queryContext::isFacetGroupNegation,
			queryContext::isFacetGroupExclusive
		);
		this.impactFormulaGenerator = new ImpactFormulaGenerator(
			queryContext::isFacetGroupConjunction,
			queryContext::isFacetGroupDisjunction,
			queryContext::isFacetGroupNegation,
			queryContext::isFacetGroupExclusive
		);
	}

	@Nullable
	@Override
	public RequestImpact calculateImpact(@Nonnull ReferenceSchemaContract referenceSchema, int facetId, @Nullable Integer facetGroupId, boolean required, @Nonnull Bitmap[] facetEntityIds) {
		// create formula that would capture the requested facet selected
		final Formula hypotheticalFormula = this.impactFormulaGenerator.generateFormula(
			this.baseFormula, this.baseFormulaWithoutUserFilter, referenceSchema, facetGroupId, facetId, facetEntityIds
		);
		// initialize the formula
		hypotheticalFormula.initialize(this.executionContext);
		// compute the hypothetical result
		final int hypotheticalCount = hypotheticalFormula.compute().size();
		// and return computed impact
		final int difference = hypotheticalCount - this.baseMatchCount;
		return new RequestImpact(
			difference,
			hypotheticalCount,
			hypotheticalCount > 0 &&
				(difference != 0 || this.impactFormulaGenerator.hasSenseAlone(hypotheticalFormula, referenceSchema, facetGroupId, facetId, facetEntityIds))
		);
	}

	@Nonnull
	@Override
	public Formula createCountFormula(@Nonnull ReferenceSchemaContract referenceSchema, int facetId, @Nullable Integer facetGroupId, @Nonnull Bitmap[] facetEntityIds) {
		// create formula that would capture all mandatory filtering constraints plus this single facet selected
		final Formula hypotheticalFormula = this.facetFormulaGenerator.generateFormula(
			this.baseFormula, this.baseFormulaWithoutUserFilter, referenceSchema, facetGroupId, facetId, facetEntityIds
		);
		// initialize the formula
		hypotheticalFormula.initialize(this.executionContext);
		return hypotheticalFormula;
	}

	@Nonnull
	@Override
	public Formula createGroupCountFormula(@Nonnull ReferenceSchemaContract referenceSchema, @Nullable Integer facetGroupId, @Nonnull Bitmap[] facetEntityIds) {
		final Formula allFacetEntityIds = FormulaFactory.or(
			Arrays.stream(facetEntityIds)
				.map(ConstantFormula::new)
				.toArray(Formula[]::new)
		);
		final Formula hypotheticalFormula;
		if (this.baseFormulaWithoutUserFilter == null) {
			hypotheticalFormula = allFacetEntityIds;
		} else {
			hypotheticalFormula = FormulaFactory.and(
				this.baseFormulaWithoutUserFilter,
				allFacetEntityIds
			);
		}
		// initialize the formula
		hypotheticalFormula.initialize(this.executionContext);
		return hypotheticalFormula;
	}

}
