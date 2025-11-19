/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import com.carrotsearch.hppc.cursors.IntCursor;
import com.github.javafaker.Faker;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.DataCarrier.Tuple;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ArrayUtils;
import lombok.extern.slf4j.Slf4j;
import one.edee.oss.pmptt.model.Hierarchy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.api.functional.attribute.EntityByChainOrderingFunctionalTest.*;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.utils.AssertionUtils.assertSortedResultEquals;
import static java.util.Optional.ofNullable;

/**
 * This test verifies the behavior related to the situation when hierarchy is traversed and sorted on different levels
 * using different sorters or their combination.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Evita entity ordering by multiple aspects within traversed hierarchy")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EntityByMultipleOrderingFunctionalTest {
	private static final String HIERARCHY_MULTIPLE_ASPECT_ORDERING = "hierarchy-multiple-aspect-ordering";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_CATEGORY_ORDER = "categoryOrder";
	private static final String REFERENCE_CATEGORY_PRODUCTS = "products";
	private static final String ATTRIBUTE_MARKET = "market";
	private static final String ATTRIBUTE_INCEPTION_YEAR = "inceptionYear";
	private static final int SEED = 40;
	private final static int PRODUCT_COUNT = 100;
	private final static int CATEGORY_COUNT = 10;
	private final static int[] PRODUCT_ORDER;

	static {
		PRODUCT_ORDER = new int[PRODUCT_COUNT];
		for (int i = 1; i <= PRODUCT_COUNT; i++) {
			PRODUCT_ORDER[i - 1] = i;
		}

		ArrayUtils.shuffleArray(new Random(SEED), PRODUCT_ORDER, PRODUCT_COUNT);
	}

	@Nullable
	@DataSet(value = HIERARCHY_MULTIPLE_ASPECT_ORDERING, readOnly = false, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final DataGenerator dataGenerator = new DataGenerator.Builder()
				.registerValueGenerator(
					Entities.PRODUCT, ATTRIBUTE_INCEPTION_YEAR,
					(refKey, faker) -> refKey.primaryKey() % 3 == 0 ? faker.number().numberBetween(1900, 2025) : null
				)
				.registerValueGenerator(
					Entities.PRODUCT, ATTRIBUTE_MARKET,
					(refKey, faker) -> refKey.primaryKey() % 4 == 0 ? faker.country().name() : null
				)
				// we need to update the order in second pass
				.registerValueGenerator(
					Entities.PRODUCT, ATTRIBUTE_CATEGORY_ORDER,
					(refKey, faker) -> refKey.primaryKey() % 7 == 0 ? Predecessor.HEAD : null
				)
				// generate order attribute for category
				.registerValueGenerator(
					Entities.CATEGORY, ATTRIBUTE_ORDER,
					faker -> Predecessor.HEAD
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

			// then the product schema
			final SealedEntitySchema productSchema = dataGenerator.getSampleProductSchema(
				session,
				schemaBuilder -> {
					schemaBuilder
						.withReferenceToEntity(
							Entities.CATEGORY,
							Entities.CATEGORY,
							Cardinality.EXACTLY_ONE,
							whichIs -> whichIs.indexedForFilteringAndPartitioning()
								.withAttribute(ATTRIBUTE_CATEGORY_ORDER, Predecessor.class, thatIs -> thatIs.nullable().sortable())
								.withAttribute(ATTRIBUTE_MARKET, String.class, thatIs -> thatIs.nullable().sortable())
								.withAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class, thatIs -> thatIs.nullable().sortable())
						);
					// we need only category + brand references in this test
					for (String referenceName : schemaBuilder.getReferences().keySet()) {
						if (!referenceName.equals(Entities.CATEGORY)) {
							schemaBuilder.withoutReferenceTo(referenceName);
						}
					}
				}
			);

			// and now data for both of them (since they are intertwined via reflected reference)
			final List<EntityReferenceContract> storedProducts = dataGenerator.generateEntities(
					productSchema,
					randomEntityPicker,
					SEED
				)
				.limit(PRODUCT_COUNT)
				.map(session::upsertEntity)
				.toList();

			// second pass - update the category order of the products
			updateReferenceAttributeInProduct(
				session,
				Entities.CATEGORY, REFERENCE_CATEGORY_PRODUCTS, ATTRIBUTE_CATEGORY_ORDER,
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
				.flatMap(it -> it.getReferences(Entities.CATEGORY).stream().map(ref -> new EntityReferenceDTO(it, ref)))
				.collect(
					Collectors.groupingBy(
						it -> it.reference().getReferencedPrimaryKey(),
						Collectors.mapping(EntityReferenceDTO::entity, Collectors.toList())
					)
				);

			// now we need to sort the products in the category
			sortProductsInByReferenceAttributeOfChainableType(productsInCategory, Entities.CATEGORY, ATTRIBUTE_CATEGORY_ORDER);

			final IntIntMap categoryPositionIndex = new IntIntHashMap();
			for (int[] order : categoryOrdering.values()) {
				for (int i = 0; i < order.length; i++) {
					categoryPositionIndex.put(order[i], i);
				}
			}

			return new DataCarrier(
				new Tuple("products", products),
				new Tuple("productsInCategory", productsInCategory),
				new Tuple("categoryHierarchy", categoryHierarchy),
				new Tuple("categoryPositionIndex", categoryPositionIndex),
				new Tuple("categoryIndex", categoryIndexByPk)
			);
		});
	}

	@DisplayName("The product should be returned in ascending order using traverse by hierarchy")
	@UseDataSet(HIERARCHY_MULTIPLE_ASPECT_ORDERING)
	@Test
	void shouldSortProductsByPredecessorAttributeInAscendingOrder(
		@Nonnull Evita evita,
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull Map<Integer, SealedEntity> categoryIndex,
		@Nonnull IntIntMap categoryPositionIndex,
		@Nonnull Map<Integer, List<SealedEntity>> productsInCategory
	) {
		final int[] sortedProducts = Arrays.stream(
				getDepthFirstOrderedPks(
					categoryHierarchy,
					Comparator.comparingInt(o -> categoryPositionIndex.get(o.getPrimaryKeyOrThrowException())),
					categoryIndex
				)
			)
			.flatMap(
				catId -> ofNullable(productsInCategory.get(catId))
					.stream()
					.flatMapToInt(
						categoryProducts -> {
							final int[] orderedProducts = categoryProducts.stream()
								.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
								.toArray();
							final Comparator<SealedEntity> comparator;
							if (catId % 3 == 0) {
								comparator = Comparator.comparing(
									it -> it.getReference(Entities.CATEGORY, catId)
										.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
										.orElseThrow()
								);
							} else if (catId % 4 == 0) {
								comparator = Comparator.comparing(
									it -> it.getReference(Entities.CATEGORY, catId)
										.map(ref -> ref.getAttribute(ATTRIBUTE_MARKET, String.class))
										.orElseThrow()
								);
							} else if (catId % 7 == 0) {
								comparator = Comparator.comparing(
									it -> ArrayUtils.indexOf(it.getPrimaryKeyOrThrowException(), orderedProducts)
								);
							} else {
								comparator = Comparator.comparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
							}
							return categoryProducts.stream()
								.sorted(comparator)
								.mapToInt(SealedEntity::getPrimaryKeyOrThrowException);
						}
					)
			)
			.toArray();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int pageNumber = 1; pageNumber <= sortedProducts.length / 20; pageNumber++) {
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(Entities.PRODUCT),
							orderBy(
								referenceProperty(
									Entities.CATEGORY,
									traverseByEntityProperty(
										TraversalMode.DEPTH_FIRST,
										attributeNatural(ATTRIBUTE_ORDER, OrderDirection.ASC)
									),
									attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.ASC),
									attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC),
									attributeNatural(ATTRIBUTE_MARKET, OrderDirection.ASC),
									entityPrimaryKeyNatural(OrderDirection.ASC)
								)
							),
							require(
								page(pageNumber, 20),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					);
					assertSortedResultEquals(
						result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
						Arrays.copyOfRange(sortedProducts, (pageNumber - 1) * 20, Math.min(pageNumber * 20, sortedProducts.length))
					);
				}
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order using traverse by hierarchy with records lacking attributes at the end")
	@UseDataSet(HIERARCHY_MULTIPLE_ASPECT_ORDERING)
	@Test
	void shouldSortProductsByPredecessorAttributeInAscendingOrderWithNoPrimaryKeySorting(
		@Nonnull Evita evita,
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull Map<Integer, SealedEntity> categoryIndex,
		@Nonnull IntIntMap categoryPositionIndex,
		@Nonnull Map<Integer, List<SealedEntity>> productsInCategory
	) {
		final IntSet productsWithMissingAttributes = new IntHashSet();
		final int[] sortedProductsFirstRound = Arrays.stream(
				getDepthFirstOrderedPks(
					categoryHierarchy,
					Comparator.comparingInt(o -> categoryPositionIndex.get(o.getPrimaryKeyOrThrowException())),
					categoryIndex
				)
			)
			.flatMap(
				catId -> ofNullable(productsInCategory.get(catId))
					.stream()
					.flatMapToInt(
						categoryProducts -> {
							final int[] orderedProducts = categoryProducts.stream()
								.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
								.toArray();
							final Comparator<SealedEntity> comparator;
							if (catId % 3 == 0) {
								comparator = Comparator.comparing(
									it -> it.getReference(Entities.CATEGORY, catId)
										.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
										.orElseThrow()
								);
							} else if (catId % 4 == 0) {
								comparator = Comparator.comparing(
									it -> it.getReference(Entities.CATEGORY, catId)
										.map(ref -> ref.getAttribute(ATTRIBUTE_MARKET, String.class))
										.orElseThrow()
								);
							} else if (catId % 7 == 0) {
								comparator = Comparator.comparing(
									it -> ArrayUtils.indexOf(it.getPrimaryKeyOrThrowException(), orderedProducts)
								);
							} else {
								comparator = (o1, o2) -> {
									productsWithMissingAttributes.add(o1.getPrimaryKeyOrThrowException());
									productsWithMissingAttributes.add(o2.getPrimaryKeyOrThrowException());
									return Integer.compare(o1.getPrimaryKeyOrThrowException(), o2.getPrimaryKeyOrThrowException());
								};
							}
							return categoryProducts.stream()
								.sorted(comparator)
								.mapToInt(SealedEntity::getPrimaryKeyOrThrowException);
						}
					)
			)
			.toArray();
		final int[] sortedProducts = new int[sortedProductsFirstRound.length];
		int index = 0;
		for (int pk : sortedProductsFirstRound) {
			if (!productsWithMissingAttributes.contains(pk)) {
				sortedProducts[index++] = pk;
			}
		}
		for (IntCursor next : productsWithMissingAttributes) {
			sortedProducts[index++] = next.value;
		}
		Arrays.sort(sortedProducts, sortedProductsFirstRound.length - productsWithMissingAttributes.size(), sortedProducts.length);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int pageNumber = 1; pageNumber <= sortedProducts.length / 20; pageNumber++) {
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(Entities.PRODUCT),
							orderBy(
								referenceProperty(
									Entities.CATEGORY,
									traverseByEntityProperty(
										TraversalMode.DEPTH_FIRST,
										attributeNatural(ATTRIBUTE_ORDER, OrderDirection.ASC)
									),
									attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.ASC),
									attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC),
									attributeNatural(ATTRIBUTE_MARKET, OrderDirection.ASC)
								)
							),
							require(
								page(pageNumber, 20),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					);
					assertSortedResultEquals(
						result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
						Arrays.copyOfRange(sortedProducts, (pageNumber - 1) * 20, Math.min(pageNumber * 20, sortedProducts.length))
					);
				}
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order using traverse by hierarchy (prefetch)")
	@UseDataSet(HIERARCHY_MULTIPLE_ASPECT_ORDERING)
	@Test
	void shouldSortProductsByPredecessorAttributeInAscendingOrderViaPrefetch(
		@Nonnull Evita evita,
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull Map<Integer, SealedEntity> categoryIndex,
		@Nonnull IntIntMap categoryPositionIndex,
		@Nonnull Map<Integer, List<SealedEntity>> productsInCategory
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		final int[] sortedProducts = Arrays.stream(
				getDepthFirstOrderedPks(
					categoryHierarchy,
					Comparator.comparingInt(o -> categoryPositionIndex.get(o.getPrimaryKeyOrThrowException())),
					categoryIndex
				)
			)
			.flatMap(
				catId -> ofNullable(productsInCategory.get(catId))
					.stream()
					.flatMapToInt(
						categoryProducts -> {
							final List<SealedEntity> filteredProducts = categoryProducts.stream()
								.filter(it -> selectedProducts.contains(it.getPrimaryKeyOrThrowException()))
								.toList();
							final int[] orderedProducts = filteredProducts.stream()
								.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
								.toArray();
							final Comparator<SealedEntity> comparator;
							if (catId % 3 == 0) {
								comparator = Comparator.comparing(
									it -> it.getReference(Entities.CATEGORY, catId)
										.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
										.orElseThrow()
								);
							} else if (catId % 4 == 0) {
								comparator = Comparator.comparing(
									it -> it.getReference(Entities.CATEGORY, catId)
										.map(ref -> ref.getAttribute(ATTRIBUTE_MARKET, String.class))
										.orElseThrow()
								);
							} else if (catId % 7 == 0) {
								comparator = Comparator.comparing(
									it -> ArrayUtils.indexOf(it.getPrimaryKeyOrThrowException(), orderedProducts)
								);
							} else {
								comparator = Comparator.comparingInt(EntityClassifier::getPrimaryKeyOrThrowException);
							}
							return filteredProducts.stream()
								.sorted(comparator)
								.mapToInt(SealedEntity::getPrimaryKeyOrThrowException);
						}
					)
			)
			.toArray();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int pageNumber = 1; pageNumber <= sortedProducts.length / 20; pageNumber++) {
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(selectedProducts.toArray())
							),
							orderBy(
								referenceProperty(
									Entities.CATEGORY,
									traverseByEntityProperty(
										TraversalMode.DEPTH_FIRST,
										attributeNatural(ATTRIBUTE_ORDER, OrderDirection.ASC)
									),
									attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.ASC),
									attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC),
									attributeNatural(ATTRIBUTE_MARKET, OrderDirection.ASC),
									entityPrimaryKeyNatural(OrderDirection.ASC)
								)
							),
							require(
								page(pageNumber, 20),
								debug(DebugMode.PREFER_PREFETCHING)
							)
						),
						EntityReference.class
					);
					assertSortedResultEquals(
						result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
						Arrays.copyOfRange(sortedProducts, (pageNumber - 1) * 20, Math.min(pageNumber * 20, sortedProducts.length))
					);
				}
				return null;
			}
		);
	}

	@DisplayName("The product should be returned in ascending order using traverse by hierarchy with records lacking attributes at the end (prefetch)")
	@UseDataSet(HIERARCHY_MULTIPLE_ASPECT_ORDERING)
	@Test
	void shouldSortProductsByPredecessorAttributeInAscendingOrderWithNoPrimaryKeySortingViaPrefetch(
		@Nonnull Evita evita,
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull Map<Integer, SealedEntity> categoryIndex,
		@Nonnull IntIntMap categoryPositionIndex,
		@Nonnull Map<Integer, List<SealedEntity>> productsInCategory
	) {
		final IntSet selectedProducts = new IntHashSet(PRODUCT_COUNT / 2);
		for (int i = 1; i <= PRODUCT_COUNT; i = i + 2) {
			selectedProducts.add(i);
		}
		final IntSet productsWithMissingAttributes = new IntHashSet();
		final int[] sortedProductsFirstRound = Arrays.stream(
				getDepthFirstOrderedPks(
					categoryHierarchy,
					Comparator.comparingInt(o -> categoryPositionIndex.get(o.getPrimaryKeyOrThrowException())),
					categoryIndex
				)
			)
			.flatMap(
				catId -> ofNullable(productsInCategory.get(catId))
					.stream()
					.flatMapToInt(
						categoryProducts -> {
							final List<SealedEntity> filteredProducts = categoryProducts.stream()
								.filter(it -> selectedProducts.contains(it.getPrimaryKeyOrThrowException()))
								.toList();
							final int[] orderedProducts = filteredProducts.stream()
								.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
								.toArray();
							final Comparator<SealedEntity> comparator;
							if (catId % 3 == 0) {
								comparator = Comparator.comparing(
									it -> it.getReference(Entities.CATEGORY, catId)
										.map(ref -> ref.getAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class))
										.orElseThrow()
								);
							} else if (catId % 4 == 0) {
								comparator = Comparator.comparing(
									it -> it.getReference(Entities.CATEGORY, catId)
										.map(ref -> ref.getAttribute(ATTRIBUTE_MARKET, String.class))
										.orElseThrow()
								);
							} else if (catId % 7 == 0) {
								comparator = Comparator.comparing(
									it -> ArrayUtils.indexOf(it.getPrimaryKeyOrThrowException(), orderedProducts)
								);
							} else {
								comparator = (o1, o2) -> {
									productsWithMissingAttributes.add(o1.getPrimaryKeyOrThrowException());
									productsWithMissingAttributes.add(o2.getPrimaryKeyOrThrowException());
									return Integer.compare(o1.getPrimaryKeyOrThrowException(), o2.getPrimaryKeyOrThrowException());
								};
							}
							return filteredProducts.stream()
								.sorted(comparator)
								.mapToInt(SealedEntity::getPrimaryKeyOrThrowException);
						}
					)
			)
			.toArray();
		final int[] sortedProducts = new int[sortedProductsFirstRound.length];
		int index = 0;
		for (int pk : sortedProductsFirstRound) {
			if (!productsWithMissingAttributes.contains(pk)) {
				sortedProducts[index++] = pk;
			}
		}
		for (IntCursor next : productsWithMissingAttributes) {
			sortedProducts[index++] = next.value;
		}
		Arrays.sort(sortedProducts, sortedProductsFirstRound.length - productsWithMissingAttributes.size(), sortedProducts.length);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int pageNumber = 1; pageNumber <= sortedProducts.length / 20; pageNumber++) {
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(selectedProducts.toArray())
							),
							orderBy(
								referenceProperty(
									Entities.CATEGORY,
									traverseByEntityProperty(
										TraversalMode.DEPTH_FIRST,
										attributeNatural(ATTRIBUTE_ORDER, OrderDirection.ASC)
									),
									attributeNatural(ATTRIBUTE_CATEGORY_ORDER, OrderDirection.ASC),
									attributeNatural(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.ASC),
									attributeNatural(ATTRIBUTE_MARKET, OrderDirection.ASC)
								)
							),
							require(
								page(pageNumber, 20),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					);
					assertSortedResultEquals(
						result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
						Arrays.copyOfRange(sortedProducts, (pageNumber - 1) * 20, Math.min(pageNumber * 20, sortedProducts.length))
					);
				}
				return null;
			}
		);
	}

}
