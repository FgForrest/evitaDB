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
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ATTRIBUTE_MARKET_SHARE;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GraphQL catalog unknown single entity query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLGetUnknownEntityQueryFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final String GET_ENTITY_PATH = "data.getEntity";

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity by globally unique attribute")
	void shouldReturnUnknownEntityByGloballyUniqueAttribute(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				filterBy(attributeEquals(ATTRIBUTE_CODE, codeAttribute))
			)
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        __typename
	                        primaryKey
	                        type
	                    }
	                }
					""",
				codeAttribute
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(TYPENAME_FIELD, EntityDescriptor.THIS_GLOBAL.name())
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return version with entity")
	void shouldReturnVersionWithEntity(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);

		final SealedEntity entity = getEntity(
			evita,
			query(
				filterBy(attributeEquals(ATTRIBUTE_CODE, codeAttribute)),
				require(page(1, 1), entityFetch())
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        primaryKey
	                        type
	                        version
	                    }
	                }
					""",
				codeAttribute
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.VERSION.name(), entity.version())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return rich unknown entity by localized globally unique attribute")
	void shouldReturnRichUnknownEntityByLocalizedGloballyUniqueAttribute(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);

		final SealedEntity entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_URL, urlAttribute),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch()
				)
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(url: "%s") {
	                        primaryKey
	                        type
	                        locales
	                        allLocales
                            attributes {
                                __typename
                                url
                            }
	                    }
	                }
					""",
				urlAttribute
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toString()))
						.e(EntityDescriptor.ALL_LOCALES.name(), entity.getAllLocales().stream().filter(Objects::nonNull).map(Locale::toString).toList())
						.e(EntityDescriptor.ATTRIBUTES.name(), map()
							.e(TYPENAME_FIELD, AttributesDescriptor.THIS_GLOBAL.name())
							.e(ATTRIBUTE_URL, urlAttribute)
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return localized rich unknown entity by non-localized globally unique attribute")
	void shouldReturnLocalizedRichUnknownEntityByNonLocalizedGloballyUniqueAttribute(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);

		final SealedEntity entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, codeAttribute),
					entityLocaleEquals(Locale.ENGLISH),
					attributeIsNotNull(ATTRIBUTE_URL)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_URL)
					)
				)
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        primaryKey
	                        type
	                        locales
	                        allLocales
                            attributes(locale: en) {
                                __typename
                                code
                                url
                            }
	                    }
	                }
					""",
				codeAttribute
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toString()))
						.e(EntityDescriptor.ALL_LOCALES.name(), entity.getAllLocales().stream().filter(Objects::nonNull).map(Locale::toString).toList())
						.e(EntityDescriptor.ATTRIBUTES.name(), map()
							.e(TYPENAME_FIELD, AttributesDescriptor.THIS_GLOBAL.name())
							.e(ATTRIBUTE_CODE, codeAttribute)
							.e(ATTRIBUTE_URL, entity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH))
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return entity with multiple different global attributes")
	void shouldReturnUnknownEntityWithMultipleDifferentGlobalAttributes(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);

		final SealedEntity entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_URL, urlAttribute),
					entityLocaleEquals(Locale.ENGLISH),
					attributeIsNotNull(ATTRIBUTE_CODE)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_URL)
					)
				)
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(url: "%s", code: "%s") {
	                        primaryKey
	                    }
	                }
					""",
				urlAttribute,
				entity.getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return entity by multiple different global attributes")
	void shouldReturnUnknownEntityByMultipleDifferentGlobalAttributes(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);

		final SealedEntity entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_URL, urlAttribute),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch()
				)
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(url: "%s", code: "%s", join: OR) {
	                        primaryKey
	                    }
	                }
					""",
				urlAttribute,
				"somethingWhichDoesntExist"
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid argument in unknown entity query")
	void shouldReturnErrorForInvalidArgumentInUnknownEntityQuery(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                getEntity(limit: 2) {
		                    primaryKey
		                    type
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
	@DisplayName("Should return error for invalid unknown entity fields")
	void shouldReturnErrorForInvalidUnknownEntityFields(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                getEntity(code: "%s") {
		                    primaryKey
		                    code
		                }
		            }
					""",
				codeAttribute
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}
}
