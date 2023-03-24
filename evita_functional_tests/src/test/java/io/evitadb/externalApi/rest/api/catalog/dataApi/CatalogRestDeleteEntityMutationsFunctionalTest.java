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

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.SectionedAttributesDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.GetEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestTester;
import io.evitadb.externalApi.rest.api.testSuite.RestTester.Request;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GraphQL catalog single entity query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class CatalogRestDeleteEntityMutationsFunctionalTest extends CatalogRestDataEndpointFunctionalTest {


	public static final String REST_THOUSAND_PRODUCTS_FOR_DELETE = REST_THOUSAND_PRODUCTS + "forDelete";

	@Override
	@DataSet(value = REST_THOUSAND_PRODUCTS_FOR_DELETE, openWebApi = RestProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		return super.setUpData(evita, evitaServer, 20);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_DELETE)
	@DisplayName("Should delete entity by query")
	void shouldDeleteEntityByQuery(Evita evita, RestTester tester) {
		final List<SealedEntity> entitiesToDelete = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfSealedEntities(
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
					)
				);
			}
		);
		assertEquals(2, entitiesToDelete.size());

		final var expectedBody = entitiesToDelete.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.LOCALES.name(), List.of())
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.NONE.name())
					.e(EntityDescriptor.ATTRIBUTES.name(), map()
						.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
							.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
							.build())
						.build())
					.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_DELETE)
			.urlPathSuffix("/product")
			.requestBody("""
                    {
                        "filterBy": {
	                        "attribute_quantity_lessThan": 5500
	                    },
						"require": {
				            "entity_fetch": {
				                "attribute_content": ["code"]
				            },
				            "strip": {
				                "offset": 0,
				                "limit": 2
				            }
	                    }
                    }
                    """)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));

		assertProductDeleted(entitiesToDelete.get(0).getPrimaryKey(), tester);
		assertProductDeleted(entitiesToDelete.get(1).getPrimaryKey(), tester);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_DELETE)
	@DisplayName("Should not delete any entity by query")
	void shouldNotDeleteAnyEntityByQuery(Evita evita, RestTester tester) {
		final List<EntityReference> entitiesToDelete = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfEntityReferences(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeGreaterThan(ATTRIBUTE_QUANTITY, 1_000_000)
						),
						require(
							strip(0, 1)
						)
					)
				);
			}
		);
		assertTrue(entitiesToDelete.isEmpty());

		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_DELETE)
			.urlPathSuffix("/product")
			.requestBody("""
                    {
                        "filterBy": {
	                        "attribute_quantity_greaterThan": "1000000"
	                    },
						"require": {
				            "strip": {
				                "offset": 0,
				                "limit": 1
				            }
	                    }
	                }
                    """)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(List.of()));
	}

	private void assertProductDeleted(int primaryKey, RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(GetEntityEndpointHeaderDescriptor.PRIMARY_KEY.name(), primaryKey)
				.build())
			.executeAndThen()
			.statusCode(404);
	}
}
