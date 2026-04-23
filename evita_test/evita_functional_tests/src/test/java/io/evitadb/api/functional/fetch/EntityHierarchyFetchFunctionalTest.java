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

import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReferenceWithParent;
import io.evitadb.core.Evita;
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

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies hierarchy fetching functionality including parent entity references,
 * hierarchy content with level/distance/node stopping conditions, and product hierarchy
 * parent entity retrieval.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity hierarchy fetch functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class EntityHierarchyFetchFunctionalTest extends AbstractEntityFetchingFunctionalTest {

	@DisplayName("Should return hierarchy parent id")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentId(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getAllChildItems(
						categoryHierarchy.getRootItems().get(0).getCode())
					.stream()
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(
								hierarchyContent()
							)
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				assertEquals(
					theParentPk, productByPk.getRecordData()
						.get(0)
						.getParentEntity()
						.orElseThrow()
						.getPrimaryKey()
				);
				final EntityClassifierWithParent parentEntity = productByPk.getRecordData()
					.get(0)
					.getParentEntity()
					.orElseThrow();
				assertInstanceOf(EntityReferenceWithParent.class, parentEntity);
				assertEquals(theParentPk, ((EntityReferenceWithParent) parentEntity).getPrimaryKey());
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent entity reference")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParents(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(hierarchyContent())
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(
					theParentPk, categoryByPk.getRecordData()
						.get(0)
						.getParentEntity()
						.orElseThrow()
						.getPrimaryKey()
				);
				assertEquals(
					createParentChain(categoryHierarchy, theChildPk, null, null), categoryByPk.getRecordData()
						.get(0)
						.getParentEntity()
						.orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent entity references stopping at level two")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentsUpToLevelTwo(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(hierarchyContent(stopAt(level(2))))
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(
					theParentPk, categoryByPk.getRecordData()
						.get(0)
						.getParentEntity()
						.orElseThrow()
						.getPrimaryKey()
				);
				assertEquals(
					createParentChain(categoryHierarchy, theChildPk, 2, null), categoryByPk.getRecordData()
						.get(0)
						.getParentEntity()
						.orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent entity references with bodies stopping at level two")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentsWithBodiesUpToLevelTwo(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(
								hierarchyContent(
									stopAt(level(2)),
									entityFetch(
										attributeContentAll(),
										referenceContent(Entities.PRICE_LIST, entityFetchAll())
									)
								)
							)
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				final SealedEntity returnedEntity = categoryByPk.getRecordData().get(0);
				assertEquals(theParentPk, returnedEntity.getParentEntity().orElseThrow().getPrimaryKey());

				boolean atLeastOnPriceListFound = false;
				Optional<EntityClassifierWithParent> parentEntityRef = returnedEntity.getParentEntity();
				boolean atLeastOnePriceListBodyFound = false;
				while (parentEntityRef.isPresent()) {
					final EntityClassifierWithParent parentEntity = parentEntityRef.get();
					assertInstanceOf(SealedEntity.class, parentEntity);
					final Collection<ReferenceContract> references = ((SealedEntity) parentEntity).getReferences(
						Entities.PRICE_LIST);
					if (!references.isEmpty()) {
						atLeastOnPriceListFound = true;
						assertEquals(1, references.size());
						atLeastOnePriceListBodyFound = atLeastOnePriceListBodyFound || references.stream().anyMatch(
							it -> it.getReferencedEntity().isPresent());
						parentEntityRef = parentEntity.getParentEntity();
					}
				}
				assertTrue(atLeastOnPriceListFound, "At least one price list should be found in the hierarchy");
				assertTrue(
					atLeastOnePriceListBodyFound, "At least one price list body should be found in the hierarchy");

				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent entity references stopping at distance one")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentsWithinDistanceOne(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(hierarchyContent(stopAt(distance(1))))
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(
					theParentPk, categoryByPk.getRecordData()
						.get(0)
						.getParentEntity()
						.orElseThrow()
						.getPrimaryKey()
				);
				assertEquals(
					createParentChain(categoryHierarchy, theChildPk, null, 1), categoryByPk.getRecordData()
						.get(0)
						.getParentEntity()
						.orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent entity references stopping at node defined by attribute filter")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentsUntilNodeSpecifiedByAttributeFilter(
		Evita evita, Map<Integer, SealedEntity> originalCategories, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());
				final SealedEntity parentCategory = originalCategories.get(theParentPk);

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(
								hierarchyContent(
									stopAt(
										node(
											filterBy(
												attributeEquals(
													ATTRIBUTE_CODE,
													parentCategory.getAttribute(ATTRIBUTE_CODE, String.class)
												)
											)
										)
									)
								)
							)
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(
					theParentPk, categoryByPk.getRecordData()
						.get(0)
						.getParentEntity()
						.orElseThrow()
						.getPrimaryKey()
				);
				assertEquals(
					createParentChain(categoryHierarchy, theChildPk, null, 1), categoryByPk.getRecordData()
						.get(0)
						.getParentEntity()
						.orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should limit the scope of parent visibility")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLimitTheScopeOfParentVisibility(
		Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());
				assertTrue(theChild.getLevel() > 2);

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(
								hierarchyContent(
									stopAt(distance(1))
								)
							)
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				final SealedEntity category = categoryByPk.getRecordData().get(0);
				assertTrue(category.getParentEntity().isPresent());
				assertFalse(category.getParentEntity().get().getParentEntity().isPresent());
				return null;
			}
		);
	}

	@DisplayName("Should limit the scope of rich parent visibility")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLimitTheScopeOfRichParentVisibility(
		Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				assertTrue(theChild.getLevel() > 2);

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(
								hierarchyContent(
									stopAt(distance(1)),
									entityFetchAll()
								)
							)
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				final SealedEntity category = categoryByPk.getRecordData().get(0);
				assertTrue(category.getParentEntity().isPresent());
				assertFalse(category.getParentEntity().get().getParentEntity().isPresent());
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent sealed entities")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentEntities(
		Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(hierarchyContent(entityFetchAll()))
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(
					theParentPk, categoryByPk.getRecordData()
						.get(0)
						.getParentEntity()
						.orElseThrow()
						.getPrimaryKey()
				);
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, null, null),
					categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent sealed entities stopping at level two")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentEntitiesUpToLevelTwo(
		Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(hierarchyContent(stopAt(level(2)), entityFetchAll()))
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(
					theParentPk, categoryByPk.getRecordData()
						.get(0)
						.getParentEntity()
						.orElseThrow()
						.getPrimaryKey()
				);
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, 2, null),
					categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent sealed entities stopping at distance one")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentEntitiesWithinDistanceOne(
		Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(hierarchyContent(stopAt(distance(1)), entityFetchAll()))
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(
					theParentPk, categoryByPk.getRecordData()
						.get(0)
						.getParentEntity()
						.orElseThrow()
						.getPrimaryKey()
				);
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, null, 1),
					categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent sealed entities stopping at node defined by attribute filter")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentEntitiesUntilNodeSpecifiedByAttributeFilter(
		Evita evita, Map<Integer, SealedEntity> originalCategories, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());
				final SealedEntity parentCategory = originalCategories.get(theParentPk);

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(
								hierarchyContent(
									stopAt(
										node(
											filterBy(
												attributeEquals(
													ATTRIBUTE_CODE,
													parentCategory.getAttribute(ATTRIBUTE_CODE, String.class)
												)
											)
										)
									),
									entityFetchAll()
								)
							)
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(
					theParentPk, categoryByPk.getRecordData()
						.get(0)
						.getParentEntity()
						.orElseThrow()
						.getPrimaryKey()
				);
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, null, 1),
					categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent entity reference")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParents(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(hierarchyContent())
								)
							),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(
					Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();
				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentChain(categoryHierarchy, theChildPk, null, null),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent entity references stopping at level two")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentsUpToLevelTwo(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(hierarchyContent(stopAt(level(2))))
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(
					Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();
				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentChain(categoryHierarchy, theChildPk, 2, null),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent entity references stopping at distance one")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentsWithinDistanceOne(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(hierarchyContent(stopAt(distance(1))))
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(
					Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();
				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentChain(categoryHierarchy, theChildPk, null, 1),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent entity references stopping at node defined by attribute filter")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentsUntilNodeSpecifiedByAttributeFilter(
		Evita evita, Map<Integer, SealedEntity> originalCategories, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());
				final SealedEntity parentCategory = originalCategories.get(theParentPk);

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(
										hierarchyContent(
											stopAt(
												node(
													filterBy(
														attributeEquals(
															ATTRIBUTE_CODE,
															parentCategory.getAttribute(ATTRIBUTE_CODE, String.class)
														)
													)
												)
											)
										)
									)
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(
					Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();
				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentChain(categoryHierarchy, theChildPk, theChild.getLevel() - 1, null),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent sealed entities")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentEntities(
		Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(Entities.CATEGORY, entityFetch(hierarchyContent(entityFetchAll()))))
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(
					Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();

				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, null, null),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent sealed entities stopping at level two")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentEntitiesUpToLevelTwo(
		Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(hierarchyContent(stopAt(level(2)), entityFetchAll()))
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(
					Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();

				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, 2, null),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent sealed entities with bodies stopping at level two")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentEntitiesWithBodiesUpToLevelTwo(
		Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(
										hierarchyContent(
											stopAt(level(2)),
											entityFetch(
												attributeContentAll(),
												referenceContent(Entities.PRICE_LIST, entityFetchAll())
											)
										)
									)
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(
					Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();
				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());

				boolean atLeastOnePriceListFound = false;
				boolean atLeastOnePriceListBodyFound = false;
				for (SealedEntity returnedEntity : products.getRecordData()) {
					final Collection<ReferenceContract> referencedCategories = returnedEntity.getReferences(
						Entities.CATEGORY);
					for (ReferenceContract category : referencedCategories) {
						final Optional<SealedEntity> referencedEntity = category.getReferencedEntity();
						if (referencedEntity.isPresent()) {
							final int[] parents = categoryHierarchy.getParentItems(
									String.valueOf(referencedEntity.get().getPrimaryKey()))
								.stream()
								.map(HierarchyItem::getCode)
								.mapToInt(Integer::parseInt)
								.toArray();
							Optional<EntityClassifierWithParent> parentEntityRef = referencedEntity.get()
								.getParentEntity();
							int level = parents.length;
							while (parentEntityRef.isPresent()) {
								final EntityClassifierWithParent parentEntity = parentEntityRef.get();
								if (level >= 2) {
									assertInstanceOf(SealedEntity.class, parentEntity);
									final Collection<ReferenceContract> references = ((SealedEntity) parentEntity).getReferences(
										Entities.PRICE_LIST);
									if (!references.isEmpty()) {
										atLeastOnePriceListFound = true;
										assertEquals(1, references.size());
										atLeastOnePriceListBodyFound = atLeastOnePriceListBodyFound || references.stream()
											.anyMatch(it -> it.getReferencedEntity().isPresent());
									}
								} else {
									assertInstanceOf(EntityReferenceWithParent.class, parentEntity);
								}
								parentEntityRef = parentEntity.getParentEntity();
								level--;
							}
						}

					}
				}

				assertTrue(atLeastOnePriceListFound, "At least one price list should be found in the hierarchy");
				assertTrue(
					atLeastOnePriceListBodyFound, "At least one price list body should be found in the hierarchy");
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent sealed entities stopping at distance one")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentEntitiesWithinDistanceOne(
		Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(hierarchyContent(stopAt(distance(1)), entityFetchAll()))
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(
					Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();

				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, null, 1),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent sealed entities stopping at node defined by attribute filter")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentEntitiesUntilNodeSpecifiedByAttributeFilter(
		Evita evita, Map<Integer, SealedEntity> originalCategories, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());
				final SealedEntity parentCategory = originalCategories.get(theParentPk);

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(
										hierarchyContent(
											stopAt(
												node(
													filterBy(
														attributeEquals(
															ATTRIBUTE_CODE,
															parentCategory.getAttribute(ATTRIBUTE_CODE, String.class)
														)
													)
												)
											),
											entityFetchAll()
										)
									)
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(
					Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();

				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, null, 1),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

}
