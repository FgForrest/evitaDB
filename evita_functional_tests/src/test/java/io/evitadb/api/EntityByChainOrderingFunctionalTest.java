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

package io.evitadb.api;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Predecessor;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.AssertionUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.core.query.algebra.prefetch.SelectionFormula.doWithCustomPrefetchCostEstimator;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;

/**
 * This test verifies the behavior related to the chained ordering of entities.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Evita entity ordering by chained elements")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EntityByChainOrderingFunctionalTest {
	public static final String ATTRIBUTE_ORDER = "order";
	private static final String CHAINED_ELEMENTS = "chained-elements";
	private static final int SEED = 40;

	private final static int PRODUCT_COUNT = 30;
	private final static int[] PRODUCT_ORDER;

	static {
		PRODUCT_ORDER = new int[PRODUCT_COUNT];
		for (int i = 1; i <= PRODUCT_COUNT; i++) {
			PRODUCT_ORDER[i - 1] = i;
		}

		ArrayUtils.shuffleArray(new Random(SEED), PRODUCT_ORDER, PRODUCT_COUNT);
	}

	@Nullable
	@DataSet(value = CHAINED_ELEMENTS, readOnly = false, destroyAfterClass = true)
	Map<Integer, SealedEntity> setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			session.updateCatalogSchema(
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.sortable().uniqueGlobally().nullable())
					.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally().nullable())
			);

			final DataGenerator dataGenerator = new DataGenerator();
			AtomicInteger index = new AtomicInteger();

			dataGenerator.registerValueGenerator(
				Entities.PRODUCT, ATTRIBUTE_ORDER,
				faker -> {
					final int ix = index.incrementAndGet();
					final int position = ArrayUtils.indexOf(ix, PRODUCT_ORDER);
					return position == 0 ? Predecessor.HEAD : new Predecessor(PRODUCT_ORDER[position - 1]);
				}
			);

			final List<EntityReference> storedProducts = dataGenerator.generateEntities(
					dataGenerator.getSampleProductSchema(
						session,
						schemaBuilder -> {
							schemaBuilder
								.withAttribute(
									ATTRIBUTE_ORDER, Predecessor.class,
									AttributeSchemaEditor::sortable
								);
							// we don't need any references for this test
							for (String referenceName : schemaBuilder.getReferences().keySet()) {
								schemaBuilder.withoutReferenceTo(referenceName);
							}
						}
					),
					(s, faker) -> {
						throw new UnsupportedOperationException();
					},
					SEED
				)
				.limit(PRODUCT_COUNT)
				.map(session::upsertEntity)
				.toList();

			return storedProducts.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
				.collect(
					Collectors.toMap(
						SealedEntity::getPrimaryKey,
						Function.identity()
					)
				);
		});
	}

	@DisplayName("The product should be returned in ascending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByPredecessorAttributeInAscendingOrder(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							attributeNatural(ATTRIBUTE_ORDER, OrderDirection.ASC)
						),
						require(
							page(1, 20),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				AssertionUtils.assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(PRODUCT_ORDER).limit(20).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByPredecessorAttributeInDescendingOrder(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)
						),
						require(
							page(2, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				AssertionUtils.assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(ArrayUtils.reverse(PRODUCT_ORDER)).skip(10).limit(10).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The prefetched product should be returned in ascending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortPrefetchedProductsByPredecessorAttributeInAscendingOrder(Evita evita) {
		final AtomicInteger counter = new AtomicInteger();
		final int[] prefetchedProducts = IntStream.generate(counter::incrementAndGet).limit(10).toArray();
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return doWithCustomPrefetchCostEstimator(() -> {
						final EvitaResponse<EntityReference> result = session.query(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									entityPrimaryKeyInSet(prefetchedProducts)
								),
								orderBy(
									attributeNatural(ATTRIBUTE_ORDER, OrderDirection.ASC)
								),
								require(
									page(2, 10),
									debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
								)
							),
							EntityReference.class
						);
						AssertionUtils.assertSortedResultEquals(
							result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
							Arrays.stream(PRODUCT_ORDER).filter(it -> Arrays.stream(prefetchedProducts).anyMatch(pid -> pid == it)).toArray()
						);
						return null;
					},
					(prefetchedEntityCount, requirementCount) -> Long.MIN_VALUE
				);
			}
		);
	}

	@DisplayName("The prefetched product should be returned in descending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortPrefetchedProductsByPredecessorAttributeInDescendingOrder(Evita evita) {
		final AtomicInteger counter = new AtomicInteger();
		final int[] prefetchedProducts = IntStream.generate(counter::incrementAndGet).limit(10).toArray();
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return doWithCustomPrefetchCostEstimator(() -> {
						final EvitaResponse<EntityReference> result = session.query(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									entityPrimaryKeyInSet(prefetchedProducts)
								),
								orderBy(
									attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)
								),
								require(
									page(2, 10),
									debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
								)
							),
							EntityReference.class
						);
						AssertionUtils.assertSortedResultEquals(
							result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
							Arrays.stream(ArrayUtils.reverse(PRODUCT_ORDER)).filter(it -> Arrays.stream(prefetchedProducts).anyMatch(pid -> pid == it)).toArray()
						);
						return null;
					},
					(prefetchedEntityCount, requirementCount) -> Long.MIN_VALUE
				);
			}
		);
	}

}
