/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.query.filter.translator.attribute;


import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.dataType.Scope;
import io.evitadb.index.Index;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.Set;

/**
 * The AbstractAttributeTranslator class provides utility methods for handling attribute keys within
 * the filtering and translation process. Specifically, it can generate AttributeKey objects using
 * given parameters such as the filterByVisitor and attributeDefinition.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class AbstractAttributeTranslator {

	/**
	 * Generates an AttributeKey object based on the provided filterByVisitor and attributeDefinition.
	 * If the attribute is localized, it requires a locale specification within the filterByVisitor.
	 *
	 * @param filterByVisitor the visitor that provides context information, including locale.
	 * @param attributeDefinition the schema contract that contains the attribute details.
	 * @return an AttributeKey object representing the specific attribute.
	 * @throws AssertionError if the attribute requires localization but no locale is provided in the filterByVisitor.
	 */
	@Nonnull
	public static AttributeKey createAttributeKey(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AttributeSchemaContract attributeDefinition
	) {
		final String attributeName = attributeDefinition.getName();
		final Set<Scope> scopes = filterByVisitor.getProcessingScope().getScopes();
		Assert.isTrue(
			!attributeDefinition.isLocalized() || filterByVisitor.getLocale() != null ||
				(scopes.stream().anyMatch(scope -> attributeDefinition.isUniqueInScope(scope) && !attributeDefinition.isUniqueWithinLocaleInScope(scope))),
			() -> new EntityLocaleMissingException(attributeName)
		);

		return attributeDefinition.isLocalized() ?
			new AttributeKey(attributeName, filterByVisitor.getLocale()) : new AttributeKey(attributeName);
	}

	/**
	 * Retrieves an optional global attribute schema based on the provided attribute name and filter visitor.
	 * The global schema is not returned in case the reference schema is present in the processing scope - i.e. it means
	 * that the search lookup is limited to attributes of particular reference schema.
	 *
	 * A global attribute reached this way never passes through {@link AttributeSchemaAccessor}'s instance getters -
	 * every caller below uses what this returns and falls back to the accessor only when it returns nothing - so this
	 * is also where such a lookup reports the capability it needed. Which registry counts it follows the rule stated
	 * on {@link AttributeSchemaAccessor#recordRequestedTraits}: the queried collection when the query names one, the
	 * catalog when it does not and the attribute is answered from the catalog's own global unique index.
	 *
	 * @param filterByVisitor the visitor that provides context information, including the processing scope and catalog schema.
	 * @param attributeName the name of the attribute for which the schema is to be retrieved.
	 * @return an Optional containing the GlobalAttributeSchemaContract if the attribute exists in the catalog schema,
	 * or an empty Optional if the reference schema is not null contextually.
	 */
	@Nonnull
	protected static Optional<GlobalAttributeSchemaContract> getOptionalGlobalAttributeSchema(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull String attributeName,
		@Nonnull AttributeTrait... traits
	) {
		final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
		final Optional<GlobalAttributeSchemaContract> result = processingScope.getReferenceSchema() == null ?
			filterByVisitor.getCatalogSchema().getAttribute(attributeName) : Optional.empty();
		if (result.isPresent() && traits.length > 0) {
			final EntitySchemaContract entitySchema = filterByVisitor.isEntityTypeKnown() ?
				filterByVisitor.getSchema() : null;
			AttributeSchemaAccessor.verifyAndReturn(
				attributeName,
				processingScope.getScopes(),
				result.get(),
				filterByVisitor.getCatalogSchema(),
				entitySchema,
				filterByVisitor.getReferenceSchema().orElse(null),
				traits
			);
			// only after the verification, which is what makes every (trait, scope) pair a capability the schema
			// really declares - the global attribute is never looked up on a reference, hence the null container
			AttributeSchemaAccessor.recordRequestedTraits(
				filterByVisitor.getQueryContext(), entitySchema, null,
				result.get().getName(), processingScope.getScopes(), traits
			);
		}
		return result;
	}

}
