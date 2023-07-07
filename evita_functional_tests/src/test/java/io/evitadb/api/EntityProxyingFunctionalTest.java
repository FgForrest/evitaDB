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

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.mock.CategoryInterface;
import io.evitadb.api.mock.ProductCategoryInterface;
import io.evitadb.api.mock.ProductInterface;
import io.evitadb.api.mock.TestEntity;
import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;
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
	private static final Locale CZECH_LOCALE = new Locale("cs", "CZ");

	private static void assertCategoryEntityReferences(
		@Nonnull Stream<EntityReference> categoryReferences,
		@Nonnull int[] expectedCategoryIds
	) {
		assertNotNull(categoryReferences);
		final EntityReference[] references = categoryReferences
			.sorted()
			.toArray(EntityReference[]::new);

		assertEquals(3, references.length);
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
		@Nullable Locale locale
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
				locale
			);
		}
	}

	private static void assertCategoryReference(
		@Nonnull ProductCategoryInterface productCategory,
		@Nonnull SealedEntity sealedEntity,
		@Nullable Locale locale
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

		assertCategory(productCategory.getCategory(), sealedEntity, locale);
		assertEquals(new EntityReference(Entities.CATEGORY, sealedEntity.getPrimaryKey()), productCategory.getCategoryReference());
		assertEquals(sealedEntity.getPrimaryKey(), productCategory.getCategoryReferencePrimaryKey());

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

	@DisplayName("Should downgrade from SealedEntity to EntityReference")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void shouldProxyToEntityReference(EvitaSessionContract evitaSession) {
		final Optional<EntityReference> theReference = evitaSession.getEntity(
			Entities.PRODUCT, EntityReference.class, 1, entityFetchAllContent()
		);
		assertTrue(theReference.isPresent());
		assertEquals(1, theReference.get().getPrimaryKey());
		assertEquals(Entities.PRODUCT, theReference.get().getType());
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

		assertTrue(productRef.isPresent());
		final ProductInterface product = productRef.get();
		assertEquals(theProduct.getPrimaryKey(), product.getPrimaryKey());
		assertEquals(theProduct.getPrimaryKey(), product.getId());
		assertEquals(Entities.PRODUCT, product.getType());
		assertEquals(TestEntity.PRODUCT, product.getEntityType());
		assertEquals(theProduct.getAttribute(DataGenerator.ATTRIBUTE_CODE), product.getCode());
		assertEquals(theProduct.getAttribute(DataGenerator.ATTRIBUTE_NAME, CZECH_LOCALE), product.getName());
		assertEquals(theProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.getQuantity());
		assertEquals(theProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.getQuantityAsDifferentProperty());
		assertEquals(theProduct.getAttribute(DataGenerator.ATTRIBUTE_ALIAS), product.isAlias());
		assertEquals(new ReferencedFileSet(null), product.getReferencedFileSet());
		assertEquals(new ReferencedFileSet(null), product.getReferencedFileSetAsDifferentProperty());

		assertCategoryParents(product.getCategories(), originalCategories, CZECH_LOCALE);

		final int[] expectedCategoryIds = theProduct.getReferences(Entities.CATEGORY)
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

		assertCategories(product.getCategories().stream(), originalCategories, expectedCategoryIds, CZECH_LOCALE);
		assertCategories(product.getCategoriesAsList().stream(), originalCategories, expectedCategoryIds, CZECH_LOCALE);
		assertCategories(product.getCategoriesAsSet().stream(), originalCategories, expectedCategoryIds, CZECH_LOCALE);
		assertCategories(Arrays.stream(product.getCategoriesAsArray()), originalCategories, expectedCategoryIds, CZECH_LOCALE);

		assertCategoryReferences(product.getProductCategories().stream(), originalCategories, expectedCategoryIds, CZECH_LOCALE);
		assertCategoryReferences(product.getProductCategoriesAsList().stream(), originalCategories, expectedCategoryIds, CZECH_LOCALE);
		assertCategoryReferences(product.getProductCategoriesAsSet().stream(), originalCategories, expectedCategoryIds, CZECH_LOCALE);
		assertCategoryReferences(Arrays.stream(product.getProductCategoriesAsArray()), originalCategories, expectedCategoryIds, CZECH_LOCALE);

		assertThrows(ContextMissingException.class, product::getPriceForSale);

		final PriceContract[] allPricesForSale = product.getAllPricesForSale();
		final PriceContract[] expectedAllPricesForSale = theProduct.getAllPricesForSale().toArray(PriceContract[]::new);
		assertEquals(expectedAllPricesForSale.length, allPricesForSale.length);
		assertArrayEquals(expectedAllPricesForSale, allPricesForSale);

		assertEquals(expectedAllPricesForSale[0], product.getPriceForSale(expectedAllPricesForSale[0].getPriceList(), expectedAllPricesForSale[0].getCurrency()));
		assertEquals(
			expectedAllPricesForSale[1],
			product.getPriceForSale(expectedAllPricesForSale[1].getPriceList(), expectedAllPricesForSale[1].getCurrency(), expectedAllPricesForSale[1].getValidity().getPreciseFrom())
		);

		assertArrayEquals(
			theProduct.getAllPricesForSale()
				.stream()
				.filter(it -> it.getPriceList().equals(expectedAllPricesForSale[0].getPriceList()))
				.toArray(PriceContract[]::new),
			product.getAllPricesForSale(expectedAllPricesForSale[0].getPriceList())
		);

		assertArrayEquals(
			theProduct.getAllPricesForSale()
				.stream()
				.filter(it -> it.getCurrency().equals(expectedAllPricesForSale[0].getCurrency()))
				.toArray(PriceContract[]::new),
			product.getAllPricesForSale(expectedAllPricesForSale[0].getCurrency())
		);

		assertArrayEquals(
			theProduct.getAllPricesForSale()
				.stream()
				.filter(it -> it.getCurrency().equals(expectedAllPricesForSale[0].getCurrency()) && it.getPriceList().equals(expectedAllPricesForSale[0].getPriceList()))
				.toArray(PriceContract[]::new),
			product.getAllPricesForSale(expectedAllPricesForSale[0].getPriceList(), expectedAllPricesForSale[0].getCurrency())
		);

		final PriceContract[] allPrices = Arrays.stream(product.getAllPricesAsArray())
			
			.toArray(PriceContract[]::new);;
		final PriceContract[] expectedAllPrices = theProduct.getPrices().toArray(PriceContract[]::new);
		assertEquals(expectedAllPrices.length, allPrices.length);
		assertArrayEquals(expectedAllPrices, allPrices);
		assertArrayEquals(expectedAllPrices, product.getAllPricesAsList().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.getAllPricesAsSet().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.getAllPrices().toArray(PriceContract[]::new));

		assertEquals(
			Arrays.stream(expectedAllPrices).filter(it -> "basic".equals(it.getPriceList())).findFirst().orElseThrow(),
			product.getBasicPrice()
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
			Entities.PRODUCT, ProductInterface.class, theProduct.getPrimaryKey(),
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

		assertTrue(productRef.isPresent());
		final ProductInterface product = productRef.get();
		assertEquals(theProduct.getPrimaryKey(), product.getPrimaryKey());
		assertEquals(theProduct.getPrimaryKey(), product.getId());
		assertEquals(Entities.PRODUCT, product.getType());
		assertEquals(TestEntity.PRODUCT, product.getEntityType());
		assertEquals(theProduct.getAttribute(DataGenerator.ATTRIBUTE_CODE), product.getCode());
		assertEquals(theProduct.getAttribute(DataGenerator.ATTRIBUTE_NAME, CZECH_LOCALE), product.getName());
		assertEquals(theProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.getQuantity());
		assertEquals(theProduct.getAttribute(DataGenerator.ATTRIBUTE_QUANTITY), product.getQuantityAsDifferentProperty());
		assertEquals(theProduct.getAttribute(DataGenerator.ATTRIBUTE_ALIAS), product.isAlias());
		assertEquals(new ReferencedFileSet(null), product.getReferencedFileSet());
		assertEquals(new ReferencedFileSet(null), product.getReferencedFileSetAsDifferentProperty());

		assertCategoryParents(product.getCategories(), originalCategories, null);

		final int[] expectedCategoryIds = theProduct.getReferences(Entities.CATEGORY)
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

		assertCategories(product.getCategories().stream(), originalCategories, expectedCategoryIds, null);
		assertCategories(product.getCategoriesAsList().stream(), originalCategories, expectedCategoryIds, null);
		assertCategories(product.getCategoriesAsSet().stream(), originalCategories, expectedCategoryIds, null);
		assertCategories(Arrays.stream(product.getCategoriesAsArray()), originalCategories, expectedCategoryIds, null);

		assertCategoryReferences(product.getProductCategories().stream(), originalCategories, expectedCategoryIds, null);
		assertCategoryReferences(product.getProductCategoriesAsList().stream(), originalCategories, expectedCategoryIds, null);
		assertCategoryReferences(product.getProductCategoriesAsSet().stream(), originalCategories, expectedCategoryIds, null);
		assertCategoryReferences(Arrays.stream(product.getProductCategoriesAsArray()), originalCategories, expectedCategoryIds, null);

		final PriceContract[] allPricesForSale = product.getAllPricesForSale();
		final PriceContract[] expectedAllPricesForSale = theProduct.getAllPricesForSale().toArray(PriceContract[]::new);
		assertEquals(expectedAllPricesForSale.length, allPricesForSale.length);
		assertArrayEquals(expectedAllPricesForSale, allPricesForSale);

		assertEquals(expectedAllPricesForSale[0], product.getPriceForSale(expectedAllPricesForSale[0].getPriceList(), expectedAllPricesForSale[0].getCurrency()));
		assertEquals(
			expectedAllPricesForSale[1],
			product.getPriceForSale(expectedAllPricesForSale[1].getPriceList(), expectedAllPricesForSale[1].getCurrency(), expectedAllPricesForSale[1].getValidity().getPreciseFrom())
		);

		assertArrayEquals(
			theProduct.getAllPricesForSale()
				.stream()
				.filter(it -> it.getPriceList().equals(expectedAllPricesForSale[0].getPriceList()))
				.toArray(PriceContract[]::new),
			product.getAllPricesForSale(expectedAllPricesForSale[0].getPriceList())
		);

		assertArrayEquals(
			theProduct.getAllPricesForSale()
				.stream()
				.filter(it -> it.getCurrency().equals(expectedAllPricesForSale[0].getCurrency()))
				.toArray(PriceContract[]::new),
			product.getAllPricesForSale(expectedAllPricesForSale[0].getCurrency())
		);

		assertArrayEquals(
			theProduct.getAllPricesForSale()
				.stream()
				.filter(it -> it.getCurrency().equals(expectedAllPricesForSale[0].getCurrency()) && it.getPriceList().equals(expectedAllPricesForSale[0].getPriceList()))
				.toArray(PriceContract[]::new),
			product.getAllPricesForSale(expectedAllPricesForSale[0].getPriceList(), expectedAllPricesForSale[0].getCurrency())
		);

		final PriceContract[] allPrices = Arrays.stream(product.getAllPricesAsArray())
			
			.toArray(PriceContract[]::new);
		final PriceContract[] expectedAllPrices = theProduct.getPrices().toArray(PriceContract[]::new);
		assertEquals(expectedAllPrices.length, allPrices.length);
		assertArrayEquals(expectedAllPrices, allPrices);
		assertArrayEquals(expectedAllPrices, product.getAllPricesAsList().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.getAllPricesAsSet().toArray(PriceContract[]::new));
		assertArrayEquals(expectedAllPrices, product.getAllPrices().toArray(PriceContract[]::new));

		assertEquals(
			Arrays.stream(expectedAllPrices).filter(it -> "basic".equals(it.getPriceList())).findFirst().orElseThrow(),
			product.getBasicPrice()
		);
	}

	private void assertCategoryParents(
		@Nonnull Collection<CategoryInterface> categories,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nullable Locale locale
	) {
		for (CategoryInterface category : categories) {
			assertCategoryParent(originalCategories, category, locale);
		}
	}

}
