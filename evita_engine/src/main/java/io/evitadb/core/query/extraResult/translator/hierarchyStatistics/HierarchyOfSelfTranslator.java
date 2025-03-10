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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics;

import io.evitadb.api.exception.EntityIsNotHierarchicalException;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.exception.HierarchyNotIndexedException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.hierarchy.predicate.FilteringFormulaHierarchyEntityPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link HierarchyOfSelf} to
 * {@link HierarchyStatisticsProducer}. The producer instance has all pointer necessary to compute result.
 * All operations in this translator are relatively cheap comparing to final result computation, that is deferred to
 * {@link ExtraResultProducer#fabricate(io.evitadb.core.query.QueryExecutionContext)} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyOfSelfTranslator
	extends AbstractHierarchyTranslator
	implements RequireConstraintTranslator<HierarchyOfSelf>, SelfTraversingTranslator {

	@Nullable
	@Override
	public ExtraResultProducer createProducer(@Nonnull HierarchyOfSelf hierarchyOfSelf, @Nonnull ExtraResultPlanningVisitor extraResultPlanner) {
		final EntitySchemaContract queriedSchema = extraResultPlanner.getSchema();
		final String queriedEntityType = queriedSchema.getName();
		// verify that requested entityType is hierarchical
		Assert.isTrue(
			queriedSchema.isWithHierarchy(),
			() -> new EntityIsNotHierarchicalException(null, queriedEntityType));

		// prepare shared data from the context
		final EvitaRequest evitaRequest = extraResultPlanner.getEvitaRequest();
		final HierarchyFilterConstraint hierarchyWithin = evitaRequest.getHierarchyWithin(null);

		// retrieve existing producer or create new one
		final HierarchyStatisticsProducer hierarchyStatisticsProducer = getHierarchyStatisticsProducer(
			extraResultPlanner
		);
		// we need to register producer prematurely
		extraResultPlanner.registerProducer(hierarchyStatisticsProducer);

		// verify that the reference has hierarchy index in requested scopes
		final ProcessingScope processingScope = extraResultPlanner.getProcessingScope();
		final Set<Scope> scopes = processingScope.getScopes();
		// hierarchy cannot be produced from multiple scopes
		if (scopes.size() > 1) {
			throw new EvitaInvalidUsageException(
				"Hierarchies of `" + queriedSchema.getName() + "` from multiple scopes cannot be combined. " +
					"They represent two distinct trees."
			);
		}
		// so, there would be only single scope to check for hierarchy index
		final Scope scope = scopes.iterator().next();
		Assert.isTrue(
			queriedSchema.isHierarchyIndexedInScope(scope),
			() -> new HierarchyNotIndexedException(queriedSchema, scope)
		);

		final Optional<EntityCollection> targetCollectionRef = extraResultPlanner.getEntityCollection(queriedEntityType);
		final GlobalEntityIndex globalIndex = targetCollectionRef
			.map(entityCollection -> entityCollection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, scope)))
			.map(GlobalEntityIndex.class::cast)
			.orElse(null);
		if (globalIndex != null) {
			final NestedContextSorter sorter = hierarchyOfSelf.getOrderBy()
				.map(
					it -> extraResultPlanner.createSorter(
						it,
						null,
						targetCollectionRef.get(),
						queriedEntityType,
						() -> "Hierarchy statistics of `" + queriedEntityType + "`: " + it
					)
				)
				.orElse(null);

			// the request is simple - we use global index of current entity
			hierarchyStatisticsProducer.interpret(
				extraResultPlanner.getQueryContext()::getRootHierarchyNodes,
				queriedSchema,
				null,
				extraResultPlanner.getAttributeSchemaAccessor(),
				hierarchyWithin,
				globalIndex,
				extraResultPlanner.getFetchRequirementCollector(),
				(nodeId, statisticsBase) -> {
					final FilterBy filter = extraResultPlanner.getFilterByForStatisticsBase(statisticsBase, null);
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
								filter,
								GlobalEntityIndex.class,
								queriedSchema,
								globalIndex,
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
					final FilterBy filter = extraResultPlanner.getFilterByForStatisticsBase(statisticsBase, null);
					if (filter == null || !filter.isApplicable()) {
						return HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;
					} else {
						final Formula baseFormula = extraResultPlanner.computeOnlyOnce(
							Collections.singletonList(globalIndex),
							filter,
							() -> createFilterFormula(
								extraResultPlanner.getQueryContext(),
								filter,
								GlobalEntityIndex.class,
								queriedSchema,
								globalIndex,
								extraResultPlanner.getAttributeSchemaAccessor()
							)
						);
						return new FilteringFormulaHierarchyEntityPredicate(
							queriedEntityType, scopes, filter, baseFormula
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
		}

		return hierarchyStatisticsProducer;
	}

}
