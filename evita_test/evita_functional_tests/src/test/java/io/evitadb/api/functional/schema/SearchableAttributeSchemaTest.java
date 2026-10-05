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
import io.evitadb.api.exception.InvalidSchemaMutationException;
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
							whichIs -> whichIs.withAttribute(
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
