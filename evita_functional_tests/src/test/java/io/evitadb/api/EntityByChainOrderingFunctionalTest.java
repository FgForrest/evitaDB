/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api;

import com.github.javafaker.Faker;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
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
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ArrayUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
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
	private static final String REFERENCE_CATEGORY_PRODUCTS = "products";
	private static final String CHAINED_ELEMENTS = "chained-elements";
	private static final String CHAINED_ELEMENTS_MINIMAL = "chained-elements-minimal";
	private static final String ATTRIBUTE_CATEGORY_MARKET = "market";
	private static final String ATTRIBUTE_INCEPTION_YEAR = "inceptionYear";
	private static final String ATTRIBUTE_MARKET_INCEPTION_YEAR = "marketInceptionYear";
	private static final int SEED = 40;
	private final static int PRODUCT_COUNT = 100;
	private final static int[] PRODUCT_ORDER;

	static {
		PRODUCT_ORDER = new int[PRODUCT_COUNT];
		for (int i = 1; i <= PRODUCT_COUNT; i++) {
			PRODUCT_ORDER[i - 1] = i;
		}

		ArrayUtils.shuffleArray(new Random(SEED), PRODUCT_ORDER, PRODUCT_COUNT);
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
		final String o1Market = o1Ref.getAttribute(ATTRIBUTE_CATEGORY_MARKET);
		final String o2Market = o2Ref.getAttribute(ATTRIBUTE_CATEGORY_MARKET);
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
	 * Sorts the products in a category based on the category order attribute.
	 *
	 * @param productsInCategory a map where the key is the category ID (non-null) and the value is a list of sealed entities (nullable).
	 */
	private static void sortProductsInCategory(@Nonnull Map<Integer, List<SealedEntity>> productsInCategory) {
		productsInCategory.forEach((key, value) -> {
			if (value != null) {
				// we rely on ChainIndex correctness - it's tested elsewhere
				final ChainIndex chainIndex = new ChainIndex(new AttributeKey(ATTRIBUTE_CATEGORY_ORDER));
				for (SealedEntity entity : value) {
					final ReferenceContract reference = entity.getReference(Entities.CATEGORY, key).orElseThrow();
					chainIndex.upsertPredecessor(reference.getAttribute(ATTRIBUTE_CATEGORY_ORDER), entity.getPrimaryKey());
				}
				// this is not much effective, but enough for a test
				final int[] sortedRecordIds = chainIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds();
				value.sort(
					(a, b) -> {
						final int aPos = ArrayUtils.indexOf(a.getPrimaryKey(), sortedRecordIds);
						final int bPos = ArrayUtils.indexOf(b.getPrimaryKey(), sortedRecordIds);
						return Integer.compare(aPos, bPos);
					}
				);
			}
		});
	}

	/**
	 * Updates the order of the products within a category in the Evita session.
	 *
	 * @param session            the session to execute the update operations
	 * @param predecessorCreator a function that creates a predecessor for the specified reference
	 */
	private static void updateCategoryOrderOfTheProducts(
		@Nonnull EvitaSessionContract session,
		@Nonnull BiFunction<ReferenceContract, int[], Serializable> predecessorCreator
	) {
		final Random rnd = new Random(SEED);
		session.queryList(
				query(
					collection(Entities.CATEGORY),
					require(
						page(1, 100),
						entityFetch(
							referenceContent(REFERENCE_CATEGORY_PRODUCTS)
						)
					)
				),
				SealedEntity.class
			)
			.forEach(category -> {
				final int[] referencedProducts = category.getReferences(REFERENCE_CATEGORY_PRODUCTS)
					.stream()
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray();
				ArrayUtils.shuffleArray(rnd, referencedProducts, referencedProducts.length);
				final EntityBuilder categoryBuilder = category.openForWrite();
				category
					.getReferences(REFERENCE_CATEGORY_PRODUCTS)
					.forEach(
						reference -> {
							categoryBuilder.setReference(
								REFERENCE_CATEGORY_PRODUCTS,
								reference.getReferencedPrimaryKey(),
								whichIs -> whichIs.setAttribute(
									ATTRIBUTE_CATEGORY_ORDER,
									predecessorCreator.apply(reference, referencedProducts)
								)
							);
						}
					);
				session.upsertEntity(categoryBuilder);
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
	private static Map<Integer, SealedEntity> collectProductIndex(
		@Nonnull EvitaSessionContract session,
		@Nonnull List<EntityReference> storedProducts
	) {
		return storedProducts.stream()
			.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
			.collect(
				Collectors.toMap(
					SealedEntity::getPrimaryKey,
					Function.identity()
				)
			);
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

			final DataGenerator dataGenerator = new DataGenerator();

			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};

			final AtomicInteger index = new AtomicInteger();
			dataGenerator.registerValueGenerator(
				Entities.PRODUCT, ATTRIBUTE_ORDER,
				faker -> {
					final int ix = index.incrementAndGet();
					final int position = ArrayUtils.indexOf(ix, PRODUCT_ORDER);
					return position == 0 ? Predecessor.HEAD : new Predecessor(PRODUCT_ORDER[position - 1]);
				}
			);
			// we need to update the order in second pass
			dataGenerator.registerValueGenerator(
				Entities.PRODUCT, ATTRIBUTE_CATEGORY_ORDER,
				faker -> Predecessor.HEAD
			);

			// we need to create category schema first
			final SealedEntitySchema categorySchema = dataGenerator.getSampleCategorySchema(
				session,
				schemaBuilder -> {
					schemaBuilder
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
							whichIs -> whichIs.indexed()
								.withAttribute(ATTRIBUTE_CATEGORY_ORDER, Predecessor.class, AttributeSchemaEditor::sortable)
								.withAttribute(ATTRIBUTE_CATEGORY_MARKET, String.class, thatIs -> thatIs.nullable().sortable())
								.withAttribute(ATTRIBUTE_INCEPTION_YEAR, Integer.class, thatIs -> thatIs.nullable().sortable())
								.withSortableAttributeCompound(
									ATTRIBUTE_MARKET_INCEPTION_YEAR,
									new AttributeElement(ATTRIBUTE_CATEGORY_MARKET, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
									new AttributeElement(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
								)
						);
					// we need only category references in this test
					for (String referenceName : schemaBuilder.getReferences().keySet()) {
						if (!referenceName.equals(Entities.CATEGORY)) {
							schemaBuilder.withoutReferenceTo(referenceName);
						}
					}
				}
			);

			// and now data for both of them (since they are intertwined via reflected reference)
			final int categoryCount = 10;
			dataGenerator.generateEntities(
					categorySchema,
					randomEntityPicker,
					SEED
				)
				.limit(categoryCount)
				.forEach(session::upsertEntity);

			final List<EntityReference> storedProducts = dataGenerator.generateEntities(
					productSchema,
					(s, faker) -> faker.random().nextInt(1, categoryCount + 1),
					SEED
				)
				.limit(PRODUCT_COUNT)
				.map(session::upsertEntity)
				.toList();

			// second pass - update the category order of the products
			updateCategoryOrderOfTheProducts(
				session,
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
				.collect(
					Collectors.groupingBy(
						it -> it.getReferences(Entities.CATEGORY)
							.stream()
							.findFirst()
							.orElseThrow()
							.getReferencedPrimaryKey(),
						Collectors.toList()
					)
				);

			// now we need to sort the products in the category
			sortProductsInCategory(productsInCategory);
			return new DataCarrier(
				REFERENCE_CATEGORY_PRODUCTS, products,
				"productsInCategory", productsInCategory
			);
		});
	}

	@Nullable
	@DataSet(value = CHAINED_ELEMENTS_MINIMAL, readOnly = false, destroyAfterClass = true)
	DataCarrier setUpMinimal(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final DataGenerator dataGenerator = new DataGenerator();

			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};

			// we need to update the order in second pass
			dataGenerator.registerValueGenerator(
				Entities.CATEGORY, ATTRIBUTE_CATEGORY_ORDER,
				faker -> Predecessor.HEAD
			);

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
							whichIs -> whichIs.indexed()
								.withAttribute(ATTRIBUTE_CATEGORY_ORDER, Predecessor.class, AttributeSchemaEditor::sortable)
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
			updateCategoryOrderOfTheProducts(
				session,
				(reference, referencedProducts) -> {
					final int theIndex = ArrayUtils.indexOf(reference.getReferencedPrimaryKey(), referencedProducts);
					return theIndex == 0 ?
						Predecessor.HEAD : new Predecessor(referencedProducts[theIndex - 1]);
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
			sortProductsInCategory(productsInCategory);

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
					productsInCategory.get(categoryId).stream().mapToInt(SealedEntity::getPrimaryKey).limit(20).toArray()
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
					productsInCategory.get(categoryId).stream().mapToInt(SealedEntity::getPrimaryKey).toArray()
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
			.mapToInt(EntityContract::getPrimaryKey)
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
			.mapToInt(EntityContract::getPrimaryKey)
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
							.mapToInt(EntityContract::getPrimaryKey)
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
							.mapToInt(EntityContract::getPrimaryKey)
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
			.mapToInt(EntityContract::getPrimaryKey)
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
			.mapToInt(EntityContract::getPrimaryKey)
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
						.mapToInt(SealedEntity::getPrimaryKey)
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
						.mapToInt(SealedEntity::getPrimaryKey)
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
			.mapToInt(EntityContract::getPrimaryKey)
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
					.mapToInt(SealedEntity::getPrimaryKey)
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
			.mapToInt(EntityContract::getPrimaryKey)
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
					.mapToInt(SealedEntity::getPrimaryKey)
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
			.mapToInt(EntityContract::getPrimaryKey)
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
			.mapToInt(EntityContract::getPrimaryKey)
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
					productsInCategory.get(categoryId).stream().mapToInt(SealedEntity::getPrimaryKey).limit(20).toArray()
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
					productsInCategory.get(categoryId).stream().mapToInt(SealedEntity::getPrimaryKey).toArray()
				);
				assertSortedResultEquals(
					result.getRecordData().stream().map(EntityReference::getPrimaryKey).toList(),
					Arrays.stream(expectedOrder).limit(20).toArray()
				);
				return null;
			}
		);
	}

	/**
	 * EntityReference is a record that encapsulates a reference to an entity along with a contract for referencing.
	 */
	private record EntityReferenceDTO(
		@Nonnull SealedEntity entity,
		@Nonnull ReferenceContract reference
	) {
	}

}
