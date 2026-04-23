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

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.MandatoryAssociatedDataNotProvidedException;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ReflectionLookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Currency;
import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.associatedDataContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.dataInLocalesAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for associated data indexing operations verifying non-nullable validation
 * constraints in evitaDB.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Associated data indexing operations")
class AssociatedDataIndexingTest implements EvitaTestSupport, IndexingTestSupport {
	private static final String DIR_ASSOCIATED_DATA_INDEXING_TEST = "associatedDataIndexingTest";
	private static final String DIR_ASSOCIATED_DATA_INDEXING_TEST_EXPORT = "associatedDataIndexingTest_export";

	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_ASSOCIATED_DATA_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_ASSOCIATED_DATA_INDEXING_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_ASSOCIATED_DATA_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_ASSOCIATED_DATA_INDEXING_TEST_EXPORT);
	}

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
					.storageDirectory(getTestDirectory().resolve(DIR_ASSOCIATED_DATA_INDEXING_TEST))
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(getTestDirectory().resolve(DIR_ASSOCIATED_DATA_INDEXING_TEST_EXPORT))
					.build()
			)
			.build();
	}

	@Nested
	@DisplayName("Non-nullable associated data validation")
	class NonNullableAssociatedDataValidationTest {

		@Test
		@DisplayName("Should fail when non-nullable associated data is not provided on new entity")
		void shouldFailToSetNonNullableAssociatedDataToNull() {
			try {
				AssociatedDataIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session
							.defineEntitySchema(Entities.PRODUCT)
							.withAssociatedData(ATTRIBUTE_EAN, String.class)
							.withAssociatedData(ATTRIBUTE_NAME, String.class, AssociatedDataSchemaEditor::localized)
							.withAssociatedData(
								ATTRIBUTE_DESCRIPTION, String.class, whichIs -> whichIs.localized().nullable())
							.updateVia(session);

						session
							.createNewEntity(Entities.PRODUCT, 1)
							.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
							.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.GERMAN, "B")
							.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "C")
							.setAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH, "D")
							.upsertVia(session);
					}
				);

				fail("MandatoryAssociatedDataNotProvidedException was expected to be thrown!");

			} catch (MandatoryAssociatedDataNotProvidedException ex) {
				assertEquals(
					"Entity `PRODUCT` requires these associated data to be non-null, but they are missing: `ean`.\n" +
						"Entity `PRODUCT` requires these localized associated data to be specified for all localized versions " +
						"of the entity, but values for some locales are missing: `name` in locales: `de`, `fr`.",
					ex.getMessage()
				);
			}
		}

		@Test
		@DisplayName("Should fail when non-nullable associated data is removed from existing entity")
		void shouldFailToSetNonNullableAssociatedDataToNullOnExistingEntity() {
			AssociatedDataIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAssociatedData(ATTRIBUTE_EAN, String.class)
						.withAssociatedData(ATTRIBUTE_NAME, String.class, AssociatedDataSchemaEditor::localized)
						.withAssociatedData(ATTRIBUTE_DESCRIPTION, String.class,
							whichIs -> whichIs.localized().nullable())
						.updateVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAssociatedData(ATTRIBUTE_EAN, "1")
						.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
						.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.GERMAN, "B")
						.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "C")
						.setAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH, "D")
						.setAssociatedData(ATTRIBUTE_NAME, Locale.GERMAN, "E")
						.setAssociatedData(ATTRIBUTE_NAME, Locale.FRENCH, "F")
						.upsertVia(session);
				}
			);

			try {

				AssociatedDataIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session
							.getEntity(Entities.PRODUCT, 1, associatedDataContentAll(), dataInLocalesAll())
							.orElseThrow()
							.openForWrite()
							.removeAssociatedData(ATTRIBUTE_EAN)
							.removeAssociatedData(ATTRIBUTE_NAME, Locale.GERMAN)
							.removeAssociatedData(ATTRIBUTE_NAME, Locale.FRENCH)
							.upsertVia(session);
					}
				);

				fail("MandatoryAssociatedDataNotProvidedException was expected to be thrown!");

			} catch (MandatoryAssociatedDataNotProvidedException ex) {
				assertEquals(
					"Entity `PRODUCT` requires these associated data to be non-null, but they are missing: `ean`.\n" +
						"Entity `PRODUCT` requires these localized associated data to be specified for all localized versions " +
						"of the entity, but values for some locales are missing: `name` in locales: `de`, `fr`.",
					ex.getMessage()
				);
			}
		}

		@Test
		@DisplayName("Should accept null for localized associated data when entity locale is missing")
		void shouldAcceptNullForNonNullableLocalizedAssociatedDataWhenEntityLocaleIsMissing() {
			AssociatedDataIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAssociatedData(ATTRIBUTE_NAME, String.class, AssociatedDataSchemaEditor::localized)
						.withAssociatedData(ATTRIBUTE_DESCRIPTION, String.class, AssociatedDataSchemaEditor::localized)
						.updateVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
						.setAssociatedData(ATTRIBUTE_NAME, Locale.FRENCH, "B")
						.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "B")
						.upsertVia(session);
				}
			);

			AssociatedDataIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity product = session.getEntity(
							Entities.PRODUCT, 1, associatedDataContentAll(), dataInLocalesAll())
						.orElseThrow();

					assertEquals("A", product.getAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH));
					assertEquals("A", product.getAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH));
					assertEquals("B", product.getAssociatedData(ATTRIBUTE_NAME, Locale.FRENCH));
					assertEquals("B", product.getAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.FRENCH));
					assertEquals(2, product.getAllLocales().size());
				}
			);

			// removing just one non-nullable localized associated data while keeping another
			// in the same locale should fail
			assertThrows(
				MandatoryAssociatedDataNotProvidedException.class,
				() -> {
					AssociatedDataIndexingTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.getEntity(
								Entities.PRODUCT, 1, associatedDataContentAll(), dataInLocalesAll()
							)
								.orElseThrow()
								.openForWrite()
								.removeAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.FRENCH)
								.upsertVia(session);
						}
					);
				}
			);

			// removing all localized associated data for a locale should succeed by
			// dropping the entire locale from the entity
			AssociatedDataIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(
							Entities.PRODUCT, 1, associatedDataContentAll(), dataInLocalesAll())
						.orElseThrow()
						.openForWrite()
						.removeAssociatedData(ATTRIBUTE_NAME, Locale.FRENCH)
						.removeAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.FRENCH)
						.upsertVia(session);
				}
			);

			AssociatedDataIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity product = session.getEntity(
							Entities.PRODUCT, 1, associatedDataContentAll(), dataInLocalesAll())
						.orElseThrow();

					assertEquals("A", product.getAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH));
					assertEquals("A", product.getAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH));
					assertEquals(1, product.getAllLocales().size());
					assertEquals(Locale.ENGLISH, product.getAllLocales().iterator().next());
				}
			);
		}

		@Test
		@DisplayName("Should fail when removing all non-nullable localized associated data from the last locale")
		void shouldFailToRemoveLastLocaleWithNonNullableLocalizedAssociatedData() {
			AssociatedDataIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAssociatedData(ATTRIBUTE_NAME, String.class, AssociatedDataSchemaEditor::localized)
						.withAssociatedData(ATTRIBUTE_DESCRIPTION, String.class, AssociatedDataSchemaEditor::localized)
						.updateVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
						.upsertVia(session);
				}
			);

			assertThrows(
				MandatoryAssociatedDataNotProvidedException.class,
				() -> {
					AssociatedDataIndexingTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.getEntity(
									Entities.PRODUCT, 1, associatedDataContentAll(), dataInLocalesAll())
								.orElseThrow()
								.openForWrite()
								.removeAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH)
								.removeAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH)
								.upsertVia(session);
						}
					);
				}
			);
		}

		@Test
		@DisplayName("Should succeed removing locale when all non-nullable attributes and associated data are removed")
		void shouldSucceedRemovingLocaleWhenAllNonNullableAttributesAndAssociatedDataAreRemoved() {
			AssociatedDataIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAttribute(ATTRIBUTE_EAN, String.class, AttributeSchemaEditor::localized)
						.withAssociatedData(ATTRIBUTE_NAME, String.class, AssociatedDataSchemaEditor::localized)
						.withAssociatedData(ATTRIBUTE_DESCRIPTION, String.class, AssociatedDataSchemaEditor::localized)
						.updateVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_EAN, Locale.ENGLISH, "EN-001")
						.setAttribute(ATTRIBUTE_EAN, Locale.FRENCH, "FR-001")
						.setAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH, "English Name")
						.setAssociatedData(ATTRIBUTE_NAME, Locale.FRENCH, "French Name")
						.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "English Desc")
						.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "French Desc")
						.upsertVia(session);
				}
			);

			// remove all localized data for FRENCH locale — should succeed
			AssociatedDataIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(
							Entities.PRODUCT, 1,
							attributeContent(), associatedDataContentAll(), dataInLocalesAll())
						.orElseThrow()
						.openForWrite()
						.removeAttribute(ATTRIBUTE_EAN, Locale.FRENCH)
						.removeAssociatedData(ATTRIBUTE_NAME, Locale.FRENCH)
						.removeAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.FRENCH)
						.upsertVia(session);
				}
			);

			AssociatedDataIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity product = session.getEntity(
							Entities.PRODUCT, 1,
							attributeContent(), associatedDataContentAll(), dataInLocalesAll())
						.orElseThrow();

					assertEquals("EN-001", product.getAttribute(ATTRIBUTE_EAN, Locale.ENGLISH));
					assertEquals("English Name", product.getAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH));
					assertEquals("English Desc", product.getAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH));
					assertEquals(1, product.getAllLocales().size());
					assertEquals(Locale.ENGLISH, product.getAllLocales().iterator().next());
				}
			);
		}

	}

	@Nested
	@DisplayName("Associated data roundtrip")
	class AssociatedDataRoundtripTest {

		@Test
		@DisplayName("Should store and retrieve minimal entity with custom serializable associated data")
		void shouldStoreAndRetrieveMinimalEntityWithCustomAssociatedData() {
			AssociatedDataIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH, Locale.GERMAN)
						.withPriceInCurrency(
							Currency.getInstance("USD"), Currency.getInstance("EUR")
						)
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs
								.withDescription("The apt product name.")
								.localized()
								.filterable()
								.sortable()
								.nullable()
						)
						.updateVia(session);

					session.createNewEntity(Entities.PRODUCT)
						.setAssociatedData(
							"stockAvailability",
							new ProductStockAvailability(10)
						)
						.upsertVia(session);

					final SealedEntity entity = session
						.getEntity(Entities.PRODUCT, 1, associatedDataContentAll())
						.orElseThrow();

					assertNotNull(
						entity.getAssociatedData(
							"stockAvailability", ProductStockAvailability.class,
							new ReflectionLookup(ReflectionCachingBehaviour.NO_CACHE)
						)
					);
				}
			);
		}

	}

}
