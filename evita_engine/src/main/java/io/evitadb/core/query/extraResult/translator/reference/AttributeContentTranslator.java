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
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

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
	 * Every scope, read once - `Scope.values()` copies the array on each call.
	 */
	private static final Scope[] SCOPES = Scope.values();

	/**
	 * Returns true when the attribute is unique in any of the scopes - such an attribute can provide an implicit
	 * locale, so naming it without a locale is not refused.
	 *
	 * @param attribute the localized attribute
	 * @param scopes    the scopes the uniqueness counts in
	 * @return true when the attribute is unique in at least one of the scopes
	 */
	private static boolean isUniqueInAnyScope(@Nonnull AttributeSchemaContract attribute, @Nonnull Set<Scope> scopes) {
		for (final Scope scope : SCOPES) {
			if (scopes.contains(scope) && attribute.isUniqueInScope(scope)) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	@Override
	public ExtraResultProducer createProducer(@Nonnull AttributeContent attributeContent, @Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor) {
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
	 * Validates named attributes in the request of their body level, which
	 * {@link ExtraResultPlanningVisitor#getEntityFetchRequest} derives. Localized names require that level
	 * to resolve a locale, provide an implicit locale, or request localized data explicitly.
	 *
	 * @param entitySchema                The entity schema for attribute validation.
	 * @param referenceSchema             The optional reference schema.
	 * @param schema                      The attribute schema provider.
	 * @param attributeContent            The attribute content to verify.
	 * @param extraResultPlanningVisitor  The extra result planning visitor.
	 * @throws AttributeNotFoundException   when a named attribute is not in the schema
	 * @throws EntityLocaleMissingException when a named attribute is localized and the body level has no locale
	 */
	public static void verifyAttributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaProvider<?> schema,
		@Nonnull AttributeContent attributeContent,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor
	) {
		verifyAttributes(entitySchema, referenceSchema, schema, attributeContent, extraResultPlanningVisitor,
			extraResultPlanningVisitor.getEntityFetchRequest(attributeContent));
	}

	/**
	 * Validates named attributes against the given request - used by callers that derive the request of the body
	 * themselves, such as the summary translators, whose bodies take their locale from the summary filter.
	 * The uniqueness exemption uses the processing scopes, which below the main fetch are the query's searched scopes.
	 *
	 * @param entitySchema               the entity schema for attribute validation
	 * @param referenceSchema            the optional reference schema
	 * @param schema                     the attribute schema provider
	 * @param attributeContent           the attribute content to verify
	 * @param extraResultPlanningVisitor the extra result planning visitor
	 * @param evitaRequest               the request fetching the body, whose required or implicit locales decide
	 *                                   whether localized names are accepted
	 * @throws AttributeNotFoundException   when a named attribute is not in the schema
	 * @throws EntityLocaleMissingException when a named attribute is localized and the request has no locale
	 */
	public static void verifyAttributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaProvider<?> schema,
		@Nonnull AttributeContent attributeContent,
		@Nonnull ExtraResultPlanningVisitor extraResultPlanningVisitor,
		@Nonnull EvitaRequest evitaRequest
	) {
		verifyAttributes(
			entitySchema, referenceSchema, schema, attributeContent,
			evitaRequest.getRequiredLocales() != null || evitaRequest.getImplicitLocale() != null,
			extraResultPlanningVisitor.getProcessingScope().getScopes()
		);
	}

	/**
	 * Checks named attributes without deriving a body request or allocating on a successful lookup.
	 * A localized attribute unique in any of the supplied scopes can supply an implicit locale.
	 *
	 * @param entitySchema the owning entity schema
	 * @param referenceSchema the reference schema, or NULL for entity attributes
	 * @param schema the provider of the attributes being checked
	 * @param attributeContent the client content requirement as written
	 * @param localeAvailable whether a locale is available at this level - the body's own, one inherited from an
	 *                        enclosing fetch, or one resolved from the reference filter
	 * @param scopes the scopes checked for a uniqueness index
	 * @throws AttributeNotFoundException when a named attribute is unknown
	 * @throws EntityLocaleMissingException when named localized attributes require a locale
	 */
	public static void verifyAttributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaProvider<?> schema,
		@Nonnull AttributeContent attributeContent,
		boolean localeAvailable,
		@Nonnull Set<Scope> scopes
	) {
		final Serializable[] names = attributeContent.getArguments();
		String[] missing = null;
		int missingCount = 0;
		for (int i = 0; i < names.length; i++) {
			final String name = (String) names[i];
			final AttributeSchemaContract attribute = schema.getAttributes().get(name);
			if (attribute == null) {
				throw referenceSchema == null ? new AttributeNotFoundException(name, entitySchema) :
					new AttributeNotFoundException(name, referenceSchema, entitySchema);
			}
			if (!localeAvailable && attribute.isLocalized() && !isUniqueInAnyScope(attribute, scopes)) {
				if (missing == null) {
					missing = new String[names.length];
				}
				if (!ArrayUtils.contains(missing, name)) {
					missing[missingCount++] = name;
				}
			}
		}
		if (missing != null) {
			throw new EntityLocaleMissingException(Arrays.copyOf(missing, missingCount));
		}
	}

}
