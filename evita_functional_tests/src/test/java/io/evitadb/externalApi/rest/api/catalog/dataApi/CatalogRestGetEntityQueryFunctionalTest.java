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

package io.evitadb.externalApi.rest.api.catalog.dataApi;

import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.SectionedAttributesDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.GetEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestTester.Request;
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests for REST catalog single entity query.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
class CatalogRestGetEntityQueryFunctionalTest extends CatalogRestDataEndpointFunctionalTest {

	@Nonnull
	@Override
	protected String getEndpointPath() {
		return "/test-catalog";
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single product by primary key")
	void shouldReturnSingleProductByPrimaryKey(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null
		);

		testRestCall()
			.urlPathSuffix("/product/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(GetEntityEndpointHeaderDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
				.e(GetEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(GetEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT.name(), "code")
				.build())
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.LOCALES.name(), List.of())
						.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
						.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
						.e(EntityDescriptor.ATTRIBUTES.name(), map()
							.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
								.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
								.build())
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single product by non-localized attribute")
	void shouldReturnSingleProductByNonLocalizedAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		final SealedEntity entityWithCode = findEntity(
			originalProductEntities,
			it -> Objects.equals(it.getAttribute(ATTRIBUTE_CODE), codeAttribute) &&
				it.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE) != null &&
				it.getAllLocales().contains(CZECH_LOCALE) &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);


		testRestCall()
			.urlPathSuffix("/product/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, codeAttribute)
				.e(GetEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(GetEntityEndpointHeaderDescriptor.LOCALE.name(), CZECH_LOCALE.toLanguageTag())
				.e(GetEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT.name(), Arrays.asList("code", "name"))
				.build())
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCode.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag()))
						.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
						.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
						.e(EntityDescriptor.ATTRIBUTES.name(),
							map()
								.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
									.e(ATTRIBUTE_CODE, codeAttribute)
									.build())
								.e(SectionedAttributesDescriptor.LOCALIZED.name(), map()
									.e(CZECH_LOCALE.toLanguageTag(), map()
										.e(ATTRIBUTE_NAME, entityWithCode.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE))
										.build())
									.build())
								.build()
						)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single product by non-localized attribute with locale in URL")
	void shouldReturnSingleProductByNonLocalizedAttributeWithLocaleInUrl(Evita evita, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		final SealedEntity entityWithCode = findEntity(
			originalProductEntities,
			it -> Objects.equals(it.getAttribute(ATTRIBUTE_CODE), codeAttribute) &&
				it.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE) != null &&
				it.getAllLocales().contains(CZECH_LOCALE) &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);


		testRestCall()
			.urlPathSuffix("/" + CZECH_LOCALE.toLanguageTag() + "/product/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, codeAttribute)
				.e(GetEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(GetEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT.name(), Arrays.asList("code", "name"))
				.build())
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCode.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag()))
						.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
						.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
						.e(EntityDescriptor.ATTRIBUTES.name(),
							map()
								.e(ATTRIBUTE_CODE, codeAttribute)
								.e(ATTRIBUTE_NAME, entityWithCode.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE))
								.build()
						)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return all attributes for single product")
	void shouldReturnAllAttributesForSingleProduct(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null
		);

		testRestCall()
			.urlPathSuffix("/product/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(GetEntityEndpointHeaderDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
				.e(GetEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(GetEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT.name(), Arrays.asList(ATTRIBUTE_CODE, ATTRIBUTE_ALIAS, ATTRIBUTE_QUANTITY,
					ATTRIBUTE_PRIORITY, TestDataGenerator.ATTRIBUTE_MANUFACTURED, TestDataGenerator.ATTRIBUTE_VISIBLE, TestDataGenerator.ATTRIBUTE_CREATED, ATTRIBUTE_URL, ATTRIBUTE_NAME))
				.e(GetEntityEndpointHeaderDescriptor.LOCALE.name(), CZECH_LOCALE.toLanguageTag())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body(
				"attributes",
				equalTo(
					map()
						.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
							.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
							.e(ATTRIBUTE_ALIAS, entity.getAttribute(ATTRIBUTE_ALIAS))
							.e(ATTRIBUTE_QUANTITY, serializeToJsonValue(entity.getAttribute(ATTRIBUTE_QUANTITY)))
							.e(ATTRIBUTE_PRIORITY, serializeToJsonValue(entity.getAttribute(ATTRIBUTE_PRIORITY)))
							.e(TestDataGenerator.ATTRIBUTE_MANUFACTURED, serializeToJsonValue(entity.getAttribute(TestDataGenerator.ATTRIBUTE_MANUFACTURED)))
							.e(TestDataGenerator.ATTRIBUTE_VISIBLE, entity.getAttribute(TestDataGenerator.ATTRIBUTE_VISIBLE))
							.e(TestDataGenerator.ATTRIBUTE_CREATED, serializeToJsonValue(entity.getAttribute(TestDataGenerator.ATTRIBUTE_CREATED)))
							.build())
						.e(SectionedAttributesDescriptor.LOCALIZED.name(), map()
							.e(CZECH_LOCALE.toLanguageTag(), map()
								.e(ATTRIBUTE_URL, entity.getAttribute(ATTRIBUTE_URL, CZECH_LOCALE))
								.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE))
								.build())
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single product by localized attribute")
	void shouldReturnSingleProductByLocalizedAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);
		final SealedEntity entityWithUrl = findEntity(
			originalProductEntities,
			it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute)
		);

		testRestCall()
			.urlPathSuffix("/product/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_URL, urlAttribute)
				.e(GetEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(GetEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT.name(), ATTRIBUTE_URL)
				.e(GetEntityEndpointHeaderDescriptor.LOCALE.name(), Locale.ENGLISH.toLanguageTag())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("",
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithUrl.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toLanguageTag()))
						.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
						.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
						.e(EntityDescriptor.ATTRIBUTES.name(),
							map()
								.e(SectionedAttributesDescriptor.LOCALIZED.name(), map()
									.e(Locale.ENGLISH.toLanguageTag(), map()
										.e(ATTRIBUTE_URL, urlAttribute)
										.build())
									.build())
								.build()
						)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter single product by non-existent price")
	void shouldFilterSingleProductByNonExistentPrice(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		testRestCall()
			.urlPathSuffix("/product/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_CURRENCY.name(), CURRENCY_CZK.getCurrencyCode())
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_PRICE_LISTS.name(), "nonexistent")
				.build())
			.executeAndThen()
			.statusCode(404)
			.body("message", equalTo("Requested resource wasn't found."));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for filtering product by non-existent currency")
	void shouldReturnErrorForFilteringProductByNonExistentCurrency(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		testRestCall()
			.urlPathSuffix("/product/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_CURRENCY.name(), "AAA")
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_PRICE_LISTS.name(), "basic")
				.build())
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("The value `AAA` cannot be converted to the type `java.util.Currency`!"));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for single product")
	void shouldReturnCustomPriceForSaleForSingleProduct(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		testRestCall()
			.urlPathSuffix("/product/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_CURRENCY.name(), CURRENCY_CZK.getCurrencyCode())
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_PRICE_LISTS.name(), "basic")
				.e(GetEntityEndpointHeaderDescriptor.PRICE_CONTENT.name(), PriceContentMode.RESPECTING_FILTER.name())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("priceForSale", equalTo(createPriceForSaleDto(entity, CURRENCY_CZK, "basic")));
	}


	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for single product")
	void shouldReturnFilteredPricesForSingleProduct(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		testRestCall()
			.urlPathSuffix("/product/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_CURRENCY.name(), CURRENCY_CZK.getCurrencyCode())
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_PRICE_LISTS.name(), "basic")
				.e(GetEntityEndpointHeaderDescriptor.PRICE_CONTENT.name(), PriceContentMode.RESPECTING_FILTER.name())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body(
				"prices",
				equalTo(createPricesDto(entity, CURRENCY_CZK, "basic"))
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data for single product")
	void shouldReturnAssociatedDataForSingleProduct(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);

		testRestCall()
			.urlPathSuffix("/product/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
				.e(GetEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(GetEntityEndpointHeaderDescriptor.ASSOCIATED_DATA_CONTENT.name(), ASSOCIATED_DATA_LABELS)
				.e(GetEntityEndpointHeaderDescriptor.LOCALE.name(), Locale.ENGLISH.toLanguageTag())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtoWithAssociatedData(entity, Locale.ENGLISH, true)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data for single product with locale in URL")
	void shouldReturnAssociatedDataForSingleProductWithLocaleInUrl(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);

		testRestCall()
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/product/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
				.e(GetEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(GetEntityEndpointHeaderDescriptor.ASSOCIATED_DATA_CONTENT.name(), ASSOCIATED_DATA_LABELS)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtoWithAssociatedData(entity, Locale.ENGLISH, false)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for single product")
	void shouldReturnReferenceListForSingleProduct(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1
		);

		testRestCall()
			.urlPathSuffix("/product/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
				.e(GetEntityEndpointHeaderDescriptor.REFERENCE_CONTENT_ALL.name(), true)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body(
				Entities.STORE.toLowerCase(),
				equalTo(createReferencesDto(entity, Entities.STORE, false))
			);
	}

	@Nonnull
	private SealedEntity findEntity(@Nonnull List<SealedEntity> originalProductEntities,
	                                @Nonnull Predicate<SealedEntity> filter) {
		return originalProductEntities.stream()
			.filter(filter)
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("No entity to test."));
	}

	@Nonnull
	private SealedEntity findEntityWithPrice(List<SealedEntity> originalProductEntities) {
		return findEntity(
			originalProductEntities,
			it -> it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1
		);
	}
}
