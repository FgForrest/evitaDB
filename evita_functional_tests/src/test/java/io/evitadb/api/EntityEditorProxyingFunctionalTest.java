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
import io.evitadb.api.exception.MandatoryAttributesNotProvidedException;
import io.evitadb.api.exception.ReferenceCardinalityViolatedException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.mock.*;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
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
import io.evitadb.utils.ArrayUtils;
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
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
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
		String code, String name,
		TestEnum theEnum,
		BigDecimal quantity,
		boolean optionallyAvailable,
		Long priority,
		String[] markets,
		DateTimeRange validity,
		int parameterId,
		int categoryId1,
		int categoryId2
	) {
		assertEquals(code, product.getAttribute(ATTRIBUTE_CODE));
		assertEquals(name, product.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE));
		assertEquals(theEnum, TestEnum.valueOf(product.getAttribute(ATTRIBUTE_ENUM)));
		assertEquals(quantity, product.getAttribute(ATTRIBUTE_QUANTITY));
		assertEquals(priority, product.getAttribute(ATTRIBUTE_PRIORITY));
		assertTrue(product.getAttribute(ATTRIBUTE_ALIAS, Boolean.class));
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

		final ReferenceContract parameter = product.getReference(Entities.PARAMETER, parameterId).orElseThrow();
		assertNotNull(parameter);
		assertEquals(10L, parameter.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class));

		final ReferenceContract category1 = product.getReference(Entities.CATEGORY, categoryId1).orElseThrow();
		assertEquals(1L, category1.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class));
		assertTrue(category1.getAttribute(ATTRIBUTE_CATEGORY_SHADOW, boolean.class));
		assertEquals("Kategorie 1", category1.getAttribute(ATTRIBUTE_CATEGORY_LABEL, CZECH_LOCALE, String.class));

		final ReferenceContract category2 = product.getReference(Entities.CATEGORY, categoryId2).orElseThrow();
		assertEquals(2L, category2.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class));
		assertFalse(category2.getAttribute(ATTRIBUTE_CATEGORY_SHADOW, boolean.class));
		assertEquals("Kategorie 2", category2.getAttribute(ATTRIBUTE_CATEGORY_LABEL, CZECH_LOCALE, String.class));

		final Collection<ReferenceContract> storeReferences = product.getReferences(Entities.STORE);
		assertEquals(3, storeReferences.size());
		assertArrayEquals(new int[]{1, 2, 3}, storeReferences.stream().mapToInt(ReferenceContract::getReferencedPrimaryKey).sorted().toArray());
	}

	private static void assertUnknownEntity(SealedEntity unknownEntity, String code, String name, long priority) {
		assertEquals(code, unknownEntity.getAttribute(ATTRIBUTE_CODE));
		assertEquals(name, unknownEntity.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE));
		assertEquals(priority, unknownEntity.getAttribute(ATTRIBUTE_PRIORITY, Long.class));
	}

	private static void assertModifiedInstance(
		ProductInterface modifiedInstance,
		int parameterId, int categoryId1, int categoryId2,
		DateTimeRange validity, String entityCode, String entityName
	) {
		assertEquals(entityCode, modifiedInstance.getCode());
		assertEquals(entityName, modifiedInstance.getName(CZECH_LOCALE));
		assertEquals(TestEnum.ONE, modifiedInstance.getEnum());
		assertEquals(BigDecimal.TEN, modifiedInstance.getQuantity());
		assertTrue(modifiedInstance.isOptionallyAvailable());
		assertArrayEquals(new String[]{"market-1", "market-2"}, modifiedInstance.getMarketsAttribute());
		assertArrayEquals(new String[]{"market-3", "market-4"}, modifiedInstance.getMarkets());

		final ProductParameterInterface parameter = modifiedInstance.getParameterById(parameterId);
		assertNotNull(parameter);
		assertEquals(parameterId, parameter.getPrimaryKey());
		assertEquals(10L, parameter.getPriority());

		final ProductCategoryInterface category1 = modifiedInstance.getCategoryById(categoryId1);
		assertNotNull(category1);
		assertEquals(categoryId1, category1.getPrimaryKey());
		assertEquals(1L, category1.getOrderInCategory());
		assertEquals("Kategorie 1", category1.getLabel(CZECH_LOCALE));
		assertTrue(category1.isShadow());

		final ProductCategoryInterface category2 = modifiedInstance.getCategoryById(categoryId2);
		assertNotNull(category2);
		assertEquals(categoryId2, category2.getPrimaryKey());
		assertEquals(2L, category2.getOrderInCategory());
		assertEquals("Kategorie 2", category2.getLabel(CZECH_LOCALE));
		assertFalse(category2.isShadow());

		assertArrayEquals(new int[]{1, 2, 3}, modifiedInstance.getStores());

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
		return createParameterEntityIfMissing(evita, 1);
	}

	private static int createParameterEntityIfMissing(EvitaContract evita, int number) {
		return evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final Optional<EntityReference> parameterReference = evitaSession.queryOneEntityReference(
					query(
						collection(Entities.PARAMETER),
						filterBy(
							attributeEquals(ATTRIBUTE_CODE, "parameter-" + number)
						)
					)
				);
				return parameterReference
					.orElseGet(
						() -> evitaSession.createNewEntity(Entities.PARAMETER)
							.setAttribute(ATTRIBUTE_CODE, "parameter-" + number)
							.setAttribute(ATTRIBUTE_PRIORITY, 178L)
							.upsertVia(evitaSession)
					)
					.getPrimaryKey();
			}
		);
	}

	private static int createParameterGroupEntityIfMissing(EvitaContract evita) {
		return createParameterEntityGroupIfMissing(evita, 1);
	}

	private static int createParameterEntityGroupIfMissing(EvitaContract evita, int number) {
		return evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final Optional<EntityReference> parameterReference = evitaSession.queryOneEntityReference(
					query(
						collection(Entities.PARAMETER_GROUP),
						filterBy(
							attributeEquals(ATTRIBUTE_CODE, "parameterGroup-" + number)
						)
					)
				);
				return parameterReference
					.orElseGet(
						() -> evitaSession.createNewEntity(Entities.PARAMETER_GROUP)
							.setAttribute(ATTRIBUTE_CODE, "parameterGroup-" + number)
							.upsertVia(evitaSession)
					)
					.getPrimaryKey();
			}
		);
	}

	private static int createBrandEntityIfMissing(EvitaContract evita) {
		return evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final Optional<EntityReference> parameterReference = evitaSession.queryOneEntityReference(
					query(
						collection(Entities.BRAND),
						filterBy(
							attributeEquals(ATTRIBUTE_CODE, "brand-1")
						)
					)
				);
				return parameterReference
					.orElseGet(
						() -> evitaSession.createNewEntity(Entities.BRAND)
							.setAttribute(ATTRIBUTE_CODE, "brand-1")
							.setReference(Entities.STORE, 1)
							.upsertVia(evitaSession)
					)
					.getPrimaryKey();
			}
		);
	}

	private static int createCategoryEntityIfMissing(EvitaContract evita, int number) {
		return evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final Optional<EntityReference> categoryReference = evitaSession.queryOneEntityReference(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							attributeEquals(ATTRIBUTE_CODE, "category-" + number)
						)
					)
				);
				return categoryReference
					.orElseGet(
						() -> evitaSession.createNewEntity(Entities.CATEGORY, 2000 + number)
							.setAttribute(ATTRIBUTE_CODE, "category-" + number)
							.setAttribute(ATTRIBUTE_PRIORITY, 178L)
							.setAssociatedData(ASSOCIATED_DATA_LABELS, new Labels())
							.setAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES, new ReferencedFileSet())
							.upsertVia(evitaSession)
					)
					.getPrimaryKey();
			}
		);
	}

	private static Optional<EntityReference> getProductByCode(EvitaContract evita, String code) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneEntityReference(
					query(collection(Entities.PRODUCT), filterBy(attributeEquals("code", code)))
				);
			}
		);
	}

	private static Optional<EntityReference> getCategoryByCode(EvitaContract evita, String code) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneEntityReference(
					query(collection(Entities.CATEGORY), filterBy(attributeEquals("code", code)))
				);
			}
		);
	}

	private static Optional<EntityReference> getParameterGroupByCode(EvitaContract evita, String code) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneEntityReference(
					query(collection(Entities.PARAMETER_GROUP), filterBy(attributeEquals("code", code)))
				);
			}
		);
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
		final int categoryId1 = createCategoryEntityIfMissing(evita, 1);
		final int categoryId2 = createCategoryEntityIfMissing(evita, 2);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-1")
					.setName(CZECH_LOCALE, "Produkt 1")
					.setEnum(TestEnum.ONE)
					.setOptionallyAvailable(true)
					.setAlias(true)
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
					.setParameter(parameterId, that -> that.setPriority(10L))
					.addProductCategory(categoryId1, that -> that.setOrderInCategory(1L).setShadow(true).setLabel(CZECH_LOCALE, "Kategorie 1"))
					.addProductCategory(categoryId2, that -> that.setOrderInCategory(2L).setShadow(false).setLabel(CZECH_LOCALE, "Kategorie 2"))
					.addStore(1)
					.addStore(2)
					.addStore(3);

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
				assertEquals(32, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertModifiedInstance(modifiedInstance, parameterId, categoryId1, categoryId2, VALIDITY, "product-1", "Produkt 1");

				newProduct.upsertVia(evitaSession);

				assertTrue(newProduct.getId() > 0);
				assertProduct(
					evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow(),
					"product-1", "Produkt 1", TestEnum.ONE, BigDecimal.TEN, true, 78L,
					new String[]{"market-1", "market-2"},
					VALIDITY, parameterId, categoryId1, categoryId2
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
		final int categoryId1 = createCategoryEntityIfMissing(evita, 1);
		final int categoryId2 = createCategoryEntityIfMissing(evita, 2);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-2")
					.setName(CZECH_LOCALE, "Produkt 2")
					.setEnum(TestEnum.ONE)
					.setOptionallyAvailable(true)
					.setAlias(true)
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
					.setParameter(parameterId, that -> that.setPriority(10L))
					.addProductCategory(categoryId1, that -> that.setOrderInCategory(1L).setShadow(true).setLabel(CZECH_LOCALE, "Kategorie 1"))
					.addProductCategory(categoryId2, that -> that.setOrderInCategory(2L).setShadow(false).setLabel(CZECH_LOCALE, "Kategorie 2"))
					.setStoresByIds(Arrays.asList(1, 2, 3))
					.setStores(List.of(evitaSession.getEntity(StoreInterface.class, 3, entityFetchAllContent()).orElseThrow()));
				;

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
				assertEquals(32, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertModifiedInstance(modifiedInstance, parameterId, categoryId1, categoryId2, VALIDITY, "product-2", "Produkt 2");

				newProduct.upsertVia(evitaSession);

				assertTrue(newProduct.getId() > 0);
				assertProduct(
					evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow(),
					"product-2", "Produkt 2", TestEnum.ONE, BigDecimal.TEN, true, 78L,
					new String[]{"market-1", "market-2"},
					VALIDITY, parameterId, categoryId1, categoryId2
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
		final int categoryId1 = createCategoryEntityIfMissing(evita, 1);
		final int categoryId2 = createCategoryEntityIfMissing(evita, 2);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-3")
					.setName(CZECH_LOCALE, "Produkt 3")
					.setEnum(TestEnum.ONE)
					.setOptionallyAvailable(true)
					.setAlias(true)
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
					.setParameter(parameterId, that -> that.setPriority(10L))
					.addProductCategory(categoryId1, that -> that.setOrderInCategory(1L).setShadow(true).setLabel(CZECH_LOCALE, "Kategorie 1"))
					.addProductCategory(categoryId2, that -> that.setOrderInCategory(2L).setShadow(false).setLabel(CZECH_LOCALE, "Kategorie 2"))
					.setStoresByIds(1, 2)
					.setStores(evitaSession.getEntity(StoreInterface.class, 3, entityFetchAllContent()).orElseThrow());

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
				assertEquals(32, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertModifiedInstance(modifiedInstance, parameterId, categoryId1, categoryId2, VALIDITY, "product-3", "Produkt 3");

				newProduct.upsertVia(evitaSession);

				assertTrue(newProduct.getId() > 0);
				assertProduct(
					evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow(),
					"product-3", "Produkt 3", TestEnum.ONE, BigDecimal.TEN, true, 78L,
					new String[]{"market-1", "market-2"},
					VALIDITY, parameterId, categoryId1, categoryId2
				);
			}
		);
	}

	@DisplayName("Should set brand by primary key")
	@Order(11)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewCustomProductWithBrandSetAsPrimaryKey(EvitaContract evita) {
		final int brandId = createBrandEntityIfMissing(evita);
		final int parameterId = createParameterEntityIfMissing(evita);
		final int categoryId1 = createCategoryEntityIfMissing(evita, 1);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-4")
					.setName(CZECH_LOCALE, "Produkt 4")
					.setEnum(TestEnum.ONE)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setBrand(brandId)
					.setParameter(parameterId, that -> that.setPriority(10L))
					.addProductCategory(categoryId1, that -> that.setOrderInCategory(1L).setShadow(true).setLabel(CZECH_LOCALE, "Kategorie 1"));

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newProduct.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(16, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertEquals(brandId, modifiedInstance.getBrandId());

				newProduct.upsertVia(evitaSession);

				assertTrue(newProduct.getId() > 0);
				final SealedEntity createdProduct = evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow();
				assertTrue(createdProduct.getReference(Entities.BRAND, brandId).isPresent());
			}
		);
	}

	@DisplayName("Should set brand by passing entity object")
	@Order(12)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewCustomProductWithBrandSetAsEntity(EvitaContract evita) {
		final int brandId = createBrandEntityIfMissing(evita);
		final int parameterId = createParameterEntityIfMissing(evita);
		final int categoryId1 = createCategoryEntityIfMissing(evita, 1);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final BrandInterface brand = evitaSession.getEntity(
					BrandInterface.class, brandId, attributeContentAll()
				).orElseThrow();

				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-5")
					.setName(CZECH_LOCALE, "Produkt 5")
					.setEnum(TestEnum.ONE)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setBrand(brand)
					.setParameter(parameterId, that -> that.setPriority(10L))
					.addProductCategory(categoryId1, that -> that.setOrderInCategory(1L).setShadow(true).setLabel(CZECH_LOCALE, "Kategorie 1"));

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newProduct.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(16, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertEquals(brandId, modifiedInstance.getBrandId());
				assertSame(brand, modifiedInstance.getBrand());

				newProduct.upsertVia(evitaSession);

				assertTrue(newProduct.getId() > 0);
				final SealedEntity createdProduct = evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow();
				assertTrue(createdProduct.getReference(Entities.BRAND, brandId).isPresent());
			}
		);
	}

	@DisplayName("Should set brand by creating new in consumer")
	@Order(13)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewCustomProductWithNewBrandViaConsumer(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);
		final int categoryId1 = createCategoryEntityIfMissing(evita, 1);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-6")
					.setName(CZECH_LOCALE, "Produkt 6")
					.setEnum(TestEnum.ONE)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setNewBrand(brand -> brand.setCode("consumer-created-brand").setStore(1))
					.setParameter(parameterId, that -> that.setPriority(10L))
					.addProductCategory(categoryId1, that -> that.setOrderInCategory(1L).setShadow(true).setLabel(CZECH_LOCALE, "Kategorie 1"));

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newProduct.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(15, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertEquals("consumer-created-brand", modifiedInstance.getBrand().getCode());

				newProduct.upsertDeeplyVia(evitaSession);

				final EntityReference createdBrand = evitaSession.queryOneEntityReference(
					query(collection(Entities.BRAND), filterBy(attributeEquals("code", "consumer-created-brand")))
				).orElseThrow();

				assertTrue(newProduct.getId() > 0);
				final SealedEntity createdProduct = evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow();
				assertTrue(createdProduct.getReference(Entities.BRAND, createdBrand.getPrimaryKey()).isPresent());
			}
		);
	}

	@DisplayName("Should fail to update non-existing brand in consumer")
	@Order(13)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldFailToUpdateNonExistingBrandInConsumer(EvitaContract evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				assertThrows(
					ContextMissingException.class,
					() -> evitaSession.createNewEntity(ProductInterfaceEditor.class)
						.setCode("product-7")
						.setName(CZECH_LOCALE, "Produkt 7")
						.updateBrand(brand -> fail("Should not be called."))
				);
			}
		);
	}

	@DisplayName("Should set brand by get or create method")
	@Order(15)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewCustomProductWithNewBrandViaGetOrCreateMethod(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);
		final int categoryId1 = createCategoryEntityIfMissing(evita, 1);
		final int categoryId2 = createCategoryEntityIfMissing(evita, 2);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-7")
					.setName(CZECH_LOCALE, "Produkt 7")
					.setEnum(TestEnum.ONE)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setParameter(parameterId, that -> that.setPriority(10L))
					.addProductCategory(categoryId1, that -> that.setOrderInCategory(1L).setShadow(true).setLabel(CZECH_LOCALE, "Kategorie 1"))
					.addProductCategory(categoryId2, that -> that.setOrderInCategory(2L).setShadow(true).setLabel(CZECH_LOCALE, "Kategorie 2"));

				assertNull(newProduct.getBrand());
				final BrandInterfaceEditor newBrand = newProduct.getOrCreateBrand();
				newBrand.setCode("getorcreate-created-brand").setStore(1);

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newProduct.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(19, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertEquals("getorcreate-created-brand", newProduct.getBrand().getCode());
				assertEquals("getorcreate-created-brand", modifiedInstance.getBrand().getCode());

				newProduct.upsertDeeplyVia(evitaSession);

				final EntityReference createdBrand = evitaSession.queryOneEntityReference(
					query(collection(Entities.BRAND), filterBy(attributeEquals("code", "getorcreate-created-brand")))
				).orElseThrow();

				assertTrue(newProduct.getId() > 0);
				final SealedEntity createdProduct = evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow();
				assertTrue(createdProduct.getReference(Entities.BRAND, createdBrand.getPrimaryKey()).isPresent());
			}
		);
	}

	@DisplayName("Should fail to update non-existing parameter in consumer")
	@Order(16)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldFailToUpdateNonExistingParameterInConsumer(EvitaContract evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				assertThrows(
					ReferenceNotFoundException.class,
					() -> evitaSession.createNewEntity(ProductInterfaceEditor.class)
						.setCode("product-8")
						.setName(CZECH_LOCALE, "Produkt 8")
						.updateParameter(7, brand -> fail("Should not be called."))
				);
			}
		);
	}

	@DisplayName("Should set parameter by get or create method with id")
	@Order(17)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewCustomProductWithNewParameterViaGetOrCreateMethodWithId(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);
		final int categoryId1 = createCategoryEntityIfMissing(evita, 1);
		final int categoryId2 = createCategoryEntityIfMissing(evita, 2);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-8")
					.setName(CZECH_LOCALE, "Produkt 8")
					.setEnum(TestEnum.ONE)
					.setOptionallyAvailable(true)
					.setAlias(true)
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
					.addProductCategory(categoryId1, that -> that.setOrderInCategory(1L).setShadow(true).setLabel(CZECH_LOCALE, "Kategorie 1"))
					.addProductCategory(categoryId2, that -> that.setOrderInCategory(2L).setShadow(false).setLabel(CZECH_LOCALE, "Kategorie 2"))
					.setStoresByIds(1, 2, 3);

				assertNull(newProduct.getParameter());
				final ProductParameterInterfaceEditor newParameter = newProduct.getOrCreateParameter(parameterId);
				newParameter.setPriority(10L);

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newProduct.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(32, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertEquals(parameterId, newProduct.getParameter().getPrimaryKey());
				assertEquals(10L, newProduct.getParameter().getPriority());
				assertEquals(parameterId, modifiedInstance.getParameter().getPrimaryKey());
				assertEquals(10L, modifiedInstance.getParameter().getPriority());

				newProduct.upsertVia(evitaSession);

				assertTrue(newProduct.getId() > 0);
				assertProduct(
					evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow(),
					"product-8", "Produkt 8", TestEnum.ONE, BigDecimal.TEN, true, 78L,
					new String[]{"market-1", "market-2"},
					VALIDITY, parameterId, categoryId1, categoryId2
				);
			}
		);
	}

	@DisplayName("Should remove references by id")
	@Order(18)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveReferences(EvitaContract evita) {
		final int brandId = createBrandEntityIfMissing(evita);
		final int categoryId1 = createCategoryEntityIfMissing(evita, 1);
		final int categoryId2 = createCategoryEntityIfMissing(evita, 2);

		final EntityReference product7Ref = getProductByCode(evita, "product-7")
			.orElseGet(() -> {
				shouldCreateNewCustomProductWithNewBrandViaGetOrCreateMethod(evita);
				return getProductByCode(evita, "product-7").orElseThrow();
			});

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor product7 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product7Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				product7.removeBrand();
				product7.removeProductCategoryById(categoryId2);

				final Optional<EntityMutation> mutation = product7.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(2, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product7.toInstance();
				assertNull(modifiedInstance.getBrand());
				assertEquals(1, modifiedInstance.getProductCategories().size());
				assertNotNull(modifiedInstance.getCategoryById(categoryId1));
				assertNull(modifiedInstance.getCategoryById(categoryId2));

				product7.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product7Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertNull(product8SE.getReference(Entities.BRAND, brandId).orElse(null));
				assertNull(product8SE.getReference(Entities.CATEGORY, categoryId2).orElse(null));
				assertNotNull(product8SE.getReference(Entities.CATEGORY, categoryId1).orElse(null));
			}
		);
	}

	@DisplayName("Should remove price")
	@Order(19)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemovePrice(EvitaContract evita) {
		final EntityReference product8Ref = getProductByCode(evita, "product-8")
			.orElseGet(() -> {
				shouldCreateNewCustomProductWithNewParameterViaGetOrCreateMethodWithId(evita);
				return getProductByCode(evita, "product-8").orElseThrow();
			});

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor product8 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				/*
				new Price(1, "reference", CURRENCY_CZK, null, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
				new Price(2, "vip", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(3, "vip", CURRENCY_CZK, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true),
				new Price(4, "vip", CURRENCY_USD, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(5, "basic", CURRENCY_CZK, 9, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
				new Price(6, "basic", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(7, "basic", CURRENCY_CZK, 8, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true)
				 */

				product8.removePricesById(2)
					.removePricesByCurrency(CURRENCY_USD)
					.removePrice(product8.getPrice("basic", CURRENCY_CZK, 5))
					.removePrice(6, "basic", CURRENCY_CZK)
					.removePricesByPriceList("reference")
					.removeBasicPrice(7, CURRENCY_CZK);

				final Optional<EntityMutation> mutation = product8.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(6, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product8.toInstance();
				assertNull(modifiedInstance.getBrand());
				assertEquals(1, modifiedInstance.getAllPricesAsList().size());

				product8.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertEquals(1, product8SE.getPrices().size());
				assertEquals(3, product8SE.getPrices().iterator().next().priceId());
			}
		);
	}

	@DisplayName("Should remove parent")
	@Order(20)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveParent(EvitaContract evita) {
		final EntityReference childCategoryRef = getCategoryByCode(evita, "child-category-1")
			.orElseGet(() -> {
				shouldCreateNewEntityOfCustomTypeWithSettingParentId(evita);
				return getCategoryByCode(evita, "child-category-1").orElseThrow();
			});

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final CategoryInterfaceEditor childCategory = evitaSession.getEntity(
					CategoryInterfaceEditor.class, childCategoryRef.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				childCategory.removeParent();

				final Optional<EntityMutation> mutation = childCategory.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(1, mutation.get().getLocalMutations().size());

				final CategoryInterface modifiedInstance = childCategory.toInstance();
				assertNull(modifiedInstance.getParentId());

				childCategory.upsertVia(evitaSession);

				final SealedEntity childCategorySE = evitaSession.getEntity(
					Entities.CATEGORY, childCategoryRef.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertTrue(childCategorySE.getParentEntity().isEmpty());
			}
		);
	}

	@DisplayName("Should modify attribute on reference in isolated editor")
	@Order(21)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldIsolateMutationsInSeparatedEditors(EvitaContract evita) {
		final EntityReference product6Ref = getProductByCode(evita, "product-6")
			.orElseGet(() -> {
				shouldCreateNewCustomProductWithNewBrandViaConsumer(evita);
				return getProductByCode(evita, "product-6").orElseThrow();
			});

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor product6 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product6Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				final List<Long> originalPriorities = product6.getProductCategoriesAsList()
					.stream()
					.map(ProductCategoryInterface::getOrderInCategory)
					.toList();

				final List<ProductCategoryInterfaceEditor> editors = product6.getProductCategoriesAsList()
					.stream()
					.map(it -> {
						final ProductCategoryInterfaceEditor editor = it.openForWrite();
						editor.setOrderInCategory(it.getOrderInCategory() << 1);
						return editor;
					})
					.toList();

				final Optional<EntityMutation> mutation = product6.toMutation();
				assertFalse(mutation.isPresent());

				for (ProductCategoryInterfaceEditor editor : editors) {
					final Optional<EntityMutation> editorMutation = editor.toMutation();
					assertTrue(editorMutation.isPresent());
					assertEquals(1, editorMutation.get().getLocalMutations().size());
				}

				final ProductInterface modifiedInstance = product6.toInstance();
				final Iterator<Long> priorityIt = originalPriorities.iterator();

				modifiedInstance.getProductCategories()
					.forEach(it -> assertEquals(priorityIt.next(), it.getOrderInCategory()));

				product6.upsertDeeplyVia(evitaSession);

				final SealedEntity storedProduct = evitaSession.getEntity(
					Entities.PRODUCT, product6Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				final Iterator<Long> priorityItAgain = originalPriorities.iterator();
				for (ReferenceContract reference : storedProduct.getReferences(Entities.CATEGORY)) {
					assertEquals(priorityItAgain.next(), reference.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class));
				}

				for (ProductCategoryInterfaceEditor editor : editors) {
					evitaSession.upsertEntity(editor);
				}

				final SealedEntity storedProductAgain = evitaSession.getEntity(
					Entities.PRODUCT, product6Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				final Iterator<Long> priorityItAgainAndAgain = originalPriorities.iterator();
				for (ReferenceContract reference : storedProductAgain.getReferences(Entities.CATEGORY)) {
					assertEquals(
						priorityItAgainAndAgain.next() << 1,
						reference.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class)
					);
				}
			}
		);
	}

	@DisplayName("Should modify attribute on reference in shared editor")
	@Order(22)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldShareReferenceMutationsInSharedEditor(EvitaContract evita) {
		final EntityReference product6Ref = getProductByCode(evita, "product-6")
			.orElseGet(() -> {
				shouldCreateNewCustomProductWithNewBrandViaConsumer(evita);
				return getProductByCode(evita, "product-6").orElseThrow();
			});

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor product6 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product6Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				final ProductParameterInterfaceEditor editor = product6.getOrCreateParameter(1);
				editor.setPriority(80L);
				assertEquals(80L, editor.getPriority());

				final Optional<EntityMutation> mutation = product6.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(1, mutation.get().getLocalMutations().size());

				final Optional<EntityMutation> editorMutation = editor.toMutation();
				assertTrue(editorMutation.isPresent());
				assertEquals(1, editorMutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product6.toInstance();
				assertEquals(80L, modifiedInstance.getParameter().getPriority());

				product6.upsertDeeplyVia(evitaSession);

				final SealedEntity storedProduct = evitaSession.getEntity(
					Entities.PRODUCT, product6Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				for (ReferenceContract reference : storedProduct.getReferences(Entities.PARAMETER)) {
					assertEquals(80L, reference.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class));
				}
			}
		);
	}

	@DisplayName("Should remove parent and return boolean result")
	@Order(23)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveParentAndReturnBooleanResult(EvitaContract evita) {
		getCategoryByCode(evita, "child-category-1")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewEntityOfCustomTypeWithSettingParentId(evita);
		final EntityReference childCategoryRef = getCategoryByCode(evita, "child-category-1").orElseThrow();

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final CategoryInterfaceEditor childCategory = evitaSession.getEntity(
					CategoryInterfaceEditor.class, childCategoryRef.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertTrue(childCategory.removeParentAndReturnResult());
				assertFalse(childCategory.removeParentAndReturnResult());

				final Optional<EntityMutation> mutation = childCategory.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(1, mutation.get().getLocalMutations().size());

				final CategoryInterface modifiedInstance = childCategory.toInstance();
				assertNull(modifiedInstance.getParentId());

				childCategory.upsertVia(evitaSession);

				final SealedEntity childCategorySE = evitaSession.getEntity(
					Entities.CATEGORY, childCategoryRef.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertTrue(childCategorySE.getParentEntity().isEmpty());
			}
		);
	}

	@DisplayName("Should remove parent and return primary key result")
	@Order(24)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveParentAndReturnPrimaryKeyResult(EvitaContract evita) {
		getCategoryByCode(evita, "child-category-1")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewEntityOfCustomTypeWithSettingParentId(evita);
		final EntityReference childCategoryRef = getCategoryByCode(evita, "child-category-1").orElseThrow();

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final CategoryInterfaceEditor childCategory = evitaSession.getEntity(
					CategoryInterfaceEditor.class, childCategoryRef.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertEquals(1000, childCategory.removeParentAndReturnItsPrimaryKey());
				assertNull(childCategory.removeParentAndReturnItsPrimaryKey());

				final Optional<EntityMutation> mutation = childCategory.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(1, mutation.get().getLocalMutations().size());

				final CategoryInterface modifiedInstance = childCategory.toInstance();
				assertNull(modifiedInstance.getParentId());

				childCategory.upsertVia(evitaSession);

				final SealedEntity childCategorySE = evitaSession.getEntity(
					Entities.CATEGORY, childCategoryRef.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertTrue(childCategorySE.getParentEntity().isEmpty());
			}
		);
	}

	@DisplayName("Should remove parent and return its body as result")
	@Order(25)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveParentAndReturnItsBodyAsResult(EvitaContract evita) {
		getCategoryByCode(evita, "child-category-1")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewEntityOfCustomTypeWithSettingParentId(evita);
		final EntityReference childCategoryRef = getCategoryByCode(evita, "child-category-1").orElseThrow();

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final CategoryInterfaceEditor childCategory = evitaSession.getEntity(
					CategoryInterfaceEditor.class, childCategoryRef.primaryKey(),
					hierarchyContent(entityFetchAll()), attributeContentAll()
				).orElseThrow();

				final CategoryInterface originalParent = childCategory.removeParentAndReturnIt();
				assertEquals(1000, originalParent.getId());
				assertEquals("root-category", originalParent.getCode());
				assertNull(childCategory.removeParentAndReturnIt());

				final Optional<EntityMutation> mutation = childCategory.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(1, mutation.get().getLocalMutations().size());

				final CategoryInterface modifiedInstance = childCategory.toInstance();
				assertNull(modifiedInstance.getParentId());

				childCategory.upsertVia(evitaSession);

				final SealedEntity childCategorySE = evitaSession.getEntity(
					Entities.CATEGORY, childCategoryRef.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertTrue(childCategorySE.getParentEntity().isEmpty());
			}
		);
	}

	@DisplayName("Should remove prices and return collection / array of removed prices")
	@Order(26)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemovePricesAndReturnTheirCollectionAsResult(EvitaContract evita) {
		getProductByCode(evita, "product-8")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithNewParameterViaGetOrCreateMethodWithId(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product8Ref = getProductByCode(evita, "product-8").orElseThrow();
				final ProductInterfaceEditor product8 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				/*
				new Price(1, "reference", CURRENCY_CZK, null, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
				new Price(2, "vip", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(3, "vip", CURRENCY_CZK, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true),
				new Price(4, "vip", CURRENCY_USD, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(5, "basic", CURRENCY_CZK, 9, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
				new Price(6, "basic", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(7, "basic", CURRENCY_CZK, 8, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true)
				 */

				final PriceContract[] removedPricesByCurrency = product8.removePricesByCurrencyAndReturnTheirArray(CURRENCY_USD);
				assertEquals(1, removedPricesByCurrency.length);
				assertEquals(4, removedPricesByCurrency[0].priceId());
				final Collection<PriceContract> removedPricesByPriceList = product8.removePricesByPriceListAndReturnTheirCollection("vip");
				assertEquals(2, removedPricesByPriceList.size());
				final Set<Integer> removedPriceIds = removedPricesByPriceList.stream().map(PriceContract::priceId).collect(Collectors.toSet());
				assertTrue(removedPriceIds.contains(2));
				assertTrue(removedPriceIds.contains(3));

				final Optional<EntityMutation> mutation = product8.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(3, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product8.toInstance();
				assertEquals(4, modifiedInstance.getAllPricesAsList().size());

				product8.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertEquals(4, product8SE.getPrices().size());
			}
		);
	}

	@DisplayName("Should remove prices and return collection / array of removed price ids")
	@Order(27)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemovePricesAndReturnCollectionOfTheirIdsAsResult(EvitaContract evita) {
		getProductByCode(evita, "product-8")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithNewParameterViaGetOrCreateMethodWithId(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product8Ref = getProductByCode(evita, "product-8").orElseThrow();
				final ProductInterfaceEditor product8 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				/*
				new Price(1, "reference", CURRENCY_CZK, null, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
				new Price(2, "vip", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(3, "vip", CURRENCY_CZK, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true),
				new Price(4, "vip", CURRENCY_USD, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(5, "basic", CURRENCY_CZK, 9, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
				new Price(6, "basic", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(7, "basic", CURRENCY_CZK, 8, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true)
				 */

				final int[] removedPricesByCurrency = product8.removePricesByCurrencyAndReturnArrayOfTheirIds(CURRENCY_USD);
				assertEquals(1, removedPricesByCurrency.length);
				assertEquals(4, removedPricesByCurrency[0]);
				final Collection<Integer> removedPricesByPriceList = product8.removePricesByPriceListAndReturnTheirIds("vip");
				assertEquals(2, removedPricesByPriceList.size());
				final Set<Integer> removedPriceIds = new HashSet<>(removedPricesByPriceList);
				assertTrue(removedPriceIds.contains(2));
				assertTrue(removedPriceIds.contains(3));

				final Optional<EntityMutation> mutation = product8.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(3, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product8.toInstance();
				assertEquals(4, modifiedInstance.getAllPricesAsList().size());

				product8.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertEquals(4, product8SE.getPrices().size());
			}
		);
	}

	@DisplayName("Should remove prices and return collection / array of removed price keys")
	@Order(28)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemovePricesAndReturnCollectionOfTheirKeysAsResult(EvitaContract evita) {
		getProductByCode(evita, "product-8")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithNewParameterViaGetOrCreateMethodWithId(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product8Ref = getProductByCode(evita, "product-8").orElseThrow();
				final ProductInterfaceEditor product8 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				/*
				new Price(1, "reference", CURRENCY_CZK, null, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
				new Price(2, "vip", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(3, "vip", CURRENCY_CZK, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true),
				new Price(4, "vip", CURRENCY_USD, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(5, "basic", CURRENCY_CZK, 9, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
				new Price(6, "basic", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(7, "basic", CURRENCY_CZK, 8, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true)
				 */

				final Price.PriceKey[] removedPricesByCurrency = product8.removePricesByCurrencyAndReturnArrayOfTheirPriceKeys(CURRENCY_USD);
				assertEquals(1, removedPricesByCurrency.length);
				assertEquals(new Price.PriceKey(4, "vip", CURRENCY_USD), removedPricesByCurrency[0]);
				final Collection<Price.PriceKey> removedPricesByPriceList = product8.removePricesByPriceListAndReturnTheirKeys("vip");
				assertEquals(2, removedPricesByPriceList.size());
				final Set<Price.PriceKey> removedPriceIds = new HashSet<>(removedPricesByPriceList);
				assertTrue(removedPriceIds.contains(new Price.PriceKey(2, "vip", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(3, "vip", CURRENCY_CZK)));

				final Optional<EntityMutation> mutation = product8.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(3, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product8.toInstance();
				assertEquals(4, modifiedInstance.getAllPricesAsList().size());

				product8.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertEquals(4, product8SE.getPrices().size());
			}
		);
	}

	@DisplayName("Should remove prices one by one and return their identification")
	@Order(29)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemovePricesOneByOneAndReturnTheirIdentification(EvitaContract evita) {
		getProductByCode(evita, "product-8")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithNewParameterViaGetOrCreateMethodWithId(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product8Ref = getProductByCode(evita, "product-8").orElseThrow();
				final ProductInterfaceEditor product8 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				/*
				new Price(1, "reference", CURRENCY_CZK, null, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
				new Price(2, "vip", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(3, "vip", CURRENCY_CZK, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true),
				new Price(4, "vip", CURRENCY_USD, 7, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(5, "basic", CURRENCY_CZK, 9, BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.TEN, null, true),
				new Price(6, "basic", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true),
				new Price(7, "basic", CURRENCY_CZK, 8, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, VALIDITY, true)
				 */

				final PriceContract priceContract = product8.removePriceByIdAndReturnIt(1, "reference", CURRENCY_CZK);
				assertEquals(new Price.PriceKey(1, "reference", CURRENCY_CZK), priceContract.priceKey());
				assertEquals(2, product8.removePriceByIdAndReturnItsId(2, "vip", CURRENCY_CZK));
				assertTrue(product8.removePriceByIdAndReturnTrueIfRemoved(3, "vip", CURRENCY_CZK));
				assertFalse(product8.removePriceByIdAndReturnTrueIfRemoved(3, "vip", CURRENCY_CZK));
				assertEquals(new Price.PriceKey(7, "basic", CURRENCY_CZK), product8.removePriceByIdAndReturnItsPriceKey(7, "basic", CURRENCY_CZK));

				final Optional<EntityMutation> mutation = product8.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(4, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product8.toInstance();
				assertEquals(3, modifiedInstance.getAllPricesAsList().size());

				product8.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertEquals(3, product8SE.getPrices().size());
			}
		);
	}

	@DisplayName("Should remove all prices and return boolean if any was removed")
	@Order(30)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveAllPricesAndReturnBooleanIfAnyRemoved(EvitaContract evita) {
		getProductByCode(evita, "product-8")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithNewParameterViaGetOrCreateMethodWithId(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product8Ref = getProductByCode(evita, "product-8").orElseThrow();
				final ProductInterfaceEditor product8 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertTrue(product8.removeAllPrices());
				assertFalse(product8.removeAllPrices());

				final Optional<EntityMutation> mutation = product8.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(7, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product8.toInstance();
				assertEquals(0, modifiedInstance.getAllPricesAsList().size());

				product8.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertEquals(0, product8SE.getPrices().size());
			}
		);
	}

	@DisplayName("Should remove all prices and return their ids as array")
	@Order(30)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveAllPricesAndReturnTheirIdsAsArray(EvitaContract evita) {
		getProductByCode(evita, "product-8")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithNewParameterViaGetOrCreateMethodWithId(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product8Ref = getProductByCode(evita, "product-8").orElseThrow();
				final ProductInterfaceEditor product8 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				final int[] removedPriceIds = product8.removeAllPricesAndReturnTheirIds();
				for (int i = 1; i < 8; i++) {
					assertTrue(ArrayUtils.contains(removedPriceIds, i));
				}

				final Optional<EntityMutation> mutation = product8.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(7, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product8.toInstance();
				assertEquals(0, modifiedInstance.getAllPricesAsList().size());

				product8.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertEquals(0, product8SE.getPrices().size());
			}
		);
	}

	@DisplayName("Should remove all prices and return their ids as collection")
	@Order(31)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveAllPricesAndReturnTheirIdsAsCollection(EvitaContract evita) {
		getProductByCode(evita, "product-8")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithNewParameterViaGetOrCreateMethodWithId(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product8Ref = getProductByCode(evita, "product-8").orElseThrow();
				final ProductInterfaceEditor product8 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				final Collection<Integer> removedPriceIds = product8.removeAllPricesAndReturnCollectionOfTheirIds();
				for (int i = 1; i < 8; i++) {
					int expectedPriceId = i;
					assertTrue(removedPriceIds.stream().anyMatch(it -> it == expectedPriceId));
				}

				final Optional<EntityMutation> mutation = product8.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(7, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product8.toInstance();
				assertEquals(0, modifiedInstance.getAllPricesAsList().size());

				product8.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertEquals(0, product8SE.getPrices().size());
			}
		);
	}

	@DisplayName("Should remove all prices and return their price keys as array")
	@Order(32)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveAllPricesAndReturnTheirPriceKeysAsArray(EvitaContract evita) {
		getProductByCode(evita, "product-8")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithNewParameterViaGetOrCreateMethodWithId(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product8Ref = getProductByCode(evita, "product-8").orElseThrow();
				final ProductInterfaceEditor product8 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				final Set<Price.PriceKey> removedPriceIds = new HashSet<>(
					Arrays.asList(product8.removeAllPricesAndReturnTheirPriceKeys())
				);

				assertTrue(removedPriceIds.contains(new Price.PriceKey(1, "reference", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(2, "vip", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(3, "vip", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(4, "vip", CURRENCY_USD)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(5, "basic", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(6, "basic", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(7, "basic", CURRENCY_CZK)));

				final Optional<EntityMutation> mutation = product8.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(7, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product8.toInstance();
				assertEquals(0, modifiedInstance.getAllPricesAsList().size());

				product8.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertEquals(0, product8SE.getPrices().size());
			}
		);
	}

	@DisplayName("Should remove all prices and return their price keys as collection")
	@Order(33)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveAllPricesAndReturnTheirPriceKeysAsCollection(EvitaContract evita) {
		getProductByCode(evita, "product-8")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithNewParameterViaGetOrCreateMethodWithId(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product8Ref = getProductByCode(evita, "product-8").orElseThrow();
				final ProductInterfaceEditor product8 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				final Set<Price.PriceKey> removedPriceIds = new HashSet<>(
					product8.removeAllPricesAndReturnCollectionOfTheirPriceKeys()
				);

				assertTrue(removedPriceIds.contains(new Price.PriceKey(1, "reference", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(2, "vip", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(3, "vip", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(4, "vip", CURRENCY_USD)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(5, "basic", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(6, "basic", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(7, "basic", CURRENCY_CZK)));

				final Optional<EntityMutation> mutation = product8.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(7, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product8.toInstance();
				assertEquals(0, modifiedInstance.getAllPricesAsList().size());

				product8.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertEquals(0, product8SE.getPrices().size());
			}
		);
	}

	@DisplayName("Should remove all prices and return them as array")
	@Order(34)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveAllPricesAndReturnThemAsArray(EvitaContract evita) {
		getProductByCode(evita, "product-8")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithNewParameterViaGetOrCreateMethodWithId(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product8Ref = getProductByCode(evita, "product-8").orElseThrow();
				final ProductInterfaceEditor product8 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				final Set<Price.PriceKey> removedPriceIds = Arrays.stream(product8.removeAllPricesAndReturnThem())
					.map(PriceContract::priceKey)
					.collect(Collectors.toSet());

				assertTrue(removedPriceIds.contains(new Price.PriceKey(1, "reference", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(2, "vip", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(3, "vip", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(4, "vip", CURRENCY_USD)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(5, "basic", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(6, "basic", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(7, "basic", CURRENCY_CZK)));

				final Optional<EntityMutation> mutation = product8.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(7, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product8.toInstance();
				assertEquals(0, modifiedInstance.getAllPricesAsList().size());

				product8.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertEquals(0, product8SE.getPrices().size());
			}
		);
	}

	@DisplayName("Should remove all prices and return them as collection")
	@Order(35)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveAllPricesAndReturnThemAsCollection(EvitaContract evita) {
		getProductByCode(evita, "product-8")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithNewParameterViaGetOrCreateMethodWithId(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product8Ref = getProductByCode(evita, "product-8").orElseThrow();
				final ProductInterfaceEditor product8 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				final Set<Price.PriceKey> removedPriceIds = product8.removeAllPricesAndReturnThemAsCollection().stream()
					.map(PriceContract::priceKey)
					.collect(Collectors.toSet());

				assertTrue(removedPriceIds.contains(new Price.PriceKey(1, "reference", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(2, "vip", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(3, "vip", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(4, "vip", CURRENCY_USD)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(5, "basic", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(6, "basic", CURRENCY_CZK)));
				assertTrue(removedPriceIds.contains(new Price.PriceKey(7, "basic", CURRENCY_CZK)));

				final Optional<EntityMutation> mutation = product8.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(7, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product8.toInstance();
				assertEquals(0, modifiedInstance.getAllPricesAsList().size());

				product8.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product8Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertEquals(0, product8SE.getPrices().size());
			}
		);
	}

	@DisplayName("Should remove one to many references by id and return their proxies")
	@Order(36)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveOneToManyReferencesAndReturnTheirProxies(EvitaContract evita) {
		final int categoryId1 = createCategoryEntityIfMissing(evita, 1);
		final int categoryId2 = createCategoryEntityIfMissing(evita, 2);

		getProductByCode(evita, "product-1")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithPricesAndReferences(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product1Ref = getProductByCode(evita, "product-1").orElseThrow();

				final ProductInterfaceEditor product1 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product1Ref.primaryKey(),
					referenceContentWithAttributes(
						Entities.CATEGORY,
						entityFetchAll()
					),
					referenceContentWithAttributes(
						Entities.STORE,
						entityFetchAll()
					)
				).orElseThrow();

				final ProductCategoryInterface removedCategory = product1.removeProductCategoryByIdAndReturnItsBody(categoryId2);
				assertNotNull(removedCategory);
				assertEquals(categoryId2, removedCategory.getCategory().getId());
				assertEquals(categoryId2, removedCategory.getCategoryReferencePrimaryKey());
				assertEquals(2L, removedCategory.getOrderInCategory());

				final StoreInterface store = product1.removeStoreById(1);
				assertNotNull(store);
				assertEquals(1, store.getId());
				assertEquals("Delirium-Tremens-1", store.getCode());

				final Optional<EntityMutation> mutation = product1.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(2, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product1.toInstance();
				assertEquals(1, modifiedInstance.getProductCategories().size());
				assertNotNull(modifiedInstance.getCategoryById(categoryId1));
				assertNull(modifiedInstance.getCategoryById(categoryId2));

				assertEquals(2, modifiedInstance.getStores().length);

				product1.upsertVia(evitaSession);

				final SealedEntity product8SE = evitaSession.getEntity(
					Entities.PRODUCT, product1Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertNull(product8SE.getReference(Entities.CATEGORY, categoryId2).orElse(null));
				assertNotNull(product8SE.getReference(Entities.CATEGORY, categoryId1).orElse(null));
				assertEquals(2, product8SE.getReferences(Entities.STORE).size());
			}
		);
	}

	@DisplayName("Should remove one to zero or one references by id and return their proxies")
	@Order(36)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveOneToZeroOrOneReferencesAndReturnTheirProxies(EvitaContract evita) {
		final int brandId = createBrandEntityIfMissing(evita);
		final int parameterId = createParameterEntityIfMissing(evita);

		getProductByCode(evita, "product-4")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithBrandSetAsPrimaryKey(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product4Ref = getProductByCode(evita, "product-4").orElseThrow();

				final ProductInterfaceEditor product4 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product4Ref.primaryKey(),
					referenceContentWithAttributes(
						Entities.BRAND,
						entityFetchAll()
					),
					referenceContentWithAttributes(
						Entities.PARAMETER,
						entityFetchAll()
					)
				).orElseThrow();

				final BrandInterface removedBrand = product4.removeBrandAndReturnItsBody();
				assertNotNull(removedBrand);
				assertEquals(brandId, removedBrand.getId());

				final ProductParameterInterface removedParameter = product4.removeParameterAndReturnItsBody();
				assertNotNull(removedParameter);
				assertEquals(parameterId, removedParameter.getPrimaryKey());

				final Optional<EntityMutation> mutation = product4.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(2, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product4.toInstance();
				assertNull(modifiedInstance.getBrand());
				assertNull(modifiedInstance.getParameter());

				assertThrows(
					ReferenceCardinalityViolatedException.class, () -> product4.upsertVia(evitaSession)
				);
			}
		);
	}

	@DisplayName("Should set reference group")
	@Order(37)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldSetReferenceGroup(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);
		final int parameterGroupId = createParameterGroupEntityIfMissing(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-9")
					.setName(CZECH_LOCALE, "Produkt 9")
					.setEnum(TestEnum.ONE)
					.setOptionallyAvailable(true)
					.setAlias(true)
					.setQuantity(BigDecimal.TEN)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setParameter(parameterId, that -> that.setPriority(10L).setParameterGroup(parameterGroupId));

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newProduct.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(15, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertEquals(parameterGroupId, modifiedInstance.getParameter().getParameterGroup());
				assertEquals(parameterGroupId, modifiedInstance.getParameter().getParameterGroupEntityClassifier().getPrimaryKey());

				newProduct.upsertVia(evitaSession);

				assertTrue(newProduct.getId() > 0);
				final SealedEntity product = evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow();
				final ReferenceContract theParameter = product.getReference(Entities.PARAMETER, parameterId).orElseThrow();
				assertEquals(parameterGroupId, theParameter.getGroup().orElseThrow().getPrimaryKey());

				final ProductInterface loadedProduct = evitaSession.getEntity(
					ProductInterfaceEditor.class, newProduct.getId(),
					hierarchyContent(), attributeContentAll(), associatedDataContentAll(), priceContentAll(),
					referenceContentAllWithAttributes(entityFetchAll(), entityGroupFetchAll()),
					dataInLocalesAll()
				).orElseThrow();

				final ParameterGroupInterfaceEditor groupEntity = loadedProduct.getParameter().getParameterGroupEntity();
				assertNotNull(groupEntity);
				assertEquals(parameterGroupId, groupEntity.getId());
				assertEquals("parameterGroup-1", groupEntity.getCode());

				loadedProduct.getParameter()
					.openForWrite()
					.removeParameterGroup()
					.upsertVia(evitaSession);

				final SealedEntity productAgain = evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow();
				final ReferenceContract theParameterAgain = productAgain.getReference(Entities.PARAMETER, parameterId).orElseThrow();
				assertTrue(theParameterAgain.getGroup().isEmpty());

				final ProductInterface loadedProductAgain = evitaSession.getEntity(
					ProductInterfaceEditor.class, newProduct.getId(),
					hierarchyContent(), attributeContentAll(), associatedDataContentAll(), priceContentAll(),
					referenceContentAllWithAttributes(entityFetchAll(), entityGroupFetchAll()),
					dataInLocalesAll()
				).orElseThrow();

				assertNull(loadedProductAgain.getParameter().getParameterGroup());
				assertNull(loadedProductAgain.getParameter().getParameterGroupEntityClassifier());
				assertNull(loadedProductAgain.getParameter().getParameterGroupEntity());
			}
		);
	}

	@DisplayName("Should set reference group as entity reference")
	@Order(38)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldSetReferenceGroupAsEntityReference(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);
		final int parameterGroupId = createParameterGroupEntityIfMissing(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-10")
					.setName(CZECH_LOCALE, "Produkt 10")
					.setEnum(TestEnum.ONE)
					.setOptionallyAvailable(true)
					.setAlias(true)
					.setQuantity(BigDecimal.TEN)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setParameter(parameterId, that -> that.setPriority(10L)
						.setParameterGroupEntityClassifier(new EntityReference(Entities.PARAMETER_GROUP, parameterGroupId))
					);

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newProduct.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(15, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertEquals(parameterGroupId, modifiedInstance.getParameter().getParameterGroup());
				assertEquals(parameterGroupId, modifiedInstance.getParameter().getParameterGroupEntityClassifier().getPrimaryKey());

				newProduct.upsertVia(evitaSession);

				assertTrue(newProduct.getId() > 0);
				final SealedEntity product = evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow();
				final ReferenceContract theParameter = product.getReference(Entities.PARAMETER, parameterId).orElseThrow();
				assertEquals(parameterGroupId, theParameter.getGroup().orElseThrow().getPrimaryKey());

				final ProductInterface loadedProduct = evitaSession.getEntity(
					ProductInterfaceEditor.class, newProduct.getId(),
					hierarchyContent(), attributeContentAll(), associatedDataContentAll(), priceContentAll(),
					referenceContentAllWithAttributes(entityFetchAll(), entityGroupFetchAll()),
					dataInLocalesAll()
				).orElseThrow();

				final ParameterGroupInterfaceEditor groupEntity = loadedProduct.getParameter().getParameterGroupEntity();
				assertNotNull(groupEntity);
				assertEquals(parameterGroupId, groupEntity.getId());
				assertEquals("parameterGroup-1", groupEntity.getCode());
			}
		);
	}

	@DisplayName("Should set reference group as entity")
	@Order(39)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldSetReferenceGroupAsEntity(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);
		final int parameterGroupId = createParameterGroupEntityIfMissing(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ParameterGroupInterfaceEditor parameterGroupEntity = evitaSession.getEntity(
					ParameterGroupInterfaceEditor.class, parameterGroupId, entityFetchAllContent()
				).orElseThrow();

				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-11")
					.setName(CZECH_LOCALE, "Produkt 11")
					.setEnum(TestEnum.ONE)
					.setOptionallyAvailable(true)
					.setAlias(true)
					.setQuantity(BigDecimal.TEN)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setParameter(parameterId, that -> that.setPriority(10L)
						.setParameterGroupEntity(parameterGroupEntity)
					);

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newProduct.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(15, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = newProduct.toInstance();
				assertEquals(parameterGroupId, modifiedInstance.getParameter().getParameterGroup());
				assertEquals(parameterGroupId, modifiedInstance.getParameter().getParameterGroupEntityClassifier().getPrimaryKey());

				newProduct.upsertVia(evitaSession);

				assertTrue(newProduct.getId() > 0);
				final SealedEntity product = evitaSession.getEntity(Entities.PRODUCT, newProduct.getId(), entityFetchAllContent()).orElseThrow();
				final ReferenceContract theParameter = product.getReference(Entities.PARAMETER, parameterId).orElseThrow();
				assertEquals(parameterGroupId, theParameter.getGroup().orElseThrow().getPrimaryKey());

				final ProductInterface loadedProduct = evitaSession.getEntity(
					ProductInterfaceEditor.class, newProduct.getId(),
					hierarchyContent(), attributeContentAll(), associatedDataContentAll(), priceContentAll(),
					referenceContentAllWithAttributes(entityFetchAll(), entityGroupFetchAll()),
					dataInLocalesAll()
				).orElseThrow();

				final ParameterGroupInterfaceEditor groupEntity = loadedProduct.getParameter().getParameterGroupEntity();
				assertNotNull(groupEntity);
				assertEquals(parameterGroupId, groupEntity.getId());
				assertEquals("parameterGroup-1", groupEntity.getCode());
			}
		);
	}

	@DisplayName("Should set reference group as newly created entity")
	@Order(40)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldSetReferenceGroupAsNewlyCreatedEntity(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-12")
					.setName(CZECH_LOCALE, "Produkt 12")
					.setEnum(TestEnum.ONE)
					.setOptionallyAvailable(true)
					.setAlias(true)
					.setQuantity(BigDecimal.TEN)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setParameter(parameterId, that -> that.setPriority(10L));

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				newProduct.upsertVia(evitaSession);

				newProduct.getParameter()
					.openForWrite()
					.getOrCreateParameterGroupEntity(newGroup -> newGroup.setCode("parameterGroup-2"))
					.upsertDeeplyVia(evitaSession);

				final EntityReference createdParameterGroup = getParameterGroupByCode(evita, "parameterGroup-2")
					.orElseThrow();

				final ProductInterface loadedProduct = evitaSession.getEntity(
					ProductInterfaceEditor.class, newProduct.getId(),
					hierarchyContent(), attributeContentAll(), associatedDataContentAll(), priceContentAll(),
					referenceContentAllWithAttributes(entityFetchAll(), entityGroupFetchAll()),
					dataInLocalesAll()
				).orElseThrow();

				final ParameterGroupInterfaceEditor groupEntity = loadedProduct.getParameter().getParameterGroupEntity();
				assertNotNull(groupEntity);
				assertEquals(createdParameterGroup.getPrimaryKey(), groupEntity.getId());
				assertEquals("parameterGroup-2", groupEntity.getCode());
			}
		);
	}

	@DisplayName("Should set reference group by id and update it")
	@Order(41)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldSetReferenceGroupByIdAndUpdateIt(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);
		final int parameterGroupId = createParameterGroupEntityIfMissing(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-13")
					.setName(CZECH_LOCALE, "Produkt 13")
					.setEnum(TestEnum.ONE)
					.setOptionallyAvailable(true)
					.setAlias(true)
					.setQuantity(BigDecimal.TEN)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setParameter(parameterId, that -> that.setPriority(10L).setParameterGroup(parameterGroupId));

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				newProduct.upsertVia(evitaSession);

				newProduct.getParameter()
					.openForWrite()
					.getOrCreateParameterGroupEntity(newGroup -> newGroup.setCode("parameterGroup-20"))
					.upsertDeeplyVia(evitaSession);

				final EntityReference createdParameterGroup = getParameterGroupByCode(evita, "parameterGroup-20")
					.orElseThrow();

				final ProductInterface loadedProduct = evitaSession.getEntity(
					ProductInterfaceEditor.class, newProduct.getId(),
					hierarchyContent(), attributeContentAll(), associatedDataContentAll(), priceContentAll(),
					referenceContentAllWithAttributes(entityFetchAll(), entityGroupFetchAll()),
					dataInLocalesAll()
				).orElseThrow();

				final ParameterGroupInterfaceEditor groupEntity = loadedProduct.getParameter().getParameterGroupEntity();
				assertNotNull(groupEntity);
				assertEquals(createdParameterGroup.getPrimaryKey(), groupEntity.getId());
				assertEquals("parameterGroup-20", groupEntity.getCode());
			}
		);
	}

	@DisplayName("Should set and remove attributes and associated data")
	@Order(42)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldSetAndRemoveAttributesAndAssociatedData(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);
		final int parameterGroupId = createParameterGroupEntityIfMissing(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final ProductInterfaceEditor newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-14")
					.setName(CZECH_LOCALE, "Produkt 14")
					.setName(Locale.ENGLISH, "Product 14")
					.setEnum(TestEnum.ONE)
					.setOptionallyAvailable(true)
					.setAlias(true)
					.setQuantity(BigDecimal.TEN)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setParameter(parameterId, that -> that.setPriority(10L).setParameterGroup(parameterGroupId));

				newProduct.setLabels(new Labels(), CZECH_LOCALE);
				newProduct.setLabels(new Labels(), Locale.ENGLISH);
				newProduct.setReferencedFileSet(new ReferencedFileSet());

				newProduct.upsertVia(evitaSession);

				final ProductInterfaceEditor loadedProduct = evitaSession.getEntity(
					ProductInterfaceEditor.class, newProduct.getId(),
					hierarchyContent(), attributeContentAll(), associatedDataContentAll(), priceContentAll(),
					referenceContentAllWithAttributes(entityFetchAll(), entityGroupFetchAll()),
					dataInLocalesAll()
				).orElseThrow();

				assertEquals("Produkt 14", loadedProduct.removeName(CZECH_LOCALE));
				assertEquals(78L, loadedProduct.removePriority());
				assertArrayEquals(new String[]{"market-1", "market-2"}, loadedProduct.removeMarketsAttribute().toArray(new String[0]));
				assertEquals(new ReferencedFileSet(), loadedProduct.removeReferencedFileSet());
				assertEquals(new Labels(), loadedProduct.removeLabels(CZECH_LOCALE));
				assertArrayEquals(new String[]{"market-3", "market-4"}, loadedProduct.removeMarkets().toArray(new String[0]));

				final Collection<? extends LocalMutation<?, ?>> localMutations = loadedProduct.toMutation().orElseThrow().getLocalMutations();
				final BitSet expectedMutations = new BitSet(6);
				assertEquals(6, localMutations.size());
				for (LocalMutation<?, ?> localMutation : localMutations) {
					if (localMutation instanceof RemoveAttributeMutation ram) {
						if (ram.getAttributeKey().attributeName().equals(ATTRIBUTE_NAME)) {
							assertEquals(CZECH_LOCALE, ram.getAttributeKey().locale());
							expectedMutations.set(0, true);
						} else if (ram.getAttributeKey().attributeName().equals(ATTRIBUTE_PRIORITY)) {
							expectedMutations.set(1, true);
						} else if (ram.getAttributeKey().attributeName().equals(ATTRIBUTE_MARKETS)) {
							expectedMutations.set(2, true);
						} else {
							fail("Unexpected attribute: " + ram.getAttributeKey());
						}
					} else if (localMutation instanceof RemoveAssociatedDataMutation radm) {
						if (radm.getAssociatedDataKey().associatedDataName().equals(ASSOCIATED_DATA_LABELS)) {
							assertEquals(CZECH_LOCALE, radm.getAssociatedDataKey().locale());
							expectedMutations.set(3, true);
						} else if (radm.getAssociatedDataKey().associatedDataName().equals(ASSOCIATED_DATA_MARKETS)) {
							expectedMutations.set(4, true);
						} else if (radm.getAssociatedDataKey().associatedDataName().equals(ASSOCIATED_DATA_REFERENCED_FILES)) {
							expectedMutations.set(4, true);
						} else {
							fail("Unexpected associated data: " + radm.getAssociatedDataKey());
						}
					} else {
						fail("Unexpected mutation: " + localMutation);
					}
				}

				for (int i = 0; i < expectedMutations.length(); i++) {
					assertTrue(expectedMutations.get(i));
				}

				assertThrows(
					MandatoryAttributesNotProvidedException.class,
					() -> loadedProduct.upsertVia(evitaSession)
				);
			}
		);
	}

	@DisplayName("Should remove all references and return their ids")
	@Order(43)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveAllReferences(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);
		final int categoryId1 = createCategoryEntityIfMissing(evita, 1);
		final int categoryId2 = createCategoryEntityIfMissing(evita, 2);

		getProductByCode(evita, "product-1")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithPricesAndReferences(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product1Ref = getProductByCode(evita, "product-1").orElseThrow();
				final ProductInterfaceEditor product1 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product1Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				final List<Integer> removedCategories = product1.removeAllProductCategoriesAndReturnTheirIds();
				assertEquals(2, removedCategories.size());
				assertTrue(removedCategories.contains(categoryId1));
				assertTrue(removedCategories.contains(categoryId2));

				final Optional<EntityMutation> mutation = product1.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(2, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product1.toInstance();
				assertTrue(modifiedInstance.getProductCategories().isEmpty());

				product1.upsertVia(evitaSession);

				final SealedEntity product1SE = evitaSession.getEntity(
					Entities.PRODUCT, product1Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertNotNull(product1SE.getReference(Entities.PARAMETER, parameterId).orElse(null));
				assertNull(product1SE.getReference(Entities.CATEGORY, categoryId2).orElse(null));
				assertNull(product1SE.getReference(Entities.CATEGORY, categoryId1).orElse(null));
			}
		);
	}

	@DisplayName("Should remove all references and return their bodies")
	@Order(44)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRemoveAllReferencesAndReturnTheirBodies(EvitaContract evita) {
		final int parameterId = createParameterEntityIfMissing(evita);
		final int categoryId1 = createCategoryEntityIfMissing(evita, 1);
		final int categoryId2 = createCategoryEntityIfMissing(evita, 2);

		getProductByCode(evita, "product-1")
			.ifPresent(it -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(it.getType(), it.getPrimaryKey());
				}
			));
		shouldCreateNewCustomProductWithPricesAndReferences(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReference product1Ref = getProductByCode(evita, "product-1").orElseThrow();
				final ProductInterfaceEditor product1 = evitaSession.getEntity(
					ProductInterfaceEditor.class, product1Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				final List<ProductCategoryInterfaceEditor> removedCategories = product1.removeAllProductCategoriesAndReturnTheirBodies();
				assertEquals(2, removedCategories.size());
				assertTrue(removedCategories.stream().anyMatch(it -> it.getPrimaryKey() == categoryId1));
				assertTrue(removedCategories.stream().anyMatch(it -> it.getPrimaryKey() == categoryId2));

				final Optional<EntityMutation> mutation = product1.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(2, mutation.get().getLocalMutations().size());

				final ProductInterface modifiedInstance = product1.toInstance();
				assertTrue(modifiedInstance.getProductCategories().isEmpty());

				product1.upsertVia(evitaSession);

				final SealedEntity product1SE = evitaSession.getEntity(
					Entities.PRODUCT, product1Ref.primaryKey(), entityFetchAllContent()
				).orElseThrow();

				assertNotNull(product1SE.getReference(Entities.PARAMETER, parameterId).orElse(null));
				assertNull(product1SE.getReference(Entities.CATEGORY, categoryId2).orElse(null));
				assertNull(product1SE.getReference(Entities.CATEGORY, categoryId1).orElse(null));
			}
		);
	}

}
