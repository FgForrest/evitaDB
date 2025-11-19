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
import io.evitadb.api.exception.AmbiguousReferenceException;
import io.evitadb.api.proxy.mock.BrandInterfaceEditor;
import io.evitadb.api.proxy.mock.CategoryInterface;
import io.evitadb.api.proxy.mock.CategoryInterfaceEditor;
import io.evitadb.api.proxy.mock.CategoryInterfaceSealed;
import io.evitadb.api.proxy.mock.ProductInterfaceEditor;
import io.evitadb.api.proxy.mock.SealedProductInterface;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
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

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the ability to proxy an entity into an arbitrary interface which is isolated from the original
 * immutable entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Evita isolated entity editor interface proxying functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@TestMethodOrder(OrderAnnotation.class)
@Slf4j
public class IsolatedEntityEditorProxyingFunctionalTest extends AbstractEntityProxyingFunctionalTest implements EvitaTestSupport {
	protected static final String HUNDRED_PRODUCTS = "HundredProxyProducts_IsolatedEntityEditorProxyingFunctionalTest";
	private static final DateTimeRange VALIDITY = DateTimeRange.between(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1));

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

	@DataSet(value = HUNDRED_PRODUCTS, destroyAfterClass = true, readOnly = false)
	@Override
	protected DataCarrier setUp(Evita evita) {
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
				/*
					This is somehow weird scenario - created instances are always mutable - so the `openForWrite` is
					technically not necessary, but create new entity should be correctly called with CategoryInterfaceEditor
					here and not the CategoryInterfaceSealed
				 */
				final CategoryInterfaceEditor newCategory = evitaSession.createNewEntity(CategoryInterfaceSealed.class, 1000)
					.openForWrite()
					.setCode("root-category")
					.setName(CZECH_LOCALE, "Kořenová kategorie")
					.setPriority(78L)
					.setValidity(VALIDITY);

				newCategory.setLabels(new Labels());
				newCategory.setReferencedFiles(new ReferencedFileSet());

				final Optional<EntityMutation> mutation = newCategory.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(6, mutation.get().getLocalMutations().size());

				final CategoryInterface modifiedInstance = newCategory.toInstance();
				assertEquals("root-category", modifiedInstance.getCode());
				assertEquals("Kořenová kategorie", modifiedInstance.getName(CZECH_LOCALE));
				assertEquals(78L, modifiedInstance.getPriority());
				assertEquals(VALIDITY, modifiedInstance.getValidity());

				newCategory.upsertVia(evitaSession);

				assertEquals(1000, newCategory.getId());
				assertCategory(
					evitaSession.getEntity(Entities.CATEGORY, newCategory.getId(), entityFetchAllContent()).orElseThrow(),
					"root-category", "Kořenová kategorie", 78L, VALIDITY
				);
			}
		);
	}

	@DisplayName("Should update existing entity of custom type")
	@Order(2)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldUpdateExistingEntityOfCustomType(EvitaContract evita) {
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
				final CategoryInterfaceSealed sealedCategory = evitaSession.queryOne(
					Query.query(
						filterBy(
							entityPrimaryKeyInSet(1000)
						),
						require(entityFetchAll())
					),
					CategoryInterfaceSealed.class
				).orElseThrow();

				final CategoryInterfaceEditor updatedCategory = sealedCategory
					.openForWrite()
					.setCode("updated-root-category")
					.setName(CZECH_LOCALE, "Aktualizovaná kořenová kategorie")
					.setPriority(178L)
					.setValidity(null);

				final Optional<EntityMutation> mutation = updatedCategory.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(4, mutation.get().getLocalMutations().size());

				final CategoryInterface modifiedInstance = updatedCategory.toInstance();
				assertEquals("updated-root-category", modifiedInstance.getCode());
				assertEquals("Aktualizovaná kořenová kategorie", modifiedInstance.getName(CZECH_LOCALE));
				assertEquals(178L, modifiedInstance.getPriority());
				assertNull(modifiedInstance.getValidity());

				assertEquals("root-category", sealedCategory.getCode());
				assertEquals("Kořenová kategorie", sealedCategory.getName(CZECH_LOCALE));
				assertEquals(78L, sealedCategory.getPriority());
				assertEquals(VALIDITY, sealedCategory.getValidity());

				updatedCategory.upsertVia(evitaSession);

				assertEquals(1000, updatedCategory.getId());
				assertCategory(
					evitaSession.getEntity(Entities.CATEGORY, updatedCategory.getId(), entityFetchAllContent()).orElseThrow(),
					"updated-root-category", "Aktualizovaná kořenová kategorie", 178L, null
				);
			}
		);
	}

	@DisplayName("Should create new entity of custom type with array of mutations")
	@Order(3)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewEntityOfCustomTypeWithArrayOfMutations(EvitaContract evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				if (evitaSession.getEntity(Entities.CATEGORY, 1000).isPresent()) {
					evitaSession.deleteEntity(Entities.CATEGORY, 1000);
				}
			}
		);
		shouldCreateNewEntityOfCustomType(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final CategoryInterfaceSealed sealedCategory = evitaSession.queryOne(
					Query.query(
						filterBy(
							entityPrimaryKeyInSet(1000)
						),
						require(entityFetchAll())
					),
					CategoryInterfaceSealed.class
				).orElseThrow();

				final CategoryInterfaceEditor updatedCategory = sealedCategory
					.withMutations(
						new UpsertAttributeMutation("code", "updated-root-category"),
						new UpsertAttributeMutation("name", CZECH_LOCALE, "Aktualizovaná kořenová kategorie"),
						new UpsertAttributeMutation("priority", 178L),
						new RemoveAttributeMutation("validity")
					);

				final Optional<EntityMutation> mutation = updatedCategory.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(4, mutation.get().getLocalMutations().size());

				final CategoryInterface modifiedInstance = updatedCategory.toInstance();
				assertEquals("updated-root-category", modifiedInstance.getCode());
				assertEquals("Aktualizovaná kořenová kategorie", modifiedInstance.getName(CZECH_LOCALE));
				assertEquals(178L, modifiedInstance.getPriority());
				assertNull(modifiedInstance.getValidity());

				assertEquals("root-category", sealedCategory.getCode());
				assertEquals("Kořenová kategorie", sealedCategory.getName(CZECH_LOCALE));
				assertEquals(78L, sealedCategory.getPriority());
				assertEquals(VALIDITY, sealedCategory.getValidity());

				updatedCategory.upsertVia(evitaSession);

				assertEquals(1000, updatedCategory.getId());
				assertCategory(
					evitaSession.getEntity(Entities.CATEGORY, updatedCategory.getId(), entityFetchAllContent()).orElseThrow(),
					"updated-root-category", "Aktualizovaná kořenová kategorie", 178L, null
				);
			}
		);
	}

	@DisplayName("Should create new entity of custom type with collection of mutations")
	@Order(3)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewEntityOfCustomTypeWithCollectionOfMutations(EvitaContract evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				if (evitaSession.getEntity(Entities.CATEGORY, 1000).isPresent()) {
					evitaSession.deleteEntity(Entities.CATEGORY, 1000);
				}
			}
		);
		shouldCreateNewEntityOfCustomType(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final CategoryInterfaceSealed sealedCategory = evitaSession.queryOne(
					Query.query(
						filterBy(
							entityPrimaryKeyInSet(1000)
						),
						require(entityFetchAll())
					),
					CategoryInterfaceSealed.class
				).orElseThrow();

				final CategoryInterfaceEditor updatedCategory = sealedCategory
					.withMutations(
						Arrays.asList(
							new UpsertAttributeMutation("code", "updated-root-category"),
							new UpsertAttributeMutation("name", CZECH_LOCALE, "Aktualizovaná kořenová kategorie"),
							new UpsertAttributeMutation("priority", 178L),
							new RemoveAttributeMutation("validity")
						)
					);

				final Optional<EntityMutation> mutation = updatedCategory.toMutation();
				assertTrue(mutation.isPresent());
				assertEquals(4, mutation.get().getLocalMutations().size());

				final CategoryInterface modifiedInstance = updatedCategory.toInstance();
				assertEquals("updated-root-category", modifiedInstance.getCode());
				assertEquals("Aktualizovaná kořenová kategorie", modifiedInstance.getName(CZECH_LOCALE));
				assertEquals(178L, modifiedInstance.getPriority());
				assertNull(modifiedInstance.getValidity());

				assertEquals("root-category", sealedCategory.getCode());
				assertEquals("Kořenová kategorie", sealedCategory.getName(CZECH_LOCALE));
				assertEquals(78L, sealedCategory.getPriority());
				assertEquals(VALIDITY, sealedCategory.getValidity());

				updatedCategory.upsertVia(evitaSession);

				assertEquals(1000, updatedCategory.getId());
				assertCategory(
					evitaSession.getEntity(Entities.CATEGORY, updatedCategory.getId(), entityFetchAllContent()).orElseThrow(),
					"updated-root-category", "Aktualizovaná kořenová kategorie", 178L, null
				);
			}
		);
	}

	@DisplayName("Should create new product with all nested entities in one go")
	@Order(4)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldCreateNewCustomProductWithPricesAndReferences(EvitaContract evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				final EntityReferenceContract newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-1")
					.setName(CZECH_LOCALE, "Produkt 1")
					.setEnum(TestEnum.ONE)
					.setQuantity(BigDecimal.TEN)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setOrUpdateParameter(1, parameter -> parameter.setPriority(10L))
					.upsertVia(evitaSession);

				final SealedProductInterface sealedProduct = evitaSession.getEntity(
					SealedProductInterface.class, newProduct.getPrimaryKey(), entityFetchAllContent()
				).orElseThrow();

				final ProductInterfaceEditor productEditor = sealedProduct.openForWrite();
				final List<EntityReferenceContract> storedReferences = productEditor.setNewBrand(
						newBrand -> {
							final BrandInterfaceEditor brandEditor = newBrand.setCode("brand-1");
							brandEditor.setNewStore(
								store -> store.setCode("store-1")
									.setLabels(new Labels())
									.setReferencedFiles(new ReferencedFileSet())
							);
						}
					)
					.upsertDeeplyVia(evitaSession);

				assertEquals(3, storedReferences.size());
				int toFind = 3;
				for (EntityReferenceContract storedReference : storedReferences) {
					final SealedEntity theEntity = evitaSession.getEntity(
						storedReference.getType(), storedReference.getPrimaryKey(), entityFetchAllContent()
					).orElseThrow();
					if (storedReference.getType().equals(Entities.PRODUCT)) {
						assertEquals("product-1", theEntity.getAttribute(ATTRIBUTE_CODE));
						assertEquals(1, theEntity.getReferences(Entities.BRAND).size());
						toFind--;
					} else if (storedReference.getType().equals(Entities.BRAND)) {
						assertEquals("brand-1", theEntity.getAttribute(ATTRIBUTE_CODE));
						assertEquals(1, theEntity.getReferences(Entities.STORE).size());
						toFind--;
					} else if (storedReference.getType().equals(Entities.STORE)) {
						assertEquals("store-1", theEntity.getAttribute(ATTRIBUTE_CODE));
						assertNotNull(theEntity.getAssociatedData(ASSOCIATED_DATA_LABELS));
						assertNotNull(theEntity.getAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES));
						toFind--;
					} else {
						fail("Unexpected entity type: " + storedReference.getType());
					}
				}
				assertEquals(0, toFind);
			}
		);
	}

	@DisplayName("Should reset parameter using setParameter with consumer when parameter already exists")
	@Order(5)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldResetParameterWhenParameterAlreadyExists(EvitaContract evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				// Create product with initial parameter
				final EntityReferenceContract newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-5")
					.setName(CZECH_LOCALE, "Produkt 5")
					.setEnum(TestEnum.ONE)
					.setQuantity(BigDecimal.TEN)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setOrUpdateParameter(1, parameter -> parameter.setPriority(10L))
					.upsertVia(evitaSession);

				// Load the product and verify initial parameter
				final SealedProductInterface sealedProduct = evitaSession.getEntity(
					SealedProductInterface.class, newProduct.getPrimaryKey(),
					entityFetchAllContentAnd(referenceContentWithAttributes(Entities.PARAMETER, entityFetchAll()))
				).orElseThrow();

				assertEquals(1, sealedProduct.getParameter(1).getPrimaryKey());

				// Now use setParameter with consumer to reset the parameter completely
				final ProductInterfaceEditor productEditor = sealedProduct.openForWrite();
				// no parameter should be known when we enter the consumer
				productEditor.setParameter(1, parameter -> assertNull(parameter.getPriority()));
			}
		);
	}

	@DisplayName("Should access existing parameter using setParameter with consumer")
	@Order(6)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldAccessExistingParameterInUpdate(EvitaContract evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				// Create product without parameter
				final EntityReferenceContract newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-3")
					.setName(CZECH_LOCALE, "Produkt 3")
					.setEnum(TestEnum.ONE)
					.setQuantity(BigDecimal.TEN)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setOrUpdateParameter(1, parameter -> parameter.setPriority(10L))
					.upsertVia(evitaSession);

				// Load the product and verify parameter exists
				final SealedProductInterface sealedProduct = evitaSession.getEntity(
					SealedProductInterface.class, newProduct.getPrimaryKey(),
					entityFetchAllContentAnd(referenceContentWithAttributes(Entities.PARAMETER, entityFetchAll()))
				).orElseThrow();

				assertNotNull(sealedProduct.getParameter(1));

				// Use setParameter with consumer to verify existing parameter contents is available
				final ProductInterfaceEditor productEditor = sealedProduct.openForWrite();
				productEditor.updateParameter(
					1,
					parameter -> {
						assertEquals(10L, parameter.getPriority());
						parameter.setPriority(11L);
					}
				);

				productEditor.upsertVia(evitaSession);

				// Load the product and verify parameter code has changed
				final SealedProductInterface sealedProductAgain = evitaSession.getEntity(
					SealedProductInterface.class, newProduct.getPrimaryKey(),
					entityFetchAllContentAnd(referenceContentWithAttributes(Entities.PARAMETER, entityFetchAll()))
				).orElseThrow();

				assertEquals(11L, sealedProductAgain.getParameter(1).getPriority());
			}
		);
	}

	@DisplayName("Should reset parameter using setParameter with consumer when parameter does not exist")
	@Order(7)
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldNotUpdateParameterWhenParameterDoesNotExist(EvitaContract evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			evitaSession -> {
				// Create product without parameter
				final EntityReferenceContract newProduct = evitaSession.createNewEntity(ProductInterfaceEditor.class)
					.setCode("product-7")
					.setName(CZECH_LOCALE, "Produkt 7")
					.setEnum(TestEnum.ONE)
					.setQuantity(BigDecimal.TEN)
					.setPriority(78L)
					.setMarketsAttribute(new String[]{"market-1", "market-2"})
					.setMarkets(new String[]{"market-3", "market-4"})
					.setOrUpdateParameter(1, parameter -> parameter.setPriority(10L))
					.upsertVia(evitaSession);

				// Load the product and verify no parameter exists
				final SealedProductInterface sealedProduct = evitaSession.getEntity(
					SealedProductInterface.class, newProduct.getPrimaryKey(),
					entityFetchAllContentAnd(referenceContentWithAttributes(Entities.PARAMETER, entityFetchAll()))
				).orElseThrow();

				assertThrows(
					AmbiguousReferenceException.class,
					sealedProduct::getParameter
				);
				assertNull(sealedProduct.getParameter(2));

				// Use setParameter with consumer to verify existing parameter contents is available
				final ProductInterfaceEditor productEditor = sealedProduct.openForWrite();
				productEditor.updateParameter(2, parameter -> fail("Should not be called, parameter does not exist"));
			}
		);
	}

}
