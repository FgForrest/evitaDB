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

import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This exception is thrown when {@link PricesContract#getPriceForSale()} is called and there is no {@link Query} known
 * that would provide sufficient data - or the query lacks price related constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ContextMissingException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 2082957443834244040L;

	public static ContextMissingException hierarchyContextMissing() {
		return new ContextMissingException(
			"Hierarchy placement was not fetched along with the entity. You need to use `hierarchyContent` requirement in " +
				"your `require` part of the query."
		);
	}

	public static ContextMissingException hierarchyEntityContextMissing() {
		return new ContextMissingException(
			"Parent entity was not fetched along with the entity. You need to use `hierarchyContent` with `entityFetch` " +
				"requirement in your `require` part of the query."
		);
	}

	public static ContextMissingException attributeContextMissing() {
		return new ContextMissingException(
			"No attributes were fetched along with the entity. You need to use `attributeContent` requirement in " +
				"your `require` part of the query."
		);
	}

	public static ContextMissingException attributeContextMissing(@Nonnull String attributeName) {
		return new ContextMissingException(
			"Attribute `" + attributeName + "` was not fetched along with the entity. You need to use `attributeContent` requirement in " +
				"your `require` part of the query."
		);
	}

	public static ContextMissingException referenceAttributeContextMissing() {
		return new ContextMissingException(
			"No reference attributes were fetched along with the entity. You need to use `attributeContent` requirement in " +
				"`referenceContent` of your `require` part of the query."
		);
	}

	public static ContextMissingException referenceAttributeContextMissing(@Nonnull String attributeName) {
		return new ContextMissingException(
			"Attribute `" + attributeName + "` was not fetched along with the entity. You need to use `attributeContent` requirement in " +
				"`referenceContent` of your `require` part of the query."
		);
	}

	public static ContextMissingException attributeLocalizationContextMissing(@Nonnull String attributeName, @Nonnull Locale locale, @Nonnull Stream<Locale> fetchedLocales) {
		return new ContextMissingException(
			"Attribute `" + attributeName + "` in requested locale `" + locale.toLanguageTag() + "` was not fetched along with the entity. " +
				"You need to use `dataInLocale` requirement with proper language tag in your `require` part of the query. " +
				"Entity was fetched with following locales: " + fetchedLocales.map(Locale::toLanguageTag).map(it -> "`" + it + "`").collect(Collectors.joining(", "))
		);
	}

	public static ContextMissingException localeForAttributeContextMissing(@Nonnull String attributeName) {
		return new ContextMissingException(
			"Attribute `" + attributeName + "` is localized. You need to use `entityLocaleEquals` constraint in " +
				"your filter part of the query, or you need to call `getAttribute()` method " +
				"with explicit locale argument!"
		);
	}

	public static ContextMissingException associatedDataContextMissing() {
		return new ContextMissingException(
			"No associated data were fetched along with the entity. You need to use `associatedDataContent` requirement in " +
				"your `require` part of the query."
		);
	}

	public static ContextMissingException associatedDataContextMissing(@Nonnull String associatedDataName) {
		return new ContextMissingException(
			"Associated data `" + associatedDataName + "` was not fetched along with the entity. You need to use `associatedDataContent` requirement in " +
				"your `require` part of the query."
		);
	}

	public static ContextMissingException associatedDataLocalizationContextMissing(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nonnull Stream<Locale> fetchedLocales) {
		return new ContextMissingException(
			"Associated data `" + associatedDataName + "` in requested locale `" + locale.toLanguageTag() + "` was not fetched along with the entity. " +
				"You need to use `dataInLocale` requirement with proper language tag in your `require` part of the query. " +
				"Entity was fetched with following locales: " + fetchedLocales.map(Locale::toLanguageTag).map(it -> "`" + it + "`").collect(Collectors.joining(", "))
		);
	}

	public static ContextMissingException localeForAssociatedDataContextMissing(@Nonnull String associatedDataName) {
		return new ContextMissingException(
			"Associated data `" + associatedDataName + "` is localized. You need to use `entityLocaleEquals` constraint in " +
				"your filter part of the query, or you need to call `getAssociatedData()` method " +
				"with explicit locale argument!"
		);
	}

	public static ContextMissingException pricesNotFetched() {
		return new ContextMissingException(
			"Prices were not fetched along with the entity. You need to use `priceContent` requirement with constants" +
				"`" + PriceContentMode.RESPECTING_FILTER + "` or `" + PriceContentMode.ALL + "` in " +
				"your `require` part of the query."
		);
	}

	public static ContextMissingException pricesNotFetched(@Nonnull Currency requiredCurrency, @Nonnull Currency fetchedCurrency) {
		return new ContextMissingException(
			"Prices in currency `" + requiredCurrency.getCurrencyCode() + "` were not fetched along with the entity. " +
				"Entity was fetched with following currency: `" + fetchedCurrency.getCurrencyCode() + "`"
		);
	}

	public static ContextMissingException pricesNotFetched(@Nonnull String requiredPriceList, @Nonnull Set<String> fetchedPriceLists) {
		return new ContextMissingException(
			"Prices in price list `" + requiredPriceList + "` were not fetched along with the entity. " +
				"Entity was fetched with following price lists: " +
				fetchedPriceLists.stream()
					.map(it -> "`" + it + "`")
					.collect(Collectors.joining(", "))
		);
	}

	public static ContextMissingException referenceContextMissing() {
		return new ContextMissingException(
			"No references were fetched along with the entity. You need to use `referenceContent` requirement in " +
				"your `require` part of the query."
		);
	}

	public static ContextMissingException referenceContextMissing(@Nonnull String referenceName) {
		return new ContextMissingException(
			"Reference `" + referenceName + "` was not fetched along with the entity. You need to use `referenceContent` requirement in " +
				"your `require` part of the query."
		);
	}

	public static ContextMissingException referencedEntityContextMissing(@Nonnull String entityName, @Nonnull String referenceName) {
		return new ContextMissingException(
			"Entity `" + entityName + "` references of type `" +
				referenceName + "` were not fetched with `entityFetch` requirement. " +
				"Related entity body is not available."
		);
	}

	public static ContextMissingException referencedEntityGroupContextMissing(@Nonnull String entityName, @Nonnull String referenceName) {
		return new ContextMissingException(
			"Entity `" + entityName + "` references of type `" +
				referenceName + "` were not fetched with `entityGroupFetch` requirement. " +
				"Related entity group body is not available."
		);
	}

	public ContextMissingException() {
		super(
			"Query context is missing. You need to use method getPriceForSale(Currency, OffsetDateTime, Serializable...) " +
				"and provide the context on your own."
		);
	}

	public ContextMissingException(@Nonnull String publicMessage) {
		super(publicMessage);
	}
}
