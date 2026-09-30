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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;

/**
 * This test verifies entity fetch sorting functionality including primary key ordering
 * in descending order, exact order from filter constraints, exact order specifications,
 * duplicate key handling, and appending remaining results.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity fetch sorting functionality")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(QUERY)
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

	@DisplayName("Should return every page of products sorted by primary key in descending order")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEveryPageOfProductsSortedByPrimaryKeyInDescendingOrder(Evita evita, List<SealedEntity> originalProducts) {
		final int[] expectedOrder = composeExpectedOrder(
			new int[0], originalProducts, Comparator.reverseOrder()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEveryPageIsSliceOf(
					expectedOrder,
					(pageNumber, pageSize) -> queryProductPage(
						session, orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)), pageNumber, pageSize
					)
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

	@Nested
	@DisplayName("Pages of products sorted by exact order starting past the exactly ordered keys")
	class PagesPastExactlyOrderedKeys {

		@DisplayName("Should fill the page from the products outside the exact order in primary key order")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Test
		void shouldFillPageFromProductsOutsideExactOrder(Evita evita, List<SealedEntity> originalProducts) {
			final int[] exactOrder = {10, 3};
			final int[] expectedOrder = composeExpectedOrder(
				exactOrder, originalProducts, Comparator.naturalOrder()
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int[] page = queryProductPage(session, orderBy(entityPrimaryKeyExact(10, 3)), 2, 3);
					assertArrayEquals(Arrays.copyOfRange(expectedOrder, 3, 6), page);
					return null;
				}
			);
		}

		@DisplayName("Should return every page as a slice of the entire exactly ordered result")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Test
		void shouldReturnEveryPageAsSliceOfEntireOrderedResult(Evita evita, List<SealedEntity> originalProducts) {
			final int[] exactOrder = {10, 3, 57};
			final int[] expectedOrder = composeExpectedOrder(
				exactOrder, originalProducts, Comparator.naturalOrder()
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEveryPageIsSliceOf(
						expectedOrder,
						(pageNumber, pageSize) -> queryProductPage(
							session, orderBy(entityPrimaryKeyExact(10, 3, 57)), pageNumber, pageSize
						)
					);
					return null;
				}
			);
		}

		@DisplayName("Should order the products outside the exact order by the next sorter on every page")
		@UseDataSet(HUNDRED_PRODUCTS)
		@Test
		void shouldOrderProductsOutsideExactOrderByNextSorter(Evita evita, List<SealedEntity> originalProducts) {
			final int[] exactOrder = {10, 3, 57};
			final int[] expectedOrder = composeExpectedOrder(
				exactOrder, originalProducts, Comparator.reverseOrder()
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEveryPageIsSliceOf(
						expectedOrder,
						(pageNumber, pageSize) -> queryProductPage(
							session,
							orderBy(
								entityPrimaryKeyExact(10, 3, 57),
								entityPrimaryKeyNatural(OrderDirection.DESC)
							),
							pageNumber, pageSize
						)
					);
					return null;
				}
			);
		}

	}

	/**
	 * Queries a single page of all products ordered by the given ordering and returns their primary keys.
	 *
	 * @param session    the session to query
	 * @param orderBy    the ordering to apply
	 * @param pageNumber the number of the page to fetch (starting with 1)
	 * @param pageSize   the size of the page
	 * @return primary keys of the products on the page in the order they were returned
	 */
	@Nonnull
	private static int[] queryProductPage(
		@Nonnull EvitaSessionContract session,
		@Nonnull OrderBy orderBy,
		int pageNumber,
		int pageSize
	) {
		return session.queryEntityReference(
				query(
					collection(Entities.PRODUCT),
					orderBy,
					require(page(pageNumber, pageSize))
				)
			)
			.getRecordData()
			.stream()
			.mapToInt(EntityReferenceContract::getPrimaryKey)
			.toArray();
	}

	/**
	 * Composes the expected order of all products - the keys of the exact order first, followed by the rest of
	 * the products ordered by their primary key using the given comparator.
	 *
	 * @param exactOrder       primary keys that must come first in this order
	 * @param originalProducts all products in the dataset
	 * @param restComparator   comparator of the primary keys of the products outside the exact order
	 * @return the primary keys of all products in the expected order
	 */
	@Nonnull
	private static int[] composeExpectedOrder(
		@Nonnull int[] exactOrder,
		@Nonnull List<SealedEntity> originalProducts,
		@Nonnull Comparator<Integer> restComparator
	) {
		final Set<Integer> exactKeys = Arrays.stream(exactOrder).boxed().collect(Collectors.toSet());
		return IntStream.concat(
				Arrays.stream(exactOrder),
				originalProducts.stream()
					.map(EntityContract::getPrimaryKeyOrThrowException)
					.filter(pk -> !exactKeys.contains(pk))
					.sorted(restComparator)
					.mapToInt(Integer::intValue)
			)
			.toArray();
	}

	/**
	 * Verifies that every page of several page sizes covering the first fifteen records (the exactly ordered keys,
	 * if any, and the records just past them) equals the corresponding slice of the entire expected order.
	 *
	 * @param expectedOrder the primary keys of all records in the expected order
	 * @param pageFetcher   function fetching the primary keys of a page by its number and size
	 */
	private static void assertEveryPageIsSliceOf(
		@Nonnull int[] expectedOrder,
		@Nonnull BiFunction<Integer, Integer, int[]> pageFetcher
	) {
		for (int pageSize : new int[]{1, 2, 3, 4, 7}) {
			for (int pageNumber = 1; (pageNumber - 1) * pageSize < 15; pageNumber++) {
				final int offset = (pageNumber - 1) * pageSize;
				assertArrayEquals(
					Arrays.copyOfRange(expectedOrder, offset, Math.min(offset + pageSize, expectedOrder.length)),
					pageFetcher.apply(pageNumber, pageSize),
					"Page " + pageNumber + " of size " + pageSize + " differs from the slice of the entire result."
				);
			}
		}
	}

}
