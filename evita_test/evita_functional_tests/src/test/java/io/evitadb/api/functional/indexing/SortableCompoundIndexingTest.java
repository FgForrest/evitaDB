/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.function.QuadriConsumer;
import io.evitadb.function.TriConsumer;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static io.evitadb.api.functional.indexing.IndexingTestSupport.*;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for sortable attribute compound indexing, verifying that compounds are correctly
 * registered, updated, and removed in both global and reduced (reference) entity indexes
 * for localized and non-localized attributes.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Sortable attribute compound indexing")
class SortableCompoundIndexingTest implements EvitaTestSupport, IndexingTestSupport {
	private static final String DIR_SORTABLE_COMPOUND_INDEXING_TEST = "sortableCompoundIndexingTest";
	private static final String DIR_SORTABLE_COMPOUND_INDEXING_TEST_EXPORT = "sortableCompoundIndexingTest_export";
	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_SORTABLE_COMPOUND_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_SORTABLE_COMPOUND_INDEXING_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_SORTABLE_COMPOUND_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_SORTABLE_COMPOUND_INDEXING_TEST_EXPORT);
	}

	/**
	 * Creates a product entity with a non-localized sortable attribute compound on code + ean,
	 * then verifies the compound is registered in the global index and updated when attributes change.
	 * This method is used as setup by several dependent tests.
	 */
	void registerNonLocalizedCompoundForEachCreatedEntity() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.nullable())
					.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.nullable())
					.withSortableAttributeCompound(
						attributeCodeEan,
						AttributeElement.attributeElement(
							ATTRIBUTE_CODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
						AttributeElement.attributeElement(ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 1));

				// retrieve the collection and verify index state
				final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();
				final SealedEntitySchema productSchema = productCollection.getSchema();
				final SortableAttributeCompoundSchemaContract codeEanCompoundSchema = productSchema
					.getSortableAttributeCompound(attributeCodeEan)
					.orElseThrow();

				// this function allows us to repeatedly verify index contents
				final Consumer<Serializable[]> verifyIndexContents = expected -> {
					final EntityIndex globalIndex = getGlobalIndex(productCollection);
					assertNotNull(globalIndex);

					final SortIndex sortIndex = globalIndex.getSortIndex(
						productSchema, null, codeEanCompoundSchema, null);
					if (ArrayUtils.isEmptyOrItsValuesNull(expected)) {
						assertNull(sortIndex);
					} else {
						assertNotNull(sortIndex);
						assertArrayEquals(new int[]{1}, sortIndex.getRecordsEqualTo(expected).getArray());
					}
				};

				verifyIndexContents.accept(new Serializable[]{null, null});

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_EAN, "123")
					.setAttribute(ATTRIBUTE_CODE, "ABC")
					.upsertVia(session);

				verifyIndexContents.accept(new Serializable[]{"ABC", "123"});
			}
		);
	}

	/**
	 * Creates a product entity with a localized sortable attribute compound on code + ean,
	 * then verifies the compound is registered per-locale in the global index.
	 * This method is used as setup by several dependent tests.
	 */
	void registerLocalizedCompoundForEachCreatedEntityLanguage() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.nullable())
					.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.localized().nullable())
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().nullable())
					.withSortableAttributeCompound(
						attributeCodeEan,
						AttributeElement.attributeElement(
							ATTRIBUTE_CODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
						AttributeElement.attributeElement(ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 1));

				// retrieve the collection and verify index state
				final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();
				final SealedEntitySchema productSchema = productCollection.getSchema();
				final SortableAttributeCompoundSchemaContract codeEanCompoundSchema = productSchema
					.getSortableAttributeCompound(attributeCodeEan)
					.orElseThrow();

				final EntityIndex globalIndex = getGlobalIndex(productCollection);
				assertNotNull(globalIndex);

				assertNull(globalIndex.getSortIndex(productSchema, null, codeEanCompoundSchema, null));
				assertNull(globalIndex.getSortIndex(productSchema, null, codeEanCompoundSchema, Locale.ENGLISH));

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "The product")
					.setAttribute(ATTRIBUTE_CODE, "ABC")
					.upsertVia(session);

				final EntityIndex updatedGlobalIndex = getGlobalIndex(productCollection);
				assertNotNull(updatedGlobalIndex);

				final SortIndex englishSortIndex = updatedGlobalIndex.getSortIndex(
					productSchema, null, codeEanCompoundSchema, Locale.ENGLISH);
				assertNotNull(englishSortIndex);

				assertArrayEquals(
					new int[]{1}, englishSortIndex.getRecordsEqualTo(new Serializable[]{"ABC", null}).getArray());

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_EAN, Locale.CANADA, "123")
					.setAttribute(ATTRIBUTE_CODE, "ABC")
					.upsertVia(session);

				final EntityIndex updatedGlobalIndexAgain = getGlobalIndex(productCollection);
				assertNotNull(updatedGlobalIndexAgain);

				final SortIndex englishSortIndexAgain = updatedGlobalIndexAgain.getSortIndex(
					productSchema, null, codeEanCompoundSchema, Locale.ENGLISH);
				assertNotNull(englishSortIndexAgain);

				assertArrayEquals(
					new int[]{1},
					englishSortIndexAgain.getRecordsEqualTo(new Serializable[]{"ABC", null}).getArray()
				);

				final SortIndex canadianSortIndex = updatedGlobalIndexAgain.getSortIndex(
					productSchema, null, codeEanCompoundSchema, Locale.CANADA);
				assertNotNull(canadianSortIndex);

				assertArrayEquals(
					new int[]{1},
					canadianSortIndex.getRecordsEqualTo(new Serializable[]{"ABC", "123"}).getArray()
				);

				final SealedEntity finalEntity = session.getEntity(
						Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll())
					.orElseThrow();

				final Set<Locale> finalAttributeLocales = finalEntity.getAttributeLocales();
				assertTrue(finalAttributeLocales.contains(Locale.ENGLISH));
				assertTrue(finalAttributeLocales.contains(Locale.CANADA));
			}
		);
	}

	/**
	 * Creates entity compounds in reduced indexes (referenced entity indexes) by first
	 * registering non-localized compounds, then adding references to categories and brands.
	 * This method is used as setup by the removal test.
	 */
	void registerEntityCompoundsInReducedIndexesOnTheirCreation() {
		registerNonLocalizedCompoundForEachCreatedEntity();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {

				session
					.defineEntitySchema(Entities.CATEGORY)
					.withHierarchy()
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 10));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 11));

				session
					.defineEntitySchema(Entities.BRAND)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 20));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 21));

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					.withReferenceToEntity(
						Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);

				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);
				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll())
					.orElseThrow()
					.openForWrite()
					.setReference(Entities.CATEGORY, 10)
					.setReference(Entities.CATEGORY, 11)
					.setReference(Entities.BRAND, 20)
					.setReference(Entities.BRAND, 21)
					.upsertVia(session);

				// retrieve the collection and verify index state
				final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();
				final SealedEntitySchema productSchema = productCollection.getSchema();
				final SortableAttributeCompoundSchemaContract codeEanCompoundSchema = productSchema
					.getSortableAttributeCompound(attributeCodeEan)
					.orElseThrow();

				final Consumer<EntityIndex> verifyIndexContents = entityIndex -> {
					assertNotNull(entityIndex);

					final SortIndex sortIndex = entityIndex.getSortIndex(
						productSchema, null, codeEanCompoundSchema, null);
					assertNotNull(sortIndex);

					assertArrayEquals(
						new int[]{1},
						sortIndex.getRecordsEqualTo(new Serializable[]{"ABC", "123"}).getArray()
					);
				};

				verifyIndexContents.accept(
					getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10));
				verifyIndexContents.accept(
					getReferencedEntityIndex(productCollection, Entities.CATEGORY, 11));
				verifyIndexContents.accept(
					getReferencedEntityIndex(productCollection, Entities.BRAND, 20));
				verifyIndexContents.accept(
					getReferencedEntityIndex(productCollection, Entities.BRAND, 21));
			}
		);
	}

	/**
	 * Creates reference compounds for non-localized attributes on category and brand references.
	 * This method is used as setup by the removal test.
	 */
	void registerNonLocalizedReferenceCompoundForEachCreatedEntity() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);

				session
					.defineEntitySchema(Entities.CATEGORY)
					.withHierarchy()
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 10));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 11));

				session
					.defineEntitySchema(Entities.BRAND)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 20));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 21));

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
							.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.nullable())
							.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.nullable())
							.withSortableAttributeCompound(
								attributeCodeEan,
								AttributeElement.attributeElement(
									ATTRIBUTE_CODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
								AttributeElement.attributeElement(
									ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.withReferenceToEntity(
						Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
							.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.nullable())
							.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.nullable())
							.withSortableAttributeCompound(
								attributeCodeEan,
								AttributeElement.attributeElement(
									ATTRIBUTE_CODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
								AttributeElement.attributeElement(
									ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(Entities.CATEGORY, 10)
						.setReference(Entities.BRAND, 20)
				);

				// retrieve the collection and verify index state
				final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				// this function allows us to repeatedly verify index contents
				final TriConsumer<EntityIndex, String, Serializable[]> verifyIndexContents =
					(entityIndex, referenceName, expected) -> {
						assertNotNull(entityIndex);
						final SealedEntitySchema productSchema = productCollection.getSchema();
						final ReferenceSchemaContract referenceSchema = productSchema
							.getReferenceOrThrowException(referenceName);
						final SortableAttributeCompoundSchemaContract compoundSchema = referenceSchema
							.getSortableAttributeCompound(attributeCodeEan)
							.orElseThrow();

						final SortIndex sortIndex = entityIndex.getSortIndex(
							productSchema, referenceSchema, compoundSchema, null);
						if (ArrayUtils.isEmptyOrItsValuesNull(expected)) {
							assertNull(sortIndex);
						} else {
							assertNotNull(sortIndex);
							assertArrayEquals(
								new int[]{1}, sortIndex.getRecordsEqualTo(expected).getArray());
						}
					};

				verifyIndexContents.accept(
					getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10),
					Entities.CATEGORY,
					new Serializable[]{null, null}
				);
				verifyIndexContents.accept(
					getReferencedEntityIndex(productCollection, Entities.BRAND, 20),
					Entities.BRAND,
					new Serializable[]{null, null}
				);

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll())
					.orElseThrow()
					.openForWrite()
					.setReference(
						Entities.CATEGORY, 10,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_CODE, "ABC")
							.setAttribute(ATTRIBUTE_EAN, "123")
					)
					.setReference(
						Entities.BRAND, 20,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_CODE, "ABC")
							.setAttribute(ATTRIBUTE_EAN, "123")
					)
					.upsertVia(session);

				verifyIndexContents.accept(
					getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10),
					Entities.CATEGORY,
					new Serializable[]{"ABC", "123"}
				);
				verifyIndexContents.accept(
					getReferencedEntityIndex(productCollection, Entities.BRAND, 20),
					Entities.BRAND,
					new Serializable[]{"ABC", "123"}
				);
			}
		);
	}

	/**
	 * Creates localized reference compounds on category and brand references.
	 * This method is used as setup by dependent localized reference tests.
	 */
	void registerLocalizedReferenceCompoundForEachCreatedEntityLanguage() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);

				session
					.defineEntitySchema(Entities.CATEGORY)
					.withHierarchy()
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 10));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 11));

				session
					.defineEntitySchema(Entities.BRAND)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 20));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 21));

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
							.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.nullable())
							.withAttribute(ATTRIBUTE_EAN, String.class,
								thatIs -> thatIs.localized().nullable())
							.withAttribute(ATTRIBUTE_NAME, String.class,
								thatIs -> thatIs.localized().nullable())
							.withSortableAttributeCompound(
								attributeCodeEan,
								AttributeElement.attributeElement(
									ATTRIBUTE_CODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
								AttributeElement.attributeElement(
									ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.withReferenceToEntity(
						Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
							.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.nullable())
							.withAttribute(ATTRIBUTE_EAN, String.class,
								thatIs -> thatIs.localized().nullable())
							.withAttribute(ATTRIBUTE_NAME, String.class,
								thatIs -> thatIs.localized().nullable())
							.withSortableAttributeCompound(
								attributeCodeEan,
								AttributeElement.attributeElement(
									ATTRIBUTE_CODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
								AttributeElement.attributeElement(
									ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(Entities.CATEGORY, 10)
						.setReference(Entities.BRAND, 20)
				);

				// retrieve the collection and verify index state
				final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();
				final SealedEntitySchema productSchema = productCollection.getSchema();
				final ReferenceSchemaContract categoryReferenceSchema = productSchema
					.getReferenceOrThrowException(Entities.CATEGORY);
				final SortableAttributeCompoundSchemaContract categoryCodeEanCompoundSchema =
					categoryReferenceSchema
						.getSortableAttributeCompound(attributeCodeEan)
						.orElseThrow();
				final ReferenceSchemaContract brandReferenceSchema = productSchema
					.getReferenceOrThrowException(Entities.BRAND);
				final SortableAttributeCompoundSchemaContract brandCodeEanCompoundSchema = brandReferenceSchema
					.getSortableAttributeCompound(attributeCodeEan)
					.orElseThrow();

				assertNull(
					getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10)
						.getSortIndex(
							productSchema, categoryReferenceSchema,
							categoryCodeEanCompoundSchema, null
						)
				);
				assertNull(
					getReferencedEntityIndex(productCollection, Entities.BRAND, 20)
						.getSortIndex(
							productSchema, brandReferenceSchema,
							brandCodeEanCompoundSchema, Locale.ENGLISH
						)
				);

				// this function allows us to repeatedly verify index contents
				final QuadriConsumer<EntityIndex, String, Locale, Serializable[]> verifyIndexContents =
					(entityIndex, referenceName, locale, expected) -> {
						assertNotNull(entityIndex);

						final SortIndex sortIndex = entityIndex.getSortIndex(
							productSchema,
							productSchema.getReferenceOrThrowException(referenceName),
							categoryCodeEanCompoundSchema,
							locale
						);
						if (ArrayUtils.isEmptyOrItsValuesNull(expected)) {
							assertNull(sortIndex);
						} else {
							assertNotNull(sortIndex);
							assertArrayEquals(
								new int[]{1}, sortIndex.getRecordsEqualTo(expected).getArray());
						}
					};

				session.getEntity(
						Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setReference(
						Entities.CATEGORY, 10, whichIs -> whichIs
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "The product")
					)
					.setReference(
						Entities.BRAND, 20, whichIs -> whichIs
							.setAttribute(ATTRIBUTE_NAME, Locale.CANADA, "The product")
					)
					.upsertVia(session);

				verifyIndexContents.accept(
					getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10),
					Entities.CATEGORY,
					Locale.ENGLISH,
					new Serializable[]{null, null}
				);
				verifyIndexContents.accept(
					getReferencedEntityIndex(productCollection, Entities.BRAND, 20),
					Entities.BRAND,
					Locale.CANADA,
					new Serializable[]{null, null}
				);

				session.getEntity(
						Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setReference(
						Entities.CATEGORY, 10, whichIs -> whichIs
							.setAttribute(ATTRIBUTE_CODE, "The product")
							.setAttribute(ATTRIBUTE_EAN, Locale.ENGLISH, "123")
					)
					.setReference(
						Entities.BRAND, 20, whichIs -> whichIs
							.setAttribute(ATTRIBUTE_CODE, "The CA product")
							.setAttribute(ATTRIBUTE_EAN, Locale.CANADA, "456")
					)
					.upsertVia(session);

				verifyIndexContents.accept(
					getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10),
					Entities.CATEGORY,
					Locale.ENGLISH,
					new Serializable[]{"The product", "123"}
				);
				verifyIndexContents.accept(
					getReferencedEntityIndex(productCollection, Entities.BRAND, 20),
					Entities.BRAND,
					Locale.CANADA,
					new Serializable[]{"The CA product", "456"}
				);
			}
		);
	}

	/**
	 * Returns the evita configuration for this test class.
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIR_SORTABLE_COMPOUND_INDEXING_TEST))
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(getTestDirectory().resolve(DIR_SORTABLE_COMPOUND_INDEXING_TEST_EXPORT))
					.build()
			)
			.build();
	}

	@Nested
	@DisplayName("Non-localized entity compounds")
	class NonLocalizedEntityCompoundsTest {

		@Test
		@DisplayName("Should register compound for each created entity")
		void shouldRegisterNonLocalizedCompoundForEachCreatedEntity() {
			registerNonLocalizedCompoundForEachCreatedEntity();
		}

		@Test
		@DisplayName("Should update compounds on attribute change")
		void shouldUpdateNonLocalizedEntityCompoundsOnChange() {
			registerNonLocalizedCompoundForEachCreatedEntity();

			SortableCompoundIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final String attributeCodeEan =
						ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);
					session.getEntity(Entities.PRODUCT, 1, attributeContentAll())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_EAN, "578")
						.setAttribute(ATTRIBUTE_CODE, "Whatever")
						.upsertVia(session);

					// retrieve the collection and verify index state
					final CatalogContract catalog =
						SortableCompoundIndexingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
					final SealedEntitySchema productSchema = productCollection.getSchema();
					final SortableAttributeCompoundSchemaContract codeEanCompoundSchema = productSchema
						.getSortableAttributeCompound(attributeCodeEan)
						.orElseThrow();

					final EntityIndex globalIndex = getGlobalIndex(productCollection);
					assertNotNull(globalIndex);

					final SortIndex sortIndex = globalIndex.getSortIndex(
						productSchema, null, codeEanCompoundSchema, null);
					assertNotNull(sortIndex);

					assertTrue(
						sortIndex.getRecordsEqualTo(new Serializable[]{"ABC", "123"}).isEmpty());
					assertArrayEquals(
						new int[]{1},
						sortIndex.getRecordsEqualTo(new Serializable[]{"Whatever", "578"}).getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("Should drop compound for each removed entity")
		void shouldDropNonLocalizedCompoundForEachRemovedEntity() {
			registerNonLocalizedCompoundForEachCreatedEntity();

			SortableCompoundIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final String attributeCodeEan =
						ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);

					session.deleteEntity(Entities.PRODUCT, 1);

					// retrieve the collection and verify index state
					final CatalogContract catalog =
						SortableCompoundIndexingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
					final SealedEntitySchema productSchema = productCollection.getSchema();
					final SortableAttributeCompoundSchemaContract codeEanCompoundSchema = productSchema
						.getSortableAttributeCompound(attributeCodeEan)
						.orElseThrow();

					final EntityIndex globalIndex = getGlobalIndex(productCollection);
					assertNotNull(globalIndex);

					final SortIndex sortIndex = globalIndex.getSortIndex(
						productSchema, null, codeEanCompoundSchema, null);
					assertNull(sortIndex);
				}
			);
		}
	}

	@Nested
	@DisplayName("Localized entity compounds")
	class LocalizedEntityCompoundsTest {

		@Test
		@DisplayName("Should register compound for each created entity language")
		void shouldRegisterLocalizedCompoundForEachCreatedEntityLanguage() {
			registerLocalizedCompoundForEachCreatedEntityLanguage();
		}

		@Test
		@DisplayName("Should update compounds on attribute change")
		void shouldUpdateLocalizedEntityCompoundsOnChange() {
			registerLocalizedCompoundForEachCreatedEntityLanguage();

			SortableCompoundIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final String attributeCodeEan =
						ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);
					session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_EAN, Locale.CANADA, "578")
						.setAttribute(ATTRIBUTE_CODE, "Whatever")
						.upsertVia(session);

					// retrieve the collection and verify index state
					final CatalogContract catalog =
						SortableCompoundIndexingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
					final SealedEntitySchema productSchema = productCollection.getSchema();
					final SortableAttributeCompoundSchemaContract codeEanCompoundSchema = productSchema
						.getSortableAttributeCompound(attributeCodeEan)
						.orElseThrow();

					final EntityIndex globalIndex = getGlobalIndex(productCollection);
					assertNotNull(globalIndex);

					final SortIndex sortIndex = globalIndex.getSortIndex(
						productSchema, null, codeEanCompoundSchema, Locale.CANADA);
					assertNotNull(sortIndex);

					assertTrue(
						sortIndex.getRecordsEqualTo(new Serializable[]{"ABC", "123"}).isEmpty());
					assertArrayEquals(
						new int[]{1},
						sortIndex.getRecordsEqualTo(
							new Serializable[]{"Whatever", "578"}).getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("Should drop compound for each dropped entity language")
		void shouldDropLocalizedCompoundForEachDroppedEntityLanguage() {
			registerLocalizedCompoundForEachCreatedEntityLanguage();

			SortableCompoundIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final String attributeCodeEan =
						ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);

					session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll())
						.orElseThrow()
						.openForWrite()
						.removeAttribute(ATTRIBUTE_NAME, Locale.ENGLISH)
						.setAttribute(ATTRIBUTE_CODE, "ABC")
						.upsertVia(session);

					// retrieve the collection and verify index state
					final CatalogContract catalog =
						SortableCompoundIndexingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
					final SealedEntitySchema productSchema = productCollection.getSchema();
					final SortableAttributeCompoundSchemaContract codeEanCompoundSchema = productSchema
						.getSortableAttributeCompound(attributeCodeEan)
						.orElseThrow();

					final EntityIndex globalIndex = getGlobalIndex(productCollection);
					assertNotNull(globalIndex);
					assertNull(globalIndex.getSortIndex(
						productSchema, null, codeEanCompoundSchema, null));

					final SortIndex englishSortIndex = globalIndex.getSortIndex(
						productSchema, null, codeEanCompoundSchema, Locale.ENGLISH);
					assertNull(englishSortIndex);

					final SortIndex canadianSortIndex = globalIndex.getSortIndex(
						productSchema, null, codeEanCompoundSchema, Locale.CANADA);
					assertNotNull(canadianSortIndex);
					assertArrayEquals(
						new int[]{1},
						canadianSortIndex.getRecordsEqualTo(
							new Serializable[]{"ABC", "123"}).getArray()
					);

					session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll())
						.orElseThrow()
						.openForWrite()
						.removeAttribute(ATTRIBUTE_EAN, Locale.CANADA)
						.setAttribute(ATTRIBUTE_CODE, "ABC")
						.upsertVia(session);

					final EntityIndex updatedGlobalIndex = getGlobalIndex(productCollection);
					assertNotNull(updatedGlobalIndex);
					assertNull(updatedGlobalIndex.getSortIndex(
						productSchema, null, codeEanCompoundSchema, null));
					assertNull(updatedGlobalIndex.getSortIndex(
						productSchema, null, codeEanCompoundSchema, Locale.ENGLISH));
					assertNull(updatedGlobalIndex.getSortIndex(
						productSchema, null, codeEanCompoundSchema, Locale.CANADA));
				}
			);
		}
	}

	@Nested
	@DisplayName("Compound propagation to reduced indexes")
	class CompoundPropagationToReducedIndexesTest {

		@Test
		@DisplayName("Should register entity compounds in reduced indexes on their creation")
		void shouldRegisterEntityCompoundsInReducedIndexesOnTheirCreation() {
			registerEntityCompoundsInReducedIndexesOnTheirCreation();
		}

		@Test
		@DisplayName("Should drop entity compounds in reduced indexes on their removal")
		void shouldDropEntityCompoundsInReducedIndexesOnTheirRemoval() {
			registerEntityCompoundsInReducedIndexesOnTheirCreation();

			SortableCompoundIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final String attributeCodeEan =
						ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);
					session.getEntity(
							Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 10)
						.removeReference(Entities.BRAND, 20)
						.upsertVia(session);

					// retrieve the collection and verify index state
					final CatalogContract catalog =
						SortableCompoundIndexingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
					final SealedEntitySchema productSchema = productCollection.getSchema();
					final SortableAttributeCompoundSchemaContract codeEanCompoundSchema = productSchema
						.getSortableAttributeCompound(attributeCodeEan)
						.orElseThrow();

					final Consumer<EntityIndex> verifyIndexContentsContains = entityIndex -> {
						assertNotNull(entityIndex);

						final SortIndex sortIndex = entityIndex.getSortIndex(
							productSchema, null, codeEanCompoundSchema, null);
						assertNotNull(sortIndex);

						assertArrayEquals(
							new int[]{1},
							sortIndex.getRecordsEqualTo(
								new Serializable[]{"ABC", "123"}).getArray()
						);
					};

					assertNull(getReferencedEntityIndex(
						productCollection, Entities.CATEGORY, 10));
					verifyIndexContentsContains.accept(getReferencedEntityIndex(
						productCollection, Entities.CATEGORY, 11));
					assertNull(getReferencedEntityIndex(
						productCollection, Entities.BRAND, 20));
					verifyIndexContentsContains.accept(getReferencedEntityIndex(
						productCollection, Entities.BRAND, 21));
				}
			);
		}
	}

	@Nested
	@DisplayName("Non-localized reference compounds")
	class NonLocalizedReferenceCompoundsTest {

		@Test
		@DisplayName("Should register compound for each created entity")
		void shouldRegisterNonLocalizedReferenceCompoundForEachCreatedEntity() {
			registerNonLocalizedReferenceCompoundForEachCreatedEntity();
		}

		@Test
		@DisplayName("Should drop compound for each removed entity")
		void shouldDropNonLocalizedReferenceCompoundForEachRemovedEntity() {
			registerNonLocalizedReferenceCompoundForEachCreatedEntity();

			SortableCompoundIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(Entities.PRODUCT, 1);

					// retrieve the collection and verify index state
					final CatalogContract catalog =
						SortableCompoundIndexingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					final EntityIndex globalIndex = getGlobalIndex(productCollection);
					assertNotNull(globalIndex);

					assertNull(getReferencedEntityIndex(
						productCollection, Entities.CATEGORY, 10));
					assertNull(getReferencedEntityIndex(
						productCollection, Entities.CATEGORY, 11));
					assertNull(getReferencedEntityIndex(
						productCollection, Entities.BRAND, 20));
					assertNull(getReferencedEntityIndex(
						productCollection, Entities.BRAND, 21));
				}
			);
		}
	}

	@Nested
	@DisplayName("Localized reference compounds")
	class LocalizedReferenceCompoundsTest {

		@Test
		@DisplayName("Should register compound for each created entity language")
		void shouldRegisterLocalizedReferenceCompoundForEachCreatedEntityLanguage() {
			registerLocalizedReferenceCompoundForEachCreatedEntityLanguage();
		}

		@Test
		@DisplayName("Should update compounds on attribute change")
		void shouldUpdateLocalizedEntityReferenceCompoundsOnChange() {
			registerLocalizedReferenceCompoundForEachCreatedEntityLanguage();

			SortableCompoundIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final String attributeCodeEan =
						ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);

					// this function allows us to repeatedly verify index contents
					final QuadriConsumer<EntityIndex, String, Locale, Serializable[]> verifyIndexContents =
						(entityIndex, referenceName, locale, expected) -> {
							assertNotNull(entityIndex);

							final SealedEntitySchema productSchema =
								session.getEntitySchemaOrThrowException(Entities.PRODUCT);
							final ReferenceSchemaContract referenceSchema = productSchema
								.getReferenceOrThrowException(referenceName);
							final SortableAttributeCompoundSchemaContract compoundSchema =
								referenceSchema
									.getSortableAttributeCompound(attributeCodeEan)
									.orElseThrow();

							final SortIndex sortIndex = entityIndex.getSortIndex(
								productSchema, referenceSchema, compoundSchema, locale);
							assertNotNull(sortIndex);

							assertArrayEquals(
								new int[]{1},
								sortIndex.getRecordsEqualTo(expected).getArray()
							);
						};

					session.getEntity(
							Entities.PRODUCT, 1,
							attributeContentAll(), referenceContentAll(), dataInLocalesAll())
						.orElseThrow()
						.openForWrite()
						.setReference(
							Entities.CATEGORY, 10,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CODE, "Whatever")
								.setAttribute(ATTRIBUTE_EAN, Locale.ENGLISH, "567")
						)
						.setReference(
							Entities.BRAND, 20,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CODE, "Else")
								.setAttribute(ATTRIBUTE_EAN, Locale.CANADA, "624")
						)
						.upsertVia(session);

					// retrieve the collection and verify index state
					final CatalogContract catalog =
						SortableCompoundIndexingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					verifyIndexContents.accept(
						getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10),
						Entities.CATEGORY,
						Locale.ENGLISH,
						new Serializable[]{"Whatever", "567"}
					);
					verifyIndexContents.accept(
						getReferencedEntityIndex(productCollection, Entities.BRAND, 20),
						Entities.BRAND,
						Locale.CANADA,
						new Serializable[]{"Else", "624"}
					);
				}
			);
		}

		@Test
		@DisplayName("Should drop compound for each dropped entity language")
		void shouldDropLocalizedReferenceCompoundForEachDroppedEntityLanguage() {
			registerLocalizedReferenceCompoundForEachCreatedEntityLanguage();

			SortableCompoundIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final String attributeCodeEan =
						ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);

					session.getEntity(
							Entities.PRODUCT, 1,
							attributeContentAll(), referenceContentAll(), dataInLocalesAll())
						.orElseThrow()
						.openForWrite()
						.setReference(
							Entities.CATEGORY, 10, whichIs -> whichIs
								.removeAttribute(ATTRIBUTE_NAME, Locale.ENGLISH)
								.removeAttribute(ATTRIBUTE_EAN, Locale.ENGLISH)
						)
						.upsertVia(session);

					// retrieve the collection and verify index state
					final CatalogContract catalog =
						SortableCompoundIndexingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
					final SealedEntitySchema productSchema = productCollection.getSchema();
					final ReferenceSchemaContract categoryReferenceSchema = productSchema
						.getReferenceOrThrowException(Entities.CATEGORY);
					final SortableAttributeCompoundSchemaContract categoryCodeEanCompoundSchema =
						categoryReferenceSchema
							.getSortableAttributeCompound(attributeCodeEan)
							.orElseThrow();
					final ReferenceSchemaContract brandReferenceSchema = productSchema
						.getReferenceOrThrowException(Entities.BRAND);
					final SortableAttributeCompoundSchemaContract brandCodeEanCompoundSchema =
						brandReferenceSchema
							.getSortableAttributeCompound(attributeCodeEan)
							.orElseThrow();

					assertNull(
						getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10)
							.getSortIndex(
								productSchema, categoryReferenceSchema,
								categoryCodeEanCompoundSchema, Locale.ENGLISH
							)
					);
					assertNotNull(
						getReferencedEntityIndex(productCollection, Entities.BRAND, 20)
							.getSortIndex(
								productSchema, brandReferenceSchema,
								brandCodeEanCompoundSchema, Locale.CANADA
							)
					);

					session.getEntity(
							Entities.PRODUCT, 1,
							attributeContentAll(), referenceContentAll(), dataInLocalesAll())
						.orElseThrow()
						.openForWrite()
						.setReference(
							Entities.BRAND, 20, whichIs -> whichIs
								.removeAttribute(ATTRIBUTE_NAME, Locale.CANADA)
								.removeAttribute(ATTRIBUTE_EAN, Locale.CANADA)
						)
						.upsertVia(session);

					assertNull(
						getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10)
							.getSortIndex(
								productSchema, categoryReferenceSchema,
								categoryCodeEanCompoundSchema, Locale.ENGLISH
							)
					);
					assertNull(
						getReferencedEntityIndex(productCollection, Entities.BRAND, 20)
							.getSortIndex(
								productSchema, brandReferenceSchema,
								brandCodeEanCompoundSchema, Locale.CANADA
							)
					);
				}
			);
		}
	}
}
