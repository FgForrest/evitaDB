/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.function.Functions;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link HierarchyOfSelf} to
 * {@link HierarchyStatisticsProducer}. The producer instance has all pointer necessary to compute result.
 * All operations in this translator are relatively cheap comparing to final result computation, that is deferred to
 * {@link ExtraResultProducer#fabricate(io.evitadb.core.query.QueryExecutionContext)} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyOfReferenceTranslator
	extends AbstractHierarchyTranslator
	implements RequireConstraintTranslator<HierarchyOfReference>, SelfTraversingTranslator {

	@Nullable
	@Override
	public ExtraResultProducer createProducer(@Nonnull HierarchyOfReference hierarchyOfReference, @Nonnull ExtraResultPlanningVisitor extraResultPlanner) {
		// prepare shared data from the context
		final EvitaRequest evitaRequest = extraResultPlanner.getEvitaRequest();
		final EntitySchema entitySchema = extraResultPlanner.getSchema();

		// retrieve existing producer or create new one
		final HierarchyStatisticsProducer hierarchyStatisticsProducer = getHierarchyStatisticsProducer(extraResultPlanner);
		// we need to register producer prematurely
		extraResultPlanner.registerProducer(hierarchyStatisticsProducer);

		for (String referenceName : hierarchyOfReference.getReferenceNames()) {
			final ReferenceSchemaContract referenceSchema = entitySchema
				.getReferenceOrThrowException(referenceName);
			final String entityType = referenceSchema.getReferencedEntityType();

			// verify that requested entityType is hierarchical
			final EntitySchemaContract referencedEntitySchema = extraResultPlanner.getSchema(entityType);
			Assert.isTrue(
				referencedEntitySchema.isWithHierarchy(),
				() -> new EntityIsNotHierarchicalException(referenceName, entityType));

			// verify that the referenced schema has its hierarchy indexed in the single requested scope
			final Scope scope = resolveSingleHierarchicalScope(
				extraResultPlanner.getProcessingScope(), referencedEntitySchema
			);

			final HierarchyFilterConstraint hierarchyWithin = evitaRequest.getHierarchyWithin(referenceName);
			final Optional<EntityCollection> targetCollectionRef = extraResultPlanner.getEntityCollection(entityType);
			final GlobalEntityIndex globalIndex = targetCollectionRef
				.map(entityCollection -> entityCollection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, scope)))
				.map(GlobalEntityIndex.class::cast)
				.orElse(null);

			if (globalIndex != null) {
				// safe: globalIndex != null implies the optional was present
				final EntityCollection targetCollection = targetCollectionRef.orElseThrow();
				final NestedContextSorter sorter = hierarchyOfReference.getOrderBy()
					.map(
						it -> extraResultPlanner.createSorter(
							it, null, targetCollection,
							() -> "Hierarchy statistics of `" + referencedEntitySchema.getName() + "`: " + it
						)
					)
					.orElse(null);

				// the request is more complex
				hierarchyStatisticsProducer.interpret(
					extraResultPlanner.getQueryContext()::getRootHierarchyNodes,
					referencedEntitySchema,
					referenceSchema,
					extraResultPlanner.getAttributeSchemaAccessor().withReferenceSchemaAccessor(referenceName),
					hierarchyWithin,
					globalIndex,
					null,
					// we need to access EntityIndexType.REFERENCED_HIERARCHY_NODE of the queried type to access
					// entity primary keys that are referencing the hierarchy entity
					(nodeId, statisticsBase) -> {
						// reuse the already-planned primary filter formula (memoised) instead of re-translating
						// the FilterBy per hierarchy node — the partitioned vs non-partitioned distinction
						// collapses because intersecting `cachedGlobal` with the reduced-index PKs yields the
						// same result regardless of how the index was carved up
						final Formula cachedGlobal = extraResultPlanner
							.getFilteringFormulaForStatisticsBase(statisticsBase, referenceSchema);
						final List<ReducedEntityIndex> reducedIndexes = extraResultPlanner
							.getQueryContext()
							.getReducedEntityIndexes(
								scope, nodeId, entitySchema, referenceSchema, Functions.noOpBiFunction()
							)
							.toList();

						final Formula[] formulas = new Formula[reducedIndexes.size()];
						if (cachedGlobal == null) {
							for (int i = 0; i < reducedIndexes.size(); i++) {
								formulas[i] = reducedIndexes.get(i).getAllPrimaryKeysFormula();
							}
						} else {
							for (int i = 0; i < reducedIndexes.size(); i++) {
								formulas[i] = FormulaFactory.and(
									reducedIndexes.get(i).getAllPrimaryKeysFormula(),
									cachedGlobal
								);
							}
						}
						return FormulaFactory.or(formulas);
					},
					null,
					hierarchyOfReference.getEmptyHierarchicalEntityBehaviour(),
					sorter,
					() -> {
						for (RequireConstraint child : hierarchyOfReference) {
							child.accept(extraResultPlanner);
						}
					}
				);
			}
		}
		return hierarchyStatisticsProducer;
	}

}
