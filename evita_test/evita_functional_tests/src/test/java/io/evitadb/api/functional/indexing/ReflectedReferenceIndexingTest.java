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
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.EntityMissingException;
import io.evitadb.api.exception.MandatoryAttributesNotProvidedException;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
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
import javax.annotation.Nullable;
import java.util.Optional;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for reflected reference indexing operations verifying that reflected references are automatically
 * created, updated, and removed in sync with their source references.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Reflected reference indexing operations")
class ReflectedReferenceIndexingTest implements EvitaTestSupport, IndexingTestSupport {
	private static final String DIR_REFLECTED_REFERENCE_INDEXING_TEST = "reflectedReferenceIndexingTest";
	private static final String DIR_REFLECTED_REFERENCE_INDEXING_TEST_EXPORT = "reflectedReferenceIndexingTest_export";

	private Evita evita;

	/**
	 * Asserts that the references between product and category entities are linked correctly.
	 *
	 * @param session the session to use for entity fetching
	 */
	private static void assertReferencesAreEntangled(@Nonnull EvitaSessionContract session) {
		final SealedEntity product = session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent()).orElseThrow();
		assertTrue(product.getReference(REFERENCE_PRODUCT_CATEGORY, 1).isPresent());
		final SealedEntity category = session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent()).orElseThrow();
		assertTrue(category.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10).isPresent());
	}

	/**
	 * Asserts that the references between product and category entities are NOT linked.
	 *
	 * @param session the session to use for entity fetching
	 */
	private static void assertReferencesAreNotEntangled(@Nonnull EvitaSessionContract session) {
		final SealedEntity product = session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent()).orElseThrow();
		assertTrue(product.getReference(REFERENCE_PRODUCT_CATEGORY, 1).isEmpty());
		final SealedEntity category = session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent()).orElseThrow();
		assertTrue(category.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10).isEmpty());
	}

	/**
	 * Asserts that at least one of the product/category entities was removed and references
	 * are no longer linked between the remaining entities.
	 *
	 * @param session the session to use for entity fetching
	 */
	private static void assertEntitiesAreNotEntangled(@Nonnull EvitaSessionContract session) {
		final Optional<SealedEntity> product = session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent());
		product.ifPresent(it -> assertTrue(it.getReference(REFERENCE_PRODUCT_CATEGORY, 1).isEmpty()));
		final Optional<SealedEntity> category = session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent());
		category.ifPresent(
			it -> assertTrue(it.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10).isEmpty())
		);
		assertTrue(
			product.isPresent() || category.isPresent(),
			"Neither product nor category entity was found!"
		);
		assertFalse(
			product.isPresent() && category.isPresent(),
			"Both product and category entities were found!"
		);
	}

	/**
	 * Creates a basic schema with reflected references between CATEGORY and PRODUCT entities.
	 *
	 * @param session the session to use for schema creation
	 */
	private static void createEntangledSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(Entities.CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY
			)
			.updateVia(session);
		session
			.defineEntitySchema(Entities.PRODUCT)
			.withReferenceToEntity(
				REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
			)
			.updateVia(session);
	}

	/**
	 * Creates a schema with reflected references and inherited attributes with default values
	 * between CATEGORY and PRODUCT entities.
	 *
	 * @param session the session to use for schema creation
	 */
	private static void createEntangledSchemaWithInheritedAttributes(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(Entities.CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
				whichIs -> whichIs.withAttributesInheritedExcept(ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED)
					.withAttribute(
						ATTRIBUTE_CATEGORY_MARKET, String.class,
						thatIs -> thatIs.filterable().sortable().withDefaultValue("CZ")
					)
			)
			.updateVia(session);
		session
			.defineEntitySchema(Entities.PRODUCT)
			.withReferenceToEntity(
				REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
					.withAttribute(
						ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED, String.class,
						thatIs -> thatIs.filterable().sortable().withDefaultValue("default")
					)
					.withAttribute(
						ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, String.class,
						thatIs -> thatIs.filterable().sortable().nullable()
					)
			)
			.updateVia(session);
	}

	/**
	 * Creates a schema with reflected references and inherited attributes without default values
	 * between CATEGORY and PRODUCT entities.
	 *
	 * @param session the session to use for schema creation
	 */
	private static void createEntangledSchemaWithInheritedAttributesWithoutDefaults(
		@Nonnull EvitaSessionContract session
	) {
		session.defineEntitySchema(Entities.CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
				whichIs -> whichIs.withAttributesInheritedExcept(ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED)
					.withAttribute(ATTRIBUTE_CATEGORY_MARKET, String.class, thatIs -> thatIs.filterable().sortable())
			)
			.updateVia(session);
		session
			.defineEntitySchema(Entities.PRODUCT)
			.withReferenceToEntity(
				REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
					.withAttribute(
						ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED, String.class,
						thatIs -> thatIs.filterable().sortable()
					)
					.withAttribute(
						ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, String.class,
						thatIs -> thatIs.filterable().sortable())
			)
			.updateVia(session);
	}

	/**
	 * Asserts the values of inherited and non-inherited attributes for specified product and category entities.
	 *
	 * @param session             the session to use for entity fetching
	 * @param productNotInherited expected value for the non-inherited product attribute
	 * @param productInherited    expected value for the inherited product attribute
	 * @param categoryMarket      expected value for the category market attribute
	 */
	private static void assertInheritedAttributesValues(
		@Nonnull EvitaSessionContract session,
		@Nonnull String productNotInherited,
		@Nullable String productInherited,
		@Nonnull String categoryMarket
	) {
		final SealedEntity product = session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent()).orElseThrow();
		final ReferenceContract productCategory = product.getReference(REFERENCE_PRODUCT_CATEGORY, 1).orElseThrow();
		assertEquals(productNotInherited, productCategory.getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED));
		if (productInherited != null) {
			assertEquals(productInherited, productCategory.getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED));
		} else {
			assertNull(productCategory.getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED));
		}

		final SealedEntity category = session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent()).orElseThrow();
		final ReferenceContract productsInCategory = category.getReference(
			REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10).orElseThrow();
		assertEquals(categoryMarket, productsInCategory.getAttribute(ATTRIBUTE_CATEGORY_MARKET));
		if (productInherited != null) {
			assertEquals(productInherited, productsInCategory.getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED));
		} else {
			assertNull(productsInCategory.getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED));
		}
	}

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_REFLECTED_REFERENCE_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_REFLECTED_REFERENCE_INDEXING_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_REFLECTED_REFERENCE_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_REFLECTED_REFERENCE_INDEXING_TEST_EXPORT);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return getEvitaConfiguration(-1);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(int inactivityTimeoutInSeconds) {
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(inactivityTimeoutInSeconds)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIR_REFLECTED_REFERENCE_INDEXING_TEST))
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(getTestDirectory().resolve(DIR_REFLECTED_REFERENCE_INDEXING_TEST_EXPORT))
					.build()
			)
			.build();
	}

	@Nested
	@DisplayName("Reflected reference creation")
	class ReflectedReferenceCreationTest {

		@Test
		@DisplayName("Setup reflected references when product references category on entity creation")
		void shouldAutomaticallySetupReflectedReferencesOnEntityCreation() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchema(session);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
							.setReference(REFERENCE_PRODUCT_CATEGORY, 1)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					assertReferencesAreEntangled(session);
				}
			);
		}

		@Test
		@DisplayName("Setup one-to-many reflected references including attributes on entity creation")
		void shouldAutomaticallySetupOneToManyReflectedReferencesIncludingAttributesOnEntityCreation() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.CATEGORY)
						.withReflectedReferenceToEntity(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT,
							REFERENCE_PRODUCT_CATEGORY,
							whichIs -> whichIs.withAttributesInherited()
								.withCardinality(Cardinality.ZERO_OR_MORE)
						)
						.updateVia(session);
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ONE_OR_MORE,
							whichIs -> whichIs.indexedForFilteringAndPartitioning()
								.withAttribute(
									ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, Boolean.class,
									thatIs -> thatIs.filterable())
						)
						.updateVia(session);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 2)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
							.setReference(
								REFERENCE_PRODUCT_CATEGORY, 1,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, true)
							)
							.setReference(
								REFERENCE_PRODUCT_CATEGORY, 2,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, false)
							)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 11)
							.setReference(
								REFERENCE_PRODUCT_CATEGORY, 1,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, false)
							)
							.setReference(
								REFERENCE_PRODUCT_CATEGORY, 2,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, true)
							)
					);
				}
			);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity category1 = session.getEntity(
						Entities.CATEGORY, 1, entityFetchAllContent()
					).orElseThrow();
					assertTrue(category1.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
						           .orElseThrow()
						           .getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, Boolean.class)
						           .booleanValue());
					assertFalse(category1.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 11)
						            .orElseThrow()
						            .getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, Boolean.class)
						            .booleanValue());

					final SealedEntity category2 = session.getEntity(
						Entities.CATEGORY, 2, entityFetchAllContent()
					).orElseThrow();
					assertFalse(category2.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
						            .orElseThrow()
						            .getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, Boolean.class)
						            .booleanValue());
					assertTrue(category2.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 11)
						           .orElseThrow()
						           .getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, Boolean.class)
						           .booleanValue());
				}
			);
		}

		@Test
		@DisplayName("Setup references when reflected ones exist on entity creation")
		void shouldAutomaticallySetupReferencesWhenReflectedOnesExistOnEntityCreation() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchema(session);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
							.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
					);

					assertReferencesAreEntangled(session);
				}
			);
		}

		@Test
		@DisplayName("Setup reflected references by references on already-created entity")
		void shouldAutomaticallySetupReflectedReferencesByReferencesOnCreatedEntity() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchema(session);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
							.setReference(REFERENCE_PRODUCT_CATEGORY, 1)
					);

					assertReferencesAreEntangled(session);
				}
			);
		}

		@Test
		@DisplayName("Setup references by reflected references on already-created entity")
		void shouldAutomaticallySetupReferencesByReflectedReferencesOnCreatedEntity() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchema(session);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
							.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
					);

					assertReferencesAreEntangled(session);
				}
			);
		}

		@Test
		@DisplayName("Setup reflected reference via update of existing entity")
		void shouldAutomaticallySetupReflectedReference() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchema(session);

					// first create both entities without any references
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
					);

					// then add regular reference
					session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(REFERENCE_PRODUCT_CATEGORY, 1)
						.upsertVia(session);

					assertReferencesAreEntangled(session);
				}
			);
		}

		@Test
		@DisplayName("Setup reference via reflected reference update on existing entity")
		void shouldAutomaticallySetupReferenceViaReflectedReference() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchema(session);

					// first create both entities without any references
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
					);

					// then add reflected reference
					session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
						.upsertVia(session);

					assertReferencesAreEntangled(session);
				}
			);
		}
	}

	@Nested
	@DisplayName("Inherited attributes on reflected references")
	class InheritedAttributesOnReflectedReferencesTest {

		@Test
		@DisplayName("Setup reflected references including inherited attributes on entity creation")
		void shouldAutomaticallySetupReflectedReferencesOnEntityCreationIncludingInheritedAttributes() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchemaWithInheritedAttributes(session);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
							.setReference(
								REFERENCE_PRODUCT_CATEGORY, 1,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED, "123")
							)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "123", "ABC", "CZ");
				}
			);
		}

		@Test
		@DisplayName("Setup references when reflected ones exist including inherited attributes")
		void shouldAutomaticallySetupReferencesWhenReflectedOnesExistOnEntityCreationIncludingInheritedAttributes() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchemaWithInheritedAttributes(session);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
							.setReference(
								REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
							)
					);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "default", "ABC", "CZ");
				}
			);
		}

		@Test
		@DisplayName("Setup reflected references by references on created entity including inherited values")
		void shouldAutomaticallySetupReflectedReferencesByReferencesOnCreatedEntityIncludingInheritedValues() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchemaWithInheritedAttributes(session);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
							.setReference(
								REFERENCE_PRODUCT_CATEGORY, 1,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
							)
					);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "default", "ABC", "CZ");
				}
			);
		}

		@Test
		@DisplayName("Setup references by reflected references on created entity including inherited attributes")
		void shouldAutomaticallySetupReferencesByReflectedReferencesOnCreatedEntityIncludingInheritedAttributes() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchemaWithInheritedAttributes(session);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
							.setReference(
								REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
							)
					);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "default", "ABC", "CZ");
				}
			);
		}

		@Test
		@DisplayName("Setup reflected reference including inherited attributes via update")
		void shouldAutomaticallySetupReflectedReferenceIncludingInheritedAttributes() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchemaWithInheritedAttributes(session);

					// first create both entities without any references
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
					);

					// then add regular reference
					session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REFERENCE_PRODUCT_CATEGORY, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
						)
						.upsertVia(session);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "default", "ABC", "CZ");
				}
			);
		}

		@Test
		@DisplayName("Setup reference via reflected reference with inherited attributes")
		void shouldAutomaticallySetupReferenceViaReflectedReferenceWithInheritedAttributes() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchemaWithInheritedAttributes(session);

					// first create both entities without any references
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
					);

					// then add reflected reference
					session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
						)
						.upsertVia(session);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "default", "ABC", "CZ");
				}
			);
		}

		@Test
		@DisplayName("Update inherited attribute on reflected reference")
		void shouldUpdateInheritedAttributeOnReflectedReference() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchemaWithInheritedAttributes(session);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
							.setReference(
								REFERENCE_PRODUCT_CATEGORY, 1,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
							)
					);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "default", "ABC", "CZ");

					session.upsertEntity(
						session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setReference(
								REFERENCE_PRODUCT_CATEGORY, 1,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "123")
							)
					);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "default", "123", "CZ");
				}
			);
		}

		@Test
		@DisplayName("Update inherited attribute on reference when set on reflected one")
		void shouldUpdateInheritedAttributeOnReferenceWhenSetOnReflectedOne() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchemaWithInheritedAttributes(session);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
							.setReference(
								REFERENCE_PRODUCT_CATEGORY, 1,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
							)
					);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "default", "ABC", "CZ");

					session.upsertEntity(
						session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setReference(
								REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "123")
							)
					);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "default", "123", "CZ");
				}
			);
		}

		@Test
		@DisplayName("Remove inherited attribute on reflected reference")
		void shouldRemoveInheritedAttributeOnReflectedReference() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchemaWithInheritedAttributes(session);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
							.setReference(
								REFERENCE_PRODUCT_CATEGORY, 1,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
							)
					);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "default", "ABC", "CZ");

					session.upsertEntity(
						session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setReference(
								REFERENCE_PRODUCT_CATEGORY, 1,
								whichIs -> whichIs
									.removeAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED)
							)
					);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "default", null, "CZ");
				}
			);
		}

		@Test
		@DisplayName("Remove inherited attribute on reference when set on reflected one")
		void shouldRemoveInheritedAttributeOnReferenceWhenSetOnReflectedOne() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchemaWithInheritedAttributes(session);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
							.setReference(
								REFERENCE_PRODUCT_CATEGORY, 1,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
							)
					);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "default", "ABC", "CZ");

					session.upsertEntity(
						session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setReference(
								REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10,
								whichIs -> whichIs
									.removeAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED)
							)
					);

					assertReferencesAreEntangled(session);
					assertInheritedAttributesValues(session, "default", null, "CZ");
				}
			);
		}
	}

	@Nested
	@DisplayName("Reflected reference removal")
	class ReflectedReferenceRemovalTest {

		@Test
		@DisplayName("Remove reference when reflected reference is removed")
		void shouldAutomaticallyRemoveReferenceViaReflectedReferenceIsRemoved() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchema(session);

					// first create both entities with particular reference
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
							.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
					);

					// then remove reflected reference
					session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
						.upsertVia(session);

					assertReferencesAreNotEntangled(session);
				}
			);
		}

		@Test
		@DisplayName("Remove reflected reference when regular reference is removed")
		void shouldAutomaticallyRemoveReflectedReferenceViaReferenceIsRemoved() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchema(session);

					// first create both entities with particular reference
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
							.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
					);

					// then remove regular reference
					session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(REFERENCE_PRODUCT_CATEGORY, 1)
						.upsertVia(session);

					assertReferencesAreNotEntangled(session);
				}
			);
		}

		@Test
		@DisplayName("Remove reflected reference when referenced entity is removed")
		void shouldAutomaticallyRemoveReflectedReferenceViaReferenceOnEntityRemoval() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchema(session);

					// first create both entities with particular reference
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
							.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
					);

					// then delete entity
					session.deleteEntity(Entities.PRODUCT, 10, entityFetchAllContent());

					assertEntitiesAreNotEntangled(session);
				}
			);
		}

		@Test
		@DisplayName("Remove reference when entity owning reflected reference is removed")
		void shouldAutomaticallyRemoveReferenceViaReflectedReferenceOnEntityRemoval() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchema(session);

					// first create both entities with particular reference
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
							.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
					);

					// then delete entity
					session.deleteEntity(Entities.CATEGORY, 1, entityFetchAllContent());

					assertEntitiesAreNotEntangled(session);
				}
			);
		}
	}

	@Nested
	@DisplayName("Reflected reference creation errors")
	class ReflectedReferenceCreationErrorsTest {

		@Test
		@DisplayName("Fail when base entity is not present during creation")
		void shouldFailToCreateReflectedReferencesWhenBaseEntityIsNotPresentDuringCreation() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchema(session);

					assertThrows(
						EntityMissingException.class,
						() -> session.upsertEntity(
							session.createNewEntity(Entities.CATEGORY, 1)
								.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
						)
					);
				}
			);
		}

		@Test
		@DisplayName("Fail when base entity is not present during update")
		void shouldFailToCreateReflectedReferencesWhenBaseEntityIsNotPresentDuringUpdate() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchema(session);

					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					assertThrows(
						EntityMissingException.class,
						() -> session.upsertEntity(
							session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
								.orElseThrow()
								.openForWrite()
								.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
								.upsertVia(session)
						)
					);
				}
			);
		}

		@Test
		@DisplayName("Fail when mandatory attributes have no default values")
		void shouldFailToCreateReflectedReferenceWhenMandatoryAttributesHasNoDefaultValues() {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					createEntangledSchemaWithInheritedAttributesWithoutDefaults(session);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 10)
							.setReference(
								REFERENCE_PRODUCT_CATEGORY, 1,
								whichIs -> whichIs
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
									.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED, "123")
							)
					);

					assertThrows(
						MandatoryAttributesNotProvidedException.class,
						() -> session.upsertEntity(
							session.createNewEntity(Entities.CATEGORY, 1)
						)
					);
				}
			);
		}
	}
}
