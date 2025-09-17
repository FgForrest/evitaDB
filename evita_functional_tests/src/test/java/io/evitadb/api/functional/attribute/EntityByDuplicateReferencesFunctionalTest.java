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
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.functional.attribute.EntityByChainOrderingFunctionalTest.EntityReferenceDTO;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.Predecessor;
import io.evitadb.index.attribute.ChainIndex;
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
import java.util.Random;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static graphql.Assert.assertNotNull;
import static io.evitadb.api.functional.attribute.EntityByChainOrderingFunctionalTest.collectProductIndex;
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

	/**
	 * Sorts the products in a category based on the reference order attribute.
	 *
	 * @param productsInCategory     a map where the key is the referenced entity ID (non-null) and the value is a list of sealed entities (nullable).
	 * @param referenceName          the name of the reference to sort by (non-null).
	 * @param referenceAttributeName the name of the attribute to sort by (non-null).
	 */
	static void sortProductsInByReferenceAttributeOfChainableType(
		@Nonnull Map<EntityCountry, List<SealedEntity>> productsInCategory,
		@Nonnull String referenceName,
		@Nonnull String referenceAttributeName
	) {
		productsInCategory.forEach((key, value) -> {
			if (value != null) {
				// we rely on ChainIndex correctness - it's tested elsewhere
				final ChainIndex chainIndex = new ChainIndex(new AttributeKey(referenceAttributeName));
				for (SealedEntity entity : value) {
					entity.getReferences(
						      new ReferenceKey(referenceName, key.entityPrimaryKey())
					      )
					      .stream()
					      .filter(
						      ref -> ref.getReferencedPrimaryKey() == key.entityPrimaryKey() &&
							      key.country().equals(ref.getAttribute(ATTRIBUTE_COUNTRY))
					      )
					      .findFirst()
					      .ifPresent(reference -> {
						      final ChainableType attribute = reference.getAttribute(referenceAttributeName);
						      if (attribute != null) {
							      chainIndex.upsertPredecessor(
								      attribute,
								      entity.getPrimaryKeyOrThrowException()
							      );
						      }
					      });
				}
				// this is not much effective, but enough for a test
				final int[] sortedRecordIds = chainIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds();
				value.sort(
					(a, b) -> {
						final int aPos = ArrayUtils.indexOf(a.getPrimaryKeyOrThrowException(), sortedRecordIds);
						final int bPos = ArrayUtils.indexOf(b.getPrimaryKeyOrThrowException(), sortedRecordIds);
						return Integer.compare(aPos, bPos);
					}
				);
			}
		});
	}

	private static void createSchema(@Nonnull EvitaSessionContract session) {
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
					       thatIs -> thatIs.filterable().representative()
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
					       thatIs -> thatIs.filterable().representative()
				       )
				       .withAttribute(ATTRIBUTE_BRAND_ORDER, Predecessor.class, AttributeSchemaEditor::sortable)
		       )
		       .updateAndFetchVia(session);
	}

	/**
	 * Updates the reference attribute for each product within the given category map using the provided logic for predecessor creation.
	 *
	 * @param session            The session instance of {@link EvitaSessionContract} used to perform entity updates.
	 * @param productsInCategory A map containing lists of products grouped by {@link EntityCountry}.
	 * @param referenceName      The name
	 */
	private static void updateReferenceInProduct(
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<EntityCountry, List<SealedEntity>> productsInCategory,
		@Nonnull String referenceName,
		@Nonnull String referenceAttributeName,
		@Nonnull BiFunction<Integer, int[], Predecessor> predecessorCreator
	) {
		final Random rnd = new Random(SEED);
		productsInCategory
			.values()
			.forEach(productsByEntityCountry -> {
				final int[] referenceEntities = productsByEntityCountry
					.stream()
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();
				ArrayUtils.shuffleArray(rnd, referenceEntities, referenceEntities.length);
				for (SealedEntity sealedEntity : productsByEntityCountry) {
					final EntityBuilder entityBuilder = sealedEntity.openForWrite();
					sealedEntity
						.getReferences(referenceName)
						.forEach(
							reference -> {
								if (reference.getAttribute(referenceAttributeName) != null) {
									entityBuilder.setReference(
										referenceName,
										reference.getReferencedPrimaryKey(),
										ref -> ref.getReferenceKey().equals(reference.getReferenceKey()),
										whichIs -> whichIs.setAttribute(
											referenceAttributeName,
											predecessorCreator.apply(
												sealedEntity.getPrimaryKeyOrThrowException(),
												referenceEntities
											)
										)
									);
								}
							}
						);
					session.upsertEntity(entityBuilder);
				}
			});
	}

	@Nullable
	@DataSet(value = DUPLICATE_REFERENCES_SCHEMA_ONLY, destroyAfterClass = true, readOnly = false)
	DataCarrier setUpOnlySchemas(Evita evita) {
		return evita.updateCatalog(
			TEST_CATALOG, session -> {
				createSchema(session);

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
						Entities.PRODUCT, ATTRIBUTE_CATEGORY_ORDER,
						(refenceKey, faker) -> Predecessor.HEAD
					)
					// we need to update the order in second pass
					.registerValueGenerator(
						Entities.PRODUCT, ATTRIBUTE_BRAND_ORDER,
						(refenceKey, faker) -> Predecessor.HEAD
					)
					.build();

				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
					final int entityCount = session.getEntityCollectionSize(entityType);
					final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
					return primaryKey == 0 ? null : primaryKey;
				};

				createSchema(session);

				// and now data for categories
				dataGenerator.generateEntities(
					             session.getEntitySchemaOrThrowException(Entities.CATEGORY),
					             randomEntityPicker,
					             SEED
				             )
				             .limit(CATEGORY_COUNT)
				             .forEach(session::upsertEntity);

				// and now data for brands
				dataGenerator.generateEntities(
					             session.getEntitySchemaOrThrowException(Entities.BRAND),
					             randomEntityPicker,
					             SEED
				             )
				             .limit(BRAND_COUNT)
				             .forEach(session::upsertEntity);

				// and now data for both of them (since they are intertwined via reflected reference)
				final List<EntityReference> storedProducts = dataGenerator
					.generateEntities(
						session.getEntitySchemaOrThrowException(Entities.PRODUCT),
						randomEntityPicker,
						SEED
					)
					.limit(PRODUCT_COUNT)
					.map(session::upsertEntity)
					.toList();

				// collect the references by countries and referenced entity
				final Map<Integer, SealedEntity> products = collectProductIndex(session, storedProducts);

				final Map<EntityCountry, List<SealedEntity>> productsInCategory = products
					.values()
					.stream()
					.flatMap(it -> it.getReferences(REFERENCE_CATEGORIES)
					                 .stream()
					                 .map(ref -> new EntityReferenceDTO(it, ref)))
					.collect(
						Collectors.groupingBy(
							it -> new EntityCountry(
								it.reference().getReferencedPrimaryKey(),
								it.reference().getAttribute(ATTRIBUTE_COUNTRY)
							),
							Collectors.mapping(EntityReferenceDTO::entity, Collectors.toList())
						)
					);

				// second pass - update the category order of the products
				updateReferenceInProduct(
					session,
					productsInCategory,
					REFERENCE_CATEGORIES, ATTRIBUTE_CATEGORY_ORDER,
					(productPk, referencedProducts) -> {
						final int theIndex = ArrayUtils.indexOf(productPk, referencedProducts);
						return theIndex == 0 ?
							Predecessor.HEAD : new Predecessor(referencedProducts[theIndex - 1]);
					}
				);

				// now we need to sort the products in the category
				sortProductsInByReferenceAttributeOfChainableType(
					productsInCategory, REFERENCE_CATEGORIES, ATTRIBUTE_CATEGORY_ORDER
				);

				final Map<EntityCountry, List<SealedEntity>> productsInBrand = products
					.values()
					.stream()
					.flatMap(it -> it.getReferences(REFERENCE_BRAND)
					                 .stream()
					                 .map(ref -> new EntityReferenceDTO(it, ref)))
					.collect(
						Collectors.groupingBy(
							it -> new EntityCountry(
								it.reference().getReferencedPrimaryKey(),
								it.reference().getAttribute(ATTRIBUTE_COUNTRY)
							),
							Collectors.mapping(EntityReferenceDTO::entity, Collectors.toList())
						)
					);

				// second pass - update the category order of the products
				updateReferenceInProduct(
					session,
					productsInBrand,
					REFERENCE_BRAND, ATTRIBUTE_BRAND_ORDER,
					(productPk, referencedProducts) -> {
						final int theIndex = ArrayUtils.indexOf(productPk, referencedProducts);
						return theIndex == 0 ?
							Predecessor.HEAD : new Predecessor(referencedProducts[theIndex - 1]);
					}
				);

				sortProductsInByReferenceAttributeOfChainableType(
					productsInBrand, REFERENCE_BRAND, ATTRIBUTE_BRAND_ORDER
				);

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

	@DisplayName("The product should filter entities by duplicate references")
	@UseDataSet(value = DUPLICATE_REFERENCES, destroyAfterTest = true)
	@Test
	void shouldFilterProductWithDuplicateReferences(Evita evita) {

	}

	/**
	 * Key to identify brand/category uniquely in combination with country attribute.
	 *
	 * @param entityPrimaryKey primary key of the category
	 * @param country          value of the country attribute
	 */
	record EntityCountry(
		int entityPrimaryKey,
		@Nonnull String country
	) {
	}

}
