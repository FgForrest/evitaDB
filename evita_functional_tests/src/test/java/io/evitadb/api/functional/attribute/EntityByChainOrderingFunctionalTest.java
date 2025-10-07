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

package io.evitadb.api.functional.attribute;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import com.carrotsearch.hppc.IntSet;
import com.github.javafaker.Faker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexKey;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.DataCarrier.Tuple;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;
import one.edee.oss.pmptt.model.Hierarchy;
import one.edee.oss.pmptt.model.HierarchyItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;
import static io.evitadb.utils.AssertionUtils.assertSortedResultEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_CATEGORY_ORDER = "categoryOrder";
	private static final String ATTRIBUTE_BRAND_ORDER = "brandOrder";
	private static final String REFERENCE_CATEGORY_PRODUCTS = "products";
	private static final String REFERENCE_BRAND_PRODUCTS = "products";
	private static final String CHAINED_ELEMENTS = "chained-elements";
	private static final String CHAINED_ELEMENTS_MINIMAL = "chained-elements-minimal";
	private static final String ATTRIBUTE_MARKET = "market";
	private static final String ATTRIBUTE_INCEPTION_YEAR = "inceptionYear";
	private static final String ATTRIBUTE_MARKET_INCEPTION_YEAR = "marketInceptionYear";
	private static final int SEED = 40;
	private final static int PRODUCT_COUNT = 100;
	private final static int CATEGORY_COUNT = 10;
	private final static int BRAND_COUNT = 10;
	private final static int[] BRAND_ORDER;
	private final static int[] PRODUCT_ORDER;

	static {
		PRODUCT_ORDER = new int[PRODUCT_COUNT];
		for (int i = 1; i <= PRODUCT_COUNT; i++) {
			PRODUCT_ORDER[i - 1] = i;
		}

		ArrayUtils.shuffleArray(new Random(SEED), PRODUCT_ORDER, PRODUCT_COUNT);

		BRAND_ORDER = new int[BRAND_COUNT];
		for (int i = 1; i <= BRAND_COUNT; i++) {
			BRAND_ORDER[i - 1] = i;
		}

		ArrayUtils.shuffleArray(new Random(SEED), BRAND_ORDER, BRAND_COUNT);
	}

	/**
	 * Comparator for compound sortable attribute used in schemas.
	 */
	private final Comparator<ComparableArray> marketInceptionYearComparator = SortIndex.createCombinedComparatorFor(
		null,
		new ComparatorSource[]{
			new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
			new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
		}
	);

	/**
	 * Sorts the products in a category based on the reference order attribute.
	 *
	 * @param productsInCategory     a map where the key is the referenced entity ID (non-null) and the value is a list of sealed entities (nullable).
	 * @param referenceName          the name of the reference to sort by (non-null).
	 * @param referenceAttributeName the name of the attribute to sort by (non-null).
	 */
	static void sortProductsInByReferenceAttributeOfChainableType(
		@Nonnull Map<Integer, List<SealedEntity>> productsInCategory,
		@Nonnull String referenceName,
		@Nonnull String referenceAttributeName
	) {
		productsInCategory.forEach((key, value) -> {
			if (value != null) {
				// we rely on ChainIndex correctness - it's tested elsewhere
				final ChainIndex chainIndex = new ChainIndex(new AttributeIndexKey(referenceName, referenceAttributeName, null));
				for (SealedEntity entity : value) {
					final ReferenceContract reference = entity.getReference(referenceName, key).orElseThrow();
					final ChainableType attribute = reference.getAttribute(referenceAttributeName);
					if (attribute != null) {
						chainIndex.upsertPredecessor(
							attribute,
							entity.getPrimaryKeyOrThrowException()
						);
					}
				}
				// this is not much effective, but enough for a test
				final int[] sortedRecordIds = chainIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds();
				value.sort(
					(a, b) -> {
						final int aPos = ArrayUtils.indexOf(a.getPrimaryKeyOrThrowException(), sortedRecordIds);
						final int bPos = ArrayUtils.indexOf(b.getPrimaryKeyOrThrowException(), sortedRecordIds);
						return Integer.compare(aPos, bPos);
					}
				);
			}
		});
	}

	/**
	 * Updates the order of the products within a category in the Evita session.
	 *
	 * @param session                the session to execute the update operations
	 * @param entityName             the name of the entity to update
	 * @param referenceName          the name of the reference to update
	 * @param referenceAttributeName the name of the reference attribute to update
	 * @param predecessorCreator     a function that creates a predecessor for the specified reference
	 */
	static void updateReferenceAttributeInProduct(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityName,
		@Nonnull String referenceName,
		@Nonnull String referenceAttributeName,
		@Nonnull BiFunction<ReferenceContract, int[], Serializable> predecessorCreator
	) {
		final Random rnd = new Random(SEED);
		session.queryList(
				query(
					collection(entityName),
					require(
						page(1, 100),
						entityFetch(
							referenceContentWithAttributes(referenceName)
						)
					)
				),
				SealedEntity.class
			)
			.forEach(theEntity -> {
				final int[] referenceEntities = theEntity.getReferences(referenceName)
					.stream()
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray();
				ArrayUtils.shuffleArray(rnd, referenceEntities, referenceEntities.length);
				final EntityBuilder entityBuilder = theEntity.openForWrite();
				theEntity
					.getReferences(referenceName)
					.forEach(
						reference -> {
							if (reference.getAttribute(referenceAttributeName) != null) {
								entityBuilder.setReference(
									referenceName,
									reference.getReferencedPrimaryKey(),
									whichIs -> whichIs.setAttribute(
										referenceAttributeName,
										predecessorCreator.apply(reference, referenceEntities)
									)
								);
							}
						}
					);
				session.upsertEntity(entityBuilder);
			});
	}

	/**
	 * Collects a map of product indexes from stored products using the provided session.
	 *
	 * @param session        the session to use for retrieving entities, must not be null
	 * @param storedProducts the list of stored products, must not be null
	 * @return a map of primary keys to sealed entities, never null
	 * @throws NoSuchElementException if any entity is not found
	 */
	@Nonnull
	static Map<Integer, SealedEntity> collectProductIndex(
		@Nonnull EvitaSessionContract session,
		@Nonnull List<EntityReference> storedProducts
	) {
		return storedProducts.stream()
			.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
			.collect(
				Collectors.toMap(
					SealedEntity::getPrimaryKeyOrThrowException,
					Function.identity()
				)
			);
	}

	/**
	 * Updates the order attribute for categories based on their parent-child relationships.
	 * This method uses the given session to query categories and determines the order of each category
	 * within its parent's list of children, updating their order attribute accordingly.
	 *
	 * @param session          the Evita session to interact with the database.
	 * @param categoryOrdering a map where the key is the parent category's primary key
	 *                         and the value is an array of child categories' primary keys
	 *                         defining their order as they relate to the parent.
	 */
	static void updateOrderAttributeInCategory(
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<Integer, int[]> categoryOrdering
	) {
		session.queryList(
				query(
					collection(Entities.CATEGORY),
					require(
						page(1, 100),
						entityFetch(
							hierarchyContent(),
							attributeContentAll()
						)
					)
				),
				SealedEntity.class
			)
			.forEach(theEntity -> {
				final Integer parent = theEntity.getParentEntity()
					.map(EntityClassifier::getPrimaryKeyOrThrowException)
					.orElse(null);

				final int[] childOrder = categoryOrdering.get(parent);
				final int index = ArrayUtils.indexOf(theEntity.getPrimaryKeyOrThrowException(), childOrder);

				assertTrue(index >= 0, "Category " + theEntity.getPrimaryKeyOrThrowException() + " is unexpectedly not found!");
				if (index > 0) {
					theEntity.openForWrite()
						.setAttribute(ATTRIBUTE_ORDER, new Predecessor(childOrder[index - 1]))
						.upsertVia(session);
				}
			});
	}

	/**
	 * Generates an order array for the items in a hierarchy and populates a given mapping
	 * with the order array for a specific parent identifier.
	 *
	 * @param parent                The ID of the parent node for which the order is being generated, must not be null.
	 * @param items                 The list of hierarchical items under the parent node, must not be null.
	 * @param categoryHierarchy     The hierarchy structure containing the relationships of items, must not be null.
	 * @param rnd                   A Random instance used for shuffling the order array, must not be null.
	 * @param categoryPositionIndex A mapping where the generated order arrays are stored with the parent node ID as the key, must not be null.
	 */
	static void generateOrder(
		@Nullable Integer parent,
		@Nonnull List<HierarchyItem> items,
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull Random rnd,
		@Nonnull Map<Integer, int[]> categoryPositionIndex
	) {
		if (!items.isEmpty()) {
			final int[] order = new int[items.size()];
			for (int i = 0; i < items.size(); i++) {
				final String itemIdAsString = items.get(i).getCode();
				final int itemId = Integer.parseInt(itemIdAsString);
				order[i] = itemId;
				generateOrder(
					itemId,
					categoryHierarchy.getChildItems(itemIdAsString),
					categoryHierarchy,
					rnd,
					categoryPositionIndex
				);
			}
			ArrayUtils.shuffleArray(rnd, order, order.length);
			categoryPositionIndex.put(parent, order);
		}
	}

	/**
	 * Returns the primary keys (PKs) of items in depth-first order from the given category hierarchy.
	 *
	 * @param categoryHierarchy the hierarchy structure containing categories to traverse. Must not be null.
	 * @return an array of integers containing primary keys of items in depth-first order. Never null.
	 */
	@Nonnull
	static int[] getDepthFirstOrderedPks(
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull Map<Integer, SealedEntity> categoryIndex
	) {
		return getDepthFirstOrderedPks(
			categoryHierarchy,
			Comparator.comparingInt(EntityClassifier::getPrimaryKeyOrThrowException),
			categoryIndex
		);
	}

	/**
	 * Returns the primary keys (PKs) of items in breadth-first order from the given category hierarchy.
	 *
	 * @param categoryHierarchy the hierarchy structure containing categories to traverse. Must not be null.
	 * @return an array of integers containing primary keys of items in breadth-first order. Never null.
	 */
	@Nonnull
	static int[] getBreadthFirstOrderedPks(
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull Map<Integer, SealedEntity> categoryIndex
	) {
		return getBreadthFirstOrderedPks(
			categoryHierarchy,
			Comparator.comparingInt(EntityClassifier::getPrimaryKeyOrThrowException),
			categoryIndex
		);
	}

	/**
	 * Returns the primary keys (PKs) of items in depth-first order from the given category hierarchy.
	 *
	 * @param categoryHierarchy the hierarchy structure containing categories to traverse. Must not be null.
	 * @return an array of integers containing primary keys of items in depth-first order. Never null.
	 */
	@Nonnull
	static int[] getDepthFirstOrderedPks(
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull Comparator<SealedEntity> entityComparator,
		@Nonnull Map<Integer, SealedEntity> categoryIndex
	) {
		final List<Integer> orderedCategoryIds = new ArrayList<>();
		categoryHierarchy.getRootItems()
			.stream()
			.sorted((cat1, cat2) -> entityComparator.compare(categoryIndex.get(Integer.parseInt(cat1.getCode())), categoryIndex.get(Integer.parseInt(cat2.getCode()))))
			.forEach(childItem -> traverseDepthFirst(childItem, categoryHierarchy, orderedCategoryIds, entityComparator, categoryIndex));
		return orderedCategoryIds.stream()
			.mapToInt(Integer::intValue)
			.toArray();
	}

	/**
	 * Returns the primary keys (PKs) of items in breadth-first order from the given category hierarchy.
	 *
	 * @param categoryHierarchy the hierarchy structure containing categories to traverse. Must not be null.
	 * @return an array of integers containing primary keys of items in breadth-first order. Never null.
	 */
	@Nonnull
	static int[] getBreadthFirstOrderedPks(
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull Comparator<SealedEntity> entityComparator,
		@Nonnull Map<Integer, SealedEntity> categoryIndex
	) {
		final List<Integer> orderedCategoryIds = new ArrayList<>();
		traverseBreadthFirst(
			categoryHierarchy.getRootItems()
				.stream()
				.sorted((cat1, cat2) -> entityComparator.compare(categoryIndex.get(Integer.parseInt(cat1.getCode())), categoryIndex.get(Integer.parseInt(cat2.getCode()))))
				.toList(),
			categoryHierarchy,
			orderedCategoryIds,
			entityComparator,
			categoryIndex
		);
		return orderedCategoryIds.stream()
			.mapToInt(Integer::intValue)
			.toArray();
	}

	/**
	 * Retrieves the category ID with the most products.
	 *
	 * @param productsInCategory a map of category IDs to lists of SealedEntity objects
	 * @return the category ID with the most products
	 * @throws NoSuchElementException if the map is empty
	 * @throws NullPointerException   if any map key, value, or contained list is null
	 */
	private static int getCategoryWithMostProducts(@Nonnull Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = productsInCategory.entrySet()
			.stream()
			.max(Comparator.comparingInt(o -> o.getValue().size()))
			.orElseThrow()
			.getKey();
		assertTrue(
			productsInCategory.get(categoryId).size() > 5,
			"Too few products in the category to test ordering."
		);
		return categoryId;
	}

	/**
	 * Compares the market inception year of two {@link SealedEntity} objects in a specified category.
	 *
	 * @param o1         the first {@link SealedEntity}, must not be null
	 * @param o2         the second {@link SealedEntity}, must not be null
	 * @param categoryId the category ID, must not be null
	 * @return a negative integer, zero, or a positive integer as the first argument is less than, equal to, or greater than the second
	 */
	private static int compareMarketInceptionYear(@Nonnull SealedEntity o1, @Nonnull SealedEntity o2, int categoryId) {
		final ReferenceContract o1Ref = o1.getReference(Entities.CATEGORY, categoryId).orElseThrow();
		final ReferenceContract o2Ref = o2.getReference(Entities.CATEGORY, categoryId).orElseThrow();
		final String o1Market = o1Ref.getAttribute(ATTRIBUTE_MARKET);
		final String o2Market = o2Ref.getAttribute(ATTRIBUTE_MARKET);
		if (Objects.equals(o1Market, o2Market)) {
			final Integer o1Year = o1Ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR);
			final Integer o2Year = o2Ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR);
			// nulls last
			if (o1Year == null) {
				return o2Year == null ? 0 : 1;
			} else if (o2Year == null) {
				return -1;
			} else {
				return Integer.compare(o2Year, o1Year);
			}
		} else {
			// nulls last
			if (o1Market == null) {
				return 1;
			} else if (o2Market == null) {
				return -1;
			} else {
				return o1Market.compareTo(o2Market);
			}
		}
	}

	/**
	 * Creates the expected order of product primary keys grouped and sorted by their associated brands.
	 * Products are grouped by the primary key of the first referenced brand and then sorted according
	 * to the order of products within each brand group as specified in the input maps.
	 *
	 * @param products        a map where the key is the product primary key, and the value is a {@link SealedEntity}
	 *                        representing the product along with its references, including brand references.
	 * @param productsInBrand a map where the key is the brand primary key, and the value is a list of
	 *                        {@link SealedEntity} objects representing all products belonging to that brand,
	 *                        already sorted as needed.
	 * @return a map of product primary keys arranged in the expected order grouped and sorted by brand.
	 */
	@Nonnull
	private static Map<Integer, int[]> createExpectedOrderOfProductsInBrand(
		@Nonnull Map<Integer, SealedEntity> products,
		@Nonnull Map<Integer, List<SealedEntity>> productsInBrand
	) {
		// collect and group products by FIRST referenced brand
		final Map<Integer, List<Integer>> productsGroupedByBrand = CollectionUtils.createHashMap(BRAND_COUNT);
		for (int i = 1; i <= PRODUCT_COUNT; i++) {
			int productPk = i;
			products.get(i).getReferences(Entities.BRAND)
				.stream()
				.findFirst()
				.ifPresent(
					it -> productsGroupedByBrand
						.computeIfAbsent(it.getReferencedPrimaryKey(), __ -> new ArrayList<>())
						.add(productPk)
				);
		}

		// sort products in each brand group
		final Map<Integer, int[]> sortedProductsGroupedByBrand = CollectionUtils.createHashMap(BRAND_COUNT);
		productsGroupedByBrand.forEach(
			(brandPk, productPks) -> {
				final List<SealedEntity> allProductsSortedInBrand = productsInBrand.get(brandPk);
				final int[] productsToBeSorted = productPks.stream().mapToInt(it -> it).toArray();
				ArrayUtils.sortAlong(
					allProductsSortedInBrand.stream().mapToInt(EntityClassifier::getPrimaryKeyOrThrowException).toArray(),
					productsToBeSorted
				);
				sortedProductsGroupedByBrand.put(brandPk, productsToBeSorted);
			}
		);

		return sortedProductsGroupedByBrand;
	}

	/**
	 * Traverses a hierarchy depth-first starting from the given item and populates a list of ordered category IDs.
	 *
	 * @param item               {@link HierarchyItem} to start traversing from. Must not be {@code null}.
	 * @param categoryHierarchy  {@link Hierarchy} representing the category hierarchy. Must not be {@code null}.
	 * @param orderedCategoryIds {@link List} that will hold the ordered category IDs. Must not be {@code null}.
	 * @throws NumberFormatException if the item's code cannot be parsed as an integer.
	 */
	private static void traverseDepthFirst(
		@Nonnull HierarchyItem item,
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull List<Integer> orderedCategoryIds,
		@Nonnull Comparator<SealedEntity> entityComparator,
		@Nonnull Map<Integer, SealedEntity> categoryIndex
	) {
		orderedCategoryIds.add(Integer.parseInt(item.getCode()));
		categoryHierarchy.getChildItems(item.getCode())
			.stream()
			.sorted((cat1, cat2) -> entityComparator.compare(categoryIndex.get(Integer.parseInt(cat1.getCode())), categoryIndex.get(Integer.parseInt(cat2.getCode()))))
			.forEach(childItem -> traverseDepthFirst(childItem, categoryHierarchy, orderedCategoryIds, entityComparator, categoryIndex));
	}

	/**
	 * Traverses a hierarchy breadth-first starting from the given item and populates a list of ordered category IDs.
	 *
	 * @param items              list {@link HierarchyItem} to start traversing from. Must not be {@code null}.
	 * @param categoryHierarchy  {@link Hierarchy} representing the category hierarchy. Must not be {@code null}.
	 * @param orderedCategoryIds {@link List} that will hold the ordered category IDs. Must not be {@code null}.
	 * @throws NumberFormatException if the item's code cannot be parsed as an integer.
	 */
	private static void traverseBreadthFirst(
		@Nonnull List<HierarchyItem> items,
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull List<Integer> orderedCategoryIds,
		@Nonnull Comparator<SealedEntity> entityComparator,
		@Nonnull Map<Integer, SealedEntity> categoryIndex
	) {
		items.stream().map(item -> Integer.parseInt(item.getCode())).forEach(orderedCategoryIds::add);
		final List<HierarchyItem> children = items.stream()
			.flatMap(
				it -> categoryHierarchy
					.getChildItems(it.getCode())
					.stream()
					.sorted((cat1, cat2) -> entityComparator.compare(categoryIndex.get(Integer.parseInt(cat1.getCode())), categoryIndex.get(Integer.parseInt(cat2.getCode()))))
			)
			.toList();
		if (!children.isEmpty()) {
			traverseBreadthFirst(
				children,
				categoryHierarchy,
				orderedCategoryIds,
				entityComparator,
				categoryIndex);
		}
	}

	@Nullable
	@DataSet(value = CHAINED_ELEMENTS, readOnly = false, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			session.updateCatalogSchema(
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.sortable().uniqueGlobally().nullable())
					.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally().nullable())
			);

			final AtomicInteger brandIndex = new AtomicInteger();
			final AtomicInteger productIndex = new AtomicInteger();
			final DataGenerator dataGenerator = new DataGenerator.Builder()
				.registerValueGenerator(
					Entities.PRODUCT, ATTRIBUTE_ORDER,
					faker -> {
						final int ix = productIndex.incrementAndGet();
						final int position = ArrayUtils.indexOf(ix, PRODUCT_ORDER);
						return position == 0 ? Predecessor.HEAD : new Predecessor(PRODUCT_ORDER[position - 1]);
					}
				)
				// we need to update the order in second pass
				.registerValueGenerator(
					Entities.PRODUCT, ATTRIBUTE_CATEGORY_ORDER,
					faker -> Predecessor.HEAD
				)
				// we need to update the order in second pass
				.registerValueGenerator(
					Entities.PRODUCT, ATTRIBUTE_BRAND_ORDER,
					faker -> Predecessor.HEAD
				)
				// generate order attribute for category
				.registerValueGenerator(
					Entities.CATEGORY, ATTRIBUTE_ORDER,
					faker -> Predecessor.HEAD
				)
				// generate order attribute for category
				.registerValueGenerator(
					Entities.BRAND, ATTRIBUTE_ORDER,
					faker -> {
						final int ix = brandIndex.incrementAndGet();
						final int position = ArrayUtils.indexOf(ix, BRAND_ORDER);
						return position == 0 ? Predecessor.HEAD : new Predecessor(BRAND_ORDER[position - 1]);
					}
				)
				.build();

			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};

			// we need to create category schema first
			final SealedEntitySchema categorySchema = dataGenerator.getSampleCategorySchema(
				session,
				schemaBuilder -> {
					schemaBuilder
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_ORDER, Predecessor.class, AttributeSchemaEditor::sortable)
						.withReflectedReferenceToEntity(
							REFERENCE_CATEGORY_PRODUCTS,
							Entities.PRODUCT,
							Entities.CATEGORY,
							whichIs -> whichIs
								.withAttributesInherited()
								.withCardinality(Cardinality.ZERO_OR_MORE)
						);
				}
			);

			// we need to create brand schema first
			final SealedEntitySchema brandSchema = dataGenerator.getSampleBrandSchema(
				session,
				schemaBuilder -> schemaBuilder
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_ORDER, Predecessor.class, AttributeSchemaEditor::sortable)
					.withReflectedReferenceToEntity(
						REFERENCE_BRAND_PRODUCTS,
						Entities.PRODUCT,
						Entities.BRAND,
						whichIs -> whichIs
							.withAttributesInherited()
							.withCardinality(Cardinality.ZERO_OR_MORE)
					).updateAndFetchVia(session)
			);

			// then the product schema
			final SealedEntitySchema productSchema = dataGenerator.getSampleProductSchema(
				session,
				schemaBuilder -> {
					schemaBuilder
						.withAttribute(
							ATTRIBUTE_ORDER, Predecessor.class,
							AttributeSchemaEditor::sortable
						)
						.withReferenceToEntity(
							Entities.CATEGORY,
							Entities.CATEGORY,
							Cardinality.EXACTLY_ONE,
							whichIs -> whichIs.indexedForFilteringAndPartitioning()
								.withAttribute(ATTRIBUTE_CATEGORY_ORDER, Predecessor.class, AttributeSchemaEditor::sortable)
								.withAttribute(ATTRIBUTE_MARKET, String.class, thatIs -> thatIs.nullable().sortable())
								.withAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class, thatIs -> thatIs.nullable().sortable())
								.withSortableAttributeCompound(
									ATTRIBUTE_MARKET_INCEPTION_YEAR,
									new AttributeElement(ATTRIBUTE_MARKET, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
									new AttributeElement(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
								)
						)
						/* new one-to many reference to non-hierarchical entity */
						.withReferenceToEntity(
							Entities.BRAND,
							Entities.BRAND,
							Cardinality.ONE_OR_MORE,
							whichIs -> whichIs
								.indexedForFilteringAndPartitioning()
								.faceted()
								.withAttribute(ATTRIBUTE_BRAND_ORDER, Predecessor.class, AttributeSchemaEditor::sortable)
								.withAttribute(ATTRIBUTE_MARKET, String.class, thatIs -> thatIs.nullable().sortable())
								.withAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class, thatIs -> thatIs.nullable().sortable())
								.withSortableAttributeCompound(
									ATTRIBUTE_MARKET_INCEPTION_YEAR,
									new AttributeElement(ATTRIBUTE_MARKET, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
									new AttributeElement(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
								)
						);
					// we need only category + brand references in this test
					for (String referenceName : schemaBuilder.getReferences().keySet()) {
						if (!referenceName.equals(Entities.CATEGORY) && !referenceName.equals(Entities.BRAND)) {
							schemaBuilder.withoutReferenceTo(referenceName);
						}
					}
				}
			);

			// and now data for both of them (since they are intertwined via reflected reference)
			final Map<Integer, SealedEntity> categoryIndexByPk = dataGenerator.generateEntities(
					categorySchema,
					randomEntityPicker,
					SEED
				)
				.limit(CATEGORY_COUNT)
				.map(it -> session.upsertAndFetchEntity(it, entityFetchAllContent()))
				.collect(
					Collectors.toMap(
						EntityClassifier::getPrimaryKeyOrThrowException,
						Function.identity()
					)
				);

			// generate category sequences and update the order respecting the hierarchy
			final Hierarchy categoryHierarchy = dataGenerator.getHierarchy(Entities.CATEGORY);
			final Map<Integer, int[]> categoryOrdering = new HashMap<>();
			final Random rnd = new Random(SEED);
			generateOrder(null, categoryHierarchy.getRootItems(), categoryHierarchy, rnd, categoryOrdering);

			// second pass - update the order of the categories
			updateOrderAttributeInCategory(session, categoryOrdering);

			dataGenerator.generateEntities(
					brandSchema,
					randomEntityPicker,
					SEED
				)
				.limit(BRAND_COUNT)
				.forEach(session::upsertEntity);

			final List<EntityReference> storedProducts = dataGenerator.generateEntities(
					productSchema,
					(entityName, faker) -> {
						if (Entities.CATEGORY.equals(entityName)) {
							return faker.random().nextInt(CATEGORY_COUNT) + 1;
						} else if (Entities.BRAND.equals(entityName)) {
							return faker.random().nextInt(BRAND_COUNT) + 1;
						} else {
							throw new IllegalArgumentException("Unknown entity type: " + entityName);
						}
					},
					SEED
				)
				.limit(PRODUCT_COUNT)
				.map(session::upsertEntity)
				.toList();

			// second pass - update the category order of the products
			updateReferenceAttributeInProduct(
				session,
				Entities.CATEGORY,
				REFERENCE_CATEGORY_PRODUCTS,
				ATTRIBUTE_CATEGORY_ORDER,
				(reference, referencedProducts) -> {
					final int theIndex = ArrayUtils.indexOf(reference.getReferencedPrimaryKey(), referencedProducts);
					return theIndex == 0 ?
						ReferencedEntityPredecessor.HEAD : new ReferencedEntityPredecessor(referencedProducts[theIndex - 1]);
				}
			);

			// second pass - update the brand order of the products
			updateReferenceAttributeInProduct(
				session,
				Entities.BRAND,
				REFERENCE_BRAND_PRODUCTS,
				ATTRIBUTE_BRAND_ORDER,
				(reference, referencedProducts) -> {
					final int theIndex = ArrayUtils.indexOf(reference.getReferencedPrimaryKey(), referencedProducts);
					return theIndex == 0 ?
						ReferencedEntityPredecessor.HEAD : new ReferencedEntityPredecessor(referencedProducts[theIndex - 1]);
				}
			);

			final Map<Integer, SealedEntity> products = collectProductIndex(session, storedProducts);

			final Map<Integer, List<SealedEntity>> productsInCategory = products
				.values()
				.stream()
				.flatMap(
					product -> product.getReferences(Entities.CATEGORY)
						.stream()
						.map(ref -> Map.entry(ref.getReferencedPrimaryKey(), product))
				)
				.collect(
					Collectors.groupingBy(
						Map.Entry::getKey, // Group by the primary key from the reference
						Collectors.mapping(
							Map.Entry::getValue, // Collect the `SealedEntity` as the value
							Collectors.toList()
						)
					)
				);
			final Map<Integer, List<SealedEntity>> productsInBrand = products
				.values()
				.stream()
				.flatMap(
					product -> product.getReferences(Entities.BRAND)
						.stream()
						.map(ref -> Map.entry(ref.getReferencedPrimaryKey(), product))
				)
				.collect(
					Collectors.groupingBy(
						Map.Entry::getKey, // Group by the primary key from the reference
						Collectors.mapping(
							Map.Entry::getValue, // Collect the `SealedEntity` as the value
							Collectors.toList()
						)
					)
				);

			// now we need to sort the products in the category
			sortProductsInByReferenceAttributeOfChainableType(productsInCategory, Entities.CATEGORY, ATTRIBUTE_CATEGORY_ORDER);
			// now we need to sort the products in the brand
			sortProductsInByReferenceAttributeOfChainableType(productsInBrand, Entities.BRAND, ATTRIBUTE_BRAND_ORDER);

			final IntIntMap brandPositionIndex = new IntIntHashMap();
			for (int i = 0; i < BRAND_ORDER.length; i++) {
				brandPositionIndex.put(BRAND_ORDER[i], i);
			}

			final IntIntMap categoryPositionIndex = new IntIntHashMap();
			for (int[] order : categoryOrdering.values()) {
				for (int i = 0; i < order.length; i++) {
					categoryPositionIndex.put(order[i], i);
				}
			}

			return new DataCarrier(
				new Tuple("products", products),
				new Tuple("productsInCategory", productsInCategory),
				new Tuple("productsInBrand", productsInBrand),
				new Tuple("categoryHierarchy", categoryHierarchy),
				new Tuple("brandPositionIndex", brandPositionIndex),
				new Tuple("categoryPositionIndex", categoryPositionIndex),
				new Tuple("categoryIndex", categoryIndexByPk)
			);
		});
	}

	@Nullable
	@DataSet(value = CHAINED_ELEMENTS_MINIMAL, readOnly = false, destroyAfterClass = true)
	DataCarrier setUpMinimal(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final DataGenerator dataGenerator = new DataGenerator.Builder()
				// we need to update the order in second pass
				.registerValueGenerator(
					Entities.CATEGORY, ATTRIBUTE_CATEGORY_ORDER,
					faker -> ReferencedEntityPredecessor.HEAD
				)
				.build();

			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};

			// we need to create category schema first
			final SealedEntitySchema categorySchema = dataGenerator.getSampleCategorySchema(
				session,
				schemaBuilder -> {
					schemaBuilder
						.withoutHierarchy()
						.withReferenceToEntity(
							REFERENCE_CATEGORY_PRODUCTS,
							Entities.PRODUCT,
							Cardinality.ONE_OR_MORE,
							whichIs -> whichIs.indexedForFilteringAndPartitioning()
								.withAttribute(ATTRIBUTE_CATEGORY_ORDER, ReferencedEntityPredecessor.class, AttributeSchemaEditor::sortable)
						);
				}
			);

			// then the product schema
			final SealedEntitySchema productSchema = dataGenerator.getSampleProductSchema(
				session,
				schemaBuilder -> {
					// we need only category references in this test
					for (String referenceName : schemaBuilder.getReferences().keySet()) {
						schemaBuilder.withoutReferenceTo(referenceName);
					}
					schemaBuilder
						.withReflectedReferenceToEntity(
							Entities.CATEGORY,
							Entities.CATEGORY,
							REFERENCE_CATEGORY_PRODUCTS,
							whichIs -> whichIs
								.withAttributesInherited()
								.withCardinality(Cardinality.ZERO_OR_MORE)
						);
				}
			);

			// and now data for both of them (since they are intertwined via reflected reference)
			final List<EntityReference> storedProducts = dataGenerator.generateEntities(
					productSchema,
					randomEntityPicker,
					SEED
				)
				.limit(PRODUCT_COUNT)
				.map(session::upsertEntity)
				.toList();

			dataGenerator.generateEntities(
					categorySchema,
					randomEntityPicker,
					SEED
				)
				.limit(10)
				.forEach(session::upsertEntity);

			// second pass - update the category order of the products
			updateReferenceAttributeInProduct(
				session,
				Entities.CATEGORY, REFERENCE_CATEGORY_PRODUCTS, ATTRIBUTE_CATEGORY_ORDER, (reference, referencedProducts) -> {
					final int theIndex = ArrayUtils.indexOf(reference.getReferencedPrimaryKey(), referencedProducts);
					return theIndex == 0 ?
						ReferencedEntityPredecessor.HEAD : new ReferencedEntityPredecessor(referencedProducts[theIndex - 1]);
				}
			);

			final Map<Integer, SealedEntity> products = collectProductIndex(session, storedProducts);

			final Map<Integer, List<SealedEntity>> productsInCategory = products
				.values()
				.stream()
				.flatMap(it -> it.getReferences(Entities.CATEGORY).stream().map(ref -> new EntityReferenceDTO(it, ref)))
				.collect(
					Collectors.groupingBy(
						it -> it.reference().getReferencedPrimaryKey(),
						Collectors.mapping(EntityReferenceDTO::entity, Collectors.toList())
					)
				);

			// now we need to sort the products in the category
			sortProductsInByReferenceAttributeOfChainableType(productsInCategory, Entities.CATEGORY, ATTRIBUTE_CATEGORY_ORDER);

			return new DataCarrier(
				REFERENCE_CATEGORY_PRODUCTS, products,
				"productsInCategory", productsInCategory
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
				assertSortedResultEquals(
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
				assertSortedResultEquals(
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
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);
				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(PRODUCT_ORDER).filter(it -> Arrays.stream(prefetchedProducts).anyMatch(pid -> pid == it)).toArray()
				);
				return null;
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
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);
				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(ArrayUtils.reverse(PRODUCT_ORDER)).filter(it -> Arrays.stream(prefetchedProducts).anyMatch(pid -> pid == it)).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by category order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryPredecessorAttributeInAscendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(categoryId)
							)
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.ASC)
							)
						),
						require(
							page(1, 20),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					productsInCategory.get(categoryId).stream().mapToInt(SealedEntity::getPrimaryKeyOrThrowException).limit(20).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending category assignment order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryPredecessorAttributeInDescendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(categoryId)
							)
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.DESC)
							)
						),
						require(
							page(1, 20),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				final int[] expectedOrder = ArrayUtils.reverse(
					productsInCategory.get(categoryId).stream().mapToInt(SealedEntity::getPrimaryKeyOrThrowException).toArray()
				);
				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(20).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The prefetched product should be returned in category ascending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortPrefetchedProductsByPredecessorCategoryAttributeInAscendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		// we need to prefetch the products, skip every other product in category
		final AtomicInteger counter = new AtomicInteger();
		final int[] prefetchedProducts = productsInCategory.get(categoryId)
			.stream()
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.filter(it -> counter.incrementAndGet() % 2 == 0)
			.limit(10)
			.toArray();

		final int[] shuffledArray = Arrays.copyOf(prefetchedProducts, prefetchedProducts.length);
		ArrayUtils.shuffleArray(new Random(), shuffledArray, shuffledArray.length);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(shuffledArray)
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.ASC)
							)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);
				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					prefetchedProducts
				);
				return null;
			}
		);
	}

	@DisplayName("The prefetched product should be returned in category descending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortPrefetchedProductsByPredecessorCategoryAttributeInDescendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		// we need to prefetch the products, skip every other product in category
		final AtomicInteger counter = new AtomicInteger();
		final int[] prefetchedProducts = productsInCategory.get(categoryId)
			.stream()
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.filter(it -> counter.incrementAndGet() % 2 == 0)
			.limit(10)
			.toArray();
		final int[] shuffledArray = Arrays.copyOf(prefetchedProducts, prefetchedProducts.length);
		ArrayUtils.shuffleArray(new Random(), shuffledArray, shuffledArray.length);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(shuffledArray)
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.DESC)
							)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);
				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					ArrayUtils.reverse(prefetchedProducts)
				);
				return null;
			}
		);
	}

	@DisplayName("The categories should have products sorted ascending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsInCategoryByAttributeInAscendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.CATEGORY),
						require(
							page(1, 10),
							entityFetch(
								referenceContentWithAttributes(
									REFERENCE_CATEGORY_PRODUCTS,
									orderBy(
										attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.ASC)
									)
								)
							),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					SealedEntity.class
				);

				assertEquals(10, result.getRecordData().size());
				for (SealedEntity category : result.getRecordData()) {
					final List<SealedEntity> products = productsInCategory.get(category.getPrimaryKey());
					final int[] sortedProductIds = products == null ?
						new int[0] :
						products
							.stream()
							.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
							.toArray();
					assertSortedResultEquals(
						category
							.getReferences(REFERENCE_CATEGORY_PRODUCTS)
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.toList(),
						sortedProductIds
					);
				}
				return null;
			}
		);
	}

	@DisplayName("The categories should have products sorted descending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsInCategoryByAttributeInDescendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.CATEGORY),
						require(
							page(1, 10),
							entityFetch(
								referenceContent(
									REFERENCE_CATEGORY_PRODUCTS,
									orderBy(
										attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.DESC)
									)
								)
							),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					SealedEntity.class
				);

				assertEquals(10, result.getRecordData().size());
				for (SealedEntity category : result.getRecordData()) {
					// we need to prefetch the products, skip every other product in category
					final List<SealedEntity> products = productsInCategory.get(category.getPrimaryKey());
					final int[] sortedProductIds = products == null ?
						new int[0] :
						products
							.stream()
							.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
							.toArray();
					assertSortedResultEquals(
						category
							.getReferences(REFERENCE_CATEGORY_PRODUCTS)
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.toList(),
						ArrayUtils.reverse(sortedProductIds)
					);
				}
				return null;
			}
		);
	}

	@DisplayName("The prefetched category should have products sorted in ascending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsInPrefetchedCategoryByAttributeInAscendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		// we need to prefetch the products, skip every other product in category
		final int[] sortedProductIds = productsInCategory.get(categoryId)
			.stream()
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.toArray();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(categoryId)
						),
						require(
							page(1, 10),
							entityFetch(
								referenceContent(
									REFERENCE_CATEGORY_PRODUCTS,
									orderBy(
										attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.ASC)
									)
								)
							),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				assertSortedResultEquals(
					result.getRecordData().get(0)
						.getReferences(REFERENCE_CATEGORY_PRODUCTS)
						.stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.toList(),
					sortedProductIds
				);
				return null;
			}
		);
	}

	@DisplayName("The prefetched category should have products sorted in descending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsInPrefetchedCategoryByAttributeInDescendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		// we need to prefetch the products, skip every other product in category
		final int[] sortedProductIds = productsInCategory.get(categoryId)
			.stream()
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.toArray();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(categoryId)
						),
						require(
							page(1, 10),
							entityFetch(
								referenceContent(
									REFERENCE_CATEGORY_PRODUCTS,
									orderBy(
										attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.DESC)
									)
								)
							),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				assertSortedResultEquals(
					result.getRecordData().get(0)
						.getReferences(REFERENCE_CATEGORY_PRODUCTS)
						.stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.toList(),
					ArrayUtils.reverse(sortedProductIds)
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by category sortable compound")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategorySortableCompoundInAscendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(categoryId)
							)
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 20),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					productsInCategory.get(categoryId).stream()
						.sorted((o1, o2) -> compareMarketInceptionYear(o1, o2, categoryId))
						.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
						.limit(20)
						.toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending order by category sortable compound")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategorySortableCompoundInDescendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(categoryId)
							)
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 20),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				final int[] expectedOrder = ArrayUtils.reverse(
					productsInCategory.get(categoryId).stream()
						.sorted((o1, o2) -> compareMarketInceptionYear(o1, o2, categoryId))
						.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
						.limit(20)
						.toArray()
				);
				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(20).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The prefetched product should be returned in category sortable compound ascending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortPrefetchedProductsByCategorySortableCompoundInAscendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		// we need to prefetch the products, skip every other product in category
		final AtomicInteger counter = new AtomicInteger();
		final int[] prefetchedProducts = productsInCategory.get(categoryId)
			.stream()
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.filter(it -> counter.incrementAndGet() % 2 == 0)
			.limit(10)
			.toArray();
		final int[] shuffledArray = Arrays.copyOf(prefetchedProducts, prefetchedProducts.length);
		ArrayUtils.shuffleArray(new Random(), shuffledArray, shuffledArray.length);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(shuffledArray)
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				counter.set(0);
				final int[] expectedOrder = productsInCategory.get(categoryId).stream()
					.filter(it -> counter.incrementAndGet() % 2 == 0)
					.limit(10)
					.sorted((o1, o2) -> compareMarketInceptionYear(o1, o2, categoryId))
					.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
					.toArray();
				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					expectedOrder
				);
				return null;
			}
		);
	}

	@DisplayName("The prefetched product should be returned in category sortable compound descending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortPrefetchedProductsByCategorySortableCompoundInDescendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		// we need to prefetch the products, skip every other product in category
		final AtomicInteger counter = new AtomicInteger();
		final int[] prefetchedProducts = productsInCategory.get(categoryId)
			.stream()
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.filter(it -> counter.incrementAndGet() % 2 == 0)
			.limit(10)
			.toArray();
		final int[] shuffledArray = Arrays.copyOf(prefetchedProducts, prefetchedProducts.length);
		ArrayUtils.shuffleArray(new Random(), shuffledArray, shuffledArray.length);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(shuffledArray)
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				counter.set(0);
				final int[] expectedOrder = productsInCategory.get(categoryId).stream()
					.filter(it -> counter.incrementAndGet() % 2 == 0)
					.limit(10)
					.sorted((o1, o2) -> compareMarketInceptionYear(o1, o2, categoryId))
					.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
					.toArray();
				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					ArrayUtils.reverse(expectedOrder)
				);
				return null;
			}
		);
	}

	@DisplayName("The prefetched category should have products sorted by sortable compound in ascending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsInPrefetchedCategoryByAttributeCompoundInAscendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		// we need to prefetch the products, skip every other product in category
		final int[] sortedProductIds = productsInCategory.get(categoryId)
			.stream()
			.sorted((o1, o2) -> compareMarketInceptionYear(o1, o2, categoryId))
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.toArray();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(categoryId)
						),
						require(
							page(1, 10),
							entityFetch(
								referenceContentWithAttributes(
									REFERENCE_CATEGORY_PRODUCTS,
									orderBy(
										attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.ASC)
									)
								)
							),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				assertSortedResultEquals(
					result.getRecordData().get(0)
						.getReferences(REFERENCE_CATEGORY_PRODUCTS)
						.stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.toList(),
					sortedProductIds
				);
				return null;
			}
		);
	}

	@DisplayName("The prefetched category should have products by sortable compound sorted in descending order")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsInPrefetchedCategoryByAttributeCompoundInDescendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		// we need to prefetch the products, skip every other product in category
		final int[] sortedProductIds = productsInCategory.get(categoryId)
			.stream()
			.sorted((o1, o2) -> compareMarketInceptionYear(o1, o2, categoryId))
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.toArray();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(categoryId)
						),
						require(
							page(1, 10),
							entityFetch(
								referenceContentWithAttributes(
									REFERENCE_CATEGORY_PRODUCTS,
									orderBy(
										attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.DESC)
									)
								)
							),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());
				assertSortedResultEquals(
					result.getRecordData().get(0)
						.getReferences(REFERENCE_CATEGORY_PRODUCTS)
						.stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.toList(),
					ArrayUtils.reverse(sortedProductIds)
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by category order via. referenced predecessor")
	@UseDataSet(CHAINED_ELEMENTS_MINIMAL)
	@Test
	void shouldSortProductsByCategoryReferencedEntityPredecessorAttributeInAscendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(categoryId)
							)
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.ASC)
							)
						),
						require(
							page(1, 20),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					productsInCategory.get(categoryId).stream().mapToInt(SealedEntity::getPrimaryKeyOrThrowException).limit(20).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending category assignment order via referenced predecessor")
	@UseDataSet(CHAINED_ELEMENTS_MINIMAL)
	@Test
	void shouldSortProductsByCategoryReferencedEntityPredecessorAttributeInDescendingOrder(Evita evita, Map<Integer, List<SealedEntity>> productsInCategory) {
		final int categoryId = getCategoryWithMostProducts(productsInCategory);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(categoryId)
							)
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.DESC)
							)
						),
						require(
							page(1, 20),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				final int[] expectedOrder = ArrayUtils.reverse(
					productsInCategory.get(categoryId).stream().mapToInt(SealedEntity::getPrimaryKeyOrThrowException).toArray()
				);
				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(20).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by first brand order via referenced predecessor")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandReferencedEntityPredecessorAttributeInAscendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Map<Integer, List<SealedEntity>> productsInBrand
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_BRAND_ORDER, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final Map<Integer, int[]> sortedProductsGroupedByBrand = createExpectedOrderOfProductsInBrand(products, productsInBrand);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;
				for (int i = 1; i <= BRAND_COUNT; i++) {
					final int[] sortedProductsInBrand = sortedProductsGroupedByBrand.get(i);
					if (sortedProductsInBrand != null) {
						for (int productPk : sortedProductsInBrand) {
							expectedOrder[index++] = productPk;
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending order by first brand order via referenced predecessor")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandReferencedEntityPredecessorAttributeInDescendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Map<Integer, List<SealedEntity>> productsInBrand
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_BRAND_ORDER, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final Map<Integer, int[]> sortedProductsGroupedByBrand = createExpectedOrderOfProductsInBrand(products, productsInBrand);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;
				for (int i = 1; i <= BRAND_COUNT; i++) {
					final int[] sortedProductsInBrand = sortedProductsGroupedByBrand.get(i);
					if (sortedProductsInBrand != null) {
						for (int j = sortedProductsInBrand.length - 1; j >= 0; j--) {
							expectedOrder[index++] = sortedProductsInBrand[j];
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by first brand order via referenced predecessor (prefetch)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandReferencedEntityPredecessorAttributeInAscendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Map<Integer, List<SealedEntity>> productsInBrand
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_BRAND_ORDER, OrderDirection.ASC)
							)
						),
						require(
							page(1, selectedProducts.size()),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final Map<Integer, int[]> sortedProductsGroupedByBrand = createExpectedOrderOfProductsInBrand(products, productsInBrand);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;
				for (int i = 1; i <= BRAND_COUNT; i++) {
					final int[] sortedProductsInBrand = sortedProductsGroupedByBrand.get(i);
					if (sortedProductsInBrand != null) {
						for (int productPk : sortedProductsInBrand) {
							if (selectedProducts.contains(productPk)) {
								expectedOrder[index++] = productPk;
							}
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending order by first brand order via referenced predecessor (prefetch)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandReferencedEntityPredecessorAttributeInDescendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Map<Integer, List<SealedEntity>> productsInBrand
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_BRAND_ORDER, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final Map<Integer, int[]> sortedProductsGroupedByBrand = createExpectedOrderOfProductsInBrand(products, productsInBrand);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;
				for (int i = 1; i <= BRAND_COUNT; i++) {
					final int[] sortedProductsInBrand = sortedProductsGroupedByBrand.get(i);
					if (sortedProductsInBrand != null) {
						for (int j = sortedProductsInBrand.length - 1; j >= 0; j--) {
							final int productPk = sortedProductsInBrand[j];
							if (selectedProducts.contains(productPk)) {
								expectedOrder[index++] = productPk;
							}
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by first brand order via attribute")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandReferencedEntityAttributeInAscendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(entity -> entity.getReferences(Entities.BRAND).stream().map(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class)).filter(Objects::nonNull).findFirst().orElseThrow());
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();
				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending order by first brand order via attribute")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandReferencedEntityAttributeInDescendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(entity -> entity.getReferences(Entities.BRAND).stream().map(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class)).filter(Objects::nonNull).findFirst().orElseThrow());
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();
				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int i = sortedValues.length - 1; i >= 0; i--) {
					int epk = sortedValues[i];
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by first brand order via attribute using prefetch")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandReferencedEntityAttributeInAscendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(entity -> entity.getReferences(Entities.BRAND).stream().map(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class)).filter(Objects::nonNull).findFirst().orElseThrow());
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();
				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i) && selectedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending order by first brand order via attribute using prefetch")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandReferencedEntityAttributeInDescendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(entity -> entity.getReferences(Entities.BRAND).stream().map(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class)).filter(Objects::nonNull).findFirst().orElseThrow());
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();
				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int i = sortedValues.length - 1; i >= 0; i--) {
					int epk = sortedValues[i];
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i) && selectedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by first brand order via sortable attribute compound")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandReferencedEntitySortableAttributeCompoundInAscendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null || ref.getAttribute(ATTRIBUTE_MARKET) != null;
				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(
					entity -> {
						final ReferenceContract ref = entity.getReferences(Entities.BRAND)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.orElseThrow();
						return new ComparableArray(
							new Serializable[]{
								ref.getAttribute(ATTRIBUTE_MARKET, String.class),
								ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class)
							}
						);
					},
					this.marketInceptionYearComparator
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending order by first brand order via sortable attribute compound")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandReferencedEntitySortableAttributeCompoundInDescendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null || ref.getAttribute(ATTRIBUTE_MARKET) != null;
				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(
					entity -> {
						final ReferenceContract ref = entity.getReferences(Entities.BRAND)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.orElseThrow();
						return new ComparableArray(
							new Serializable[]{
								ref.getAttribute(ATTRIBUTE_MARKET, String.class),
								ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class)
							}
						);
					},
					this.marketInceptionYearComparator
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int i = sortedValues.length - 1; i >= 0; i--) {
					int epk = sortedValues[i];
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by first brand order via sortable attribute compound using prefetch")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandReferencedEntitySortableAttributeCompoundInAscendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null || ref.getAttribute(ATTRIBUTE_MARKET) != null;
				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(
					entity -> {
						final ReferenceContract ref = entity.getReferences(Entities.BRAND)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.orElseThrow();
						return new ComparableArray(
							new Serializable[]{
								ref.getAttribute(ATTRIBUTE_MARKET, String.class),
								ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class)
							}
						);
					},
					this.marketInceptionYearComparator
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i) && selectedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending order by first brand order via sortable attribute compound using prefetch")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandReferencedEntitySortableAttributeCompoundInDescendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null || ref.getAttribute(ATTRIBUTE_MARKET) != null;
				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(
					entity -> {
						final ReferenceContract ref = entity.getReferences(Entities.BRAND)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.orElseThrow();
						return new ComparableArray(
							new Serializable[]{
								ref.getAttribute(ATTRIBUTE_MARKET, String.class),
								ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class)
							}
						);
					},
					this.marketInceptionYearComparator
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int i = sortedValues.length - 1; i >= 0; i--) {
					int epk = sortedValues[i];
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i) && selectedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal order via predecessor reference attribute")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalReferencedEntityPredecessorAttributeInAscendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, List<SealedEntity>> productsInCategory,
		Map<Integer, SealedEntity> categoryIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.DEPTH_FIRST),
								attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getDepthFirstOrderedPks(categoryHierarchy, categoryIndex);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_CATEGORY_ORDER) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> productsInCategory.get(ref.getReferencedPrimaryKey()).indexOf(entity))
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal order via reference predecessor attribute (desc)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalReferencedEntityPredecessorAttributeDescendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, List<SealedEntity>> productsInCategory,
		Map<Integer, SealedEntity> categoryIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.DEPTH_FIRST),
								attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getDepthFirstOrderedPks(categoryHierarchy, categoryIndex);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_CATEGORY_ORDER) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> productsInCategory.get(ref.getReferencedPrimaryKey()).indexOf(entity))
						.orElseThrow(),
					Comparator.reverseOrder()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal order via reference predecessor attribute (prefetch)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalReferencedEntityPredecessorAttributeInAscendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, List<SealedEntity>> productsInCategory,
		Map<Integer, SealedEntity> categoryIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.DEPTH_FIRST),
								attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getDepthFirstOrderedPks(categoryHierarchy, categoryIndex);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_CATEGORY_ORDER) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> productsInCategory.get(ref.getReferencedPrimaryKey()).indexOf(entity))
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> selectedProducts.contains(entity.getPrimaryKeyOrThrowException()))
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						if (selectedProducts.contains(i)) {
							expectedOrder[index++] = i;
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal order via reference predecessor attribute (descending prefetch)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalReferencedEntityPredecessorAttributeInDescendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, List<SealedEntity>> productsInCategory,
		Map<Integer, SealedEntity> categoryIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.DEPTH_FIRST),
								attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getDepthFirstOrderedPks(categoryHierarchy, categoryIndex);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_CATEGORY_ORDER) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> productsInCategory.get(ref.getReferencedPrimaryKey()).indexOf(entity))
						.orElseThrow(),
					Comparator.reverseOrder()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> selectedProducts.contains(entity.getPrimaryKeyOrThrowException()))
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						if (selectedProducts.contains(i)) {
							expectedOrder[index++] = i;
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal order via reference attribute")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalReferencedEntityAttributeInAscendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.DEPTH_FIRST),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getDepthFirstOrderedPks(categoryHierarchy, categoryIndex);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal order via reference attribute (desc)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalReferencedEntityAttributeDescendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.DEPTH_FIRST),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getDepthFirstOrderedPks(categoryHierarchy, categoryIndex);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow(),
					Comparator.reverseOrder()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal order via reference attribute (prefetch)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalReferencedEntityAttributeInAscendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.DEPTH_FIRST),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getDepthFirstOrderedPks(categoryHierarchy, categoryIndex);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> selectedProducts.contains(entity.getPrimaryKeyOrThrowException()))
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						if (selectedProducts.contains(i)) {
							expectedOrder[index++] = i;
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal order via reference attribute (descending prefetch)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalReferencedEntityAttributeInDescendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.DEPTH_FIRST),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getDepthFirstOrderedPks(categoryHierarchy, categoryIndex);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow(),
					Comparator.reverseOrder()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> selectedProducts.contains(entity.getPrimaryKeyOrThrowException()))
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						if (selectedProducts.contains(i)) {
							expectedOrder[index++] = i;
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by breadth first category traversal order via reference attribute")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryBreadthFirstTraversalReferencedEntityAttributeInAscendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.BREADTH_FIRST),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getBreadthFirstOrderedPks(categoryHierarchy, categoryIndex);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by breadth first category traversal order via reference attribute (desc)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryBreadthFirstTraversalReferencedEntityAttributeDescendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.BREADTH_FIRST),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getBreadthFirstOrderedPks(categoryHierarchy, categoryIndex);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow(),
					Comparator.reverseOrder()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by breadth first category traversal order via reference attribute (prefetch)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryBreadthFirstTraversalReferencedEntityAttributeInAscendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.BREADTH_FIRST),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getBreadthFirstOrderedPks(categoryHierarchy, categoryIndex);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> selectedProducts.contains(entity.getPrimaryKeyOrThrowException()))
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						if (selectedProducts.contains(i)) {
							expectedOrder[index++] = i;
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by breadth first category traversal order via reference attribute (descending prefetch)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryBreadthFirstTraversalReferencedEntityAttributeInDescendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.BREADTH_FIRST),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getBreadthFirstOrderedPks(categoryHierarchy, categoryIndex);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow(),
					Comparator.reverseOrder()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> selectedProducts.contains(entity.getPrimaryKeyOrThrowException()))
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						if (selectedProducts.contains(i)) {
							expectedOrder[index++] = i;
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by traversed category order via sortable attribute compound")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByTraversedCategoryReferencedEntitySortableAttributeCompoundInAscendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.DEPTH_FIRST),
								attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getDepthFirstOrderedPks(categoryHierarchy, categoryIndex);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null || ref.getAttribute(ATTRIBUTE_MARKET) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> {
						final ReferenceContract ref = entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.orElseThrow();
						return new ComparableArray(
							new Serializable[]{
								ref.getAttribute(ATTRIBUTE_MARKET, String.class),
								ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class)
							}
						);
					},
					this.marketInceptionYearComparator
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending order by traversed category order via sortable attribute compound")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByTraversedCategoryReferencedEntitySortableAttributeCompoundInDescendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null || ref.getAttribute(ATTRIBUTE_MARKET) != null;
				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(
					entity -> {
						final ReferenceContract ref = entity.getReferences(Entities.BRAND)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.orElseThrow();
						return new ComparableArray(
							new Serializable[]{
								ref.getAttribute(ATTRIBUTE_MARKET, String.class),
								ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class)
							}
						);
					},
					this.marketInceptionYearComparator
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int i = sortedValues.length - 1; i >= 0; i--) {
					int epk = sortedValues[i];
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by traversed category order via sortable attribute compound using prefetch")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByTraversedCategoryReferencedEntitySortableAttributeCompoundInAscendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null || ref.getAttribute(ATTRIBUTE_MARKET) != null;
				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(
					entity -> {
						final ReferenceContract ref = entity.getReferences(Entities.BRAND)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.orElseThrow();
						return new ComparableArray(
							new Serializable[]{
								ref.getAttribute(ATTRIBUTE_MARKET, String.class),
								ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class)
							}
						);
					},
					this.marketInceptionYearComparator
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i) && selectedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending order by traversed category order via sortable attribute compound using prefetch")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByTraversedCategoryReferencedEntitySortableAttributeCompoundInDescendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_MARKET_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null || ref.getAttribute(ATTRIBUTE_MARKET) != null;
				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(
					entity -> {
						final ReferenceContract ref = entity.getReferences(Entities.BRAND)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.orElseThrow();
						return new ComparableArray(
							new Serializable[]{
								ref.getAttribute(ATTRIBUTE_MARKET, String.class),
								ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class)
							}
						);
					},
					this.marketInceptionYearComparator
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int i = sortedValues.length - 1; i >= 0; i--) {
					int epk = sortedValues[i];
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i) && selectedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by first brand order using different ordering via attribute")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandByAttributeReferencedEntityAttributeInAscendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		IntIntMap brandPositionIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								pickFirstByEntityProperty(attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(
					entity -> entity.getReferences(Entities.BRAND)
						.stream()
						.sorted(
							(o1, o2) -> {
								int pos1 = brandPositionIndex.getOrDefault(o1.getReferencedPrimaryKey(), Integer.MAX_VALUE);
								int pos2 = brandPositionIndex.getOrDefault(o2.getReferencedPrimaryKey(), Integer.MAX_VALUE);
								return Integer.compare(pos2, pos1);
							}
						)
						.map(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.filter(Objects::nonNull)
						.findFirst()
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(
						entity -> entity.getReferences(Entities.BRAND)
							.stream()
							.anyMatch(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null)
					)
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();
				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending order by first brand using different ordering order via attribute")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandByAttributeReferencedEntityAttributeInDescendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		IntIntMap brandPositionIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								pickFirstByEntityProperty(attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(
					entity -> entity.getReferences(Entities.BRAND)
						.stream()
						.sorted(
							(o1, o2) -> {
								int pos1 = brandPositionIndex.getOrDefault(o1.getReferencedPrimaryKey(), Integer.MAX_VALUE);
								int pos2 = brandPositionIndex.getOrDefault(o2.getReferencedPrimaryKey(), Integer.MAX_VALUE);
								return Integer.compare(pos2, pos1);
							}
						)
						.map(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.filter(Objects::nonNull)
						.findFirst()
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();
				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int i = sortedValues.length - 1; i >= 0; i--) {
					int epk = sortedValues[i];
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by first brand using different ordering via attribute using prefetch")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandByAttributeReferencedEntityAttributeInAscendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		IntIntMap brandPositionIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								pickFirstByEntityProperty(attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(
					entity -> entity.getReferences(Entities.BRAND)
						.stream()
						.sorted(
							(o1, o2) -> {
								int pos1 = brandPositionIndex.getOrDefault(o1.getReferencedPrimaryKey(), Integer.MAX_VALUE);
								int pos2 = brandPositionIndex.getOrDefault(o2.getReferencedPrimaryKey(), Integer.MAX_VALUE);
								return Integer.compare(pos2, pos1);
							}
						)
						.map(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.filter(Objects::nonNull)
						.findFirst()
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();
				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i) && selectedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in descending order by first brand using different ordering via attribute using prefetch")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByFirstBrandByAttributeReferencedEntityAttributeInDescendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		IntIntMap brandPositionIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								pickFirstByEntityProperty(attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Comparator<SealedEntity> attributeComparator = Comparator.comparing(
					entity -> entity.getReferences(Entities.BRAND)
						.stream()
						.sorted(
							(o1, o2) -> {
								int pos1 = brandPositionIndex.getOrDefault(o1.getReferencedPrimaryKey(), Integer.MAX_VALUE);
								int pos2 = brandPositionIndex.getOrDefault(o2.getReferencedPrimaryKey(), Integer.MAX_VALUE);
								return Integer.compare(pos2, pos1);
							}
						)
						.map(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.filter(Objects::nonNull)
						.findFirst()
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.BRAND).stream().anyMatch(it -> it.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();
				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int i = sortedValues.length - 1; i >= 0; i--) {
					int epk = sortedValues[i];
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i) && selectedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by breadth first category traversal custom order via reference attribute")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryBreadthFirstTraversalByAttributeReferencedEntityAttributeInAscendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		IntIntMap categoryPositionIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.BREADTH_FIRST, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getBreadthFirstOrderedPks(
					categoryHierarchy,
					(o1, o2) -> {
						int pos1 = categoryPositionIndex.get(o1.getPrimaryKeyOrThrowException());
						int pos2 = categoryPositionIndex.get(o2.getPrimaryKeyOrThrowException());
						return Integer.compare(pos2, pos1);
					},
					categoryIndex
				);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by breadth first category traversal custom order via reference attribute (desc)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryBreadthFirstTraversalByAttributeReferencedEntityAttributeDescendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		IntIntMap categoryPositionIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.BREADTH_FIRST, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getBreadthFirstOrderedPks(
					categoryHierarchy,
					(o1, o2) -> {
						int pos1 = categoryPositionIndex.get(o1.getPrimaryKeyOrThrowException());
						int pos2 = categoryPositionIndex.get(o2.getPrimaryKeyOrThrowException());
						return Integer.compare(pos2, pos1);
					},
					categoryIndex
				);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow(),
					Comparator.reverseOrder()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by breadth first category traversal custom order via reference attribute (prefetch)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryBreadthFirstTraversalByAttributeReferencedEntityAttributeInAscendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		IntIntMap categoryPositionIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.BREADTH_FIRST, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getBreadthFirstOrderedPks(
					categoryHierarchy,
					(o1, o2) -> {
						int pos1 = categoryPositionIndex.get(o1.getPrimaryKeyOrThrowException());
						int pos2 = categoryPositionIndex.get(o2.getPrimaryKeyOrThrowException());
						return Integer.compare(pos2, pos1);
					},
					categoryIndex
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> selectedProducts.contains(entity.getPrimaryKeyOrThrowException()))
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						if (selectedProducts.contains(i)) {
							expectedOrder[index++] = i;
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by breadth first category traversal custom order via reference attribute (descending prefetch)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryBreadthFirstTraversalByAttributeReferencedEntityAttributeInDescendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		IntIntMap categoryPositionIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.BREADTH_FIRST, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getBreadthFirstOrderedPks(
					categoryHierarchy,
					(o1, o2) -> {
						int pos1 = categoryPositionIndex.get(o1.getPrimaryKeyOrThrowException());
						int pos2 = categoryPositionIndex.get(o2.getPrimaryKeyOrThrowException());
						return Integer.compare(pos2, pos1);
					},
					categoryIndex
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow(),
					Comparator.reverseOrder()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> selectedProducts.contains(entity.getPrimaryKeyOrThrowException()))
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						if (selectedProducts.contains(i)) {
							expectedOrder[index++] = i;
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal custom order via reference attribute")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalByAttributeReferencedEntityAttributeInAscendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		IntIntMap categoryPositionIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60)/*,
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)*/
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getDepthFirstOrderedPks(
					categoryHierarchy,
					(o1, o2) -> {
						int pos1 = categoryPositionIndex.get(o1.getPrimaryKeyOrThrowException());
						int pos2 = categoryPositionIndex.get(o2.getPrimaryKeyOrThrowException());
						return Integer.compare(pos2, pos1);
					},
					categoryIndex
				);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal custom order via reference attribute (desc)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalByAttributeReferencedEntityAttributeDescendingOrder(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		IntIntMap categoryPositionIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getDepthFirstOrderedPks(
					categoryHierarchy,
					(o1, o2) -> {
						int pos1 = categoryPositionIndex.get(o1.getPrimaryKeyOrThrowException());
						int pos2 = categoryPositionIndex.get(o2.getPrimaryKeyOrThrowException());
						return Integer.compare(pos2, pos1);
					},
					categoryIndex
				);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow(),
					Comparator.reverseOrder()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal custom order via reference attribute (prefetch)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalByAttributeReferencedEntityAttributeInAscendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		IntIntMap categoryPositionIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getDepthFirstOrderedPks(
					categoryHierarchy,
					(o1, o2) -> {
						int pos1 = categoryPositionIndex.get(o1.getPrimaryKeyOrThrowException());
						int pos2 = categoryPositionIndex.get(o2.getPrimaryKeyOrThrowException());
						return Integer.compare(pos2, pos1);
					},
					categoryIndex
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> selectedProducts.contains(entity.getPrimaryKeyOrThrowException()))
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						if (selectedProducts.contains(i)) {
							expectedOrder[index++] = i;
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal custom order via reference attribute (descending prefetch)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalByAttributeReferencedEntityAttributeInDescendingOrderUsingPrefetch(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		IntIntMap categoryPositionIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProducts.toArray())
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
								attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
							)
						),
						require(
							page(1, 60),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final int[] orderedCategoryIds = getDepthFirstOrderedPks(
					categoryHierarchy,
					(o1, o2) -> {
						int pos1 = categoryPositionIndex.get(o1.getPrimaryKeyOrThrowException());
						int pos2 = categoryPositionIndex.get(o2.getPrimaryKeyOrThrowException());
						return Integer.compare(pos2, pos1);
					},
					categoryIndex
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow(),
					Comparator.reverseOrder()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> selectedProducts.contains(entity.getPrimaryKeyOrThrowException()))
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						if (selectedProducts.contains(i)) {
							expectedOrder[index++] = i;
						}
					}
				}

				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(60).toArray()
				);
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal custom order via reference attribute (plus pagination and skipping)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalByAttributeReferencedEntityAttributeInAscendingOrderWithPaginationAndSkipping(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		IntIntMap categoryPositionIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int[] orderedCategoryIds = getDepthFirstOrderedPks(
					categoryHierarchy,
					(o1, o2) -> {
						int pos1 = categoryPositionIndex.get(o1.getPrimaryKeyOrThrowException());
						int pos2 = categoryPositionIndex.get(o2.getPrimaryKeyOrThrowException());
						return Integer.compare(pos2, pos1);
					},
					categoryIndex
				);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				int pageNumber = 1;
				int consumed = 0;
				EvitaResponse<EntityReference> result;
				do {
					result = session.query(
						query(
							collection(Entities.PRODUCT),
							orderBy(
								referenceProperty(
									Entities.CATEGORY,
									traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
									attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
								)
							),
							require(
								page(pageNumber, 7, spacing(gap(1, "$pageNumber % 2 == 0"))),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					);

					int expectedPageSize = pageNumber % 2 == 0 ? 6 : 7;
					assertSortedResultEquals(
						result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
						Arrays.stream(expectedOrder).skip(consumed).limit(expectedPageSize).toArray()
					);

					consumed += expectedPageSize;
					pageNumber++;
				} while (result.getRecordPage().hasNext());

				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal custom order via reference attribute (desc plus pagination and skipping)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalByAttributeReferencedEntityAttributeDescendingOrderWithPaginationAndSkipping(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		IntIntMap categoryPositionIndex
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int[] orderedCategoryIds = getDepthFirstOrderedPks(
					categoryHierarchy,
					(o1, o2) -> {
						int pos1 = categoryPositionIndex.get(o1.getPrimaryKeyOrThrowException());
						int pos2 = categoryPositionIndex.get(o2.getPrimaryKeyOrThrowException());
						return Integer.compare(pos2, pos1);
					},
					categoryIndex
				);

				// create expected order of products
				final int[] expectedOrder = new int[products.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow(),
					Comparator.reverseOrder()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					expectedOrder[index++] = epk;
					sortedProducts.add(epk);
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						expectedOrder[index++] = i;
					}
				}

				int pageNumber = 1;
				int consumed = 0;
				EvitaResponse<EntityReference> result;
				do {
					result = session.query(
						query(
							collection(Entities.PRODUCT),
							orderBy(
								referenceProperty(
									Entities.CATEGORY,
									traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
									attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
								)
							),
							require(
								page(pageNumber, 7, spacing(gap(1, "$pageNumber % 2 == 0"))),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					);

					int expectedPageSize = pageNumber % 2 == 0 ? 6 : 7;
					assertSortedResultEquals(
						result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
						Arrays.stream(expectedOrder).skip(consumed).limit(expectedPageSize).toArray()
					);

					consumed += expectedPageSize;
					pageNumber++;
				} while (result.getRecordPage().hasNext());

				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal custom order via reference attribute (prefetch plus pagination and skipping)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalByAttributeReferencedEntityAttributeInAscendingOrderUsingPrefetchWithPaginationAndSkipping(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		IntIntMap categoryPositionIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int[] orderedCategoryIds = getDepthFirstOrderedPks(
					categoryHierarchy,
					(o1, o2) -> {
						int pos1 = categoryPositionIndex.get(o1.getPrimaryKeyOrThrowException());
						int pos2 = categoryPositionIndex.get(o2.getPrimaryKeyOrThrowException());
						return Integer.compare(pos2, pos1);
					},
					categoryIndex
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> selectedProducts.contains(entity.getPrimaryKeyOrThrowException()))
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						if (selectedProducts.contains(i)) {
							expectedOrder[index++] = i;
						}
					}
				}

				int pageNumber = 1;
				int consumed = 0;
				EvitaResponse<EntityReference> result;
				do {
					result = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(selectedProducts.toArray())
							),
							orderBy(
								referenceProperty(
									Entities.CATEGORY,
									traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
									attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC)
								)
							),
							require(
								page(pageNumber, 7, spacing(gap(1, "$pageNumber % 2 == 0"))),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
							)
						),
						EntityReference.class
					);

					int expectedPageSize = pageNumber % 2 == 0 ? 6 : 7;
					assertSortedResultEquals(
						result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
						Arrays.stream(expectedOrder).skip(consumed).limit(expectedPageSize).toArray()
					);

					consumed += expectedPageSize;
					pageNumber++;
				} while (result.getRecordPage().hasNext());

				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order by depth first category traversal custom order via reference attribute (descending prefetch plus pagination and skipping)")
	@UseDataSet(CHAINED_ELEMENTS)
	@Test
	void shouldSortProductsByCategoryDepthFirstTraversalByAttributeReferencedEntityAttributeInDescendingOrderUsingPrefetchWithPaginationAndSkipping(
		Evita evita,
		Map<Integer, SealedEntity> products,
		Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		IntIntMap categoryPositionIndex
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int[] orderedCategoryIds = getDepthFirstOrderedPks(
					categoryHierarchy,
					(o1, o2) -> {
						int pos1 = categoryPositionIndex.get(o1.getPrimaryKeyOrThrowException());
						int pos2 = categoryPositionIndex.get(o2.getPrimaryKeyOrThrowException());
						return Integer.compare(pos2, pos1);
					},
					categoryIndex
				);

				// create expected order of products
				final int[] expectedOrder = new int[selectedProducts.size()];
				int index = 0;

				final Predicate<ReferenceContract> referencePredicate = ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR) != null;
				final Comparator<SealedEntity> categoryComparator = Comparator
					.comparing(
						entity -> entity.getReferences(Entities.CATEGORY)
							.stream()
							.filter(referencePredicate)
							.findFirst()
							.map(ref -> ArrayUtils.indexOf(ref.getReferencedPrimaryKey(), orderedCategoryIds))
							.orElseThrow()
					);
				final Comparator<SealedEntity> attributeComparator = categoryComparator.thenComparing(
					entity -> entity.getReferences(Entities.CATEGORY)
						.stream()
						.filter(referencePredicate)
						.findFirst()
						.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
						.orElseThrow(),
					Comparator.reverseOrder()
				);
				final Comparator<SealedEntity> pkComparator = attributeComparator.thenComparingInt(
					EntityClassifier::getPrimaryKeyOrThrowException
				);

				final int[] sortedValues = products.values()
					.stream()
					.filter(entity -> selectedProducts.contains(entity.getPrimaryKeyOrThrowException()))
					.filter(entity -> entity.getReferences(Entities.CATEGORY).stream().anyMatch(referencePredicate))
					.sorted(pkComparator)
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();

				final IntSet sortedProducts = new IntHashSet(sortedValues.length);
				for (int epk : sortedValues) {
					if (selectedProducts.contains(epk)) {
						expectedOrder[index++] = epk;
						sortedProducts.add(epk);
					}
				}

				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					if (!sortedProducts.contains(i)) {
						if (selectedProducts.contains(i)) {
							expectedOrder[index++] = i;
						}
					}
				}

				int pageNumber = 1;
				int consumed = 0;
				EvitaResponse<EntityReference> result;
				do {
					result = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(selectedProducts.toArray())
							),
							orderBy(
								referenceProperty(
									Entities.CATEGORY,
									traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural(ATTRIBUTE_ORDER, OrderDirection.DESC)),
									attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC)
								)
							),
							require(
								page(pageNumber, 7, spacing(gap(1, "$pageNumber % 2 == 0"))),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING)
							)
						),
						EntityReference.class
					);

					int expectedPageSize = pageNumber % 2 == 0 ? 6 : 7;
					assertSortedResultEquals(
						result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
						Arrays.stream(expectedOrder).skip(consumed).limit(expectedPageSize).toArray()
					);

					consumed += expectedPageSize;
					pageNumber++;
				} while (result.getRecordPage().hasNext());

				return null;
			}
		);
	}

	/**
	 * EntityReference is a record that encapsulates a reference to an entity along with a contract for referencing.
	 */
	record EntityReferenceDTO(
		@Nonnull SealedEntity entity,
		@Nonnull ReferenceContract reference
	) {
	}

}
