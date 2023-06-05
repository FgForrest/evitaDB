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

package io.evitadb.core.query.extraResult.translator.reference;

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.utils.Assert;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * This implementation of {@link RequireConstraintTranslator} adds only a requirement for prefetching references when
 * {@link AssociatedDataContent} requirement is encountered. This requirement signalizes that we would need to use
 * the {@link ReferenceFetcher} implementation to fetch referenced entities, and we'd need the information about entity
 * references already present at that moment.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityGroupFetchTranslator implements RequireConstraintTranslator<EntityGroupFetch>, SelfTraversingTranslator {

	@Nullable
	@Override
	public ExtraResultProducer apply(EntityGroupFetch entityGroupFetch, ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		if (extraResultPlanningVisitor.isEntityTypeKnown()) {
			final EntitySchemaContract schema = extraResultPlanningVisitor.isScopeEmpty() ?
				extraResultPlanningVisitor.getSchema() : getReferencedSchema(extraResultPlanningVisitor);

			extraResultPlanningVisitor.executeInContext(
				entityGroupFetch,
				() -> null,
				() -> schema,
				() -> {
					for (RequireConstraint innerConstraint : entityGroupFetch.getChildren()) {
						innerConstraint.accept(extraResultPlanningVisitor);
					}
					return null;
				}
			);
		}
		return null;
	}

	@Nullable
	private EntitySchemaContract getReferencedSchema(ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		final ProcessingScope processingScope = extraResultPlanningVisitor.getProcessingScope();
		final Optional<ReferenceSchemaContract> referenceSchema = processingScope.getReferenceSchema();

		Assert.isTrue(
			referenceSchema.isPresent(),
			() -> "EntityGroupFetch constraint is probably incorrectly nested in require part of the query."
		);

		return referenceSchema
			.filter(ReferenceSchemaContract::isReferencedGroupTypeManaged)
			.map(schema -> extraResultPlanningVisitor.getSchema(schema.getReferencedGroupType()))
			.orElse(null);
	}

}
