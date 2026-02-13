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

package io.evitadb.api.exception;

import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Exception thrown when attempting to access entity data that was not fetched along with
 * the entity due to missing query requirements.
 *
 * evitaDB uses a projection-based data fetching model where clients explicitly specify
 * which parts of an entity to load via query requirements like `attributeContent`,
 * `referenceContent`, `priceContent`, etc. This exception is thrown when code attempts to
 * access data that was not requested in the original query.
 *
 * **Common Scenarios:**
 * - Calling `getAttribute()` without `attributeContent` requirement
 * - Calling `getPriceForSale()` without `priceContent` requirement
 * - Accessing references without `referenceContent` requirement
 * - Requesting localized data without `dataInLocale` requirement
 * - Accessing hierarchy placement without `hierarchyContent` requirement
 *
 * **Design Rationale:**
 * This projection-based approach enables efficient queries that fetch only necessary data,
 * reducing memory usage and network transfer. It also makes data access costs explicit and
 * predictable.
 *
 * **Resolution:**
 * Add the appropriate requirement to your query's `require` clause, or use alternative
 * methods that accept explicit context parameters (e.g., `getPriceForSale(Currency,
 * OffsetDateTime, Serializable...)` instead of `getPriceForSale()`).
 *
 * This exception provides factory methods for specific missing context scenarios, each
 * with a detailed error message indicating which requirement is needed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ContextMissingException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 2082957443834244040L;

	/**
	 * Creates an exception for missing hierarchy placement data.
	 *
	 * @return exception indicating that hierarchy data was not fetched
	 */
	public static ContextMissingException hierarchyContextMissing() {
		return new ContextMissingException(
			"Hierarchy placement was not fetched along with the entity. You need to use `hierarchyContent` requirement in " +
				"your `require` part of the query."
		);
	}

	/**
	 * Creates an exception for missing parent entity data in hierarchy.
	 *
	 * @return exception indicating that parent entity body was not fetched
	 */
	public static ContextMissingException hierarchyEntityContextMissing() {
		return new ContextMissingException(
			"Parent entity was not fetched along with the entity. You need to use `hierarchyContent` with `entityFetch` " +
				"requirement in your `require` part of the query."
		);
	}

	/**
	 * Creates an exception for missing attribute data (no attributes fetched at all).
	 *
	 * @return exception indicating that no attributes were fetched
	 */
	public static ContextMissingException attributeContextMissing() {
		return new ContextMissingException(
			"No attributes were fetched along with the entity. You need to use `attributeContent` requirement in " +
				"your `require` part of the query."
		);
	}

	/**
	 * Creates an exception for a specific missing attribute.
	 *
	 * @param attributeName name of the attribute that was not fetched
	 * @return exception indicating that the specific attribute was not fetched
	 */
	public static ContextMissingException attributeContextMissing(@Nonnull String attributeName) {
		return new ContextMissingException(
			"Attribute `" + attributeName + "` was not fetched along with the entity. You need to use `attributeContent` requirement in " +
				"your `require` part of the query."
		);
	}

	/**
	 * Creates an exception for missing reference attribute data (no reference attributes
	 * fetched at all).
	 *
	 * @return exception indicating that no reference attributes were fetched
	 */
	public static ContextMissingException referenceAttributeContextMissing() {
		return new ContextMissingException(
			"No reference attributes were fetched along with the entity. You need to use `attributeContent` requirement in " +
				"`referenceContent` of your `require` part of the query."
		);
	}

	/**
	 * Creates an exception for a specific missing reference attribute.
	 *
	 * @param attributeName name of the reference attribute that was not fetched
	 * @return exception indicating that the specific reference attribute was not fetched
	 */
	public static ContextMissingException referenceAttributeContextMissing(@Nonnull String attributeName) {
		return new ContextMissingException(
			"Attribute `" + attributeName + "` was not fetched along with the entity. You need to use `attributeContent` requirement in " +
				"`referenceContent` of your `require` part of the query."
		);
	}

	/**
	 * Creates an exception for missing attribute data in a specific locale.
	 *
	 * @param attributeName  name of the localized attribute
	 * @param locale         the requested locale that was not fetched
	 * @param fetchedLocales locales that were actually fetched with the entity
	 * @return exception indicating the locale mismatch
	 */
	public static ContextMissingException attributeLocalizationContextMissing(@Nonnull String attributeName, @Nonnull Locale locale, @Nonnull Stream<Locale> fetchedLocales) {
		return new ContextMissingException(
			"Attribute `" + attributeName + "` in requested locale `" + locale.toLanguageTag() + "` was not fetched along with the entity. " +
				"You need to use `dataInLocale` requirement with proper language tag in your `require` part of the query. " +
				"Entity was fetched with following locales: " + fetchedLocales.map(Locale::toLanguageTag).map(it -> "`" + it + "`").collect(Collectors.joining(", "))
		);
	}

	/**
	 * Creates an exception when trying to access a localized attribute without providing
	 * locale context.
	 *
	 * @param attributeName name of the localized attribute
	 * @return exception indicating that locale must be specified
	 */
	public static ContextMissingException localeForAttributeContextMissing(@Nonnull String attributeName) {
		return new ContextMissingException(
			"Attribute `" + attributeName + "` is localized. You need to use `entityLocaleEquals` constraint in " +
				"your filter part of the query, or you need to call `getAttribute()` method " +
				"with explicit locale argument!"
		);
	}

	/**
	 * Creates an exception for missing associated data (no associated data fetched at all).
	 *
	 * @return exception indicating that no associated data were fetched
	 */
	public static ContextMissingException associatedDataContextMissing() {
		return new ContextMissingException(
			"No associated data were fetched along with the entity. You need to use `associatedDataContent` requirement in " +
				"your `require` part of the query."
		);
	}

	/**
	 * Creates an exception for a specific missing associated data.
	 *
	 * @param associatedDataName name of the associated data that was not fetched
	 * @return exception indicating that the specific associated data was not fetched
	 */
	public static ContextMissingException associatedDataContextMissing(@Nonnull String associatedDataName) {
		return new ContextMissingException(
			"Associated data `" + associatedDataName + "` was not fetched along with the entity. You need to use `associatedDataContent` requirement in " +
				"your `require` part of the query."
		);
	}

	/**
	 * Creates an exception for missing associated data in a specific locale.
	 *
	 * @param associatedDataName name of the localized associated data
	 * @param locale             the requested locale that was not fetched
	 * @param fetchedLocales     locales that were actually fetched with the entity
	 * @return exception indicating the locale mismatch
	 */
	public static ContextMissingException associatedDataLocalizationContextMissing(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nonnull Stream<Locale> fetchedLocales) {
		return new ContextMissingException(
			"Associated data `" + associatedDataName + "` in requested locale `" + locale.toLanguageTag() + "` was not fetched along with the entity. " +
				"You need to use `dataInLocale` requirement with proper language tag in your `require` part of the query. " +
				"Entity was fetched with following locales: " + fetchedLocales.map(Locale::toLanguageTag).map(it -> "`" + it + "`").collect(Collectors.joining(", "))
		);
	}

	/**
	 * Creates an exception when trying to access localized associated data without
	 * providing locale context.
	 *
	 * @param associatedDataName name of the localized associated data
	 * @return exception indicating that locale must be specified
	 */
	public static ContextMissingException localeForAssociatedDataContextMissing(@Nonnull String associatedDataName) {
		return new ContextMissingException(
			"Associated data `" + associatedDataName + "` is localized. You need to use `entityLocaleEquals` constraint in " +
				"your filter part of the query, or you need to call `getAssociatedData()` method " +
				"with explicit locale argument!"
		);
	}

	/**
	 * Creates an exception for missing price data (no prices fetched at all).
	 *
	 * @return exception indicating that prices were not fetched
	 */
	public static ContextMissingException pricesNotFetched() {
		return new ContextMissingException(
			"Prices were not fetched along with the entity. You need to use `priceContent` requirement with constants" +
				"`" + PriceContentMode.RESPECTING_FILTER + "` or `" + PriceContentMode.ALL + "` in " +
				"your `require` part of the query."
		);
	}

	/**
	 * Creates an exception for missing prices in a specific currency.
	 *
	 * @param requiredCurrency the currency that was requested but not fetched
	 * @param fetchedCurrency  the currency that was actually fetched
	 * @return exception indicating the currency mismatch
	 */
	public static ContextMissingException pricesNotFetched(@Nonnull Currency requiredCurrency, @Nonnull Currency fetchedCurrency) {
		return new ContextMissingException(
			"Prices in currency `" + requiredCurrency.getCurrencyCode() + "` were not fetched along with the entity. " +
				"Entity was fetched with following currency: `" + fetchedCurrency.getCurrencyCode() + "`"
		);
	}

	/**
	 * Creates an exception for missing prices in a specific price list.
	 *
	 * @param requiredPriceList the price list that was requested but not fetched
	 * @param fetchedPriceLists the price lists that were actually fetched
	 * @return exception indicating the price list mismatch
	 */
	public static ContextMissingException pricesNotFetched(@Nonnull String requiredPriceList, @Nonnull Set<String> fetchedPriceLists) {
		return new ContextMissingException(
			"Prices in price list `" + requiredPriceList + "` were not fetched along with the entity. " +
				"Entity was fetched with following price lists: " +
				fetchedPriceLists.stream()
					.map(it -> "`" + it + "`")
					.collect(Collectors.joining(", "))
		);
	}

	/**
	 * Creates an exception for missing reference data (no references fetched at all).
	 *
	 * @return exception indicating that no references were fetched
	 */
	public static ContextMissingException referenceContextMissing() {
		return new ContextMissingException(
			"No references were fetched along with the entity. You need to use `referenceContent` requirement in " +
				"your `require` part of the query."
		);
	}

	/**
	 * Creates an exception for a specific missing reference type.
	 *
	 * @param referenceName name of the reference type that was not fetched
	 * @return exception indicating that the specific reference type was not fetched
	 */
	public static ContextMissingException referenceContextMissing(@Nonnull String referenceName) {
		return new ContextMissingException(
			"Reference `" + referenceName + "` was not fetched along with the entity. You need to use `referenceContent` requirement in " +
				"your `require` part of the query."
		);
	}

	/**
	 * Creates an exception for missing referenced entity body data.
	 *
	 * @param entityName    name of the entity type containing the reference
	 * @param referenceName name of the reference type whose target entity was not fetched
	 * @return exception indicating that referenced entity body is not available
	 */
	public static ContextMissingException referencedEntityContextMissing(@Nonnull String entityName, @Nonnull String referenceName) {
		return new ContextMissingException(
			"Entity `" + entityName + "` references of type `" +
				referenceName + "` were not fetched with `entityFetch` requirement. " +
				"Related entity body is not available."
		);
	}

	/**
	 * Creates an exception for missing reference group entity body data.
	 *
	 * @param entityName    name of the entity type containing the reference
	 * @param referenceName name of the reference type whose group entity was not fetched
	 * @return exception indicating that reference group entity body is not available
	 */
	public static ContextMissingException referencedEntityGroupContextMissing(@Nonnull String entityName, @Nonnull String referenceName) {
		return new ContextMissingException(
			"Entity `" + entityName + "` references of type `" +
				referenceName + "` were not fetched with `entityGroupFetch` requirement. " +
				"Related entity group body is not available."
		);
	}

	/**
	 * Creates an exception for missing price context when calling `getPriceForSale()`
	 * without query context.
	 */
	public ContextMissingException() {
		super(
			"Query context is missing. You need to use method getPriceForSale(Currency, OffsetDateTime, Serializable...) " +
				"and provide the context on your own."
		);
	}

	/**
	 * Creates an exception with a custom error message.
	 *
	 * @param publicMessage detailed message describing the missing context
	 */
	public ContextMissingException(@Nonnull String publicMessage) {
		super(publicMessage);
	}
}
