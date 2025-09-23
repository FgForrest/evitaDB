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
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.functional.attribute.EntityByChainOrderingFunctionalTest.EntityReferenceDTO;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.RepresentativeReferenceKey;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;
import one.edee.oss.pmptt.model.Hierarchy;
import one.edee.oss.pmptt.model.HierarchyItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static graphql.Assert.assertNotNull;
import static io.evitadb.api.functional.attribute.EntityByChainOrderingFunctionalTest.collectProductIndex;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static org.junit.jupiter.api.Assertions.*;

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
	 * Retrieves the referenced entity index based on the provided parameters.
	 *
	 * @param evita                         the evita instance, must not be null
	 * @param scope                         the scope in which the index resides, must not be null
	 * @param entityType                    the type of the entity for which the index is being retrieved, must not be null
	 * @param recordId                      the unique record identifier of the entity
	 * @param representativeAttributeValues an array of representative attribute values used as part of the reference key, must not be null
	 * @return the referenced entity index if it exists, or null if it does not exist
	 */
	@Nullable
	static EntityIndex getReferencedEntityIndex(
		@Nonnull Evita evita,
		@Nonnull Scope scope,
		@Nonnull String entityType,
		@Nonnull String referenceName,
		int recordId,
		@Nonnull Serializable... representativeAttributeValues
	) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollectionContract collection = catalog.getCollectionForEntity(entityType).orElseThrow();
		Assert.isTrue(collection instanceof EntityCollection, "Unexpected entity collection type!");
		return ((EntityCollection) collection).getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY,
				scope,
				new RepresentativeReferenceKey(
					new ReferenceKey(referenceName, recordId),
					representativeAttributeValues
				)
			)
		);
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
					       ATTRIBUTE_CATEGORY_ORDER,
					       Predecessor.class,
					       thatIs -> thatIs.sortable().withDefaultValue(Predecessor.HEAD)
				       )
		       )
		       .withReferenceToEntity(
			       REFERENCE_BRAND,
			       Entities.BRAND,
			       Cardinality.ONE_OR_MORE_WITH_DUPLICATES,
			       whichIs -> whichIs
				       .indexedForFilteringAndPartitioning()
				       .withAttribute(
					       ATTRIBUTE_COUNTRY, String.class,
					       thatIs -> thatIs.filterable().representative()
				       )
				       .withAttribute(
					       ATTRIBUTE_BRAND_ORDER,
					       Predecessor.class,
					       thatIs -> thatIs.sortable().withDefaultValue(Predecessor.HEAD)
				       )
		       )
		       .updateAndFetchVia(session);
	}

	/**
	 * Updates the reference attribute for each product within the given category map using the provided logic for predecessor creation.
	 *
	 * @param session         The session instance of {@link EvitaSessionContract} used to perform entity updates.
	 * @param groupedProducts A map containing lists of products grouped by {@link EntityCountry}.
	 * @param referenceName   The name
	 */
	private static void updateReferenceInProduct(
		@Nonnull EvitaSessionContract session,
		@Nonnull Map<EntityCountry, List<SealedEntity>> groupedProducts,
		@Nonnull String referenceName,
		@Nonnull String referenceAttributeName,
		@Nonnull BiFunction<Integer, int[], Predecessor> predecessorCreator
	) {
		final Random rnd = new Random(SEED);
		groupedProducts
			.forEach((entityCountry, productsByEntityCountry) -> {
				final int[] referenceEntities = productsByEntityCountry
					.stream()
					.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
					.toArray();
				ArrayUtils.shuffleArray(rnd, referenceEntities, referenceEntities.length);
				productsByEntityCountry.sort(
					(o1, o2) -> {
						final int i1 = ArrayUtils.indexOf(o1.getPrimaryKeyOrThrowException(), referenceEntities);
						final int i2 = ArrayUtils.indexOf(o2.getPrimaryKeyOrThrowException(), referenceEntities);
						return Integer.compare(i1, i2);
					}
				);
				for (SealedEntity sealedEntity : productsByEntityCountry) {
					final EntityBuilder entityBuilder = sealedEntity.openForWrite();
					sealedEntity
						.getReferences(referenceName, entityCountry.entityPrimaryKey())
						.stream()
						.filter(reference -> entityCountry.country().equals(reference.getAttribute(ATTRIBUTE_COUNTRY)))
						.forEach(
							reference -> entityBuilder.setReference(
								referenceName,
								reference.getReferencedPrimaryKey(),
								ref -> ref.getReferenceKey().equals(reference.getReferenceKey()),
								whichIs -> {
									final Predecessor predecessor = predecessorCreator.apply(
										sealedEntity.getPrimaryKeyOrThrowException(),
										referenceEntities
									);
									whichIs.setAttribute(
										referenceAttributeName,
										predecessor
									);
								}
							)
						);
					assertNotNull(entityBuilder.toInstance());
					session.upsertEntity(entityBuilder);
				}
			});
	}

	/**
	 * Recursively adds products from the given category hierarchy to the provided list of products.
	 *
	 * @param products           The list to which products will be added.
	 * @param productsInCategory A map containing lists of products grouped by {@link EntityCountry}.
	 * @param categoryHierarchy  The hierarchy structure defining category relationships.
	 * @param country            The country identifier used to locate products within the hierarchy.
	 * @param rootItem           The root item from which to begin traversal of the category hierarchy.
	 */
	private static void addProducts(
		@Nonnull LinkedHashSet<SealedEntity> products,
		@Nonnull Map<EntityCountry, List<SealedEntity>> productsInCategory,
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull String country,
		@Nonnull HierarchyItem rootItem
	) {
		addProducts(products, productsInCategory, categoryHierarchy, country, rootItem, it -> true);
	}

	/**
	 * Recursively adds products from the given category hierarchy to the provided list of products.
	 *
	 * @param products           The list to which products will be added.
	 * @param productsInCategory A map containing lists of products grouped by {@link EntityCountry}.
	 * @param categoryHierarchy  The hierarchy structure defining category relationships.
	 * @param country            The country identifier used to locate products within the hierarchy.
	 * @param rootItem           The root item from which to begin traversal of the category hierarchy.
	 */
	private static void addProducts(
		@Nonnull LinkedHashSet<SealedEntity> products,
		@Nonnull Map<EntityCountry, List<SealedEntity>> productsInCategory,
		@Nonnull Hierarchy categoryHierarchy,
		@Nonnull String country,
		@Nonnull HierarchyItem rootItem,
		@Nonnull Predicate<SealedEntity> productFilter
	) {
		final int categoryId = Integer.parseInt(rootItem.getCode());
		Optional.ofNullable(productsInCategory.get(new EntityCountry(categoryId, country)))
		        .ifPresent(pic -> {
			        for (SealedEntity product : pic) {
				        if (productFilter.test(product)) {
					        products.add(product);
				        }
			        }
		        });
		final List<HierarchyItem> childItems = categoryHierarchy.getChildItems(rootItem.getCode());
		for (HierarchyItem childItem : childItems) {
			addProducts(products, productsInCategory, categoryHierarchy, country, childItem, productFilter);
		}
	}

	/**
	 * Counts the number of distinct references for a given reference name in a product entity.
	 *
	 * @param product       The {@link SealedEntity} representing the product whose references are to be counted.
	 * @param referenceName The name of the reference whose distinct values need to be counted.
	 * @return The number of distinct references for the given reference name.
	 */
	private static int countDuplicates(
		@Nonnull SealedEntity product,
		@Nonnull String referenceName
	) {
		return Math.toIntExact(
			product
				.getReferences(referenceName)
				.stream()
				.collect(
					Collectors.groupingBy(
						ReferenceContract::getReferencedPrimaryKey,
						Collectors.counting()
					)
				)
				.values()
				.stream()
				.max(Long::compareTo)
				.orElse(0L)
		);
	}

	/**
	 * Counts the number of distinct references for a given reference name in a product entity.
	 *
	 * @param product       The {@link SealedEntity} representing the product whose references are to be counted.
	 * @param referenceName The name of the reference whose distinct values need to be counted.
	 * @return The number of distinct references for the given reference name.
	 */
	private static int countDuplicates(
		@Nonnull SealedEntity product,
		@Nonnull String referenceName,
		int referencedEntityId
	) {
		return Math.toIntExact(
			product
				.getReferences(referenceName, referencedEntityId)
				.stream()
				.collect(
					Collectors.groupingBy(
						ReferenceContract::getReferencedPrimaryKey,
						Collectors.counting()
					)
				)
				.values()
				.stream()
				.max(Long::compareTo)
				.orElse(0L)
		);
	}

	/**
	 * Verifies that the stored product entity and its references are correctly reflected and updated in the session.
	 * This method ensures that attributes of the product, references to related entities, and referenced entities'
	 * reflected references are consistent with the expected states.
	 *
	 * @param session - The {@link EvitaSessionContract} instance used to retrieve and validate entities and their references.
	 */
	private static void assertStoredProductAndReflectedReferencesContents(@Nonnull EvitaSessionContract session) {
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

	/**
	 * Collects the cardinalities (counts) of categories grouped by country and entity primary key.
	 * This method retrieves a category entity from the session by its primary key, gathers its
	 * product references, and groups them based on the combination of the referenced primary key
	 * and a specified country attribute.
	 *
	 * @param session    The session instance of {@link EvitaSessionContract} used to fetch the category entity.
	 * @param primaryKey The primary key of the category entity for which cardinalities are to be calculated.
	 * @return A map where the keys are {@link EntityCountry} objects representing the combination of
	 * an entity's primary key and country, and the values are counts of occurrences.
	 */
	@Nonnull
	private static Map<EntityCountry, Long> collectCategoryCardinalities(
		@Nonnull EvitaSessionContract session, int primaryKey) {
		return session
			.getEntity(Entities.CATEGORY, primaryKey, entityFetchAllContent())
			.orElseThrow(() -> new ContextMissingException("Category with pk 1 is missing!"))
			.getReferences(REFERENCE_CATEGORY_PRODUCTS)
			.stream()
			.collect(
				Collectors.groupingBy(
					ref -> new EntityCountry(
						ref.getReferencedPrimaryKey(), ref.getAttribute(ATTRIBUTE_COUNTRY)),
					Collectors.counting()
				)
			);
	}

	/**
	 * Retrieves a list of product entity references filtered by a specific category and country.
	 *
	 * @param session the Evita session contract used to perform the query, must not be null
	 * @param country the country filter used in the query, must not be null
	 * @return a list of entity references that match the specified category and country filters
	 */
	@Nonnull
	private static List<EntityReference> getProductByCategoryCountry(
		@Nonnull EvitaSessionContract session, @Nonnull String country) {
		return session.queryListOfEntityReferences(
			query(
				collection(Entities.PRODUCT),
				filterBy(
					referenceHaving(
						REFERENCE_CATEGORIES,
						entityPrimaryKeyInSet(1),
						attributeEquals(ATTRIBUTE_COUNTRY, country)
					)
				)
			)
		);
	}

	/**
	 * Retrieves a list of product entity references where the reference category has the specified primary key
	 * and the attribute "country" is null.
	 *
	 * @param session The Evita session contract used to execute the query. Must not be null.
	 * @return A list of entity references representing the products matching the query criteria. Never null.
	 */
	@Nonnull
	private static List<EntityReference> getProductByNullCategoryCountry(@Nonnull EvitaSessionContract session) {
		return session.queryListOfEntityReferences(
			query(
				collection(Entities.PRODUCT),
				filterBy(
					referenceHaving(
						REFERENCE_CATEGORIES,
						entityPrimaryKeyInSet(1),
						attributeIsNull(ATTRIBUTE_COUNTRY)
					)
				)
			)
		);
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

				return new DataCarrier(
					"products", products,
					"productsInCategory", productsInCategory,
					"productsInBrand", productsInBrand,
					"categoryHierarchy", dataGenerator.getHierarchy(Entities.CATEGORY)
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
				assertStoredProductAndReflectedReferencesContents(session);
			}
		);
	}

	@DisplayName("The product should be indexed when it contains duplicate references (different order)")
	@UseDataSet(value = DUPLICATE_REFERENCES_SCHEMA_ONLY, destroyAfterTest = true)
	@Test
	void shouldIndexProductWithReferencesWithDifferentOrder(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// first create a product that references the same brand and category twice
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

				// now create a single brand and two categories
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

				assertNotNull(product);
				assertStoredProductAndReflectedReferencesContents(session);
			}
		);
	}

	@DisplayName("It must not be possible to create non-duplicated reflected reference to duplicated reference")
	@UseDataSet(value = DUPLICATE_REFERENCES_SCHEMA_ONLY, destroyAfterTest = true)
	@Test
	void failToSetupNonDuplicatedReflectedSchemaToDuplicateReferenceSchema(Evita evita) {
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// attempt to create reflected reference without duplicates to reference with duplicates
					// should fail
					session
						.defineEntitySchema(Entities.CATEGORY)
						.withReflectedReferenceToEntity(
							REFERENCE_CATEGORY_PRODUCTS,
							Entities.PRODUCT,
							REFERENCE_CATEGORIES,
							whichIs ->
								whichIs.withCardinality(Cardinality.ZERO_OR_MORE)
						)
						.updateAndFetchVia(session);
				}
			);
			fail("Should not be able to complete the transaction!");
		} catch (RollbackException ex) {
			final Throwable cause = ex.getCause();
			assertInstanceOf(InvalidSchemaMutationException.class, cause);
			assertEquals(
				"""
					Schema `CATEGORY` contains validation errors:
						Reference schema `products` contains validation errors:
						Reflected reference `products` cannot disallow duplicates, because the original reflected reference `categories` in entity `PRODUCT` allows them!""",
				cause.getMessage()
			);
		}
	}

	@DisplayName("It must not be possible to create reflected reference to duplicated reference without inheriting its representative attributes")
	@UseDataSet(value = DUPLICATE_REFERENCES_SCHEMA_ONLY, destroyAfterTest = true)
	@Test
	void failToSetupNonReflectedSchemaToDuplicateReferenceSchemaWithoutInheritingRepresentativeAttributes(Evita evita) {
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// attempt to create reflected reference without inheriting representative attribute
					// should fail
					session
						.defineEntitySchema(Entities.CATEGORY)
						.withReflectedReferenceToEntity(
							REFERENCE_CATEGORY_PRODUCTS,
							Entities.PRODUCT,
							REFERENCE_CATEGORIES,
							whichIs ->
								whichIs.indexedForFiltering()
								       .withoutAttributesInherited()
						)
						.updateAndFetchVia(session);
				}
			);
			fail("Should not be able to complete the transaction!");
		} catch (RollbackException ex) {
			final Throwable cause = ex.getCause();
			assertInstanceOf(InvalidSchemaMutationException.class, cause);
			assertEquals(
				"""
					Schema `CATEGORY` contains validation errors:
						Reference schema `products` contains validation errors:
						Reflected reference `products` must contain all representative attributes of the original reflected reference `categories` in entity `PRODUCT`! Missing representative attributes: country""",
				cause.getMessage()
			);
		}
	}

	@DisplayName("It must not be possible to create reflected reference to duplicated reference with localized representative attribute")
	@UseDataSet(value = DUPLICATE_REFERENCES_SCHEMA_ONLY, destroyAfterTest = true)
	@Test
	void failToSetupDuplicatedReferenceBasedOnLocalizedRepresentativeAttribute(Evita evita) {
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// attempt to create reflected reference without inheriting representative attribute
					// should fail
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							"someNewReference",
							Entities.CATEGORY,
							Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
							whichIs ->
								whichIs.indexedForFiltering()
									.withAttribute("name", String.class, thatIs -> thatIs.filterable().localized().representative())
						)
						.updateAndFetchVia(session);
				}
			);
			fail("Should not be able to complete the transaction!");
		} catch (RollbackException ex) {
			final Throwable cause = ex.getCause();
			assertInstanceOf(InvalidSchemaMutationException.class, cause);
			assertEquals(
				"""
					Schema `PRODUCT` contains validation errors:
						Reference schema `someNewReference` contains validation errors:
						Attribute `name` of reference schema `someNewReference` is marked as representative but also localized! This is not supported yet - see issue #956.""",
				cause.getMessage()
			);
		}
	}

	@DisplayName("The product references should be created via reflected references when duplicate references are present")
	@UseDataSet(value = DUPLICATE_REFERENCES_SCHEMA_ONLY, destroyAfterTest = true)
	@Test
	void shouldInsertDuplicatedReferencesViaReflectedReferences(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// create a product first that references the non-existing brand
				final EntityReference product = session
					.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Bravia TV")
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Bravia Fernseher")
					.setReference(
						REFERENCE_BRAND,
						1,
						ref -> true,
						refBuilder -> refBuilder
							.setAttribute(ATTRIBUTE_BRAND_ORDER, Predecessor.HEAD)
							.setAttribute(ATTRIBUTE_COUNTRY, "US")
					)
					.upsertVia(session);

				// then create a single brand
				final EntityReference brand = session
					.createNewEntity(Entities.BRAND, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Sony, Inc.")
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Sony GmbH")
					.upsertVia(session);

				// now create category with duplicated reflected reference to product
				final EntityReference categoryRef1 = session
					.createNewEntity(Entities.CATEGORY, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Cameras")
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Kameras")
					.setReference(
						REFERENCE_CATEGORY_PRODUCTS,
						1,
						filter -> false,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_COUNTRY, "DE")
					)
					.setReference(
						REFERENCE_CATEGORY_PRODUCTS,
						1,
						filter -> false,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_COUNTRY, "US")
					)
					.upsertVia(session);

				// now create category with duplicated reflected reference to product
				final EntityReference categoryRef2 = session
					.createNewEntity(Entities.CATEGORY, 2)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TVs")
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Fernseher")
					.setReference(
						REFERENCE_CATEGORY_PRODUCTS,
						1,
						filter -> false,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_COUNTRY, "DE")
					)
					.setReference(
						REFERENCE_CATEGORY_PRODUCTS,
						1,
						filter -> false,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_COUNTRY, "US")
					)
					.upsertVia(session);

				assertNotNull(brand);
				assertNotNull(categoryRef1);
				assertNotNull(categoryRef2);

				assertNotNull(product);
				assertStoredProductAndReflectedReferencesContents(session);
			}
		);
	}

	@DisplayName("All duplicated references can be gradually removed from the product")
	@UseDataSet(value = DUPLICATE_REFERENCES_SCHEMA_ONLY, destroyAfterTest = true)
	@Test
	void shouldRemoveDuplicateReferences(Evita evita) {
		shouldIndexProductWithReferences(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session
					.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new ContextMissingException("Product with pk 1 is missing!"));

				assertEquals(2, countDuplicates(product, REFERENCE_CATEGORIES));

				// remove first DE reference to category 1
				product.openForWrite()
				       .removeReferences(
					       REFERENCE_CATEGORIES, ref -> ref.getReferencedPrimaryKey() == 1 && ref.getAttribute(
						       ATTRIBUTE_COUNTRY).equals("DE")
				       )
				       .upsertVia(session);

				final SealedEntity updatedProduct = session
					.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new ContextMissingException("Product with pk 1 is missing!"));

				assertEquals(1, countDuplicates(updatedProduct, REFERENCE_CATEGORIES, 1));
				assertEquals(2, countDuplicates(updatedProduct, REFERENCE_CATEGORIES, 2));

				final Map<EntityCountry, Long> category1EntityCountries = collectCategoryCardinalities(session, 1);
				assertEquals(1, category1EntityCountries.size());
				assertEquals(1, category1EntityCountries.get(new EntityCountry(1, "US")));

				final Map<EntityCountry, Long> category2EntityCountries = collectCategoryCardinalities(session, 2);

				assertEquals(2, category2EntityCountries.size());
				assertEquals(1, category2EntityCountries.get(new EntityCountry(1, "DE")));
				assertEquals(1, category2EntityCountries.get(new EntityCountry(1, "US")));

				// remove secondary DE reference to category 1
				updatedProduct.openForWrite()
				              .removeReferences(
					              REFERENCE_CATEGORIES, ref -> ref.getAttribute(ATTRIBUTE_COUNTRY).equals("DE")
				              )
				              .upsertVia(session);

				final SealedEntity updatedProductWithoutDE = session
					.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new ContextMissingException("Product with pk 1 is missing!"));

				assertEquals(1, countDuplicates(updatedProductWithoutDE, REFERENCE_CATEGORIES, 1));
				assertEquals(1, countDuplicates(updatedProductWithoutDE, REFERENCE_CATEGORIES, 2));

				final Map<EntityCountry, Long> category1EntityCountriesWithoutDE = collectCategoryCardinalities(
					session, 1);
				assertEquals(1, category1EntityCountriesWithoutDE.size());
				assertEquals(1, category1EntityCountriesWithoutDE.get(new EntityCountry(1, "US")));

				final Map<EntityCountry, Long> category2EntityCountriesWithoutDE = collectCategoryCardinalities(
					session, 2);

				assertEquals(1, category2EntityCountriesWithoutDE.size());
				assertEquals(1, category2EntityCountriesWithoutDE.get(new EntityCountry(1, "US")));

				// remove all US references at once
				updatedProductWithoutDE.openForWrite()
				                       .removeReferences(REFERENCE_CATEGORIES, ref -> true)
				                       .upsertVia(session);

				final SealedEntity updatedProductWithoutCategories = session
					.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new ContextMissingException("Product with pk 1 is missing!"));

				assertEquals(0, countDuplicates(updatedProductWithoutCategories, REFERENCE_CATEGORIES, 1));
				assertEquals(0, countDuplicates(updatedProductWithoutCategories, REFERENCE_CATEGORIES, 2));

				final Map<EntityCountry, Long> category1EntityCountriesWithoutRefs = collectCategoryCardinalities(
					session, 1);
				assertEquals(0, category1EntityCountriesWithoutRefs.size());

				final Map<EntityCountry, Long> category2EntityCountriesWithoutRefs = collectCategoryCardinalities(
					session, 2);

				assertEquals(0, category2EntityCountriesWithoutRefs.size());
			}
		);
	}

	@DisplayName("All duplicated references can be gradually removed from the product via reflected references")
	@UseDataSet(value = DUPLICATE_REFERENCES_SCHEMA_ONLY, destroyAfterTest = true)
	@Test
	void shouldRemoveDuplicateReferencesViaReflectedReferences(Evita evita) {
		shouldIndexProductWithReferences(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session
					.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new ContextMissingException("Product with pk 1 is missing!"));

				assertEquals(2, countDuplicates(product, REFERENCE_CATEGORIES));

				// remove reflected reference DE in category 1
				session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
				       .orElseThrow(() -> new ContextMissingException("Category with pk 1 is missing!"))
				       .openForWrite()
				       .removeReferences(
					       REFERENCE_CATEGORY_PRODUCTS, ref -> ref.getAttribute(ATTRIBUTE_COUNTRY).equals("DE"))
				       .upsertVia(session);

				final SealedEntity updatedProduct = session
					.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new ContextMissingException("Product with pk 1 is missing!"));

				assertEquals(1, countDuplicates(updatedProduct, REFERENCE_CATEGORIES, 1));
				assertEquals(2, countDuplicates(updatedProduct, REFERENCE_CATEGORIES, 2));

				final Map<EntityCountry, Long> category1EntityCountries = collectCategoryCardinalities(session, 1);
				assertEquals(1, category1EntityCountries.size());
				assertEquals(1, category1EntityCountries.get(new EntityCountry(1, "US")));

				final Map<EntityCountry, Long> category2EntityCountries = collectCategoryCardinalities(session, 2);

				assertEquals(2, category2EntityCountries.size());
				assertEquals(1, category2EntityCountries.get(new EntityCountry(1, "DE")));
				assertEquals(1, category2EntityCountries.get(new EntityCountry(1, "US")));

				// remove reflected reference DE in category 2
				session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
				       .orElseThrow(() -> new ContextMissingException("Category with pk 2 is missing!"))
				       .openForWrite()
				       .removeReferences(
					       REFERENCE_CATEGORY_PRODUCTS, ref -> ref.getAttribute(ATTRIBUTE_COUNTRY).equals("DE"))
				       .upsertVia(session);

				final SealedEntity updatedProductWithoutDE = session
					.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new ContextMissingException("Product with pk 1 is missing!"));

				assertEquals(1, countDuplicates(updatedProductWithoutDE, REFERENCE_CATEGORIES, 1));
				assertEquals(1, countDuplicates(updatedProductWithoutDE, REFERENCE_CATEGORIES, 2));

				final Map<EntityCountry, Long> category1EntityCountriesWithoutDE = collectCategoryCardinalities(
					session, 1);
				assertEquals(1, category1EntityCountriesWithoutDE.size());
				assertEquals(1, category1EntityCountriesWithoutDE.get(new EntityCountry(1, "US")));

				final Map<EntityCountry, Long> category2EntityCountriesWithoutDE = collectCategoryCardinalities(
					session, 2);

				assertEquals(1, category2EntityCountriesWithoutDE.size());
				assertEquals(1, category2EntityCountriesWithoutDE.get(new EntityCountry(1, "US")));

				// remove both US references
				session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
				       .orElseThrow(() -> new ContextMissingException("Category with pk 1 is missing!"))
				       .openForWrite()
				       .removeReferences(REFERENCE_CATEGORY_PRODUCTS)
				       .upsertVia(session);
				session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
				       .orElseThrow(() -> new ContextMissingException("Category with pk 1 is missing!"))
				       .openForWrite()
				       .removeReferences(REFERENCE_CATEGORY_PRODUCTS)
				       .upsertVia(session);

				final SealedEntity updatedProductWithoutCategories = session
					.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new ContextMissingException("Product with pk 1 is missing!"));

				assertEquals(0, countDuplicates(updatedProductWithoutCategories, REFERENCE_CATEGORIES, 1));
				assertEquals(0, countDuplicates(updatedProductWithoutCategories, REFERENCE_CATEGORIES, 2));

				final Map<EntityCountry, Long> category1EntityCountriesWithoutRefs = collectCategoryCardinalities(
					session, 1);
				assertEquals(0, category1EntityCountriesWithoutRefs.size());

				final Map<EntityCountry, Long> category2EntityCountriesWithoutRefs = collectCategoryCardinalities(
					session, 2);

				assertEquals(0, category2EntityCountriesWithoutRefs.size());
			}
		);
	}

	@DisplayName("Product with duplicated references can be archived and unarchived")
	@UseDataSet(value = DUPLICATE_REFERENCES_SCHEMA_ONLY, destroyAfterTest = true)
	@Test
	void shouldArchiveAndUnarchiveEntityWithDuplicatedReferences(Evita evita) {
		shouldIndexProductWithReferences(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// archive the product
				session.archiveEntity(Entities.PRODUCT, 1);

				assertNull(
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent()).orElse(null)
				);

				final SealedEntity archivedProduct = session.getEntity(
					Entities.PRODUCT, 1, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()
				).orElse(null);

				// verify it still has duplicated references intact
				assertNotNull(archivedProduct);
				assertEquals(2, countDuplicates(archivedProduct, REFERENCE_CATEGORIES));

				// verify categories have no references to the archived product
				final Map<EntityCountry, Long> category1AfterArchivation = collectCategoryCardinalities(session, 1);
				assertEquals(0, category1AfterArchivation.size());

				final Map<EntityCountry, Long> category2AfterARchivation = collectCategoryCardinalities(session, 2);
				assertEquals(0, category2AfterARchivation.size());

				// restore the product
				session.restoreEntity(Entities.PRODUCT, 1);

				// verify the product and its duplicated references are back
				final SealedEntity restoredProduct = session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
				                                            .orElse(null);

				assertNotNull(restoredProduct);
				assertEquals(2, countDuplicates(restoredProduct, REFERENCE_CATEGORIES));

				final Map<EntityCountry, Long> category1AfterRestore = collectCategoryCardinalities(session, 1);
				assertEquals(2, category1AfterRestore.size());

				final Map<EntityCountry, Long> category2AfterRestore = collectCategoryCardinalities(session, 2);
				assertEquals(2, category2AfterRestore.size());
			}
		);
	}

	@DisplayName("Representative attributes of duplicated references can be changed")
	@UseDataSet(value = DUPLICATE_REFERENCES_SCHEMA_ONLY, destroyAfterTest = true)
	@Test
	void shouldChangeRepresentativeAttributes(Evita evita) {
		shouldIndexProductWithReferences(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session
					.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new ContextMissingException("Product with pk 1 is missing!"));

				// change country DE to FR in both duplicated references to category 1
				product.openForWrite()
				       .setReference(
					       REFERENCE_CATEGORIES,
					       1,
					       ref -> "DE".equals(ref.getAttribute(ATTRIBUTE_COUNTRY)),
					       refBuilder -> refBuilder.setAttribute(ATTRIBUTE_COUNTRY, "FR")
				       )
				       .upsertVia(session);

				final SealedEntity updatedProduct = session
					.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new ContextMissingException("Product with pk 1 is missing!"));

				assertEquals(2, countDuplicates(updatedProduct, REFERENCE_CATEGORIES, 1));
				assertEquals(2, countDuplicates(updatedProduct, REFERENCE_CATEGORIES, 2));

				final Map<EntityCountry, Long> category1EntityCountries = collectCategoryCardinalities(session, 1);
				assertEquals(2, category1EntityCountries.size());
				assertEquals(1, category1EntityCountries.get(new EntityCountry(1, "FR")));
				assertEquals(1, category1EntityCountries.get(new EntityCountry(1, "US")));

				final Map<EntityCountry, Long> category2EntityCountries = collectCategoryCardinalities(session, 2);

				assertEquals(2, category2EntityCountries.size());
				assertEquals(1, category2EntityCountries.get(new EntityCountry(1, "DE")));
				assertEquals(1, category2EntityCountries.get(new EntityCountry(1, "US")));

				assertNull(
					getReferencedEntityIndex(evita, Scope.LIVE, Entities.PRODUCT, REFERENCE_CATEGORIES, 1, "DE"));
				assertNotNull(
					getReferencedEntityIndex(evita, Scope.LIVE, Entities.PRODUCT, REFERENCE_CATEGORIES, 1, "FR"));

				// product can be found by new country in category 1
				final List<EntityReference> foundProducts = getProductByCategoryCountry(session, "FR");
				assertEquals(1, foundProducts.size());
				assertEquals(1, foundProducts.get(0).getPrimaryKey());

				// product cannot be found by old country in category 1
				final List<EntityReference> notFoundProducts = getProductByCategoryCountry(session, "DE");
				assertEquals(0, notFoundProducts.size());
			}
		);
	}

	@DisplayName("Representative attributes of duplicated references can be removed and restored again")
	@UseDataSet(value = DUPLICATE_REFERENCES_SCHEMA_ONLY, destroyAfterTest = true)
	@Test
	void shouldRemoveAndRestoreRepresentativeAttributes(Evita evita) {
		shouldIndexProductWithReferences(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
				       .withReferenceToEntity(
					       REFERENCE_CATEGORIES,
					       Entities.CATEGORY,
					       Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
					       whichIs -> whichIs.withAttribute(
						       ATTRIBUTE_COUNTRY,
						       String.class,
						       thatIs -> thatIs.filterable().representative().nullable()
					       )
				       )
				       .updateVia(session);

				final SealedEntity product = session
					.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new ContextMissingException("Product with pk 1 is missing!"));

				// remove country attribute in both duplicated references to category 1
				product.openForWrite()
				       .setReference(
					       REFERENCE_CATEGORIES,
					       1,
					       ref -> "DE".equals(ref.getAttribute(ATTRIBUTE_COUNTRY)),
					       refBuilder -> refBuilder.removeAttribute(ATTRIBUTE_COUNTRY)
				       )
				       .upsertVia(session);

				final SealedEntity updatedProduct = session
					.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new ContextMissingException("Product with pk 1 is missing!"));

				assertEquals(2, countDuplicates(updatedProduct, REFERENCE_CATEGORIES, 1));
				assertEquals(2, countDuplicates(updatedProduct, REFERENCE_CATEGORIES, 2));

				final Map<EntityCountry, Long> category1EntityCountries = collectCategoryCardinalities(session, 1);
				assertEquals(2, category1EntityCountries.size());
				assertEquals(1, category1EntityCountries.get(new EntityCountry(1, null)));
				assertEquals(1, category1EntityCountries.get(new EntityCountry(1, "US")));

				assertNull(
					getReferencedEntityIndex(evita, Scope.LIVE, Entities.PRODUCT, REFERENCE_CATEGORIES, 1, "DE"));
				assertNotNull(
					getReferencedEntityIndex(evita, Scope.LIVE, Entities.PRODUCT, REFERENCE_CATEGORIES, 1, "US"));
				assertNotNull(getReferencedEntityIndex(
					evita, Scope.LIVE, Entities.PRODUCT, REFERENCE_CATEGORIES, 1,
					new Serializable[]{null}
				));

				// product can be found by empty country in category 1
				final List<EntityReference> foundProducts = getProductByNullCategoryCountry(session);
				assertEquals(1, foundProducts.size());
				assertEquals(1, foundProducts.get(0).getPrimaryKey());
				final List<EntityReference> foundProductsUS = getProductByCategoryCountry(session, "US");
				assertEquals(1, foundProductsUS.size());
				assertEquals(1, foundProductsUS.get(0).getPrimaryKey());

				// product cannot be found by old country in category 1
				final List<EntityReference> notFoundProductsDE = getProductByCategoryCountry(session, "DE");
				assertEquals(0, notFoundProductsDE.size());

				// restore country attribute in both duplicated references to category 1
				updatedProduct
					.openForWrite()
					.setReference(
						REFERENCE_CATEGORIES,
						1,
						ref -> ref.getAttribute(ATTRIBUTE_COUNTRY) == null,
						refBuilder -> refBuilder.setAttribute(ATTRIBUTE_COUNTRY, "DE")
					)
					.upsertVia(session);

				final SealedEntity restoredProduct = session
					.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new ContextMissingException("Product with pk 1 is missing!"));

				assertEquals(2, countDuplicates(restoredProduct, REFERENCE_CATEGORIES, 1));
				assertEquals(2, countDuplicates(restoredProduct, REFERENCE_CATEGORIES, 2));

				final Map<EntityCountry, Long> category1EntityCountriesAfterRestore = collectCategoryCardinalities(
					session, 1);
				assertEquals(2, category1EntityCountriesAfterRestore.size());
				assertEquals(1, category1EntityCountriesAfterRestore.get(new EntityCountry(1, "DE")));
				assertEquals(1, category1EntityCountriesAfterRestore.get(new EntityCountry(1, "US")));

				final Map<EntityCountry, Long> category2EntityCountriesAfterRestore = collectCategoryCardinalities(
					session, 2);
				assertEquals(2, category2EntityCountriesAfterRestore.size());
				assertEquals(1, category2EntityCountriesAfterRestore.get(new EntityCountry(1, "DE")));
				assertEquals(1, category2EntityCountriesAfterRestore.get(new EntityCountry(1, "US")));

				assertNull(getReferencedEntityIndex(
					evita, Scope.LIVE, Entities.PRODUCT, REFERENCE_CATEGORIES, 1,
					new Serializable[]{null}
				));
				assertNotNull(
					getReferencedEntityIndex(evita, Scope.LIVE, Entities.PRODUCT, REFERENCE_CATEGORIES, 1, "DE"));
				assertNotNull(
					getReferencedEntityIndex(evita, Scope.LIVE, Entities.PRODUCT, REFERENCE_CATEGORIES, 1, "US"));

				// product can be found by restored country in category 1
				final List<EntityReference> foundProductsDE = getProductByCategoryCountry(session, "DE");
				assertEquals(1, foundProductsDE.size());
				assertEquals(1, foundProductsDE.get(0).getPrimaryKey());

				final List<EntityReference> foundProductsUSAfterRestore = getProductByCategoryCountry(session, "US");
				assertEquals(1, foundProductsUSAfterRestore.size());
				assertEquals(1, foundProductsUSAfterRestore.get(0).getPrimaryKey());

				// product cannot be found by empty country in category 1
				final List<EntityReference> notFoundProductsNull = getProductByNullCategoryCountry(session);
				assertEquals(0, notFoundProductsNull.size());
			}
		);
	}

	@Test
	void failToInsertDuplicatedReferenceSharingRepresentativeAssociatedValues() {
		fail("Not implemented yet");
	}

	@DisplayName("The product should filter entities by brand duplicate references")
	@UseDataSet(value = DUPLICATE_REFERENCES)
	@Test
	void shouldFilterProductByBrandWithDuplicateReferences(
		Evita evita, Map<EntityCountry, List<SealedEntity>> productsInBrand) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// verify that each product is found when filtering by brand
				productsInBrand.forEach(
					(entityCountry, products) -> {
						final List<EntityReference> foundProducts = session.queryListOfEntityReferences(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									referenceHaving(
										REFERENCE_BRAND,
										entityPrimaryKeyInSet(entityCountry.entityPrimaryKey()),
										attributeEquals(ATTRIBUTE_COUNTRY, entityCountry.country())
									)
								),
								orderBy(
									referenceProperty(
										REFERENCE_BRAND,
										attributeNatural(ATTRIBUTE_BRAND_ORDER)
									)
								),
								require(
									debug(
										DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS,
										DebugMode.VERIFY_POSSIBLE_CACHING_TREES
									)
								)
							)
						);
						assertEquals(
							products.size(), foundProducts.size(),
							"Expected to find " + products.size() + " products for brand " +
								entityCountry.entityPrimaryKey() + " in country " + entityCountry.country() +
								", but found " + foundProducts.size()
						);
						assertArrayEquals(
							products.stream()
							        .mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
							        .toArray(),
							foundProducts.stream()
							             .mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
							             .toArray()
						);
					}
				);
			}
		);
	}

	@DisplayName("The product should filter entities by brand duplicate references (prefetch)")
	@UseDataSet(value = DUPLICATE_REFERENCES)
	@Test
	void shouldFilterProductByBrandWithDuplicateReferencesByPrefetch(
		Evita evita,
		Map<EntityCountry, List<SealedEntity>> productsInBrand
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// verify that each product is found when filtering by brand
				productsInBrand.forEach(
					(entityCountry, products) -> {
						final int[] expectedProducts = products
							.stream()
							.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
							.toArray();
						final List<EntityReference> foundProducts = session.queryListOfEntityReferences(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									entityPrimaryKeyInSet(
										ArrayUtils.mergeArrays(
											// garbage pks to verify prefetch doesn't influence the result
											new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
											// expected
											expectedProducts
										)
									),
									referenceHaving(
										REFERENCE_BRAND,
										entityPrimaryKeyInSet(entityCountry.entityPrimaryKey()),
										attributeEquals(ATTRIBUTE_COUNTRY, entityCountry.country())
									)
								),
								orderBy(
									referenceProperty(
										REFERENCE_BRAND,
										attributeNatural(ATTRIBUTE_BRAND_ORDER)
									)
								),
								require(
									debug(DebugMode.PREFER_PREFETCHING)
								)
							)
						);
						assertEquals(
							products.size(), foundProducts.size(),
							"Expected to find " + products.size() + " products for brand " +
								entityCountry.entityPrimaryKey() + " in country " + entityCountry.country() +
								", but found " + foundProducts.size()
						);
						final int[] foundPks = foundProducts
							.stream()
							.mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
							.toArray();
						assertArrayEquals(
							expectedProducts,
							foundPks
						);
					}
				);
			}
		);
	}

	@DisplayName("The product should filter entities by category hierarchy duplicate references")
	@UseDataSet(value = DUPLICATE_REFERENCES)
	@Test
	void shouldFilterProductByCategoryWithDuplicateReferences(
		Evita evita,
		Map<EntityCountry, List<SealedEntity>> productsInCategory,
		Hierarchy categoryHierarchy
	) {
		final Set<String> countries = productsInCategory
			.keySet()
			.stream()
			.map(EntityCountry::country)
			.collect(Collectors.toSet());

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (String country : countries) {
					final List<EntityReference> foundProducts = session.queryListOfEntityReferences(
						query(
							collection(Entities.PRODUCT),
							/* CURRENTLY, WE DON'T support traversing hierarchy by reference attributes */
							/*filterBy(
								hierarchyWithinRoot(
									REFERENCE_CATEGORIES,
									having(
										attributeEquals(ATTRIBUTE_COUNTRY, country)
									)
								)
							),*/
							filterBy(
								referenceHaving(
									REFERENCE_CATEGORIES,
									attributeEquals(ATTRIBUTE_COUNTRY, country)
								),
								hierarchyWithinRoot(REFERENCE_CATEGORIES)
							),
							orderBy(
								referenceProperty(
									REFERENCE_CATEGORIES,
									attributeNatural(ATTRIBUTE_CATEGORY_ORDER)
								)
							),
							require(
								debug(
									DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						)
					);

					final LinkedHashSet<SealedEntity> products = new LinkedHashSet<>(256);
					for (HierarchyItem rootItem : categoryHierarchy.getRootItems()) {
						addProducts(products, productsInCategory, categoryHierarchy, country, rootItem);
					}

					assertEquals(
						products.size(),
						foundProducts.size(),
						"Expected to find " + products.size() + " products for country " + country +
							", but found " + foundProducts.size()
					);
					assertArrayEquals(
						products.stream()
						        .mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
						        .toArray(),
						foundProducts.stream()
						             .mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
						             .toArray()
					);
				}
			}
		);
	}

	@DisplayName("The product should filter entities by category hierarchy duplicate references (prefetch)")
	@UseDataSet(value = DUPLICATE_REFERENCES)
	@Test
	void shouldFilterProductByCategoryWithDuplicateReferencesWithPrefetch(
		Evita evita,
		Map<EntityCountry, List<SealedEntity>> productsInCategory,
		Hierarchy categoryHierarchy
	) {
		final Set<String> countries = productsInCategory
			.keySet()
			.stream()
			.map(EntityCountry::country)
			.collect(Collectors.toSet());

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (String country : countries) {
					final List<EntityReference> foundProducts = session.queryListOfEntityReferences(
						query(
							collection(Entities.PRODUCT),
							/* CURRENTLY, WE DON'T support traversing hierarchy by reference attributes */
							/*filterBy(
								hierarchyWithinRoot(
									REFERENCE_CATEGORIES,
									having(
										attributeEquals(ATTRIBUTE_COUNTRY, country)
									)
								)
							),*/
							filterBy(
								entityPrimaryKeyInSet(IntStream.rangeClosed(1, 50).toArray()),
								referenceHaving(
									REFERENCE_CATEGORIES,
									attributeEquals(ATTRIBUTE_COUNTRY, country)
								)
							),
							orderBy(
								referenceProperty(
									REFERENCE_CATEGORIES,
									attributeNatural(ATTRIBUTE_CATEGORY_ORDER)
								)
							),
							require(
								debug(DebugMode.PREFER_PREFETCHING)
							)
						)
					);

					final LinkedHashSet<SealedEntity> products = new LinkedHashSet<>(256);
					for (HierarchyItem rootItem : categoryHierarchy.getRootItems()) {
						addProducts(
							products, productsInCategory, categoryHierarchy, country, rootItem,
							product -> product.getPrimaryKeyOrThrowException() <= 50
						);
					}

					assertEquals(
						products.size(),
						foundProducts.size(),
						"Expected to find " + products.size() + " products for country " + country +
							", but found " + foundProducts.size()
					);
					assertArrayEquals(
						products.stream()
						        .mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
						        .toArray(),
						foundProducts.stream()
						             .mapToInt(EntityClassifier::getPrimaryKeyOrThrowException)
						             .toArray()
					);
				}
			}
		);
	}

	@DisplayName("The product should contain duplicate references")
	@UseDataSet(value = DUPLICATE_REFERENCES)
	@Test
	void shouldFetchAndBrowseDuplicateReferences(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> allProducts = session.queryListOfSealedEntities(
					query(
						collection(Entities.PRODUCT),
						require(
							page(1, 100),
							entityFetch(
								referenceContentWithAttributes(REFERENCE_CATEGORIES, entityFetchAll()),
								referenceContentWithAttributes(REFERENCE_BRAND, entityFetchAll())
							)
						)
					)
				);
				int maxDuplicateCategories = 0;
				int maxDuplicateBrands = 0;
				for (SealedEntity product : allProducts) {
					maxDuplicateCategories = Math.max(
						maxDuplicateCategories,
						countDuplicates(product, REFERENCE_CATEGORIES)
					);
					maxDuplicateBrands = Math.max(
						maxDuplicateBrands,
						countDuplicates(product, REFERENCE_BRAND)
					);
				}
				assertTrue(maxDuplicateCategories > 1);
				assertTrue(maxDuplicateBrands > 1);
			}
		);
	}

	@DisplayName("The product should contain filtered duplicate references")
	@UseDataSet(value = DUPLICATE_REFERENCES)
	@Test
	void shouldFetchFilteredDuplicateReferences(
		Evita evita,
		Map<EntityCountry, List<SealedEntity>> productsInBrand,
		Map<Integer, SealedEntity> products
	) {
		final String singleCountry = productsInBrand.keySet().iterator().next().country();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> fetchedProducts = session.queryListOfSealedEntities(
					query(
						collection(Entities.PRODUCT),
						require(
							page(1, 100),
							entityFetch(
								referenceContentWithAttributes(
									REFERENCE_BRAND,
									filterBy(attributeEquals(ATTRIBUTE_COUNTRY, singleCountry)),
									entityFetchAll()
								)
							)
						)
					)
				);

				int expectedTotalCount = Math.toIntExact(
					products
						.values()
						.stream()
						.flatMap(it -> it.getReferences(REFERENCE_BRAND).stream())
						.filter(ref -> singleCountry.equals(ref.getAttribute(ATTRIBUTE_COUNTRY)))
						.count()
				);

				int filteredReferences = 0;
				for (SealedEntity product : fetchedProducts) {
					assertThrows(
						ContextMissingException.class,
						() -> product.getReferences(REFERENCE_CATEGORIES).isEmpty()
					);
					filteredReferences += product.getReferences(REFERENCE_BRAND).size();
					for (ReferenceContract reference : product.getReferences(REFERENCE_BRAND)) {
						assertEquals(singleCountry, reference.getAttribute(ATTRIBUTE_COUNTRY));
					}
				}
				assertEquals(expectedTotalCount, filteredReferences);
			}
		);
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
