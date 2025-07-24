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
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.proxy.mock.CategoryInterface;
import io.evitadb.api.proxy.mock.ProductCategoryInterface;
import io.evitadb.api.proxy.mock.ProductInterface;
import io.evitadb.api.proxy.mock.TestEntity;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES;
import static io.evitadb.test.generator.DataGenerator.CURRENCY_CZK;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_BASIC;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the ability to proxy an entity into an arbitrary interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Evita entity interface proxying functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@TestMethodOrder(MethodOrderer.MethodName.class)
@Slf4j
public class EntityInterfaceProxyingFunctionalTest extends AbstractEntityProxyingFunctionalTest implements EvitaTestSupport {
	private static final String HUNDRED_PRODUCTS = "HundredProxyProducts_EntityInterfaceProxyingFunctionalTest";
	private static final Locale CZECH_LOCALE = new Locale("cs", "CZ");
	private static final ReflectionLookup REFLECTION_LOOKUP = new ReflectionLookup(ReflectionCachingBehaviour.CACHE);

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
				assertEquals(attributeValue.value(), category.getName(attributeValue.key().locale()));
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

		assertInstanceOf(SealedEntityReferenceProxy.class, productCategory);
		final ReferenceContract theReference = ((SealedEntityReferenceProxy) productCategory).getReference();
		final Long categoryPriority = theReference.getAttribute(DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY, Long.class);
		assertEquals(categoryPriority, productCategory.getOrderInCategory());
		assertEquals(categoryPriority == null ? OptionalLong.empty() : OptionalLong.of(categoryPriority), productCategory.getOrderInCategoryIfPresent());

		if (locale == null) {
			for (AttributeValue attributeValue : theReference.getAttributeValues(ATTRIBUTE_CATEGORY_LABEL)) {
				assertEquals(attributeValue.value(), productCategory.getLabel(attributeValue.key().locale()));
			}
		} else {
			assertEquals(theReference.getAttribute(ATTRIBUTE_CATEGORY_LABEL, locale), productCategory.getLabel());
			assertEquals(theReference.getAttribute(ATTRIBUTE_CATEGORY_LABEL, locale), productCategory.getLabel(locale));
		}

		if (externalEntities) {
			assertCategory(productCategory.getCategory(), sealedEntity, locale);
			assertTrue(productCategory.getCategoryIfPresentAndFetched().isPresent());
			assertEquals(new EntityReference(Entities.CATEGORY, sealedEntity.getPrimaryKey()), productCategory.getCategoryReference());
			assertTrue(productCategory.getCategoryReferenceIfPresent().isPresent());
			assertEquals(sealedEntity.getPrimaryKey(), productCategory.getCategoryReferencePrimaryKey());
			assertTrue(productCategory.getCategoryReferencePrimaryKeyIfPresent().isPresent());
		} else {
			assertThrows(ContextMissingException.class, productCategory::getCategory);
			assertEquals(empty(), productCategory.getCategoryIfPresentAndFetched());
			assertThrows(ContextMissingException.class, productCategory::getCategoryIfPresent);
			assertEquals(of(new EntityReference(Entities.CATEGORY, sealedEntity.getPrimaryKey())), productCategory.getCategoryReferenceIfPresent());
			assertEquals(OptionalInt.of(sealedEntity.getPrimaryKey()), productCategory.getCategoryReferencePrimaryKeyIfPresent());
		}

	}

	private static void assertCategoryParent(
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nonnull CategoryInterface category,
		@Nullable Locale locale
	) {
		final SealedEntity originalCategory = originalCategories.get(category.getId());
		if (originalCategory.getParentEntity().isEmpty()) {
			assertNull(category.getParentId());
			assertTrue(category.getParentIdIfPresent().isEmpty());
			assertNull(category.getParentEntityReference());
			assertTrue(category.getParentEntityReferenceIfPresent().isEmpty());
			assertNull(category.getParentEntity());
			assertTrue(category.getParentEntityIfPresent().isEmpty());
		} else {
			final int expectedParentId = originalCategory.getParentEntity().get().getPrimaryKey();
			assertEquals(
				expectedParentId,
				category.getParentId()
			);
			assertEquals(
				OptionalInt.of(expectedParentId),
				category.getParentIdIfPresent()
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
			assertEquals(
				Optional.of(new EntityReference(Entities.CATEGORY, expectedParentId)),
				category.getParentEntityReferenceIfPresent()
			);
			assertCategory(category.getParentEntity(), originalCategories.get(expectedParentId), locale);
			assertEquals(
				Optional.of(category.getParentEntity()),
				category.getParentEntityIfPresent()
			);
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
		@Nullable Currency currency,
		boolean externalEntities
	) {
		assertProductBasicData(originalProduct, product);
		assertProductLocales(originalProduct, product, locale);
		assertProductAttributes(originalProduct, product, locale);
		assertProductAssociatedData(originalProduct, product, locale);

		final Optional<ReferencedFileSet> referenceFileSetOptional = ofNullable(originalProduct.getAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES, ReferencedFileSet.class, ReflectionLookup.NO_CACHE_INSTANCE));
		referenceFileSetOptional
			.ifPresent(it -> {
				assertEquals(it, product.getReferencedFileSet());
				assertEquals(it, product.getReferencedFileSetAsDifferentProperty());
			});
		assertEquals(referenceFileSetOptional, product.getReferencedFileSetIfPresent());

		assertCategoryParents(product.getCategories(), originalCategories, locale);
		if (product.getCategories().isEmpty()) {
			assertTrue(product.getCategoriesIfFetched().isEmpty());
		} else {
			assertTrue(product.getCategoriesIfFetched().isPresent());
		}

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
		assertEquals(empty(), product.getPriceForSaleIfPresent());

		PriceContract[] expectedAllPricesForSale = null;
		try {
			final PriceContract[] allPricesForSale = product.getAllPricesForSale();
			expectedAllPricesForSale = originalProduct.getAllPricesForSale().toArray(PriceContract[]::new);
			assertEquals(expectedAllPricesForSale.length, allPricesForSale.length);
			assertArrayEquals(expectedAllPricesForSale, allPricesForSale);
		} catch (ContextMissingException ex) {
			assertFalse(originalProduct.isPriceForSaleContextAvailable());
		}

		if (expectedAllPricesForSale != null && expectedAllPricesForSale.length > 0) {
			final PriceContract expectedPrice = expectedAllPricesForSale[0];
			assertEquals(
				expectedPrice,
				product.getPriceForSale(expectedPrice.priceList(), expectedPrice.currency())
			);
			if (expectedPrice.validity() != null) {
				assertEquals(
					expectedPrice,
					product.getPriceForSale(
						expectedPrice.priceList(),
						expectedPrice.currency(),
						ofNullable(expectedPrice.validity().getPreciseFrom())
							.orElse(expectedPrice.validity().getPreciseTo())
					)
				);
			}

			assertArrayEquals(
				originalProduct.getAllPricesForSale()
					.stream()
					.filter(it -> it.priceList().equals(expectedPrice.priceList()))
					.toArray(PriceContract[]::new),
				product.getAllPricesForSale(expectedPrice.priceList())
			);

			assertArrayEquals(
				originalProduct.getAllPricesForSale()
					.stream()
					.filter(it -> it.currency().equals(expectedPrice.currency()))
					.toArray(PriceContract[]::new),
				product.getAllPricesForSale(expectedPrice.currency())
			);

			assertArrayEquals(
				originalProduct.getAllPricesForSale()
					.stream()
					.filter(it -> it.currency().equals(expectedPrice.currency()) && it.priceList().equals(expectedPrice.priceList()))
					.toArray(PriceContract[]::new),
				product.getAllPricesForSale(expectedPrice.priceList(), expectedPrice.currency())
			);
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
		final Optional<PriceContract> first = Arrays.stream(expectedAllPrices).filter(it -> "basic".equals(it.priceList())).findFirst();
		if (first.isEmpty()) {
			assertNull(product.getBasicPrice());
			assertEquals(empty(), product.getBasicPriceIfPresent());
		} else {
			assertEquals(
				first.get(),
				product.getBasicPrice()
			);
			assertEquals(
				first,
				product.getBasicPriceIfPresent()
			);
		}
		}
	}

	private static void assertProductLocales(
		@Nonnull SealedEntity originalProduct,
		@Nullable ProductInterface product,
		@Nullable Locale filteredLocale
	) {
		final Set<Locale> allLocales = product.allLocales();
		final Set<Locale> expectedAllLocales = originalProduct.getAllLocales();
		assertEquals(expectedAllLocales.size(), allLocales.size());
		allLocales.forEach(locale -> assertTrue(expectedAllLocales.contains(locale)));

		final Set<Locale> locales = product.locales();
		final Set<Locale> expectedLocales = filteredLocale == null ? originalProduct.getLocales() : Set.of(filteredLocale);
		assertEquals(expectedLocales.size(), locales.size());
		locales.forEach(locale -> assertTrue(expectedLocales.contains(locale)));
	}

	private static void assertProductBasicData(@Nonnull SealedEntity originalProduct, @Nullable ProductInterface product) {
		assertNotNull(product);
		assertEquals(originalProduct.version(), product.version());
		assertEquals(Entities.PRODUCT, product.entitySchema().getName());
		assertEquals(originalProduct.getPrimaryKey(), product.getPrimaryKey());
		assertEquals(originalProduct.getPrimaryKey(), product.getId());
		assertEquals(Entities.PRODUCT, product.getType());
		assertEquals(TestEntity.PRODUCT, product.getEntityType());
		assertEquals(Scope.LIVE, product.getScope());
	}

	private static void assertProductAttributes(@Nonnull SealedEntity originalProduct, @Nonnull ProductInterface product, @Nullable Locale locale) {
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_CODE), product.getCode());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_NAME, locale), product.getName());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.getQuantity());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.getQuantityAsDifferentProperty());
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_ALIAS), product.isAlias());
		assertEquals(TestEnum.valueOf(originalProduct.getAttribute(ATTRIBUTE_ENUM, String.class)), product.getEnum());

		// methods with implementation not directly annotated by @Attribute annotation are not intercepted
		assertEquals("computed EAN", product.getEan());
		// methods with different name are intercepted based on @Attribute annotation
		assertEquals(originalProduct.getAttribute(DataGenerator.ATTRIBUTE_EAN),  product.getEanAsDifferentProperty());

		final Optional<Object> optionallyAvailable = ofNullable(originalProduct.getAttribute(ATTRIBUTE_OPTIONAL_AVAILABILITY));
		assertEquals(optionallyAvailable.orElse(false), product.isOptionallyAvailable());
		assertEquals(optionallyAvailable, product.getOptionallyAvailable());

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

	private static void assertProductAssociatedData(@Nonnull SealedEntity originalProduct, @Nonnull ProductInterface product, @Nullable Locale locale) {
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

	@DataSet(value = HUNDRED_PRODUCTS, destroyAfterClass = true, readOnly = false)
	@Override
	protected DataCarrier setUp(Evita evita) {
		final DataCarrier dataCarrier = super.setUp(evita);


		final List<SealedEntity> originalProducts = (List<SealedEntity>) dataCarrier.getValueByName("originalProducts");
		final List<SealedEntity> productsWithCzkSellingPrice = originalProducts
			.stream()
			.filter(it -> it.getLocales().contains(Locale.ENGLISH))
			.filter(it -> it.getPriceForSale(CURRENCY_CZK, null, PRICE_LIST_BASIC).isPresent())
			.limit(2)
			.toList();
		assertEquals(2, productsWithCzkSellingPrice.size());

		return dataCarrier
			.put("productWithCzkSellingPrice", productsWithCzkSellingPrice.get(0))
			.put("productsWithCzkSellingPrice", productsWithCzkSellingPrice);
	}

	@DisplayName("Should return entity schema directly or via model class")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldReturnEntitySchema(EvitaSessionContract evitaSession) {
		assertNotNull(evitaSession.getEntitySchema(Entities.PRODUCT).orElse(null));
		assertNotNull(evitaSession.getEntitySchema(ProductInterface.class).orElse(null));
		assertSame(
			evitaSession.getEntitySchema(Entities.PRODUCT).orElseThrow(),
			evitaSession.getEntitySchema(ProductInterface.class).orElseThrow()
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
		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 1)
			.findFirst()
			.orElseThrow();

		final ProductInterface product = evitaSession.getEntity(
			ProductInterface.class, 1, entityFetchAllContent()
		).orElse(null);

		assertNotNull(product.entity());
		assertProduct(
			theProduct,
			product,
			originalCategories,
			null,
			null,
			false
		);
	}

	@DisplayName("Should enrich custom entity model instance")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
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
			null,
			null,
			false
		);
	}

	@DisplayName("Should limit custom entity model instance")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
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
		assertThrows(ContextMissingException.class, limitedProduct::getReferencedFileSet);
		assertThrows(ContextMissingException.class, limitedProduct::getReferencedFileSetAsDifferentProperty);
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
					dataInLocalesAll()
				)
			)
		);

		assertProduct(
			theProduct,
			evitaSession.queryOne(query, ProductInterface.class).orElse(null),
			originalCategories,
			null,
			CURRENCY_CZK,
			false
		);
	}

	@DisplayName("Should return custom entity model instance")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void queryOneCustomEntity(
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
					dataInLocalesAll()
				)
			)
		);

		assertProduct(
			productWithCzkSellingPrice,
			evitaSession.queryOne(query, ProductInterface.class).orElse(null),
			originalCategories,
			null,
			CURRENCY_CZK,
			false
		);
	}

	@DisplayName("Should return same proxy instances for repeated calls")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldReturnSameInstancesForRepeatedCalls(EvitaSessionContract evitaSession) {
		final Query query = query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(1)),
			require(
				entityFetch(
					attributeContentAll(), hierarchyContent(),
					associatedDataContentAll(), priceContentAll(),
					referenceContentAllWithAttributes(
						entityFetchAll(), entityGroupFetchAll()
					), dataInLocalesAll()
				)
			)
		);

		final ProductInterface product = evitaSession.queryOne(query, ProductInterface.class)
			.orElseThrow();

		for(int i = 0; i < 2; i++) {
			final ProductCategoryInterface[] array1 = product.getProductCategoriesAsArray();
			final ProductCategoryInterface[] array2 = product.getProductCategoriesAsArray();
			for (int j = 0; j < array1.length; j++) {
				final ProductCategoryInterface productCategory1 = array1[j];
				final ProductCategoryInterface productCategory2 = array2[j];

				assertSame(productCategory1, productCategory2);
				assertSame(productCategory1.getCategory(), productCategory2.getCategory());
			}

			final CategoryInterface[] catArray1 = product.getCategoriesAsArray();
			final CategoryInterface[] catArray2 = product.getCategoriesAsArray();
			for (int j = 0; j < catArray1.length; j++) {
				final CategoryInterface category1 = catArray1[j];
				final CategoryInterface category2 = catArray2[j];

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
		List<SealedEntity> productsWithCzkSellingPrice,
		Map<Integer, SealedEntity> originalCategories
	) {
		final int[] fetchedProducts = productsWithCzkSellingPrice.stream()
			.mapToInt(EntityContract::getPrimaryKey)
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

		final List<ProductInterface> products = evitaSession.queryList(query, ProductInterface.class);
		for (int i = 0; i < productsWithCzkSellingPrice.size(); i++) {
			final SealedEntity expectedProduct = productsWithCzkSellingPrice.get(i);
			final ProductInterface actualProduct = products.get(i);
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

		final List<ProductInterface> products = evitaSession.query(query, ProductInterface.class).getRecordData();
		for (int i = 0; i < productsWithCzkSellingPrice.size(); i++) {
			final SealedEntity expectedProduct = productsWithCzkSellingPrice.get(i);
			final ProductInterface actualProduct = products.get(i);
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

	@DisplayName("Should wrap an interface and load data in single localization")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldProxyToInterfaceWithCzechLocalization(
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

		final Optional<ProductInterface> productRef = evitaSession.queryOne(
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(theProduct.getPrimaryKey()),
					priceInCurrency(CURRENCY_CZK),
					entityLocaleEquals(CZECH_LOCALE)
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
			ProductInterface.class
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

	@DisplayName("Should wrap an interface and load all data")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldProxyToInterface(
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

		final Optional<ProductInterface> productRef = evitaSession.getEntity(
			ProductInterface.class,
			theProduct.getPrimaryKey(),
			attributeContentAll(),
			associatedDataContentAll(),
			priceContentAll(),
			referenceContentAllWithAttributes(
				entityFetch(
					hierarchyContent(
						entityFetchAll()
					),
					attributeContentAll(),
					associatedDataContentAll()
				),
				entityGroupFetchAll()
			),
			dataInLocalesAll()
		);

		assertProduct(
			theProduct,
			productRef.orElse(null),
			originalCategories,
			null,
			null,
			true
		);
	}

	@DisplayName("Should not throw exception when accessing optional non-fetched data")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldNotThrowExceptionWhenAccessingOptionalNonFetchedData(
		EvitaSessionContract evitaSession
	) {
		final ProductInterface product = evitaSession.getEntity(ProductInterface.class, 1).orElse(null);
		assertTrue(product.getOptionallyAvailable().isEmpty());
		assertTrue(product.getMarketsIfAvailable().isEmpty());
		assertTrue(product.getMarketsAssociatedDataIfAvailable().isEmpty());
		assertTrue(product.getReferencedFileSetIfPresent().isEmpty());
		assertTrue(product.getCategoriesIfFetched().isEmpty());
		assertTrue(product.getPriceForSaleIfPresent().isEmpty());
		assertTrue(product.getBasicPriceIfPresent().isEmpty());

		final CategoryInterface category = evitaSession.getEntity(CategoryInterface.class, 1).orElse(null);
		assertTrue(category.getParentEntityIfPresent().isEmpty());
	}

	@DisplayName("Should delete entity")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void x_deleteEntity(
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

	@DisplayName("Should archive entity")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void x_archiveEntity(
		EvitaContract evita,
		List<SealedEntity> originalProducts
	) {
		final SealedEntity theProduct = originalProducts
			.stream()
			.filter(it -> it.getPrimaryKey() == 49)
			.findFirst()
			.orElseThrow();

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productToArchive = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(49)
						),
						require(
							entityFetchAll()
						)
					)
				).orElseThrow();
				assertEquals(theProduct, productToArchive);

				productToArchive
					.openForWrite()
					.setScope(Scope.ARCHIVED)
					.upsertVia(session);

				assertTrue(session.getEntity(Entities.PRODUCT, 49, entityFetchAllContent()).isEmpty());
				assertTrue(session.getEntity(Entities.PRODUCT, 49, new Scope[] { Scope.ARCHIVED }, entityFetchAllContent()).isPresent());
			}
		);
	}

	@DisplayName("Should delete entity with hierarchy")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void x_deleteEntityWithHierarchy(
		EvitaContract evita,
		Map<Integer, SealedEntity> originalCategories
	) {
		final SealedEntity theCategory = originalCategories
			.values()
			.stream()
			.max(Comparator.comparingInt(EntityContract::getPrimaryKeyOrThrowException))
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
