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

package io.evitadb.externalApi.rest.io.handler;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.ParamDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.SectionedAssociatedDataDescriptor;
import io.evitadb.externalApi.rest.testSuite.RESTTester.Request;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.rest.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for GraphQL catalog unknown entity list query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class CatalogRESTListUnknownEntitiesQueryFunctionalTest extends CatalogRESTEndpointFunctionalTest {

	@Nonnull
	@Override
	protected String getEndpointPath() {
		return "/test-catalog";
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity list by multiple globally unique attribute")
	void shouldReturnUnknownEntityListByMultipleGloballyUniqueAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		final String codeAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 5);
		final String codeAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 7);
		final SealedEntity entityWithCode1 = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_CODE), codeAttribute1))
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with code attribute"));
		final SealedEntity entityWithCode2 = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_CODE), codeAttribute2))
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with code attribute"));

		testRESTCall()
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, Arrays.asList(codeAttribute1, codeAttribute2))
				.build())
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					List.of(
						map()
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCode1.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.build(),
						map()
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCode2.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.build()
					)
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return rich unknown entity list by multiple localized globally unique attribute")
	void shouldReturnRichUnknownEntityListByMultipleLocalizedGloballyUniqueAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		final String urlAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH, 5);
		final String urlAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH, 7);
		final SealedEntity entityWithUrl1 = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute1) &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null)
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with url attribute"));
		final SealedEntity entityWithUrl2 = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute2) &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null)
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with url attribute"));

		testRESTCall()
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_URL, Arrays.asList(urlAttribute1, urlAttribute2))
				.e(ParamDescriptor.ATTRIBUTE_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					List.of(
						map()
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithUrl1.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toLanguageTag()))
							.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
							.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
							.e(EntityDescriptor.ATTRIBUTES.name(), createEntityAttributes(entityWithUrl1, true, Locale.ENGLISH))
							.build(),
						map()
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithUrl2.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toLanguageTag()))
							.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
							.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
							.e(EntityDescriptor.ATTRIBUTES.name(), createEntityAttributes(entityWithUrl2, true, Locale.ENGLISH))
							.build()
					)
				)
			);
	}

	/*@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for products")
	void shouldReturnCustomPriceForSaleForEntities(Evita evita, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String,Object>> expectedBody = entities.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.PRICE_FOR_SALE.name(), map()
						.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
						.e(PriceDescriptor.CURRENCY.name(), CURRENCY_CZK.toString())
						.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
						.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax().toString())
						.build())
					.build()
			)
			.toList();

		testRESTCall()
			.document(
				"""
	                query {
	                    list_entity(
	                        code: ["%s", "%s"]
	                    ) {
	                        primaryKey
	                        type
                            ... on Product {
                                priceForSale(currency: CZK, priceList: "basic") {
                                    __typename
	                                currency
	                                priceList
	                                priceWithTax
	                            }
                            }
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(ENTITY_LIST_PATH, equalTo(expectedBody));
	}*/

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for entities")
	void shouldReturnPriceForEntities(Evita evita, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.LOCALES.name(), List.of())
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.NONE.name())
					.e(EntityDescriptor.PRICES.name(), createPricesDto(entity))
					.build()
			)
			.toList();

		testRESTCall()
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, Arrays.asList(
					entities.get(0).getAttribute(ATTRIBUTE_CODE),
					entities.get(1).getAttribute(ATTRIBUTE_CODE)))
				.e(ParamDescriptor.PRICE_CONTENT.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with custom locale for entities")
	void shouldReturnAssociatedDataWithCustomLocaleForEntities(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);

		final var expectedBody = entities.stream()
			.map(entity ->
				createEntityDtoWithAssociatedData(entity, Locale.ENGLISH, true)
			)
			.toList();

		testRESTCall()
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, Arrays.asList(
					entities.get(0).getAttribute(ATTRIBUTE_CODE),
					entities.get(1).getAttribute(ATTRIBUTE_CODE)))
				.e(ParamDescriptor.LOCALE.name(), Locale.ENGLISH.toLanguageTag())
				.e(ParamDescriptor.ASSOCIATED_DATA_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with custom locale for entities with locale in URL")
	void shouldReturnAssociatedDataWithCustomLocaleForEntitiesWithLocaleInUrl(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);

		final var expectedBody = entities.stream()
			.map(entity ->
				createEntityDtoWithAssociatedData(entity, Locale.ENGLISH, false)
			)
			.toList();

		testRESTCall()
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, Arrays.asList(
					entities.get(0).getAttribute(ATTRIBUTE_CODE),
					entities.get(1).getAttribute(ATTRIBUTE_CODE)))
				.e(ParamDescriptor.ASSOCIATED_DATA_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for entities")
	void shouldReturnReferenceListForEntities(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1
		);

		final var expectedBody = entities.stream()
			.map(entity ->
				createReferencesDto(entity, Entities.STORE, false)
			)
			.toList();

		testRESTCall()
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, Arrays.asList(
					entities.get(0).getAttribute(ATTRIBUTE_CODE),
					entities.get(1).getAttribute(ATTRIBUTE_CODE)))
				.e(ParamDescriptor.LOCALE.name(), Locale.ENGLISH.toLanguageTag())
				.e(ParamDescriptor.REFERENCE_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("store", equalTo(expectedBody));
	}

	@Override
	@Nonnull
	protected Map<String, Object> createEntityDtoWithAssociatedData(@Nonnull SealedEntity entity, @Nonnull Locale requestedLocale, @Nonnull boolean distinguishLocalizedData) {
		final ArrayList<Object> entityLocales = new ArrayList<>(1);
		entityLocales.add(entity.getLocales().stream().filter(locale -> locale.equals(requestedLocale)).findFirst().orElseThrow().toLanguageTag());

		final Map<String, Object> associatedData;
		if (distinguishLocalizedData) {
			associatedData = map()
				.e(SectionedAssociatedDataDescriptor.GLOBAL.name(), map()
					.e("referencedFiles", map()
						.e("root", map().build())
						.build()
					)
					.e("localization", map()
						.e("root", map().build())
						.build()
					)
					.build())
				.e(SectionedAssociatedDataDescriptor.LOCALIZED.name(), map()
					.e(Locale.ENGLISH.toLanguageTag(), map()
						.e(ASSOCIATED_DATA_LABELS, map()
							.e("root", map().build())
							.build())
						.build())
					.build())
				.build();
		} else {
			associatedData = map()
				.e("referencedFiles", map()
					.e("root", map().build())
					.build()
				)
				.e("localization", map()
					.e("root", map().build())
					.build()
				)
				.e(ASSOCIATED_DATA_LABELS, map()
					.e("root", map().build())
					.build())
				.build();
		}

		return map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.LOCALES.name(), entityLocales)
			.e(EntityDescriptor.ALL_LOCALES.name(), entity.getAllLocales().stream().map(Locale::toLanguageTag).collect(Collectors.toCollection(ArrayList::new)))
			.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
			.e(EntityDescriptor.ASSOCIATED_DATA.name(), associatedData)
			.build();
	}

	@Nonnull
	private List<SealedEntity> findEntities(@Nonnull List<SealedEntity> originalProductEntities,
	                                        @Nonnull Predicate<SealedEntity> filter) {
		final List<SealedEntity> entities = originalProductEntities.stream()
			.filter(filter)
			.limit(2)
			.toList();
		assertEquals(2, entities.size());
		return entities;
	}

	@Nonnull
	private List<SealedEntity> findEntitiesWithPrice(List<SealedEntity> originalProductEntities) {
		return findEntities(
			originalProductEntities,
			it -> it.getPrices(CURRENCY_CZK, "basic").size() == 1
		);
	}
}
