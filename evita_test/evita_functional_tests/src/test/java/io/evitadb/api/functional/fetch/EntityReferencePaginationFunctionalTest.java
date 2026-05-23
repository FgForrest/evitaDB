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
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
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

	@DisplayName("Should provide paginated access to references ordered by reference attribute (regression for issue #1177)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnPaginatedReferencesOrderedByReferenceAttribute(Evita evita, List<SealedEntity> originalProducts) {
		// iterate the dataset and pick the first product that exercises the bug. The picker mirrors
		// what the server query will compute, then layers extra preconditions on top of that so the
		// strict assertions below are unambiguous:
		//   1. priorityRanked = source references that have the priority attribute set, sorted by
		//      priority DESC — this is exactly what the server returns for page(1, pageSize).
		//   2. priorityRanked must have at least pageSize entries (so the page is full).
		//   3. The top pageSize by priority must disagree with the top pageSize by PK among the same
		//      priorityRanked set — otherwise the buggy PK-bitmap slice would accidentally agree
		//      with the post-fetch sort and the bug wouldn't be exercised.
		//   4. Every reference in the top pageSize by priority must also have a group set in the
		//      source data — so the per-reference groupEntity assertion is meaningful.
		// Under the fix, the response page must equal that top-pageSize-by-priority and every
		// reference must have both referencedEntity and groupEntity populated.
		final int pageSize = 5;
		final Comparator<ReferenceContract> byPriorityDesc = Comparator
			.comparing((ReferenceContract r) -> r.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class))
			.reversed();
		final SealedEntity bugCandidate = originalProducts.stream()
			.filter(product -> {
				final List<ReferenceContract> priorityRanked = product.getReferences(Entities.PARAMETER).stream()
					.filter(r -> r.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class) != null)
					.sorted(byPriorityDesc)
					.toList();
				if (priorityRanked.size() < pageSize) {
					return false;
				}
				final List<ReferenceContract> topByPriority = priorityRanked.subList(0, pageSize);
				if (topByPriority.stream().anyMatch(r -> r.getGroup().isEmpty())) {
					return false;
				}
				final List<Integer> topPksByPriority = topByPriority.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.toList();
				final List<Integer> topPksByPk = priorityRanked.stream()
					.sorted(Comparator.comparingInt(ReferenceContract::getReferencedPrimaryKey))
					.limit(pageSize)
					.map(ReferenceContract::getReferencedPrimaryKey)
					.toList();
				return !topPksByPriority.equals(topPksByPk);
			})
			.findFirst()
			.orElseThrow(() -> new IllegalStateException(
				"No product in HUNDRED_PRODUCTS has " + pageSize + " PARAMETER references with priority " +
					"set where the top-" + pageSize + "-by-priority all have groups AND disagree with " +
					"the top-" + pageSize + "-by-PK — dataset cannot exercise issue #1177."
			));

		final List<Integer> expectedTopPks = bugCandidate.getReferences(Entities.PARAMETER).stream()
			.filter(r -> r.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class) != null)
			.sorted(byPriorityDesc)
			.limit(pageSize)
			.map(ReferenceContract::getReferencedPrimaryKey)
			.toList();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(bugCandidate.getPrimaryKeyOrThrowException())
						),
						require(
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									filterBy(attributeIsNotNull(ATTRIBUTE_CATEGORY_PRIORITY)),
									orderBy(attributeNatural(ATTRIBUTE_CATEGORY_PRIORITY, OrderDirection.DESC)),
									entityFetchAll(),
									entityGroupFetchAll(),
									page(1, pageSize)
								)
							)
						)
					)
				).orElseThrow();

				final Collection<ReferenceContract> foundParameters = productByPk.getReferences(Entities.PARAMETER);
				assertEquals(pageSize, foundParameters.size(), "Expected exactly the requested page size");

				// regression check: every returned reference must have its referenced entity body AND
				// its group body fetched. Without the fix, the PK-based pre-fetch slice and the
				// attribute-based post-fetch slice disagree and the post-sort winners come back with
				// null bodies. The candidate-picker guarantees every expected reference has a group
				// in the source data, so this assertion is unambiguous.
				for (ReferenceContract foundParameter : foundParameters) {
					assertTrue(
						foundParameter.getReferencedEntity().isPresent(),
						"Reference to parameter #" + foundParameter.getReferencedPrimaryKey() +
							" has no referencedEntity — the pre-fetch slice picked different PKs than the post-fetch sort."
					);
					assertTrue(
						foundParameter.getGroupEntity().isPresent(),
						"Reference to parameter #" + foundParameter.getReferencedPrimaryKey() +
							" has no groupEntity — group body was not fetched for this reference."
					);
				}

				final List<Integer> actualTopPks = foundParameters.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.toList();
				assertEquals(
					expectedTopPks, actualTopPks,
					"The returned page must contain the top references by ATTRIBUTE_CATEGORY_PRIORITY DESC, " +
						"in the requested order."
				);

				return null;
			}
		);
	}

	@DisplayName("Should provide paginated access to references with default comparator (PK-bitmap fast path)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnPaginatedReferencesWithDefaultComparatorPkOrder(
		Evita evita, List<SealedEntity> originalProducts
	) {
		// Default (no orderBy) takes the PK-bitmap fast path in createPrefetchedEntities. The
		// page must therefore be the natural-PK-ascending top-pageSize of the filtered
		// references, with both referencedEntity and groupEntity populated for each entry.
		final int pageSize = 5;
		final SealedEntity productWithParameters = originalProducts.stream()
			.filter(product -> product.getReferences(Entities.PARAMETER).size() >= pageSize)
			.filter(product -> product.getReferences(Entities.PARAMETER).stream()
				.allMatch(r -> r.getGroup().isPresent()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException(
				"No product in HUNDRED_PRODUCTS has at least " + pageSize +
					" PARAMETER references where every reference has a group."
			));

		final List<Integer> expectedPksAsc = productWithParameters.getReferences(Entities.PARAMETER).stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.sorted()
			.limit(pageSize)
			.toList();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productWithParameters.getPrimaryKeyOrThrowException())
						),
						require(
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									entityFetchAll(),
									entityGroupFetchAll(),
									page(1, pageSize)
								)
							)
						)
					)
				).orElseThrow();

				final Collection<ReferenceContract> foundParameters = productByPk.getReferences(Entities.PARAMETER);
				assertEquals(pageSize, foundParameters.size());

				final List<Integer> actualPks = foundParameters.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.toList();
				assertEquals(
					expectedPksAsc, actualPks,
					"PK-bitmap fast path must return the lowest pageSize PKs in ascending order"
				);

				for (ReferenceContract foundParameter : foundParameters) {
					assertTrue(
						foundParameter.getReferencedEntity().isPresent(),
						"Reference to parameter #" + foundParameter.getReferencedPrimaryKey() +
							" has no referencedEntity on the PK-bitmap fast path."
					);
					assertTrue(
						foundParameter.getGroupEntity().isPresent(),
						"Reference to parameter #" + foundParameter.getReferencedPrimaryKey() +
							" has no groupEntity on the PK-bitmap fast path."
					);
				}

				return null;
			}
		);
	}

	@DisplayName("Should provide paginated references when orderBy contains entity group property (group-sort fallback)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnPaginatedReferencesWhenOrderByContainsEntityGroupProperty(
		Evita evita, List<SealedEntity> originalProducts
	) {
		// When the reference orderBy contains an `entityGroupProperty`, the optimization in
		// createPrefetchedEntities can't engage (the group filter is computed downstream) and
		// the slicer falls back to fetching all filtered references via NoTransformer. The
		// post-fetch sort+chunk in EntityDecorator still has to produce a correct page with
		// fully-populated referencedEntity and groupEntity. The candidate-picker insists every
		// reference has a group so the per-reference assertions are unambiguous.
		final int pageSize = 5;
		final SealedEntity productWithGroupedParameters = originalProducts.stream()
			.filter(product -> product.getLocales().contains(CZECH_LOCALE))
			.filter(product -> product.getReferences(Entities.PARAMETER).size() >= pageSize)
			.filter(product -> product.getReferences(Entities.PARAMETER).stream()
				.allMatch(r -> r.getGroup().isPresent()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException(
				"No product in HUNDRED_PRODUCTS has at least " + pageSize +
					" PARAMETER references all having a group set — cannot exercise group-sort fallback."
			));

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(productWithGroupedParameters.getPrimaryKeyOrThrowException()),
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									orderBy(
										entityGroupProperty(
											attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)
										)
									),
									entityFetchAll(),
									entityGroupFetchAll(),
									page(1, pageSize)
								)
							)
						)
					)
				).orElseThrow();

				final Collection<ReferenceContract> foundParameters = productByPk.getReferences(Entities.PARAMETER);
				assertEquals(
					pageSize, foundParameters.size(),
					"Expected exactly the requested page size on the group-sort fallback path"
				);
				int groupEntitiesLoaded = 0;
				for (ReferenceContract foundParameter : foundParameters) {
					assertTrue(
						foundParameter.getReferencedEntity().isPresent(),
						"Reference to parameter #" + foundParameter.getReferencedPrimaryKey() +
							" has no referencedEntity on the group-sort fallback path."
					);
					if (foundParameter.getGroupEntity().isPresent()) {
						groupEntitiesLoaded++;
					}
				}
				assertTrue(
					groupEntitiesLoaded > 0,
					"At least one fetched parameter must have its groupEntity populated on the " +
						"group-sort fallback path (orderBy entityGroupProperty must trigger group fetch)"
				);

				return null;
			}
		);
	}

	@DisplayName("Should provide paginated references over multiple source entities (multi-entity initReferenceIndex)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnPaginatedReferencesOverMultipleSourceEntities(
		Evita evita, List<SealedEntity> originalProducts
	) {
		// Exercises the multi-entity branch of initReferenceIndex: a query that selects two
		// different source entities at once, each requesting page(1, K) on the same reference
		// name. Each source entity must independently get its first K references back, with
		// both referencedEntity and groupEntity populated.
		final int pageSize = 3;
		final List<SealedEntity> sources = originalProducts.stream()
			.filter(product -> product.getReferences(Entities.PARAMETER).size() >= pageSize)
			.filter(product -> product.getReferences(Entities.PARAMETER).stream()
				.allMatch(r -> r.getGroup().isPresent()))
			.limit(2)
			.toList();
		if (sources.size() < 2) {
			throw new IllegalStateException(
				"Dataset does not contain two products with at least " + pageSize +
					" PARAMETER references each (all having groups)."
			);
		}
		final SealedEntity sourceA = sources.get(0);
		final SealedEntity sourceB = sources.get(1);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> products = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(
								sourceA.getPrimaryKeyOrThrowException(),
								sourceB.getPrimaryKeyOrThrowException()
							)
						),
						require(
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									entityFetchAll(),
									entityGroupFetchAll(),
									page(1, pageSize)
								)
							)
						)
					),
					SealedEntity.class
				).getRecordData();

				assertEquals(2, products.size(), "Both source entities must come back");
				for (SealedEntity product : products) {
					final Collection<ReferenceContract> foundParameters = product.getReferences(Entities.PARAMETER);
					assertTrue(
						foundParameters.size() <= pageSize && !foundParameters.isEmpty(),
						"Each source entity must independently get up to pageSize references"
					);
					for (ReferenceContract foundParameter : foundParameters) {
						assertTrue(
							foundParameter.getReferencedEntity().isPresent(),
							"Reference to parameter #" + foundParameter.getReferencedPrimaryKey() +
								" has no referencedEntity (multi-source-entity path)."
						);
						assertTrue(
							foundParameter.getGroupEntity().isPresent(),
							"Reference to parameter #" + foundParameter.getReferencedPrimaryKey() +
								" has no groupEntity (multi-source-entity path)."
						);
					}
				}

				return null;
			}
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
