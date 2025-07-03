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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.FetchEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.ScopeAwareEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.UnknownEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.ATTRIBUTE_RELATIVE_URL;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_LABELS;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for REST catalog unknown single entity query.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
class CatalogRestGetUnknownEntityQueryFunctionalTest extends CatalogRestDataEndpointFunctionalTest {


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
					entityFetch(attributeContent(ATTRIBUTE_CODE))
				)
			),
			SealedEntity.class
		);

		final var expectedBodyOfArchivedEntities = createEntityDto(new EntityReference(archivedEntity.getType(), archivedEntity.getPrimaryKey()));

		tester.test(TEST_CATALOG)
			.get("/entity/get")
			.requestParam(ATTRIBUTE_CODE, archivedEntity.getAttribute(ATTRIBUTE_CODE))
			.requestParam(ScopeAwareEndpointHeaderDescriptor.SCOPE.name(), List.of(Scope.ARCHIVED.name()))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBodyOfArchivedEntities));
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
					entityFetch(attributeContent(ATTRIBUTE_CODE))
				)
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.get("/entity/get")
			.requestParam(ATTRIBUTE_CODE, Collections.singletonList((String) archivedEntity.getAttribute(ATTRIBUTE_CODE)))
			.executeAndThen()
			.statusCode(404);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity by globally unique attribute")
	void shouldReturnUnknownEntityByGloballyUniqueAttribute(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, codeAttribute)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, codeAttribute)
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), false)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity by globally unique locale specific attribute without specifying collection")
	void shouldReturnUnknownEntityByGloballyUniqueLocaleSpecificAttributeWithoutSpecifyingCollection(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final AttributeValue relativeUrl = getRandomAttributeValueObject(originalProductEntities, ATTRIBUTE_RELATIVE_URL);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_RELATIVE_URL, relativeUrl.value()),
					entityLocaleEquals(relativeUrl.key().locale())
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_RELATIVE_URL, relativeUrl.value())
				.e(FetchEntityEndpointHeaderDescriptor.LOCALE.name(), relativeUrl.key().locale().toLanguageTag())
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), false)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when filtering by globally unique local specific attribute without locale")
	void shouldReturnErrorWhenFilteringByGloballyUniqueLocalSpecificAttributeWithoutLocale(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final AttributeValue relativeUrl = getRandomAttributeValueObject(originalProductEntities, ATTRIBUTE_RELATIVE_URL);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_RELATIVE_URL, relativeUrl.value())
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), false)
				.build())
			.executeAndThen()
			.statusCode(400);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity with multiple different global attributes")
	void shouldReturnUnknownEntityWithMultipleDifferentGlobalAttributes(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		final SealedEntity entityWithCode = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, codeAttribute)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_URL),
						dataInLocales(Locale.ENGLISH)
					)
				)
			),
			SealedEntity.class
		);
		final String urlAttribute = entityWithCode.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, codeAttribute),
					attributeEquals(ATTRIBUTE_URL, urlAttribute)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, codeAttribute)
				.e(ATTRIBUTE_URL, urlAttribute)
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), false)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity by multiple different global attributes")
	void shouldReturnUnknownEntityByMultipleDifferentGlobalAttributes(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				filterBy(
					or(
						attributeEquals(ATTRIBUTE_CODE, codeAttribute),
						attributeEquals(ATTRIBUTE_URL, "somethingWhichDoesntExist")
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, codeAttribute)
				.e(ATTRIBUTE_URL, "somethingWhichDoesntExist")
				.e(UnknownEntityEndpointHeaderDescriptor.FILTER_JOIN.name(), "OR")
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), false)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}


	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return rich unknown entity by localized globally unique attribute")
	void shouldReturnRichUnknownEntityByLocalizedGloballyUniqueAttribute(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_URL, urlAttribute)
				),
				require(
					entityFetch(
						attributeContentAll()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_URL, urlAttribute)
				.e(FetchEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return rich unknown entity by localized globally unique attribute with locale in URL")
	void shouldReturnRichUnknownEntityByLocalizedGloballyUniqueAttributeWithLocaleInUrl(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_URL, urlAttribute),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						attributeContentAll()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/entity/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_URL, urlAttribute)
				.e(FetchEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity, true)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when request contains no parameter")
	void shouldReturnErrorWhenRequestContainsNoParameter(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/get")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(404)
			.body("message", equalTo("Requested resource wasn't found."));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for single entity")
	void shouldReturnPriceForSingleEntity(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final int pk = findEntityWithPricePk(originalProductEntities);
		final String code = (String) getAttributesByPks(evita, new Integer[]{pk}, ATTRIBUTE_CODE).get(0);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, code)
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/get/")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, code)
				.e(FetchEntityEndpointHeaderDescriptor.PRICE_CONTENT.name(), PriceContentMode.RESPECTING_FILTER.name())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}


	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return direct category parent entity references")
	@Disabled
	void shouldReturnAllDirectCategoryParentEntityReferences(Evita evita, RestTester tester) {
		final SealedEntity category = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, "Automotive-21")
				),
				require(
					entityFetch(
						hierarchyContent()
					)
				)
			),
			SealedEntity.class
		);
		// check that it has at least 2 parents
		assertTrue(category.getParentEntity().isPresent());
		assertTrue(category.getParentEntity().get().getParentEntity().isPresent());

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/get")
			.httpMethod(Request.METHOD_GET)
			.requestParam(FetchEntityEndpointHeaderDescriptor.HIERARCHY_CONTENT.name(), true)
			.executeAndExpectOkAndThen()
			.body("", equalTo(createEntityDto(category)));
	}


	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with custom locale for single entity")
	void shouldReturnAssociatedDataWithCustomLocaleForSingleEntity(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final int pk = findEntityPk(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);
		final String code = (String) getAttributesByPks(evita, new Integer[]{pk}, ATTRIBUTE_CODE).get(0);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, code),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						associatedDataContentAll()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/get/")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, code)
				.e(FetchEntityEndpointHeaderDescriptor.LOCALE.name(), Locale.ENGLISH.toLanguageTag())
				.e(FetchEntityEndpointHeaderDescriptor.ASSOCIATED_DATA_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDto(entity)));
	}
}
