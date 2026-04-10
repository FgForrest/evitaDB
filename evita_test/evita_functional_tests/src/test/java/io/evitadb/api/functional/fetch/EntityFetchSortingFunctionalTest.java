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

package io.evitadb.api.functional.fetch;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies entity fetch sorting functionality including primary key ordering
 * in descending order, exact order from filter constraints, exact order specifications,
 * duplicate key handling, and appending remaining results.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity fetch sorting functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class EntityFetchSortingFunctionalTest extends AbstractEntityFetchingFunctionalTest {

	@DisplayName("Should return products sorted by primary key in descending order")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductsSortedByPrimaryKeyInDescendingOrder(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] exactOrder = {12, 1, 18, 23, 5};
				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(exactOrder)
						),
						orderBy(
							entityPrimaryKeyNatural(OrderDirection.DESC)
						)
					)
				);
				assertEquals(5, products.getRecordData().size());
				assertEquals(5, products.getTotalRecordCount());

				Arrays.sort(exactOrder, (o1, o2) -> Integer.compare(o2, o1));
				assertArrayEquals(
					exactOrder,
					products.getRecordData().stream()
						.map(EntityContract::getPrimaryKey)
						.toArray(Integer[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order in the filter constraint")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrderInFilter(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] exactOrder = {12, 1, 18, 23, 5};
				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(exactOrder)
						),
						orderBy(
							entityPrimaryKeyInFilter()
						)
					)
				);
				assertEquals(5, products.getRecordData().size());
				assertEquals(5, products.getTotalRecordCount());

				assertArrayEquals(
					exactOrder,
					products.getRecordData().stream()
						.map(EntityContract::getPrimaryKey)
						.toArray(Integer[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrder(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] exactOrder = {12, 1, 18, 23, 5};
				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(Arrays.stream(exactOrder).sorted().toArray(Integer[]::new))
						),
						orderBy(
							entityPrimaryKeyExact(exactOrder)
						)
					)
				);
				assertEquals(5, products.getRecordData().size());
				assertEquals(5, products.getTotalRecordCount());

				assertArrayEquals(
					exactOrder,
					products.getRecordData().stream()
						.map(EntityContract::getPrimaryKey)
						.toArray(Integer[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order with duplicate keys")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrderWithDuplicateKeys(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] exactOrder = {12, 1};
				final Integer[] duplicatedExactOrder = {12, 12, 1};
				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(Arrays.stream(exactOrder).sorted().toArray(Integer[]::new))
						),
						orderBy(
							entityPrimaryKeyExact(duplicatedExactOrder)
						)
					)
				);
				assertEquals(2, products.getRecordData().size());
				assertEquals(2, products.getTotalRecordCount());

				assertArrayEquals(
					exactOrder,
					products.getRecordData().stream()
						.map(EntityContract::getPrimaryKey)
						.toArray(Integer[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order appending the rest")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrderAppendingTheRest(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] productsStartingWithA = originalProducts.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_CODE, String.class).startsWith("A"))
			.map(EntityContract::getPrimaryKey)
			.toArray(Integer[]::new);
		Assert.isTrue(productsStartingWithA.length >= 5, "Not enough products starting with A found");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] exactOrder = Arrays.copyOfRange(productsStartingWithA, 0, (int) (productsStartingWithA.length * 0.5));
				final Integer[] theRest = Arrays.copyOfRange(productsStartingWithA, (int) (productsStartingWithA.length * 0.5), productsStartingWithA.length);
				ArrayUtils.reverse(exactOrder);
				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeStartsWith(ATTRIBUTE_CODE, "A")
						),
						orderBy(
							entityPrimaryKeyExact(exactOrder)
						),
						require(
							page(1, productsStartingWithA.length)
						)
					)
				);
				assertEquals(productsStartingWithA.length, products.getRecordData().size());
				assertEquals(productsStartingWithA.length, products.getTotalRecordCount());

				assertArrayEquals(
					ArrayUtils.mergeArrays(
						exactOrder, theRest
					),
					products.getRecordData().stream()
						.map(EntityContract::getPrimaryKey)
						.toArray(Integer[]::new)
				);
				return null;
			}
		);
	}

}
