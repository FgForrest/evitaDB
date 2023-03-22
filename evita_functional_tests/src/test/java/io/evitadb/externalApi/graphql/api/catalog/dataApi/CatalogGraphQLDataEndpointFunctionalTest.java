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

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.externalApi.api.catalog.dataApi.model.AssociatedDataDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLEndpointFunctionalTest;
import io.evitadb.test.Entities;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_LABELS;
import static io.evitadb.test.generator.DataGenerator.CURRENCY_CZK;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_BASIC;

/**
 * Ancestor for tests for GraphQL catalog endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class CatalogGraphQLDataEndpointFunctionalTest extends GraphQLEndpointFunctionalTest {

	/**
	 * Returns value of "random" value in the dataset.
	 */
	protected <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities, @Nonnull String attributeName) {
		return getRandomAttributeValue(originalProductEntities, attributeName, 10);
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	protected <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities, @Nonnull String attributeName, int order) {
		return originalProductEntities
			.stream()
			.map(it -> (T) it.getAttribute(attributeName))
			.filter(Objects::nonNull)
			.skip(order)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Failed to localize `" + attributeName + "` attribute!"));
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	protected <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities,
	                                                                           @Nonnull String attributeName,
	                                                                           @Nonnull Locale locale) {
		return getRandomAttributeValue(originalProductEntities, attributeName, locale, 10);
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	protected <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities,
	                                                                           @Nonnull String attributeName,
	                                                                           @Nonnull Locale locale,
	                                                                           int order) {
		return originalProductEntities
			.stream()
			.map(it -> (T) it.getAttribute(attributeName, locale))
			.filter(Objects::nonNull)
			.skip(order)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Failed to localize `" + attributeName + "` attribute!"));
	}


	@Nonnull
	protected Map<String, Object> createEntityDtoWithFormattedPriceForSale(@Nonnull SealedEntity entity) {
		final NumberFormat priceFormatter = NumberFormat.getCurrencyInstance(CZECH_LOCALE);
		priceFormatter.setCurrency(CURRENCY_CZK);

		return map()
			.e(EntityDescriptor.PRICE_FOR_SALE.name(), map()
				.e(PriceDescriptor.PRICE_WITH_TAX.name(), priceFormatter.format(entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax()))
				.build())
			.build();
	}

	@Nonnull
	protected Map<String, Object> createEntityDtoWithFormattedPrice(@Nonnull SealedEntity entity) {
		final NumberFormat priceFormatter = NumberFormat.getCurrencyInstance(CZECH_LOCALE);
		priceFormatter.setCurrency(CURRENCY_CZK);

		return map()
			.e(EntityDescriptor.PRICE.name(), map()
				.e(PriceDescriptor.PRICE_WITH_TAX.name(), priceFormatter.format(entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax()))
				.build())
			.build();
	}


	@Nonnull
	protected Map<String, Object> createEntityDtoWithFormattedPrices(@Nonnull SealedEntity entity) {
		final NumberFormat priceFormatter = NumberFormat.getCurrencyInstance(CZECH_LOCALE);
		priceFormatter.setCurrency(CURRENCY_CZK);

		return map()
			.e(EntityDescriptor.PRICES.name(), List.of(
				map()
					.e(PriceDescriptor.PRICE_WITH_TAX.name(), priceFormatter.format(entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax()))
					.build()
			))
			.build();
	}

	@Nonnull
	protected Map<String, Object> createEntityDtoWithPriceForSale(@Nonnull SealedEntity entity) {
		return map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.PRICE_FOR_SALE.name(), map()
				.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
				.e(PriceDescriptor.CURRENCY.name(), CURRENCY_CZK.toString())
				.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
				.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax().toString())
				.build())
			.build();
	}

	@Nonnull
	protected Map<String, Object> createEntityDtoWithPrice(@Nonnull SealedEntity entity) {
		return map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.PRICE.name(), map()
				.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
				.e(PriceDescriptor.CURRENCY.name(), CURRENCY_CZK.toString())
				.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
				.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax().toString())
				.build())
			.build();
	}

	@Nonnull
	protected Map<String, Object> createEntityDtoWithAssociatedData(@Nonnull SealedEntity entity) {
		return map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.ASSOCIATED_DATA.name(), map()
				.e(TYPENAME_FIELD, AssociatedDataDescriptor.THIS.name(createEmptyEntitySchema("Product")))
				.e(ASSOCIATED_DATA_LABELS, map()
					.build())
				.build())
			.build();
	}
}
