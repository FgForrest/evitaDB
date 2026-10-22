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

import io.evitadb.api.exception.ReferenceContentMisplacedException;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.fetch.ReferencedEntityFetcher;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Optional;

import static java.util.Optional.of;

/**
 * This implementation of {@link RequireConstraintTranslator} adds only a requirement for prefetching references when
 * {@link ReferenceContent} requirement is encountered. This requirement signalizes that we would need to use
 * the {@link ReferenceFetcher} implementation to fetch referenced entities, and we'd need the information about entity
 * references already present at that moment.
 *
 * The filter and the ordering of the fetched references are checked against the schemas here, while the query is
 * planned - see {@link ReferencedEntityFetcher#verifyReferenceContentConstraints}. The fetch evaluates them only when
 * there is a reference to filter or order, so a constraint the schemas refuse would otherwise fail the query or pass
 * depending on the data it returns.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceContentTranslator implements RequireConstraintTranslator<ReferenceContent>, SelfTraversingTranslator {

	@Nullable
	@Override
	public ExtraResultProducer createProducer(@Nonnull ReferenceContent referenceContent, @Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		if (extraResultPlanningVisitor.isEntityTypeKnown()) {
			final Optional<EntitySchemaContract> entitySchema = extraResultPlanningVisitor.getCurrentEntitySchema();

			Assert.isTrue(
				entitySchema.isPresent(),
				() -> new ReferenceContentMisplacedException(
					extraResultPlanningVisitor.getEntityContentRequireChain(referenceContent)
				)
			);
			final EntitySchemaContract schema = entitySchema.get();

			of(referenceContent.getReferenceNames())
				.filter(it -> !ArrayUtils.isEmpty(it))
				.map(Arrays::stream)
				.map(it -> it.map(schema::getReferenceOrThrowException))
				.orElseGet(() -> schema.getReferences().values().stream())
				.forEach(referenceSchema -> {
					ReferencedEntityFetcher.verifyReferenceContentConstraints(
						extraResultPlanningVisitor.getQueryContext(),
						schema,
						referenceSchema,
						referenceContent.getFilterBy().orElse(null),
						referenceContent.getOrderBy().orElse(null),
						extraResultPlanningVisitor.getProcessingScope().getScopes()
					);
					extraResultPlanningVisitor.executeInContext(
						referenceContent,
						() -> referenceSchema,
						() -> schema,
						() -> {
							for (RequireConstraint innerConstraint : referenceContent.getChildren()) {
								innerConstraint.accept(extraResultPlanningVisitor);
							}
							return null;
						}
					);
				});
		}

		if (extraResultPlanningVisitor.isScopeOfQueriedEntity() && (referenceContent.getFilterBy().isPresent() || referenceContent.getOrderBy().isPresent())) {
			extraResultPlanningVisitor.addRequirementToPrefetch(ReferenceContent.ALL_REFERENCES);
		}

		return null;
	}

}
