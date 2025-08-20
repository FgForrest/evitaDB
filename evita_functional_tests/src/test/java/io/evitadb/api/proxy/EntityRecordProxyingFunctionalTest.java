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

package io.evitadb.api.proxy;

import io.evitadb.api.AbstractEntityProxyingFunctionalTest;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.proxy.mock.CategoryRecord;
import io.evitadb.api.proxy.mock.ProductCategoryRecord;
import io.evitadb.api.proxy.mock.ProductRecord;
import io.evitadb.api.proxy.mock.TestEntity;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.generator.DataGenerator.Labels;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ReflectionLookup;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the ability to proxy an entity into an arbitrary interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Evita entity record proxying functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EntityRecordProxyingFunctionalTest extends AbstractEntityProxyingFunctionalTest {
	protected static final String HUNDRED_PRODUCTS = "HundredProxyProducts_EntityRecordProxyingFunctionalTest";

	private static void assertCategories(
		@Nonnull Stream<CategoryRecord> categoryReferences,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nonnull int[] expectedCategoryIds,
		@Nullable Locale locale
	) {
		assertNotNull(categoryReferences);
		final CategoryRecord[] references = categoryReferences
			.sorted(Comparator.comparingInt(CategoryRecord::id))
			.toArray(CategoryRecord[]::new);

		assertEquals(expectedCategoryIds.length, references.length);
		for (CategoryRecord reference : references) {
			assertCategory(
				reference,
				originalCategories.computeIfAbsent(reference.id(), id -> {
					throw new AssertionError("Category with id " + id + " not found");
				}),
				locale
			);
		}
	}

	private static void assertCategory(
		@Nonnull CategoryRecord category,
		@Nonnull SealedEntity sealedEntity,
		@Nullable Locale locale
	) {
		assertEquals(TestEntity.CATEGORY, category.entityType());
		assertEquals(sealedEntity.getPrimaryKey(), category.id());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_CODE), category.code());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_PRIORITY), category.priority());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_VALIDITY), category.validity());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_NAME, locale), category.name());
	}

	private static void assertCategoryReferences(
		@Nonnull Stream<ProductCategoryRecord> categoryReferences,
		@Nonnull SealedEntity originalProduct,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nonnull int[] expectedCategoryIds,
		@Nullable Locale locale,
		boolean externalEntities
	) {
		assertNotNull(categoryReferences);
		final ProductCategoryRecord[] references = categoryReferences
			.sorted(Comparator.comparingInt(ProductCategoryRecord::primaryKey))
			.toArray(ProductCategoryRecord[]::new);

		assertEquals(expectedCategoryIds.length, references.length);
		for (ProductCategoryRecord reference : references) {
			assertCategoryReference(
				reference,
				originalProduct.getReference(Entities.CATEGORY, reference.primaryKey()).orElseThrow(),
				originalCategories.computeIfAbsent(
					reference.primaryKey(),
					id -> {
						throw new AssertionError("Category with id " + id + " not found");
					}
				),
				locale,
				externalEntities
			);
		}
	}

	private static void assertCategoryReference(
		@Nonnull ProductCategoryRecord productCategory,
		@Nonnull ReferenceContract reference,
		@Nonnull SealedEntity sealedEntity,
		@Nullable Locale locale,
		boolean externalEntities
	) {
		assertEquals(sealedEntity.getPrimaryKey(), productCategory.primaryKey());

		final Long categoryPriority = reference.getAttribute(DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY, Long.class);
		assertEquals(categoryPriority, productCategory.orderInCategory());

		assertEquals(reference.getAttribute(ATTRIBUTE_CATEGORY_LABEL, locale), productCategory.label());

		if (externalEntities) {
			assertCategory(productCategory.category(), sealedEntity, locale);
			assertEquals(new EntityReference(Entities.CATEGORY, sealedEntity.getPrimaryKey()), productCategory.categoryReference());
			assertEquals(sealedEntity.getPrimaryKey(), productCategory.categoryReferencePrimaryKey());
		} else {
			assertNull(productCategory.category());
		}

	}

	private static void assertCategoryParent(
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nonnull CategoryRecord category,
		@Nullable Locale locale
	) {
		final SealedEntity originalCategory = originalCategories.get(category.id());
		if (originalCategory.getParentEntity().isEmpty()) {
			assertNull(category.parentId());
			assertNull(category.parentEntityReference());
			assertNull(category.parentEntity());
		} else {
			final int expectedParentId = originalCategory.getParentEntity().get().getPrimaryKey();
			assertEquals(
				expectedParentId,
				category.parentId()
			);
			assertEquals(
				new EntityReference(Entities.CATEGORY, expectedParentId),
				category.parentEntityReference()
			);
			assertEquals(
				expectedParentId,
				category.parentEntityClassifier().getPrimaryKey()
			);
			assertEquals(
				expectedParentId,
				category.parentEntityClassifierWithParent().getPrimaryKey()
			);
			assertCategory(category.parentEntity(), originalCategories.get(expectedParentId), locale);
			assertCategoryParent(originalCategories, category.parentEntity(), locale);
		}
	}

	private static void assertCategoryParents(
		@Nonnull Collection<CategoryRecord> categories,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nullable Locale locale
	) {
		for (CategoryRecord category : categories) {
			assertCategoryParent(originalCategories, category, locale);
		}
	}

	private static void assertProduct(
		@Nonnull SealedEntity originalProduct,
		@Nullable ProductRecord product,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nullable Locale locale,
		boolean externalEntities
	) {
		assertProductBasicData(originalProduct, product);
		assertProductAttributes(originalProduct, product, locale);
		assertProductAssociatedData(originalProduct, product, locale);

		final Optional<ReferencedFileSet> referenceFileSetOptional = ofNullable(originalProduct.getAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES, ReferencedFileSet.class, ReflectionLookup.NO_CACHE_INSTANCE));
		referenceFileSetOptional
			.ifPresent(it -> {
				assertEquals(it, product.referencedFileSet());
				assertEquals(it, product.referencedFileSetAsDifferentProperty());
			});

		assertCategoryParents(product.categories(), originalCategories, locale);

		final int[] expectedCategoryIds = originalProduct.getReferences(Entities.CATEGORY)
			.stream()
			.mapToInt(ReferenceContract::getReferencedPrimaryKey)
			.toArray();

		assertCategoryIds(product.categoryIds().stream(), expectedCategoryIds);
		assertCategoryIds(product.categoryIdsAsList().stream(), expectedCategoryIds);
		assertCategoryIds(product.categoryIdsAsSet().stream(), expectedCategoryIds);
		assertCategoryIds(Arrays.stream(product.categoryIdsAsArray()).boxed(), expectedCategoryIds);

		assertCategoryEntityReferences(product.categoryReferences().stream(), expectedCategoryIds);
		assertCategoryEntityReferences(product.categoryReferencesAsList().stream(), expectedCategoryIds);
		assertCategoryEntityReferences(product.categoryReferencesAsSet().stream(), expectedCategoryIds);
		assertCategoryEntityReferences(Arrays.stream(product.categoryReferencesAsArray()), expectedCategoryIds);

		if (externalEntities) {
			assertCategories(product.categories().stream(), originalCategories, expectedCategoryIds, locale);
			assertCategories(product.categoriesAsList().stream(), originalCategories, expectedCategoryIds, locale);
			assertCategories(product.categoriesAsSet().stream(), originalCategories, expectedCategoryIds, locale);
			assertCategories(Arrays.stream(product.categoriesAsArray()), originalCategories, expectedCategoryIds, locale);
		} else {
			assertTrue(product.categories().isEmpty());
			assertTrue(product.categoriesAsList().isEmpty());
			assertTrue(product.categoriesAsSet().isEmpty());
			assertTrue(ArrayUtils.isEmpty(product.categoriesAsArray()));
		}

		assertCategoryReferences(product.productCategories().stream(), originalProduct, originalCategories, expectedCategoryIds, locale, externalEntities);
		assertCategoryReferences(product.productCategoriesAsList().stream(), originalProduct, originalCategories, expectedCategoryIds, locale, externalEntities);
		assertCategoryReferences(product.productCategoriesAsSet().stream(), originalProduct, originalCategories, expectedCategoryIds, locale, externalEntities);
		assertCategoryReferences(Arrays.stream(product.productCategoriesAsArray()), originalProduct, originalCategories, expectedCategoryIds, locale, externalEntities);

		assertNull(product.priceForSale());

		final PriceContract[] allPricesForSale = product.allPricesForSale();
		if (allPricesForSale == null) {
			assertFalse(originalProduct.isPriceForSaleContextAvailable());
		} else {
			final PriceContract[] expectedAllPricesForSale = originalProduct.getAllPricesForSale().toArray(PriceContract[]::new);
			assertEquals(expectedAllPricesForSale.length, allPricesForSale.length);
			assertArrayEquals(expectedAllPricesForSale, allPricesForSale);
		}

		final PriceContract[] expectedAllPrices = originalProduct.getPrices().toArray(PriceContract[]::new);
		final PriceContract[] allPrices = Arrays.stream(product.allPricesAsArray())
			.toArray(PriceContract[]::new);

		assertEquals(expectedAllPrices.length, allPrices.length);
		assertArrayEquals(expectedAllPrices, allPrices);

		assertArrayEquals(expectedAllPrices, product.allPricesAsList().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.allPricesAsSet().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.allPrices().toArray(PriceContract[]::new));

		final Optional<PriceContract> first = Arrays.stream(expectedAllPrices).filter(it -> "basic".equals(it.priceList())).findFirst();
		if (first.isEmpty()) {
			assertNull(product.basicPrice());
		} else {
			assertEquals(
				first.get(),
				product.basicPrice()
			);
		}
	}

	private static void assertProductBasicData(@Nonnull SealedEntity originalProduct, @Nullable ProductRecord product) {
		assertNotNull(product);
		assertEquals(originalProduct.getPrimaryKey(), product.id());
		assertEquals(TestEntity.PRODUCT, product.entityType());
	}

	private static void assertProductAttributes(@Nonnull SealedEntity originalProduct, @Nonnull ProductRecord product, @Nonnull Locale locale) {
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_CODE), product.code());
		final String actualName = originalProduct.getAttribute(DataGenerator.ATTRIBUTE_NAME, locale, String.class);
		if (actualName == null) {
			assertNull(product.names());
		} else {
			assertArrayEquals(new String[]{actualName}, product.names());
		}

		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.quantity());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_ALIAS), product.alias());
		assertEquals(TestEnum.valueOf(originalProduct.getAttribute(ATTRIBUTE_ENUM, String.class)), product.testEnum());

		// methods with implementation not directly annotated by @Attribute annotation are not intercepted
		assertEquals("computed EAN", product.getEan());
		// methods with different name are intercepted based on @Attribute annotation
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_EAN), product.eanAsDifferentProperty());

		assertEquals(
			ofNullable(originalProduct.getAttribute(ATTRIBUTE_OPTIONAL_AVAILABILITY)),
			ofNullable(product.available())
		);

		assertArrayEquals(
			originalProduct.getAttribute(ATTRIBUTE_MARKETS, String[].class),
			product.marketsAttribute()
		);

		assertArrayEquals(
			originalProduct.getAttribute(ATTRIBUTE_MARKETS, String[].class),
			product.marketsAttributeAsList().toArray(new String[0])
		);

		assertArrayEquals(
			Arrays.stream(originalProduct.getAttribute(ATTRIBUTE_MARKETS, String[].class)).sorted().distinct().toArray(String[]::new),
			product.marketsAttributeAsSet().stream().sorted().toArray(String[]::new)
		);
	}

	private static void assertProductAssociatedData(@Nonnull SealedEntity originalProduct, @Nonnull ProductRecord product, @Nullable Locale locale) {
		assertEquals(
			originalProduct.getAssociatedData(DataGenerator.ASSOCIATED_DATA_LABELS, locale, Labels.class, REFLECTION_LOOKUP),
			product.labels()
		);

		assertArrayEquals(
			originalProduct.getAssociatedData(ASSOCIATED_DATA_MARKETS, String[].class, REFLECTION_LOOKUP),
			product.markets()
		);

		assertArrayEquals(
			originalProduct.getAssociatedData(ASSOCIATED_DATA_MARKETS, String[].class, REFLECTION_LOOKUP),
			product.marketsAsList().toArray(new String[0])
		);

		assertArrayEquals(
			Arrays.stream(originalProduct.getAssociatedData(ASSOCIATED_DATA_MARKETS, String[].class, REFLECTION_LOOKUP)).distinct().sorted().toArray(String[]::new),
			product.marketsAsSet().stream().sorted().toArray(String[]::new)
		);
	}

	/**
	 * Verifies that all fields declared in `proxyClass` are set to non-null value in proxy.
	 *
	 * @param proxy      Proxy instance
	 * @param proxyClass Proxy class
	 */
	private static void verifyAllComponentsAreSet(@Nonnull Object proxy, @Nonnull Class<?> proxyClass, String... except) {
		final Field[] fields = proxyClass.getDeclaredFields();
		final Set<String> exceptFields = new HashSet<>(Arrays.asList(except));
		for (Field field : fields) {
			if (!exceptFields.contains(field.getName())) {
				field.setAccessible(true);
				try {
					assertNotNull(field.get(proxy), "Field `" + field.getName() + "` is unexpectedly null!");
				} catch (IllegalAccessException e) {
					fail(e.getMessage());
				}
			}
		}
	}

	@DataSet(value = HUNDRED_PRODUCTS, destroyAfterClass = true, readOnly = false)
	@Override
	protected DataCarrier setUp(Evita evita) {
		return super.setUp(evita);
	}

	@DisplayName("Should return entity schema directly or via model class")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldReturnEntitySchema(EvitaSessionContract evitaSession) {
		assertNotNull(evitaSession.getEntitySchema(Entities.PRODUCT));
		assertNotNull(evitaSession.getEntitySchema(ProductRecord.class));
		assertEquals(
			evitaSession.getEntitySchema(Entities.PRODUCT),
			evitaSession.getEntitySchema(ProductRecord.class)
		);

		assertNotNull(evitaSession.getEntitySchemaOrThrow(Entities.PRODUCT));
		assertNotNull(evitaSession.getEntitySchemaOrThrow(ProductRecord.class));
		assertEquals(
			evitaSession.getEntitySchemaOrThrow(Entities.PRODUCT),
			evitaSession.getEntitySchemaOrThrow(ProductRecord.class)
		);
	}

	@DisplayName("Should get sealed entity")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
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
	@UseDataSet(HUNDRED_PRODUCTS)
	void getCustomEntity(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final int expectedAssociatedDataCount = originalProducts.get(0).getSchema().getAssociatedData().size();
		final int expectedReferenceCount = originalProducts.get(0).getSchema().getReferences().size();
		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null)
			.filter(it ->
				it.getAssociatedDataValues()
					.stream()
					.filter(aValue -> !aValue.key().localized() || aValue.key().locale().equals(Locale.ENGLISH))
					.map(aValue -> aValue.key().associatedDataName())
					.distinct()
					.count() == expectedAssociatedDataCount &&
					it.getReferences()
						.stream()
						.map(ReferenceContract::getReferenceName)
						.distinct()
						.count() == expectedReferenceCount &&
					it.getPrices().stream().anyMatch(PriceContract::indexed)
			)
			.findFirst()
			.orElseThrow();

		final ProductRecord proxiedEntity = evitaSession.getEntity(
			ProductRecord.class, theProduct.getPrimaryKey(),
			hierarchyContent(),
			attributeContentAll(),
			associatedDataContentAll(),
			priceContentAll(),
			referenceContentAllWithAttributes(),
			dataInLocales(Locale.ENGLISH)
		).orElse(null);

		verifyAllComponentsAreSet(
			proxiedEntity, ProductRecord.class,
			"priceForSale", "allPricesForSale", "eanAsDifferentProperty"
		);

		assertProduct(
			theProduct,
			proxiedEntity,
			originalCategories,
			Locale.ENGLISH,
			false
		);
	}

	@DisplayName("Should fail to enrich custom entity model instance")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void failsToEnrichCustomEntity(EvitaSessionContract evitaSession) {
		final ProductRecord partiallyLoadedEntity = evitaSession
			.getEntity(ProductRecord.class, 1)
			.orElse(null);

		assertThrows(
			EvitaInvalidUsageException.class,
			() -> evitaSession.enrichEntity(
				partiallyLoadedEntity,
				hierarchyContent(),
				attributeContentAll(),
				associatedDataContentAll(),
				priceContentAll(),
				referenceContentAllWithAttributes(),
				dataInLocales(Locale.ENGLISH)
			)
		);
	}

	@DisplayName("Should fail to limit custom entity model instance")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void failsToLimitCustomEntity(
		EvitaSessionContract evitaSession
	) {
		final ProductRecord partiallyLoadedEntity = evitaSession
			.getEntity(
				ProductRecord.class, 1,
				hierarchyContent(),
				attributeContentAll(),
				associatedDataContentAll(),
				priceContentAll(),
				referenceContentAllWithAttributes(),
				dataInLocales(Locale.ENGLISH)
			)
			.orElse(null);


		assertThrows(
			EvitaInvalidUsageException.class,
			() -> evitaSession.enrichOrLimitEntity(
				partiallyLoadedEntity,
				attributeContentAll()
			)
		);
	}

	@DisplayName("Should return entity reference")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
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
	@UseDataSet(HUNDRED_PRODUCTS)
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
	@UseDataSet(HUNDRED_PRODUCTS)
	@ParameterizedTest
	@MethodSource("returnRandomSeed")
	void queryOneRandomizedEntity(
		long seed,
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final Random rnd = new Random(seed);
		final int primaryKey = rnd.nextInt(49) + 1;

		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == primaryKey)
			.findFirst()
			.orElseThrow();

		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				entityPrimaryKeyInSet(primaryKey),
				attributeEquals(ATTRIBUTE_ENUM, TestEnum.valueOf(theProduct.getAttribute(ATTRIBUTE_ENUM, String.class)))
			),
			require(
				entityFetch(
					hierarchyContent(),
					attributeContentAll(),
					associatedDataContentAll(),
					priceContentAll(),
					referenceContentAllWithAttributes(),
					dataInLocales(Locale.ENGLISH)
				)
			)
		);

		assertProduct(
			theProduct,
			evitaSession.queryOne(query, ProductRecord.class).orElse(null),
			originalCategories,
			Locale.ENGLISH, false
		);
	}

	@DisplayName("Should return custom entity model instance")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void queryOneCustomEntity(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(1)),
			require(
				entityFetch(
					hierarchyContent(),
					attributeContentAll(),
					associatedDataContentAll(),
					priceContentAll(),
					referenceContentAllWithAttributes(),
					dataInLocales(Locale.ENGLISH)
				)
			)
		);

		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1)
			.findFirst()
			.orElseThrow();

		assertProduct(
			theProduct,
			evitaSession.queryOne(query, ProductRecord.class).orElse(null),
			originalCategories,
			Locale.ENGLISH, false
		);
	}

	@DisplayName("Should return same proxy instances for repeated calls")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldReturnSameInstancesForRepeatedCalls(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts
	) {
		final SealedEntity testProduct = originalProducts
			.stream()
			.filter(it -> it.getReferences(Entities.CATEGORY).size() == 2)
			.filter(it -> it.getLocales().contains(Locale.ENGLISH))
			.findFirst()
			.orElseThrow();

		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(testProduct.getPrimaryKeyOrThrowException())),
			require(
				entityFetch(
					hierarchyContent(),
					attributeContentAll(),
					associatedDataContentAll(),
					priceContentAll(),
					referenceContentAllWithAttributes(),
					dataInLocales(Locale.ENGLISH)
				)
			)
		);

		final ProductRecord product = evitaSession.queryOne(query, ProductRecord.class)
			.orElseThrow();

		for (int i = 0; i < 2; i++) {
			final ProductCategoryRecord[] array1 = product.productCategoriesAsArray();
			final ProductCategoryRecord[] array2 = product.productCategoriesAsArray();
			for (int j = 0; j < array1.length; j++) {
				final ProductCategoryRecord productCategory1 = array1[j];
				final ProductCategoryRecord productCategory2 = array2[j];

				assertSame(productCategory1, productCategory2);
				assertSame(productCategory1.category(), productCategory2.category());
			}

			final CategoryRecord[] catArray1 = product.categoriesAsArray();
			final CategoryRecord[] catArray2 = product.categoriesAsArray();
			for (int j = 0; j < catArray1.length; j++) {
				final CategoryRecord category1 = catArray1[j];
				final CategoryRecord category2 = catArray2[j];

				assertSame(category1, category2);
			}
		}

	}

	@DisplayName("Should return list of entity references")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void queryListOfEntityReference(EvitaSessionContract evitaSession) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				entityPrimaryKeyInSet(1, 2)
			)
		);
		assertArrayEquals(
			new EntityReference[]{
				new EntityReference(Entities.PRODUCT, 1),
				new EntityReference(Entities.PRODUCT, 2)
			},
			evitaSession.queryListOfEntityReferences(query).toArray()
		);
		assertArrayEquals(
			new EntityReference[]{
				new EntityReference(Entities.PRODUCT, 1),
				new EntityReference(Entities.PRODUCT, 2)
			},
			evitaSession.queryList(query, EntityReference.class).toArray()
		);
	}

	@DisplayName("Should return list of sealed entities")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
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
	@UseDataSet(HUNDRED_PRODUCTS)
	void queryListOfCustomEntities(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(1, 2)),
			require(
				entityFetch(
					hierarchyContent(),
					attributeContentAll(),
					associatedDataContentAll(),
					priceContentAll(),
					referenceContentAllWithAttributes(),
					dataInLocales(Locale.ENGLISH)
				)
			)
		);

		final List<SealedEntity> theProducts = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1 || it.getPrimaryKey() == 2)
			.toList();

		final List<? extends ProductRecord> products = evitaSession.queryList(query, ProductRecord.class);
		for (int i = 0; i < theProducts.size(); i++) {
			final SealedEntity expectedProduct = theProducts.get(i);
			final ProductRecord actualProduct = products.get(i);
			assertProduct(
				expectedProduct,
				actualProduct,
				originalCategories,
				Locale.ENGLISH, false
			);
		}
	}

	@DisplayName("Should query entity references")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void queryEntityReferences(EvitaSessionContract evitaSession) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				entityPrimaryKeyInSet(1, 2)
			)
		);
		assertArrayEquals(
			new EntityReference[]{
				new EntityReference(Entities.PRODUCT, 1),
				new EntityReference(Entities.PRODUCT, 2)
			},
			evitaSession.queryEntityReference(query).getRecordData().toArray()
		);
		assertArrayEquals(
			new EntityReference[]{
				new EntityReference(Entities.PRODUCT, 1),
				new EntityReference(Entities.PRODUCT, 2)
			},
			evitaSession.query(query, EntityReference.class).getRecordData().toArray()
		);
	}

	@DisplayName("Should query sealed entities")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
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
	@UseDataSet(HUNDRED_PRODUCTS)
	void queryCustomEntities(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(1, 2)),
			require(
				entityFetch(
					hierarchyContent(),
					attributeContentAll(),
					associatedDataContentAll(),
					priceContentAll(),
					referenceContentAllWithAttributes(),
					dataInLocales(Locale.ENGLISH)
				)
			)
		);

		final List<SealedEntity> theProducts = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1 || it.getPrimaryKey() == 2)
			.toList();

		final List<? extends ProductRecord> products = evitaSession.query(query, ProductRecord.class).getRecordData();
		for (int i = 0; i < theProducts.size(); i++) {
			final SealedEntity expectedProduct = theProducts.get(i);
			final ProductRecord actualProduct = products.get(i);
			assertProduct(
				expectedProduct,
				actualProduct,
				originalCategories,
				Locale.ENGLISH, false
			);
		}
	}

	@DisplayName("Should wrap an pojo and load data in single localization")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldProxyToPojoWithCzechLocalization(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getReferences(Entities.CATEGORY).size() > 1)
			.filter(it -> it.getPrices().stream().anyMatch(PriceContract::indexed))
			.findFirst()
			.orElseThrow();

		final Optional<? extends ProductRecord> productRef = evitaSession.queryOne(
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(theProduct.getPrimaryKey()),
					entityLocaleEquals(CZECH_LOCALE)
				),
				require(
					entityFetch(
						attributeContentAll(),
						associatedDataContentAll(),
						priceContentAll(),
						referenceContentAllWithAttributes(
							entityFetch(
								hierarchyContent(
									entityFetch(
										attributeContentAll(),
										associatedDataContentAll()
									)
								),
								attributeContentAll(),
								associatedDataContentAll()
							),
							entityGroupFetch(
								attributeContentAll(),
								associatedDataContentAll()
							)
						)
					)
				)
			),
			ProductRecord.class
		);

		assertProduct(
			theProduct,
			productRef.orElse(null),
			originalCategories,
			CZECH_LOCALE,
			true
		);
	}

	@DisplayName("Should wrap an pojo and load all data")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldProxyToPojo(
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getReferences(Entities.CATEGORY).size() > 1)
			.filter(it -> it.getPrices().stream().anyMatch(PriceContract::indexed))
			.findFirst()
			.orElseThrow();

		final Optional<? extends ProductRecord> productRef = evitaSession.getEntity(
			ProductRecord.class,
			theProduct.getPrimaryKey(),
			dataInLocales(Locale.ENGLISH),
			attributeContentAll(),
			associatedDataContentAll(),
			priceContentAll(),
			referenceContentAllWithAttributes(
				entityFetch(
					hierarchyContent(
						entityFetch(
							attributeContentAll(),
							associatedDataContentAll()
						)
					),
					attributeContentAll(),
					associatedDataContentAll()
				),
				entityGroupFetch(
					attributeContentAll(),
					associatedDataContentAll()
				)
			)
		);

		assertProduct(
			theProduct,
			productRef.orElse(null),
			originalCategories,
			Locale.ENGLISH,
			true
		);
	}

}
