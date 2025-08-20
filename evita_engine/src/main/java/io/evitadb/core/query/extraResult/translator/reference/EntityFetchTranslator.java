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

package io.evitadb.core.query.extraResult.translator.reference;

import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.DataInLocales;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * This implementation of {@link RequireConstraintTranslator} adds only a requirement for prefetching references when
 * {@link AssociatedDataContent} requirement is encountered. This requirement signalizes that we would need to use
 * the {@link ReferenceFetcher} implementation to fetch referenced entities, and we'd need the information about entity
 * references already present at that moment.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityFetchTranslator implements RequireConstraintTranslator<EntityFetch>, SelfTraversingTranslator {

	/**
	 * Verify the fetch requirement for a given referenced type.
	 *
	 * @param entitySchema        the schema of the referenced entity
	 * @param requirement         the fetch requirement to be verified
	 * @param extraResultPlanner  the visitor used for extra result planning
	 * @throws EntityLocaleMissingException  if there are missing localized attributes or associated data
	 */
	public static void verifyEntityFetchLocalizedAttributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EntityFetchRequire requirement,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanner
	) throws EntityLocaleMissingException {
		final EntityContentRequire[] requirements = requirement.getRequirements();
		String[] missingLocalizedAttributes = null;
		String[] missingLocalizedAssociatedData = null;
		for (EntityContentRequire require : requirements) {
			try {
				if (require instanceof AttributeContent attributeContent) {
					AttributeContentTranslator.verifyAttributes(
						entitySchema, null, entitySchema, attributeContent, extraResultPlanner
					);
				} else if (require instanceof AssociatedDataContent associatedDataContent) {
					AssociatedDataContentTranslator.verifyAssociatedData(
						associatedDataContent, entitySchema, extraResultPlanner
					);
				} else if (require instanceof ReferenceContent referenceContent) {
					final Collection<ReferenceSchemaContract> referencedEntityReferenceSchemas = referenceContent.isAllRequested() ?
						entitySchema.getReferences().values() :
						List.of(
							entitySchema.getReference(referenceContent.getReferenceName())
								.orElseThrow(() -> new ReferenceNotFoundException(referenceContent.getReferenceName(), entitySchema))
						);
					for (ReferenceSchemaContract referencedEntityReferenceSchema : referencedEntityReferenceSchemas) {
						referenceContent.getAttributeContent()
							.ifPresent(it -> AttributeContentTranslator.verifyAttributes(
								entitySchema, referencedEntityReferenceSchema, referencedEntityReferenceSchema, it, extraResultPlanner
							));
					}
				} else if (require instanceof DataInLocales) {
					// locales are specified here
					return;
				}
			} catch (EntityLocaleMissingException ex) {
				// gradually collect all missing localized attributes and associated data
				missingLocalizedAttributes = missingLocalizedAttributes == null ?
					ex.getAttributeNames() :
					(ex.getAttributeNames() == null ?
						missingLocalizedAttributes :
						ArrayUtils.mergeArrays(missingLocalizedAttributes, ex.getAttributeNames())
					);
				missingLocalizedAssociatedData = missingLocalizedAssociatedData == null ?
					ex.getAssociatedDataNames() :
					(ex.getAssociatedDataNames() == null ?
						missingLocalizedAssociatedData :
						ArrayUtils.mergeArrays(missingLocalizedAssociatedData, ex.getAssociatedDataNames())
					);
			}
		}
		// if there are any missing localized attributes or associated data, throw an exception
		if (missingLocalizedAttributes != null || missingLocalizedAssociatedData != null) {
			throw new EntityLocaleMissingException(
				missingLocalizedAttributes,
				missingLocalizedAssociatedData
			);
		}
	}

	@Nullable
	@Override
	public ExtraResultProducer createProducer(@Nonnull EntityFetch entityFetch, @Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		if (extraResultPlanningVisitor.isRootScope()) {
			extraResultPlanningVisitor.addRequirementToPrefetch(entityFetch.getRequirements());
		}
		if (extraResultPlanningVisitor.isEntityTypeKnown()) {
			final EntitySchemaContract schema = extraResultPlanningVisitor.isRootScope() ?
				extraResultPlanningVisitor.getSchema() :
				getReferencedSchema(extraResultPlanningVisitor)
					.orElseGet(() -> extraResultPlanningVisitor.getCurrentEntitySchema()
						.orElseThrow(() -> new EvitaInvalidUsageException("EntityFetch constraint is probably incorrectly nested in require part of the query.")));

			extraResultPlanningVisitor.executeInContext(
				entityFetch,
				() -> null,
				() -> schema,
				() -> {
					for (RequireConstraint innerConstraint : entityFetch.getChildren()) {
						innerConstraint.accept(extraResultPlanningVisitor);
					}
					return null;
				}
			);
		}
		return null;
	}

	@Nonnull
	private static Optional<EntitySchemaContract> getReferencedSchema(@Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		final ProcessingScope processingScope = extraResultPlanningVisitor.getProcessingScope();
		final Optional<ReferenceSchemaContract> referenceSchema = processingScope.getReferenceSchema();
		return referenceSchema
			.filter(ReferenceSchemaContract::isReferencedEntityTypeManaged)
			.map(schema -> extraResultPlanningVisitor.getSchema(schema.getReferencedEntityType()));
	}

}
