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

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the server applies the entity schema changes a catalog schema mutation cascades - the removal of a
 * global attribute from every entity schema using it - whatever catalog mutations surround it in one update.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Cascades of catalog schema mutations")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class CatalogSchemaCascadeTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String CHANGED_DESCRIPTION = "changed";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogSchemaCascadeTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_CODE, String.class)
					.withAttribute(ATTRIBUTE_NAME, String.class)
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withGlobalAttribute(ATTRIBUTE_NAME)
					.updateVia(session);
			}
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@DisplayName("remove a global attribute from the entities using it when a catalog mutation without cascade follows")
	@Test
	void shouldRemoveGlobalAttributeFromEntityWhenCatalogMutationWithoutCascadeFollows() {
		updateCatalogSchema(
			new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE),
			new ModifyCatalogSchemaDescriptionMutation(CHANGED_DESCRIPTION)
		);

		assertTrue(productSchema().getAttribute(ATTRIBUTE_CODE).isEmpty());
		assertTrue(productSchema().getAttribute(ATTRIBUTE_NAME).isPresent());
		assertCatalogSchemaChanged();
	}

	@DisplayName("remove a global attribute from the entities using it after a catalog mutation without cascade")
	@Test
	void shouldRemoveGlobalAttributeFromEntityWhenCatalogMutationWithoutCascadePrecedes() {
		updateCatalogSchema(
			new ModifyCatalogSchemaDescriptionMutation(CHANGED_DESCRIPTION),
			new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE)
		);

		assertTrue(productSchema().getAttribute(ATTRIBUTE_CODE).isEmpty());
		assertTrue(productSchema().getAttribute(ATTRIBUTE_NAME).isPresent());
		assertCatalogSchemaChanged();
	}

	@DisplayName("remove both global attributes when a catalog mutation without cascade sits between their removals")
	@Test
	void shouldRemoveBothGlobalAttributesWhenCatalogMutationWithoutCascadeSitsBetween() {
		updateCatalogSchema(
			new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE),
			new ModifyCatalogSchemaDescriptionMutation(CHANGED_DESCRIPTION),
			new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME)
		);

		assertTrue(productSchema().getAttributes().isEmpty(), () -> "Kept " + productSchema().getAttributes().keySet());
		assertCatalogSchemaChanged();
	}

	/**
	 * Applies the mutations to the catalog schema in one update.
	 *
	 * @param mutations the mutations to apply, in this order
	 */
	private void updateCatalogSchema(@Nonnull LocalCatalogSchemaMutation... mutations) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.updateCatalogSchema(mutations);
		});
	}

	/**
	 * Returns the product entity schema as the catalog holds it now.
	 *
	 * @return the product entity schema
	 */
	@Nonnull
	private EntitySchemaContract productSchema() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchemaOrThrowException(Entities.PRODUCT);
			}
		);
	}

	/**
	 * Asserts that the catalog schema carries the changed description and no longer has the removed global attribute,
	 * so the catalog mutations themselves were applied.
	 */
	private void assertCatalogSchemaChanged() {
		final CatalogSchemaContract catalogSchema = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getCatalogSchema();
			}
		);
		assertEquals(CHANGED_DESCRIPTION, catalogSchema.getDescription());
		assertTrue(catalogSchema.getAttribute(ATTRIBUTE_CODE).isEmpty());
	}

}
