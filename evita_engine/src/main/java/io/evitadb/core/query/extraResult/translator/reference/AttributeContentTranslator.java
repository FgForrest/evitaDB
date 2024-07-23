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

import io.evitadb.api.exception.AttributeContentMisplacedException;
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * This implementation of {@link RequireConstraintTranslator} adds only a requirement for prefetching references when
 * {@link AttributeContent} requirement is encountered. This requirement signalizes that we would need to use
 * the {@link ReferenceFetcher} implementation to fetch referenced entities, and we'd need the information about entity
 * references already present at that moment.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeContentTranslator implements RequireConstraintTranslator<AttributeContent> {

	/**
	 * Verifies if the attributes in the given attributeContent are known in the schema.
	 *
	 * @param attributeNames  The attribute content to verify.
	 * @param schema          The attribute schema provider.
	 * @param referenceSchema The optional reference schema.
	 * @param entitySchema    The entity schema for attribute validation.
	 */
	private static void verifyAttributesKnown(
		@Nonnull String[] attributeNames,
		@Nonnull AttributeSchemaProvider<?> schema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull EntitySchemaContract entitySchema
	) {
		for (String attributeName : attributeNames) {
			Assert.isTrue(
				schema.getAttribute(attributeName).isPresent(),
				() -> referenceSchema == null ?
					new AttributeNotFoundException(attributeName, entitySchema):
					new AttributeNotFoundException(attributeName, referenceSchema, entitySchema)
			);
		}
	}

	/**
	 * Verifies if the attributes in the given attributeContent are known in the schema and not localized.
	 *
	 * @param attributeNames  The attribute content to verify.
	 * @param schema          The attribute schema provider.
	 * @param referenceSchema The optional reference schema.
	 * @param entitySchema    The entity schema for attribute validation.
	 */
	private static void verifyAttributesKnownAndNotLocalized(
		@Nonnull String[] attributeNames,
		@Nonnull AttributeSchemaProvider<? extends AttributeSchemaContract> schema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull EntitySchemaContract entitySchema
	) {
		final List<String> missingLocalizedAttributes = new LinkedList<>();
		for (String attributeName : attributeNames) {
			final Optional<? extends AttributeSchemaContract> attributeSchema = schema.getAttribute(attributeName);
			Assert.isTrue(
				attributeSchema.isPresent(),
				() -> referenceSchema == null ?
					new AttributeNotFoundException(attributeName, entitySchema):
					new AttributeNotFoundException(attributeName, referenceSchema, entitySchema)
			);
			// unique attributes could provide implicit locale
			if (attributeSchema.get().isLocalized() && !attributeSchema.get().isUnique()) {
				missingLocalizedAttributes.add(attributeName);
			}
		}

		if (!missingLocalizedAttributes.isEmpty()) {
			throw new EntityLocaleMissingException(missingLocalizedAttributes.toArray(new String[0]));
		}

	}

	@Nullable
	@Override
	public ExtraResultProducer apply(AttributeContent attributeContent, ExtraResultPlanningVisitor extraResultPlanningVisitor) {
		if (extraResultPlanningVisitor.isEntityTypeKnown()) {
			final Optional<ReferenceSchemaContract> referenceSchema = extraResultPlanningVisitor.getCurrentReferenceSchema();
			final Optional<EntitySchemaContract> entitySchema = extraResultPlanningVisitor.getCurrentEntitySchema();
			final AttributeSchemaProvider<?> schema = referenceSchema
				.map(AttributeSchemaProvider.class::cast)
				.orElseGet(() -> entitySchema.orElse(null));

			Assert.notNull(
				schema,
				() -> new AttributeContentMisplacedException(
					extraResultPlanningVisitor.getEntityContentRequireChain(attributeContent)
				)
			);

			verifyAttributes(
				entitySchema.orElseThrow(),
				referenceSchema.orElse(null),
				schema,
				attributeContent,
				extraResultPlanningVisitor
			);
		}
		if (extraResultPlanningVisitor.isScopeOfQueriedEntity()) {
			extraResultPlanningVisitor.addRequirementToPrefetch(attributeContent);
		}
		return null;
	}

	/**
	 * Verifies if the attributes in the given attributeContent are known in the schema and not localized.
	 * If the Evita request has either required locales or an implicit locale, it only verifies if the attributes are known in the schema.
	 *
	 * @param entitySchema                The entity schema for attribute validation.
	 * @param referenceSchema             The optional reference schema.
	 * @param schema                      The attribute schema provider.
	 * @param attributeContent            The attribute content to verify.
	 * @param extraResultPlanningVisitor  The extra result planning visitor.
	 */
	public static void verifyAttributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaProvider<?> schema,
		@Nonnull AttributeContent attributeContent,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor
	) {
		final String[] attributeNames = attributeContent.getAttributeNames();
		if (!ArrayUtils.isEmpty(attributeNames)) {
			final EvitaRequest evitaRequest = extraResultPlanningVisitor.getEvitaRequest();
			if (evitaRequest.getRequiredLocales() == null && evitaRequest.getImplicitLocale() == null) {
				verifyAttributesKnownAndNotLocalized(attributeNames, schema, referenceSchema, entitySchema);
			} else {
				verifyAttributesKnown(attributeNames, schema, referenceSchema, entitySchema);
			}
		}
	}

}
