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
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for GraphQL catalog unknown entity list query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLListUnknownEntitiesQueryFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final String ENTITY_LIST_PATH = "data.listEntity";

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity list by multiple globally unique attribute")
	void shouldReturnUnknownEntityListByMultipleGloballyUniqueAttribute(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(code: ["%s","%s"]) {
	                        __typename
	                        primaryKey
	                        type
	                    }
	                }
					""",
				codeAttribute1,
				codeAttribute2
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				ENTITY_LIST_PATH,
				equalTo(
					List.of(
						map()
							.e(TYPENAME_FIELD, GraphQLEntityDescriptor.THIS_GLOBAL.name())
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCode1.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.build(),
						map()
							.e(TYPENAME_FIELD, GraphQLEntityDescriptor.THIS_GLOBAL.name())
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCode2.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.build()
					)
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return rich unknown entity list by multiple localized globally unique attribute")
	void shouldReturnRichUnknownEntityListByMultipleLocalizedGloballyUniqueAttribute(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String urlAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH, 5);
		final String urlAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH, 7);
		final SealedEntity entityWithUrl1 = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute1))
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with url attribute"));
		final SealedEntity entityWithUrl2 = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute2))
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with url attribute"));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(url: ["%s","%s"]) {
	                        primaryKey
	                        type
                            attributes {
                                __typename
                                url
                            }
	                    }
	                }
					""",
				urlAttribute1,
				urlAttribute2
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				ENTITY_LIST_PATH,
				equalTo(
					List.of(
						map()
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithUrl1.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.e(EntityDescriptor.ATTRIBUTES.name(), map()
								.e(TYPENAME_FIELD, AttributesDescriptor.THIS_GLOBAL.name())
								.e(ATTRIBUTE_URL, urlAttribute1)
								.build())
							.build(),
						map()
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithUrl2.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.e(EntityDescriptor.ATTRIBUTES.name(), map()
								.e(TYPENAME_FIELD, AttributesDescriptor.THIS_GLOBAL.name())
								.e(ATTRIBUTE_URL, urlAttribute2)
								.build())
							.build()
					)
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return localized rich unknown entity list by multiple non-localized globally unique attribute")
	void shouldReturnLocalizedRichUnknownEntityListByMultipleNonLocalizedGloballyUniqueAttribute(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 5);
		final String codeAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 7);
		final SealedEntity entityWithCodeAndUrl1 = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_CODE), codeAttribute1) &&
				it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null)
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with url attribute"));
		final SealedEntity entityWithCodeAndUrl2 = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_CODE), codeAttribute2) &&
				it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null)
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with url attribute"));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(code: ["%s","%s"]) {
	                        primaryKey
	                        type
                            attributes(locale: en) {
                                __typename
                                code
                                url
                            }
	                    }
	                }
					""",
				codeAttribute1,
				codeAttribute2
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				ENTITY_LIST_PATH,
				equalTo(
					List.of(
						map()
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCodeAndUrl1.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.e(EntityDescriptor.ATTRIBUTES.name(), map()
								.e(TYPENAME_FIELD, AttributesDescriptor.THIS_GLOBAL.name())
								.e(ATTRIBUTE_CODE, codeAttribute1)
								.e(ATTRIBUTE_URL, entityWithCodeAndUrl1.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH))
								.build())
							.build(),
						map()
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCodeAndUrl2.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.e(EntityDescriptor.ATTRIBUTES.name(), map()
								.e(TYPENAME_FIELD, AttributesDescriptor.THIS_GLOBAL.name())
								.e(ATTRIBUTE_CODE, codeAttribute2)
								.e(ATTRIBUTE_URL, entityWithCodeAndUrl2.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH))
								.build())
							.build()
					)
				)
			);
	}


	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity with multiple different global attributes")
	void shouldReturnUnknownEntityWithMultipleDifferentGlobalAttributes(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 5);
		final SealedEntity entityWithCode = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_CODE), codeAttribute))
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with code attribute"));
		final String urlAttribute = entityWithCode.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(code: "%s", url: "%s") {
	                        primaryKey
	                    }
	                }
					""",
				codeAttribute,
				urlAttribute
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				ENTITY_LIST_PATH,
				equalTo(
					List.of(
						map()
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCode.getPrimaryKey())
							.build()
					)
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity list by multiple different global attributes")
	void shouldReturnUnknownEntityListByMultipleDifferentGlobalAttributes(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 5);
		final SealedEntity entityWithCode = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_CODE), codeAttribute))
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with code attribute"));
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH, 7);
		final SealedEntity entityWithUrl = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute))
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with url attribute"));

		assertNotEquals(entityWithCode.getPrimaryKey(), entityWithUrl.getPrimaryKey());

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(code: "%s", url: "%s", join: OR) {
	                        primaryKey
	                    }
	                }
					""",
				codeAttribute,
				urlAttribute
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				ENTITY_LIST_PATH,
				containsInAnyOrder(
					List.of(
						equalTo(
							map()
								.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCode.getPrimaryKey())
								.build()
						),
						equalTo(
							map()
								.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithUrl.getPrimaryKey())
								.build()
						)
					)
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid argument in unknown entity list query")
	void shouldReturnErrorForInvalidArgumentInUnknownEntityListQuery(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(primaryKey: 1, 2) {
	                        primaryKey
	                        type
                            attributes {
                                code
                            }
	                    }
	                }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return rich unknown entity list by multiple globally unique attribute")
	void shouldReturnErrorForInvalidUnknownEntityListFields(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 5);
		final String codeAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 7);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(code: ["%s","%s"]) {
	                        primaryKey
	                        code
	                    }
	                }
					""",
				codeAttribute1,
				codeAttribute2
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
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
}
