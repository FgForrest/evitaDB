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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.predicate;

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.AttributeNotFoundException;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyFilteringPredicate;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyProducerContext;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyTraversalPredicate;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.index.bitmap.Bitmap;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;

import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.Assert.notNull;

/**
 * The predicate evaluates the nested query filter function to get the {@link Bitmap} of all hierarchy entity primary
 * keys that match the passed filtering constraint. It uses the result bitmap to resolve to decide output of the
 * predicate test method - for each key matching the computed result returns true, otherwise false.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class FilteredHierarchyEntityPredicate implements HierarchyFilteringPredicate, HierarchyTraversalPredicate {
	/**
	 * Formula computes id of all hierarchical entities that match input filter by constraint.
	 */
	private final Formula filteringFormula;

	public FilteredHierarchyEntityPredicate(@Nonnull HierarchyProducerContext context, @Nonnull FilterBy filterBy) {
		final QueryContext queryContext = context.queryContext();
		try {
			final String stepDescription = "Hierarchy statistics of `" + context.entitySchema().getName() + "`: " + filterBy.getChild().toString();
			queryContext.pushStep(
				QueryPhase.PLANNING_FILTER_NESTED_QUERY,
				stepDescription
			);
			// crete a visitor
			final FilterByVisitor theFilterByVisitor = new FilterByVisitor(
				queryContext,
				Collections.emptyList(),
				TargetIndexes.EMPTY,
				false
			);
			// now analyze the filter by in a nested context with exchanged primary entity index
			final Formula theFormula = theFilterByVisitor.executeInContext(
				Collections.singletonList(context.entityIndex()),
				null,
				null,
				null,
				null,
				(entitySchema, attributeName) -> {
					final AttributeSchemaContract attributeSchema = context.entitySchema().getAttribute(attributeName).orElse(null);
					notNull(
						attributeSchema,
						() -> new AttributeNotFoundException(attributeName, entitySchema)
					);
					isTrue(
						attributeSchema.isFilterable() || attributeSchema.isUnique(),
						() -> new AttributeNotFilterableException(attributeName, entitySchema)
					);
					return attributeSchema;
				},
				AttributesContract::getAttribute,
				() -> {
					filterBy.accept(theFilterByVisitor);
					// get the result and clear the visitor internal structures
					return theFilterByVisitor.getFormulaAndClear();
				}
			);
			// create a deferred formula that will log the execution time to query telemetry
			this.filteringFormula = new DeferredFormula(
				new FormulaWrapper(
					theFormula,
					formula -> {
						try {
							queryContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, stepDescription);
							return formula.compute();
						} finally {
							queryContext.popStep();
						}
					}
				)
			);
		} finally {
			queryContext.popStep();
		}
	}

	@Nullable
	@Override
	public Formula getFilteringFormula() {
		return filteringFormula;
	}

	@Override
	public boolean test(int hierarchyNodeId, int level, int distance) {
		return filteringFormula.compute().contains(hierarchyNodeId);
	}

	@Override
	public boolean test(int hierarchyNodeId) {
		return filteringFormula.compute().contains(hierarchyNodeId);
	}

}
