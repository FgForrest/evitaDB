/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.extraResult.translator.reference;

import io.evitadb.api.exception.HierarchyContentMisplacedException;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.HierarchyNode;
import io.evitadb.api.query.require.HierarchyStopAt;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.AbstractHierarchyTranslator.TraversalDirection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

import static io.evitadb.core.query.extraResult.translator.hierarchyStatistics.AbstractHierarchyTranslator.stopAtConstraintToPredicate;

/**
 * This implementation of {@link RequireConstraintTranslator} adds only a requirement for prefetching references when
 * {@link HierarchyContent} requirement is encountered. This requirement signalizes that we would need to use
 * the {@link ReferenceFetcher} implementation to fetch referenced entities, and we'd need the information about entity
 * references already present at that moment.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyContentTranslator implements RequireConstraintTranslator<HierarchyContent> {

	@Nullable
	@Override
	public ExtraResultProducer createProducer(@Nonnull HierarchyContent hierarchyContent, @Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		if (extraResultPlanningVisitor.isEntityTypeKnown()) {
			final Optional<EntitySchemaContract> entitySchema = extraResultPlanningVisitor.getCurrentEntitySchema();
			Assert.isTrue(
				entitySchema.isPresent(),
				() -> new HierarchyContentMisplacedException(
					extraResultPlanningVisitor.getEntityContentRequireChain(hierarchyContent)
				)
			);
			verifyStopAtNode(hierarchyContent, entitySchema.get(), extraResultPlanningVisitor);
		}
		if (extraResultPlanningVisitor.isScopeOfQueriedEntity()) {
			extraResultPlanningVisitor.addRequirementToPrefetch(hierarchyContent);
		}
		return null;
	}

	/**
	 * Checks the node filter of the `stopAt(node(...))` bound of the parents against the schema, in every processing
	 * scope. The parents are walked only while an entity is fetched, and only for an entity that has a parent - so the
	 * filter would otherwise be checked or not depending on the data the query returns. It is translated here over the
	 * global index of each scope, or over an empty index of a scope holding no entity of the type, and thrown away.
	 *
	 * @param hierarchyContent           the requirement whose bound is checked
	 * @param entitySchema               the schema of the entity whose parents are fetched
	 * @param extraResultPlanningVisitor the visitor planning the requirement
	 */
	private static void verifyStopAtNode(
		@Nonnull HierarchyContent hierarchyContent,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor
	) {
		final HierarchyStopAt stopAt = hierarchyContent.getStopAt().orElse(null);
		if (stopAt != null && stopAt.getStopAtDefinition() instanceof HierarchyNode && entitySchema.isWithHierarchy()) {
			final QueryPlanningContext queryContext = extraResultPlanningVisitor.getQueryContext();
			for (final Scope scope : extraResultPlanningVisitor.getProcessingScope().getScopes()) {
				stopAtConstraintToPredicate(
					TraversalDirection.BOTTOM_UP,
					stopAt,
					queryContext,
					queryContext.getGlobalEntityIndexIfExists(entitySchema.getName(), scope)
						.orElseGet(() -> GlobalEntityIndex.createEmptyIndex(entitySchema.getName(), scope)),
					entitySchema,
					null
				);
			}
		}
	}

}
