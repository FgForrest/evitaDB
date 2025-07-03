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
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.FetchEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.QueryHeaderFilterArgumentsJoinType;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.ScopeAwareEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.UnknownEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.ATTRIBUTE_RELATIVE_URL;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_LABELS;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GraphQL catalog unknown entity list query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class CatalogRestListUnknownEntitiesQueryFunctionalTest extends CatalogRestDataEndpointFunctionalTest {


	@Test
	@UseDataSet(REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should return archived entities")
	void shouldReturnArchivedEntities(Evita evita, RestTester tester) {
		final List<SealedEntity> archivedEntities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					scope(Scope.ARCHIVED)
				),
				require(
					page(1, 2),
					entityFetch(attributeContent(ATTRIBUTE_CODE))
				)
			),
			SealedEntity.class
		);

		final var expectedBodyOfArchivedEntities = archivedEntities.stream()
			.map(entity -> new EntityReference(entity.getType(), entity.getPrimaryKey()))
			.map(CatalogRestDataEndpointFunctionalTest::createEntityDto)
			.toList();

		tester.test(TEST_CATALOG)
			.get("/entity/list")
			.requestParam(ATTRIBUTE_CODE, archivedEntities.stream().map(it -> it.getAttribute(ATTRIBUTE_CODE)).toList())
			.requestParam(ScopeAwareEndpointHeaderDescriptor.SCOPE.name(), List.of(Scope.ARCHIVED.name()))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBodyOfArchivedEntities));
	}

	@Test
	@UseDataSet(REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should return both live and archived entities explicitly")
	void shouldReturnBothLiveAndArchivedEntitiesExplicitly(Evita evita, RestTester tester) {
		final SealedEntity liveEntity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					scope(Scope.LIVE)
				),
				require(
					page(1, 1),
					entityFetch(attributeContent(ATTRIBUTE_CODE))
				)
			),
			SealedEntity.class
		);
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
		assertNotEquals((String) liveEntity.getAttribute(ATTRIBUTE_CODE), (String) archivedEntity.getAttribute(ATTRIBUTE_CODE));

		final var expectedBodyOfArchivedEntities = Stream.of(liveEntity, archivedEntity)
			.map(entity -> new EntityReference(entity.getType(), entity.getPrimaryKey()))
			.map(CatalogRestDataEndpointFunctionalTest::createEntityDto)
			.toList();

		tester.test(TEST_CATALOG)
			.get("/entity/list")
			.requestParam(ATTRIBUTE_CODE, Stream.of(liveEntity, archivedEntity).map(it -> it.getAttribute(ATTRIBUTE_CODE)).toList())
			.requestParam(ScopeAwareEndpointHeaderDescriptor.SCOPE.name(), List.of(Scope.LIVE.name(), Scope.ARCHIVED.name()))
			.executeAndThen()
			.statusCode(200)
			.body("", containsInAnyOrder(expectedBodyOfArchivedEntities.toArray()));
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
			.get("/entity/list")
			.requestParam(ATTRIBUTE_CODE, Collections.singletonList((String) archivedEntity.getAttribute(ATTRIBUTE_CODE)))
			.executeAndThen()
			.statusCode(200)
			.body("", emptyIterable());
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity list by multiple globally unique attribute")
	void shouldReturnUnknownEntityListByMultipleGloballyUniqueAttribute(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final String codeAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 5);
		final String codeAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 7);

		final EntityClassifier entityWithCode1 = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, codeAttribute1)
				)
			)
		);
		final EntityClassifier entityWithCode2 = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, codeAttribute2)
				)
			)
		);

		tester.test(TEST_CATALOG)
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
						createEntityDto(entityWithCode1),
						createEntityDto(entityWithCode2)
					)
				)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity list with multiple different global attributes")
	void shouldReturnUnknownEntityListWithMultipleGlobalAttributes(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 5);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, codeAttribute),
					entityLocaleEquals(Locale.ENGLISH),
					attributeIsNotNull(ATTRIBUTE_URL)
				)
			)
		);
		final String urlAttribute = (String) getAttributesByPks(evita, new Integer[] {entity.getPrimaryKey()}, ATTRIBUTE_URL, Locale.ENGLISH).get(0);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, List.of(codeAttribute))
				.e(ATTRIBUTE_URL, List.of(urlAttribute))
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(List.of(createEntityDto(entity))));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity list by multiple different global attributes")
	void shouldReturnUnknownEntityListByMultipleDifferentGlobalAttributes(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 5);
		final EntityClassifier entityWithCode = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, codeAttribute)
				)
			)
		);
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH, 7);
		final EntityClassifier entityWithUrl = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_URL, urlAttribute),
					entityLocaleEquals(Locale.ENGLISH)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, List.of(codeAttribute))
				.e(ATTRIBUTE_URL, List.of(urlAttribute))
				.e(UnknownEntityEndpointHeaderDescriptor.FILTER_JOIN.name(), QueryHeaderFilterArgumentsJoinType.OR.toString())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					List.of(
						createEntityDto(entityWithCode),
						createEntityDto(entityWithUrl)
					)
				)
			);
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
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_RELATIVE_URL, List.of(relativeUrl.value()))
				.e(FetchEntityEndpointHeaderDescriptor.LOCALE.name(), relativeUrl.key().locale().toLanguageTag())
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), false)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(List.of(createEntityDto(entity))));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when filtering by globally unique local specific attribute without locale")
	void shouldReturnErrorWhenFilteringByGloballyUniqueLocalSpecificAttributeWithoutLocale(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final AttributeValue relativeUrl = getRandomAttributeValueObject(originalProductEntities, ATTRIBUTE_RELATIVE_URL);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_RELATIVE_URL, List.of(relativeUrl.value()))
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), false)
				.build())
			.executeAndThen()
			.statusCode(400);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return rich unknown entity list by multiple localized globally unique attribute")
	void shouldReturnRichUnknownEntityListByMultipleLocalizedGloballyUniqueAttribute(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final EvitaResponse<SealedEntity> entities = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityLocaleEquals(Locale.ENGLISH),
					attributeIsNotNull(ATTRIBUTE_NAME)
				),
				require(
					page(1, 2),
					entityFetch(
						attributeContentAll()
					)
				)
			),
			SealedEntity.class
		);
		assertEquals(2, entities.getRecordData().size());
		final SealedEntity entityWithUrl1 = entities.getRecordData().get(0);
		final String urlAttribute1 = entityWithUrl1.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH);
		final SealedEntity entityWithUrl2 = entities.getRecordData().get(1);
		final String urlAttribute2 = entityWithUrl2.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_URL, Arrays.asList(urlAttribute1, urlAttribute2))
				.e(FetchEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					List.of(
						createEntityDto(entityWithUrl1),
						createEntityDto(entityWithUrl2)
					)
				)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for entities")
	void shouldReturnPriceForEntities(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final var pks = findEntityWithPricePks(originalProductEntities, 2);

		final List<String> codes = getAttributesByPks(evita, pks, ATTRIBUTE_CODE);

		final List<SealedEntity> entities = getEntities(
			evita,
			query(
				filterBy(
					attributeInSet(ATTRIBUTE_CODE, codes.toArray(String[]::new))
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, codes)
				.e(FetchEntityEndpointHeaderDescriptor.PRICE_CONTENT.name(), PriceContentMode.RESPECTING_FILTER.name())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with custom locale for entities")
	void shouldReturnAssociatedDataWithCustomLocaleForEntities(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null,
			2
		);

		final List<String> codes = getAttributesByPks(evita, pks, ATTRIBUTE_CODE);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				filterBy(
					attributeInSet(ATTRIBUTE_CODE, codes.toArray(String[]::new)),
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
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, codes)
				.e(FetchEntityEndpointHeaderDescriptor.LOCALE.name(), Locale.ENGLISH.toLanguageTag())
				.e(FetchEntityEndpointHeaderDescriptor.ASSOCIATED_DATA_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with custom locale for entities with locale in URL")
	void shouldReturnAssociatedDataWithCustomLocaleForEntitiesWithLocaleInUrl(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null,
			2
		);

		final List<String> codes = getAttributesByPks(evita, pks, ATTRIBUTE_CODE);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				filterBy(
					attributeInSet(ATTRIBUTE_CODE, codes.toArray(String[]::new)),
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
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, codes)
				.e(FetchEntityEndpointHeaderDescriptor.ASSOCIATED_DATA_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities, true)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for entities")
	void shouldReturnReferenceListForEntities(Evita evita, List<SealedEntity> originalProductEntities, RestTester tester) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1,
			2
		);

		final List<String> codes = getAttributesByPks(evita, pks, ATTRIBUTE_CODE);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				filterBy(
					attributeInSet(ATTRIBUTE_CODE, codes.toArray(String[]::new)),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						referenceContentAll()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, codes)
				.e(FetchEntityEndpointHeaderDescriptor.LOCALE.name(), Locale.ENGLISH.toLanguageTag())
				.e(FetchEntityEndpointHeaderDescriptor.REFERENCE_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return direct category parent entity references")
	void shouldReturnAllDirectCategoryParentEntityReferences(Evita evita, RestTester tester) {
		final List<SealedEntity> categories = getEntities(
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
			c -> {
				// check that it has at least 2 parents
				assertTrue(c.getParentEntity().isPresent());
				assertTrue(c.getParentEntity().get().getParentEntity().isPresent());
			},
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/entity/list")
			.httpMethod(Request.METHOD_GET)
			.requestParam(ATTRIBUTE_CODE, "Automotive-21")
			.requestParam(FetchEntityEndpointHeaderDescriptor.HIERARCHY_CONTENT.name(), true)
			.executeAndExpectOkAndThen()
			.body("", equalTo(createEntityDtos(categories)));
	}
}
