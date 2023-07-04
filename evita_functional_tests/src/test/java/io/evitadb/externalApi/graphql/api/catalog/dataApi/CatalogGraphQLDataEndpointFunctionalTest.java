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

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.api.catalog.dataApi.model.AssociatedDataDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLEndpointFunctionalTest;
import io.evitadb.test.Entities;
import io.evitadb.test.builder.MapBuilder;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.text.NumberFormat;
import java.util.Collection;
import java.util.Currency;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
		return createEntityDtoWithPrice(entity, CURRENCY_CZK, PRICE_LIST_BASIC);
	}

	@Nonnull
	protected Map<String, Object> createEntityDtoWithPrice(@Nonnull SealedEntity entity, @Nonnull Currency currency, @Nonnull String priceList) {
		final Collection<PriceContract> prices = entity.getPrices(currency, priceList);
		assertEquals(1, prices.size());
		return map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.PRICE.name(), map()
				.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
				.e(PriceDescriptor.CURRENCY.name(), currency.toString())
				.e(PriceDescriptor.PRICE_LIST.name(), priceList)
				.e(PriceDescriptor.PRICE_WITH_TAX.name(), prices.iterator().next().getPriceWithTax().toString())
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

	@Nonnull
	protected Map<String, Object> createEntityWithSelfParentsDto(@Nonnull SealedEntity hierarchicalEntity, boolean withBody) {
		EntityClassifierWithParent node = hierarchicalEntity;
		final Deque<EntityClassifierWithParent> parents = new LinkedList<>();
		EntityClassifierWithParent parentNode;
		while ((parentNode = node.getParentEntity().orElse(null)) != null) {
			parents.addFirst(parentNode);
			node = parentNode;
		}

		final Map<String, Object> entityWithParentsDto = map()
			.e(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.name(), hierarchicalEntity.getParent().isPresent() ? hierarchicalEntity.getParent().getAsInt() : null)
			.e(GraphQLEntityDescriptor.PARENTS.name(), parents.stream()
				.map(entityClassifier -> {
					final MapBuilder parentBuilder = map()
						.e(GraphQLEntityDescriptor.PRIMARY_KEY.name(), entityClassifier.getPrimaryKey())
						.e(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.name(), entityClassifier.getParentEntity()
							.map(EntityClassifier::getPrimaryKey)
							.orElse(null));

					if (withBody) {
						final SealedEntity parent = (SealedEntity) entityClassifier;
						parentBuilder
							.e(GraphQLEntityDescriptor.ALL_LOCALES.name(), parent.getAllLocales()
								.stream()
								.map(Locale::toLanguageTag)
								.toList())
							.e(GraphQLEntityDescriptor.ATTRIBUTES.name(), map()
								.e(ATTRIBUTE_CODE, parent.getAttribute(ATTRIBUTE_CODE)));
					}

					return parentBuilder.build();
				})
				.toList())
			.build();

		return entityWithParentsDto;
	}

	@Nonnull
	protected Map<String, Object> createEntityWithReferencedParentsDto(@Nonnull SealedEntity entity,
	                                                                   @Nonnull String referenceName,
	                                                                   boolean withBody) {
		return map()
			.e(StringUtils.toCamelCase(referenceName), entity.getReferences(referenceName)
				.stream()
				.map(it -> {
					final SealedEntity referencedEntity = it.getReferencedEntity().orElseThrow();
					return map()
						.e(ReferenceDescriptor.REFERENCED_ENTITY.name(), createEntityWithSelfParentsDto(referencedEntity, withBody))
						.build();
				})
				.toList())
			.build();
	}
}
