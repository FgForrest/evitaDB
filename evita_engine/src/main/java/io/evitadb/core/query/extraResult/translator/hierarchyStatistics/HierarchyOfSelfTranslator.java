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
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.dataType.Scope;
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

		// verify that the queried schema has its hierarchy indexed in the single requested scope
		final Scope scope = resolveSingleHierarchicalScope(extraResultPlanner.getProcessingScope(), queriedSchema);
		final Set<Scope> scopes = Collections.singleton(scope);

		final Optional<EntityCollection> targetCollectionRef = extraResultPlanner.getEntityCollection(queriedEntityType);
		final GlobalEntityIndex globalIndex = targetCollectionRef
			.map(entityCollection -> entityCollection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, scope)))
			.map(GlobalEntityIndex.class::cast)
			.orElse(null);
		if (globalIndex != null) {
			final NestedContextSorter sorter = hierarchyOfSelf.getOrderBy()
				.map(
					it -> extraResultPlanner.createSorter(
						it, null, targetCollectionRef.get(),
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
					// reuse the already-planned primary filter formula (memoised) instead of re-translating
					// the FilterBy per hierarchy node
					final Formula cachedGlobal = extraResultPlanner
						.getFilteringFormulaForStatisticsBase(statisticsBase, null);
					final Formula childrenExceptSelfFormula = FormulaFactory.not(
						new ConstantFormula(new BaseBitmap(nodeId)),
						globalIndex.getHierarchyNodesForParentFormula(nodeId)
					);
					if (cachedGlobal == null) {
						return childrenExceptSelfFormula;
					}
					return FormulaFactory.and(cachedGlobal, childrenExceptSelfFormula);
				},
				statisticsBase -> {
					final Formula cachedGlobal = extraResultPlanner
						.getFilteringFormulaForStatisticsBase(statisticsBase, null);
					if (cachedGlobal == null) {
						return HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;
					}
					return new FilteringFormulaHierarchyEntityPredicate(
						queriedEntityType, scopes, cachedGlobal
					);
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
