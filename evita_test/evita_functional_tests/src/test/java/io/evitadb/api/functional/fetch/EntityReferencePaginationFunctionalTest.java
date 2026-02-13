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
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.StripList;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests verifying paginated and stripped access to entity references,
 * reference ordering by primary key, and enrichment with different pagination settings.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity reference pagination functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class EntityReferencePaginationFunctionalTest extends AbstractEntityFetchingFunctionalTest {

	@DisplayName("Should provide paginated access to references")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnPaginatedReferences(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithMaxReferences = originalProducts
			.stream()
			.filter(it -> !it.getReferences(Entities.BRAND).isEmpty() && !it.getReferences(Entities.PARAMETER)
				.isEmpty())
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(
				Entities.PARAMETER).size()))
			.orElseThrow();
		final Set<Integer> originParameters = productWithMaxReferences.getReferences(Entities.PARAMETER)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());
		final int totalParameterCount = originParameters.size();

		assertEquals(
			originParameters,
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Set<Integer> referencedParameters = CollectionUtils.createHashSet(totalParameterCount);
					for (int pageNumber = 1; pageNumber <= Math.ceil(totalParameterCount / 5.0f); pageNumber++) {
						final SealedEntity productByPk = session.queryOneSealedEntity(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
								),
								require(
									entityFetch(
										// provide all brands
										referenceContent(Entities.BRAND),
										// but only first four parameters
										referenceContent(
											Entities.PARAMETER,
											entityFetchAll(),
											entityGroupFetchAll(),
											page(pageNumber, 5)
										)
									)
								)
							)
						).orElseThrow();

						assertEquals(1, productByPk.getReferences(Entities.BRAND).size());

						final Collection<ReferenceContract> foundParameters = productByPk.getReferences(
							Entities.PARAMETER);
						assertTrue(!foundParameters.isEmpty() && foundParameters.size() <= 5);
						assertEquals(
							foundParameters.size(), productByPk.getReferences()
								.stream()
								.filter(it -> it.getReferenceName().equals(Entities.PARAMETER))
								.count()
						);

						for (ReferenceContract foundParameter : foundParameters) {
							assertNotNull(foundParameter.getReferencedEntity());
							assertNotNull(foundParameter.getGroupEntity().orElse(null));
						}

						PaginatedList<ReferenceContract> parameters = new PaginatedList<>(
							pageNumber, 5, totalParameterCount, new ArrayList<>(foundParameters));
						assertEquals(parameters, productByPk.getReferenceChunk(Entities.PARAMETER));
						foundParameters
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.forEach(referencedParameters::add);

					}
					return referencedParameters;
				}
			)
		);
	}

	@DisplayName("Should provide paginated access to references with spacing")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnPaginatedReferencesWithSpacing(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithMaxReferences = originalProducts
			.stream()
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(
				Entities.PARAMETER).size()))
			.orElseThrow();
		final Set<Integer> originParameters = productWithMaxReferences.getReferences(Entities.PARAMETER)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());
		final int totalParameterCount = originParameters.size();

		assertEquals(
			originParameters,
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Set<Integer> referencedParameters = CollectionUtils.createHashSet(totalParameterCount);
					PaginatedList<ReferenceContract> parameters;
					int pageNumber = 1;
					do {
						final SealedEntity productByPk = session.queryOneSealedEntity(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
								),
								require(
									entityFetch(
										// but only first four parameters
										referenceContent(
											Entities.PARAMETER,
											entityFetchAll(),
											entityGroupFetchAll(),
											page(pageNumber, 5, spacing(gap(1, "$pageNumber % 2 == 0")))
										)
									)
								)
							)
						).orElseThrow();

						final Collection<ReferenceContract> foundParameters = productByPk.getReferences(
							Entities.PARAMETER);
						final int maxItemsPerPage = pageNumber % 2 == 0 ? 4 : 5;
						assertTrue(!foundParameters.isEmpty() && foundParameters.size() <= maxItemsPerPage);
						assertEquals(
							foundParameters.size(), productByPk.getReferences()
								.stream()
								.filter(it -> it.getReferenceName().equals(Entities.PARAMETER))
								.count()
						);

						for (ReferenceContract foundParameter : foundParameters) {
							assertNotNull(foundParameter.getReferencedEntity());
							assertNotNull(foundParameter.getGroupEntity().orElse(null));
						}

						parameters = new PaginatedList<>(
							pageNumber, 4, 5, totalParameterCount, new ArrayList<>(foundParameters));
						assertEquals(parameters, productByPk.getReferenceChunk(Entities.PARAMETER));
						foundParameters
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.forEach(referencedParameters::add);
						pageNumber++;

					} while (parameters.hasNext());

					return referencedParameters;
				}
			)
		);
	}

	@DisplayName("Should provide stripped access to references")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnStrippedReferences(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithMaxReferences = originalProducts
			.stream()
			.filter(it -> !it.getReferences(Entities.BRAND).isEmpty() && !it.getReferences(Entities.PARAMETER)
				.isEmpty())
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(
				Entities.PARAMETER).size()))
			.orElseThrow();
		final Set<Integer> originParameters = productWithMaxReferences.getReferences(Entities.PARAMETER)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());
		final int totalParameterCount = originParameters.size();

		assertEquals(
			originParameters,
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Set<Integer> referencedParameters = CollectionUtils.createHashSet(totalParameterCount);
					for (int pageNumber = 1; pageNumber <= Math.ceil(totalParameterCount / 5.0f); pageNumber++) {
						final int offset = (pageNumber - 1) * 5;
						final SealedEntity productByPk = session.queryOneSealedEntity(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
								),
								require(
									entityFetch(
										// provide all brands
										referenceContent(Entities.BRAND),
										// but only first four parameters
										referenceContent(
											Entities.PARAMETER,
											entityFetchAll(),
											entityGroupFetchAll(),
											strip(offset, 5)
										)
									)
								)
							)
						).orElseThrow();

						assertEquals(1, productByPk.getReferences(Entities.BRAND).size());

						final Collection<ReferenceContract> foundParameters = productByPk.getReferences(
							Entities.PARAMETER);
						assertTrue(!foundParameters.isEmpty() && foundParameters.size() <= 5);
						assertEquals(
							foundParameters.size(), productByPk.getReferences()
								.stream()
								.filter(it -> it.getReferenceName().equals(Entities.PARAMETER))
								.count()
						);

						for (ReferenceContract foundParameter : foundParameters) {
							assertNotNull(foundParameter.getReferencedEntity());
							assertNotNull(foundParameter.getGroupEntity().orElse(null));
						}

						StripList<ReferenceContract> parameters = new StripList<>(
							offset, 5, totalParameterCount, new ArrayList<>(foundParameters));
						assertEquals(parameters, productByPk.getReferenceChunk(Entities.PARAMETER));
						foundParameters
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.forEach(referencedParameters::add);

					}
					return referencedParameters;
				}
			)
		);
	}

	@DisplayName("Should provide paginated and stripped access to references at once")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldCombinePaginatedAndStrippedReferences(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithMaxReferences = originalProducts
			.stream()
			.filter(it -> !it.getReferences(Entities.BRAND).isEmpty())
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(
				Entities.PARAMETER).size() + o.getReferences(Entities.PRICE_LIST).size()))
			.orElseThrow();
		final Set<Integer> originParameters = productWithMaxReferences.getReferences(Entities.PARAMETER)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());
		final int totalParameterCount = originParameters.size();
		final Set<Integer> originPriceLists = productWithMaxReferences.getReferences(Entities.PRICE_LIST)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());
		final int totalPriceListCount = originPriceLists.size();

		final Set[] result = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> referencedParameters = CollectionUtils.createHashSet(totalParameterCount);
				final Set<Integer> referencedPriceLists = CollectionUtils.createHashSet(totalPriceListCount);
				for (int pageNumber = 1; pageNumber <= Math.ceil(totalParameterCount / 5.0f); pageNumber++) {
					final int offset = (pageNumber - 1) * 5;
					final SealedEntity productByPk = session.queryOneSealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
							),
							require(
								entityFetch(
									// provide all brands
									referenceContent(Entities.BRAND),
									// but only first four price lists
									referenceContent(
										Entities.PRICE_LIST,
										entityFetchAll(),
										page(pageNumber, 5)
									),
									// but only first four parameters
									referenceContent(
										Entities.PARAMETER,
										entityFetchAll(),
										entityGroupFetchAll(),
										strip(offset, 5)
									)
								)
							)
						)
					).orElseThrow();

					assertEquals(1, productByPk.getReferences(Entities.BRAND).size());

					final StripList<ReferenceContract> foundParameters = (StripList<ReferenceContract>) productByPk.getReferenceChunk(
						Entities.PARAMETER);
					foundParameters
						.stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.forEach(referencedParameters::add);

					final PaginatedList<ReferenceContract> foundPriceLists = (PaginatedList<ReferenceContract>) productByPk.getReferenceChunk(
						Entities.PRICE_LIST);
					foundPriceLists
						.stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.forEach(referencedPriceLists::add);

				}
				return new Set[]{referencedParameters, referencedPriceLists};
			}
		);

		assertEquals(originParameters, result[0]);
		assertEquals(originPriceLists, result[1]);
	}

	@DisplayName("Should fetch entity without chunking and chunk it afterwards using enrichment")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchEntityFirstAndEnrichItWithStrip(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithMaxReferences = originalProducts
			.stream()
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(
				Entities.PARAMETER).size()))
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity firstFetch = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
						)
					)
				).orElseThrow();

				final SealedEntity secondFetch = session.enrichEntity(
					firstFetch,
					referenceContent(
						Entities.PARAMETER,
						entityFetchAll(),
						entityGroupFetchAll(),
						strip(2, 4)
					)
				);

				final Collection<ReferenceContract> originalParameters = productWithMaxReferences.getReferences(
					Entities.PARAMETER);
				final int[] expectedParameters = originalParameters
					.stream()
					.skip(2)
					.limit(4)
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray();

				final Collection<ReferenceContract> foundParameters = secondFetch.getReferences(Entities.PARAMETER);
				assertTrue(!foundParameters.isEmpty() && foundParameters.size() <= 4);
				assertEquals(
					foundParameters.size(), secondFetch.getReferences()
						.stream()
						.filter(it -> it.getReferenceName().equals(Entities.PARAMETER))
						.count()
				);
				assertArrayEquals(
					expectedParameters,
					secondFetch.getReferences(Entities.PARAMETER)
						.stream()
						.mapToInt(ReferenceContract::getReferencedPrimaryKey)
						.toArray()
				);

				for (ReferenceContract foundParameter : foundParameters) {
					assertNotNull(foundParameter.getReferencedEntity());
					assertNotNull(foundParameter.getGroupEntity().orElse(null));
				}

				StripList<ReferenceContract> parameters = new StripList<>(
					2, 4, (int) originalParameters.stream().count(), new ArrayList<>(foundParameters));
				assertEquals(parameters, secondFetch.getReferenceChunk(Entities.PARAMETER));

				return null;
			}
		);
	}

	@DisplayName("Should fetch entity with one page and enrich it with different one")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchEntityWithPageAndChangeItInEnrichment(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithMaxReferences = originalProducts
			.stream()
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(
				Entities.PARAMETER).size()))
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity firstFetch = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
						),
						require(
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									entityFetchAll(),
									entityGroupFetchAll(),
									page(1, 4)
								)
							)
						)
					)
				).orElseThrow();

				final Collection<ReferenceContract> originalParameters = productWithMaxReferences.getReferences(
					Entities.PARAMETER);
				final int[] expectedFirstFetchParameters = originalParameters
					.stream()
					.limit(4)
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray();

				final Collection<ReferenceContract> foundParametersOnFirstFetch = firstFetch.getReferences(
					Entities.PARAMETER);
				assertTrue(!foundParametersOnFirstFetch.isEmpty() && foundParametersOnFirstFetch.size() <= 4);
				assertEquals(
					foundParametersOnFirstFetch.size(), firstFetch.getReferences()
						.stream()
						.filter(it -> it.getReferenceName().equals(Entities.PARAMETER))
						.count()
				);
				assertArrayEquals(
					expectedFirstFetchParameters,
					firstFetch.getReferences(Entities.PARAMETER)
						.stream()
						.mapToInt(ReferenceContract::getReferencedPrimaryKey)
						.toArray()
				);

				for (ReferenceContract foundParameter : foundParametersOnFirstFetch) {
					assertNotNull(foundParameter.getReferencedEntity());
					assertNotNull(foundParameter.getGroupEntity().orElse(null));
				}

				PaginatedList<ReferenceContract> firstFetchChunk = new PaginatedList<>(
					1, 4, (int) originalParameters.stream()
					.count(), new ArrayList<>(foundParametersOnFirstFetch)
				);
				assertEquals(firstFetchChunk, firstFetch.getReferenceChunk(Entities.PARAMETER));

				final SealedEntity secondFetch = session.enrichEntity(
					firstFetch,
					referenceContent(
						Entities.PARAMETER,
						entityFetchAll(),
						entityGroupFetchAll(),
						strip(2, 4)
					)
				);

				final int[] expectedSecondFetchParameters = originalParameters
					.stream()
					.skip(2)
					.limit(4)
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray();

				final Collection<ReferenceContract> foundParametersOnSecondFetch = secondFetch.getReferences(
					Entities.PARAMETER);
				assertTrue(!foundParametersOnSecondFetch.isEmpty() && foundParametersOnSecondFetch.size() <= 4);
				assertEquals(
					foundParametersOnSecondFetch.size(), secondFetch.getReferences()
						.stream()
						.filter(it -> it.getReferenceName().equals(Entities.PARAMETER))
						.count()
				);
				assertArrayEquals(
					expectedSecondFetchParameters,
					secondFetch.getReferences(Entities.PARAMETER)
						.stream()
						.mapToInt(ReferenceContract::getReferencedPrimaryKey)
						.toArray()
				);

				for (ReferenceContract foundParameter : foundParametersOnSecondFetch) {
					assertNotNull(foundParameter.getReferencedEntity());
					assertNotNull(foundParameter.getGroupEntity().orElse(null));
				}

				StripList<ReferenceContract> secondFetchChunk = new StripList<>(
					2, 4, (int) originalParameters.stream()
					.count(), new ArrayList<>(foundParametersOnSecondFetch)
				);
				assertEquals(secondFetchChunk, secondFetch.getReferenceChunk(Entities.PARAMETER));

				return null;
			}
		);
	}

	@DisplayName("References should be ordered by entity primary key natural descending")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldOrderReferencesByEntityPrimaryKeyNaturalDesc(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithManyStores = originalProducts.stream()
			.filter(it -> it.getReferences(Entities.STORE).size() > 5)
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productWithManyStores.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContent(
									Entities.STORE,
									orderBy(
										entityProperty(
											entityPrimaryKeyNatural(OrderDirection.DESC)
										)
									)
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				final Collection<ReferenceContract> references = product.getReferences(Entities.STORE);

				final int[] receivedPrimaryKeys = references.stream()
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray();

				final int[] expectedPrimaryKeys = Arrays.stream(receivedPrimaryKeys)
					.boxed()
					.sorted(Comparator.reverseOrder())
					.mapToInt(Integer::intValue)
					.toArray();

				assertArrayEquals(expectedPrimaryKeys, receivedPrimaryKeys);

				return null;
			}
		);
	}

	@DisplayName("References should be ordered by entity primary key natural descending (directly)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldOrderReferencesByEntityPrimaryKeyNaturalDescDirectly(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithManyStores = originalProducts.stream()
			.filter(it -> it.getReferences(Entities.STORE).size() > 5)
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productWithManyStores.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContent(
									Entities.STORE,
									orderBy(
										entityPrimaryKeyNatural(OrderDirection.DESC)
									)
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				final Collection<ReferenceContract> references = product.getReferences(Entities.STORE);

				final int[] receivedPrimaryKeys = references.stream()
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray();

				final int[] expectedPrimaryKeys = Arrays.stream(receivedPrimaryKeys)
					.boxed()
					.sorted(Comparator.reverseOrder())
					.mapToInt(Integer::intValue)
					.toArray();

				assertArrayEquals(expectedPrimaryKeys, receivedPrimaryKeys);

				return null;
			}
		);
	}

	@DisplayName("References should be ordered by entity primary key exact order")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldOrderReferencesByEntityPrimaryKeyExact(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithManyStores = originalProducts.stream()
			.filter(it -> it.getReferences(Entities.STORE).size() > 5)
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// obtain original store primary keys and shuffle them with a fixed seed to keep the test deterministic
				final List<Integer> shuffledStorePks = productWithManyStores.getReferences(Entities.STORE)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.collect(Collectors.toCollection(ArrayList::new));
				Collections.shuffle(shuffledStorePks, new Random(42L));
				final Integer[] exactOrder = shuffledStorePks.toArray(new Integer[0]);

				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productWithManyStores.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContent(
									Entities.STORE,
									orderBy(
										entityProperty(
											entityPrimaryKeyExact(exactOrder)
										)
									)
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				final Collection<ReferenceContract> references = product.getReferences(Entities.STORE);

				final Integer[] receivedPrimaryKeys = references.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.toArray(Integer[]::new);

				assertArrayEquals(exactOrder, receivedPrimaryKeys);

				return null;
			}
		);
	}

	@DisplayName("References should be ordered by entity primary key exact order (directly)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldOrderReferencesByEntityPrimaryKeyExactDirectly(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithManyStores = originalProducts.stream()
			.filter(it -> it.getReferences(Entities.STORE).size() > 5)
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// obtain original store primary keys and shuffle them with a fixed seed to keep the test deterministic
				final List<Integer> shuffledStorePks = productWithManyStores.getReferences(Entities.STORE)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.collect(Collectors.toCollection(ArrayList::new));
				Collections.shuffle(shuffledStorePks, new Random(84L));
				final Integer[] exactOrder = shuffledStorePks.toArray(new Integer[0]);

				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productWithManyStores.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContent(
									Entities.STORE,
									orderBy(
										entityPrimaryKeyExact(exactOrder)
									)
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				final Collection<ReferenceContract> references = product.getReferences(Entities.STORE);

				final Integer[] receivedPrimaryKeys = references.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.toArray(Integer[]::new);

				assertArrayEquals(exactOrder, receivedPrimaryKeys);

				return null;
			}
		);
	}

	@DisplayName("References should be ordered by exact order for first three and then by PK natural descending")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldOrderReferencesByEntityPrimaryKeyExactThenNaturalDesc(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithManyStores = originalProducts.stream()
			.filter(it -> it.getReferences(Entities.STORE).size() > 5)
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// prepare exact order for first three stores using deterministic shuffle
				final List<Integer> storePks = productWithManyStores.getReferences(Entities.STORE)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.collect(Collectors.toCollection(ArrayList::new));
				Collections.shuffle(storePks, new Random(42L));
				final Integer[] exactFirstThree = storePks.stream().limit(3).toArray(Integer[]::new);

				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productWithManyStores.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContent(
									Entities.STORE,
									orderBy(
										entityProperty(
											entityPrimaryKeyExact(exactFirstThree),
											entityPrimaryKeyNatural(OrderDirection.DESC)
										)
									)
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				final List<Integer> receivedPrimaryKeys = product.getReferences(Entities.STORE)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.toList();

				// compute expected order: first exact three in given order, then the rest by descending PK
				final Set<Integer> exactSet = new HashSet<>(Arrays.asList(exactFirstThree));
				final List<Integer> remaining = product.getReferences(Entities.STORE)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.filter(pk -> !exactSet.contains(pk))
					.sorted(Comparator.reverseOrder())
					.toList();
				final List<Integer> expected = new ArrayList<>(Arrays.asList(exactFirstThree));
				expected.addAll(remaining);

				assertArrayEquals(expected.toArray(new Integer[0]), receivedPrimaryKeys.toArray(new Integer[0]));

				return null;
			}
		);
	}

	@DisplayName("References should be ordered by exact order for first three and then by PK natural descending (directly)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldOrderReferencesByEntityPrimaryKeyExactThenNaturalDescDirectly(
		Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithManyStores = originalProducts.stream()
			.filter(it -> it.getReferences(Entities.STORE).size() > 5)
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// prepare exact order for first three stores using deterministic shuffle
				final List<Integer> storePks = productWithManyStores.getReferences(Entities.STORE)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.collect(Collectors.toCollection(ArrayList::new));
				Collections.shuffle(storePks, new Random(84L));
				final Integer[] exactFirstThree = storePks.stream().limit(3).toArray(Integer[]::new);

				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productWithManyStores.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContent(
									Entities.STORE,
									orderBy(
										entityPrimaryKeyExact(exactFirstThree),
										entityPrimaryKeyNatural(OrderDirection.DESC)
									)
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				final List<Integer> receivedPrimaryKeys = product.getReferences(Entities.STORE)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.toList();

				// compute expected order: first exact three in given order, then the rest by descending PK
				final Set<Integer> exactSet = new HashSet<>(Arrays.asList(exactFirstThree));
				final List<Integer> remaining = product.getReferences(Entities.STORE)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.filter(pk -> !exactSet.contains(pk))
					.sorted(Comparator.reverseOrder())
					.toList();
				final List<Integer> expected = new ArrayList<>(Arrays.asList(exactFirstThree));
				expected.addAll(remaining);

				assertArrayEquals(expected.toArray(new Integer[0]), receivedPrimaryKeys.toArray(new Integer[0]));

				return null;
			}
		);
	}

}
