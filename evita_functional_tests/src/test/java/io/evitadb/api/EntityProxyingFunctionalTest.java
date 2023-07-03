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

import io.evitadb.api.mock.CategoryInterface;
import io.evitadb.api.mock.ProductInterface;
import io.evitadb.api.mock.TestEntity;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
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
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	private static void assertCategoryReferences(@Nonnull Stream<EntityReference> categoryReferences) {
		assertNotNull(categoryReferences);
		final EntityReference[] references = categoryReferences
			.sorted()
			.toArray(EntityReference[]::new);

		assertEquals(2, references.length);
		assertArrayEquals(
			new EntityReference[]{
				new EntityReference(Entities.CATEGORY, 1),
				new EntityReference(Entities.CATEGORY, 10)
			},
			references
		);
	}

	private static void assertCategories(
		@Nonnull Stream<CategoryInterface> categoryReferences,
		@Nonnull Map<Integer, SealedEntity> originalCategories,
		@Nullable Locale locale
	) {
		assertNotNull(categoryReferences);
		final CategoryInterface[] references = categoryReferences
			.sorted(Comparator.comparingInt(CategoryInterface::getId))
			.toArray(CategoryInterface[]::new);

		assertEquals(2, references.length);
		assertCategory(
			references[0],
			originalCategories.computeIfAbsent(references[0].getId(), id -> {
				throw new AssertionError("Category with id " + id + " not found");
			}),
			locale
		);
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
	void shouldProxyToInterfaceWithCzechLocalization(EvitaSessionContract evitaSession, Map<Integer, SealedEntity> originalCategories) {
		final Optional<ProductInterface> productRef = evitaSession.queryOne(
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(1),
					entityLocaleEquals(CZECH_LOCALE)
				),
				require(
					entityFetch(
						attributeContent(), associatedDataContent(), priceContentAll(), referenceContentAllWithAttributes(
							entityFetch(
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
		assertEquals(1, product.getPrimaryKey());
		assertEquals(1, product.getId());
		assertEquals(Entities.PRODUCT, product.getType());
		assertEquals(TestEntity.PRODUCT, product.getEntityType());
		assertEquals("Ergonomic-Plastic-Table-1", product.getCode());
		assertEquals("Incredible Linen Clock", product.getName());
		assertEquals(new BigDecimal("310.37"), product.getQuantity());
		assertEquals(new BigDecimal("310.37"), product.getQuantityAsDifferentProperty());
		assertTrue(product.isAlias());
		assertEquals(new ReferencedFileSet(null), product.getReferencedFileSet());
		assertEquals(new ReferencedFileSet(null), product.getReferencedFileSetAsDifferentProperty());

		assertCategoryReferences(product.getCategoryReferences().stream());
		assertCategoryReferences(product.getCategoryReferencesAsList().stream());
		assertCategoryReferences(product.getCategoryReferencesAsSet().stream());
		assertCategoryReferences(Arrays.stream(product.getCategoryReferencesAsArray()));

		assertCategories(product.getCategories().stream(), originalCategories, CZECH_LOCALE);
		assertCategories(product.getCategoriesAsList().stream(), originalCategories, CZECH_LOCALE);
		assertCategories(product.getCategoriesAsSet().stream(), originalCategories, CZECH_LOCALE);
		assertCategories(Arrays.stream(product.getCategoriesAsArray()), originalCategories, CZECH_LOCALE);
	}

	@DisplayName("Should wrap an interface and load all data")
	@Test
	@UseDataSet(FIFTY_PRODUCTS)
	void shouldProxyToInterface(EvitaSessionContract evitaSession, Map<Integer, SealedEntity> originalCategories) {
		final Optional<ProductInterface> productRef = evitaSession.getEntity(
			Entities.PRODUCT, ProductInterface.class, 1,
			attributeContent(), associatedDataContent(), priceContentAll(),
			referenceContentAllWithAttributes(
				entityFetchAll(), entityGroupFetchAll()
			),
			dataInLocales()
		);
		assertTrue(productRef.isPresent());
		final ProductInterface product = productRef.get();
		assertEquals(1, product.getPrimaryKey());
		assertEquals(1, product.getId());
		assertEquals(Entities.PRODUCT, product.getType());
		assertEquals(TestEntity.PRODUCT, product.getEntityType());
		assertEquals("Ergonomic-Plastic-Table-1", product.getCode());
		assertEquals("Incredible Linen Clock", product.getName(CZECH_LOCALE));
		assertEquals("Incredible Linen Clock_2", product.getName(Locale.ENGLISH));
		assertEquals(new BigDecimal("310.37"), product.getQuantity());
		assertEquals(new BigDecimal("310.37"), product.getQuantityAsDifferentProperty());
		assertTrue(product.isAlias());
		assertEquals(new ReferencedFileSet(null), product.getReferencedFileSet());
		assertEquals(new ReferencedFileSet(null), product.getReferencedFileSetAsDifferentProperty());

		assertCategoryReferences(product.getCategoryReferences().stream());
		assertCategoryReferences(product.getCategoryReferencesAsList().stream());
		assertCategoryReferences(product.getCategoryReferencesAsSet().stream());
		assertCategoryReferences(Arrays.stream(product.getCategoryReferencesAsArray()));

		assertCategories(product.getCategories().stream(), originalCategories, null);
		assertCategories(product.getCategoriesAsList().stream(), originalCategories, null);
		assertCategories(product.getCategoriesAsSet().stream(), originalCategories, null);
		assertCategories(Arrays.stream(product.getCategoriesAsArray()), originalCategories, null);
	}

}
