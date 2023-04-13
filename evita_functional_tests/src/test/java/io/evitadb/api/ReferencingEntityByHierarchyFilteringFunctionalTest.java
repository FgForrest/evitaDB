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

import com.github.javafaker.Faker;
import io.evitadb.api.EntityByHierarchyFilteringFunctionalTest.TestHierarchyPredicate;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents.ParentsByReference;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.DbInstanceParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ArrayUtils;
import lombok.extern.slf4j.Slf4j;
import one.edee.oss.pmptt.model.Hierarchy;
import one.edee.oss.pmptt.model.HierarchyItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static io.evitadb.utils.AssertionUtils.assertResultIs;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * This test verifies whether entities that reference other - hierarchical entity can be filtered by hierarchy constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita referenced entity filtering by hierarchy functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(DbInstanceParameterResolver.class)
@Slf4j
public class ReferencingEntityByHierarchyFilteringFunctionalTest extends AbstractHierarchyTest {
	private static final String THOUSAND_PRODUCTS = "ThousandProducts";
	private static final String ATTRIBUTE_MARKET_SHARE = "marketShare";
	private static final String ATTRIBUTE_FOUNDED = "founded";
	private static final String ATTRIBUTE_CAPACITY = "capacity";
	private static final String ATTRIBUTE_SHORTCUT = "shortcut";
	private static final int SEED = 40;
	private final DataGenerator dataGenerator = new DataGenerator();

	@Nonnull
	private static Map<Integer, Integer> getCardinalityIndex(Integer parentCategoryId, Hierarchy categoryHierarchy, List<SealedEntity> allProducts, List<SealedEntity> allCategories, Predicate<SealedEntity> filterPredicate, Predicate<SealedEntity> treePredicate) {
		final Map<Integer, SealedEntity> categoriesById = allCategories.stream()
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					Function.identity()
				)
			);

		final Map<Integer, Integer> categoryCardinalities = new HashMap<>();
		for (SealedEntity product : allProducts) {
			if (filterPredicate.test(product)) {
				final Collection<ReferenceContract> categoryReferences = product.getReferences(Entities.CATEGORY);
				for (ReferenceContract category : categoryReferences) {
					final boolean pathValid = categoryHierarchy.getParentItems(String.valueOf(category.getReferenceKey().primaryKey()))
						.stream()
						.map(HierarchyItem::getCode)
						.map(Integer::parseInt)
						.map(categoriesById::get)
						.allMatch(treePredicate) &&
						treePredicate.test(categoriesById.get(category.getReferenceKey().primaryKey()));
					if (pathValid) {
						final int categoryId = category.getReferenceKey().primaryKey();
						final List<Integer> categoryPath = Stream.concat(
							categoryHierarchy.getParentItems(String.valueOf(categoryId))
								.stream()
								.map(it -> Integer.parseInt(it.getCode())),
							Stream.of(categoryId)
						).toList();
						for (int i = categoryPath.size() - 1; i >= 0; i--) {
							int cid = categoryPath.get(i);
							categoryCardinalities.merge(cid, 1, Integer::sum);
							if (parentCategoryId != null && cid == parentCategoryId) {
								// we have encountered requested parent
								break;
							}
						}
					}
				}
			}
		}
		return categoryCardinalities;
	}

	@DataSet(value = THOUSAND_PRODUCTS, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};
			dataGenerator.generateEntities(
					dataGenerator.getSampleBrandSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(5)
				.forEach(session::upsertEntity);

			final List<EntityReference> storedCategories = dataGenerator.generateEntities(
					dataGenerator.getSampleCategorySchema(
						session,
						schemaBuilder -> {
							schemaBuilder.withAttribute(ATTRIBUTE_SHORTCUT, Boolean.class, whichIs -> whichIs.filterable());
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(100)
				.map(session::upsertEntity)
				.toList();

			dataGenerator.generateEntities(
					dataGenerator.getSamplePriceListSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(4)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleStoreSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(12)
				.forEach(session::upsertEntity);

			final List<EntityReference> storedProducts = dataGenerator.generateEntities(
					dataGenerator.getSampleProductSchema(
						session,
						schemaBuilder -> {
							schemaBuilder
								.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique().sortable())
								.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class, whichIs -> whichIs.filterable().sortable().indexDecimalPlaces(2))
								.withAttribute(ATTRIBUTE_PRIORITY, Long.class, whichIs -> whichIs.sortable().filterable())
								.withReferenceToEntity(
									Entities.BRAND,
									Entities.BRAND,
									Cardinality.ZERO_OR_ONE,
									whichIs -> whichIs.filterable()
										.withAttribute(ATTRIBUTE_MARKET_SHARE, BigDecimal.class, thatIs -> thatIs.filterable().sortable())
										.withAttribute(ATTRIBUTE_FOUNDED, OffsetDateTime.class, thatIs -> thatIs.filterable().sortable())
								)
								.withReferenceToEntity(
									Entities.STORE,
									Entities.STORE,
									Cardinality.ZERO_OR_MORE,
									whichIs -> whichIs.withAttribute(ATTRIBUTE_CAPACITY, Long.class, thatIs -> thatIs.filterable().sortable().nullable())
								);
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(1000)
				.map(session::upsertEntity)
				.toList();

			return new DataCarrier(
				"originalProductEntities",
				storedProducts.stream()
					.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), attributeContent(), referenceContent(), dataInLocales()).orElseThrow())
					.collect(Collectors.toList()),
				"originalCategoryEntities",
				storedCategories.stream()
					.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), attributeContent(), referenceContent(), dataInLocales()).orElseThrow())
					.collect(Collectors.toList()),
				"categoryHierarchy",
				dataGenerator.getHierarchy(Entities.CATEGORY)
			);
		});
	}

	@DisplayName("Should return all product linked to any category")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnAllProductsLinkedToAnyCategory(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithinRoot(Entities.CATEGORY)),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> !sealedEntity.getReferences(Entities.CATEGORY).isEmpty(),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return all products in categories except specified category subtrees")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnAllProductsInCategoriesExceptSpecifiedSubtrees(Evita evita, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy) {
		final Set<Integer> excluded = new HashSet<>(Arrays.asList(1, 7, 13, 16, 40, 55));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithinRoot(Entities.CATEGORY, excluding(entityPrimaryKeyInSet(excluded.toArray(new Integer[0]))))),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity
						.getReferences(Entities.CATEGORY)
						.stream()
						.anyMatch(category -> {
							// is not directly excluded node
							return !excluded.contains(category.getReferenceKey().primaryKey()) &&
								// has no parent node that is in excluded set
								categoryHierarchy.getParentItems(String.valueOf(category.getReferenceKey().primaryKey()))
									.stream()
									.map(it -> Integer.parseInt(it.getCode()))
									.noneMatch(excluded::contains);
						}),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products in category")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnProductsInCategory(Evita evita, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithin(Entities.CATEGORY, 7, directRelation())),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity
						.getReferences(Entities.CATEGORY)
						.stream()
						.anyMatch(category -> {
							// is directly referencing category 7
							return Objects.equals(category.getReferenceKey().primaryKey(), 7);
						}),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products in selected category subtree")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnProductsInCategorySubtree(Evita evita, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithin(Entities.CATEGORY, 7)),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity
						.getReferences(Entities.CATEGORY)
						.stream()
						.anyMatch(category -> {
							final String categoryId = String.valueOf(category.getReferenceKey().primaryKey());
							// is either category 7
							return Objects.equals(categoryId, String.valueOf(7)) ||
								// or has parent category 7
								categoryHierarchy.getParentItems(categoryId)
									.stream()
									.anyMatch(it -> Objects.equals(it.getCode(), String.valueOf(7)));
						}),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products in selected category subtree except directly related products")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnProductsInCategorySubtreeExceptDirectlyRelated(Evita evita, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithin(Entities.CATEGORY, 7, excludingRoot())),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity
						.getReferences(Entities.CATEGORY)
						.stream()
						.anyMatch(category -> {
							final String categoryId = String.valueOf(category.getReferenceKey().primaryKey());
							// is not exactly category 7
							return !Objects.equals(categoryId, String.valueOf(7)) &&
								// but has parent category 7
								categoryHierarchy.getParentItems(categoryId)
									.stream()
									.anyMatch(it -> Objects.equals(it.getCode(), String.valueOf(7)));
						}),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products in category subtree except specified subtrees")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnProductsInCategorySubtreeExceptSpecifiedSubtrees(Evita evita, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy) {
		final Set<Integer> excluded = new HashSet<>(Arrays.asList(2, 43, 34, 53));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithin(Entities.CATEGORY, 1, excluding(entityPrimaryKeyInSet(excluded.toArray(new Integer[0]))))),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity
						.getReferences(Entities.CATEGORY)
						.stream()
						.anyMatch(category -> {
							final int categoryId = category.getReferenceKey().primaryKey();
							final String categoryIdAsString = String.valueOf(categoryId);
							final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(categoryIdAsString);
							return
								// is not directly excluded node
								!excluded.contains(categoryId) &&
									// has no excluded parent node
									parentItems
										.stream()
										.map(it -> Integer.parseInt(it.getCode()))
										.noneMatch(excluded::contains) &&
									// has parent node 1
									(
										Objects.equals(1, categoryId) ||
											parentItems
												.stream()
												.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()))
									);
						}),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products in category subtree except specified subtrees and matching attribute filter")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnProductsInCategorySubtreeExceptSpecifiedSubtreesAndMatchingCertainConstraint(Evita evita, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy) {
		final Set<Integer> excluded = new HashSet<>(Arrays.asList(2, 34));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								or(
									attributeLessThan(ATTRIBUTE_PRIORITY, 25000),
									attributeStartsWith(ATTRIBUTE_CODE, "E")
								),
								referenceHaving(
									Entities.BRAND,
									attributeGreaterThan(ATTRIBUTE_MARKET_SHARE, 50)
								),
								referenceHaving(
									Entities.STORE,
									attributeGreaterThan(ATTRIBUTE_CAPACITY, 50000)
								),
								hierarchyWithin(Entities.CATEGORY, 1, excluding(entityPrimaryKeyInSet(excluded.toArray(new Integer[0]))))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> {
						return
							// direct attribute condition matches
							(
								ofNullable(sealedEntity.getAttribute(ATTRIBUTE_CODE)).map(it -> ((String) it).startsWith("E")).orElse(false) ||
									ofNullable(sealedEntity.getAttribute(ATTRIBUTE_PRIORITY)).map(it -> ((Long) it) < 25000).orElse(false)
							) &&
								// query on referenced entity BRAND attribute matches
								(
									sealedEntity.getReferences(Entities.BRAND)
										.stream()
										.anyMatch(brand -> {
											// brand market share is bigger than
											return ofNullable(brand.getAttribute(ATTRIBUTE_MARKET_SHARE))
												.map(it -> ((BigDecimal) it).compareTo(new BigDecimal("50")) > 0)
												.orElse(false);
										})
								) &&
								// query on referenced entity STORE attribute matches
								(
									sealedEntity.getReferences(Entities.STORE)
										.stream()
										.anyMatch(brand -> {
											// brand market share is bigger than
											return ofNullable(brand.getAttribute(ATTRIBUTE_CAPACITY))
												.map(it -> ((Long) it) > 50000L)
												.orElse(false);
										})
								) &&
								// category hierarchy query is fulfilled
								sealedEntity
									.getReferences(Entities.CATEGORY)
									.stream()
									.anyMatch(category -> {
										final int categoryId = category.getReferenceKey().primaryKey();
										final String categoryIdAsString = String.valueOf(categoryId);
										final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(categoryIdAsString);
										// is not directly excluded node
										return !excluded.contains(categoryId) &&
											// has no excluded parent node
											parentItems
												.stream()
												.map(it -> Integer.parseInt(it.getCode()))
												.noneMatch(excluded::contains) &&
											// has parent node 1
											(
												Objects.equals(1, categoryId) ||
													parentItems
														.stream()
														.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()))
											);
									});
					},
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return category parents for returned products when only primary keys are returned")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCategoryParentsForReturnedProductsWhenOnlyPrimaryKeysAreReturned(Evita evita, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithin(Entities.CATEGORY, 94)),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							hierarchyParentsOfReference(Entities.CATEGORY)
						)
					),
					EntityReference.class
				);

				assertFalse(result.getRecordData().isEmpty());

				final HierarchyParents hierarchyParents = result.getExtraResult(HierarchyParents.class);
				assertNotNull(hierarchyParents, "No parents DTO was returned!");
				final ParentsByReference categoryParents = hierarchyParents.ofType(Entities.CATEGORY);

				final Map<Integer, SealedEntity> originalProductIndex = originalProductEntities.stream()
					.collect(
						Collectors.toMap(
							EntityContract::getPrimaryKey,
							Function.identity()
						)
					);

				for (EntityReference entity : result.getRecordData()) {
					final SealedEntity originalEntity = originalProductIndex.get(entity.getPrimaryKey());
					final Collection<ReferenceContract> references = originalEntity.getReferences(Entities.CATEGORY);
					assertFalse(references.isEmpty());
					references.forEach(relatedCategory -> {
						final int referencedCategoryId = relatedCategory.getReferenceKey().primaryKey();
						final Integer[] relatedParentIds = Arrays.stream(categoryParents.getParentsFor(entity.getPrimaryKey(), referencedCategoryId))
							.map(it -> it.getPrimaryKey())
							.toArray(Integer[]::new);
						assertArrayEquals(
							getParentIds(categoryHierarchy, referencedCategoryId),
							relatedParentIds
						);
					});
				}

				return null;
			}
		);
	}

	@DisplayName("Should return category parents for returned products")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCategoryParentsForReturnedProducts(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithin(Entities.CATEGORY, 94)),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								referenceContent(Entities.CATEGORY)
							),
							hierarchyParentsOfReference(Entities.CATEGORY)
						)
					),
					SealedEntity.class
				);

				assertFalse(result.getRecordData().isEmpty());

				final HierarchyParents hierarchyParents = result.getExtraResult(HierarchyParents.class);
				assertNotNull(hierarchyParents, "No parents DTO was returned!");
				final ParentsByReference categoryParents = hierarchyParents.ofType(Entities.CATEGORY);

				for (SealedEntity entity : result.getRecordData()) {
					final Collection<ReferenceContract> references = entity.getReferences(Entities.CATEGORY);
					assertFalse(references.isEmpty());
					references.forEach(relatedCategory -> {
						final int referencedCategoryId = relatedCategory.getReferenceKey().primaryKey();
						final Integer[] relatedParentIds = Arrays.stream(categoryParents.getParentsFor(entity.getPrimaryKey(), referencedCategoryId))
							.map(it -> it.getPrimaryKey())
							.toArray(Integer[]::new);
						final Integer[] parentIds = getParentIds(categoryHierarchy, referencedCategoryId);
						assertArrayEquals(parentIds, relatedParentIds);
					});
				}

				return null;
			}
		);
	}

	@DisplayName("Should return category parent bodies for returned products")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCategoryParentBodiesForReturnedProducts(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithin(Entities.CATEGORY, 94)),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								referenceContent(Entities.CATEGORY)
							),
							hierarchyParentsOfReference(Entities.CATEGORY, entityFetch())
						)
					),
					SealedEntity.class
				);

				assertFalse(result.getRecordData().isEmpty());

				final HierarchyParents hierarchyParents = result.getExtraResult(HierarchyParents.class);
				assertNotNull(hierarchyParents, "No parents DTO was returned!");
				final ParentsByReference categoryParents = hierarchyParents.ofType(Entities.CATEGORY);

				// all results should start with same parents when we query by hierarchy
				for (SealedEntity entity : result.getRecordData()) {
					final Collection<ReferenceContract> references = entity.getReferences(Entities.CATEGORY);
					assertFalse(references.isEmpty());
					references.forEach(relatedCategory -> {
						final int referencedCategoryId = relatedCategory.getReferenceKey().primaryKey();
						final EntityClassifier[] relatedParents = categoryParents.getParentsFor(entity.getPrimaryKey(), referencedCategoryId);
						final Integer[] relatedParentIds = Arrays.stream(relatedParents).map(EntityClassifier::getPrimaryKey).toArray(Integer[]::new);
						assertArrayEquals(
							getParentIds(categoryHierarchy, referencedCategoryId),
							relatedParentIds
						);
					});
				}

				return null;
			}
		);
	}

	@DisplayName("Should return cardinalities for products")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForProducts(Evita evita, List<SealedEntity> originalProductEntities, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								attributeEquals(ATTRIBUTE_ALIAS, true),
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								fromRoot(
									"megaMenu",
									entityFetch(attributeContent()),
									statistics()
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					entity -> entity.getLocales().contains(CZECH_LOCALE) &&
						ofNullable((Boolean) entity.getAttribute(ATTRIBUTE_ALIAS)).orElse(false),
					languagePredicate,
					languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(session, null, categoryHierarchy, categoryCardinalities, false, true)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return cardinalities for products in subtree")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForProductsWhenSubTreeIsRequested(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								attributeEquals(ATTRIBUTE_ALIAS, true),
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY, 2)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								fromRoot(
									"megaMenu",
									entityFetch(attributeContent()),
									statistics()
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					entity -> entity.getLocales().contains(CZECH_LOCALE) &&
						ofNullable((Boolean) entity.getAttribute(ATTRIBUTE_ALIAS)).orElse(false),
					languagePredicate,
					languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(session, null, categoryHierarchy, categoryCardinalities, false, true)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for sibling categories with all statistics within requested category 2")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForCategorySiblingsWhenSubTreeIsRequested(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY, 6)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								siblings(
									"megaMenu",
									entityFetch(attributeContent()),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate categoryPredicate = (sealedEntity, parentItems) -> {
					// has parent node 1
					return parentItems.size() == 1 && "1".equals(parentItems.get(0).getCode());
				};
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate,
					(category, parentItems) -> categoryPredicate.test(category, parentItems) && languagePredicate.test(category, parentItems),
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities, true, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics within category 2")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForCategoriesOfCertainCategory(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY,6)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								fromNode(
									"megaMenu",
									node(filterBy(entityPrimaryKeyInSet(2))),
									entityFetch(attributeContent()),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate categoryPredicate = (sealedEntity, parentItems) -> {
					final Integer categoryId = sealedEntity.getPrimaryKey();
					// has parent node 2
					return (
						Objects.equals(2, categoryId) ||
							parentItems
								.stream()
								.anyMatch(it -> Objects.equals(String.valueOf(2), it.getCode()))
					);
				};
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate,
					(category, parentItems) -> categoryPredicate.test(category, parentItems) && languagePredicate.test(category, parentItems),
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 2, categoryHierarchy,
							categoryCardinalities, false, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for root categories with all statistics in distance 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForRootCategoriesInDistanceOne(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY,2)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								fromRoot(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(distance(1)),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					return languagePredicate.test(sealedEntity, parentItems) && (level <= 1);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate,
					treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for requested categories with all statistics in distance 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForRequestedCategoriesInDistanceOne(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY,1)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								children(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(distance(1)),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					// has parent node 1
					final boolean hasParentNode = parentItems
						.stream()
						.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) &&
						(sealedEntity.getPrimaryKey() == 1 || hasParentNode) &&
						(level <= 2);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for requested category siblings with all statistics in distance 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForRequestedCategorySiblingsInDistanceOne(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY,6)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								siblings(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(distance(1)),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					// has parent node 1
					final boolean hasParentNode = parentItems
						.stream()
						.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) &&
						(sealedEntity.getPrimaryKey() == 1 || hasParentNode) &&
						(level <= 2);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities, true, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for specified category with all statistics in distance 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForSpecifiedCategoryInDistanceOne(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY,6)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								fromNode(
									"megaMenu",
									node(filterBy(entityPrimaryKeyInSet(1))),
									entityFetch(attributeContent()),
									stopAt(distance(1)),
									statistics()
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					// has parent node 1
					final boolean hasParentNode = parentItems
						.stream()
						.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) &&
						(sealedEntity.getPrimaryKey() == 1 || hasParentNode) && (level <= 2);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return root children for categories with all statistics in distance 1 within when no filtering constraint is specified")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForWithinCategoryInDistanceOne(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								children(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(distance(1)),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					return languagePredicate.test(sealedEntity, parentItems) && (level <= 1);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, true, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for root categories with all statistics until shortcut category is reached")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForRootCategoriesAndStopAtShortCuts(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								fromRoot(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(node(filterBy(attributeEqualsFalse(ATTRIBUTE_SHORTCUT)))),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> languagePredicate.test(sealedEntity, parentItems) && !sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics until shortcut category is reached within category 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedAndStopAtShortCuts(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								fromNode(
									"megaMenu",
									node(filterBy(entityPrimaryKeyInSet(1))),
									entityFetch(attributeContent()),
									stopAt(node(filterBy(attributeEqualsFalse(ATTRIBUTE_SHORTCUT)))),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1 &&
						!sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics until shortcut category is reached within requested category 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForRequestedCategoryAndStopAtShortCuts(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY,1)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								children(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(node(filterBy(attributeEqualsFalse(ATTRIBUTE_SHORTCUT)))),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1 &&
						!sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class) &&
						level <= 2;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return siblings for categories with all statistics until shortcut category is reached within requested category 6")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnSiblingsCardinalitiesForRequestedCategoryAndStopAtShortCuts(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY, 6)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								siblings(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(node(filterBy(attributeEqualsFalse(ATTRIBUTE_SHORTCUT)))),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1 &&
						!sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class) &&
						level <= 2;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities, true, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics except shortcut categories")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForCategoriesExceptShortCuts(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinRoot(
									Entities.CATEGORY,
									excluding(
										entityHaving(
											attributeEqualsTrue(ATTRIBUTE_SHORTCUT)
										)
									)
								)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								fromRoot(
									"megaMenu",
									entityFetch(attributeContent()),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate =
					(entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE) &&
						!entity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics except shortcut categories within category 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedExceptShortCuts(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(
									Entities.CATEGORY,1,
									excluding(
										entityHaving(
											attributeEqualsTrue(ATTRIBUTE_SHORTCUT)
										)
									)
								)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							/* TODO JNO - Uncomment */
							/*debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),*/
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								children(
									"megaMenu",
									entityFetch(attributeContent()),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate =
					(sealedEntity, parentItems) -> sealedEntity.getLocales().contains(CZECH_LOCALE) &&
						!sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children categories with all statistics except shortcut categories for siblings of category 6")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnSiblingCardinalitiesForCategoriesWhenSubTreeIsRequestedExceptShortCuts(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(
									Entities.CATEGORY,6,
									excluding(
										entityHaving(
											attributeEqualsTrue(ATTRIBUTE_SHORTCUT)
										)
									)
								)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								siblings(
									"megaMenu",
									entityFetch(attributeContent()),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate =
					(sealedEntity, parentItems) -> sealedEntity.getLocales().contains(CZECH_LOCALE) &&
						!sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities, true, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics except shortcut categories for category 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForCategoriesForRequestedSubTreeExceptShortCuts(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(
									Entities.CATEGORY,6,
									excluding(
										entityHaving(
											attributeEqualsTrue(ATTRIBUTE_SHORTCUT)
										)
									)
								)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								fromNode(
									"megaMenu",
									node(filterBy(entityPrimaryKeyInSet(1))),
									entityFetch(attributeContent()),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate =
					(sealedEntity, parentItems) -> sealedEntity.getLocales().contains(CZECH_LOCALE) &&
						!sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics until level 2")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForCategoriesUntilLevelTwo(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								fromRoot(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(level(2)),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					return languagePredicate.test(sealedEntity, parentItems) && level <= 2;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics in category 1 until level two")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedUntilLevelTwo(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY,1)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								children(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(level(2)),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1 && level <= 2;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics in category 1 until level two")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForCategoriesForSelectedSubTreeUntilLevelTwo(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY,6)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								fromNode(
									"megaMenu",
									node(filterBy(entityPrimaryKeyInSet(1))),
									entityFetch(attributeContent()),
									stopAt(level(2)),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1 && level <= 2;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return sorted children for categories with all statistics until level two")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnSortedCardinalitiesUntilLevelTwo(
		Evita evita,
		List<SealedEntity> originalProductEntities,
		List<SealedEntity> originalCategoryEntities,
		Hierarchy categoryHierarchy
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinRoot(Entities.CATEGORY)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								orderBy(
									attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)
								),
								fromRoot(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(level(2)),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					return languagePredicate.test(sealedEntity, parentItems) && level <= 2;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryEntities,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false, true,
							Comparator.comparing(o -> o.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class))
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@Nonnull
	private Integer[] getParentIds(Hierarchy categoryHierarchy, int categoryId) {
		return Stream.concat(
				categoryHierarchy
					.getParentItems(String.valueOf(categoryId))
					.stream()
					.map(it -> Integer.parseInt(it.getCode())),
				Stream.of(categoryId)
			)
			.toArray(Integer[]::new);
	}

	@Nonnull
	private CardinalityProvider computeCardinalities(
		Hierarchy categoryHierarchy,
		List<SealedEntity> allProducts,
		List<SealedEntity> allCategories,
		Predicate<SealedEntity> filterPredicate,
		TestHierarchyPredicate treeFilterPredicate,
		TestHierarchyPredicate scopePredicate
	) {
		final Map<Integer, SealedEntity> categoryIndex = allCategories
			.stream()
			.collect(Collectors.toMap(EntityContract::getPrimaryKey, Function.identity()));

		final Set<Integer> categoriesWithValidPath = new HashSet<>();
		for (SealedEntity category : allCategories) {
			final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(String.valueOf(category.getPrimaryKey()));
			if (scopePredicate.test(category, parentItems)) {
				categoriesWithValidPath.add(category.getPrimaryKey());
			}
		}
		final Collection<SealedEntity> filteredProducts = allProducts.stream()
			.filter(filterPredicate)
			.toList();
		final Cardinalities categoryCardinalities = new Cardinalities();

		final Set<List<Integer>> emptyCategories = new HashSet<>();
		for (SealedEntity category : allCategories) {
			final int categoryId = category.getPrimaryKey();
			final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(String.valueOf(categoryId));
			final List<Integer> categoryPath = Stream.concat(
				parentItems
					.stream()
					.map(it -> Integer.parseInt(it.getCode())),
				Stream.of(categoryId)
			).toList();

			if (categoryPath.stream().allMatch(cid -> treeFilterPredicate.test(categoryIndex.get(cid), parentItems))) {
				int cid = categoryPath.get(categoryPath.size() - 1);
				final int[] productIds = filteredProducts
					.stream()
					.filter(it -> it.getReference(Entities.CATEGORY, cid).isPresent())
					.mapToInt(EntityContract::getPrimaryKey)
					.toArray();
				if (ArrayUtils.isEmpty(productIds)) {
					emptyCategories.add(categoryPath);
				} else {
					categoryCardinalities.record(categoryPath, productIds, categoriesWithValidPath::contains);
					for (int i = categoryPath.size() - 1; i > 0; i--) {
						final List<Integer> parentPath = categoryPath.subList(0, i);
						if (emptyCategories.contains(parentPath)) {
							if (parentPath.size() > 1) {
								categoryCardinalities.recordCategoryVisible(parentPath.get(parentPath.size() - 2));
							}
							emptyCategories.remove(parentPath);
						} else {
							break;
						}
					}
				}
			}
		}
		return categoryCardinalities;
	}

	@Nonnull
	private HierarchyStatistics computeExpectedStatistics(
		Hierarchy categoryHierarchy,
		List<SealedEntity> allProducts,
		List<SealedEntity> allCategories,
		Predicate<SealedEntity> filterPredicate,
		TestHierarchyPredicate treeFilterPredicate,
		TestHierarchyPredicate scopePredicate,
		Function<CardinalityProvider, HierarchyStatisticsTuple> statisticsComputer
	) {
		final CardinalityProvider categoryCardinalities = computeCardinalities(
			categoryHierarchy, allProducts, allCategories, filterPredicate, treeFilterPredicate, scopePredicate
		);
		final HierarchyStatisticsTuple result = statisticsComputer.apply(categoryCardinalities);
		final Map<String, List<LevelInfo>> theResults = Map.of(
			result.name(), result.levelInfos()
		);
		return new HierarchyStatistics(
			Collections.emptyMap(),
			Collections.singletonMap(Entities.CATEGORY, theResults)
		);
	}

	private static class Cardinalities implements CardinalityProvider {
		private final Map<Integer, Bitmap> itemCardinality = new HashMap<>(32);
		private final Map<Integer, Integer> childrenItemCount = new HashMap<>(32);

		public void recordCategoryVisible(int categoryId) {
			childrenItemCount.merge(categoryId, 1, Integer::sum);
		}

		public void record(@Nonnull List<Integer> categoryPath, int[] productIds, @Nonnull IntPredicate categoryValid) {
			if (categoryPath.size() > 1) {
				final Integer cid = categoryPath.get(categoryPath.size() - 2);
				if (categoryValid.test(cid)) {
					recordCategoryVisible(cid);
				}
			}
			categoryPath
				.stream()
				.filter(categoryValid::test)
				.forEach(it -> itemCardinality.merge(
					it, new BaseBitmap(productIds),
					(pIds, pIds2) -> new BaseBitmap(
						RoaringBitmap.or(
							RoaringBitmapBackedBitmap.getRoaringBitmap(pIds),
							RoaringBitmapBackedBitmap.getRoaringBitmap(pIds2)
						)
					)
				));
		}

		public boolean isValid(int categoryId) {
			return itemCardinality.containsKey(categoryId);
		}

		@Override
		public int getCardinality(int categoryId) {
			return itemCardinality.get(categoryId).size();
		}

		@Override
		public int getChildrenCount(int categoryId) {
			return ofNullable(childrenItemCount.get(categoryId)).orElse(0);
		}
	}

}
