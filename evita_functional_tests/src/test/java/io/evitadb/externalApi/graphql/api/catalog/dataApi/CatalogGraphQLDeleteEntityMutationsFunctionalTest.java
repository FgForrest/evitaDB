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

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesProviderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GraphQL catalog single entity query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLDeleteEntityMutationsFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final String DELETE_PRODUCT_PATH = "data.deleteProduct";
	private static final String GET_PRODUCT_PATH = "data.getProduct";
	public static final String GRAPHQL_THOUSAND_PRODUCTS_FOR_DELETE = GRAPHQL_THOUSAND_PRODUCTS + "forDelete";

	@Override
	@DataSet(value = GRAPHQL_THOUSAND_PRODUCTS_FOR_DELETE, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita) {
		return super.setUpData(evita, 50, false);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_DELETE)
	@DisplayName("Should delete entity by query")
	void shouldDeleteEntityByQuery(Evita evita, GraphQLTester tester) {
		final List<SealedEntity> entitiesToDelete = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeLessThan(ATTRIBUTE_QUANTITY, 5500)
				),
				require(
					strip(0, 2),
					entityFetch(
						attributeContent(ATTRIBUTE_CODE)
					)
				)
			),
			SealedEntity.class
		);
		assertEquals(2, entitiesToDelete.size());

		final var expectedBody = entitiesToDelete.stream()
			.map(entity -> map()
				.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
				.e(
					AttributesProviderDescriptor.ATTRIBUTES.name(), map()
					.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE, String.class))
					.build())
				.build())
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    deleteProduct(
	                        filterBy: {
	                            attributeQuantityLessThan: "5500"
	                        },
	                        limit: 2
	                    ) {
	                        primaryKey
	                        attributes {
	                            code
	                        }
	                    }
	                }
					"""
			)
			.executeAndExpectOkAndThen()
			.body(DELETE_PRODUCT_PATH, equalTo(expectedBody));

		assertProductDeleted(entitiesToDelete.get(0).getPrimaryKey(), tester);
		assertProductDeleted(entitiesToDelete.get(1).getPrimaryKey(), tester);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_DELETE)
	@DisplayName("Should not delete any entity by query")
	void shouldNotDeleteAnyEntityByQuery(Evita evita, GraphQLTester tester) {
		final List<EntityClassifier> entitiesToDelete = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeGreaterThan(ATTRIBUTE_QUANTITY, 1_000_000)
				),
				require(
					strip(0, 1)
				)
			),
			true
		);
		assertTrue(entitiesToDelete.isEmpty());

		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    deleteProduct(
	                        filterBy: {
	                            attributeQuantityGreaterThan: "1000000"
	                        }
	                        limit: 1
	                    ) {
	                        primaryKey
	                    }
	                }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(DELETE_PRODUCT_PATH, equalTo(List.of()));
	}

	private void assertProductDeleted(int primaryKey, GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(primaryKey: %d) {
	                        primaryKey
	                    }
	                }
					""",
				primaryKey
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, nullValue());
	}
}
