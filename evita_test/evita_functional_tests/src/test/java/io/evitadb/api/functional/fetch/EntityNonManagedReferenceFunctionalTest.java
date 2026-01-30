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
import io.evitadb.api.exception.EntityNotManagedException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.core.Evita;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying non-managed entity reference functionality including filtering,
 * ordering, pagination, attribute access, and lazy loading of non-managed references.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita non-managed entity reference functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class EntityNonManagedReferenceFunctionalTest extends AbstractEntityFetchingFunctionalTest {

	@DisplayName("Non-managed references should be filtered by reference attribute")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterNonManagedReferencesByAttribute(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = findEntityByPredicate(
			originalProducts,
			it -> {
				final Collection<ReferenceContract> galleryRefs = it.getReferences(REFERENCE_GALLERY);
				final long distinctDiscriminators = galleryRefs.stream()
					.map(ref -> ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class))
					.filter(Objects::nonNull)
					.distinct()
					.count();
				return galleryRefs.size() >= 2 && distinctDiscriminators >= 2;
			}
		);

		final String targetDiscriminator = targetProduct.getReferences(REFERENCE_GALLERY).stream()
			.map(ref -> ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class))
			.filter(Objects::nonNull)
			.findFirst()
			.orElseThrow();

		final long expectedCount = targetProduct.getReferences(REFERENCE_GALLERY).stream()
			.filter(ref -> targetDiscriminator.equals(ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class)))
			.count();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(targetProduct.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContentWithAttributes(
									REFERENCE_GALLERY,
									filterBy(
										attributeEquals(ATTRIBUTE_GALLERY_DISCRIMINATOR, targetDiscriminator)
									),
									attributeContentAll()
								)
							)
						)
					)
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				final Collection<ReferenceContract> filteredRefs = product.getReferences(REFERENCE_GALLERY);
				assertEquals(expectedCount, filteredRefs.size());
				for (ReferenceContract ref : filteredRefs) {
					assertEquals(
						targetDiscriminator,
						ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class),
						"All returned gallery references must match the discriminator filter!"
					);
				}
				return null;
			}
		);
	}

	@DisplayName("Non-managed references should be ordered by reference attribute")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldOrderNonManagedReferencesByAttribute(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = findEntityByPredicate(
			originalProducts,
			it -> it.getReferences(REFERENCE_GALLERY).size() >= 2
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(targetProduct.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContentWithAttributes(
									REFERENCE_GALLERY,
									orderBy(
										attributeNatural(ATTRIBUTE_GALLERY_ORDER, OrderDirection.ASC)
									),
									attributeContentAll()
								)
							)
						)
					)
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				final Long[] receivedOrders = product.getReferences(REFERENCE_GALLERY)
					.stream()
					.map(ref -> ref.getAttribute(ATTRIBUTE_GALLERY_ORDER, Long.class))
					.toArray(Long[]::new);

				// verify ascending order (nulls first)
				for (int i = 1; i < receivedOrders.length; i++) {
					if (receivedOrders[i - 1] != null && receivedOrders[i] != null) {
						assertTrue(
							receivedOrders[i - 1] <= receivedOrders[i],
							"Gallery references must be ordered by galleryOrder ascending!"
						);
					}
				}
				return null;
			}
		);
	}

	@DisplayName("Non-managed references should be both filtered and ordered by reference attributes")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterAndOrderNonManagedReferencesByAttributes(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = findEntityByPredicate(
			originalProducts,
			it -> {
				final Collection<ReferenceContract> galleryRefs = it.getReferences(REFERENCE_GALLERY);
				final long distinctDiscriminators = galleryRefs.stream()
					.map(ref -> ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class))
					.filter(Objects::nonNull)
					.distinct()
					.count();
				return galleryRefs.size() >= 3 && distinctDiscriminators >= 2;
			}
		);

		final String targetDiscriminator = targetProduct.getReferences(REFERENCE_GALLERY).stream()
			.map(ref -> ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class))
			.filter(Objects::nonNull)
			.findFirst()
			.orElseThrow();

		final long expectedCount = targetProduct.getReferences(REFERENCE_GALLERY).stream()
			.filter(ref -> targetDiscriminator.equals(ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class)))
			.count();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(targetProduct.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContentWithAttributes(
									REFERENCE_GALLERY,
									filterBy(
										attributeEquals(ATTRIBUTE_GALLERY_DISCRIMINATOR, targetDiscriminator)
									),
									orderBy(
										attributeNatural(ATTRIBUTE_GALLERY_ORDER, OrderDirection.DESC)
									),
									attributeContentAll()
								)
							)
						)
					)
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				final Collection<ReferenceContract> filteredRefs = product.getReferences(REFERENCE_GALLERY);
				assertEquals(expectedCount, filteredRefs.size());

				// verify all match discriminator
				for (ReferenceContract ref : filteredRefs) {
					assertEquals(
						targetDiscriminator,
						ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class)
					);
				}

				// verify descending order
				final Long[] receivedOrders = filteredRefs.stream()
					.map(ref -> ref.getAttribute(ATTRIBUTE_GALLERY_ORDER, Long.class))
					.toArray(Long[]::new);
				for (int i = 1; i < receivedOrders.length; i++) {
					if (receivedOrders[i - 1] != null && receivedOrders[i] != null) {
						assertTrue(
							receivedOrders[i - 1] >= receivedOrders[i],
							"Gallery references must be ordered by galleryOrder descending!"
						);
					}
				}
				return null;
			}
		);
	}

	@DisplayName("Entity having constraint on non-managed reference should throw EntityNotManagedException")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowWhenEntityHavingUsedOnNonManagedReference(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityNotManagedException.class,
					() -> session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(1)
							),
							require(
								entityFetch(
									referenceContentWithAttributes(
										REFERENCE_GALLERY,
										filterBy(
											entityHaving(
												attributeEquals(ATTRIBUTE_CODE, "someValue")
											)
										),
										attributeContentAll()
									)
								)
							)
						)
					)
				);
				return null;
			}
		);
	}

	@DisplayName("Non-managed references with all attributes can be retrieved")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchNonManagedReferencesWithAllAttributes(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = findEntityByPredicate(
			originalProducts,
			it -> !it.getReferences(REFERENCE_GALLERY).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(targetProduct.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContentWithAttributes(
									REFERENCE_GALLERY,
									attributeContentAll()
								)
							)
						)
					)
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				final Collection<ReferenceContract> galleryRefs = product.getReferences(REFERENCE_GALLERY);
				assertFalse(galleryRefs.isEmpty(), "Product should have gallery references!");

				for (ReferenceContract ref : galleryRefs) {
					assertTrue(ref.attributesAvailable(), "Attributes should be available on gallery references!");
					assertNotNull(
						ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR),
						"Discriminator attribute should be present!"
					);
					assertNotNull(
						ref.getAttribute(ATTRIBUTE_GALLERY_ORDER),
						"Gallery order attribute should be present!"
					);
				}
				return null;
			}
		);
	}

	@DisplayName("Non-managed references with specific attribute can be retrieved")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchNonManagedReferencesWithSpecificAttribute(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = findEntityByPredicate(
			originalProducts,
			it -> !it.getReferences(REFERENCE_GALLERY).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(targetProduct.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContentWithAttributes(
									REFERENCE_GALLERY,
									attributeContent(ATTRIBUTE_GALLERY_DISCRIMINATOR)
								)
							)
						)
					)
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				final Collection<ReferenceContract> galleryRefs = product.getReferences(REFERENCE_GALLERY);
				assertFalse(galleryRefs.isEmpty());

				for (ReferenceContract ref : galleryRefs) {
					assertTrue(ref.attributesAvailable());
					assertEquals(1, ref.getAttributeValues().size(), "Only discriminator attribute should be present!");
					assertNotNull(ref.getAttributeValue(ATTRIBUTE_GALLERY_DISCRIMINATOR));
				}
				return null;
			}
		);
	}

	@DisplayName("Fetching entity body for non-managed reference should throw EntityNotManagedException")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldNotFetchEntityBodyForNonManagedReference(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = findEntityByPredicate(
			originalProducts,
			it -> !it.getReferences(REFERENCE_GALLERY).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityNotManagedException.class,
					() -> session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(targetProduct.getPrimaryKey())
							),
							require(
								entityFetch(
									referenceContent(
										REFERENCE_GALLERY,
										entityFetch(
											attributeContentAll()
										)
									)
								)
							)
						)
					)
				);
				return null;
			}
		);
	}

	@DisplayName("All references including non-managed can be retrieved with referenceContentAll")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchAllReferencesIncludingNonManaged(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = findEntityByPredicate(
			originalProducts,
			it -> !it.getReferences(REFERENCE_GALLERY).isEmpty() &&
				!it.getReferences(Entities.CATEGORY).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(targetProduct.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContentAll()
							)
						)
					)
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);

				// verify both managed and non-managed references are present
				assertFalse(
					product.getReferences(REFERENCE_GALLERY).isEmpty(), "Gallery references should be present!");
				assertFalse(
					product.getReferences(Entities.CATEGORY).isEmpty(), "Category references should be present!");
				assertEquals(
					targetProduct.getReferences(REFERENCE_GALLERY).size(),
					product.getReferences(REFERENCE_GALLERY).size(),
					"All gallery references should be returned!"
				);
				return null;
			}
		);
	}

	@DisplayName("Named reference set with filtering on non-managed reference attributes")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchNamedReferenceSetsWithNonManagedReferenceFiltering(
		Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = findEntityByPredicate(
			originalProducts,
			it -> {
				final Collection<ReferenceContract> galleryRefs = it.getReferences(REFERENCE_GALLERY);
				final long distinctDiscriminators = galleryRefs.stream()
					.map(ref -> ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class))
					.filter(Objects::nonNull)
					.distinct()
					.count();
				return galleryRefs.size() >= 2 && distinctDiscriminators >= 2;
			}
		);

		final String targetDiscriminator = targetProduct.getReferences(REFERENCE_GALLERY).stream()
			.map(ref -> ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class))
			.filter(Objects::nonNull)
			.findFirst()
			.orElseThrow();

		final long expectedCount = targetProduct.getReferences(REFERENCE_GALLERY).stream()
			.filter(ref -> targetDiscriminator.equals(ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class)))
			.count();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(targetProduct.getPrimaryKey())
						),
						require(
							entityFetch(
								new ReferenceContent(
									"filteredGallery",
									ManagedReferencesBehaviour.ANY,
									new String[]{REFERENCE_GALLERY},
									new RequireConstraint[]{attributeContentAll()},
									new Constraint[]{
										filterBy(attributeEquals(ATTRIBUTE_GALLERY_DISCRIMINATOR, targetDiscriminator))
									}
								)
							)
						)
					)
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				assertInstanceOf(ServerEntityDecorator.class, product);
				final ServerEntityDecorator serverEntity = (ServerEntityDecorator) product;
				final DataChunk<ReferenceContract> filteredGallery = serverEntity.getReferencesForReferenceContentInstance(
					new ReferenceContentKey("filteredGallery", REFERENCE_GALLERY)
				).orElseThrow();

				assertEquals(expectedCount, filteredGallery.getTotalRecordCount());
				for (ReferenceContract ref : filteredGallery) {
					assertEquals(
						targetDiscriminator,
						ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class)
					);
				}
				return null;
			}
		);
	}

	@DisplayName("Non-managed references with ManagedReferencesBehaviour.ANY returns all references")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchNonManagedReferencesWithManagedBehaviourAny(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = findEntityByPredicate(
			originalProducts,
			it -> !it.getReferences(REFERENCE_GALLERY).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(targetProduct.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContentWithAttributes(ManagedReferencesBehaviour.ANY, REFERENCE_GALLERY)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				assertEquals(
					targetProduct.getReferences(REFERENCE_GALLERY).size(),
					product.getReferences(REFERENCE_GALLERY).size(),
					"ALL gallery references should be returned with ANY behaviour!"
				);
				return null;
			}
		);
	}

	@DisplayName("Non-managed references with ManagedReferencesBehaviour.EXISTING does not filter")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchNonManagedReferencesWithManagedBehaviourExisting(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = findEntityByPredicate(
			originalProducts,
			it -> !it.getReferences(REFERENCE_GALLERY).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(targetProduct.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContentWithAttributes(ManagedReferencesBehaviour.EXISTING, REFERENCE_GALLERY)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				// for non-managed entities, EXISTING cannot verify entity existence,
				// so all references should still be returned
				assertEquals(
					targetProduct.getReferences(REFERENCE_GALLERY).size(),
					product.getReferences(REFERENCE_GALLERY).size(),
					"EXISTING behaviour should not filter non-managed references since existence cannot be verified!"
				);
				return null;
			}
		);
	}

	@DisplayName("Non-managed references should be filtered by multiple attribute conditions")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterNonManagedReferencesByMultipleAttributes(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = findEntityByPredicate(
			originalProducts,
			it -> {
				final Collection<ReferenceContract> galleryRefs = it.getReferences(REFERENCE_GALLERY);
				return galleryRefs.size() >= 3 &&
					galleryRefs.stream()
						.map(ref -> ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class))
						.filter(Objects::nonNull)
						.distinct()
						.count() >= 2;
			}
		);

		final String targetDiscriminator = targetProduct.getReferences(REFERENCE_GALLERY).stream()
			.map(ref -> ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class))
			.filter(Objects::nonNull)
			.findFirst()
			.orElseThrow();

		// find a threshold that will filter some references within the chosen discriminator group
		final Long medianOrder = targetProduct.getReferences(REFERENCE_GALLERY).stream()
			.filter(ref -> targetDiscriminator.equals(ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class)))
			.map(ref -> ref.getAttribute(ATTRIBUTE_GALLERY_ORDER, Long.class))
			.filter(Objects::nonNull)
			.sorted()
			.findFirst()
			.orElseThrow();

		final long expectedCount = targetProduct.getReferences(REFERENCE_GALLERY).stream()
			.filter(ref -> targetDiscriminator.equals(ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class)))
			.filter(ref -> {
				final Long order = ref.getAttribute(ATTRIBUTE_GALLERY_ORDER, Long.class);
				return order != null && order >= medianOrder;
			})
			.count();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(targetProduct.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContentWithAttributes(
									REFERENCE_GALLERY,
									filterBy(
										and(
											attributeEquals(ATTRIBUTE_GALLERY_DISCRIMINATOR, targetDiscriminator),
											attributeGreaterThanEquals(ATTRIBUTE_GALLERY_ORDER, medianOrder)
										)
									),
									attributeContentAll()
								)
							)
						)
					)
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				final Collection<ReferenceContract> filteredRefs = product.getReferences(REFERENCE_GALLERY);
				assertEquals(expectedCount, filteredRefs.size());

				for (ReferenceContract ref : filteredRefs) {
					assertEquals(
						targetDiscriminator,
						ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class)
					);
					final Long order = ref.getAttribute(ATTRIBUTE_GALLERY_ORDER, Long.class);
					assertNotNull(order);
					assertTrue(order >= medianOrder);
				}
				return null;
			}
		);
	}

	@DisplayName("Both managed and non-managed references with attribute filters fetched simultaneously")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchManagedAndNonManagedReferencesWithAttributeFilters(
		Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = findEntityByPredicate(
			originalProducts,
			it -> {
				final Collection<ReferenceContract> galleryRefs = it.getReferences(REFERENCE_GALLERY);
				final Collection<ReferenceContract> categoryRefs = it.getReferences(Entities.CATEGORY);
				return galleryRefs.size() >= 2 && categoryRefs.size() >= 2 &&
					galleryRefs.stream()
						.map(ref -> ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class))
						.filter(Objects::nonNull)
						.distinct()
						.count() >= 2;
			}
		);

		final String targetDiscriminator = targetProduct.getReferences(REFERENCE_GALLERY).stream()
			.map(ref -> ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class))
			.filter(Objects::nonNull)
			.findFirst()
			.orElseThrow();

		final long expectedGalleryCount = targetProduct.getReferences(REFERENCE_GALLERY).stream()
			.filter(ref -> targetDiscriminator.equals(ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class)))
			.count();

		final long expectedCategoryCount = targetProduct.getReferences(Entities.CATEGORY).stream()
			.filter(ref -> Boolean.TRUE.equals(ref.getAttribute(ATTRIBUTE_CATEGORY_SHADOW, Boolean.class)))
			.count();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(targetProduct.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContentWithAttributes(
									REFERENCE_GALLERY,
									filterBy(
										attributeEquals(ATTRIBUTE_GALLERY_DISCRIMINATOR, targetDiscriminator)
									),
									attributeContentAll()
								),
								referenceContentWithAttributes(
									Entities.CATEGORY,
									filterBy(
										attributeEquals(ATTRIBUTE_CATEGORY_SHADOW, true)
									),
									attributeContentAll()
								)
							)
						)
					)
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);

				// verify gallery (non-managed) references are filtered
				final Collection<ReferenceContract> galleryRefs = product.getReferences(REFERENCE_GALLERY);
				assertEquals(expectedGalleryCount, galleryRefs.size());
				for (ReferenceContract ref : galleryRefs) {
					assertEquals(targetDiscriminator, ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR, String.class));
				}

				// verify category (managed) references are filtered
				final Collection<ReferenceContract> categoryRefs = product.getReferences(Entities.CATEGORY);
				assertEquals(expectedCategoryCount, categoryRefs.size());
				for (ReferenceContract ref : categoryRefs) {
					assertEquals(Boolean.TRUE, ref.getAttribute(ATTRIBUTE_CATEGORY_SHADOW, Boolean.class));
				}
				return null;
			}
		);
	}

	@DisplayName("Non-managed references should support pagination")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnPaginatedNonManagedReferences(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = originalProducts.stream()
			.max(Comparator.comparingInt(o -> o.getReferences(REFERENCE_GALLERY).size()))
			.orElseThrow();
		final int totalGalleryCount = targetProduct.getReferences(REFERENCE_GALLERY).size();
		assertTrue(
			totalGalleryCount >= 2, "Target product must have at least 2 gallery references for pagination test!");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<ReferenceKey> allReferencedKeys = CollectionUtils.createHashSet(totalGalleryCount);
				for (int pageNumber = 1; pageNumber <= Math.ceil(totalGalleryCount / 2.0f); pageNumber++) {
					final SealedEntity productByPk = session.queryOneSealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(targetProduct.getPrimaryKeyOrThrowException())
							),
							require(
								entityFetch(
									referenceContent(
										REFERENCE_GALLERY,
										page(pageNumber, 2)
									)
								)
							)
						)
					).orElseThrow();

					final Collection<ReferenceContract> foundRefs = productByPk.getReferences(REFERENCE_GALLERY);
					assertTrue(!foundRefs.isEmpty() && foundRefs.size() <= 2);

					final PaginatedList<ReferenceContract> paginatedList = new PaginatedList<>(
						pageNumber, 2, totalGalleryCount, new ArrayList<>(foundRefs)
					);
					assertEquals(paginatedList, productByPk.getReferenceChunk(REFERENCE_GALLERY));
					foundRefs.stream()
						.map(ReferenceContract::getReferenceKey)
						.forEach(allReferencedKeys::add);
				}
				assertEquals(
					totalGalleryCount, allReferencedKeys.size(),
					"All gallery references must be reachable via pagination!"
				);
				return null;
			}
		);
	}

	@DisplayName("Non-managed references should be lazy-loadable")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazyLoadNonManagedReferences(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity targetProduct = findEntityByPredicate(
			originalProducts,
			it -> !it.getReferences(REFERENCE_GALLERY).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(targetProduct.getPrimaryKey())
						),
						require(
							entityFetch()
						)
					)
				);

				assertEquals(1, result.getRecordData().size());
				final SealedEntity product = result.getRecordData().get(0);
				assertFalse(product.referencesAvailable());
				assertThrows(ContextMissingException.class, product::getReferences);

				// lazy load gallery references
				final SealedEntity enrichedProduct = session.enrichEntity(
					product, referenceContentWithAttributes(REFERENCE_GALLERY, attributeContentAll())
				);

				final Collection<ReferenceContract> galleryRefs = enrichedProduct.getReferences(REFERENCE_GALLERY);
				assertEquals(
					targetProduct.getReferences(REFERENCE_GALLERY).size(),
					galleryRefs.size(),
					"All gallery references should be lazy-loaded!"
				);
				for (ReferenceContract ref : galleryRefs) {
					assertTrue(ref.attributesAvailable(), "Attributes should be available on lazy-loaded references!");
					assertNotNull(ref.getAttribute(ATTRIBUTE_GALLERY_DISCRIMINATOR));
				}
				return null;
			}
		);
	}

}
