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
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyStatisticsProducer;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link HierarchyOfSelf} to
 * {@link HierarchyStatisticsProducer}. The producer instance has all pointer necessary to compute result.
 * All operations in this translator are relatively cheap comparing to final result computation, that is deferred to
 * {@link ExtraResultProducer#fabricate(List)} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyStatisticsOfReferenceTranslator implements RequireConstraintTranslator<HierarchyOfReference>, SelfTraversingTranslator {

	@Nonnull
	private static EntityIndexKey createReferencedHierarchyIndexKey(@Nonnull String referenceName, int hierarchyNodeId) {
		return new EntityIndexKey(EntityIndexType.REFERENCED_HIERARCHY_NODE, new ReferenceKey(referenceName, hierarchyNodeId));
	}

	@Nonnull
	private static HierarchyStatisticsProducer getHierarchyStatisticsProducer(
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner,
		@Nullable Locale language,
		@Nonnull Formula filteringFormula
	) {
		return ofNullable(extraResultPlanner.findExistingProducer(HierarchyStatisticsProducer.class))
			.orElseGet(() -> new HierarchyStatisticsProducer(extraResultPlanner.getQueryContext(), language, filteringFormula));
	}

	@Override
	public ExtraResultProducer apply(HierarchyOfReference hierarchyStatsConstraint, ExtraResultPlanningVisitor extraResultPlanner) {
		// prepare shared data from the context
		final EvitaRequest evitaRequest = extraResultPlanner.getEvitaRequest();
		final String queriedEntityType = extraResultPlanner.getSchema().getName();
		// retrieve existing producer or create new one
		final HierarchyStatisticsProducer hierarchyStatisticsProducer = getHierarchyStatisticsProducer(
			extraResultPlanner, evitaRequest.getLocale(), extraResultPlanner.getFilteringFormula()
		);

		for (String referenceName : hierarchyStatsConstraint.getReferenceNames()) {
			final String entityType = extraResultPlanner.getSchema()
				.getReferenceOrThrowException(referenceName)
				.getReferencedEntityType();

			// verify that requested entityType is hierarchical
			final EntitySchemaContract entitySchema = extraResultPlanner.getSchema(entityType);
			Assert.isTrue(
				entitySchema.isWithHierarchy(),
				() -> new TargetEntityIsNotHierarchicalException(referenceName, entityType));

			final HierarchyWithin hierarchyWithin = evitaRequest.getHierarchyWithin(referenceName);
			final EntityIndex globalIndex = extraResultPlanner.getGlobalEntityIndex(entityType);

			// the request is more complex
			/* TODO JNO - reimplement */
			/* hierarchyStatisticsProducer
				.addHierarchyRequest(
					extraResultPlanner.getSchema().getReferenceOrThrowException(referenceName),
					hierarchyWithin,
					globalIndex,
					// we need to access EntityIndexType.REFERENCED_HIERARCHY_NODE of the queried type to access
					// entity primary keys that are referencing the hierarchy entity
					hierarchyNodeId -> ofNullable(extraResultPlanner.getIndex(queriedEntityType, createReferencedHierarchyIndexKey(referenceName, hierarchyNodeId)))
						.map(EntityIndex::getAllPrimaryKeys)
						.orElse(EmptyBitmap.INSTANCE),
					hierarchyStatsConstraint.getEntityRequirement()
				);
			*/
		}
		return hierarchyStatisticsProducer;
	}

}
