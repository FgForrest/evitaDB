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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesAvailabilityChecker;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
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
import one.edee.oss.pmptt.model.Hierarchy;
import one.edee.oss.pmptt.model.HierarchyItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Functional tests for entity reference fetch operations including reference retrieval,
 * reference attributes, named reference sets, and paginated references.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity reference fetch functionality")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(REFERENCE)
class EntityReferenceFetchFunctionalTest extends AbstractEntityFetchingFunctionalTest {
	/**
	 * Instance name of the named reference set the I/O accounting tests declare over {@link Entities#STORE}.
	 */
	private static final String MY_STORES = "myStores";
	private static final String MY_CATEGORIES = "myCategories";

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
	@DisplayName("In internal API, a named reference set that requires no initialization could be fetched")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchNamedReferenceSetRequiringNoInitialization(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.CATEGORY).isEmpty()
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
								// asks for attributes only - no bodies, no filter, no order, no paging, which is
								// the one shape whose requirements need no referenced entity index built for them
								new ReferenceContent(
									"plainCategories",
									ManagedReferencesBehaviour.ANY,
									new String[]{Entities.CATEGORY},
									new RequireConstraint[]{attributeContentAll()},
									new Constraint[0]
								)
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertInstanceOf(ServerEntityDecorator.class, product);
					final ServerEntityDecorator serverEntity = (ServerEntityDecorator) product;
					final DataChunk<ReferenceContract> plainCategories = serverEntity
						.getReferencesForReferenceContentInstance(
							new ReferenceContentKey("plainCategories", Entities.CATEGORY)
						)
						.orElseThrow();
					final Collection<ReferenceContract> allCategories = product.getReferences(Entities.CATEGORY);
					assertFalse(allCategories.isEmpty());
					// nothing narrows the set, so it holds every reference of that name, unpaginated
					assertEquals(allCategories.size(), plainCategories.getData().size());
					assertEquals(allCategories.size(), plainCategories.getTotalRecordCount());
					for (ReferenceContract category : plainCategories) {
						// the attributes were asked for and must be there
						assertNotNull(category.getAttributeValue(ATTRIBUTE_CATEGORY_SHADOW));
						// the bodies were not
						assertFalse(category.getReferencedEntity().isPresent());
					}
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

	@DisplayName("Store references filtered by a disjunctive entityHaving return exactly the matching ones")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterReferencesByDisjunctiveEntityHaving(
		Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalStores
	) {
		final SealedEntity firstStore = originalStores.get(0);
		final SealedEntity secondStore = originalStores.get(1);
		final String firstCode = firstStore.getAttribute(ATTRIBUTE_CODE, String.class);
		final String secondCode = secondStore.getAttribute(ATTRIBUTE_CODE, String.class);
		final Set<Integer> acceptedStorePks = Set.of(
			firstStore.getPrimaryKeyOrThrowException(), secondStore.getPrimaryKeyOrThrowException()
		);

		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences(Entities.STORE)
				.stream()
				.anyMatch(ref -> acceptedStorePks.contains(ref.getReferencedPrimaryKey()))
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
										entityHaving(
											or(
												attributeEquals(ATTRIBUTE_CODE, firstCode),
												attributeEquals(ATTRIBUTE_CODE, secondCode)
											)
										)
									)
								)
							),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getRecordData().size());

				// the engine narrows the nested query behind an `entityHaving` to the keys the owners actually
				// reference. That key set has to be AND-ed *alongside* the disjunction, never folded into it - a fold
				// would widen the result to every store the owners reference, which is what this comparison against
				// the original data catches
				for (SealedEntity product : productByPk.getRecordData()) {
					assertEquals(
						expectedReferencedPks(originalProducts, product, acceptedStorePks::contains),
						actualReferencedPks(product)
					);
				}
				return null;
			}
		);
	}

	@DisplayName("Store references filtered by a negated entityHaving return every reference but one")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterReferencesByNegatedEntityHaving(
		Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalStores
	) {
		final SealedEntity excludedStore = originalStores.get(0);
		final String excludedCode = excludedStore.getAttribute(ATTRIBUTE_CODE, String.class);
		final int excludedPk = excludedStore.getPrimaryKeyOrThrowException();

		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences(Entities.STORE)
				.stream()
				.anyMatch(ref -> ref.getReferencedPrimaryKey() == excludedPk)
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
									filterBy(entityHaving(not(attributeEquals(ATTRIBUTE_CODE, excludedCode))))
								)
							),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getRecordData().size());

				// a negation takes its superset from the conjuncts beside it, so injecting the owners' key set
				// changes what that superset *is*. The outer intersection is what makes the two agree again, and
				// this is the shape where it would show if it did not
				for (SealedEntity product : productByPk.getRecordData()) {
					assertEquals(
						expectedReferencedPks(originalProducts, product, pk -> pk != excludedPk),
						actualReferencedPks(product)
					);
				}
				return null;
			}
		);
	}

	/**
	 * Returns the {@link Entities#STORE} primary keys the passed product carries in the original (unfiltered) data,
	 * kept only where the predicate accepts them - the answer a filtered reference content has to produce.
	 */
	@Nonnull
	private static Set<Integer> expectedReferencedPks(
		@Nonnull List<SealedEntity> originalProducts,
		@Nonnull SealedEntity product,
		@Nonnull IntPredicate acceptedReferencedPk
	) {
		return originalProducts.stream()
			.filter(it -> Objects.equals(it.getPrimaryKey(), product.getPrimaryKey()))
			.findFirst()
			.orElseThrow()
			.getReferences(Entities.STORE)
			.stream()
			.mapToInt(ReferenceContract::getReferencedPrimaryKey)
			.filter(acceptedReferencedPk)
			.boxed()
			.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Returns the {@link Entities#STORE} primary keys the passed product actually carries after the query ran.
	 */
	@Nonnull
	private static Set<Integer> actualReferencedPks(@Nonnull SealedEntity product) {
		return product.getReferences(Entities.STORE)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toUnmodifiableSet());
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

	@DisplayName("References filtered by the query and by the requirement should be returned as a page")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnFilteredReferencePageWhenReferenceIsAlsoFiltered(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.CATEGORY).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> response = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(entitiesMatchingTheRequirements),
								referenceHaving(
									Entities.CATEGORY,
									entityPrimaryKeyInSet(1, 2, 3, 4, 5)
								)
							)
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									filterBy(entityPrimaryKeyInSet(1, 2, 3, 4, 5)),
									page(1, 2)
								)
							)
						)
					)
				);

				assertFalse(response.getRecordData().isEmpty());
				for (final SealedEntity product : response.getRecordData()) {
					final DataChunk<ReferenceContract> categories =
						product.getReferenceChunk(Entities.CATEGORY);
					assertTrue(categories.getData().size() <= 2, "The reference page must not exceed its size!");
					for (final ReferenceContract category : categories) {
						assertTrue(
							category.getReferencedPrimaryKey() <= 5,
							"Only the categories matching the requirement filter may be returned!"
						);
					}
				}
				return null;
			}
		);
	}

	/**
	 * Pins that the reads a requested referenced body costs are reported by the entity that asked for it, and are
	 * reported exactly once.
	 *
	 * A referenced entity's body is read only because `referenceContent` carried an `entityFetch`, so its cost
	 * belongs to the statistics of the entity that carried the requirement. Both arms fetch the very same product
	 * and the very same reference set; the only difference is whether the referenced bodies are materialized, so
	 * whatever the second arm reports over the first is exactly what those bodies cost.
	 */
	@DisplayName("Should count the IO statistics of requested referenced bodies exactly once")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldCountTheIoStatisticsOfRequestedReferencedBodiesExactlyOnce(
		Evita evita, List<SealedEntity> originalProducts
	) {
		final int productPk = productMatching(
			originalProducts,
			it -> it.getReferences(Entities.STORE).size() >= 2,
			"a product with several store references"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final ServerEntityDecorator withoutBodies = fetchProductWith(
					session, productPk, referenceContent(Entities.STORE)
				);
				final ServerEntityDecorator withBodies = fetchProductWith(
					session, productPk, referenceContent(Entities.STORE, entityFetch(attributeContentAll()))
				);

				assertAttachedBodiesCountedOnce(
					withoutBodies, withBodies,
					distinctBodies(withBodies.getReferences(Entities.STORE), ReferenceContract::getReferencedEntity),
					"Requested referenced bodies"
				);
				return null;
			}
		);
	}

	/**
	 * Pins that the reads a requested **group** body costs are reported by the entity that asked for it, and are
	 * reported exactly once.
	 *
	 * A group body is reached through `ReferenceContract#getGroupEntity()`, which is a slot of its own - an
	 * accounting that walks only the referenced entities misses it entirely, and with it every attribute, price and
	 * reference the group body itself carries. The arms differ solely in `entityGroupFetch`, and one group is
	 * routinely shared by many references, so the expected cost is summed over *distinct* group bodies.
	 */
	@DisplayName("Should count the IO statistics of requested group bodies exactly once")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldCountTheIoStatisticsOfRequestedGroupBodiesExactlyOnce(
		Evita evita, List<SealedEntity> originalProducts
	) {
		final int productPk = productMatching(
			originalProducts,
			it -> it.getReferences(Entities.PARAMETER).stream().anyMatch(ref -> ref.getGroup().isPresent()),
			"a product whose parameter references carry a group"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final ServerEntityDecorator withoutGroups = fetchProductWith(
					session, productPk,
					referenceContent(Entities.PARAMETER, entityFetch(attributeContent()))
				);
				final ServerEntityDecorator withGroups = fetchProductWith(
					session, productPk,
					referenceContent(
						Entities.PARAMETER, entityFetch(attributeContent()), entityGroupFetch(attributeContent())
					)
				);

				assertAttachedBodiesCountedOnce(
					withoutGroups, withGroups,
					distinctBodies(withGroups.getReferences(Entities.PARAMETER), ReferenceContract::getGroupEntity),
					"Requested group bodies"
				);
				return null;
			}
		);
	}

	/**
	 * Pins that bodies fetched into a **named** reference set are reported by the entity that asked for them, and
	 * are reported exactly once.
	 *
	 * A named `referenceContent` fetches bodies into a set of its own, keyed by instance name, which the ordinary
	 * reference traversal never reaches. Both arms declare the same named set over the same reference name; only
	 * the second asks for the bodies.
	 */
	@DisplayName("Should count the IO statistics of bodies fetched into a named reference set exactly once")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldCountTheIoStatisticsOfNamedReferenceSetBodiesExactlyOnce(
		Evita evita, List<SealedEntity> originalProducts
	) {
		final int productPk = productMatching(
			originalProducts,
			it -> it.getReferences(Entities.STORE).size() >= 2,
			"a product with several store references"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final ServerEntityDecorator withoutBodies = fetchProductWith(
					session, productPk, namedStoreReferenceSet(false)
				);
				final ServerEntityDecorator withBodies = fetchProductWith(
					session, productPk, namedStoreReferenceSet(true)
				);

				final DataChunk<ReferenceContract> namedSet = withBodies
					.getReferencesForReferenceContentInstance(new ReferenceContentKey(MY_STORES, Entities.STORE))
					.orElseThrow();
				assertAttachedBodiesCountedOnce(
					withoutBodies, withBodies,
					distinctBodies(namedSet.getData(), ReferenceContract::getReferencedEntity),
					"Bodies of a named reference set"
				);
				return null;
			}
		);
	}

	/**
	 * Pins that an entity this one exposes through two views is counted once.
	 *
	 * Each named reference set is composed on its own, through its own prefetch index, so two named sets over the
	 * same reference name arrive as two distinct decorator objects describing the very same referenced entities.
	 * The per-entity statistic reports what this product would have cost fetched alone, and fetching it alone reads
	 * each referenced entity once however many views of it the request happens to ask for - so the two arms must
	 * report the same number. Object identity cannot collapse those views; only the entity they describe can.
	 */
	@DisplayName("Should not count a body the query composed twice as two reads")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldNotCountARepeatedCompositionOfTheSameBodyTwice(Evita evita, List<SealedEntity> originalProducts) {
		final int productPk = productMatching(
			originalProducts,
			it -> it.getReferences(Entities.STORE).size() >= 2,
			"a product with several store references"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final ServerEntityDecorator composedOnce = fetchProductWith(
					session, productPk, namedStoreReferenceSet(true)
				);
				final ServerEntityDecorator composedTwice = fetchProductWith(
					session, productPk,
					namedStoreReferenceSet(true),
					namedStoreReferenceSet(MY_STORES + "Again", true)
				);

				assertEquals(
					composedOnce.getIoFetchCount(),
					composedTwice.getIoFetchCount(),
					"A second view of the same entities describes the same reads and must count once."
				);
				assertEquals(
					composedOnce.getIoFetchedBytes(),
					composedTwice.getIoFetchedBytes(),
					"A second view of the same entities describes the same Bytes and must count once."
				);
				return null;
			}
		);
	}
	/**
	 * Pins that an enrichment adding a second view of bodies it already holds costs nothing extra.
	 *
	 * An enrichment does not re-read the bodies its input resolved - it hands the very same ones to the ordinary
	 * prefetch and to each named prefetch, which wrap them again into decorators of their own. The product then
	 * exposes one referenced entity through two objects that describe a single set of reads, and counting both is
	 * how the two mechanisms this one replaced went wrong.
	 */
	@DisplayName("Should not count a body a second reference set only re-wraps")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldNotCountAReWrappedBodyTwiceOnEnrichment(Evita evita, List<SealedEntity> originalProducts) {
		final int productPk = productMatching(
			originalProducts,
			it -> it.getReferences(Entities.STORE).size() >= 2,
			"a product with several store references"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final ReferenceContent ordinaryStores = referenceContent(
					Entities.STORE, entityFetch(attributeContentAll())
				);

				final ServerEntityDecorator ordinaryOnly = enrichProductWith(
					session, productPk, ordinaryStores
				);
				final ServerEntityDecorator ordinaryAndNamed = enrichProductWith(
					session, productPk, ordinaryStores, namedStoreReferenceSet(true)
				);

				assertTrue(
					ordinaryOnly.getIoFetchCount() > 0,
					"The fixture must make fetching the store bodies cost something at all."
				);
				assertEquals(
					ordinaryOnly.getIoFetchCount(),
					ordinaryAndNamed.getIoFetchCount(),
					"A named set re-wrapping bodies the entity already holds must add nothing."
				);
				assertEquals(
					ordinaryOnly.getIoFetchedBytes(),
					ordinaryAndNamed.getIoFetchedBytes(),
					"A named set re-wrapping bodies the entity already holds must add no Bytes."
				);
				return null;
			}
		);
	}

	/**
	 * Pins that bodies read and then dropped by paging are still reported by the entity whose requirement read
	 * them.
	 *
	 * An ordering that ranks references by a property of their **group** cannot rank anything before every
	 * candidate group is known, so the engine has to read all of them and only then sort and slice. Asking for the
	 * first five of a hundred therefore reads a hundred, and the ninety-five the page dropped are exposed by
	 * nobody. The per-entity statistic reports what obtaining this entity cost, which the page size does not
	 * change - so the paged arm has to report exactly what the unpaged one does.
	 */
	@DisplayName("Should count bodies the requested page dropped")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldCountBodiesTheRequestedPageDropped(Evita evita, List<SealedEntity> originalProducts) {
		final int pageSize = 2;
		// every reference has to carry a group, or the ordering below has nothing to rank by
		final int productPk = productMatching(
			originalProducts,
			it -> it.getLocales().contains(CZECH_LOCALE) &&
				it.getReferences(Entities.PARAMETER).size() > pageSize &&
				it.getReferences(Entities.PARAMETER).stream().allMatch(ref -> ref.getGroup().isPresent()),
			"a product with more grouped parameter references than fit one page"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final ServerEntityDecorator wholeSet = fetchGroupOrderedParameters(session, productPk, null);
				final ServerEntityDecorator onePage = fetchGroupOrderedParameters(session, productPk, pageSize);

				assertEquals(
					pageSize, onePage.getReferences(Entities.PARAMETER).size(),
					"The paged arm must expose fewer references than the whole set, or it drops nothing."
				);
				assertTrue(
					wholeSet.getReferences(Entities.PARAMETER).size() > pageSize,
					"The unpaged arm must expose the whole set, or the arms do not differ."
				);

				assertEquals(
					wholeSet.getIoFetchCount(),
					onePage.getIoFetchCount(),
					"Paging the reference set changes what the entity exposes, not what obtaining it cost."
				);
				assertEquals(
					wholeSet.getIoFetchedBytes(),
					onePage.getIoFetchedBytes(),
					"Paging the reference set changes what the entity exposes, not what it read."
				);
				return null;
			}
		);
	}

	/**
	 * Pins that an owner exposing two **disjoint** views of one referenced entity reports the union of what they
	 * read, not the larger of the two.
	 *
	 * Ordinary and named reference requirements are held in independent maps with independent prefetch indexes, so
	 * one request really can ask for a referenced entity's Czech attributes through one and its English attributes
	 * through the other. The two views then describe the same entity, share its body, and each hold one attribute
	 * record the other does not - so neither contains the other, and keeping the richer of the two silently drops
	 * whatever the poorer one alone had to read. Asking for both locales through a single view reads exactly the
	 * union, which is what the two-view arm has to report.
	 */
	@DisplayName("Should count the union of two disjoint views of one referenced entity")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldCountTheUnionOfTwoDisjointViewsOfOneReferencedEntity(
		Evita evita, List<SealedEntity> originalProducts
	) {
		final int productPk = productMatching(
			originalProducts,
			it -> !it.getReferences(Entities.CATEGORY).isEmpty(),
			"a product referencing at least one category"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// one view reads the Czech attribute record, the other the English one, and both read the body
				final ServerEntityDecorator twoDisjointViews = fetchProductWith(
					session, productPk,
					referenceContent(
						Entities.CATEGORY, entityFetch(attributeContentAll(), dataInLocales(CZECH_LOCALE))
					),
					namedCategorySet(entityFetch(attributeContentAll(), dataInLocales(Locale.ENGLISH)))
				);
				// the same records, reached through a single view of the same entity
				final ServerEntityDecorator oneRicherView = fetchProductWith(
					session, productPk,
					referenceContent(
						Entities.CATEGORY, entityFetch(attributeContentAll(), dataInLocalesAll())
					),
					namedCategorySet()
				);

				assertFalse(
					twoDisjointViews.getReferences(Entities.CATEGORY).isEmpty(),
					"The fixture must expose the referenced categories at all."
				);
				assertTrue(
					oneRicherView.getIoFetchCount() > 0,
					"Reading the referenced categories has to cost something at all."
				);

				assertEquals(
					oneRicherView.getIoFetchCount(),
					twoDisjointViews.getIoFetchCount(),
					"Two disjoint views of one entity cost their union, which is what one richer view reads."
				);
				assertEquals(
					oneRicherView.getIoFetchedBytes(),
					twoDisjointViews.getIoFetchedBytes(),
					"Two disjoint views of one entity read their union, which is what one richer view reads."
				);
				return null;
			}
		);
	}

	/**
	 * Pins that an entity reachable through two different bodies of this one is counted once, not once per path.
	 *
	 * Two of the categories this product references sit under one and the same parent, so that parent is reachable
	 * through two of the product's bodies. De-duplicating only among the bodies the product carries cannot see it -
	 * the parent is not one of them, it hangs off each of them - and adding up what each body reports bills the
	 * shared parent once per category leading to it. The arms differ solely in whether the parent bodies are
	 * materialized, so whatever the second reports over the first is exactly what those parents cost, and the
	 * expectation is summed over the **distinct** parents.
	 */
	@DisplayName("Should count a body two referenced bodies share exactly once")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldCountABodyTwoReferencedBodiesShareExactlyOnce(
		Evita evita, List<SealedEntity> originalProducts, Hierarchy categoryHierarchy
	) {
		final int productPk = productMatching(
			originalProducts,
			it -> sharesAParent(categoryHierarchy, it.getReferences(Entities.CATEGORY)),
			"a product referencing two categories that sit under one parent"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final ServerEntityDecorator withoutParentBodies = fetchProductWith(
					session, productPk,
					referenceContent(Entities.CATEGORY, entityFetch(hierarchyContent()))
				);
				final ServerEntityDecorator withParentBodies = fetchProductWith(
					session, productPk,
					referenceContent(
						Entities.CATEGORY,
						entityFetch(
							hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll()))
						)
					)
				);

				// one parent body per referenced category, indexed by the entity it is rather than by the object
				// carrying it - which is the whole point, since two categories reach the same parent
				final Map<Integer, ServerEntityDecorator> distinctParents = new HashMap<>(8);
				int categoriesCarryingAParent = 0;
				for (ReferenceContract reference : withParentBodies.getReferences(Entities.CATEGORY)) {
					final SealedEntity category = reference.getReferencedEntity().orElseThrow();
					final EntityClassifierWithParent parent = category.getParentEntity().orElse(null);
					if (parent instanceof ServerEntityDecorator parentBody) {
						categoriesCarryingAParent++;
						distinctParents.putIfAbsent(parentBody.getPrimaryKeyOrThrowException(), parentBody);
					}
				}
				assertTrue(
					distinctParents.size() < categoriesCarryingAParent,
					"The fixture must offer a parent that two referenced categories share."
				);

				int expectedFetchCount = 0;
				int expectedFetchedBytes = 0;
				for (ServerEntityDecorator parent : distinctParents.values()) {
					expectedFetchCount += parent.getIoFetchCount();
					expectedFetchedBytes += parent.getIoFetchedBytes();
				}
				assertTrue(
					expectedFetchCount > 0,
					"Reading the parent bodies has to cost at least one fetch."
				);

				assertEquals(
					expectedFetchCount,
					withParentBodies.getIoFetchCount() - withoutParentBodies.getIoFetchCount(),
					"A parent two referenced categories share must contribute its fetch count exactly once."
				);
				assertEquals(
					expectedFetchedBytes,
					withParentBodies.getIoFetchedBytes() - withoutParentBodies.getIoFetchedBytes(),
					"A parent two referenced categories share must contribute its fetched Bytes exactly once."
				);
				return null;
			}
		);
	}

	/**
	 * Pins that reaching a given richness by enrichment costs the entity exactly what reaching it in one fetch
	 * costs.
	 *
	 * The per-entity statistic reports what the entity would have cost fetched on its own, so the route taken to
	 * it is not allowed to show up in the number: the same entity, at the same richness, is the same entity. Both
	 * arms end at `attributeContentAll()` plus the store bodies; the first asks for all of it at once, the second
	 * asks for the attributes and then widens. Whatever the second reports over the first is work the enrichment
	 * did that the entity did not need - re-reading parts it already held.
	 */
	@DisplayName("Should cost the same whether reached by one fetch or by an enrichment")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldCostTheSameWhetherReachedByFetchOrByEnrichment(Evita evita, List<SealedEntity> originalProducts) {
		final int productPk = productMatching(
			originalProducts,
			it -> it.getReferences(Entities.STORE).size() >= 2,
			"a product with several store references"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final ReferenceContent stores = referenceContent(
					Entities.STORE, entityFetch(attributeContentAll(), dataInLocalesAll())
				);

				final ServerEntityDecorator inOneFetch = fetchProductWith(
					session, productPk, attributeContentAll(), dataInLocalesAll(), stores
				);
				final ServerEntityDecorator byEnrichment = enrichProductWith(
					session, productPk, attributeContentAll(), dataInLocalesAll(), stores
				);

				assertEquals(
					inOneFetch.getIoFetchCount(),
					byEnrichment.getIoFetchCount(),
					"The route to a given richness must not change what the entity cost."
				);
				assertEquals(
					inOneFetch.getIoFetchedBytes(),
					byEnrichment.getIoFetchedBytes(),
					"The route to a given richness must not change what the entity read."
				);
				return null;
			}
		);
	}

	/**
	 * Builds the named reference set over {@link Entities#STORE} the accounting tests declare, with or without the
	 * referenced bodies.
	 *
	 * @param withBodies whether the referenced bodies are to be materialized
	 * @return the requirement to put into `entityFetch`
	 */
	@Nonnull
	private static ReferenceContent namedStoreReferenceSet(boolean withBodies) {
		return namedStoreReferenceSet(MY_STORES, withBodies);
	}

	/**
	 * Builds a named reference set over {@link Entities#STORE} under the passed instance name, with or without the
	 * referenced bodies.
	 *
	 * The `strip` is not incidental: a named reference content that asks for no bodies, no filter, no order and no
	 * paging requires no prefetching at all, and the engine then reads nothing on its behalf - so the arms would
	 * differ in more than the bodies. Both arms carry it, so the only difference between them stays the
	 * `entityFetch`.
	 *
	 * @param instanceName name of the reference content instance
	 * @param withBodies   whether the referenced bodies are to be materialized
	 * @return the requirement to put into `entityFetch`
	 */
	@Nonnull
	private static ReferenceContent namedStoreReferenceSet(@Nonnull String instanceName, boolean withBodies) {
		return new ReferenceContent(
			instanceName,
			ManagedReferencesBehaviour.ANY,
			new String[]{Entities.STORE},
			withBodies ?
				new RequireConstraint[]{attributeContentAll(), entityFetch(attributeContentAll()), strip(0, 100)} :
				new RequireConstraint[]{attributeContentAll(), strip(0, 100)},
			new Constraint[0]
		);
	}

	/**
	 * Asserts that `attachedBodies` contribute their reads to `withBodies` exactly once, taking `withoutBodies` -
	 * the same fetch with the bodies left out - as the baseline everything else is common to.
	 *
	 * @param withoutBodies  the arm that did not materialize the bodies
	 * @param withBodies     the arm that did
	 * @param attachedBodies the bodies that arm materialized, each exactly once
	 * @param subject        what the bodies are, for the assertion messages
	 */
	private static void assertAttachedBodiesCountedOnce(
		@Nonnull ServerEntityDecorator withoutBodies,
		@Nonnull ServerEntityDecorator withBodies,
		@Nonnull Collection<ServerEntityDecorator> attachedBodies,
		@Nonnull String subject
	) {
		assertFalse(attachedBodies.isEmpty(), subject + " must have been materialized at all.");

		int expectedFetchCount = 0;
		int expectedFetchedBytes = 0;
		for (ServerEntityDecorator body : attachedBodies) {
			expectedFetchCount += body.getIoFetchCount();
			expectedFetchedBytes += body.getIoFetchedBytes();
		}
		assertTrue(expectedFetchCount > 0, "Reading " + subject.toLowerCase() + " has to cost at least one fetch.");

		assertEquals(
			expectedFetchCount,
			withBodies.getIoFetchCount() - withoutBodies.getIoFetchCount(),
			subject + " must contribute their fetch count exactly once."
		);
		assertEquals(
			expectedFetchedBytes,
			withBodies.getIoFetchedBytes() - withoutBodies.getIoFetchedBytes(),
			subject + " must contribute their fetched Bytes exactly once."
		);
	}

	/**
	 * Collects the distinct bodies the passed slot of the passed references carries.
	 *
	 * Distinctness is by instance and is load-bearing: one group body is routinely shared by many references, and
	 * summing per reference would expect a single read to be billed once per reference pointing at it.
	 *
	 * @param references   references to walk
	 * @param bodyAccessor the slot to read off each of them
	 * @return the distinct bodies found there
	 */
	@Nonnull
	private static Collection<ServerEntityDecorator> distinctBodies(
		@Nonnull Collection<ReferenceContract> references,
		@Nonnull Function<ReferenceContract, Optional<SealedEntity>> bodyAccessor
	) {
		final Set<ServerEntityDecorator> bodies = Collections.newSetFromMap(new IdentityHashMap<>());
		for (ReferenceContract reference : references) {
			bodyAccessor.apply(reference)
				.map(ServerEntityDecorator.class::cast)
				.ifPresent(bodies::add);
		}
		return bodies;
	}

	/**
	 * Fetches a product's parameter references ordered by a property of their group, optionally limited to a single
	 * page. The ordering is what forces every candidate body to be read before anything can be sliced away.
	 *
	 * @param session    session to query through
	 * @param primaryKey primary key of the product to fetch
	 * @param pageSize   size of the requested page, or NULL to ask for the whole set
	 * @return the returned entity, as the decorator that carries its I/O statistics
	 */
	@Nonnull
	private static ServerEntityDecorator fetchGroupOrderedParameters(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer pageSize
	) {
		final EvitaResponse<SealedEntity> response = session.querySealedEntity(
			query(
				collection(Entities.PRODUCT),
				filterBy(
					and(
						entityPrimaryKeyInSet(primaryKey),
						entityLocaleEquals(CZECH_LOCALE)
					)
				),
				require(
					entityFetch(
						referenceContent(
							Entities.PARAMETER,
							orderBy(entityGroupProperty(attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC))),
							entityFetchAll(),
							entityGroupFetchAll(),
							pageSize == null ? null : page(1, pageSize)
						)
					)
				)
			)
		);
		assertEquals(1, response.getRecordData().size());
		return assertInstanceOf(ServerEntityDecorator.class, response.getRecordData().get(0));
	}

	/**
	 * Builds a named reference set over {@link Entities#CATEGORY}, optionally materializing the referenced bodies.
	 *
	 * The `strip` is what makes the set require prefetching at all, so that both arms of the caller run the same
	 * machinery and differ solely in what the bodies are fetched with.
	 *
	 * @param bodyRequirements what the referenced bodies are to be fetched with, none to leave them unfetched
	 * @return the requirement to put into `entityFetch`
	 */
	@Nonnull
	private static ReferenceContent namedCategorySet(@Nonnull RequireConstraint... bodyRequirements) {
		final RequireConstraint[] requirements = new RequireConstraint[bodyRequirements.length + 1];
		System.arraycopy(bodyRequirements, 0, requirements, 0, bodyRequirements.length);
		requirements[bodyRequirements.length] = strip(0, 100);
		return new ReferenceContent(
			MY_CATEGORIES,
			ManagedReferencesBehaviour.ANY,
			new String[]{Entities.CATEGORY},
			requirements,
			new Constraint[0]
		);
	}

	/**
	 * Tells whether two of the passed category references sit under one and the same parent in the fixture
	 * hierarchy, which is what makes that parent reachable through two bodies of the referencing entity.
	 *
	 * @param categoryHierarchy   the fixture hierarchy
	 * @param categoryReferences  the category references of one product
	 * @return TRUE when at least two of them share a parent
	 */
	private static boolean sharesAParent(
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull Collection<ReferenceContract> categoryReferences
	) {
		final Set<String> parentCodes = new HashSet<>(categoryReferences.size());
		for (ReferenceContract reference : categoryReferences) {
			final HierarchyItem parent = categoryHierarchy.getParentItem(
				String.valueOf(reference.getReferencedPrimaryKey())
			);
			if (parent != null && !parentCodes.add(parent.getCode())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the primary key of the first product the passed predicate accepts.
	 *
	 * @param originalProducts the fixture products
	 * @param predicate        what the product has to offer
	 * @param expectation      description of the expectation, for the failure message
	 * @return primary key of the matching product
	 */
	private static int productMatching(
		@Nonnull List<SealedEntity> originalProducts,
		@Nonnull java.util.function.Predicate<SealedEntity> predicate,
		@Nonnull String expectation
	) {
		return originalProducts
			.stream()
			.filter(predicate)
			.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
			.findFirst()
			.orElseThrow(() -> new AssertionError("The fixture must offer " + expectation + "."));
	}

	/**
	 * Fetches a single product by primary key under the passed requirements.
	 *
	 * @param session            session to query through
	 * @param primaryKey         primary key of the product to fetch
	 * @param entityRequirements the requirements shaping what is materialized
	 * @return the returned entity, as the decorator that carries its I/O statistics
	 */
	@Nonnull
	private static ServerEntityDecorator fetchProductWith(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nonnull EntityContentRequire... entityRequirements
	) {
		final EvitaResponse<SealedEntity> response = session.querySealedEntity(
			query(
				collection(Entities.PRODUCT),
				filterBy(entityPrimaryKeyInSet(primaryKey)),
				require(entityFetch(entityRequirements))
			)
		);
		assertEquals(1, response.getRecordData().size());
		return assertInstanceOf(ServerEntityDecorator.class, response.getRecordData().get(0));
	}

	/**
	 * Fetches the product bare and then enriches it with the passed requirements, so the bodies the enrichment
	 * needs are resolved by an enrichment rather than by the original fetch.
	 *
	 * @param session            session to query through
	 * @param primaryKey         primary key of the product to fetch
	 * @param entityRequirements requirements the enrichment widens the entity by
	 * @return the enriched entity, as the decorator that carries its I/O statistics
	 */
	@Nonnull
	private static ServerEntityDecorator enrichProductWith(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nonnull EntityContentRequire... entityRequirements
	) {
		final SealedEntity fetched = fetchProductWith(session, primaryKey, attributeContentAll());
		return assertInstanceOf(
			ServerEntityDecorator.class,
			session.enrichEntity(fetched, entityRequirements)
		);
	}

	/**
	 * Pins that the groups read for references the page sliced away are counted.
	 *
	 * When a paged `referenceContent` carries no ordering, the engine slices the reference set **before** fetching
	 * and reads only the page's referenced entity bodies - so the paged arm legitimately costs less than the
	 * unpaged one. The group bodies are the exception: `BitmapSlicer#getGroupIds` returns the groups of the whole
	 * filtered set whichever slicing path ran, so every filtered reference's group is read regardless of the page.
	 * A group reached solely through a reference the page dropped is then carried by no reference at all - the
	 * reference that would have carried it has no referenced entity, and a group is only ever attached beside one -
	 * so nothing walking the exposed graph can find it. Isolating the group half of the cost is what makes the two
	 * arms comparable: the entity bodies differ between them by design, the groups must not.
	 */
	@DisplayName("Should count group bodies read for references the page sliced away")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldCountGroupBodiesReadForReferencesThePageSlicedAway(Evita evita, List<SealedEntity> originalProducts) {
		final int pageSize = 1;
		// several distinct groups are required, or the page's own group is the whole set and nothing is dropped
		final int productPk = productMatching(
			originalProducts,
			it -> it.getReferences(Entities.PARAMETER).size() > pageSize &&
				it.getReferences(Entities.PARAMETER).stream().allMatch(ref -> ref.getGroup().isPresent()) &&
				it.getReferences(Entities.PARAMETER).stream()
					.map(ref -> ref.getGroup().orElseThrow().primaryKey())
					.distinct()
					.count() > 1,
			"a product whose parameter references span several groups and outnumber one page"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final ServerEntityDecorator wholeSetWithGroups = fetchPagedParameters(session, productPk, null, true);
				final ServerEntityDecorator wholeSetBare = fetchPagedParameters(session, productPk, null, false);
				final ServerEntityDecorator onePageWithGroups = fetchPagedParameters(session, productPk, pageSize, true);
				final ServerEntityDecorator onePageBare = fetchPagedParameters(session, productPk, pageSize, false);

				assertEquals(
					pageSize, onePageWithGroups.getReferences(Entities.PARAMETER).size(),
					"The paged arm must expose one reference, or it drops nothing."
				);

				final int groupCostWholeSet = wholeSetWithGroups.getIoFetchCount() - wholeSetBare.getIoFetchCount();
				final int groupCostOnePage = onePageWithGroups.getIoFetchCount() - onePageBare.getIoFetchCount();
				assertTrue(
					groupCostWholeSet > 1,
					"The whole set has to read more than one group body, or the arms cannot differ."
				);
				assertEquals(
					groupCostWholeSet, groupCostOnePage,
					"Every filtered reference's group is read whatever the page shows, so the page must not change what the groups cost."
				);
				assertEquals(
					wholeSetWithGroups.getIoFetchedBytes() - wholeSetBare.getIoFetchedBytes(),
					onePageWithGroups.getIoFetchedBytes() - onePageBare.getIoFetchedBytes(),
					"Every filtered reference's group is read whatever the page shows, so the page must not change what the groups read."
				);
				return null;
			}
		);
	}

	/**
	 * Fetches the product's parameter references, optionally paged and optionally with the group bodies, and
	 * deliberately **without** an ordering so the order-free pre-fetch slice engages.
	 *
	 * @param session         session to query through
	 * @param primaryKey      primary key of the product to fetch
	 * @param pageSize        size of the requested page, or NULL for the whole set
	 * @param withGroupBodies whether the group bodies are to be materialized
	 * @return the entity, as the decorator that carries its I/O statistics
	 */
	@Nonnull
	private static ServerEntityDecorator fetchPagedParameters(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer pageSize,
		boolean withGroupBodies
	) {
		final EvitaResponse<SealedEntity> response = session.querySealedEntity(
			query(
				collection(Entities.PRODUCT),
				filterBy(entityPrimaryKeyInSet(primaryKey)),
				require(
					entityFetch(
						referenceContent(
							Entities.PARAMETER,
							(io.evitadb.api.query.order.OrderBy) null,
							entityFetchAll(),
							withGroupBodies ? entityGroupFetchAll() : (io.evitadb.api.query.require.EntityGroupFetch) null,
							pageSize == null ? null : page(1, pageSize)
						)
					)
				)
			)
		);
		assertEquals(1, response.getRecordData().size());
		return assertInstanceOf(ServerEntityDecorator.class, response.getRecordData().get(0));
	}

	/**
	 * Pins that an entity is not billed for a group body only its page-mate reached.
	 *
	 * One group prefetch index serves every owner entity in the batch, while which references a given owner keeps
	 * is decided per owner by the reference `filterBy`. An owner whose own reference to a group was filtered away
	 * caused none of that group's read - its page-mate did - so the owner's standalone cost must be exactly what it
	 * is when that page-mate is not in the query at all.
	 */
	@DisplayName("Should not bill an entity for a group only its page-mate reached")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldNotBillAnEntityForAGroupOnlyItsPageMateReached(Evita evita, List<SealedEntity> originalProducts) {
		Integer excludedOwnerPk = null;
		Integer mateOwnerPk = null;
		Integer keptParameterPk = null;

		// two products sharing a parameter group, through parameters that are not the same parameter: the filter
		// below keeps the mate's parameter, so the shared group enters the index without the other owner's doing
		outer:
		for (SealedEntity candidate : originalProducts) {
			final Collection<ReferenceContract> candidateRefs = candidate.getReferences(Entities.PARAMETER);
			final Set<Integer> ownParameters = candidateRefs.stream()
				.map(it -> it.getReferenceKey().primaryKey())
				.collect(Collectors.toSet());
			final Set<Integer> ownGroups = candidateRefs.stream()
				.filter(it -> it.getGroup().isPresent())
				.map(it -> it.getGroup().orElseThrow().primaryKey())
				.collect(Collectors.toSet());
			if (ownGroups.isEmpty()) {
				continue;
			}
			for (SealedEntity mate : originalProducts) {
				if (mate.getPrimaryKeyOrThrowException() == candidate.getPrimaryKeyOrThrowException()) {
					continue;
				}
				for (ReferenceContract mateRef : mate.getReferences(Entities.PARAMETER)) {
					final int mateParameter = mateRef.getReferenceKey().primaryKey();
					if (ownParameters.contains(mateParameter) || mateRef.getGroup().isEmpty()) {
						continue;
					}
					if (ownGroups.contains(mateRef.getGroup().orElseThrow().primaryKey())) {
						excludedOwnerPk = candidate.getPrimaryKeyOrThrowException();
						mateOwnerPk = mate.getPrimaryKeyOrThrowException();
						keptParameterPk = mateParameter;
						break outer;
					}
				}
			}
		}
		assertNotNull(
			excludedOwnerPk,
			"the dataset must hold two products reaching one parameter group through different parameters"
		);

		final int excludedOwner = excludedOwnerPk;
		final int mateOwner = mateOwnerPk;
		final int keptParameter = keptParameterPk;

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final ServerEntityDecorator alone = productFromBatch(
					session, new int[]{excludedOwner}, keptParameter, excludedOwner
				);
				final ServerEntityDecorator besideItsMate = productFromBatch(
					session, new int[]{excludedOwner, mateOwner}, keptParameter, excludedOwner
				);

				assertEquals(
					alone.getIoFetchCount(),
					besideItsMate.getIoFetchCount(),
					"A group its page-mate reached is not this entity's cost."
				);
				assertEquals(
					alone.getIoFetchedBytes(),
					besideItsMate.getIoFetchedBytes(),
					"A group its page-mate reached is not this entity's read."
				);
				return null;
			}
		);
	}

	/**
	 * Queries the passed products together, keeping only the named parameter on every one of them, and returns the
	 * one asked for.
	 *
	 * @param session         session to query through
	 * @param primaryKeys     products to put in one batch
	 * @param keptParameterPk the only parameter the reference filter keeps
	 * @param wantedPk        primary key of the product to return
	 * @return the wanted entity, as the decorator that carries its I/O statistics
	 */
	@Nonnull
	private static ServerEntityDecorator productFromBatch(
		@Nonnull EvitaSessionContract session,
		@Nonnull int[] primaryKeys,
		int keptParameterPk,
		int wantedPk
	) {
		final EvitaResponse<SealedEntity> response = session.querySealedEntity(
			query(
				collection(Entities.PRODUCT),
				filterBy(entityPrimaryKeyInSet(primaryKeys)),
				require(
					entityFetch(
						referenceContent(
							Entities.PARAMETER,
							filterBy(entityHaving(entityPrimaryKeyInSet(keptParameterPk))),
							entityFetch(attributeContent()),
							entityGroupFetch(attributeContent())
						)
					),
					page(1, Integer.MAX_VALUE)
				)
			)
		);
		return response.getRecordData().stream()
			.filter(it -> it.getPrimaryKeyOrThrowException() == wantedPk)
			.findFirst()
			.map(it -> assertInstanceOf(ServerEntityDecorator.class, it))
			.orElseThrow(() -> new AssertionError("product " + wantedPk + " missing from the response"));
	}

}
