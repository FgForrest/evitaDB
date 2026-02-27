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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.require;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for catalog-level and schema-level indexing operations in {@link Evita}, covering direct mutations,
 * catalog updates, prototyping mode, schema creation, and strict schema validation behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Catalog and schema indexing operations")
class CatalogAndSchemaIndexingTest implements EvitaTestSupport, IndexingTestSupport {
	private static final String DIR_CATALOG_AND_SCHEMA_INDEXING_TEST = "catalogAndSchemaIndexingTest";
	private static final String DIR_CATALOG_AND_SCHEMA_INDEXING_TEST_EXPORT = "catalogAndSchemaIndexingTest_export";

	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_CATALOG_AND_SCHEMA_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_CATALOG_AND_SCHEMA_INDEXING_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_CATALOG_AND_SCHEMA_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_CATALOG_AND_SCHEMA_INDEXING_TEST_EXPORT);
	}

	/**
	 * Returns the default evitaDB configuration for these tests.
	 *
	 * @return evitaDB configuration instance
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
					.storageDirectory(getTestDirectory().resolve(DIR_CATALOG_AND_SCHEMA_INDEXING_TEST))
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(getTestDirectory().resolve(DIR_CATALOG_AND_SCHEMA_INDEXING_TEST_EXPORT))
					.build()
			)
			.build();
	}

	/**
	 * Tests for applying direct mutations and updating catalogs with new products.
	 */
	@Nested
	@DisplayName("Direct mutations and catalog updates")
	class DirectMutationsAndCatalogUpdatesTest {

		@DisplayName("Update catalog with direct mutations.")
		@Test
		void shouldApplyDirectMutations() {
			CatalogAndSchemaIndexingTest.this.evita.defineCatalog(TEST_CATALOG)
				.withEntitySchema(
					Entities.PRODUCT,
					whichIs -> whichIs
						.withDescription("My fabulous product.")
						.withoutGeneratedPrimaryKey()
						.withAttribute("code", String.class)
						.withAttribute("name", String.class, AttributeSchemaEditor::localized)
						.withAttribute("logo", String.class)
						.withAttribute("productCount", Integer.class)
						.withPriceInCurrency(CURRENCY_CZK)
				)
				.updateViaNewSession(CatalogAndSchemaIndexingTest.this.evita);

			CatalogAndSchemaIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.applyMutation(
						new EntityUpsertMutation(
							Entities.PRODUCT,
							1000,
							EntityExistence.MUST_NOT_EXIST,
							List.of(
								new UpsertAttributeMutation("code", "siemens"),
								new UpsertAttributeMutation("name", Locale.ENGLISH, "Siemens"),
								new UpsertAttributeMutation("logo", "https://www.siemens.com/logo.png"),
								new UpsertAttributeMutation("productCount", 1),
								new UpsertPriceMutation(
									new PriceKey(1, "basic", CURRENCY_CZK),
									BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true
								)
							)
						)
					);
				}
			);
			final SealedEntity fetchedEntity = CatalogAndSchemaIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 1000, entityFetchAllContent()).orElseThrow();
				}
			);
			assertEquals(1000, fetchedEntity.getPrimaryKey());
			assertEquals("siemens", fetchedEntity.getAttribute("code"));
			assertEquals("Siemens", fetchedEntity.getAttribute("name", Locale.ENGLISH));
			assertEquals("https://www.siemens.com/logo.png", fetchedEntity.getAttribute("logo"));
			assertEquals(Integer.valueOf(1), fetchedEntity.getAttribute("productCount"));
			IndexingTestSupport.assertPrice(
				fetchedEntity, 1, "basic", Currency.getInstance("CZK"), BigDecimal.TEN, BigDecimal.ZERO,
				BigDecimal.TEN, true
			);
		}

		@DisplayName("Update catalog in warm-up mode with another product - synchronously.")
		@Test
		void shouldUpdateCatalogWithAnotherProduct() {
			final SealedEntity addedEntity = CatalogAndSchemaIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					CatalogAndSchemaIndexingTest.this.evita.defineCatalog(TEST_CATALOG)
						.withDescription("Some description.")
						.withEntitySchema(
							Entities.PRODUCT,
							whichIs -> whichIs
								.withDescription("My fabulous product.")
								.withGeneratedPrimaryKey()
						)
						.updateVia(session);

					final SealedEntity upsertedEntity = session.upsertAndFetchEntity(
						session.createNewEntity(Entities.PRODUCT)
					);
					assertNotNull(upsertedEntity);
					return upsertedEntity;
				}
			);

			CatalogAndSchemaIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Optional<SealedEntity> fetchedEntity = session.getEntity(
						Entities.PRODUCT, addedEntity.getPrimaryKey());
					assertTrue(fetchedEntity.isPresent());
					assertEquals(addedEntity, fetchedEntity.get());
				}
			);
		}

		@DisplayName("Update catalog in warm-up mode with another product - asynchronously.")
		@Test
		void shouldUpdateCatalogWithAnotherProductAsynchronously()
			throws ExecutionException, InterruptedException, TimeoutException {
			shouldUpdateCatalogWithAnotherProduct();

			final int addedEntityPrimaryKey = CatalogAndSchemaIndexingTest.this.evita.updateCatalogAsync(
					TEST_CATALOG,
					session -> {
						final SealedEntity upsertedEntity = session.upsertAndFetchEntity(
							session.createNewEntity(Entities.PRODUCT)
						);
						assertNotNull(upsertedEntity);
						return upsertedEntity;
					},
					CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION
				).toCompletableFuture()
				.get(1, TimeUnit.MINUTES)
				.getPrimaryKeyOrThrowException();

			CatalogAndSchemaIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// the entity will be immediately available in indexes
					final Optional<SealedEntity> fetchedEntity = session.getEntity(
						Entities.PRODUCT, addedEntityPrimaryKey);
					assertTrue(fetchedEntity.isPresent());
				}
			);
		}
	}

	/**
	 * Tests for creating catalogs and entity collections in prototyping mode and alongside schema definitions.
	 */
	@Nested
	@DisplayName("Prototyping mode and schema creation")
	class PrototypingModeAndSchemaCreationTest {

		@DisplayName("Create catalog and entity collections in prototyping mode.")
		@Test
		void shouldAllowCreatingCatalogAndEntityCollectionsInPrototypingMode() {
			final String someCatalogName = "differentCatalog";
			try {
				CatalogAndSchemaIndexingTest.this.evita.defineCatalog(someCatalogName)
					.withDescription("This is a tutorial catalog.")
					.updateViaNewSession(CatalogAndSchemaIndexingTest.this.evita);

				assertTrue(CatalogAndSchemaIndexingTest.this.evita.getCatalogNames().contains(someCatalogName));
				CatalogAndSchemaIndexingTest.this.evita.updateCatalog(
					someCatalogName,
					session -> {
						session.createNewEntity("Brand", 1)
							.setAttribute("name", Locale.ENGLISH, "Lenovo")
							.upsertVia(session);

						final Optional<SealedEntitySchema> brand = session.getEntitySchema("Brand");
						assertTrue(brand.isPresent());

						final Optional<EntityAttributeSchemaContract> nameAttribute =
							brand.get().getAttribute("name");
						assertTrue(nameAttribute.isPresent());
						assertTrue(nameAttribute.get().isLocalized());

						// now create an example category tree
						session.createNewEntity("Category", 10)
							.setAttribute("name", Locale.ENGLISH, "Electronics")
							.upsertVia(session);

						session.createNewEntity("Category", 11)
							.setAttribute("name", Locale.ENGLISH, "Laptops")
							// laptops will be a child category of electronics
							.setParent(10)
							.upsertVia(session);

						// finally, create a product
						session.createNewEntity("Product")
							// with a few attributes
							.setAttribute("name", Locale.ENGLISH, "ThinkPad P15 Gen 1")
							.setAttribute("cores", 8)
							.setAttribute("graphics", "NVIDIA Quadro RTX 4000 with Max-Q Design")
							// and price for sale
							.setPrice(
								1, "basic",
								Currency.getInstance("USD"),
								new BigDecimal("1420"), new BigDecimal("20"), new BigDecimal("1704"),
								true
							)
							// link it to the manufacturer
							.setReference(
								"brand", "Brand",
								Cardinality.EXACTLY_ONE,
								1
							)
							// and to the laptop category
							.setReference(
								"categories", "Category",
								Cardinality.ZERO_OR_MORE,
								11
							)
							.upsertVia(session);

						final Optional<SealedEntitySchema> product = session.getEntitySchema("Product");
						assertTrue(product.isPresent());

						final Optional<EntityAttributeSchemaContract> productNameAttribute =
							product.get().getAttribute("name");
						assertTrue(productNameAttribute.isPresent());
						assertTrue(productNameAttribute.get().isLocalized());
					}
				);

			} finally {
				CatalogAndSchemaIndexingTest.this.evita.deleteCatalogIfExists(someCatalogName);
			}
		}

		@DisplayName("Create catalog and entity collections along with the schema.")
		@Test
		void shouldAllowCreatingCatalogAndEntityCollectionsAlongWithTheSchema() {
			final String someCatalogName = "differentCatalog";
			try {
				CatalogAndSchemaIndexingTest.this.evita.defineCatalog(someCatalogName)
					.withDescription("Some description.")
					.withEntitySchema(
						Entities.PRODUCT,
						whichIs -> whichIs
							.withDescription("My fabulous product.")
							.withAttribute(
								"someAttribute", String.class, thatIs -> thatIs.filterable().nullable()
							)
					)
					.updateViaNewSession(CatalogAndSchemaIndexingTest.this.evita);

				assertTrue(CatalogAndSchemaIndexingTest.this.evita.getCatalogNames().contains(someCatalogName));
				CatalogAndSchemaIndexingTest.this.evita.queryCatalog(
					someCatalogName,
					session -> {
						assertEquals("Some description.", session.getCatalogSchema().getDescription());
						final SealedEntitySchema productSchema =
							session.getEntitySchema(Entities.PRODUCT).orElseThrow();

						assertEquals("My fabulous product.", productSchema.getDescription());
						final AttributeSchemaContract attributeSchema =
							productSchema.getAttribute("someAttribute").orElseThrow();
						assertTrue(attributeSchema.isFilterable());
						assertTrue(attributeSchema.isNullable());
						assertFalse(attributeSchema.isSortable());
					}
				);

			} finally {
				CatalogAndSchemaIndexingTest.this.evita.deleteCatalogIfExists(someCatalogName);
			}
		}

		@DisplayName("Update catalog and entity collections along with the schema.")
		@Test
		void shouldAllowUpdatingCatalogAndEntityCollectionsAlongWithTheSchema() {
			/* first update the catalog the standard way */
			CatalogAndSchemaIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withAttribute(ATTRIBUTE_NAME, String.class)
						.withDescription("Test")
						.updateVia(session);
				}
			);

			// now alter it again from the bird's-eye view
			try {
				CatalogAndSchemaIndexingTest.this.evita.defineCatalog(TEST_CATALOG)
					.withDescription("Some description.")
					.withEntitySchema(
						Entities.PRODUCT,
						whichIs -> whichIs
							.withDescription("My fabulous product.")
							.withAttribute(
								"someAttribute", String.class, thatIs -> thatIs.filterable().nullable()
							)
					)
					.updateViaNewSession(CatalogAndSchemaIndexingTest.this.evita);

				CatalogAndSchemaIndexingTest.this.evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						assertEquals("Some description.", session.getCatalogSchema().getDescription());
						final SealedEntitySchema productSchema =
							session.getEntitySchema(Entities.PRODUCT).orElseThrow();

						assertEquals("My fabulous product.", productSchema.getDescription());

						final AttributeSchemaContract alreadyExistingAttributeSchema =
							productSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
						assertSame(String.class, alreadyExistingAttributeSchema.getType());
						assertFalse(alreadyExistingAttributeSchema.isFilterable());
						assertFalse(alreadyExistingAttributeSchema.isNullable());
						assertFalse(alreadyExistingAttributeSchema.isSortable());

						final AttributeSchemaContract attributeSchema =
							productSchema.getAttribute("someAttribute").orElseThrow();
						assertSame(String.class, alreadyExistingAttributeSchema.getType());
						assertTrue(attributeSchema.isFilterable());
						assertTrue(attributeSchema.isNullable());
						assertFalse(attributeSchema.isSortable());
					}
				);

			} finally {
				CatalogAndSchemaIndexingTest.this.evita.deleteCatalogIfExists(TEST_CATALOG);
			}
		}
	}

	/**
	 * Tests for strict and lax schema validation behavior when upserting entities.
	 */
	@Nested
	@DisplayName("Strict schema validation")
	class StrictSchemaValidationTest {

		@DisplayName("Fail to upsert unknown entity to strictly validated catalog schema.")
		@Test
		void shouldFailToUpsertUnknownEntityToStrictlyValidatedCatalogSchema() {
			/* set strict schema verification */
			CatalogAndSchemaIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.verifyCatalogSchemaStrictly()
						.updateVia(session);
				}
			);

			// now try to upsert an unknown entity
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> CatalogAndSchemaIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.createNewEntity(Entities.PRODUCT)
							.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Produkt")
							.upsertVia(session);
					}
				)
			);
		}

		@DisplayName("Upsert unknown entity to laxly validated catalog schema.")
		@Test
		void shouldUpsertUnknownEntityToLaxlyValidatedCatalogSchema() {
			/* set lax schema verification */
			CatalogAndSchemaIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.verifyCatalogSchemaButCreateOnTheFly()
						.updateVia(session);
				}
			);

			// now try to upsert an unknown entity
			CatalogAndSchemaIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT)
						.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Produkt")
						.upsertVia(session);
				}
			);

			assertNotNull(
				CatalogAndSchemaIndexingTest.this.evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						return session.queryOneSealedEntity(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									and(
										entityLocaleEquals(LOCALE_CZ),
										attributeEquals(ATTRIBUTE_NAME, "Produkt")
									)
								),
								require(
									entityFetchAll()
								)
							)
						);
					}
				)
			);
		}

		@DisplayName("Different clients see schema unchanged until committed.")
		@Test
		void shouldDifferentClientsSeeSchemaUnchangedUntilCommitted() {
			CatalogAndSchemaIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withGeneratedPrimaryKey()
						.updateVia(session);
					// switch to transactional mode
					session.goLiveAndClose();
				}
			);
			// change schema by upserting first entity
			try (final EvitaSessionContract session = CatalogAndSchemaIndexingTest.this.evita.createReadWriteSession(TEST_CATALOG)) {
				// now implicitly update the schema
				session
					.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
					.upsertVia(session);

				// try to read the schema in different session
				final Thread asyncThread = new Thread(() -> {
					CatalogAndSchemaIndexingTest.this.evita.queryCatalog(
						TEST_CATALOG,
						differentSession -> {
							final SealedEntitySchema theSchema =
								differentSession.getEntitySchema(Entities.PRODUCT).orElseThrow();
							assertTrue(theSchema.isWithGeneratedPrimaryKey());
							assertTrue(theSchema.getAttributes().isEmpty());
							assertNull(differentSession.getEntity(Entities.PRODUCT, 1).orElse(null));
						}
					);
				});
				asyncThread.start();
				int i = 0;
				do {
					Thread.onSpinWait();
					i++;
				} while (asyncThread.isAlive() && i < 1_000_000);
			}

			// try to read the schema in different session when changes have been committed
			final Thread asyncThread = new Thread(() -> {
				CatalogAndSchemaIndexingTest.this.evita.queryCatalog(
					TEST_CATALOG,
					differentSession -> {
						final SealedEntitySchema theSchema =
							differentSession.getEntitySchema(Entities.PRODUCT).orElseThrow();
						assertFalse(theSchema.isWithGeneratedPrimaryKey());
						assertFalse(theSchema.getAttributes().isEmpty());
					}
				);
			});
		}
	}
}
