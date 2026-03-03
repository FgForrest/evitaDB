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

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesAvailabilityChecker;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.DataChunk;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional tests for entity reference fetch operations including reference retrieval,
 * reference attributes, named reference sets, and paginated references.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity reference fetch functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class EntityReferenceFetchFunctionalTest extends AbstractEntityFetchingFunctionalTest {

	@DisplayName("Multiple entities with references by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithReferencesByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences().isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements)
						),
						require(
							entityFetch(
								referenceContentAll()
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertFalse(product.getReferences().isEmpty());
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with specific references by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithSpecificReferencesByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.CATEGORY).isEmpty() && !it.getReferences(Entities.STORE).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements)
						),
						require(
							entityFetch(
								referenceContent(Entities.CATEGORY),
								referenceContent(Entities.STORE)
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertFalse(product.getReferences(Entities.CATEGORY).isEmpty());
					assertTrue(product.getReferences(Entities.CATEGORY).stream().noneMatch(AttributesContract::attributesAvailable));
					assertFalse(product.getReferences(Entities.STORE).isEmpty());
					assertTrue(product.getReferences(Entities.STORE).stream().noneMatch(AttributesContract::attributesAvailable));
				}
				return null;
			}
		);
	}

	@DisplayName("Entities should be found by their primary keys with all references without attributes")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveEntitiesWithoutReferenceAttributes(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences().stream().noneMatch(ref -> ref.getAttributeValues().isEmpty())
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements)
						),
						require(
							entityFetch(
								referenceContentAll()
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertTrue(
						product.getReferences()
							.stream()
							.noneMatch(AttributesAvailabilityChecker::attributesAvailable)
					);
				}
				return null;
			}
		);
	}

	@DisplayName("Entities should be found by their primary keys with all references with all attributes")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveEntitiesWithAllReferenceAttributes(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences().stream().noneMatch(ref -> ref.getAttributeValues().isEmpty())
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements)
						),
						require(
							entityFetch(
								referenceContentAllWithAttributes()
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertTrue(
						product.getReferences()
							.stream()
							.allMatch(AttributesAvailabilityChecker::attributesAvailable)
					);
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with specific references with exactly stated attributes can be retrieved")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithSpecificReferencesByPrimaryKeyWithExactAttributes(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.CATEGORY).isEmpty() && !it.getReferences(Entities.STORE).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements)
						),
						require(
							entityFetch(
								referenceContentWithAttributes(
									Entities.CATEGORY,
									attributeContent(ATTRIBUTE_CATEGORY_SHADOW)
								)
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertFalse(product.getReferences(Entities.CATEGORY).isEmpty());
					for (ReferenceContract categoryRef : product.getReferences(Entities.CATEGORY)) {
						assertEquals(1, categoryRef.getAttributeValues().size());
						assertNotNull(categoryRef.getAttributeValue(ATTRIBUTE_CATEGORY_SHADOW));
					}
				}
				return null;
			}
		);
	}

	@DisplayName("In internal API, multiple reference sets with different filtering settings could be fetched")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchMultipleNamedReferenceSets(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> {
				final Map<Boolean, Long> shadow = it.getReferences(Entities.CATEGORY)
					.stream()
					.collect(
						Collectors.groupingBy(
							ref -> ref.getAttribute(ATTRIBUTE_CATEGORY_SHADOW, Boolean.class),
							Collectors.counting()
						)
					);
				return shadow.getOrDefault(true, 0L) > 0 && shadow.getOrDefault(false, 0L) > 0;
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements)
						),
						require(
							entityFetch(
								new ReferenceContent(
									"shadowfull",
									ManagedReferencesBehaviour.ANY,
									new String[] { Entities.CATEGORY },
									new RequireConstraint[]{ attributeContentAll(), entityFetchAll() },
									new Constraint[]{
										filterBy(attributeEquals(ATTRIBUTE_CATEGORY_SHADOW, true))
									}
								),
								new ReferenceContent(
									"shadowless",
									ManagedReferencesBehaviour.ANY,
									new String[] { Entities.CATEGORY },
									new RequireConstraint[]{ attributeContentAll(), entityFetchAll() },
									new Constraint[]{
										filterBy(attributeEquals(ATTRIBUTE_CATEGORY_SHADOW, false))
									}
								)
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertInstanceOf(ServerEntityDecorator.class, product);
					final ServerEntityDecorator serverEntity = (ServerEntityDecorator) product;
					final DataChunk<ReferenceContract> shadowfull = serverEntity.getReferencesForReferenceContentInstance(
						new ReferenceContentKey(
							"shadowfull",
							Entities.CATEGORY
						)
					).orElseThrow();
					shadowfull.forEach(ref -> assertTrue(ref.getAttribute(ATTRIBUTE_CATEGORY_SHADOW, Boolean.class)));
					final DataChunk<ReferenceContract> shadowless = serverEntity.getReferencesForReferenceContentInstance(
						new ReferenceContentKey(
							"shadowless",
							Entities.CATEGORY
						)
					).orElseThrow();
					shadowless.forEach(ref -> assertFalse(ref.getAttribute(ATTRIBUTE_CATEGORY_SHADOW, Boolean.class)));
					final Collection<ReferenceContract> allCategories = product.getReferences(Entities.CATEGORY);
					assertFalse(allCategories.isEmpty());
					assertEquals(allCategories.size(), shadowfull.getTotalRecordCount() + shadowless.getTotalRecordCount());
				}
				return null;
			}
		);
	}

	@DisplayName("In internal API, named reference set with different pagination settings could be fetched")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchMultipleNamedPaginatedReferenceSets(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences(Entities.PRICE_LIST).size() > 2
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements)
						),
						require(
							entityFetch(
								new ReferenceContent(
									"myPriceLists",
									ManagedReferencesBehaviour.ANY,
									new String[] { Entities.PRICE_LIST },
									new RequireConstraint[]{
										attributeContentAll(),
										entityFetchAll(),
										strip(0, 2)
									},
									new Constraint[0]
								)
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertInstanceOf(ServerEntityDecorator.class, product);
					final ServerEntityDecorator serverEntity = (ServerEntityDecorator) product;
					final DataChunk<ReferenceContract> myPriceLists = serverEntity.getReferencesForReferenceContentInstance(
						new ReferenceContentKey("myPriceLists", Entities.PRICE_LIST)
					)
						.orElseThrow();
					assertEquals(2, myPriceLists.getData().size());
					assertTrue(myPriceLists.getTotalRecordCount() > 2);
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with specific references with all attributes can be retrieved")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithSpecificReferencesByPrimaryKeyWithAllAttributes(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.CATEGORY).isEmpty() && !it.getReferences(Entities.STORE).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements)
						),
						require(
							entityFetch(
								referenceContentWithAttributes(
									Entities.CATEGORY,
									attributeContentAll()
								)
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertFalse(product.getReferences(Entities.CATEGORY).isEmpty());
					for (ReferenceContract categoryRef : product.getReferences(Entities.CATEGORY)) {
						assertFalse(categoryRef.getAttributeValues().isEmpty());
					}
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with specific references with all attributes (default behaviour) can be retrieved")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithSpecificReferencesByPrimaryKeyWithAllAttributesDefault(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.CATEGORY).isEmpty() && !it.getReferences(Entities.STORE).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements)
						),
						require(
							entityFetch(
								referenceContentWithAttributes(Entities.CATEGORY)
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertFalse(product.getReferences(Entities.CATEGORY).isEmpty());
					for (ReferenceContract categoryRef : product.getReferences(Entities.CATEGORY)) {
						assertFalse(categoryRef.getAttributeValues().isEmpty());
					}
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with references filtered by type and by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithReferencesByTypeAndByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.STORE).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements)
						),
						require(
							entityFetch(
								referenceContent(Entities.STORE)
							)
						)
					)
				);

				assertEquals(Math.min(entitiesMatchingTheRequirements.length, 20), productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertFalse(product.getReferences(Entities.STORE).isEmpty());
					assertThrows(ContextMissingException.class, () -> product.getReferences(Entities.BRAND));
					assertThrows(ContextMissingException.class, () -> product.getReferences(Entities.CATEGORY));
				}
				return null;
			}
		);
	}

	@DisplayName("Store references filtered by entityHaving should return only references to matching entities")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterStoreReferencesByEntityHavingAttributeEquals(
		Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalStores
	) {
		final SealedEntity firstStore = originalStores.get(0);
		final String targetStoreCode = firstStore.getAttribute(ATTRIBUTE_CODE, String.class);
		final int targetStorePk = firstStore.getPrimaryKey();

		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences(Entities.STORE)
				.stream()
				.anyMatch(ref -> ref.getReferencedPrimaryKey() == targetStorePk)
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(entitiesMatchingTheRequirements)),
						require(
							entityFetch(
								referenceContent(
									Entities.STORE,
									filterBy(entityHaving(attributeEquals(ATTRIBUTE_CODE, targetStoreCode))),
									entityFetch(attributeContent())
								)
							),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getRecordData().size());

				for (SealedEntity product : productByPk.getRecordData()) {
					final Collection<ReferenceContract> storeRefs = product.getReferences(Entities.STORE);
					assertFalse(storeRefs.isEmpty());
					for (ReferenceContract ref : storeRefs) {
						assertEquals(targetStorePk, ref.getReferencedPrimaryKey());
						final SealedEntity referencedStore = ref.getReferencedEntity().orElseThrow();
						assertEquals(targetStoreCode, referencedStore.getAttribute(ATTRIBUTE_CODE, String.class));
					}
				}
				return null;
			}
		);
	}

	@DisplayName("Category references filtered by entityPrimaryKeyInSet should return only references matching specified PKs")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterCategoryReferencesByEntityPrimaryKeyInSet(
		Evita evita, List<SealedEntity> originalProducts, Map<Integer, SealedEntity> originalCategories
	) {
		final Integer[] targetCategoryPks = originalCategories.keySet()
			.stream()
			.sorted()
			.limit(3)
			.toArray(Integer[]::new);
		final Set<Integer> targetCategorySet = Set.of(targetCategoryPks);

		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences(Entities.CATEGORY)
				.stream()
				.anyMatch(ref -> targetCategorySet.contains(ref.getReferencedPrimaryKey()))
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(entitiesMatchingTheRequirements)),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									filterBy(entityPrimaryKeyInSet(targetCategoryPks))
								)
							),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getRecordData().size());

				for (SealedEntity product : productByPk.getRecordData()) {
					final Collection<ReferenceContract> categoryRefs = product.getReferences(Entities.CATEGORY);
					assertFalse(categoryRefs.isEmpty());
					for (ReferenceContract ref : categoryRefs) {
						assertTrue(
							targetCategorySet.contains(ref.getReferencedPrimaryKey()),
							"Reference to category " + ref.getReferencedPrimaryKey() +
								" should not be present, expected only " + targetCategorySet
						);
					}

					final SealedEntity originalProduct = originalProducts.stream()
						.filter(p -> p.getPrimaryKey().equals(product.getPrimaryKey()))
						.findFirst()
						.orElseThrow();
					final long expectedCount = originalProduct.getReferences(Entities.CATEGORY)
						.stream()
						.filter(ref -> targetCategorySet.contains(ref.getReferencedPrimaryKey()))
						.count();
					assertEquals(expectedCount, categoryRefs.size());
				}
				return null;
			}
		);
	}

	@DisplayName("Parameter references filtered by groupHaving should return only references from matching groups")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterParameterReferencesByGroupHaving(Evita evita, List<SealedEntity> originalProducts) {
		final Set<Integer> allGroupPks = originalProducts.stream()
			.flatMap(p -> p.getReferences(Entities.PARAMETER).stream())
			.map(ref -> ref.getGroup().orElse(null))
			.filter(Objects::nonNull)
			.map(GroupEntityReference::getPrimaryKey)
			.collect(Collectors.toSet());

		assertFalse(allGroupPks.isEmpty(), "There should be at least one parameter group.");

		final int targetGroupPk = allGroupPks.iterator().next();

		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences(Entities.PARAMETER)
				.stream()
				.anyMatch(
					ref -> ref.getGroup()
						.map(group -> group.getPrimaryKey() == targetGroupPk)
						.orElse(false)
				)
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(entitiesMatchingTheRequirements)),
						require(
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									filterBy(groupHaving(entityPrimaryKeyInSet(targetGroupPk))),
									entityFetch(attributeContent()),
									entityGroupFetch(attributeContent())
								)
							),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getRecordData().size());

				for (SealedEntity product : productByPk.getRecordData()) {
					final Collection<ReferenceContract> paramRefs = product.getReferences(Entities.PARAMETER);
					assertFalse(paramRefs.isEmpty());
					for (ReferenceContract ref : paramRefs) {
						final int groupPk = ref.getGroup().orElseThrow().getPrimaryKey();
						assertEquals(
							targetGroupPk, groupPk,
							"Parameter reference group PK should be " + targetGroupPk + " but was " + groupPk
						);
						assertTrue(ref.getGroupEntity().isPresent());
					}

					final SealedEntity originalProduct = originalProducts.stream()
						.filter(p -> p.getPrimaryKey().equals(product.getPrimaryKey()))
						.findFirst()
						.orElseThrow();
					final long expectedCount = originalProduct.getReferences(Entities.PARAMETER)
						.stream()
						.filter(
							ref -> ref.getGroup()
								.map(group -> group.getPrimaryKey() == targetGroupPk)
								.orElse(false)
						)
						.count();
					assertEquals(expectedCount, paramRefs.size());
				}
				return null;
			}
		);
	}

	@DisplayName("Store references filtered by entityHaving combined with entityPrimaryKeyInSet should satisfy both")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterStoreReferencesByEntityHavingCombinedWithEntityPrimaryKeyInSet(
		Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalStores
	) {
		final Map<Integer, String> storeCodeByPk = originalStores.stream()
			.collect(
				Collectors.toMap(
					SealedEntity::getPrimaryKey,
					store -> store.getAttribute(ATTRIBUTE_CODE, String.class)
				)
			);

		final Integer[] pkSubset = originalStores.stream()
			.limit(6)
			.map(SealedEntity::getPrimaryKey)
			.toArray(Integer[]::new);
		final Set<Integer> pkSubsetSet = Set.of(pkSubset);

		final String[] codeSubset = originalStores.stream()
			.limit(4)
			.map(store -> store.getAttribute(ATTRIBUTE_CODE, String.class))
			.toArray(String[]::new);
		final Set<String> codeSubsetSet = Set.of(codeSubset);

		final Set<Integer> expectedStorePks = storeCodeByPk.entrySet()
			.stream()
			.filter(e -> pkSubsetSet.contains(e.getKey()) && codeSubsetSet.contains(e.getValue()))
			.map(Map.Entry::getKey)
			.collect(Collectors.toSet());

		assertFalse(expectedStorePks.isEmpty(), "There should be at least one store in the intersection.");

		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences(Entities.STORE)
				.stream()
				.anyMatch(ref -> expectedStorePks.contains(ref.getReferencedPrimaryKey()))
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(entitiesMatchingTheRequirements)),
						require(
							entityFetch(
								referenceContent(
									Entities.STORE,
									filterBy(
										entityHaving(attributeInSet(ATTRIBUTE_CODE, codeSubset)),
										entityPrimaryKeyInSet(pkSubset)
									),
									entityFetch(attributeContent())
								)
							),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getRecordData().size());

				for (SealedEntity product : productByPk.getRecordData()) {
					final Collection<ReferenceContract> storeRefs = product.getReferences(Entities.STORE);
					assertFalse(storeRefs.isEmpty());
					for (ReferenceContract ref : storeRefs) {
						assertTrue(
							expectedStorePks.contains(ref.getReferencedPrimaryKey()),
							"Store reference PK " + ref.getReferencedPrimaryKey() +
								" should be in expected set " + expectedStorePks
						);
					}
				}
				return null;
			}
		);
	}

	@DisplayName("Parameter references filtered by groupHaving and entityHaving should satisfy both constraints")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterParameterReferencesByGroupHavingCombinedWithEntityHaving(
		Evita evita, List<SealedEntity> originalProducts
	) {
		final SealedEntity richProduct = originalProducts.stream()
			.filter(p -> p.getReferences(Entities.PARAMETER).size() > 4)
			.findFirst()
			.orElseThrow();

		final int targetGroupPk = richProduct.getReferences(Entities.PARAMETER)
			.stream()
			.map(ref -> ref.getGroup().orElse(null))
			.filter(Objects::nonNull)
			.map(GroupEntityReference::getPrimaryKey)
			.findFirst()
			.orElseThrow();

		final Set<Integer> paramsInTargetGroup = richProduct.getReferences(Entities.PARAMETER)
			.stream()
			.filter(
				ref -> ref.getGroup()
					.map(group -> group.getPrimaryKey() == targetGroupPk)
					.orElse(false)
			)
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());

		final Set<Integer> paramsNotInTargetGroup = richProduct.getReferences(Entities.PARAMETER)
			.stream()
			.filter(
				ref -> ref.getGroup()
					.map(group -> group.getPrimaryKey() != targetGroupPk)
					.orElse(true)
			)
			.map(ReferenceContract::getReferencedPrimaryKey)
			.limit(2)
			.collect(Collectors.toSet());

		final Set<Integer> targetParamPkSet = new HashSet<>(paramsInTargetGroup);
		targetParamPkSet.addAll(paramsNotInTargetGroup);
		final Integer[] targetParamPks = targetParamPkSet.toArray(Integer[]::new);

		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences(Entities.PARAMETER)
				.stream()
				.anyMatch(
					ref -> paramsInTargetGroup.contains(ref.getReferencedPrimaryKey()) &&
						ref.getGroup()
							.map(group -> group.getPrimaryKey() == targetGroupPk)
							.orElse(false)
				)
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(entitiesMatchingTheRequirements)),
						require(
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									filterBy(
										groupHaving(entityPrimaryKeyInSet(targetGroupPk)),
										entityHaving(entityPrimaryKeyInSet(targetParamPks))
									),
									entityFetch(attributeContent()),
									entityGroupFetch(attributeContent())
								)
							),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getRecordData().size());

				for (SealedEntity product : productByPk.getRecordData()) {
					final Collection<ReferenceContract> paramRefs = product.getReferences(Entities.PARAMETER);
					assertFalse(paramRefs.isEmpty());
					for (ReferenceContract ref : paramRefs) {
						assertTrue(
							paramsInTargetGroup.contains(ref.getReferencedPrimaryKey()),
							"Parameter PK " + ref.getReferencedPrimaryKey() +
								" should be in expected set " + paramsInTargetGroup
						);
						assertEquals(
							targetGroupPk,
							ref.getGroup().orElseThrow().getPrimaryKey(),
							"Parameter reference should belong to group " + targetGroupPk
						);
					}
				}
				return null;
			}
		);
	}

	@DisplayName("Category references filtered by shadow reference attribute should return only matching references")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterCategoryReferencesByShadowAttribute(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences(Entities.CATEGORY)
				.stream()
				.anyMatch(ref -> Boolean.TRUE.equals(ref.getAttribute(ATTRIBUTE_CATEGORY_SHADOW, Boolean.class)))
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(entitiesMatchingTheRequirements)),
						require(
							entityFetch(
								referenceContentWithAttributes(
									Entities.CATEGORY,
									filterBy(attributeEquals(ATTRIBUTE_CATEGORY_SHADOW, true)),
									attributeContentAll()
								)
							),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getRecordData().size());

				for (SealedEntity product : productByPk.getRecordData()) {
					final Collection<ReferenceContract> categoryRefs = product.getReferences(Entities.CATEGORY);
					assertFalse(categoryRefs.isEmpty());
					for (ReferenceContract ref : categoryRefs) {
						assertTrue(
							ref.getAttribute(ATTRIBUTE_CATEGORY_SHADOW, Boolean.class),
							"Category reference should have shadow attribute set to true"
						);
					}

					final SealedEntity originalProduct = originalProducts.stream()
						.filter(p -> p.getPrimaryKey().equals(product.getPrimaryKey()))
						.findFirst()
						.orElseThrow();
					final long expectedCount = originalProduct.getReferences(Entities.CATEGORY)
						.stream()
						.filter(
							ref -> Boolean.TRUE.equals(ref.getAttribute(ATTRIBUTE_CATEGORY_SHADOW, Boolean.class))
						)
						.count();
					assertEquals(expectedCount, categoryRefs.size());
				}
				return null;
			}
		);
	}

}
