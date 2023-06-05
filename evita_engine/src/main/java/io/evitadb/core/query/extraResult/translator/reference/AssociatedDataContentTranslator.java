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

import io.evitadb.api.exception.AssociatedDataContentMisplacedException;
import io.evitadb.api.exception.AssociatedDataNotFoundException;
import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.utils.ArrayUtils;
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
public class AssociatedDataContentTranslator implements RequireConstraintTranslator<AssociatedDataContent> {

	@Nullable
	@Override
	public ExtraResultProducer apply(AssociatedDataContent associatedDataContent, ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		if (extraResultPlanningVisitor.isEntityTypeKnown()) {
			final Optional<EntitySchemaContract> entitySchema = extraResultPlanningVisitor.getCurrentEntitySchema();
			Assert.isTrue(
				entitySchema.isPresent(),
				() -> new AssociatedDataContentMisplacedException(
					extraResultPlanningVisitor.getEntityContentRequireChain(associatedDataContent)
				)
			);

			final String[] associatedDataNames = associatedDataContent.getAssociatedDataNames();
			if (!ArrayUtils.isEmpty(associatedDataNames)) {
				final EntitySchemaContract schema = entitySchema.get();
				for (String associatedDataName : associatedDataNames) {
					Assert.isTrue(
						schema.getAssociatedData(associatedDataName).isPresent(),
						() -> new AssociatedDataNotFoundException(associatedDataName, schema)
					);
				}
			}
		}
		if (extraResultPlanningVisitor.isScopeEmpty()) {
			extraResultPlanningVisitor.addRequirementToPrefetch(associatedDataContent);
		}
		return null;
	}

}
