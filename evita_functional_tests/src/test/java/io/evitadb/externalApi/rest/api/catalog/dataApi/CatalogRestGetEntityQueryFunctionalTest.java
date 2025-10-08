/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.rest.api.catalog.dataApi;

import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.FetchEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.GetEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.ScopeAwareEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for REST catalog single entity query.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
class CatalogRestGetEntityQueryFunctionalTest extends CatalogRestDataEndpointFunctionalTest {

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single product by primary key")
	void shouldReturnSingleProductByPrimaryKey(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final int pk = findEntityPk(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null
		);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pk)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_CODE)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(GetEntityEndpointHeaderDescriptor.PRIMARY_KEY.name(), pk)
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(FetchEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT.name(), "code")
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}

	@Test
	@UseDataSet(REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should return archived entity")
	void shouldReturnArchivedEntity(Evita evita, RestTester tester) {
		final SealedEntity archivedEntity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					scope(Scope.ARCHIVED)
				),
				require(
					page(1, 1),
					entityFetch()
				)
			),
			SealedEntity.class
		);

		final var expectedBodyOfArchivedEntity = createEntityDto(new EntityReference(archivedEntity.getType(), archivedEntity.getPrimaryKey()));

		tester.test(TEST_CATALOG)
			.get("/PRODUCT/get")
			.requestParams(map()
				.e(GetEntityEndpointHeaderDescriptor.PRIMARY_KEY.name(), archivedEntity.getPrimaryKey())
				.e(ScopeAwareEndpointHeaderDescriptor.SCOPE.name(), Scope.ARCHIVED.name())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBodyOfArchivedEntity));
	}

	@Test
	@UseDataSet(REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should not return archived entity without scope")
	void shouldNotReturnArchivedEntityWithoutScope(Evita evita, RestTester tester) {
		final SealedEntity archivedEntity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					scope(Scope.ARCHIVED)
				),
				require(
					page(1, 1),
					entityFetch()
				)
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.post("/PRODUCT/get")
			.requestParam(GetEntityEndpointHeaderDescriptor.PRIMARY_KEY.name(), archivedEntity.getPrimaryKey())
			.executeAndThen()
			.statusCode(404);
	}
	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single product by non-localized attribute")
	void shouldReturnSingleProductByNonLocalizedAttribute(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, codeAttribute),
					entityLocaleEquals(CZECH_LOCALE)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, codeAttribute)
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(FetchEntityEndpointHeaderDescriptor.LOCALE.name(), CZECH_LOCALE.toLanguageTag())
				.e(FetchEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT.name(), Arrays.asList("code", "name"))
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single product by non-localized attribute with locale in URL")
	void shouldReturnSingleProductByNonLocalizedAttributeWithLocaleInUrl(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, codeAttribute),
					entityLocaleEquals(CZECH_LOCALE)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + CZECH_LOCALE.toLanguageTag() + "/PRODUCT/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, codeAttribute)
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(FetchEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT.name(), Arrays.asList(ATTRIBUTE_CODE, ATTRIBUTE_NAME))
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity, true)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return all attributes for single product")
	void shouldReturnAllAttributesForSingleProduct(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final int pk = findEntityPk(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null
		);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pk),
					entityLocaleEquals(CZECH_LOCALE)
				),
				require(
					entityFetch(
						attributeContentAll()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(GetEntityEndpointHeaderDescriptor.PRIMARY_KEY.name(), pk)
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(FetchEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT_ALL.name(), true)
				.e(FetchEntityEndpointHeaderDescriptor.LOCALE.name(), CZECH_LOCALE.toLanguageTag())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single product by localized attribute")
	void shouldReturnSingleProductByLocalizedAttribute(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);
		final int pk = findEntityPk(
			originalProductEntities,
			it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute)
		);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pk),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_URL)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_URL, urlAttribute)
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(FetchEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT.name(), ATTRIBUTE_URL)
				.e(FetchEntityEndpointHeaderDescriptor.LOCALE.name(), Locale.ENGLISH.toLanguageTag())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter single product by non-existent price")
	void shouldFilterSingleProductByNonExistentPrice(RestTester tester, List<SealedEntity> originalProductEntities) {
		final int pk = findEntityWithPricePk(originalProductEntities);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/get/" + pk)
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
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
	void shouldReturnErrorForFilteringProductByNonExistentCurrency(RestTester tester, List<SealedEntity> originalProductEntities) {
		final int pk = findEntityWithPricePk(originalProductEntities);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/get/" + pk)
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_CURRENCY.name(), "AAA")
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_PRICE_LISTS.name(), "basic")
				.build())
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("The value `AAA` cannot be converted to the type `java.util.Currency`!"));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return direct category parent entity references")
	void shouldReturnAllDirectCategoryParentEntityReferences(Evita evita, RestTester tester) {
		final SealedEntity category = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.queryOneSealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(16)
						),
						require(
							entityFetch(
								hierarchyContent()
							)
						)
					)
				).orElseThrow();

				// check that it has at least 2 parents
				assertTrue(entity.getParentEntity().isPresent());
				assertTrue(entity.getParentEntity().get().getParentEntity().isPresent());
				return entity;
			}
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/CATEGORY/get/" + category.getPrimaryKey())
			.httpMethod(Request.METHOD_GET)
			.requestParam(FetchEntityEndpointHeaderDescriptor.HIERARCHY_CONTENT.name(), true)
			.executeAndExpectOkAndThen()
			.body("", equalTo(createEntityDto(category)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for single product")
	void shouldReturnCustomPriceForSaleForSingleProduct(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final int pk = findEntityWithPricePk(originalProductEntities);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pk),
					priceInCurrency(CURRENCY_CZK),
					priceInPriceLists("basic")
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/get/" + pk)
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_CURRENCY.name(), CURRENCY_CZK.getCurrencyCode())
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_PRICE_LISTS.name(), "basic")
				.e(FetchEntityEndpointHeaderDescriptor.PRICE_CONTENT.name(), PriceContentMode.RESPECTING_FILTER.name())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for single product")
	void shouldReturnFilteredPricesForSingleProduct(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final int pk = findEntityWithPricePk(originalProductEntities);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pk),
					priceInCurrency(CURRENCY_CZK),
					priceInPriceLists("basic")
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/get/" + pk)
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_CURRENCY.name(), CURRENCY_CZK.getCurrencyCode())
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_PRICE_LISTS.name(), "basic")
				.e(FetchEntityEndpointHeaderDescriptor.PRICE_CONTENT.name(), PriceContentMode.RESPECTING_FILTER.name())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return all prices for sale for master products")
	void shouldReturnAllPricesForSaleForMasterProducts(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pk = findEntityPk(
			originalProductEntities,
			it -> !it.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.NONE) &&
				it.getPrices(CURRENCY_CZK)
					.stream()
					.filter(PriceContract::indexed)
					.map(PriceContract::innerRecordId)
					.distinct()
					.count() > 1
		);

		final List<String> priceLists = originalProductEntities.stream()
			.filter(it -> it.getPrimaryKey().equals(pk))
			.flatMap(it -> it.getPrices(CURRENCY_CZK).stream().map(PriceContract::priceList))
			.distinct()
			.toList();
		assertTrue(priceLists.size() > 1);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pk),
					priceInCurrency(CURRENCY_CZK),
					priceInPriceLists(priceLists.toArray(String[]::new))
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(GetEntityEndpointHeaderDescriptor.PRIMARY_KEY.name(), pk)
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_CURRENCY.name(), CURRENCY_CZK.getCurrencyCode())
				.e(GetEntityEndpointHeaderDescriptor.PRICE_IN_PRICE_LISTS.name(), priceLists)
				.e(FetchEntityEndpointHeaderDescriptor.PRICE_CONTENT.name(), PriceContentMode.RESPECTING_FILTER.name())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data for single product")
	void shouldReturnAssociatedDataForSingleProduct(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final int pk = findEntityPk(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pk),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						associatedDataContent(ASSOCIATED_DATA_LABELS)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/get/" + pk)
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(FetchEntityEndpointHeaderDescriptor.ASSOCIATED_DATA_CONTENT.name(), ASSOCIATED_DATA_LABELS)
				.e(FetchEntityEndpointHeaderDescriptor.LOCALE.name(), Locale.ENGLISH.toLanguageTag())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data for single product with locale in URL")
	void shouldReturnAssociatedDataForSingleProductWithLocaleInUrl(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final int pk = findEntityPk(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pk),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						associatedDataContent(ASSOCIATED_DATA_LABELS)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/PRODUCT/get/" + pk)
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), Boolean.TRUE)
				.e(FetchEntityEndpointHeaderDescriptor.ASSOCIATED_DATA_CONTENT.name(), ASSOCIATED_DATA_LABELS)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity, true)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for single product")
	void shouldReturnReferenceListForSingleProduct(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final int pk = findEntityPk(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1
		);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pk)
				),
				require(
					entityFetch(
						referenceContentAll()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/get/" + pk)
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(FetchEntityEndpointHeaderDescriptor.REFERENCE_CONTENT_ALL.name(), true)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}
}
