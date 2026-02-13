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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
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

}
