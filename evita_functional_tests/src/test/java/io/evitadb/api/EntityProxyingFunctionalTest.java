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

import io.evitadb.api.EvitaSessionContract.DeletedHierarchy;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.mock.CategoryInterface;
import io.evitadb.api.mock.ProductCategoryInterface;
import io.evitadb.api.mock.ProductInterface;
import io.evitadb.api.mock.TestEntity;
import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;
import io.evitadb.utils.ArrayUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the ability to proxy an entity into an arbitrary interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Evita entity proxying functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EntityProxyingFunctionalTest extends AbstractFiftyProductsFunctionalTest {
	private static final String FIFTY_PRODUCTS = "FiftyProxyProducts";
	private static final Locale CZECH_LOCALE = new Locale("cs", "CZ");

	private static void assertCategoryEntityReferences(
		@Nonnull Stream<EntityReference> categoryReferences,
		@Nonnull int[] expectedCategoryIds
	) {
		assertNotNull(categoryReferences);
		final EntityReference[] references = categoryReferences
			.sorted()
			.toArray(EntityReference[]::new);

		assertEquals(expectedCategoryIds.length, references.length);
		assertArrayEquals(
			Arrays.stream(expectedCategoryIds)
				.mapToObj(it -> new EntityReference(Entities.CATEGORY, it))
				.toArray(EntityReference[]::new),
			references
		);
	}

	private static void assertCategoryIds(
		@Nonnull Stream<Integer> categoryIds,
		@Nonnull int[] expectedCategoryIds
	) {
		assertNotNull(categoryIds);
		final Integer[] references = categoryIds
			.sorted()
			.toArray(Integer[]::new);

		assertEquals(expectedCategoryIds.length, references.length);
		assertArrayEquals(
			Arrays.stream(expectedCategoryIds)
				.boxed()
				.toArray(Integer[]::new),
			references
		);
	}

	private static void assertCategories(
		@Nonnull Stream<CategoryInterface> categoryReferences,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nonnull int[] expectedCategoryIds,
		@Nullable Locale locale
	) {
		assertNotNull(categoryReferences);
		final CategoryInterface[] references = categoryReferences
			.sorted(Comparator.comparingInt(CategoryInterface::getId))
			.toArray(CategoryInterface[]::new);

		assertEquals(expectedCategoryIds.length, references.length);
		for (CategoryInterface reference : references) {
			assertCategory(
				reference,
				originalCategories.computeIfAbsent(reference.getId(), id -> {
					throw new AssertionError("Category with id " + id + " not found");
				}),
				locale
			);
		}
	}

	private static void assertCategory(
		@Nonnull CategoryInterface category,
		@Nonnull SealedEntity sealedEntity,
		@Nullable Locale locale
	) {
		assertEquals(TestEntity.CATEGORY, category.getEntityType());
		assertEquals(sealedEntity.getPrimaryKey(), category.getId());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_CODE), category.getCode());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_PRIORITY), category.getPriority());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_VALIDITY), category.getValidity());
		if (locale == null) {
			for (AttributeValue attributeValue : sealedEntity.getAttributeValues(DataGenerator.ATTRIBUTE_NAME)) {
				assertEquals(attributeValue.getValue(), category.getName(attributeValue.getKey().getLocale()));
			}
		} else {
			assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_NAME, locale), category.getName());
			assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_NAME, locale), category.getName(locale));
		}
	}

	private static void assertCategoryReferences(
		@Nonnull Stream<ProductCategoryInterface> categoryReferences,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nonnull int[] expectedCategoryIds,
		@Nullable Locale locale,
		boolean externalEntities
	) {
		assertNotNull(categoryReferences);
		final ProductCategoryInterface[] references = categoryReferences
			.sorted(Comparator.comparingInt(ProductCategoryInterface::getPrimaryKey))
			.toArray(ProductCategoryInterface[]::new);

		assertEquals(expectedCategoryIds.length, references.length);
		for (ProductCategoryInterface reference : references) {
			assertCategoryReference(
				reference,
				originalCategories.computeIfAbsent(reference.getPrimaryKey(), id -> {
					throw new AssertionError("Category with id " + id + " not found");
				}),
				locale,
				externalEntities
			);
		}
	}

	private static void assertCategoryReference(
		@Nonnull ProductCategoryInterface productCategory,
		@Nonnull SealedEntity sealedEntity,
		@Nullable Locale locale,
		boolean externalEntities
	) {
		assertEquals(sealedEntity.getPrimaryKey(), productCategory.getPrimaryKey());

		assertTrue(productCategory instanceof SealedEntityReferenceProxy);
		final ReferenceContract theReference = ((SealedEntityReferenceProxy) productCategory).getReference();
		assertEquals(theReference.getAttribute(DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY), productCategory.getOrderInCategory());

		if (locale == null) {
			for (AttributeValue attributeValue : theReference.getAttributeValues(ATTRIBUTE_CATEGORY_LABEL)) {
				assertEquals(attributeValue.getValue(), productCategory.getLabel(attributeValue.getKey().getLocale()));
			}
		} else {
			assertEquals(theReference.getAttribute(ATTRIBUTE_CATEGORY_LABEL, locale), productCategory.getLabel());
			assertEquals(theReference.getAttribute(ATTRIBUTE_CATEGORY_LABEL, locale), productCategory.getLabel(locale));
		}

		if (externalEntities) {
			assertCategory(productCategory.getCategory(), sealedEntity, locale);
			assertEquals(new EntityReference(Entities.CATEGORY, sealedEntity.getPrimaryKey()), productCategory.getCategoryReference());
			assertEquals(sealedEntity.getPrimaryKey(), productCategory.getCategoryReferencePrimaryKey());
		} else {
			assertNull(productCategory.getCategory());
		}

	}

	private static void assertCategoryParent(
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nonnull CategoryInterface category,
		@Nullable Locale locale
	) {
		final SealedEntity originalCategory = originalCategories.get(category.getId());
		if (originalCategory.getParent().isEmpty()) {
			assertNull(category.getParentId());
			assertNull(category.getParentEntityReference());
			assertNull(category.getParentEntity());
		} else {
			final int expectedParentId = originalCategory.getParent().getAsInt();
			assertEquals(
				expectedParentId,
				category.getParentId()
			);
			assertEquals(
				new EntityReference(Entities.CATEGORY, expectedParentId),
				category.getParentEntityReference()
			);
			assertCategory(category.getParentEntity(), originalCategories.get(expectedParentId), locale);
			assertCategoryParent(originalCategories, category.getParentEntity(), locale);
		}
	}

	private static void assertCategoryParents(
		@Nonnull Collection<CategoryInterface> categories,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nullable Locale locale
	) {
		for (CategoryInterface category : categories) {
			assertCategoryParent(originalCategories, category, locale);
		}
	}

	private static void assertProduct(
		@Nonnull SealedEntity originalProduct,
		@Nullable ProductInterface product,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nullable Locale locale,
		boolean externalEntities
	) {
		assertProductBasicData(originalProduct, product);
		assertProductAttributes(originalProduct, product, locale);
		assertEquals(new ReferencedFileSet(null), product.getReferencedFileSet());
		assertEquals(new ReferencedFileSet(null), product.getReferencedFileSetAsDifferentProperty());

		assertCategoryParents(product.getCategories(), originalCategories, locale);

		final int[] expectedCategoryIds = originalProduct.getReferences(Entities.CATEGORY)
			.stream()
			.mapToInt(ReferenceContract::getReferencedPrimaryKey)
			.toArray();

		assertCategoryIds(product.getCategoryIds().stream(), expectedCategoryIds);
		assertCategoryIds(product.getCategoryIdsAsList().stream(), expectedCategoryIds);
		assertCategoryIds(product.getCategoryIdsAsSet().stream(), expectedCategoryIds);
		assertCategoryIds(Arrays.stream(product.getCategoryIdsAsArray()).boxed(), expectedCategoryIds);

		assertCategoryEntityReferences(product.getCategoryReferences().stream(), expectedCategoryIds);
		assertCategoryEntityReferences(product.getCategoryReferencesAsList().stream(), expectedCategoryIds);
		assertCategoryEntityReferences(product.getCategoryReferencesAsSet().stream(), expectedCategoryIds);
		assertCategoryEntityReferences(Arrays.stream(product.getCategoryReferencesAsArray()), expectedCategoryIds);

		if (externalEntities) {
			assertCategories(product.getCategories().stream(), originalCategories, expectedCategoryIds, locale);
			assertCategories(product.getCategoriesAsList().stream(), originalCategories, expectedCategoryIds, locale);
			assertCategories(product.getCategoriesAsSet().stream(), originalCategories, expectedCategoryIds, locale);
			assertCategories(Arrays.stream(product.getCategoriesAsArray()), originalCategories, expectedCategoryIds, locale);
		} else {
			assertTrue(product.getCategories().isEmpty());
			assertTrue(product.getCategoriesAsList().isEmpty());
			assertTrue(product.getCategoriesAsSet().isEmpty());
			assertTrue(ArrayUtils.isEmpty(product.getCategoriesAsArray()));
		}

		assertCategoryReferences(product.getProductCategories().stream(), originalCategories, expectedCategoryIds, locale, externalEntities);
		assertCategoryReferences(product.getProductCategoriesAsList().stream(), originalCategories, expectedCategoryIds, locale, externalEntities);
		assertCategoryReferences(product.getProductCategoriesAsSet().stream(), originalCategories, expectedCategoryIds, locale, externalEntities);
		assertCategoryReferences(Arrays.stream(product.getProductCategoriesAsArray()), originalCategories, expectedCategoryIds, locale, externalEntities);

		assertThrows(ContextMissingException.class, product::getPriceForSale);

		final PriceContract[] allPricesForSale = product.getAllPricesForSale();
		final PriceContract[] expectedAllPricesForSale = originalProduct.getAllPricesForSale().toArray(PriceContract[]::new);
		assertEquals(expectedAllPricesForSale.length, allPricesForSale.length);
		assertArrayEquals(expectedAllPricesForSale, allPricesForSale);

		if (expectedAllPricesForSale.length > 0) {
			final PriceContract expectedPrice = expectedAllPricesForSale[0];
			assertEquals(
				expectedPrice,
				product.getPriceForSale(expectedPrice.getPriceList(), expectedPrice.getCurrency())
			);
			assertEquals(
				expectedPrice,
				product.getPriceForSale(expectedPrice.getPriceList(), expectedPrice.getCurrency(), expectedPrice.getValidity().getPreciseFrom())
			);

			assertArrayEquals(
				originalProduct.getAllPricesForSale()
					.stream()
					.filter(it -> it.getPriceList().equals(expectedPrice.getPriceList()))
					.toArray(PriceContract[]::new),
				product.getAllPricesForSale(expectedPrice.getPriceList())
			);

			assertArrayEquals(
				originalProduct.getAllPricesForSale()
					.stream()
					.filter(it -> it.getCurrency().equals(expectedPrice.getCurrency()))
					.toArray(PriceContract[]::new),
				product.getAllPricesForSale(expectedPrice.getCurrency())
			);

			assertArrayEquals(
				originalProduct.getAllPricesForSale()
					.stream()
					.filter(it -> it.getCurrency().equals(expectedPrice.getCurrency()) && it.getPriceList().equals(expectedPrice.getPriceList()))
					.toArray(PriceContract[]::new),
				product.getAllPricesForSale(expectedPrice.getPriceList(), expectedPrice.getCurrency())
			);
		}

		final PriceContract[] expectedAllPrices = originalProduct.getPrices().toArray(PriceContract[]::new);
		final PriceContract[] allPrices = Arrays.stream(product.getAllPricesAsArray())
			.toArray(PriceContract[]::new);

		assertEquals(expectedAllPrices.length, allPrices.length);
		assertArrayEquals(expectedAllPrices, allPrices);

		assertArrayEquals(expectedAllPrices, product.getAllPricesAsList().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.getAllPricesAsSet().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.getAllPrices().toArray(PriceContract[]::new));

		final Optional<PriceContract> first = Arrays.stream(expectedAllPrices).filter(it -> "basic".equals(it.getPriceList())).findFirst();
		if (first.isEmpty()) {
			assertNull(product.getBasicPrice());
		} else {
			assertEquals(
				first.get(),
				product.getBasicPrice()
			);
		}
	}

	private static void assertProductBasicData(@Nonnull SealedEntity originalProduct, @Nullable ProductInterface product) {
		assertNotNull(product);
		assertEquals(originalProduct.getPrimaryKey(), product.getPrimaryKey());
		assertEquals(originalProduct.getPrimaryKey(), product.getId());
		assertEquals(Entities.PRODUCT, product.getType());
		assertEquals(TestEntity.PRODUCT, product.getEntityType());
	}

	private static void assertProductAttributes(@Nonnull SealedEntity originalProduct, @Nonnull ProductInterface product, @Nullable Locale locale) {
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_CODE), product.getCode());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_NAME, locale), product.getName());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.getQuantity());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.getQuantityAsDifferentProperty());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_ALIAS), product.isAlias());
	}

	@DataSet(value = FIFTY_PRODUCTS, destroyAfterClass = true, readOnly = false)
	@Override
	DataCarrier setUp(Evita evita) {
		return super.setUp(evita);
	}

	@DisplayName("Should return entity schema directly or via model class")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void shouldReturnEntitySchema(EvitaSessionContract evitaSession) {
		assertNotNull(evitaSession.getEntitySchema(Entities.PRODUCT));
		assertNotNull(evitaSession.getEntitySchema(ProductInterface.class));
		assertEquals(
			evitaSession.getEntitySchema(Entities.PRODUCT),
			evitaSession.getEntitySchema(ProductInterface.class)
		);

		assertNotNull(evitaSession.getEntitySchemaOrThrow(Entities.PRODUCT));
		assertNotNull(evitaSession.getEntitySchemaOrThrow(ProductInterface.class));
		assertEquals(
			evitaSession.getEntitySchemaOrThrow(Entities.PRODUCT),
			evitaSession.getEntitySchemaOrThrow(ProductInterface.class)
		);
	}

	@DisplayName("Should get sealed entity")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void getSealedEntity(EvitaSessionContract evitaSession, List<SealedEntity> originalProducts) {
		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1)
			.findFirst()
			.orElseThrow();
		assertEquals(theProduct, evitaSession.getEntity(Entities.PRODUCT, 1, entityFetchAllContent()).orElseThrow());
	}

	@DisplayName("Should get custom entity model instance")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void getCustomEntity(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1)
			.findFirst()
			.orElseThrow();

		assertProduct(
			theProduct,
			evitaSession.getEntity(ProductInterface.class, 1, entityFetchAllContent()).orElse(null),
			originalCategories,
			null, false
		);
	}

	@DisplayName("Should enrich custom entity model instance")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void enrichCustomEntity(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1)
			.findFirst()
			.orElseThrow();

		final ProductInterface partiallyLoadedEntity = evitaSession
			.getEntity(ProductInterface.class, 1)
			.orElse(null);
		assertProduct(
			theProduct,
			evitaSession.enrichEntity(
				partiallyLoadedEntity,
				entityFetchAllContent()
			),
			originalCategories,
			null, false
		);
	}

	@DisplayName("Should limit custom entity model instance")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void limitCustomEntity(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts
	) {
		final SealedEntity originalProduct = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1)
			.findFirst()
			.orElseThrow();

		final ProductInterface partiallyLoadedEntity = evitaSession
			.getEntity(ProductInterface.class, 1, entityFetchAllContent())
			.orElse(null);


		final ProductInterface limitedProduct = evitaSession.enrichOrLimitEntity(
			partiallyLoadedEntity,
			attributeContentAll()
		);

		assertProductBasicData(originalProduct, limitedProduct);
		assertProductAttributes(originalProduct, limitedProduct, null);
		assertNull(limitedProduct.getReferencedFileSet());
		assertNull(limitedProduct.getReferencedFileSetAsDifferentProperty());
	}

	@DisplayName("Should return entity reference")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void queryOneEntityReference(EvitaSessionContract evitaSession) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				entityPrimaryKeyInSet(1)
			)
		);
		assertEquals(new EntityReference(Entities.PRODUCT, 1), evitaSession.queryOneEntityReference(query).orElseThrow());
		assertEquals(new EntityReference(Entities.PRODUCT, 1), evitaSession.queryOne(query, EntityReference.class).orElseThrow());
	}

	@DisplayName("Should return sealed entity")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void queryOneSealedEntity(EvitaSessionContract evitaSession, List<SealedEntity> originalProducts) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(1)),
			require(entityFetchAll())
		);

		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1)
			.findFirst()
			.orElseThrow();
		assertEquals(theProduct, evitaSession.queryOneSealedEntity(query).orElseThrow());
		assertEquals(theProduct, evitaSession.queryOne(query, SealedEntity.class).orElseThrow());
	}

	@DisplayName("Should return custom entity model instance")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void queryOneCustomEntity(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(1)),
			require(entityFetchAll())
		);

		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1)
			.findFirst()
			.orElseThrow();

		assertProduct(
			theProduct,
			evitaSession.queryOne(query, ProductInterface.class).orElse(null),
			originalCategories,
			null, false
		);
	}

	@DisplayName("Should return list of entity references")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void queryListOfEntityReference(EvitaSessionContract evitaSession) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				entityPrimaryKeyInSet(1, 2)
			)
		);
		assertArrayEquals(
			new EntityReference[] {
				new EntityReference(Entities.PRODUCT, 1),
				new EntityReference(Entities.PRODUCT, 2)
			},
			evitaSession.queryListOfEntityReferences(query).toArray()
		);
		assertArrayEquals(
			new EntityReference[] {
				new EntityReference(Entities.PRODUCT, 1),
				new EntityReference(Entities.PRODUCT, 2)
			},
			evitaSession.queryList(query, EntityReference.class).toArray()
		);
	}

	@DisplayName("Should return list of sealed entities")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void queryListOfSealedEntities(EvitaSessionContract evitaSession, List<SealedEntity> originalProducts) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(1, 2)),
			require(entityFetchAll())
		);

		final List<SealedEntity> theProducts = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1 || it.getPrimaryKey() == 2)
			.toList();
		assertEquals(theProducts, evitaSession.queryListOfSealedEntities(query));
		assertEquals(theProducts, evitaSession.queryList(query, SealedEntity.class));
	}

	@DisplayName("Should return list of custom entity model instances")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void queryListOfCustomEntities(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(1, 2)),
			require(entityFetchAll())
		);

		final List<SealedEntity> theProducts = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1 || it.getPrimaryKey() == 2)
			.toList();

		final List<ProductInterface> products = evitaSession.queryList(query, ProductInterface.class);
		for (int i = 0; i < theProducts.size(); i++) {
			final SealedEntity expectedProduct = theProducts.get(i);
			final ProductInterface actualProduct = products.get(i);
			assertProduct(
				expectedProduct,
				actualProduct,
				originalCategories,
				null, false
			);
		}
	}

	@DisplayName("Should query entity references")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void queryEntityReferences(EvitaSessionContract evitaSession) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				entityPrimaryKeyInSet(1, 2)
			)
		);
		assertArrayEquals(
			new EntityReference[] {
				new EntityReference(Entities.PRODUCT, 1),
				new EntityReference(Entities.PRODUCT, 2)
			},
			evitaSession.queryEntityReference(query).getRecordData().toArray()
		);
		assertArrayEquals(
			new EntityReference[] {
				new EntityReference(Entities.PRODUCT, 1),
				new EntityReference(Entities.PRODUCT, 2)
			},
			evitaSession.query(query, EntityReference.class).getRecordData().toArray()
		);
	}

	@DisplayName("Should query sealed entities")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void querySealedEntities(EvitaSessionContract evitaSession, List<SealedEntity> originalProducts) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(1, 2)),
			require(entityFetchAll())
		);

		final List<SealedEntity> theProducts = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1 || it.getPrimaryKey() == 2)
			.toList();
		assertEquals(theProducts, evitaSession.querySealedEntity(query).getRecordData());
		assertEquals(theProducts, evitaSession.query(query, SealedEntity.class).getRecordData());
	}

	@DisplayName("Should query custom entity model instances")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void queryCustomEntities(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(1, 2)),
			require(entityFetchAll())
		);

		final List<SealedEntity> theProducts = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1 || it.getPrimaryKey() == 2)
			.toList();

		final List<ProductInterface> products = evitaSession.query(query, ProductInterface.class).getRecordData();
		for (int i = 0; i < theProducts.size(); i++) {
			final SealedEntity expectedProduct = theProducts.get(i);
			final ProductInterface actualProduct = products.get(i);
			assertProduct(
				expectedProduct,
				actualProduct,
				originalCategories,
				null, false
			);
		}
	}

	@DisplayName("Should wrap an interface and load data in single localization")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void shouldProxyToInterfaceWithCzechLocalization(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getReferences(Entities.CATEGORY).size() > 2)
			.filter(it -> it.getAllPricesForSale().size() > 1)
			.findFirst()
			.orElseThrow();

		final Optional<ProductInterface> productRef = evitaSession.queryOne(
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(theProduct.getPrimaryKey()),
					entityLocaleEquals(CZECH_LOCALE)
				),
				require(
					entityFetch(
						attributeContent(),
						associatedDataContent(),
						priceContentAll(),
						referenceContentAllWithAttributes(
							entityFetch(
								hierarchyContent(
									entityFetchAll()
								),
								attributeContent(),
								associatedDataContent()
							),
							entityGroupFetch(
								attributeContent(),
								associatedDataContent()
							)
						)
					)
				)
			),
			ProductInterface.class
		);

		assertProduct(
			theProduct,
			productRef.orElse(null),
			originalCategories,
			CZECH_LOCALE,
			true
		);
	}

	@DisplayName("Should wrap an interface and load all data")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void shouldProxyToInterface(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getReferences(Entities.CATEGORY).size() > 2)
			.filter(it -> it.getAllPricesForSale().size() > 1)
			.findFirst()
			.orElseThrow();

		final Optional<ProductInterface> productRef = evitaSession.getEntity(
			ProductInterface.class,
			theProduct.getPrimaryKey(),
			attributeContent(),
			associatedDataContent(),
			priceContentAll(),
			referenceContentAllWithAttributes(
				entityFetch(
					hierarchyContent(
						entityFetchAll()
					),
					attributeContent(),
					associatedDataContent()
				),
				entityGroupFetchAll()
			),
			dataInLocales()
		);

		assertProduct(
			theProduct,
			productRef.orElse(null),
			originalCategories,
			null, true
		);
	}

	@DisplayName("Should delete entity")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void deleteEntity(
		EvitaContract evita,
		List<SealedEntity> originalProducts
	) {
		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 50)
			.findFirst()
			.orElseThrow();

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productToDelete = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(50)
						),
						require(
							entityFetchAll()
						)
					)
				).orElseThrow();
				assertEquals(theProduct, productToDelete);

				final SealedEntity deletedProduct = session.deleteEntity(
					Entities.PRODUCT,
					50,
					entityFetchAllContent()
				).orElseThrow();
				assertEquals(theProduct, deletedProduct);

				assertTrue(session.getEntity(Entities.PRODUCT, 50, entityFetchAllContent()).isEmpty());
			}
		);
	}

	@DisplayName("Should delete entity with hierarchy")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void deleteEntityWithHierarchy(
		EvitaContract evita,
		Map<Integer, SealedEntity> originalCategories
	) {
		final SealedEntity theCategory = originalCategories
			.values()
			.stream()
			.max(Comparator.comparingInt(EntityContract::getPrimaryKey))
			.orElseThrow();

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity categoryToDelete = session.queryOneSealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theCategory.getPrimaryKey())
						),
						require(
							entityFetchAll()
						)
					)
				).orElseThrow();
				assertEquals(theCategory, categoryToDelete);

				final DeletedHierarchy<SealedEntity> deletedHierarchy = session.deleteEntityAndItsHierarchy(
					Entities.CATEGORY,
					theCategory.getPrimaryKey(),
					entityFetchAllContent()
				);
				assertEquals(theCategory, deletedHierarchy.deletedRootEntity());
				assertEquals(1, deletedHierarchy.deletedEntities());

				assertTrue(session.getEntity(Entities.CATEGORY, theCategory.getPrimaryKey(), entityFetchAllContent()).isEmpty());
			}
		);
	}

}
