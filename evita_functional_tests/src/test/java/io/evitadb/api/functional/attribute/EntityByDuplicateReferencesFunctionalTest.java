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

package io.evitadb.api.functional.attribute;

import com.github.javafaker.Faker;
import io.evitadb.api.functional.attribute.EntityByChainOrderingFunctionalTest.EntityReferenceDTO;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Predecessor;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static graphql.Assert.assertNotNull;
import static io.evitadb.api.functional.attribute.EntityByChainOrderingFunctionalTest.collectProductIndex;
import static io.evitadb.api.functional.attribute.EntityByChainOrderingFunctionalTest.sortProductsInByReferenceAttributeOfChainableType;
import static io.evitadb.api.functional.attribute.EntityByChainOrderingFunctionalTest.updateReferenceAttributeInProduct;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies the behavior related to the duplicate references of the same type in entities and reflected
 * references to them. It tests both indexing as well as querying capabilities of evitaDB.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Evita entity duplicate references handling")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EntityByDuplicateReferencesFunctionalTest {
	private static final String DUPLICATE_REFERENCES = "duplicateReferences";
	private static final String DUPLICATE_REFERENCES_SCHEMA_ONLY = "duplicateReferencesSchemaOnly";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String REFERENCE_BRAND = "brand";
	private static final String ATTRIBUTE_CATEGORY_ORDER = "categoryOrder";
	private static final String ATTRIBUTE_BRAND_ORDER = "brandOrder";
	private static final String ATTRIBUTE_COUNTRY = "country";
	private static final String REFERENCE_CATEGORY_PRODUCTS = "products";
	private static final int SEED = 40;
	private final static int PRODUCT_COUNT = 100;
	private final static int CATEGORY_COUNT = 10;
	private final static int BRAND_COUNT = 10;

	@Nullable
	@DataSet(value = DUPLICATE_REFERENCES_SCHEMA_ONLY, destroyAfterClass = true, readOnly = false)
	DataCarrier setUpOnlySchemas(Evita evita) {
		return evita.updateCatalog(
			TEST_CATALOG, session -> {
				// we need to create category schema first
				session
					.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withReflectedReferenceToEntity(
						REFERENCE_CATEGORY_PRODUCTS,
						Entities.PRODUCT,
						REFERENCE_CATEGORIES,
						whichIs ->
							whichIs.indexedForFiltering()
							       .withAttributesInherited(ATTRIBUTE_COUNTRY)
					)
					.updateAndFetchVia(session);

				// we need to create brand schema first
				session
					.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH, Locale.GERMAN)
					.updateAndFetchVia(session);

				// then the product schema
				session.defineEntitySchema(Entities.PRODUCT)
				       .withLocale(Locale.ENGLISH, Locale.GERMAN)
				       .withAttribute(
					       ATTRIBUTE_NAME, String.class, thatIs -> thatIs.sortable().filterable().localized())
				       .withReferenceToEntity(
					       REFERENCE_CATEGORIES,
					       Entities.CATEGORY,
					       Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
					       whichIs -> whichIs
						       .indexedForFiltering()
						       .withAttribute(
							       ATTRIBUTE_COUNTRY, String.class,
							       thatIs -> thatIs.sortable().filterable().representative()
						       )
						       .withAttribute(
							       ATTRIBUTE_CATEGORY_ORDER, Predecessor.class, AttributeSchemaEditor::sortable)
				       )
				       .withReferenceToEntity(
					       REFERENCE_BRAND,
					       Entities.CATEGORY,
					       Cardinality.ONE_OR_MORE_WITH_DUPLICATES,
					       whichIs -> whichIs
						       .indexedForFilteringAndPartitioning()
						       .withAttribute(
							       ATTRIBUTE_COUNTRY, String.class,
							       thatIs -> thatIs.sortable().filterable().representative()
						       )
						       .withAttribute(ATTRIBUTE_BRAND_ORDER, Predecessor.class, AttributeSchemaEditor::sortable)
				       )
				       .updateAndFetchVia(session);

				return new DataCarrier();
			}
		);
	}

	@Nullable
	@DataSet(value = DUPLICATE_REFERENCES, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(
			TEST_CATALOG, session -> {
				final DataGenerator dataGenerator = new DataGenerator.Builder()
					// we need to update the order in second pass
					.registerValueGenerator(
						Entities.CATEGORY, ATTRIBUTE_CATEGORY_ORDER,
						faker -> Predecessor.HEAD
					)
					.build();

				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
					final int entityCount = session.getEntityCollectionSize(entityType);
					final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
					return primaryKey == 0 ? null : primaryKey;
				};

				// we need to create category schema first
				final SealedEntitySchema categorySchema = session
					.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withReflectedReferenceToEntity(
						REFERENCE_CATEGORY_PRODUCTS,
						Entities.PRODUCT,
						REFERENCE_CATEGORIES,
						whichIs -> whichIs.indexedForFiltering()
						                  .withAttributesInherited(
							                  ATTRIBUTE_COUNTRY)
					)
					.updateAndFetchVia(session);

				// and now data for categories
				dataGenerator.generateEntities(
					             categorySchema,
					             randomEntityPicker,
					             SEED
				             )
				             .limit(CATEGORY_COUNT)
				             .forEach(session::upsertEntity);

				// we need to create brand schema first
				final SealedEntitySchema brandSchema = session
					.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH, Locale.GERMAN)
					.updateAndFetchVia(session);

				// and now data for brands
				dataGenerator.generateEntities(
					             brandSchema,
					             randomEntityPicker,
					             SEED
				             )
				             .limit(BRAND_COUNT)
				             .forEach(session::upsertEntity);

				// then the product schema
				final SealedEntitySchema productSchema = session
					.defineEntitySchema(Entities.PRODUCT)
					.withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withAttribute(
						ATTRIBUTE_NAME, String.class,
						thatIs -> thatIs.sortable()
						                .filterable()
						                .localized()
					)
					.withReferenceToEntity(
						REFERENCE_CATEGORIES,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						whichIs -> whichIs
							.indexedForFiltering()
							.withAttribute(
								ATTRIBUTE_CATEGORY_ORDER,
								Predecessor.class,
								AttributeSchemaEditor::sortable
							)
							.withAttribute(
								ATTRIBUTE_COUNTRY, String.class,
								thatIs -> thatIs.sortable()
								                .filterable()
							)
					)
					.withReferenceToEntity(
						REFERENCE_BRAND,
						Entities.CATEGORY,
						Cardinality.ONE_OR_MORE_WITH_DUPLICATES,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(
								ATTRIBUTE_BRAND_ORDER, Predecessor.class,
								AttributeSchemaEditor::sortable
							)
							.withAttribute(
								ATTRIBUTE_COUNTRY, String.class,
								thatIs -> thatIs.sortable()
								                .filterable()
							)
					)
					.updateAndFetchVia(session);

				// and now data for both of them (since they are intertwined via reflected reference)
				final List<EntityReference> storedProducts = dataGenerator
					.generateEntities(
						productSchema,
						randomEntityPicker,
						SEED
					)
					.limit(PRODUCT_COUNT)
					.map(session::upsertEntity)
					.toList();

				// second pass - update the category / brand order of the products
				updateReferenceAttributeInProduct(
					session,
					Entities.PRODUCT, REFERENCE_CATEGORIES, ATTRIBUTE_CATEGORY_ORDER,
					(reference, referencedProducts) -> {
						final int theIndex = ArrayUtils.indexOf(
							reference.getReferencedPrimaryKey(), referencedProducts);
						return theIndex == 0 ?
							Predecessor.HEAD : new Predecessor(referencedProducts[theIndex - 1]);
					}
				);
				updateReferenceAttributeInProduct(
					session,
					Entities.CATEGORY, REFERENCE_BRAND, ATTRIBUTE_BRAND_ORDER, (reference, referencedProducts) -> {
						final int theIndex = ArrayUtils.indexOf(
							reference.getReferencedPrimaryKey(), referencedProducts);
						return theIndex == 0 ?
							Predecessor.HEAD : new Predecessor(referencedProducts[theIndex - 1]);
					}
				);

				final Map<Integer, SealedEntity> products = collectProductIndex(session, storedProducts);

				final Map<Integer, List<SealedEntity>> productsInCategory = products
					.values()
					.stream()
					.flatMap(it -> it.getReferences(REFERENCE_CATEGORIES)
					                 .stream()
					                 .map(ref -> new EntityReferenceDTO(it, ref)))
					.collect(
						Collectors.groupingBy(
							it -> it.reference().getReferencedPrimaryKey(),
							Collectors.mapping(EntityReferenceDTO::entity, Collectors.toList())
						)
					);

				// now we need to sort the products in the category
				sortProductsInByReferenceAttributeOfChainableType(
					productsInCategory, Entities.PRODUCT, ATTRIBUTE_CATEGORY_ORDER);

				final Map<Integer, List<SealedEntity>> productsInBrand = products
					.values()
					.stream()
					.flatMap(it -> it.getReferences(REFERENCE_BRAND)
					                 .stream()
					                 .map(ref -> new EntityReferenceDTO(it, ref)))
					.collect(
						Collectors.groupingBy(
							it -> it.reference().getReferencedPrimaryKey(),
							Collectors.mapping(EntityReferenceDTO::entity, Collectors.toList())
						)
					);

				sortProductsInByReferenceAttributeOfChainableType(
					productsInBrand, Entities.PRODUCT, ATTRIBUTE_BRAND_ORDER);

				return new DataCarrier(
					REFERENCE_CATEGORY_PRODUCTS, products,
					"productsInCategory", productsInCategory,
					"productsInBrand", productsInBrand
				);
			}
		);
	}

	@DisplayName("The product should be indexed when it contains duplicate references")
	@UseDataSet(value = DUPLICATE_REFERENCES_SCHEMA_ONLY, destroyAfterTest = true)
	@Test
	void shouldIndexProductWithReferences(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// first create a single brand and two categories
				final EntityReference brand = session
					.createNewEntity(Entities.BRAND, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Sony, Inc.")
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Sony GmbH")
					.upsertVia(session);

				final EntityReference categoryRef1 = session
					.createNewEntity(Entities.CATEGORY, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Cameras")
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Kameras")
					.upsertVia(session);

				final EntityReference categoryRef2 = session
					.createNewEntity(Entities.CATEGORY, 2)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TVs")
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Fernseher")
					.upsertVia(session);

				assertNotNull(brand);
				assertNotNull(categoryRef1);
				assertNotNull(categoryRef2);

				// now create a product that references the same brand and category twice
				final EntityReference product = session
					.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Bravia TV")
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Bravia Fernseher")
					.setReference(
						REFERENCE_BRAND,
						1,
						ref -> false,
						refBuilder -> refBuilder
							.setAttribute(ATTRIBUTE_BRAND_ORDER, Predecessor.HEAD)
							.setAttribute(ATTRIBUTE_COUNTRY, "DE")
					)
					.setReference(
						REFERENCE_BRAND,
						1,
						ref -> true,
						refBuilder -> refBuilder
							.setAttribute(ATTRIBUTE_BRAND_ORDER, Predecessor.HEAD)
							.setAttribute(ATTRIBUTE_COUNTRY, "US")
					)
					.setReference(
						REFERENCE_CATEGORIES,
						1,
						ref -> false,
						refBuilder -> refBuilder
							.setAttribute(ATTRIBUTE_CATEGORY_ORDER, Predecessor.HEAD)
							.setAttribute(ATTRIBUTE_COUNTRY, "DE")
					)
					.setReference(
						REFERENCE_CATEGORIES,
						1,
						ref -> false,
						refBuilder -> refBuilder
							.setAttribute(ATTRIBUTE_CATEGORY_ORDER, Predecessor.HEAD)
							.setAttribute(ATTRIBUTE_COUNTRY, "US")
					)
					.setReference(
						REFERENCE_CATEGORIES,
						2,
						ref -> false,
						refBuilder -> refBuilder
							.setAttribute(ATTRIBUTE_CATEGORY_ORDER, Predecessor.HEAD)
							.setAttribute(ATTRIBUTE_COUNTRY, "DE")
					)
					.setReference(
						REFERENCE_CATEGORIES,
						2,
						ref -> false,
						refBuilder -> refBuilder
							.setAttribute(ATTRIBUTE_CATEGORY_ORDER, Predecessor.HEAD)
							.setAttribute(ATTRIBUTE_COUNTRY, "US")
					)
					.upsertVia(session);

				assertNotNull(product);

				// create helper record for reference identity that combines reference key with country attribute
				record ReferenceIdentity(String referenceName, int referencedPrimaryKey, String country) {
					static ReferenceIdentity from(@Nonnull ReferenceContract reference) {
						return new ReferenceIdentity(
							reference.getReferenceName(),
							reference.getReferencedPrimaryKey(),
							reference.getAttribute(ATTRIBUTE_COUNTRY)
						);
					}
				}

				// verify product was stored correctly
				final SealedEntity storedProduct = session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
				                                          .orElseThrow();
				assertEquals("Bravia TV", storedProduct.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
				assertEquals("Bravia Fernseher", storedProduct.getAttribute(ATTRIBUTE_NAME, Locale.GERMAN));

				// verify brand references
				final Set<ReferenceIdentity> brandReferences = storedProduct
					.getReferences(REFERENCE_BRAND)
					.stream()
					.map(ReferenceIdentity::from)
					.collect(Collectors.toSet());

				// brand reference was updated twice, so the country attribute was overwritten with the latter value
				assertEquals(
					Set.of(
						new ReferenceIdentity(REFERENCE_BRAND, 1, "US")
					),
					brandReferences
				);

				// verify category references
				final Set<ReferenceIdentity> categoryReferences = storedProduct
					.getReferences(REFERENCE_CATEGORIES)
					.stream()
					.map(ReferenceIdentity::from)
					.collect(Collectors.toSet());

				assertEquals(
					Set.of(
						new ReferenceIdentity(REFERENCE_CATEGORIES, 1, "DE"),
						new ReferenceIdentity(REFERENCE_CATEGORIES, 1, "US"),
						new ReferenceIdentity(REFERENCE_CATEGORIES, 2, "DE"),
						new ReferenceIdentity(REFERENCE_CATEGORIES, 2, "US")
					),
					categoryReferences
				);

				// verify reflected references in categories
				final SealedEntity category1 = session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
				                                      .orElseThrow();
				final Set<ReferenceIdentity> category1Products = category1
					.getReferences(REFERENCE_CATEGORY_PRODUCTS)
					.stream()
					.map(ReferenceIdentity::from)
					.collect(Collectors.toSet());

				assertEquals(
					Set.of(
						new ReferenceIdentity(REFERENCE_CATEGORY_PRODUCTS, 1, "DE"),
						new ReferenceIdentity(REFERENCE_CATEGORY_PRODUCTS, 1, "US")
					),
					category1Products
				);

				final SealedEntity category2 = session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
				                                      .orElseThrow();
				final Set<ReferenceIdentity> category2Products = category2
					.getReferences(REFERENCE_CATEGORY_PRODUCTS)
					.stream()
					.map(ReferenceIdentity::from)
					.collect(Collectors.toSet());

				assertEquals(
					Set.of(
						new ReferenceIdentity(REFERENCE_CATEGORY_PRODUCTS, 1, "DE"),
						new ReferenceIdentity(REFERENCE_CATEGORY_PRODUCTS, 1, "US")
					),
					category2Products
				);
			}
		);
	}

}
