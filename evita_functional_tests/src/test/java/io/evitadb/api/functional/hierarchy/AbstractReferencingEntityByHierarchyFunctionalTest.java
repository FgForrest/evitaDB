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

package io.evitadb.api.functional.hierarchy;

import com.github.javafaker.Faker;
import io.evitadb.api.functional.hierarchy.EntityByHierarchyFilteringFunctionalTest.TestHierarchyPredicate;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ArrayUtils;
import lombok.extern.slf4j.Slf4j;
import one.edee.oss.pmptt.model.HierarchyItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.text.Collator;
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
import static io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY;
import static io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.extension.DataCarrier.tuple;
import static io.evitadb.test.generator.DataGenerator.*;
import static io.evitadb.utils.AssertionUtils.assertResultIs;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * This test verifies whether entities that reference other - hierarchical entity can be filtered by hierarchy constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
public abstract class AbstractReferencingEntityByHierarchyFunctionalTest extends AbstractHierarchyTest {
	private static final String THOUSAND_PRODUCTS = "ThousandProducts";
	private static final String ATTRIBUTE_MARKET_SHARE = "marketShare";
	private static final String ATTRIBUTE_FOUNDED = "founded";
	private static final String ATTRIBUTE_CAPACITY = "capacity";
	private static final String ATTRIBUTE_SHORTCUT = "shortcut";
	private static final int SEED = 40;
	private final DataGenerator dataGenerator = new DataGenerator();

	@Nonnull
	private static CardinalityProvider computeCardinalities(
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy,
		List<SealedEntity> allProducts,
		Map<Integer, SealedEntity> categoryIndex,
		Predicate<SealedEntity> filterPredicate,
		TestHierarchyPredicate treeFilterPredicate,
		TestHierarchyPredicate scopePredicate,
		EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour
	) {
		final Set<Integer> categoriesWithValidPath = new HashSet<>();
		for (SealedEntity category : categoryIndex.values()) {
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
		for (SealedEntity category : categoryIndex.values()) {
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
				if (emptyHierarchicalEntityBehaviour == REMOVE_EMPTY && ArrayUtils.isEmpty(productIds)) {
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
	private static Hierarchy computeExpectedStatistics(
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy,
		List<SealedEntity> allProducts,
		Map<Integer, SealedEntity> categoryIndex,
		Predicate<SealedEntity> filterPredicate,
		TestHierarchyPredicate treeFilterPredicate,
		TestHierarchyPredicate scopePredicate,
		Function<CardinalityProvider, HierarchyStatisticsTuple> statisticsComputer
	) {
		return computeExpectedStatistics(
			categoryHierarchy, allProducts, categoryIndex, filterPredicate, treeFilterPredicate, scopePredicate,
			REMOVE_EMPTY, statisticsComputer
		);
	}

	@Nonnull
	private static Hierarchy computeExpectedStatistics(
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy,
		List<SealedEntity> allProducts,
		Map<Integer, SealedEntity> categoryIndex,
		Predicate<SealedEntity> filterPredicate,
		TestHierarchyPredicate treeFilterPredicate,
		TestHierarchyPredicate scopePredicate,
		EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		Function<CardinalityProvider, HierarchyStatisticsTuple> statisticsComputer
	) {
		final CardinalityProvider categoryCardinalities = computeCardinalities(
			categoryHierarchy, allProducts, categoryIndex, filterPredicate,
			treeFilterPredicate, scopePredicate, emptyHierarchicalEntityBehaviour
		);
		final HierarchyStatisticsTuple result = statisticsComputer.apply(categoryCardinalities);
		final Map<String, List<LevelInfo>> theResults = Map.of(
			result.name(), result.levelInfos()
		);
		return new Hierarchy(
			Collections.emptyMap(),
			Collections.singletonMap(Entities.CATEGORY, theResults)
		);
	}

	@DataSet(value = THOUSAND_PRODUCTS, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};
			this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleBrandSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(5)
				.forEach(session::upsertEntity);

			final List<EntityReference> storedCategories = this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleCategorySchema(
						session,
						schemaBuilder -> {
							schemaBuilder.withAttribute(ATTRIBUTE_SHORTCUT, Boolean.class, AttributeSchemaEditor::filterable);
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(100)
				.map(session::upsertEntity)
				.toList();

			this.dataGenerator.generateEntities(
					this.dataGenerator.getSamplePriceListSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(4)
				.forEach(session::upsertEntity);

			this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleStoreSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(12)
				.forEach(session::upsertEntity);

			final List<EntityReference> storedProducts = this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleProductSchema(
						session,
						schemaBuilder -> {
							schemaBuilder
								.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique().sortable())
								.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class, whichIs -> whichIs.filterable().sortable().indexDecimalPlaces(2))
								.withAttribute(ATTRIBUTE_PRIORITY, Long.class, whichIs -> whichIs.sortable().filterable())
								.withReferenceToEntity(
									Entities.CATEGORY,
									Entities.CATEGORY,
									Cardinality.ZERO_OR_MORE,
									whichIs -> makeReferenceIndexed(whichIs).faceted()
								)
								.withReferenceToEntity(
									Entities.BRAND,
									Entities.BRAND,
									Cardinality.ZERO_OR_ONE,
									whichIs -> makeReferenceIndexed(whichIs)
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

			final List<SealedEntity> categoriesAvailable = storedCategories.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), hierarchyContent(), attributeContentAll(), referenceContentAll(), dataInLocalesAll()).orElseThrow())
				.toList();
			return new DataCarrier(
				tuple(
					"originalProductEntities",
					storedProducts.stream()
						.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), attributeContentAll(), referenceContentAllWithAttributes(), dataInLocalesAll()).orElseThrow())
						.collect(Collectors.toList())
				),
				tuple(
					"originalCategoryIndex",
					categoriesAvailable
				),
				tuple(
					"originalCategoryIndex",
					categoriesAvailable.stream().collect(Collectors.toMap(EntityContract::getPrimaryKey, Function.identity()))
				),
				tuple(
					"categoryHierarchy",
					this.dataGenerator.getHierarchy(Entities.CATEGORY)
				)
			);
		});
	}

	/**
	 * Configures the provided ReferenceSchemaBuilder to be indexed.
	 *
	 * @param whichIs the ReferenceSchemaBuilder instance to be configured as indexed
	 * @return the configured ReferenceSchemaBuilder instance
	 */
	@Nonnull
	protected abstract ReferenceSchemaBuilder makeReferenceIndexed(ReferenceSchemaBuilder whichIs);

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
	void shouldReturnAllProductsInCategoriesExceptSpecifiedSubtrees(Evita evita, List<SealedEntity> originalProductEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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

	@DisplayName("Should return all products in categories having specified category subtrees")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnAllProductsInCategoriesHavingSpecifiedSubtrees(Evita evita, List<SealedEntity> originalProductEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		final Set<Integer> requestedChildren = new HashSet<>(Arrays.asList(1, 7, 13, 16, 40, 55));
		final Set<Integer> parentsOfRequestedChildren = requestedChildren
			.stream()
			.flatMap(
				pk -> categoryHierarchy.getParentItems(String.valueOf(pk))
					.stream()
					.map(HierarchyItem::getCode)
					.map(Integer::parseInt)
			)
			.collect(Collectors.toSet());

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithinRoot(Entities.CATEGORY, anyHaving(entityPrimaryKeyInSet(requestedChildren.toArray(new Integer[0]))))),
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
							// is requested child
							return requestedChildren.contains(category.getReferenceKey().primaryKey()) ||
								// or its parent
								parentsOfRequestedChildren.contains(category.getReferenceKey().primaryKey());
						}),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return all products in categories having specific child")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnAllProductsInCategoriesHavingMatchingChild(Evita evita, List<SealedEntity> originalProductEntities, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		final Set<Integer> excluded = new HashSet<>(Arrays.asList(1, 7, 13, 16, 40, 55));
		final Set<Integer> includedCategories = originalCategoryIndex.values().stream()
			.filter(sealedEntity ->
				// is not directly excluded node
				!excluded.contains(sealedEntity.getPrimaryKey()) &&
					// has no parent node that is in excluded set
					categoryHierarchy.getParentItems(sealedEntity.getPrimaryKey().toString())
						.stream()
						.map(it -> Integer.parseInt(it.getCode()))
						.noneMatch(excluded::contains))
			.map(EntityContract::getPrimaryKey)
			.collect(Collectors.toSet());

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithinRoot(Entities.CATEGORY, having(entityPrimaryKeyInSet(includedCategories.toArray(new Integer[0]))))),
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
	void shouldReturnProductsInCategory(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(7), directRelation())),
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

	@DisplayName("Should return products in shortcut categories")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnProductsInAllShortcutCategories(Evita evita, List<SealedEntity> originalProductEntities, Map<Integer, SealedEntity> originalCategoryIndex) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(
								Entities.CATEGORY,
								attributeEqualsTrue(ATTRIBUTE_SHORTCUT),
								directRelation()
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
					sealedEntity -> sealedEntity
						.getReferences(Entities.CATEGORY)
						.stream()
						.map(categoryRel -> originalCategoryIndex.get(categoryRel.getReferencedPrimaryKey()))
						.anyMatch(category -> category.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return all subcategories of shortcuts and their children")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnSubCategoriesInAllShortCutsAndTheirChildren(Evita evita, List<SealedEntity> originalProductEntities, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(
								Entities.CATEGORY,
								attributeEqualsTrue(ATTRIBUTE_SHORTCUT)
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
					sealedEntity -> sealedEntity
						.getReferences(Entities.CATEGORY)
						.stream()
						.flatMap(
							categoryRel -> {
								final SealedEntity referencedCategory = originalCategoryIndex.get(categoryRel.getReferencedPrimaryKey());
								return Stream.concat(
									Stream.of(referencedCategory),
									categoryHierarchy.getParentItems(String.valueOf(referencedCategory.getPrimaryKey()))
										.stream()
										.map(it -> Integer.parseInt(it.getCode()))
										.map(originalCategoryIndex::get)
								);
							}
						)
						.anyMatch(category -> category.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products in selected category subtree")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnProductsInCategorySubtree(Evita evita, List<SealedEntity> originalProductEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(7))),
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
	void shouldReturnProductsInCategorySubtreeExceptDirectlyRelated(Evita evita, List<SealedEntity> originalProductEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int selectedCategoryId = 5;
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(selectedCategoryId), excludingRoot())),
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
							// is not exactly category 5
							return !Objects.equals(categoryId, String.valueOf(selectedCategoryId)) &&
								// but has parent category 5
								categoryHierarchy.getParentItems(categoryId)
									.stream()
									.anyMatch(it -> Objects.equals(it.getCode(), String.valueOf(selectedCategoryId)));
						}),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products in category subtree only of specified subtrees")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnProductsInCategorySubtreeOnlyOfSpecifiedSubtrees(Evita evita, List<SealedEntity> originalProductEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		final Set<Integer> included = new HashSet<>(Arrays.asList(6, 13, 20, 25));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(1),
								having(entityPrimaryKeyInSet(included.toArray(new Integer[0])))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final Set<Integer> includedAndRoot = Stream.concat(
						Stream.of(1),
						included.stream()
					)
					.collect(Collectors.toSet());

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
								// is directly included node
								includedAndRoot.contains(categoryId) &&
									// has included parent node
									parentItems
										.stream()
										.map(it -> Integer.parseInt(it.getCode()))
										.allMatch(it -> includedAndRoot.contains(it) || Objects.equals(it, 1));
						}),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products in category subtree only of specified subtrees excluding root")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnProductsInCategorySubtreeOnlyOfSpecifiedSubtreesExcludingRoot(Evita evita, List<SealedEntity> originalProductEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		final Set<Integer> included = new HashSet<>(Arrays.asList(6, 13, 20, 25));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(1),
								having(entityPrimaryKeyInSet(included.toArray(new Integer[0]))),
								excludingRoot()
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
					sealedEntity -> sealedEntity
						.getReferences(Entities.CATEGORY)
						.stream()
						.anyMatch(category -> {
							final int categoryId = category.getReferenceKey().primaryKey();
							final String categoryIdAsString = String.valueOf(categoryId);
							final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(categoryIdAsString);
							return
								// is directly included node
								included.contains(categoryId) &&
									// has included parent node
									parentItems
										.stream()
										.map(it -> Integer.parseInt(it.getCode()))
										.allMatch(it -> included.contains(it) || Objects.equals(it, 1));
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
	void shouldReturnProductsInCategorySubtreeExceptSpecifiedSubtrees(Evita evita, List<SealedEntity> originalProductEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		final Set<Integer> excluded = new HashSet<>(Arrays.asList(2, 43, 34, 53));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(1),
								excluding(entityPrimaryKeyInSet(excluded.toArray(new Integer[0])))
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

	@DisplayName("Should return products in category subtree except specified subtrees without affecting root node")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnProductsInCategorySubtreeExceptSpecifiedSubtreesWithoutAffectingRootNode(Evita evita, List<SealedEntity> originalProductEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		final Set<Integer> excluded = new HashSet<>(Arrays.asList(2, 43, 34, 53));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(1),
								excluding(
									entityPrimaryKeyInSet(excluded.toArray(new Integer[0]))
								)
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
	void shouldReturnProductsInCategorySubtreeExceptSpecifiedSubtreesAndMatchingCertainConstraint(Evita evita, List<SealedEntity> originalProductEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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
								hierarchyWithin(
									Entities.CATEGORY,
									entityPrimaryKeyInSet(1),
									excluding(entityPrimaryKeyInSet(excluded.toArray(new Integer[0])))
								)
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
								ofNullable(sealedEntity.getAttribute(ATTRIBUTE_CODE)).map(it -> !((String) it).isEmpty() && ((String) it).charAt(0) == 'E').orElse(false) ||
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

	@DisplayName("Should return cardinalities for products when filter constraint is eliminated")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldReturnCardinalitiesForProductsWhenFilterConstraintIsEliminated(Evita evita, List<SealedEntity> originalProductEntities, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int selectedCategoryId = 1;
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(selectedCategoryId)),
							entityLocaleEquals(CZECH_LOCALE)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							hierarchyOfReference(
								Entities.CATEGORY,
								REMOVE_EMPTY,
								fromRoot(
									"megaMenu",
									entityFetch(attributeContentAll(), dataInLocales(CZECH_LOCALE))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					entity -> entity.getLocales().contains(CZECH_LOCALE),
					languagePredicate,
					languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy, categoryCardinalities,
							false,
							false, false,
							selectedCategoryId
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return cardinalities for products")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForProducts(EnumSet<StatisticsType> statisticsType, Evita evita, List<SealedEntity> originalProductEntities, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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
									entityFetch(attributeContentAll()),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					entity -> entity.getLocales().contains(CZECH_LOCALE) &&
						ofNullable((Boolean) entity.getAttribute(ATTRIBUTE_ALIAS)).orElse(false),
					languagePredicate,
					languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy, categoryCardinalities,
							false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return cardinalities for products, leaving empty categories")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForProductsLeavingEmptyCategories(EnumSet<StatisticsType> statisticsType, Evita evita, List<SealedEntity> originalProductEntities, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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
								LEAVE_EMPTY,
								fromRoot(
									"megaMenu",
									entityFetch(attributeContentAll()),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					entity -> entity.getLocales().contains(CZECH_LOCALE) &&
						ofNullable((Boolean) entity.getAttribute(ATTRIBUTE_ALIAS)).orElse(false),
					languagePredicate,
					languagePredicate,
					LEAVE_EMPTY,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy, categoryCardinalities,
							false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return cardinalities for products in subtree")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForProductsWhenSubTreeIsRequested(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2))
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
									entityFetch(attributeContentAll()),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					entity -> entity.getLocales().contains(CZECH_LOCALE) &&
						ofNullable((Boolean) entity.getAttribute(ATTRIBUTE_ALIAS)).orElse(false),
					languagePredicate,
					languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null,
							categoryHierarchy, categoryCardinalities,
							false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							2
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for sibling categories with all statistics within requested category 2")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategorySiblingsWhenSubTreeIsRequested(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(6))
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
									entityFetch(attributeContentAll()),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate,
					(category, parentItems) -> categoryPredicate.test(category, parentItems) && languagePredicate.test(category, parentItems),
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							6
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics within category 2")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesOfCertainCategory(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(6))
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
									entityFetch(attributeContentAll()),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate,
					(category, parentItems) -> categoryPredicate.test(category, parentItems) && languagePredicate.test(category, parentItems),
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 2, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for root categories with all statistics in distance 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForRootCategoriesInDistanceOne(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2))
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
									entityFetch(attributeContentAll()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate,
					treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							2
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for requested categories with all statistics in distance 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForRequestedCategoriesInDistanceOne(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(1))
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
									entityFetch(attributeContentAll()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							1
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for requested category siblings with all statistics in distance 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForRequestedCategorySiblingsInDistanceOne(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(6))
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
									entityFetch(attributeContentAll()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate scopePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					// has parent node 1
					final boolean hasParentNode = parentItems
						.stream()
						.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) &&
						(sealedEntity.getPrimaryKey() == 1 || hasParentNode) &&
						(level <= 3);
				};

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							6
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for specified category with all statistics in distance 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForSpecifiedCategoryInDistanceOne(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(6))
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
									entityFetch(attributeContentAll()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							6
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return root children for categories with all statistics in distance 1 within when no filtering constraint is specified")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForWithinCategoryInDistanceOne(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
									entityFetch(attributeContentAll()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, true,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for root categories with all statistics until shortcut category is reached")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForRootCategoriesAndStopAtShortCuts(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
									entityFetch(attributeContentAll()),
									stopAt(node(filterBy(attributeEqualsTrue(ATTRIBUTE_SHORTCUT)))),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) ->
					languagePredicate.test(sealedEntity, parentItems) &&
						(sealedEntity.getParentEntity().isEmpty() ||
							!originalCategoryIndex.get(sealedEntity.getParentEntity().get().getPrimaryKey()).getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class));

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics until shortcut category is reached within category 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedAndStopAtShortCuts(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
									entityFetch(attributeContentAll()),
									stopAt(node(filterBy(attributeEqualsTrue(ATTRIBUTE_SHORTCUT)))),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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
						(sealedEntity.getParentEntity().isEmpty() ||
							!originalCategoryIndex.get(sealedEntity.getParentEntity().get().getPrimaryKey()).getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class));
				};

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics until shortcut category is reached within requested category 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForRequestedCategoryAndStopAtShortCuts(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(1))
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
									entityFetch(attributeContentAll()),
									stopAt(node(filterBy(attributeEqualsTrue(ATTRIBUTE_SHORTCUT)))),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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
						(sealedEntity.getParentEntity().isEmpty() ||
							!originalCategoryIndex.get(sealedEntity.getParentEntity().get().getPrimaryKey()).getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class));
				};

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							1
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return siblings for categories with all statistics until shortcut category is reached within requested category 6")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnSiblingsCardinalitiesForRequestedCategoryAndStopAtShortCuts(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(6))
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
									stopAt(node(filterBy(attributeEqualsTrue(ATTRIBUTE_SHORTCUT)))),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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
						(sealedEntity.getParentEntity().isEmpty() ||
							!originalCategoryIndex.get(sealedEntity.getParentEntity().get().getPrimaryKey()).getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class));
				};

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							6
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics except shortcut categories")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesExceptShortCuts(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
										attributeEqualsTrue(ATTRIBUTE_SHORTCUT)
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate =
					(entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE) &&
						!entity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics except shortcut categories within category 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedExceptShortCuts(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
									Entities.CATEGORY, entityPrimaryKeyInSet(1),
									excluding(
										attributeEqualsTrue(ATTRIBUTE_SHORTCUT)
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
								children(
									"megaMenu",
									entityFetch(attributeContent()),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							1
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children categories with all statistics except shortcut categories for siblings of category 16")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnSiblingCardinalitiesForCategoriesWhenSubTreeIsRequestedExceptShortCuts(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
									Entities.CATEGORY, entityPrimaryKeyInSet(16),
									excluding(
										attributeEqualsTrue(ATTRIBUTE_SHORTCUT)
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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
					final boolean childOfCategory1 = parentItems.size() == 1 && Objects.equals(1, Integer.parseInt(parentItems.get(0).getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && childOfCategory1;
				};

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 16, categoryHierarchy,
							categoryCardinalities,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							16
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics except shortcut categories for category 1")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesForRequestedSubTreeExceptShortCuts(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
									Entities.CATEGORY, entityPrimaryKeyInSet(16),
									excluding(
										attributeEqualsTrue(ATTRIBUTE_SHORTCUT)
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							16
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics until level 2")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesUntilLevelTwo(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics in category 1 until level two")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedUntilLevelTwo(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(1))
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							1
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics in category 1 until level two")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesForSelectedSubTreeUntilLevelTwo(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(6))
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							6
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return sorted children for categories with all statistics until level two")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnSortedCardinalitiesUntilLevelTwo(
		EnumSet<StatisticsType> statisticsType,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Collator collator = Collator.getInstance(CZECH_LOCALE);
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					product -> product.getLocales().contains(CZECH_LOCALE),
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							(o1, o2) -> {
								final String name1 = o1.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class);
								final String name2 = o2.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class);
								return collator.compare(name1, name2);
							}
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for all categories until level three on different filter base")
	@UseDataSet(THOUSAND_PRODUCTS)
	@ParameterizedTest
	@MethodSource({"statisticTypeAndBaseVariants"})
	void shouldReturnChildrenToLevelThreeFroDifferentFilterBase(
		EnumSet<StatisticsType> statisticsType,
		StatisticsBase base,
		Evita evita,
		List<SealedEntity> originalProductEntities,
		Map<Integer, SealedEntity> originalCategoryIndex,
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy
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
								userFilter(
									attributeEqualsFalse(ATTRIBUTE_ALIAS)
								),
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
								fromRoot(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(level(3)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Predicate<SealedEntity> filterPredicate;
				if (base == StatisticsBase.COMPLETE_FILTER) {
					filterPredicate = (sealedEntity) -> sealedEntity.getLocales().contains(CZECH_LOCALE)
						&& !sealedEntity.getAttribute(ATTRIBUTE_ALIAS, Boolean.class);
				} else {
					filterPredicate = (entity) -> entity.getLocales().contains(CZECH_LOCALE);
				}
				final TestHierarchyPredicate treeFilterPredicate = (sealedEntity, parentItems) -> sealedEntity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate scopePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					return treeFilterPredicate.test(sealedEntity, parentItems) && level <= 3;
				};

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					filterPredicate,
					treeFilterPredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return same results for prefetched calculation")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldSameResultsForPrefetchedCalculation(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityLocaleEquals(Locale.ENGLISH),
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(7))
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS),
							entityFetchAll()
						)
					),
					SealedEntity.class
				);

				final EvitaResponse<SealedEntity> resultUsingPrefetch = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityLocaleEquals(Locale.ENGLISH),
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(7))
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING),
							entityFetchAll()
						)
					),
					SealedEntity.class
				);

				assertEquals(
					result.getTotalRecordCount(),
					resultUsingPrefetch.getTotalRecordCount()
				);

				return null;
			}
		);
	}

	@DisplayName("Should ignore hierarchy selection in user filter and correctly compute statistics")
	@UseDataSet(THOUSAND_PRODUCTS)
	@Test
	void shouldIgnoreHierarchySelectionInUserFilterAndCorrectlyComputeStatistics(Evita evita, List<SealedEntity> originalProductEntities, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityLocaleEquals(Locale.ENGLISH),
							userFilter(
								facetHaving(
									Entities.CATEGORY,
									entityPrimaryKeyInSet(7),
									includingChildren()
								)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS),
							hierarchyOfReference(
								Entities.CATEGORY,
								REMOVE_EMPTY,
								children(
									"megaMenu",
									entityFetchAll(),
									statistics(
										StatisticsBase.COMPLETE_FILTER_EXCLUDING_SELF_IN_USER_FILTER,
										StatisticsType.QUERIED_ENTITY_COUNT,
										StatisticsType.CHILDREN_COUNT
									)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(Locale.ENGLISH);
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalProductEntities, originalCategoryIndex,
					entity -> entity.getLocales().contains(Locale.ENGLISH),
					languagePredicate,
					languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null,
							categoryHierarchy, categoryCardinalities,
							false,
							true, true
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	private static class Cardinalities implements CardinalityProvider {
		private final Map<Integer, Bitmap> itemCardinality = new HashMap<>(32);
		private final Map<Integer, Integer> childrenItemCount = new HashMap<>(32);
		private final EmptyHierarchicalEntityBehaviour emptyBehaviour = REMOVE_EMPTY;

		public void recordCategoryVisible(int categoryId) {
			this.childrenItemCount.merge(categoryId, 1, Integer::sum);
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
				.forEach(it -> this.itemCardinality.merge(
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
			return this.emptyBehaviour == LEAVE_EMPTY || this.itemCardinality.containsKey(categoryId);
		}

		@Override
		public int getCardinality(int categoryId) {
			return ofNullable(this.itemCardinality.get(categoryId)).map(Bitmap::size).orElse(0);
		}

		@Override
		public int getChildrenCount(int categoryId) {
			return ofNullable(this.childrenItemCount.get(categoryId)).orElse(0);
		}
	}

}
