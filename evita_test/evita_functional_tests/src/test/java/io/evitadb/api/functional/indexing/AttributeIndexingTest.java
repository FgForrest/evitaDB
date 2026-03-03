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
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.MandatoryAttributesNotProvidedException;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Currency;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.dataInLocalesAll;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.api.query.QueryConstraints.referenceContentAllWithAttributes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for attribute indexing operations verifying unique constraints, non-nullable validation,
 * and special attribute type handling in evitaDB.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Attribute indexing operations")
class AttributeIndexingTest implements EvitaTestSupport, IndexingTestSupport {
	private static final String DIR_ATTRIBUTE_INDEXING_TEST = "attributeIndexingTest";
	private static final String DIR_ATTRIBUTE_INDEXING_TEST_EXPORT = "attributeIndexingTest_export";

	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_ATTRIBUTE_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_ATTRIBUTE_INDEXING_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_ATTRIBUTE_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_ATTRIBUTE_INDEXING_TEST_EXPORT);
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
					.storageDirectory(getTestDirectory().resolve(DIR_ATTRIBUTE_INDEXING_TEST))
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(getTestDirectory().resolve(DIR_ATTRIBUTE_INDEXING_TEST_EXPORT))
					.build()
			)
			.build();
	}

	@Nested
	@DisplayName("Unique attributes")
	class UniqueAttributesTest {

		@Test
		@DisplayName("Should allow creating two entities with different unique attribute values")
		void shouldAllowToCreateTwoUniqueAttributes() {
			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateEntitySchema(
						session
							.defineEntitySchema(Entities.PRODUCT)
							.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().unique())
					);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.upsertVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 2)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "B")
						.upsertVia(session);
				}
			);
			AttributeIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.queryOneEntityReference(
						query(collection(Entities.PRODUCT), filterBy(attributeEquals(ATTRIBUTE_NAME, "A"))))
						.isPresent());
					assertTrue(session.queryOneEntityReference(
						query(collection(Entities.PRODUCT), filterBy(attributeEquals(ATTRIBUTE_NAME, "B"))))
						.isPresent());
					assertFalse(session.queryOneEntityReference(
						query(collection(Entities.PRODUCT), filterBy(attributeEquals(ATTRIBUTE_NAME, "V"))))
						.isPresent());
				}
			);
		}

		@Test
		@DisplayName("Should fail when creating two entities with the same unique attribute value")
		void shouldFailToCreateTwoNonUniqueAttributes() {
			try {
				AttributeIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateEntitySchema(
							session
								.defineEntitySchema(Entities.PRODUCT)
								.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().unique())
						);

						session
							.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
							.upsertVia(session);
						session
							.createNewEntity(Entities.PRODUCT, 2)
							.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "A")
							.upsertVia(session);
					}
				);

				fail("UniqueValueViolationException was expected to be thrown!");

			} catch (UniqueValueViolationException ex) {
				assertEquals(
					"Unique constraint violation: attribute `name` value A` is already present for entity `PRODUCT` (existing entity PK: 1, newly inserted  entity PK: 2)!",
					ex.getMessage()
				);
			}
		}

		@Test
		@DisplayName("Should fail when creating two entities with the same non-localized unique attribute value")
		void shouldFailToInsertConflictingNonLocalizedUniqueAttributes() {
			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND)
						.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique())
						.updateVia(session);

					session
						.createNewEntity(Entities.BRAND, 1)
						.setAttribute(ATTRIBUTE_CODE, "siemens")
						.upsertVia(session);

					assertThrows(
						UniqueValueViolationException.class,
						() -> session
							.createNewEntity(Entities.BRAND, 2)
							.setAttribute(ATTRIBUTE_CODE, "siemens")
							.upsertVia(session)
					);
				}
			);
		}

		@Test
		@DisplayName("Should allow reusing unique attribute value after it is changed on original entity")
		void shouldAllowToReuseUniqueAttributeAfterUpdate() {
			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND)
						.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique())
						.updateVia(session);

					session
						.createNewEntity(Entities.BRAND, 1)
						.setAttribute(ATTRIBUTE_CODE, "siemens")
						.upsertVia(session);

					// change the unique value on the original entity
					final SealedEntity theBrand = session
						.getEntity(Entities.BRAND, 1, attributeContentAll())
						.orElseThrow();

					theBrand.openForWrite()
						.setAttribute(ATTRIBUTE_CODE, "otherCode")
						.upsertVia(session);

					// now we can use the original code for a different entity
					session
						.createNewEntity(Entities.BRAND, 2)
						.setAttribute(ATTRIBUTE_CODE, "siemens")
						.upsertVia(session);
				}
			);
		}

		@Test
		@DisplayName("Should allow reusing unique attribute value after original entity is removed")
		void shouldAllowToReuseUniqueAttributeAfterEntityRemoval() {
			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND)
						.withAttribute(
							ATTRIBUTE_CODE, String.class,
							whichIs -> whichIs.unique().nullable()
						)
						.updateVia(session);

					session
						.createNewEntity(Entities.BRAND, 1)
						.setAttribute(ATTRIBUTE_CODE, "siemens")
						.upsertVia(session);

					// delete the original entity
					session.deleteEntity(Entities.BRAND, 1);

					// now we can use the original code for a different entity
					session
						.createNewEntity(Entities.BRAND, 2)
						.setAttribute(ATTRIBUTE_CODE, "siemens")
						.upsertVia(session);
				}
			);
		}

	}

	@Nested
	@DisplayName("Locale-specific unique attributes")
	class LocaleSpecificUniqueAttributesTest {

		@Test
		@DisplayName("Should allow same unique value in different locales")
		void shouldAllowToCreateTwoLocaleSpecificUniqueAttributes() {
			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateEntitySchema(
						session
							.defineEntitySchema(Entities.PRODUCT)
							.withAttribute(
								ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().uniqueWithinLocale())
					);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.upsertVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 2)
						.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "A")
						.upsertVia(session);
				}
			);
			AttributeIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.queryOneEntityReference(query(
						collection(Entities.PRODUCT), filterBy(
							attributeEquals(ATTRIBUTE_NAME, "A"),
							entityLocaleEquals(Locale.ENGLISH)
						)
					)).isPresent());
					assertTrue(session.queryOneEntityReference(query(
						collection(Entities.PRODUCT), filterBy(
							attributeEquals(ATTRIBUTE_NAME, "A"),
							entityLocaleEquals(Locale.GERMAN)
						)
					)).isPresent());
					assertFalse(session.queryOneEntityReference(query(
						collection(Entities.PRODUCT), filterBy(
							attributeEquals(ATTRIBUTE_NAME, "A"),
							entityLocaleEquals(Locale.FRENCH)
						)
					)).isPresent());
				}
			);
		}

		@Test
		@DisplayName("Should fail when same unique value exists in the same locale")
		void shouldFailToCreateTwoLocaleSpecificNonUniqueAttributes() {
			try {
				AttributeIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateEntitySchema(
							session
								.defineEntitySchema(Entities.PRODUCT)
								.withAttribute(
									ATTRIBUTE_NAME, String.class,
									whichIs -> whichIs.localized().uniqueWithinLocale())
						);

						session
							.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
							.upsertVia(session);
						session
							.createNewEntity(Entities.PRODUCT, 2)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
							.upsertVia(session);
					}
				);

				fail("UniqueValueViolationException was expected to be thrown!");

			} catch (UniqueValueViolationException ex) {
				assertEquals(
					"Unique constraint violation: attribute `name` value A` in locale `en` is already present for entity `PRODUCT` (existing entity PK: 1, newly inserted  entity PK: 2)!",
					ex.getMessage()
				);
			}
		}

	}

	@Nested
	@DisplayName("Globally unique attributes")
	class GloballyUniqueAttributesTest {

		@Test
		@DisplayName("Should allow different globally unique values across entity types")
		void shouldAllowToCreateTwoUniqueGloballyAttributes() {
			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().uniqueGlobally())
						.updateVia(session);

					session
						.defineEntitySchema(Entities.CATEGORY)
						.withGlobalAttribute(ATTRIBUTE_NAME)
						.updateVia(session);

					session
						.defineEntitySchema(Entities.PRODUCT)
						.withGlobalAttribute(ATTRIBUTE_NAME)
						.updateVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.upsertVia(session);

					session
						.createNewEntity(Entities.CATEGORY, 2)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "B")
						.upsertVia(session);
				}
			);
			AttributeIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(
						session.queryOneEntityReference(
							query(filterBy(attributeEquals(ATTRIBUTE_NAME, "A")))).isPresent());
					assertTrue(
						session.queryOneEntityReference(
							query(filterBy(attributeEquals(ATTRIBUTE_NAME, "B")))).isPresent());
					assertFalse(
						session.queryOneEntityReference(
							query(filterBy(attributeEquals(ATTRIBUTE_NAME, "V")))).isPresent());
				}
			);
		}

		@Test
		@DisplayName("Should fail when same globally unique value exists across entity types")
		void shouldFailToCreateTwoNonUniqueGloballyAttributes() {
			try {
				AttributeIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getCatalogSchema()
							.openForWrite()
							.withAttribute(
								ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().uniqueGlobally())
							.updateVia(session);

						session
							.defineEntitySchema(Entities.CATEGORY)
							.withGlobalAttribute(ATTRIBUTE_NAME)
							.updateVia(session);

						session
							.defineEntitySchema(Entities.PRODUCT)
							.withGlobalAttribute(ATTRIBUTE_NAME)
							.updateVia(session);

						session
							.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
							.upsertVia(session);

						session
							.createNewEntity(Entities.CATEGORY, 2)
							.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "A")
							.upsertVia(session);
					}
				);

				fail("UniqueValueViolationException was expected to be thrown!");

			} catch (UniqueValueViolationException ex) {
				assertEquals(
					"Unique constraint violation: attribute `name` value A` is already present for entity `PRODUCT` (existing entity PK: 1, newly inserted `CATEGORY` entity PK: 2)!",
					ex.getMessage()
				);
			}
		}

		@Test
		@DisplayName("Should allow same globally unique value in different locales")
		void shouldAllowToCreateTwoLocaleSpecificUniqueGloballyAttributes() {
			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs.localized().uniqueGloballyWithinLocale())
						.updateVia(session);

					session
						.defineEntitySchema(Entities.CATEGORY)
						.withGlobalAttribute(ATTRIBUTE_NAME)
						.updateVia(session);

					session
						.defineEntitySchema(Entities.PRODUCT)
						.withGlobalAttribute(ATTRIBUTE_NAME)
						.updateVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.upsertVia(session);

					session
						.createNewEntity(Entities.CATEGORY, 2)
						.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "A")
						.upsertVia(session);
				}
			);
			AttributeIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.queryOneEntityReference(query(
						filterBy(attributeEquals(ATTRIBUTE_NAME, "A"), entityLocaleEquals(Locale.ENGLISH))
					)).isPresent());
					assertTrue(session.queryOneEntityReference(query(
						filterBy(attributeEquals(ATTRIBUTE_NAME, "A"), entityLocaleEquals(Locale.GERMAN))
					)).isPresent());
					assertFalse(session.queryOneEntityReference(query(
						filterBy(attributeEquals(ATTRIBUTE_NAME, "A"), entityLocaleEquals(Locale.FRENCH))
					)).isPresent());
				}
			);
		}

		@Test
		@DisplayName("Should fail when same globally unique value exists in the same locale")
		void shouldFailToCreateTwoLocaleSpecificNonUniqueGloballyAttributes() {
			try {
				AttributeIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getCatalogSchema()
							.openForWrite()
							.withAttribute(
								ATTRIBUTE_NAME, String.class,
								whichIs -> whichIs.localized().uniqueGloballyWithinLocale())
							.updateVia(session);

						session
							.defineEntitySchema(Entities.CATEGORY)
							.withGlobalAttribute(ATTRIBUTE_NAME)
							.updateVia(session);

						session
							.defineEntitySchema(Entities.PRODUCT)
							.withGlobalAttribute(ATTRIBUTE_NAME)
							.updateVia(session);

						session
							.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
							.upsertVia(session);

						session
							.createNewEntity(Entities.CATEGORY, 2)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
							.upsertVia(session);
					}
				);

				fail("UniqueValueViolationException was expected to be thrown!");

			} catch (UniqueValueViolationException ex) {
				assertEquals(
					"Unique constraint violation: attribute `name` value A` in locale `en` is already present for entity `PRODUCT` (existing entity PK: 1, newly inserted `CATEGORY` entity PK: 2)!",
					ex.getMessage()
				);
			}
		}

	}

	@Nested
	@DisplayName("Non-nullable attribute validation")
	class NonNullableAttributeValidationTest {

		@Test
		@DisplayName("Should accept null and fill in default values for non-nullable attributes")
		void shouldAcceptNullInNonNullableAttributeWhenDefaultValueIsSet() {
			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.withDefaultValue("01"))
						.withAttribute(ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs.localized().withDefaultValue("A"))
						.withAttribute(ATTRIBUTE_DESCRIPTION, String.class,
							whichIs -> whichIs.localized().nullable())
						.withReferenceTo(
							Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE,
							thatIs -> thatIs
								.withAttribute(ATTRIBUTE_BRAND_EAN, String.class,
									whichIs -> whichIs.withDefaultValue("01"))
								.withAttribute(
									ATTRIBUTE_BRAND_NAME, String.class,
									whichIs -> whichIs.localized().withDefaultValue("A")
								)
								.withAttribute(
									ATTRIBUTE_BRAND_DESCRIPTION, String.class,
									whichIs -> whichIs.localized().nullable())
						)
						.updateVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.GERMAN, "B")
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "C")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "D")
						.setReference(
							Entities.BRAND, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.ENGLISH, "A")
								.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.GERMAN, "B")
								.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.FRENCH, "C")
								.setAttribute(ATTRIBUTE_BRAND_NAME, Locale.ENGLISH, "D")
						).upsertVia(session);
				}
			);

			AttributeIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity product = session.getEntity(
							Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll(),
							referenceContentAllWithAttributes())
						.orElseThrow();

					assertEquals("01", product.getAttribute(ATTRIBUTE_EAN));
					assertEquals("D", product.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
					assertEquals("A", product.getAttribute(ATTRIBUTE_NAME, Locale.GERMAN));
					assertEquals("A", product.getAttribute(ATTRIBUTE_NAME, Locale.FRENCH));

					final ReferenceContract theBrand = product.getReference(Entities.BRAND, 1).orElseThrow();
					assertEquals("01", theBrand.getAttribute(ATTRIBUTE_BRAND_EAN));
					assertEquals("D", theBrand.getAttribute(ATTRIBUTE_BRAND_NAME, Locale.ENGLISH));
					assertEquals("A", theBrand.getAttribute(ATTRIBUTE_BRAND_NAME, Locale.GERMAN));
					assertEquals("A", theBrand.getAttribute(ATTRIBUTE_BRAND_NAME, Locale.FRENCH));
				}
			);
		}

		@Test
		@DisplayName("Should accept null for localized attribute when entity locale is missing")
		void shouldAcceptNullForNonNullableLocalizedAttributeWhenEntityLocaleIsMissing() {
			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
						.withAttribute(ATTRIBUTE_DESCRIPTION, String.class, AttributeSchemaEditor::localized)
						.updateVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
						.setAttribute(ATTRIBUTE_NAME, Locale.FRENCH, "B")
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "B")
						.upsertVia(session);
				}
			);

			AttributeIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity product = session.getEntity(
							Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll(), referenceContentAll())
						.orElseThrow();

					assertEquals("A", product.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
					assertEquals("A", product.getAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH));
					assertEquals("B", product.getAttribute(ATTRIBUTE_NAME, Locale.FRENCH));
					assertEquals("B", product.getAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH));
					assertEquals(2, product.getAllLocales().size());
				}
			);

			assertThrows(
				MandatoryAttributesNotProvidedException.class,
				() -> {
					AttributeIndexingTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.getEntity(
									Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll(),
									referenceContentAll())
								.orElseThrow()
								.openForWrite()
								.removeAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH)
								.upsertVia(session);
						}
					);
				}
			);

			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(
							Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll(), referenceContentAll())
						.orElseThrow()
						.openForWrite()
						.removeAttribute(ATTRIBUTE_NAME, Locale.FRENCH)
						.removeAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH)
						.upsertVia(session);
				}
			);

			AttributeIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity product = session.getEntity(
							Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll(), referenceContentAll())
						.orElseThrow();

					assertEquals("A", product.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
					assertEquals("A", product.getAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH));
					assertEquals(1, product.getAllLocales().size());
					assertEquals(Locale.ENGLISH, product.getAllLocales().iterator().next());
				}
			);
		}

		@Test
		@DisplayName("Should fail when non-nullable attribute is not provided on new entity")
		void shouldFailToSetNonNullableAttributeToNull() {
			try {
				AttributeIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateEntitySchema(
							session
								.defineEntitySchema(Entities.PRODUCT)
								.withAttribute(ATTRIBUTE_EAN, String.class)
								.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
								.withAttribute(
									ATTRIBUTE_DESCRIPTION, String.class,
									whichIs -> whichIs.localized().nullable())
								.withReferenceTo(
									Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE,
									thatIs -> thatIs
										.withAttribute(ATTRIBUTE_BRAND_EAN, String.class)
										.withAttribute(
											ATTRIBUTE_BRAND_NAME, String.class, AttributeSchemaEditor::localized)
										.withAttribute(
											ATTRIBUTE_BRAND_DESCRIPTION, String.class,
											whichIs -> whichIs.localized().nullable()
										)
								)
						);

						final EntityBuilder product = session
							.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
							.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.GERMAN, "B")
							.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "C")
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "D")
							.setReference(
								Entities.BRAND, 1,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.ENGLISH, "A")
									.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.GERMAN, "B")
									.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.FRENCH, "C")
									.setAttribute(ATTRIBUTE_BRAND_NAME, Locale.ENGLISH, "D")
							);
						session.upsertEntity(product);
					}
				);

				fail("MandatoryAttributesNotProvidedException was expected to be thrown!");

			} catch (MandatoryAttributesNotProvidedException ex) {
				assertEquals(
					"""
						Entity `PRODUCT` requires these attributes to be non-null, but they are missing: `ean`.
						Entity `PRODUCT` requires these localized attributes to be specified for all localized versions of the entity, but values for some locales are missing: `name` in locales: `de`, `fr`.
						Entity `PRODUCT` reference `BRAND` requires these attributes to be non-null, but they are missing: `brandEan`.
						Entity `PRODUCT` reference `BRAND` requires these localized attributes to be specified for all localized versions of the entity, but values for some locales are missing: `brandName` in locales: `de`, `fr`.""",
					ex.getMessage()
				);
			}
		}

		@Test
		@DisplayName("Should fail when non-nullable attribute is removed from existing entity")
		void shouldFailToSetNonNullableAttributeToNullOnExistingEntity() {
			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAttribute(ATTRIBUTE_EAN, String.class)
						.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
						.withAttribute(ATTRIBUTE_DESCRIPTION, String.class,
							whichIs -> whichIs.localized().nullable())
						.withReferenceTo(
							Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE,
							thatIs -> thatIs
								.withAttribute(ATTRIBUTE_BRAND_EAN, String.class)
								.withAttribute(
									ATTRIBUTE_BRAND_NAME, String.class,
									AttributeSchemaEditor::localized
								)
								.withAttribute(
									ATTRIBUTE_BRAND_DESCRIPTION, String.class,
									whichIs -> whichIs.localized().nullable())
						)
						.updateVia(session);

					final EntityBuilder product = session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_EAN, "1")
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.GERMAN, "B")
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "C")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "D")
						.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "E")
						.setAttribute(ATTRIBUTE_NAME, Locale.FRENCH, "F")
						.setReference(
							Entities.BRAND, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_BRAND_EAN, "1")
								.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.ENGLISH, "A")
								.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.GERMAN, "B")
								.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.FRENCH, "C")
								.setAttribute(ATTRIBUTE_BRAND_NAME, Locale.ENGLISH, "D")
								.setAttribute(ATTRIBUTE_BRAND_NAME, Locale.GERMAN, "E")
								.setAttribute(ATTRIBUTE_BRAND_NAME, Locale.FRENCH, "F")
						);
					session.upsertEntity(product);
				}
			);

			try {
				AttributeIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session
							.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll(),
								referenceContentAll())
							.orElseThrow()
							.openForWrite()
							.removeAttribute(ATTRIBUTE_EAN)
							.removeAttribute(ATTRIBUTE_NAME, Locale.GERMAN)
							.removeAttribute(ATTRIBUTE_NAME, Locale.FRENCH)
							.updateReference(
								Entities.BRAND, 1,
								whichIs -> whichIs
									.removeAttribute(ATTRIBUTE_BRAND_EAN)
									.removeAttribute(ATTRIBUTE_BRAND_NAME, Locale.GERMAN)
									.removeAttribute(ATTRIBUTE_BRAND_NAME, Locale.FRENCH)
							)
							.upsertVia(session);
					}
				);

				fail("MandatoryAttributesNotProvidedException was expected to be thrown!");

			} catch (MandatoryAttributesNotProvidedException ex) {
				assertEquals(
					"""
						Entity `PRODUCT` requires these attributes to be non-null, but they are missing: `ean`.
						Entity `PRODUCT` requires these localized attributes to be specified for all localized versions of the entity, but values for some locales are missing: `name` in locales: `de`, `fr`.
						Entity `PRODUCT` reference `BRAND` requires these attributes to be non-null, but they are missing: `brandEan`.
						Entity `PRODUCT` reference `BRAND` requires these localized attributes to be specified for all localized versions of the entity, but values for some locales are missing: `brandName` in locales: `de`, `fr`.""",
					ex.getMessage()
				);
			}
		}

		@Test
		@DisplayName("Should fail when removing all non-nullable localized attributes from the last locale")
		void shouldFailToRemoveLastLocaleWithNonNullableLocalizedAttributes() {
			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
						.withAttribute(ATTRIBUTE_DESCRIPTION, String.class, AttributeSchemaEditor::localized)
						.updateVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
						.upsertVia(session);
				}
			);

			assertThrows(
				MandatoryAttributesNotProvidedException.class,
				() -> {
					AttributeIndexingTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.getEntity(
									Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll(),
									referenceContentAll())
								.orElseThrow()
								.openForWrite()
								.removeAttribute(ATTRIBUTE_NAME, Locale.ENGLISH)
								.removeAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH)
								.upsertVia(session);
						}
					);
				}
			);
		}

	}

	@Nested
	@DisplayName("Special attribute types")
	class SpecialAttributeTypesTest {

		@Test
		@DisplayName("Should mark Predecessor attribute as sortable")
		void shouldMarkPredecessorAsSortable() {
			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withEntitySchema(
							"whatever",
							whichIs -> whichIs.withAttribute(
								"whatever", Predecessor.class, AttributeSchemaEditor::sortable)
						)
						.updateVia(session);
				}
			);

			AttributeIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(
						session.getEntitySchemaOrThrowException("whatever")
							.getAttribute("whatever")
							.orElseThrow()
							.isSortable()
					);
				}
			);
		}

		@Test
		@DisplayName("Should fail to mark Predecessor attribute as filterable")
		void shouldFailToMarkPredecessorAsFilterable() {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getCatalogSchema()
							.openForWrite()
							.withEntitySchema(
								"whatever",
								whichIs -> whichIs.withAttribute(
									"whatever", Predecessor.class, AttributeSchemaEditor::filterable)
							)
							.updateVia(session);
					}
				)
			);
		}

		@Test
		@DisplayName("Should fail to set up ReferencedEntityPredecessor as direct entity attribute")
		void shouldFailToSetUpReferencedEntityPredecessorAsDirectAttribute() {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getCatalogSchema()
							.openForWrite()
							.withEntitySchema(
								"whatever",
								whichIs -> whichIs.withAttribute(
									"whatever", ReferencedEntityPredecessor.class)
							)
							.updateVia(session);
					}
				)
			);
		}

		@Test
		@DisplayName("Should fail to mark ReferencedEntityPredecessor reference attribute as filterable")
		void shouldFailToMarkReferencedEntityPredecessorAsFilterable() {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getCatalogSchema()
							.openForWrite()
							.withEntitySchema(
								"whatever",
								thatIs -> thatIs.withReferenceToEntity(
									"whatever", "whatever", Cardinality.ZERO_OR_MORE,
									whichIs -> whichIs.withAttribute(
										"whatever", ReferencedEntityPredecessor.class,
										AttributeSchemaEditor::filterable)
								)
							)
							.updateVia(session);
					}
				)
			);
		}

		@Test
		@DisplayName("Should fail to set up Predecessor as associated data")
		void shouldFailToSetUpPredecessorAsAssociatedData() {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getCatalogSchema()
							.openForWrite()
							.withEntitySchema(
								"whatever",
								whichIs -> whichIs.withAssociatedData("whatever", Predecessor.class)
							)
							.updateVia(session);
					}
				)
			);
		}

		@Test
		@DisplayName("Should fail to set up ReferencedEntityPredecessor as associated data")
		void shouldFailToSetUpReferencedEntityPredecessorAsAssociatedData() {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getCatalogSchema()
							.openForWrite()
							.withEntitySchema(
								"whatever",
								whichIs -> whichIs.withAssociatedData(
									"whatever", ReferencedEntityPredecessor.class)
							)
							.updateVia(session);
					}
				)
			);
		}

		@Test
		@DisplayName("Should mark Currency attribute as filterable")
		void shouldMarkCurrencyAsFilterable() {
			AttributeIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withEntitySchema(
							"whatever",
							whichIs -> whichIs.withAttribute(
								"whatever", Currency.class, AttributeSchemaEditor::filterable)
						)
						.updateVia(session);
				}
			);

			AttributeIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(
						session.getEntitySchemaOrThrowException("whatever")
							.getAttribute("whatever")
							.orElseThrow()
							.isFilterable()
					);
				}
			);
		}

	}

}
