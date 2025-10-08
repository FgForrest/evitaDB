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
import io.evitadb.api.proxy.mock.AbstractCategoryPojo;
import io.evitadb.api.proxy.mock.AbstractProductCategoryPojo;
import io.evitadb.api.proxy.mock.AbstractProductPojo;
import io.evitadb.api.proxy.mock.FinalProductPojo;
import io.evitadb.api.proxy.mock.ProductPojo;
import io.evitadb.api.proxy.mock.SealedProductPojo;
import io.evitadb.api.proxy.mock.TestEntity;
import io.evitadb.api.query.Query;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.CURRENCY_CZK;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_BASIC;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the ability to proxy an entity into an arbitrary interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Evita entity POJO proxying functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EntityPojoProxyingFunctionalTest extends AbstractEntityProxyingFunctionalTest {
	protected static final String HUNDRED_PRODUCTS = "HundredProxyProducts_EntityPojoProxyingFunctionalTest";

	private static void assertCategories(
		@Nonnull Stream<AbstractCategoryPojo> categoryReferences,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nonnull int[] expectedCategoryIds,
		@Nullable Locale locale
	) {
		assertNotNull(categoryReferences);
		final AbstractCategoryPojo[] references = categoryReferences
			.sorted(Comparator.comparingInt(AbstractCategoryPojo::getId))
			.toArray(AbstractCategoryPojo[]::new);

		assertEquals(expectedCategoryIds.length, references.length);
		for (AbstractCategoryPojo reference : references) {
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
		@Nonnull AbstractCategoryPojo category,
		@Nonnull SealedEntity sealedEntity,
		@Nullable Locale locale
	) {
		assertEquals(TestEntity.CATEGORY, category.getEntityType());
		assertEquals(sealedEntity.getPrimaryKey(), category.getId());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_CODE), category.getCode());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_PRIORITY), category.getPriority());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_VALIDITY), category.getValidity());
		assertEquals(sealedEntity.getAttribute(DataGenerator.ATTRIBUTE_NAME, locale), category.getName());
	}

	private static void assertCategoryReferences(
		@Nonnull Stream<AbstractProductCategoryPojo> categoryReferences,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nonnull int[] expectedCategoryIds,
		@Nullable Locale locale,
		boolean externalEntities
	) {
		assertNotNull(categoryReferences);
		final AbstractProductCategoryPojo[] references = categoryReferences
			.sorted(Comparator.comparingInt(AbstractProductCategoryPojo::getPrimaryKey))
			.toArray(AbstractProductCategoryPojo[]::new);

		assertEquals(expectedCategoryIds.length, references.length);
		for (AbstractProductCategoryPojo reference : references) {
			assertCategoryReference(
				reference,
				originalCategories.computeIfAbsent(
					reference.getPrimaryKey(),
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
		@Nonnull AbstractProductCategoryPojo productCategory,
		@Nonnull SealedEntity sealedEntity,
		@Nullable Locale locale,
		boolean externalEntities
	) {
		assertEquals(sealedEntity.getPrimaryKey(), productCategory.getPrimaryKey());

		assertInstanceOf(SealedEntityReferenceProxy.class, productCategory);
		final ReferenceContract theReference = ((SealedEntityReferenceProxy) productCategory).getReference();
		final Long categoryPriority = theReference.getAttribute(DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY, Long.class);
		assertEquals(categoryPriority, productCategory.getOrderInCategory());

		assertEquals(theReference.getAttribute(ATTRIBUTE_CATEGORY_LABEL, locale), productCategory.getLabel());

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
		@Nonnull AbstractCategoryPojo category,
		@Nullable Locale locale
	) {
		final SealedEntity originalCategory = originalCategories.get(category.getId());
		if (originalCategory.getParentEntity().isEmpty()) {
			assertNull(category.getParentId());
			assertNull(category.getParentEntityReference());
			assertNull(category.getParentEntity());
		} else {
			final int expectedParentId = originalCategory.getParentEntity().get().getPrimaryKey();
			assertEquals(
				expectedParentId,
				category.getParentId()
			);
			assertEquals(
				new EntityReference(Entities.CATEGORY, expectedParentId),
				category.getParentEntityReference()
			);
			assertEquals(
				expectedParentId,
				category.getParentEntityClassifier().getPrimaryKey()
			);
			assertEquals(
				expectedParentId,
				category.getParentEntityClassifierWithParent().getPrimaryKey()
			);
			assertCategory(category.getParentEntity(), originalCategories.get(expectedParentId), locale);
			assertCategoryParent(originalCategories, category.getParentEntity(), locale);
		}
	}

	private static void assertCategoryParents(
		@Nonnull Collection<AbstractCategoryPojo> categories,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nullable Locale locale
	) {
		for (AbstractCategoryPojo category : categories) {
			assertCategoryParent(originalCategories, category, locale);
		}
	}

	private static void assertProduct(
		@Nonnull SealedEntity originalProduct,
		@Nullable AbstractProductPojo product,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nullable Locale locale,
		@Nullable Currency currency,
		boolean externalEntities
	) {
		assertProductBasicData(originalProduct, product);
		assertProductAttributes(originalProduct, product, locale);
		assertProductAssociatedData(originalProduct, product, locale);

		final Optional<ReferencedFileSet> referenceFileSetOptional = ofNullable(originalProduct.getAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES, ReferencedFileSet.class, ReflectionLookup.NO_CACHE_INSTANCE));
		referenceFileSetOptional
			.ifPresent(it -> {
				assertEquals(it, product.getReferencedFileSet());
				assertEquals(it, product.getReferencedFileSetAsDifferentProperty());
			});

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

		if (!(product instanceof SealedProductPojo)) {
			assertNull(product.getPriceForSale());
		}

		final PriceContract[] allPricesForSale = product.getAllPricesForSale();
		if (allPricesForSale == null) {
			assertFalse(originalProduct.isPriceForSaleContextAvailable());
		} else {
			final PriceContract[] expectedAllPricesForSale = originalProduct.getAllPricesForSale().toArray(PriceContract[]::new);
			assertEquals(expectedAllPricesForSale.length, allPricesForSale.length);
			assertArrayEquals(expectedAllPricesForSale, allPricesForSale);
		}

		final PriceContract[] expectedAllPrices = currency == null ?
			originalProduct.getPrices().toArray(PriceContract[]::new) :
			originalProduct.getPrices().stream().filter(it -> currency.equals(it.currency())).toArray(PriceContract[]::new);
		final PriceContract[] allPrices = Arrays.stream(product.getAllPricesAsArray())
			.toArray(PriceContract[]::new);

		assertEquals(expectedAllPrices.length, allPrices.length);
		assertArrayEquals(expectedAllPrices, allPrices);

		assertArrayEquals(expectedAllPrices, product.getAllPricesAsList().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.getAllPricesAsSet().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.getAllPrices().toArray(PriceContract[]::new));

		if (currency != null) {
			final Optional<PriceContract> first = Arrays.stream(expectedAllPrices)
				.filter(it -> "basic".equals(it.priceList()) && currency.equals(it.currency()))
				.findFirst();
			if (first.isEmpty()) {
				assertNull(product.getBasicPrice());
			} else {
				assertEquals(
					first.get(),
					product.getBasicPrice()
				);
			}
		}
	}

	private static void assertProductBasicData(@Nonnull SealedEntity originalProduct, @Nullable AbstractProductPojo product) {
		assertNotNull(product);
		assertEquals(originalProduct.getPrimaryKey(), product.getId());
		assertEquals(TestEntity.PRODUCT, product.getEntityType());
	}

	private static void assertProductAttributes(@Nonnull SealedEntity originalProduct, @Nonnull AbstractProductPojo product, @Nullable Locale locale) {
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_CODE), product.getCode());
		if (locale == null) {
			if (!((SealedEntityProxy)product).entity().getLocales().isEmpty()) {
				assertArrayEquals(
					originalProduct.getAttributeLocales().stream().map(it -> originalProduct.getAttribute(DataGenerator.ATTRIBUTE_NAME, it, String.class)).toArray(String[]::new),
					product.getNames()
				);
			}
		} else {
			final String actualName = originalProduct.getAttribute(DataGenerator.ATTRIBUTE_NAME, locale, String.class);
			if (actualName == null) {
				assertNull(product.getNames());
			} else {
				assertArrayEquals(new String[]{actualName}, product.getNames());
			}
		}
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.getQuantity());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_ALIAS), product.getAlias());
		assertEquals(TestEnum.valueOf(originalProduct.getAttribute(ATTRIBUTE_ENUM, String.class)), product.getTestEnum());

		// methods with implementation not directly annotated by @Attribute annotation are not intercepted
		assertEquals("computed EAN", product.getEan());
		// methods with different name are intercepted based on @Attribute annotation
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_EAN), product.getEanAsDifferentProperty());

		assertEquals(
			ofNullable(originalProduct.getAttribute(ATTRIBUTE_OPTIONAL_AVAILABILITY)),
			ofNullable(product.getAvailable())
		);

		assertArrayEquals(
			originalProduct.getAttribute(ATTRIBUTE_MARKETS, String[].class),
			product.getMarketsAttribute()
		);

		assertArrayEquals(
			originalProduct.getAttribute(ATTRIBUTE_MARKETS, String[].class),
			product.getMarketsAttributeAsList().toArray(new String[0])
		);

		assertArrayEquals(
			Arrays.stream(originalProduct.getAttribute(ATTRIBUTE_MARKETS, String[].class)).sorted().distinct().toArray(String[]::new),
			product.getMarketsAttributeAsSet().stream().sorted().toArray(String[]::new)
		);
	}

	private static void assertProductAssociatedData(@Nonnull SealedEntity originalProduct, @Nonnull AbstractProductPojo product, @Nullable Locale locale) {
		assertEquals(
			originalProduct.getAssociatedData(DataGenerator.ASSOCIATED_DATA_LABELS, locale, Labels.class, REFLECTION_LOOKUP),
			product.getLabels()
		);

		assertArrayEquals(
			originalProduct.getAssociatedData(ASSOCIATED_DATA_MARKETS, String[].class, REFLECTION_LOOKUP),
			product.getMarkets()
		);

		assertArrayEquals(
			originalProduct.getAssociatedData(ASSOCIATED_DATA_MARKETS, String[].class, REFLECTION_LOOKUP),
			product.getMarketsAsList().toArray(new String[0])
		);

		assertArrayEquals(
			Arrays.stream(originalProduct.getAssociatedData(ASSOCIATED_DATA_MARKETS, String[].class, REFLECTION_LOOKUP)).distinct().sorted().toArray(String[]::new),
			product.getMarketsAsSet().stream().sorted().toArray(String[]::new)
		);
	}

	/**
	 * Verifies that all fields declared in `proxyClass` are set to non-null value in proxy.
	 *
	 * @param proxy      Proxy instance
	 * @param proxyClass Proxy class
	 */
	private static void verifyAllFieldsAreSet(@Nonnull Object proxy, @Nonnull Class<?> proxyClass, String... except) {
		final Field[] fields = proxyClass.getDeclaredFields();
		final Set<String> exceptFields = new HashSet<>(Arrays.asList(except));
		for (Field field : fields) {
			final String fieldName = field.getName();
			if (!exceptFields.contains(fieldName)) {
				field.setAccessible(true);
				try {
					assertNotNull(field.get(proxy), "Field `" + fieldName + "` is unexpectedly null!");
				} catch (IllegalAccessException e) {
					fail(e.getMessage());
				}
			}
		}
	}

	protected static Stream<Arguments> testedOpenPojoClasses() {
		return Stream.of(
			Arguments.of(AbstractProductPojo.class),
			Arguments.of(ProductPojo.class)
		);
	}

	protected static Stream<Arguments> testedPojoClasses() {
		return Stream.of(
			Arguments.of(AbstractProductPojo.class),
			Arguments.of(ProductPojo.class),
			Arguments.of(FinalProductPojo.class),
			Arguments.of(SealedProductPojo.class)
		);
	}

	protected static Stream<Arguments> returnRandomSeedAndTestPojoClasses() {
		final Random random = new Random();
		final Class<?>[] pojoClasses = new Class[] {
			AbstractProductPojo.class,
			ProductPojo.class,
			FinalProductPojo.class,
			SealedProductPojo.class
		};
		return LongStream.generate(random::nextLong).limit(50)
			.mapToObj(it -> {
				final int index = Math.abs((int) (it % pojoClasses.length));
				return Arguments.of(pojoClasses[index], it);
			});
	}

	@DataSet(value = HUNDRED_PRODUCTS, destroyAfterClass = true, readOnly = false)
	@Override
	protected DataCarrier setUp(Evita evita) {
		final DataCarrier dataCarrier = super.setUp(evita);

		final List<SealedEntity> originalProducts = (List<SealedEntity>) dataCarrier.getValueByName("originalProducts");
		final List<SealedEntity> productsWithCzkSellingPrice = originalProducts
			.stream()
			.filter(it -> it.getPriceForSale(CURRENCY_CZK, null, PRICE_LIST_BASIC).isPresent())
			.filter(it -> !it.getReferences(Entities.CATEGORY).isEmpty())
			.limit(2)
			.toList();
		assertEquals(2, productsWithCzkSellingPrice.size());

		return dataCarrier
			.put("productWithCzkSellingPrice", productsWithCzkSellingPrice.get(0))
			.put("productsWithCzkSellingPrice", productsWithCzkSellingPrice);
	}

	@DisplayName("Should return entity schema directly or via model class")
	@ParameterizedTest
	@MethodSource("testedPojoClasses")
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldReturnEntitySchema(Class<? extends AbstractProductPojo> theClass, EvitaSessionContract evitaSession) {
		assertNotNull(evitaSession.getEntitySchema(Entities.PRODUCT));
		assertNotNull(evitaSession.getEntitySchema(theClass));
		assertEquals(
			evitaSession.getEntitySchema(Entities.PRODUCT),
			evitaSession.getEntitySchema(theClass)
		);

		assertNotNull(evitaSession.getEntitySchemaOrThrowException(Entities.PRODUCT));
		assertNotNull(evitaSession.getEntitySchemaOrThrowException(theClass));
		assertEquals(
			evitaSession.getEntitySchemaOrThrowException(Entities.PRODUCT),
			evitaSession.getEntitySchemaOrThrowException(theClass)
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
	@ParameterizedTest
	@MethodSource("testedPojoClasses")
	@UseDataSet(HUNDRED_PRODUCTS)
	void getCustomEntity(
		Class<? extends AbstractProductPojo> theClass,
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
					!it.getPrices(PRICE_LIST_BASIC).isEmpty()
			)
			.findFirst()
			.orElseThrow();

		final AbstractProductPojo proxiedEntity = evitaSession.getEntity(
			theClass,
			theProduct.getPrimaryKey(),
			hierarchyContent(),
			attributeContentAll(),
			associatedDataContentAll(),
			priceContentAll(),
			referenceContentAllWithAttributes(),
			dataInLocales(Locale.ENGLISH)
		).orElse(null);

		verifyAllFieldsAreSet(
			proxiedEntity, theClass,
			"priceForSale", "allPricesForSale", "eanAsDifferentProperty"
		);

		assertProduct(
			theProduct,
			proxiedEntity,
			originalCategories,
			Locale.ENGLISH,
			null,
			false
		);
	}

	@DisplayName("Should enrich custom entity model instance")
	@ParameterizedTest
	@MethodSource("testedOpenPojoClasses")
	@UseDataSet(HUNDRED_PRODUCTS)
	void enrichCustomEntity(
		Class<? extends AbstractProductPojo> theClass,
		EvitaSessionContract evitaSession,
		SealedEntity productWithCzkSellingPrice,
		Map<Integer, SealedEntity> originalCategories
	) {
		final AbstractProductPojo partiallyLoadedEntity = evitaSession
			.getEntity(theClass, productWithCzkSellingPrice.getPrimaryKey())
			.orElse(null);

		assertProduct(
			productWithCzkSellingPrice,
			evitaSession.enrichEntity(
				partiallyLoadedEntity,
				hierarchyContent(),
				attributeContentAll(),
				associatedDataContentAll(),
				priceContentRespectingFilter(),
				referenceContentAllWithAttributes(),
				dataInLocales(Locale.ENGLISH)
			),
			originalCategories,
			Locale.ENGLISH,
			null,
			false
		);
	}

	@DisplayName("Should limit custom entity model instance")
	@ParameterizedTest
	@MethodSource("testedOpenPojoClasses")
	@UseDataSet(HUNDRED_PRODUCTS)
	void limitCustomEntity(
		Class<? extends AbstractProductPojo> theClass,
		EvitaSessionContract evitaSession,
		SealedEntity productWithCzkSellingPrice
	) {
		final AbstractProductPojo partiallyLoadedEntity = evitaSession
			.getEntity(
				theClass, productWithCzkSellingPrice.getPrimaryKey(),
				hierarchyContent(),
				attributeContentAll(),
				associatedDataContentAll(),
				priceContentAll(),
				referenceContentAllWithAttributes(),
				dataInLocales(Locale.ENGLISH)
			)
			.orElse(null);

		final AbstractProductPojo limitedProduct = evitaSession.enrichOrLimitEntity(
			partiallyLoadedEntity,
			attributeContentAll()
		);

		assertProductBasicData(productWithCzkSellingPrice, limitedProduct);
		assertProductAttributes(productWithCzkSellingPrice, limitedProduct, Locale.ENGLISH);
		assertNull(limitedProduct.getReferencedFileSet());
		assertNull(limitedProduct.getReferencedFileSetAsDifferentProperty());
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
	void queryOneSealedEntity(
		EvitaSessionContract evitaSession,
		SealedEntity productWithCzkSellingPrice
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(productWithCzkSellingPrice.getPrimaryKey())),
			require(entityFetchAll())
		);

		assertEquals(productWithCzkSellingPrice, evitaSession.queryOneSealedEntity(query).orElseThrow());
		assertEquals(productWithCzkSellingPrice, evitaSession.queryOne(query, SealedEntity.class).orElseThrow());
	}

	@DisplayName("Should return custom entity model instance")
	@UseDataSet(HUNDRED_PRODUCTS)
	@ParameterizedTest
	@MethodSource("returnRandomSeedAndTestPojoClasses")
	void queryOneRandomizedEntity(
		Class<? extends AbstractProductPojo> theClass,
		long seed,
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final Random rnd = new Random(seed);
		final int primaryKey = rnd.nextInt(49) + 1;

		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() >= primaryKey && it.getPriceForSale(CURRENCY_CZK, null, PRICE_LIST_BASIC).isPresent())
			.findFirst()
			.orElseThrow();

		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				entityPrimaryKeyInSet(theProduct.getPrimaryKey()),
				priceInCurrency(CURRENCY_CZK),
				attributeEquals(ATTRIBUTE_ENUM, TestEnum.valueOf(theProduct.getAttribute(ATTRIBUTE_ENUM, String.class)))
			),
			require(
				entityFetch(
					hierarchyContent(),
					attributeContentAll(),
					associatedDataContentAll(),
					priceContentRespectingFilter(),
					referenceContentAllWithAttributes(),
					dataInLocales(Locale.ENGLISH)
				)
			)
		);

		assertProduct(
			theProduct,
			evitaSession.queryOne(query, theClass).orElse(null),
			originalCategories,
			Locale.ENGLISH,
			CURRENCY_CZK,
			false
		);
	}

	@DisplayName("Should return custom entity model instance")
	@ParameterizedTest
	@MethodSource("testedPojoClasses")
	@UseDataSet(HUNDRED_PRODUCTS)
	void queryOneCustomEntity(
		Class<? extends AbstractProductPojo> theClass,
		EvitaSessionContract evitaSession,
		SealedEntity productWithCzkSellingPrice,
		Map<Integer, SealedEntity> originalCategories
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				entityPrimaryKeyInSet(productWithCzkSellingPrice.getPrimaryKey()),
				priceInCurrency(CURRENCY_CZK)
			),
			require(
				entityFetch(
					hierarchyContent(),
					attributeContentAll(),
					associatedDataContentAll(),
					priceContentRespectingFilter(),
					referenceContentAllWithAttributes(),
					dataInLocales(Locale.ENGLISH)
				)
			)
		);

		assertProduct(
			productWithCzkSellingPrice,
			evitaSession.queryOne(query, theClass).orElse(null),
			originalCategories,
			Locale.ENGLISH,
			CURRENCY_CZK,
			false
		);
	}

	@DisplayName("Should return same proxy instances for repeated calls")
	@ParameterizedTest
	@MethodSource("testedPojoClasses")
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldReturnSameInstancesForRepeatedCalls(
		Class<? extends AbstractProductPojo> theClass,
		EvitaSessionContract evitaSession,
		SealedEntity productWithCzkSellingPrice
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(productWithCzkSellingPrice.getPrimaryKey())),
			require(
				entityFetch(
					hierarchyContent(),
					attributeContentAll(),
					associatedDataContentAll(),
					priceContentRespectingFilter(),
					referenceContentAllWithAttributes(),
					dataInLocales(Locale.ENGLISH)
				)
			)
		);

		final AbstractProductPojo product = evitaSession.queryOne(query, theClass)
			.orElseThrow();

		for (int i = 0; i < 2; i++) {
			final AbstractProductCategoryPojo[] array1 = product.getProductCategoriesAsArray();
			final AbstractProductCategoryPojo[] array2 = product.getProductCategoriesAsArray();
			for (int j = 0; j < array1.length; j++) {
				final AbstractProductCategoryPojo productCategory1 = array1[j];
				final AbstractProductCategoryPojo productCategory2 = array2[j];

				assertSame(productCategory1, productCategory2);
				assertSame(productCategory1.getCategory(), productCategory2.getCategory());
			}

			final AbstractCategoryPojo[] catArray1 = product.getCategoriesAsArray();
			final AbstractCategoryPojo[] catArray2 = product.getCategoriesAsArray();
			for (int j = 0; j < catArray1.length; j++) {
				final AbstractCategoryPojo category1 = catArray1[j];
				final AbstractCategoryPojo category2 = catArray2[j];

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
	@ParameterizedTest
	@MethodSource("testedPojoClasses")
	@UseDataSet(HUNDRED_PRODUCTS)
	void queryListOfCustomEntities(
		Class<? extends AbstractProductPojo> theClass,
		EvitaSessionContract evitaSession,
		List<SealedEntity> productsWithCzkSellingPrice,
		Map<Integer, SealedEntity> originalCategories
	) {
		final int[] fetchedProducts = productsWithCzkSellingPrice.stream()
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.toArray();
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				entityPrimaryKeyInSet(fetchedProducts),
				priceInCurrency(CURRENCY_CZK)
			),
			require(
				entityFetch(
					hierarchyContent(),
					attributeContentAll(),
					associatedDataContentAll(),
					priceContentRespectingFilter(),
					referenceContentAllWithAttributes(),
					dataInLocales(Locale.ENGLISH)
				)
			)
		);

		final List<? extends AbstractProductPojo> products = evitaSession.queryList(query, theClass);
		for (int i = 0; i < productsWithCzkSellingPrice.size(); i++) {
			final SealedEntity expectedProduct = productsWithCzkSellingPrice.get(i);
			final AbstractProductPojo actualProduct = products.get(i);
			assertProduct(
				expectedProduct,
				actualProduct,
				originalCategories,
				Locale.ENGLISH,
				CURRENCY_CZK,
				false
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
	@ParameterizedTest
	@MethodSource("testedPojoClasses")
	@UseDataSet(HUNDRED_PRODUCTS)
	void queryCustomEntities(
		Class<? extends AbstractProductPojo> theClass,
		EvitaSessionContract evitaSession,
		List<SealedEntity> productsWithCzkSellingPrice,
		Map<Integer, SealedEntity> originalCategories
	) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(
				entityPrimaryKeyInSet(
					productsWithCzkSellingPrice.stream().mapToInt(EntityContract::getPrimaryKey).toArray()
				),
				priceInCurrency(CURRENCY_CZK)
			),
			require(
				entityFetch(
					hierarchyContent(),
					attributeContentAll(),
					associatedDataContentAll(),
					priceContentRespectingFilter(),
					referenceContentAllWithAttributes(),
					dataInLocales(Locale.ENGLISH)
				)
			)
		);

		final List<? extends AbstractProductPojo> products = evitaSession.query(query, theClass).getRecordData();
		for (int i = 0; i < productsWithCzkSellingPrice.size(); i++) {
			final SealedEntity expectedProduct = productsWithCzkSellingPrice.get(i);
			final AbstractProductPojo actualProduct = products.get(i);
			assertProduct(
				expectedProduct,
				actualProduct,
				originalCategories,
				Locale.ENGLISH,
				CURRENCY_CZK,
				false
			);
		}
	}

	@DisplayName("Should wrap an pojo and load data in single localization")
	@ParameterizedTest
	@MethodSource("testedPojoClasses")
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldProxyToPojoWithCzechLocalization(
		Class<? extends AbstractProductPojo> theClass,
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getReferences(Entities.CATEGORY).size() > 1)
			.filter(it -> it.getPriceForSale(CURRENCY_CZK, null, PRICE_LIST_BASIC).isPresent())
			.findFirst()
			.orElseThrow();

		final Optional<? extends AbstractProductPojo> productRef = evitaSession.queryOne(
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(theProduct.getPrimaryKey()),
					entityLocaleEquals(CZECH_LOCALE),
					priceInCurrency(CURRENCY_CZK)
				),
				require(
					entityFetch(
						attributeContentAll(),
						associatedDataContentAll(),
						priceContentRespectingFilter(),
						referenceContentAllWithAttributes(
							entityFetch(
								hierarchyContent(
									entityFetch(
										hierarchyContent(
											entityFetchAll()
										),
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
			theClass
		);

		assertProduct(
			theProduct,
			productRef.orElse(null),
			originalCategories,
			CZECH_LOCALE,
			CURRENCY_CZK,
			true
		);
	}

	@DisplayName("Should wrap an pojo and load all data")
	@ParameterizedTest
	@MethodSource("testedPojoClasses")
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldProxyToPojo(
		Class<? extends AbstractProductPojo> theClass,
		EvitaSessionContract evitaSession,
		List<SealedEntity> originalProducts,
		Map<Integer, SealedEntity> originalCategories
	) {
		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getReferences(Entities.CATEGORY).size() > 1)
			.filter(it -> it.getPriceForSale(CURRENCY_CZK, null, PRICE_LIST_BASIC).isPresent())
			.findFirst()
			.orElseThrow();

		final Optional<? extends AbstractProductPojo> productRef = evitaSession.getEntity(
			theClass,
			theProduct.getPrimaryKey(),
			dataInLocales(Locale.ENGLISH),
			attributeContentAll(),
			associatedDataContentAll(),
			priceContentRespectingFilter(),
			referenceContentAllWithAttributes(
				entityFetch(
					hierarchyContent(
						entityFetch(
							hierarchyContent(
								entityFetchAll()
							),
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
			null,
			true
		);
	}

}
