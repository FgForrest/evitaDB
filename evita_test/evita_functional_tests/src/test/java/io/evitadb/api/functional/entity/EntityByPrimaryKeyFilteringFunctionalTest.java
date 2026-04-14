/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.functional.entity;

import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.require.DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS;
import static io.evitadb.api.query.require.DebugMode.VERIFY_POSSIBLE_CACHING_TREES;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.utils.AssertionUtils.assertResultIs;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies whether entities can be filtered by primary key comparison constraints.
 * It reuses the HUNDRED_PRODUCTS dataset created by
 * {@link io.evitadb.api.functional.attribute.AbstractEntityByAttributeFilteringFunctionalTest}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Evita entity filtering by primary key comparison constraints")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EntityByPrimaryKeyFilteringFunctionalTest {
	private static final String HUNDRED_PRODUCTS = "HundredProductsForPrimaryKeyFiltering";

	/**
	 * Creates a minimal dataset of 100 product entities with sequential primary keys.
	 * No attributes, references, or prices — only primary keys are needed for these tests.
	 *
	 * @param evita the evitaDB instance
	 * @return data carrier containing the list of created product entities
	 */
	@DataSet(value = HUNDRED_PRODUCTS, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT);

			final List<EntityReferenceContract> storedProducts = IntStream.rangeClosed(1, 100)
				.mapToObj(i -> session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, i)
				))
				.toList();

			return new DataCarrier(
				"originalProductEntities",
				storedProducts.stream()
					.map(it -> session.getEntity(
						it.getType(),
						it.getPrimaryKeyOrThrowException()
					).orElseThrow())
					.collect(Collectors.toList())
			);
		});
	}

	@DisplayName("Should return entities filtered by primary key greater than")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByPrimaryKeyGreaterThan(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int threshold = originalProductEntities
					.get(originalProductEntities.size() / 2)
					.getPrimaryKeyOrThrowException();

				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyGreaterThan(threshold)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(VERIFY_ALTERNATIVE_INDEX_RESULTS, VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.getPrimaryKeyOrThrowException() > threshold,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities filtered by primary key greater than or equals")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByPrimaryKeyGreaterThanEquals(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int threshold = originalProductEntities
					.get(originalProductEntities.size() / 2)
					.getPrimaryKeyOrThrowException();

				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyGreaterThanEquals(threshold)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(VERIFY_ALTERNATIVE_INDEX_RESULTS, VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.getPrimaryKeyOrThrowException() >= threshold,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities filtered by primary key less than")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByPrimaryKeyLessThan(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int threshold = originalProductEntities
					.get(originalProductEntities.size() / 2)
					.getPrimaryKeyOrThrowException();

				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyLessThan(threshold)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(VERIFY_ALTERNATIVE_INDEX_RESULTS, VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.getPrimaryKeyOrThrowException() < threshold,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities filtered by primary key less than or equals")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByPrimaryKeyLessThanEquals(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int threshold = originalProductEntities
					.get(originalProductEntities.size() / 2)
					.getPrimaryKeyOrThrowException();

				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyLessThanEquals(threshold)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(VERIFY_ALTERNATIVE_INDEX_RESULTS, VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.getPrimaryKeyOrThrowException() <= threshold,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities filtered by primary key between")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByPrimaryKeyBetween(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int from = originalProductEntities
					.get(originalProductEntities.size() / 4)
					.getPrimaryKeyOrThrowException();
				final int to = originalProductEntities
					.get(originalProductEntities.size() * 3 / 4)
					.getPrimaryKeyOrThrowException();

				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyBetween(from, to)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(VERIFY_ALTERNATIVE_INDEX_RESULTS, VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> {
						final int pk = sealedEntity.getPrimaryKeyOrThrowException();
						return pk >= from && pk <= to;
					},
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return no entities when greater-than threshold is Integer.MAX_VALUE")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEmptyResultWhenGreaterThanMaxValue(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyGreaterThan(Integer.MAX_VALUE)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(VERIFY_ALTERNATIVE_INDEX_RESULTS, VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertTrue(
					result.getRecordData().isEmpty(),
					"Expected no entities to match entityPrimaryKeyGreaterThan(Integer.MAX_VALUE)"
				);
				return null;
			}
		);
	}

	@DisplayName("Should return no entities when less-than threshold is Integer.MIN_VALUE")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEmptyResultWhenLessThanMinValue(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyLessThan(Integer.MIN_VALUE)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(VERIFY_ALTERNATIVE_INDEX_RESULTS, VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertTrue(
					result.getRecordData().isEmpty(),
					"Expected no entities to match entityPrimaryKeyLessThan(Integer.MIN_VALUE)"
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities filtered by primary key between with unbounded upper bound")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByPrimaryKeyBetweenWithNullUpperBound(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int from = originalProductEntities
					.get(originalProductEntities.size() / 2)
					.getPrimaryKeyOrThrowException();

				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyBetween(from, null)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(VERIFY_ALTERNATIVE_INDEX_RESULTS, VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.getPrimaryKeyOrThrowException() >= from,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities filtered by primary key between with unbounded lower bound")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByPrimaryKeyBetweenWithNullLowerBound(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int to = originalProductEntities
					.get(originalProductEntities.size() / 2)
					.getPrimaryKeyOrThrowException();

				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyBetween(null, to)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(VERIFY_ALTERNATIVE_INDEX_RESULTS, VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.getPrimaryKeyOrThrowException() <= to,
					result.getRecordData()
				);
				return null;
			}
		);
	}

}
