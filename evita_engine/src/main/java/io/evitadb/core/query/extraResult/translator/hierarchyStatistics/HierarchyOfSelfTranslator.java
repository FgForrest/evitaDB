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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics;

import io.evitadb.api.exception.TargetEntityIsNotHierarchicalException;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.hierarchy.predicate.FilteringFormulaHierarchyEntityPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.utils.Assert;

import java.util.Collections;
import java.util.List;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link HierarchyOfSelf} to
 * {@link HierarchyStatisticsProducer}. The producer instance has all pointer necessary to compute result.
 * All operations in this translator are relatively cheap comparing to final result computation, that is deferred to
 * {@link ExtraResultProducer#fabricate(List)} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyOfSelfTranslator
	extends AbstractHierarchyTranslator
	implements RequireConstraintTranslator<HierarchyOfSelf>, SelfTraversingTranslator {

	@Override
	public ExtraResultProducer apply(HierarchyOfSelf hierarchyOfSelf, ExtraResultPlanningVisitor extraResultPlanner) {
		final String queriedEntityType = extraResultPlanner.getSchema().getName();
		// verify that requested entityType is hierarchical
		final EntitySchemaContract entitySchema = extraResultPlanner.getSchema(queriedEntityType);
		Assert.isTrue(
			entitySchema.isWithHierarchy(),
			() -> new TargetEntityIsNotHierarchicalException(null, queriedEntityType));

		// prepare shared data from the context
		final EvitaRequest evitaRequest = extraResultPlanner.getEvitaRequest();
		final HierarchyFilterConstraint hierarchyWithin = evitaRequest.getHierarchyWithin(null);
		final EntityIndex globalIndex = extraResultPlanner.getGlobalEntityIndex(queriedEntityType);
		final Sorter sorter = hierarchyOfSelf.getOrderBy()
			.map(
				it -> extraResultPlanner.createSorter(
					it,
					globalIndex,
					queriedEntityType,
					() -> "Hierarchy statistics of `" + entitySchema.getName() + "`: " + it
				)
			)
			.orElse(null);

		// retrieve existing producer or create new one
		final HierarchyStatisticsProducer hierarchyStatisticsProducer = getHierarchyStatisticsProducer(
			extraResultPlanner
		);
		// we need to register producer prematurely
		extraResultPlanner.registerProducer(hierarchyStatisticsProducer);

		// the request is simple - we use global index of current entity
		hierarchyStatisticsProducer.interpret(
			entitySchema,
			null,
			extraResultPlanner.getAttributeSchemaAccessor(),
			hierarchyWithin,
			globalIndex,
			extraResultPlanner.getPrefetchRequirementCollector(),
			(nodeId, statisticsBase) -> {
				final FilterBy filter = statisticsBase == StatisticsBase.COMPLETE_FILTER ?
					extraResultPlanner.getFilterByWithoutHierarchyFilter(null) :
					extraResultPlanner.getFilterByWithoutHierarchyAndUserFilter(null);
				final Formula childrenExceptSelfFormula = FormulaFactory.not(
					new ConstantFormula(new BaseBitmap(nodeId)),
					globalIndex.getHierarchyNodesForParentFormula(nodeId)
				);
				if (filter == null || !filter.isApplicable()) {
					return childrenExceptSelfFormula;
				} else {
					final Formula baseFormula = extraResultPlanner.computeOnlyOnce(
						Collections.singletonList(globalIndex),
						filter,
						() -> createFilterFormula(
							extraResultPlanner.getQueryContext(),
							filter, globalIndex,
							extraResultPlanner.getAttributeSchemaAccessor()
						)
					);
					return FormulaFactory.and(
						baseFormula,
						childrenExceptSelfFormula
					);
				}
			},
			statisticsBase -> {
				final FilterBy filter = statisticsBase == StatisticsBase.COMPLETE_FILTER ?
					extraResultPlanner.getFilterByWithoutHierarchyFilter(null) :
					extraResultPlanner.getFilterByWithoutHierarchyAndUserFilter(null);
				if (filter == null || !filter.isApplicable()) {
					return HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;
				} else {
					final Formula baseFormula = extraResultPlanner.computeOnlyOnce(
						Collections.singletonList(globalIndex),
						filter,
						() -> createFilterFormula(
							extraResultPlanner.getQueryContext(),
							filter, globalIndex,
							extraResultPlanner.getAttributeSchemaAccessor()
						)
					);
					return new FilteringFormulaHierarchyEntityPredicate(
						filter, baseFormula
					);
				}
			},
			EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY,
			sorter,
			() -> {
				for (RequireConstraint child : hierarchyOfSelf) {
					child.accept(extraResultPlanner);
				}
			}
		);

		return hierarchyStatisticsProducer;
	}

}
