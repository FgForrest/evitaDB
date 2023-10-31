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
import io.evitadb.api.mock.CategoryInterfaceEditor;
import io.evitadb.api.mock.ProductInterface;
import io.evitadb.api.mock.ProductInterfaceEditor;
import io.evitadb.api.mock.UnknownEntityEditorInterface;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.EntityReferenceWithParent;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the ability to proxy an entity into an arbitrary interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Evita entity editor interface proxying functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@TestMethodOrder(OrderAnnotation.class)
@Slf4j
public class EntityEditorProxyingFunctionalTest extends AbstractEntityProxyingFunctionalTest implements EvitaTestSupport {
	protected static final String HUNDRED_PRODUCTS = "HundredProxyProducts_EntityEditorProxyingFunctionalTest";
	private final static DateTimeRange VALIDITY = DateTimeRange.between(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1));

	private static void assertCategory(SealedEntity category, String code, String name, long priority, DateTimeRange validity, int parentId) {
		assertCategory(category, code, name, priority, validity);
		assertEquals(parentId, category.getParentEntity().orElseThrow().getPrimaryKey());
	}

	private static void assertCategory(SealedEntity category, String code, String name, long priority, DateTimeRange validity) {
		assertEquals(code, category.getAttribute(ATTRIBUTE_CODE));
		assertEquals(name, category.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE));
		assertEquals(priority, category.getAttribute(ATTRIBUTE_PRIORITY, Long.class));
		if (validity == null) {
			assertNull(category.getAttribute(ATTRIBUTE_VALIDITY));
		} else {
			assertEquals(validity, category.getAttribute(ATTRIBUTE_VALIDITY));
		}
	}

	private static void assertProduct(
		SealedEntity product,
		String code, String name, TestEnum theEnum,
		BigDecimal quantity, boolean optionallyAvailable, Long priority,
		String[] markets,
		DateTimeRange validity
	) {
		assertEquals(code, product.getAttribute(ATTRIBUTE_CODE));
		assertEquals(name, product.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE));
		assertEquals(theEnum, TestEnum.valueOf(product.getAttribute(ATTRIBUTE_ENUM)));
		assertEquals(quantity, product.getAttribute(ATTRIBUTE_QUANTITY));
		assertEquals(priority, product.getAttribute(ATTRIBUTE_PRIORITY));
		if (optionallyAvailable) {
			assertTrue(product.getAttribute(ATTRIBUTE_OPTIONAL_AVAILABILITY, Boolean.class));
		} else {
			assertFalse(product.getAttribute(ATTRIBUTE_OPTIONAL_AVAILABILITY, Boolean.class));
		}
		assertArrayEquals(markets, product.getAttribute(ATTRIBUTE_MARKETS));

		assertFalse(
			new Price(1, new Price.PriceKey(1, "reference", CURRENCY_CZK), null, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true)
				.differsFrom(
					product.getPrice(1, "reference", CURRENCY_CZK).orElseThrow()
				)
		);

		assertFalse(
			new Price(1, new Price.PriceKey(2, "vip", CURRENCY_CZK), null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true)
				.differsFrom(
					product.getPrice(2, "vip", CURRENCY_CZK).orElseThrow()
				)
		);

		assertFalse(
			new Price(1, new Price.PriceKey(3, "vip", CURRENCY_CZK), 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, validity, true)
				.differsFrom(
					product.getPrice(3, "vip", CURRENCY_CZK).orElseThrow()
				)
		);

		assertFalse(
			new Price(1, new Price.PriceKey(4, "vip", CURRENCY_USD), 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true)
				.differsFrom(
					product.getPrice(4, "vip", CURRENCY_USD).orElseThrow()
				)
		);

		assertFalse(
			new Price(1, new Price.PriceKey(5, "basic", CURRENCY_CZK), 9, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true)
				.differsFrom(
					product.getPrice(5, "basic", CURRENCY_CZK).orElseThrow()
				)
		);

		assertFalse(
			new Price(1, new Price.PriceKey(6, "basic", CURRENCY_CZK), null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true)
				.differsFrom(
					product.getPrice(6, "basic", CURRENCY_CZK).orElseThrow()
				)
		);

		assertFalse(
			new Price(1, new Price.PriceKey(7, "basic", CURRENCY_CZK), 8, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, validity, true)
				.differsFrom(
					product.getPrice(7, "basic", CURRENCY_CZK).orElseThrow()
				)
		);
	}

	private static void assertUnknownEntity(SealedEntity unknownEntity, String code, String name, long priority) {
		assertEquals(code, unknownEntity.getAttribute(ATTRIBUTE_CODE));
		assertEquals(name, unknownEntity.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE));
		assertEquals(priority, unknownEntity.getAttribute(ATTRIBUTE_PRIORITY, Long.class));
	}

	private static void assertModifiedInstance(ProductInterface modifiedInstance, int parameterId, DateTimeRange validity, String entityCode, String entityName) {
		assertEquals(entityCode, modifiedInstance.getCode());
		assertEquals(entityName, modifiedInstance.getName(CZECH_LOCALE));
		assertEquals(TestEnum.ONE, modifiedInstance.getEnum());
		assertEquals(BigDecimal.TEN, modifiedInstance.getQuantity());
		assertTrue(modifiedInstance.isOptionallyAvailable());
		assertArrayEquals(new String[]{"market-1", "market-2"}, modifiedInstance.getMarketsAttribute());
		assertArrayEquals(new String[]{"market-3", "market-4"}, modifiedInstance.getMarkets());
		assertNotNull(modifiedInstance.getParameterById(parameterId));
		assertEquals(parameterId, modifiedInstance.getParameterById(parameterId).getPrimaryKey());
		assertEquals(1L, modifiedInstance.getParameterById(parameterId).getCategoryPriority());

		assertFalse(
			new Price(1, new Price.PriceKey(1, "reference", CURRENCY_CZK), null, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true)
				.differsFrom(
					modifiedInstance.getPrice("reference", CURRENCY_CZK, 1)
				)
		);

		assertFalse(
			new Price(1, new Price.PriceKey(2, "vip", CURRENCY_CZK), null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true)
				.differsFrom(
					modifiedInstance.getPrice("vip", CURRENCY_CZK, 2)
				)
		);

		assertFalse(
			new Price(1, new Price.PriceKey(3, "vip", CURRENCY_CZK), 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, validity, true)
				.differsFrom(
					modifiedInstance.getPrice("vip", CURRENCY_CZK, 3)
				)
		);

		assertFalse(
			new Price(1, new Price.PriceKey(4, "vip", CURRENCY_USD), 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true)
				.differsFrom(
					modifiedInstance.getPrice("vip", CURRENCY_USD, 4)
				)
		);

		assertFalse(
			new Price(1, new Price.PriceKey(5, "basic", CURRENCY_CZK), 9, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true)
				.differsFrom(
					modifiedInstance.getPrice("basic", CURRENCY_CZK, 5)
				)
		);

		assertFalse(
			new Price(1, new Price.PriceKey(6, "basic", CURRENCY_CZK), null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true)
				.differsFrom(
					modifiedInstance.getPrice("basic", CURRENCY_CZK, 6)
				)
		);

		assertFalse(
			new Price(1, new Price.PriceKey(7, "basic", CURRENCY_CZK), 8, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, validity, true)
				.differsFrom(
					modifiedInstance.getPrice("basic", CURRENCY_CZK, 7)
				)
		);
	}

	private static int createParameterEntityIfMissing(EvitaContract evita) {
		final int parameterId = evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final Optional<EntityReference> parameterReference = evitaSession.queryOneEntityReference(
					query(
						collection(Entities.PARAMETER),
						filterBy(
							attributeEquals(ATTRIBUTE_CODE, "parameter-1")
						)
					)
				);
				return parameterReference
					.orElseGet(
						() -> evitaSession.createNewEntity(Entities.PARAMETER)
							.setAttribute(ATTRIBUTE_CODE, "parameter-1")
							.setAttribute(ATTRIBUTE_PRIORITY, 178L)
							.upsertVia(evitaSession)
					)
					.getPrimaryKey();
			}
		);
		return parameterId;
	}

	@DataSet(value = HUNDRED_PRODUCTS, destroyAfterClass = true, readOnly = false)
	@Override
	DataCarrier setUp(Evita evita) {
		return super.setUp(evita);
	}

	@DisplayName("Should create new entity of custom type")
	@Order(1)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewEntityOfCustomType(EvitaContract evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final DateTimeRange validity = DateTimeRange.between(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1));
				final CategoryInterfaceEditor newCategory = evitaSession.createNewEntity(CategoryInterfaceEditor.class, 1000)
					.setCode("root-category")
					.setName(CZECH_LOCALE, "Kořenová kategorie")
					.setPriority(78L)
					.setValidity(validity);

				newCategory.setLabels(new Labels());
				newCategory.setReferencedFiles(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newCategory.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(7, mutation.get().getLocalMutations().size());

				final CategoryInterface modifiedInstance = newCategory.toInstance();
				assertEquals("root-category", modifiedInstance.getCode());
				assertEquals("Kořenová kategorie", modifiedInstance.getName(CZECH_LOCALE));
				assertEquals(78L, modifiedInstance.getPriority());
				assertEquals(validity, modifiedInstance.getValidity());

				newCategory.upsertVia(evitaSession);

				assertEquals(1000, newCategory.getId());
				assertCategory(
					evitaSession.getEntity(Entities.CATEGORY, newCategory.getId(), entityFetchAllContent()).orElseThrow(),
					"root-category", "Kořenová kategorie", 78L, validity
				);
			}
		);
	}

	@DisplayName("Should create new entity of custom type with parent")
	@Order(2)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewEntityOfCustomTypeWithSettingParentId(EvitaContract evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			evitaSession -> {
				if (evitaSession.getEntity(Entities.CATEGORY, 1000).isEmpty()) {
					shouldCreateNewEntityOfCustomType(evita);
				}
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final CategoryInterfaceEditor newCategory = evitaSession.createNewEntity(CategoryInterfaceEditor.class, 1001)
					.setCode("child-category-1")
					.setName(CZECH_LOCALE, "Dětská kategorie")
					.setPriority(90L)
					.setParentId(1000);

				newCategory.setLabels(new Labels());
				newCategory.setReferencedFiles(new ReferencedFileSet());
				evitaSession.upsertEntity(newCategory);

				assertEquals(1001, newCategory.getId());
				final SealedEntity fetchedChildCategory = evitaSession.getEntity(
					Entities.CATEGORY, newCategory.getId(), entityFetchAllContent()
				).orElseThrow();
				assertCategory(
					fetchedChildCategory,
					"child-category-1", "Dětská kategorie", 90L, null
				);
				assertEquals(1000, fetchedChildCategory.getParentEntity().orElseThrow().getPrimaryKey());
			}
		);
	}

	@DisplayName("Should create new entity of custom type with parent as entity reference")
	@Order(3)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewEntityOfCustomTypeWithSettingParentAsEntityReference(EvitaContract evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			evitaSession -> {
				if (evitaSession.getEntity(Entities.CATEGORY, 1000).isEmpty()) {
					shouldCreateNewEntityOfCustomType(evita);
				}
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference parentEntityReference = new EntityReference(Entities.CATEGORY, 1000);
				final CategoryInterfaceEditor newCategory = evitaSession.createNewEntity(CategoryInterfaceEditor.class, 1002)
					.setCode("child-category-2")
					.setName(CZECH_LOCALE, "Dětská kategorie")
					.setPriority(90L)
					.setParentEntityReference(parentEntityReference);

				newCategory.setLabels(new Labels());
				newCategory.setReferencedFiles(new ReferencedFileSet());
				evitaSession.upsertEntity(newCategory);

				assertSame(parentEntityReference, newCategory.getParentEntityReference());
				assertEquals(1002, newCategory.getId());
				final SealedEntity fetchedChildCategory = evitaSession.getEntity(
					Entities.CATEGORY, newCategory.getId(), entityFetchAllContent()
				).orElseThrow();
				assertCategory(
					fetchedChildCategory,
					"child-category-2", "Dětská kategorie", 90L, null
				);
				assertEquals(1000, fetchedChildCategory.getParentEntity().orElseThrow().getPrimaryKey());
			}
		);
	}

	@DisplayName("Should create new entity of custom type with parent as entity classifier")
	@Order(4)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewEntityOfCustomTypeWithSettingParentAsEntityClassifier(EvitaContract evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			evitaSession -> {
				if (evitaSession.getEntity(Entities.CATEGORY, 1000).isEmpty()) {
					shouldCreateNewEntityOfCustomType(evita);
				}
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReferenceWithParent parentEntityReference = new EntityReferenceWithParent(Entities.CATEGORY, 1000, null);
				final CategoryInterfaceEditor newCategory = evitaSession.createNewEntity(CategoryInterfaceEditor.class, 1003)
					.setCode("child-category-3")
					.setName(CZECH_LOCALE, "Dětská kategorie")
					.setPriority(90L)
					.setParentEntityClassifier(parentEntityReference);

				newCategory.setLabels(new Labels());
				newCategory.setReferencedFiles(new ReferencedFileSet());
				evitaSession.upsertEntity(newCategory);

				assertSame(parentEntityReference, newCategory.getParentEntityClassifier());
				assertEquals(1000, newCategory.getParentEntityReference().getPrimaryKey());
				assertEquals(1003, newCategory.getId());
				final SealedEntity fetchedChildCategory = evitaSession.getEntity(
					Entities.CATEGORY, newCategory.getId(), entityFetchAllContent()
				).orElseThrow();
				assertCategory(
					fetchedChildCategory,
					"child-category-3", "Dětská kategorie", 90L, null
				);
				assertEquals(1000, fetchedChildCategory.getParentEntity().orElseThrow().getPrimaryKey());
			}
		);
	}

	@DisplayName("Should create new entity of custom type with parent as full category entity")
	@Order(5)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewEntityOfCustomTypeWithSettingParentAsFullCategoryEntity(EvitaContract evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			evitaSession -> {
				if (evitaSession.getEntity(Entities.CATEGORY, 1000).isEmpty()) {
					shouldCreateNewEntityOfCustomType(evita);
				}
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final CategoryInterfaceEditor parentEntity = evitaSession.getEntity(CategoryInterfaceEditor.class, 1000)
					.orElseThrow();
				final CategoryInterfaceEditor newCategory = evitaSession.createNewEntity(CategoryInterfaceEditor.class, 1004)
					.setCode("child-category-4")
					.setName(CZECH_LOCALE, "Dětská kategorie")
					.setPriority(90L)
					.setParentEntity(
						parentEntity
					);

				newCategory.setLabels(new Labels());
				newCategory.setReferencedFiles(new ReferencedFileSet());
				evitaSession.upsertEntity(newCategory);

				assertSame(parentEntity, newCategory.getParentEntity());
				assertEquals(1000, newCategory.getParentEntityReference().getPrimaryKey());
				assertEquals(1000, newCategory.getParentEntityClassifier().getPrimaryKey());
				assertEquals(1004, newCategory.getId());
				final SealedEntity fetchedChildCategory = evitaSession.getEntity(
					Entities.CATEGORY, newCategory.getId(), entityFetchAllContent()
				).orElseThrow();
				assertCategory(
					fetchedChildCategory,
					"child-category-4", "Dětská kategorie", 90L, null
				);
				assertEquals(1000, fetchedChildCategory.getParentEntity().orElseThrow().getPrimaryKey());
			}
		);
	}

	@DisplayName("Should create entire category tree")
	@Order(6)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateEntireCategoryTreeOnTheFlyUsingLambdaConsumer(EvitaContract evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final CategoryInterfaceEditor newCategory = evitaSession.createNewEntity(CategoryInterfaceEditor.class, 1005)
					.setCode("child-category-5")
					.setName(CZECH_LOCALE, "Dětská kategorie")
					.setPriority(90L)
					.withParent(
						1100,
						whichIs -> {
							whichIs.setCode("root-category-1")
								.setName(CZECH_LOCALE, "Kořenová kategorie")
								.setPriority(78L);
							whichIs.setLabels(new Labels());
							whichIs.setReferencedFiles(new ReferencedFileSet());
						}
					);

				newCategory.setLabels(new Labels());
				newCategory.setReferencedFiles(new ReferencedFileSet());
				final List<EntityReference> createdEntityReferences = evitaSession.upsertEntityDeeply(newCategory);

				assertEquals(2, createdEntityReferences.size());
				assertEquals(new EntityReference(Entities.CATEGORY, 1100), createdEntityReferences.get(0));
				assertEquals(new EntityReference(Entities.CATEGORY, 1005), createdEntityReferences.get(1));

				assertEquals(1100, newCategory.getParentEntity().getId());
				assertEquals(1100, newCategory.getParentEntityReference().getPrimaryKey());
				assertEquals(1100, newCategory.getParentEntityClassifier().getPrimaryKey());
				assertEquals(1005, newCategory.getId());

				assertCategory(
					evitaSession.getEntity(
						Entities.CATEGORY, newCategory.getId(), entityFetchAllContent()
					).orElseThrow(),
					"child-category-5", "Dětská kategorie", 90L, null, 1100
				);
				assertCategory(
					evitaSession.getEntity(
						Entities.CATEGORY, 1100, entityFetchAllContent()
					).orElseThrow(),
					"root-category-1", "Kořenová kategorie", 78L, null
				);
			}
		);
	}

	@DisplayName("Should create new entity of custom type with auto-generated primary key and schema")
	@Order(7)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewEntityOfCustomTypeWithAutoGeneratedPrimaryKeyAndSchema(EvitaContract evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				evitaSession.defineEntitySchemaFromModelClass(UnknownEntityEditorInterface.class);
				final UnknownEntityEditorInterface newEntity = evitaSession.createNewEntity(UnknownEntityEditorInterface.class);
				newEntity.setCode("entity1");
				newEntity.setName("Nějaká entita", CZECH_LOCALE);
				newEntity.setPriority(78L);

				evitaSession.upsertEntity(newEntity);

				assertTrue(newEntity.getId() > 0);
				assertUnknownEntity(
					evitaSession.getEntity("newlyDefinedEntity", newEntity.getId(), entityFetchAllContent()).orElseThrow(),
					"entity1", "Nějaká entita", 78L
				);
			}
		);
	}

	@DisplayName("Should create new entity with prices and references of custom type")
	@Order(8)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewCustomProductWithPricesAndReferences(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-1")
					.setName(CZECH_LOCALE, "Produkt 1")
					.setEnum(TestEnum.ONE)
					.setOptionallyAvailable(true)
					.setQuantity(BigDecimal.TEN)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setPrice(new Price(1, "reference", CURRENCY_CZK, null, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true))
					.setPrice(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "vip", CURRENCY_CZK, 2)
					.setPrice(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "vip", "CZK", 3, VALIDITY, 7)
					.setPrice(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "vip", "USD", 4, null, 7)
					.setBasicPrice(new Price(5, "basic", CURRENCY_CZK, 9, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true))
					.setBasicPrice(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, CURRENCY_CZK, 6)
					.setBasicPrice(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "CZK", 7, VALIDITY, 8)
					// TODO JNO - tohle vyzkoušet na něco, co nemá povinné atributy
					// .addParameter(parameterId);
					.addParameter(parameterId, that -> that.setCategoryPriority(1L));

				assertThrows(
					EvitaInvalidUsageException.class,
					() -> newProduct.setBasicPrice(
						new Price(
							5, "reference", CURRENCY_CZK, null,
							BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("1.1"), null, true
						)
					),
					"Should refuse to set different price via basic price setter."
				);

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newProduct.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(19, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertModifiedInstance(modifiedInstance, parameterId, VALIDITY, "product-1", "Produkt 1");

				newProduct.upsertVia(evitaSession);

				assertTrue(newProduct.getId() > 0);
				assertProduct(
					evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow(),
					"product-1", "Produkt 1", TestEnum.ONE, BigDecimal.TEN, true, 78L,
					new String[]{"market-1", "market-2"},
					VALIDITY
				);
			}
		);
	}

	@DisplayName("Should create new entity with prices and references of custom type as list")
	@Order(9)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewCustomProductWithPricesAndReferencesAsList(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-2")
					.setName(CZECH_LOCALE, "Produkt 2")
					.setEnum(TestEnum.ONE)
					.setOptionallyAvailable(true)
					.setQuantity(BigDecimal.TEN)
					.setPriority(78L)
					.setMarketsAttributeAsList(Arrays.asList("market-1", "market-2"))
					.setMarketsAsList(Arrays.asList("market-3", "market-4"))
					.setAllPricesAsList(
						Arrays.asList(
							new Price(1, "reference", CURRENCY_CZK, null, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
							new Price(2, "vip", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
							new Price(3, "vip", CURRENCY_CZK, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true),
							new Price(4, "vip", CURRENCY_USD, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
							new Price(5, "basic", CURRENCY_CZK, 9, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
							new Price(6, "basic", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
							new Price(7, "basic", CURRENCY_CZK, 8, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true)
						)
					)
					.addParameter(parameterId, that -> that.setCategoryPriority(1L));

				assertThrows(
					EvitaInvalidUsageException.class,
					() -> newProduct.setBasicPrice(
						new Price(
							5, "reference", CURRENCY_CZK, null,
							BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("1.1"), null, true
						)
					),
					"Should refuse to set different price via basic price setter."
				);

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newProduct.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(19, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertModifiedInstance(modifiedInstance, parameterId, VALIDITY, "product-2", "Produkt 2");

				newProduct.upsertVia(evitaSession);

				assertTrue(newProduct.getId() > 0);
				assertProduct(
					evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow(),
					"product-2", "Produkt 2", TestEnum.ONE, BigDecimal.TEN, true, 78L,
					new String[]{"market-1", "market-2"},
					VALIDITY
				);
			}
		);
	}

	@DisplayName("Should create new entity with prices and references of custom type as array")
	@Order(10)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewCustomProductWithPricesAndReferencesAsArray(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-3")
					.setName(CZECH_LOCALE, "Produkt 3")
					.setEnum(TestEnum.ONE)
					.setOptionallyAvailable(true)
					.setQuantity(BigDecimal.TEN)
					.setPriority(78L)
					.setMarketsAttributeAsVarArg("market-1", "market-2")
					.setMarketsAsVarArg("market-3", "market-4")
					.setAllPricesAsArray(
						new Price(1, "reference", CURRENCY_CZK, null, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
						new Price(2, "vip", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
						new Price(3, "vip", CURRENCY_CZK, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true),
						new Price(4, "vip", CURRENCY_USD, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
						new Price(5, "basic", CURRENCY_CZK, 9, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
						new Price(6, "basic", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
						new Price(7, "basic", CURRENCY_CZK, 8, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true)
					)
					.addParameter(parameterId, that -> that.setCategoryPriority(1L));

				assertThrows(
					EvitaInvalidUsageException.class,
					() -> newProduct.setBasicPrice(
						new Price(
							5, "reference", CURRENCY_CZK, null,
							BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("1.1"), null, true
						)
					),
					"Should refuse to set different price via basic price setter."
				);

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newProduct.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(19, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertModifiedInstance(modifiedInstance, parameterId, VALIDITY, "product-3", "Produkt 3");

				newProduct.upsertVia(evitaSession);

				assertTrue(newProduct.getId() > 0);
				assertProduct(
					evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow(),
					"product-3", "Produkt 3", TestEnum.ONE, BigDecimal.TEN, true, 78L,
					new String[]{"market-1", "market-2"},
					VALIDITY
				);
			}
		);
	}

}
