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
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.exception.HierarchyNotIndexedException;
import io.evitadb.core.query.algebra.base.EmptyFormula;
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
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
public class HierarchyOfReferenceTranslator
	extends AbstractHierarchyTranslator
	implements RequireConstraintTranslator<HierarchyOfReference>, SelfTraversingTranslator {

	@Nonnull
	private static EntityIndexKey createReferencedHierarchyIndexKey(@Nonnull String referenceName, int hierarchyNodeId) {
		return new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, new ReferenceKey(referenceName, hierarchyNodeId));
	}

	@Nullable
	@Override
	public ExtraResultProducer createProducer(@Nonnull HierarchyOfReference hierarchyOfReference, @Nonnull ExtraResultPlanningVisitor extraResultPlanner) {
		// prepare shared data from the context
		final EvitaRequest evitaRequest = extraResultPlanner.getEvitaRequest();
		final EntitySchema entitySchema = extraResultPlanner.getSchema();
		final String queriedEntityType = entitySchema.getName();

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

			// verify that the reference has hierarchy index in requested scopes
			final ProcessingScope processingScope = extraResultPlanner.getProcessingScope();
			final Set<Scope> scopes = processingScope.getScopes();
			// hierarchy cannot be produced from multiple scopes
			if (scopes.size() > 1) {
				throw new EvitaInvalidUsageException(
					"Hierarchies of `" + referencedEntitySchema.getName() + "` from multiple scopes cannot be combined. " +
						"They represent two distinct trees."
				);
			}
			// so, there would be only single scope to check for hierarchy index
			final Scope scope = scopes.iterator().next();
			Assert.isTrue(
				referencedEntitySchema.isHierarchyIndexedInScope(scope),
				() -> new HierarchyNotIndexedException(entitySchema, scope)
			);

			final HierarchyFilterConstraint hierarchyWithin = evitaRequest.getHierarchyWithin(referenceName);
			final Optional<EntityCollection> targetCollectionRef = extraResultPlanner.getEntityCollection(entityType);
			final GlobalEntityIndex globalIndex = targetCollectionRef
				.map(entityCollection -> entityCollection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, scope)))
				.map(GlobalEntityIndex.class::cast)
				.orElse(null);

			if (globalIndex != null) {
				final NestedContextSorter sorter = hierarchyOfReference.getOrderBy()
					.map(
						it -> extraResultPlanner.createSorter(
							it, null, targetCollectionRef.get(),
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
					(nodeId, statisticsBase) ->
						extraResultPlanner.getIndex(queriedEntityType, createReferencedHierarchyIndexKey(referenceName, nodeId), ReducedEntityIndex.class)
							.map(hierarchyIndex -> {
								final FilterBy filter = extraResultPlanner.getFilterByForStatisticsBase(statisticsBase, referenceSchema);
								if (filter == null || !filter.isApplicable()) {
									return hierarchyIndex.getAllPrimaryKeysFormula();
								} else {
									return createFilterFormula(
										extraResultPlanner.getQueryContext(),
										filter,
										ReducedEntityIndex.class,
										extraResultPlanner.getSchema(queriedEntityType),
										hierarchyIndex,
										extraResultPlanner.getAttributeSchemaAccessor()
									);
								}
							})
							.orElse(EmptyFormula.INSTANCE),
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
