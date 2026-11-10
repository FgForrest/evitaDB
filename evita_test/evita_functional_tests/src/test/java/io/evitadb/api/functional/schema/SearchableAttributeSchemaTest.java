/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.functional.schema;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.Locale;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the `searchable()` declaration of an attribute end to end on an embedded evitaDB: that it is accepted on every
 * kind of attribute that may carry it, that it survives a restart through the catalog storage, and that it is
 * refused - for reasons that have nothing to do with the data - on an attribute that cannot carry it.
 *
 * **A declaration over stored entities is accepted.** Bringing indexes in line with a changed schema is the
 * reindexing work of issue #409, and until it exists evitaDB never refuses a schema change because the collection
 * already holds entities.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Searchable attribute declared by a schema change")
@Tag(ENGINE)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
@Tag(FULLTEXT)
class SearchableAttributeSchemaTest implements EvitaTestSupport {

	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_DESCRIPTION = "description";
	private static final String ATTRIBUTE_TITLE = "title";
	private static final String ATTRIBUTE_BRAND_NAME = "brandName";
	private static final String REFERENCE_BRAND = "brand";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String REFERENCE_PRODUCTS = "products";
	private static final String ATTRIBUTE_LABEL = "label";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("SearchableAttributeSchema");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("declared before the data goes in")
	class DeclaredUpFront {

		@Test
		@DisplayName("should keep entity, reference and global attributes searchable across a restart")
		void shouldKeepSearchableAttributesAcrossRestart() {
			final SearchableAttributeSchemaTest test = SearchableAttributeSchemaTest.this;
			test.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withAttribute(ATTRIBUTE_TITLE, String.class, whichIs -> whichIs.localized().searchable())
						.updateVia(session);
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH)
						.withGlobalAttribute(ATTRIBUTE_TITLE)
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs.localized().searchableInScope(Scope.LIVE, Scope.ARCHIVED)
						)
						.withAttribute(
							ATTRIBUTE_DESCRIPTION, String.class,
							whichIs -> whichIs.localized().nullable().filterable()
						)
						.withReferenceTo(
							REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs.indexedForFiltering().withAttribute(
								ATTRIBUTE_BRAND_NAME, String.class,
								thatIs -> thatIs.localized().nullable().searchable()
							)
						)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "Phone")
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "iPhone 15")
					);
				}
			);

			test.reopenOverTheSameStorage();

			final EntitySchemaContract schema = test.productSchema();
			assertEquals(
				EnumSet.of(Scope.LIVE, Scope.ARCHIVED),
				schema.getAttribute(ATTRIBUTE_NAME).orElseThrow().getSearchableInScopes()
			);
			assertTrue(schema.getAttribute(ATTRIBUTE_TITLE).orElseThrow().isSearchable());
			assertTrue(
				schema.getReference(REFERENCE_BRAND).orElseThrow()
					.getAttribute(ATTRIBUTE_BRAND_NAME).orElseThrow()
					.isSearchable()
			);
			// searchability and filterability are independent - neither leaked into the other across the restart
			final AttributeSchemaContract description = schema.getAttribute(ATTRIBUTE_DESCRIPTION).orElseThrow();
			assertTrue(description.isFilterable());
			assertFalse(description.isSearchableInAnyScope());
			assertFalse(schema.getAttribute(ATTRIBUTE_NAME).orElseThrow().isFilterableInAnyScope());
		}

		@Test
		@DisplayName("should refuse a searchable attribute that is not localized and leave the schema untouched")
		void shouldRefuseSearchableAttributeThatIsNotLocalized() {
			final SearchableAttributeSchemaTest test = SearchableAttributeSchemaTest.this;
			test.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().nullable())
						.updateVia(session);
				}
			);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> test.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withAttribute(ATTRIBUTE_DESCRIPTION, String.class, AttributeSchemaEditor::searchable)
							.updateVia(session);
					}
				)
			);

			assertTrue(test.productSchema().getAttribute(ATTRIBUTE_DESCRIPTION).isEmpty());
		}

		@Test
		@DisplayName("should refuse a searchable reference attribute in a scope where its reference is not indexed")
		void shouldRefuseSearchableReferenceAttributeOfReferenceNotIndexed() {
			final SearchableAttributeSchemaTest test = SearchableAttributeSchemaTest.this;
			test.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH)
						.updateVia(session);
					session.goLiveAndClose();
				}
			);
			final RollbackException notIndexedRollback = assertThrows(
				RollbackException.class,
				() -> test.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withReferenceTo(
								REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
								whichIs -> whichIs.nonIndexed().withAttribute(
									ATTRIBUTE_BRAND_NAME, String.class,
									thatIs -> thatIs.localized().nullable().searchable()
								)
							)
							.updateVia(session);
					}
				)
			);
			final InvalidSchemaMutationException notIndexed =
				assertInstanceOf(InvalidSchemaMutationException.class, notIndexedRollback.getCause());
			assertTrue(
				notIndexed.getMessage().contains("`" + ATTRIBUTE_BRAND_NAME + "`") &&
					notIndexed.getMessage().contains("searchable in scope `LIVE`"),
				notIndexed.getMessage()
			);

			// indexed in the live scope only: searchability in the archived scope is refused there
			final RollbackException archivedOnlyRollback = assertThrows(
				RollbackException.class,
				() -> test.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withReferenceTo(
								REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
								whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE).withAttribute(
									ATTRIBUTE_BRAND_NAME, String.class,
									thatIs -> thatIs.localized().nullable()
										.searchableInScope(Scope.LIVE, Scope.ARCHIVED)
								)
							)
							.updateVia(session);
					}
				)
			);
			final InvalidSchemaMutationException archivedOnly =
				assertInstanceOf(InvalidSchemaMutationException.class, archivedOnlyRollback.getCause());
			assertTrue(archivedOnly.getMessage().contains("searchable in scope `ARCHIVED`"), archivedOnly.getMessage());
			assertFalse(archivedOnly.getMessage().contains("searchable in scope `LIVE`"), archivedOnly.getMessage());
		}

		@Test
		@DisplayName("should refuse an inherited searchable attribute where the reflected reference is not indexed")
		void shouldRefuseInheritedSearchableAttributeWhereReflectedReferenceIsNotIndexed() {
			final SearchableAttributeSchemaTest test = SearchableAttributeSchemaTest.this;
			// the reflected reference inherits the indexing of the original, so the inherited attribute is accepted
			test.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					defineProductWithSearchableCategoryLabel(session, Scope.LIVE, Scope.ARCHIVED);
					session.defineEntitySchema(Entities.CATEGORY)
						.withoutGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH)
						.withReflectedReferenceToEntity(
							REFERENCE_PRODUCTS, Entities.PRODUCT, REFERENCE_CATEGORIES,
							whichIs -> whichIs.withAttributesInherited()
						)
						.updateVia(session);
					session.goLiveAndClose();
				}
			);

			final RollbackException rollback = assertThrows(
				RollbackException.class,
				() -> test.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.CATEGORY)
							.openForWrite()
							.withReflectedReferenceToEntity(
								REFERENCE_PRODUCTS, Entities.PRODUCT, REFERENCE_CATEGORIES,
								whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE).withAttributesInherited()
							)
							.updateVia(session);
					}
				)
			);
			final InvalidSchemaMutationException exception =
				assertInstanceOf(InvalidSchemaMutationException.class, rollback.getCause());
			assertTrue(
				exception.getMessage().contains(
					"Attribute `" + ATTRIBUTE_LABEL + "` of reference schema `" + REFERENCE_PRODUCTS +
						"` is searchable in scope `ARCHIVED`"
				),
				exception.getMessage()
			);
			assertFalse(exception.getMessage().contains("searchable in scope `LIVE`"), exception.getMessage());
		}

		@Test
		@DisplayName("should refuse widening an original attribute beyond the scopes its reflected reference indexes")
		void shouldRefuseWideningSearchabilityBeyondReflectedReferenceIndex() {
			final SearchableAttributeSchemaTest test = SearchableAttributeSchemaTest.this;
			test.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					defineProductWithSearchableCategoryLabel(session, Scope.LIVE);
					session.defineEntitySchema(Entities.CATEGORY)
						.withoutGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH)
						.withReflectedReferenceToEntity(
							REFERENCE_PRODUCTS, Entities.PRODUCT, REFERENCE_CATEGORIES,
							whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE).withAttributesInherited()
						)
						.updateVia(session);
					session.goLiveAndClose();
				}
			);

			// the original is indexed in both scopes, so only the reflected reference can object
			final RollbackException rollback = assertThrows(
				RollbackException.class,
				() -> test.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						defineProductWithSearchableCategoryLabel(session, Scope.LIVE, Scope.ARCHIVED);
					}
				)
			);
			final InvalidSchemaMutationException exception =
				assertInstanceOf(InvalidSchemaMutationException.class, rollback.getCause());
			assertTrue(
				exception.getMessage().contains(
					"Attribute `" + ATTRIBUTE_LABEL + "` of reference schema `" + REFERENCE_PRODUCTS +
						"` is searchable in scope `ARCHIVED`"
				),
				exception.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("declared over stored entities")
	class DeclaredOverStoredEntities {

		@Test
		@DisplayName("should accept searchability on a live collection holding entities and keep it across a restart")
		void shouldAcceptSearchabilityOverStoredEntities() {
			final SearchableAttributeSchemaTest test = SearchableAttributeSchemaTest.this;
			test.goLiveWithOneLocalizedEntity();

			// never refused because of the stored entity - the values written before stay unindexed until #409
			test.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::searchable)
						.updateVia(session);
				}
			);
			assertTrue(test.productSchema().getAttribute(ATTRIBUTE_NAME).orElseThrow().isSearchable());

			test.reopenOverTheSameStorage();

			assertTrue(test.productSchema().getAttribute(ATTRIBUTE_NAME).orElseThrow().isSearchable());
		}

		@Test
		@DisplayName("should withdraw searchability on a live collection and keep it withdrawn across a restart")
		void shouldWithdrawSearchabilityOverStoredEntities() {
			final SearchableAttributeSchemaTest test = SearchableAttributeSchemaTest.this;
			test.goLiveWithOneLocalizedEntity();
			test.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::searchable)
						.updateVia(session);
				}
			);

			test.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::nonSearchable)
						.updateVia(session);
				}
			);
			test.reopenOverTheSameStorage();

			assertFalse(test.productSchema().getAttribute(ATTRIBUTE_NAME).orElseThrow().isSearchableInAnyScope());
		}
	}

	/**
	 * Declares a product collection with one localized, not yet searchable attribute, stores one entity with a
	 * value of it and switches the catalog to the transactional (alive) state.
	 */
	private void goLiveWithOneLocalizedEntity() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "iPhone 15")
				);
				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Reads the current product entity schema in a fresh read-only session.
	 *
	 * @return the product entity schema
	 */
	@Nonnull
	private EntitySchemaContract productSchema() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchemaOrThrow(Entities.PRODUCT);
			}
		);
	}

	/**
	 * Defines or updates the `PRODUCT` schema with a managed `categories` reference indexed in both scopes, whose
	 * localized `label` attribute is searchable in the given scopes.
	 *
	 * @param session          the session of the catalog update
	 * @param searchableScopes the scopes the label is searchable in
	 */
	private static void defineProductWithSearchableCategoryLabel(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope... searchableScopes
	) {
		session.defineEntitySchema(Entities.PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withLocale(Locale.ENGLISH)
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED).withAttribute(
					ATTRIBUTE_LABEL, String.class,
					thatIs -> thatIs.localized().nullable().searchableInScope(searchableScopes)
				)
			)
			.updateVia(session);
	}

	/**
	 * Closes the embedded evitaDB and opens a new one over the very same storage, so that everything asserted
	 * afterwards was read back from disk.
	 */
	private void reopenOverTheSameStorage() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		// deterministic rather than a wait: the constructor schedules the initial catalog load and this joins the
		// futures it created, so the state read below is the settled one
		this.evita.waitUntilFullyInitialized();
		if (this.evita.getCatalogState(TEST_CATALOG).orElse(null) == CatalogState.INACTIVE) {
			CatalogUnpublishableBarrierAssertions.activateWithConflictRetry(this.evita, TEST_CATALOG);
		}
	}

}
