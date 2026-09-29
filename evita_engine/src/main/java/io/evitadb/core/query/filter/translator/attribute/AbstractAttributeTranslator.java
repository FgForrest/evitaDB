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
import java.util.function.Predicate;

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

	/**
	 * Tells whether a localized attribute is read in the query locale alone in the scope, i.e. whether a record
	 * carrying its value there holds the query locale. That is not so where the uniqueness of the attribute ignores
	 * the locale: unique across locales within the collection, or globally unique across the whole catalog - a value
	 * in any locale is then carried.
	 *
	 * @param attributeSchema the schema definition of the attribute being processed
	 * @param scope           the scope the attribute is read in
	 * @return true when only the value in the query locale counts as carried in the scope
	 */
	protected static boolean isBoundToQueryLocale(
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Scope scope
	) {
		if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema &&
			globalAttributeSchema.isUniqueGloballyInScope(scope)
		) {
			return globalAttributeSchema.isUniqueGloballyWithinLocaleInScope(scope);
		}
		return !attributeSchema.isUniqueInScope(scope) || attributeSchema.isUniqueWithinLocaleInScope(scope);
	}

	/**
	 * Tells whether every record a lookup of the localized attribute yields holds the query locale, which is what an
	 * {@link io.evitadb.core.query.algebra.attribute.AttributeFormula} built over it may claim through
	 * {@link io.evitadb.core.query.algebra.attribute.AttributeFormula#isLocaleImplied()}. It does so only when a query
	 * locale is requested and every requested scope reads the attribute in that locale alone - in a scope where the
	 * uniqueness ignores the locale, a lookup finds a record carrying the value in any locale, including a record that
	 * lacks the query locale altogether.
	 *
	 * @param filterByVisitor the visitor that provides the query locale and the requested scopes
	 * @param attributeSchema the schema definition of the attribute being processed
	 * @return true when the records of a lookup of the attribute all hold the query locale
	 */
	protected static boolean isQueryLocaleImplied(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AttributeSchemaContract attributeSchema
	) {
		return isQueryLocaleImpliedByEveryScope(filterByVisitor, scope -> isBoundToQueryLocale(attributeSchema, scope));
	}

	/**
	 * Tells whether the attribute is unique in the scope, within the collection or - for a catalog attribute -
	 * globally.
	 *
	 * @param attributeSchema the schema definition of the attribute being processed
	 * @param scope           the scope to examine
	 * @return true when the attribute is unique in the scope
	 */
	protected static boolean isUniqueInScope(@Nonnull AttributeSchemaContract attributeSchema, @Nonnull Scope scope) {
		return attributeSchema.isUniqueInScope(scope) ||
			attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema &&
				globalAttributeSchema.isUniqueGloballyInScope(scope);
	}

	/**
	 * Tells whether every record a unique lookup of a value of the localized attribute yields holds the query locale -
	 * see {@link io.evitadb.core.query.algebra.attribute.AttributeFormula#isLocaleImplied()}. The unique index of the
	 * catalog records the locale of every value and matches it against the query locale, and so does the unique index
	 * of a collection where the attribute is unique within a locale. The unique index of a collection where it is
	 * unique across locales is shared by every locale: it finds the record whatever locale it carries the value in,
	 * and that record may lack the query locale altogether.
	 *
	 * @param filterByVisitor the visitor that provides the query locale and the requested scopes
	 * @param attributeSchema the schema definition of the attribute being processed
	 * @return true when the records of a unique lookup of the attribute all hold the query locale
	 */
	protected static boolean isQueryLocaleImpliedByUniqueLookup(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AttributeSchemaContract attributeSchema
	) {
		return isQueryLocaleImpliedByEveryScope(
			filterByVisitor,
			// a catalog lookup matches the locale of the value even where the uniqueness spans every locale, so unlike
			// `isBoundToQueryLocale` (which answers the null tests) any global uniqueness implies the query locale here
			scope -> (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema &&
				globalAttributeSchema.isUniqueGloballyInScope(scope)) ||
				!attributeSchema.isUniqueInScope(scope) ||
				attributeSchema.isUniqueWithinLocaleInScope(scope)
		);
	}

	/**
	 * Tells whether a query locale was requested and every requested scope satisfies the given per-scope condition -
	 * the check {@link #isQueryLocaleImplied} and {@link #isQueryLocaleImpliedByUniqueLookup} both need before testing
	 * their own, different condition.
	 *
	 * @param filterByVisitor    the visitor that provides the query locale and the requested scopes
	 * @param scopeImpliesLocale the per-scope condition to test against every requested scope
	 * @return true when a query locale was requested and every requested scope satisfies the condition
	 */
	private static boolean isQueryLocaleImpliedByEveryScope(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull Predicate<Scope> scopeImpliesLocale
	) {
		return filterByVisitor.getLocale() != null &&
			filterByVisitor.getProcessingScope().getScopes().stream().allMatch(scopeImpliesLocale);
	}

}
