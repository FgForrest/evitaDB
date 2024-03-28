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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.api.catalog.dataApi.model.AssociatedDataDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GlobalEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLEndpointFunctionalTest;
import io.evitadb.test.Entities;
import io.evitadb.test.builder.MapBuilder;
import io.evitadb.test.tester.GraphQLTester;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Currency;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ancestor for tests for GraphQL catalog endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class CatalogGraphQLDataEndpointFunctionalTest extends GraphQLEndpointFunctionalTest implements ExternalApiFunctionTestsSupport {

	/**
	 * Inserts product with only mandatory attributes so it passes validation, otherwise it's empty.
	 */
	protected int insertMinimalEmptyProduct(GraphQLTester tester) {
		return tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertProduct(
                            entityExistence: MUST_NOT_EXIST,
                            mutations: [
                                {
                                    upsertAttributeMutation: {
                                        name: "code",
                                        value: "pwoa"
                                    },
                                },
                                {
                                    upsertAttributeMutation: {
										name: "visible",
										value: true
									},
								},
                                {
									upsertAttributeMutation: {
										name: "created",
										value: "%s"
									},
								},
                                {
									upsertAttributeMutation: {
										name: "priority",
										value: 1
									},
								},
                                {
									upsertAttributeMutation: {
										name: "size",
										value: [[1,2]]
									},
								},
                                {
									upsertAttributeMutation: {
										name: "manufactured",
										value: "%s"
									}
                                },
                                {
                                    insertReferenceMutation: {
                                        name: "PARAMETER"
                                        primaryKey: 1
                                    }
                                },
                                {
                                    referenceAttributeMutation: {
										name: "PARAMETER",
										primaryKey: 1,
										attributeMutation: {
											upsertAttributeMutation: {
												name: "marketShare",
												value: "1.1"
											}
										}
                                    }
                                }
                            ]
                        ) {
	                        primaryKey
	                    }
	                }
					""",
				OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
				OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.extract()
			.path("data.upsertProduct.primaryKey");
	}

	@Nonnull
	protected Map<String, Object> createEntityDtoWithFormattedPriceForSale(@Nonnull SealedEntity entity) {
		final NumberFormat priceFormatter = NumberFormat.getCurrencyInstance(CZECH_LOCALE);
		priceFormatter.setCurrency(CURRENCY_CZK);

		return map()
			.e(EntityDescriptor.PRICE_FOR_SALE.name(), map()
				.e(PriceDescriptor.PRICE_WITH_TAX.name(), priceFormatter.format(entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().priceWithTax()))
				.build())
			.build();
	}

	@Nonnull
	protected Map<String, Object> createEntityDtoWithFormattedPrice(@Nonnull SealedEntity entity) {
		final NumberFormat priceFormatter = NumberFormat.getCurrencyInstance(CZECH_LOCALE);
		priceFormatter.setCurrency(CURRENCY_CZK);

		return map()
			.e(EntityDescriptor.PRICE.name(), map()
				.e(PriceDescriptor.PRICE_WITH_TAX.name(), priceFormatter.format(entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().priceWithTax()))
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
					.e(PriceDescriptor.PRICE_WITH_TAX.name(), priceFormatter.format(entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().priceWithTax()))
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
				.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPriceForSale(CURRENCY_CZK, null, PRICE_LIST_BASIC)
					.orElseThrow()
					.priceWithTax()
					.toString())
				.build())
			.build();
	}

	@Nonnull
	protected Map<String, Object> createEntityDtoWithOnlyOnePriceForSale(@Nonnull SealedEntity entity) {
		return map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.PRICE_FOR_SALE.name(), map()
				.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
				.e(PriceDescriptor.CURRENCY.name(), CURRENCY_CZK.toString())
				.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
				.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPriceForSale(CURRENCY_CZK, null, PRICE_LIST_BASIC)
					.orElseThrow()
					.priceWithTax()
					.toString())
				.build())
			.e(GraphQLEntityDescriptor.MULTIPLE_PRICES_FOR_SALE_AVAILABLE.name(), false)
			.build();
	}

	@Nonnull
	protected Map<String, Object> createEntityDtoWithAllPricesForSale(@Nonnull SealedEntity entity, @Nonnull String... priceLists) {
		return map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(GraphQLEntityDescriptor.MULTIPLE_PRICES_FOR_SALE_AVAILABLE.name(), true)
			.e(GraphQLEntityDescriptor.ALL_PRICES_FOR_SALE.name(), entity.getAllPricesForSale(CURRENCY_CZK, null, priceLists)
				.stream()
				.map(price -> map()
					.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
					.e(PriceDescriptor.CURRENCY.name(), CURRENCY_CZK.toString())
					.e(PriceDescriptor.PRICE_LIST.name(), price.priceList())
					.e(PriceDescriptor.PRICE_WITH_TAX.name(), price.priceWithTax().toString())
					.e(PriceDescriptor.INNER_RECORD_ID.name(), price.innerRecordId())
					.build())
				.toList())
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
				.e(PriceDescriptor.PRICE_WITH_TAX.name(), prices.iterator().next().priceWithTax().toString())
				.build())
			.build();
	}

	@Nonnull
	protected Map<String, Object> createEntityDtoWithPrices(@Nonnull SealedEntity entity) {
		return map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.PRICES.name(), List.of(
				map()
					.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
					.e(PriceDescriptor.CURRENCY.name(), CURRENCY_CZK.toString())
					.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
					.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().priceWithTax().toString())
					.build()
			))
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
			.e(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.name(), hierarchicalEntity.getParentEntity().isPresent() ? hierarchicalEntity.getParentEntity().get().getPrimaryKey() : null)
			.e(GraphQLEntityDescriptor.PARENTS.name(), parents.stream()
				.map(entityClassifier -> {
					final MapBuilder parentBuilder = map()
						.e(GraphQLEntityDescriptor.PRIMARY_KEY.name(), entityClassifier.getPrimaryKey());

					if (withBody) {
						final SealedEntity parent = (SealedEntity) entityClassifier;
						parentBuilder
							.e(GraphQLEntityDescriptor.ALL_LOCALES.name(), parent.getAllLocales()
								.stream()
								.map(Locale::toString)
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

	@Nonnull
	protected Map<String, Object> createTargetEntityDto(@Nonnull Map<String, Object> entityDto) {
		return map()
			.e(GlobalEntityDescriptor.TARGET_ENTITY.name(), entityDto)
			.build();
	}

	@Nonnull
	protected Map<String, Object> createTargetEntityDto(@Nonnull Map<String, Object> entityDto, boolean extractCommonFields) {
		if (!extractCommonFields) {
			return createTargetEntityDto(entityDto);
		}
		final MapBuilder newEntityDto = map();

		final Map<String, Object> newTargetEntityDto = new HashMap<>(entityDto);
		Optional.ofNullable(newTargetEntityDto.remove(EntityDescriptor.PRIMARY_KEY.name()))
			.ifPresent(it -> newEntityDto.e(EntityDescriptor.PRIMARY_KEY.name(), it));
		Optional.ofNullable(newTargetEntityDto.remove(EntityDescriptor.TYPE.name()))
			.ifPresent(it -> newEntityDto.e(EntityDescriptor.TYPE.name(), it));

		return newEntityDto
			.e(GlobalEntityDescriptor.TARGET_ENTITY.name(), newTargetEntityDto)
			.build();
	}
}
