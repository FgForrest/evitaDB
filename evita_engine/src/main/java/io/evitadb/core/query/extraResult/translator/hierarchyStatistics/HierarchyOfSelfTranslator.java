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
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.AttributeNotFoundException;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.translator.EntityAttributeExtractor;
import io.evitadb.index.EntityIndex;
import io.evitadb.utils.Assert;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.Assert.notNull;

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
			.map(it -> createSorter(extraResultPlanner, it, globalIndex))
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
			hierarchyWithin,
			globalIndex,
			extraResultPlanner.getPrefetchRequirementCollector(),
			globalIndex::getHierarchyNodesForParentFormula,
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

	private Sorter createSorter(ExtraResultPlanningVisitor extraResultPlanner, OrderBy orderBy, EntityIndex entityIndex) {
		final QueryContext queryContext = extraResultPlanner.getQueryContext();
		try {
			final Supplier<String> stepDescriptionSupplier = () -> "Hierarchy statistics of `" + entityIndex.getEntitySchema().getName() + "`: " +
				Arrays.stream(orderBy.getChildren()).map(Object::toString).collect(Collectors.joining(", "));
			queryContext.pushStep(
				QueryPhase.PLANNING_FILTER_NESTED_QUERY,
				stepDescriptionSupplier
			);
			// crete a visitor
			final OrderByVisitor orderByVisitor = new OrderByVisitor(
				queryContext,
				extraResultPlanner.getPrefetchRequirementCollector(),
				extraResultPlanner.getFilteringFormula()
			);
			// now analyze the filter by in a nested context with exchanged primary entity index
			final Sorter sorter = orderByVisitor.executeInContext(
				entityIndex,
				(attributeName) -> {
					final AttributeSchemaContract attributeSchema = queryContext.getSchema()
						.getAttribute(attributeName)
						.orElse(null);
					notNull(
						attributeSchema,
						() -> new AttributeNotFoundException(attributeName, queryContext.getSchema())
					);
					isTrue(
						attributeSchema.isFilterable() || attributeSchema.isUnique(),
						() -> new AttributeNotFilterableException(attributeName, queryContext.getSchema())
					);
				},
				EntityAttributeExtractor.INSTANCE,
				() -> {
					for (OrderConstraint innerConstraint : orderBy.getChildren()) {
						innerConstraint.accept(orderByVisitor);
					}
					return orderByVisitor.getLastUsedSorter();
				}
			);
			// create a deferred formula that will log the execution time to query telemetry
			/* TODO JNO - nějak dodělat defer */
//			this.filteringFormula = new DeferredFormula(
//				new FormulaWrapper(
//					theFormula,
//					formula -> {
//						try {
//							queryContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, stepDescriptionSupplier);
//							return formula.compute();
//						} finally {
//							queryContext.popStep();
//						}
//					}
//				)
//			);
			return sorter;
		} finally {
			queryContext.popStep();
		}
	}

}
