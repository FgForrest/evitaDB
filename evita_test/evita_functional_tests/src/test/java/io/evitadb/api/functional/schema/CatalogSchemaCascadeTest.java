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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the server applies the entity schema changes a catalog schema mutation cascades - the removal of a
 * global attribute from every entity schema using it - right after that mutation, whatever mutations surround it in
 * one update, the way the replay of the WAL applies them.
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
	private static final String ATTRIBUTE_SKU = "sku";
	private static final String CHANGED_DESCRIPTION = "changed";
	private static final String RENAMED_PRODUCT = "renamedProduct";

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

	@DisplayName("keep a local attribute that replaces a removed global one in the same update")
	@ParameterizedTest(name = "with a catalog mutation without cascade in between: {0}")
	@ValueSource(booleans = {false, true})
	void shouldKeepLocalAttributeReplacingRemovedGlobalOne(boolean descriptionInBetween) {
		final AtomicReference<EntitySchemaContract> builtProduct = new AtomicReference<>();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final CatalogSchemaBuilder builder = session.getCatalogSchema()
					.openForWrite()
					.withoutAttribute(ATTRIBUTE_CODE);
				if (descriptionInBetween) {
					builder.withDescription(CHANGED_DESCRIPTION);
				}
				builder.withEntitySchema(
					Entities.PRODUCT,
					whichIs -> whichIs.withoutAttribute(ATTRIBUTE_CODE).withAttribute(ATTRIBUTE_CODE, Integer.class)
				);
				builtProduct.set(builder.toInstance().getEntitySchemaOrThrowException(Entities.PRODUCT));
				builder.updateVia(session);
			}
		);

		// the removal of the global attribute must not remove the local attribute the update creates in its place
		assertLocalCodeAttribute(builtProduct.get());
		assertLocalCodeAttribute(productSchema());
	}

	@DisplayName("keep a local attribute that replaces a removed global one in two modifications of the entity")
	@Test
	void shouldKeepLocalAttributeReplacingRemovedGlobalOneInTwoModifications() {
		final AtomicReference<EntitySchemaContract> builtProduct = new AtomicReference<>();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// the two modifications of the entity combine into one, where the removal of the global attribute
				// meets the creation of the local one
				final CatalogSchemaBuilder builder = session.getCatalogSchema()
					.openForWriteWithMutations(
						new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE),
						new ModifyEntitySchemaMutation(
							Entities.PRODUCT, new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE)
						)
					)
					.withEntitySchema(
						Entities.PRODUCT, whichIs -> whichIs.withAttribute(ATTRIBUTE_CODE, Integer.class)
					);
				builtProduct.set(builder.toInstance().getEntitySchemaOrThrowException(Entities.PRODUCT));
				builder.updateVia(session);
			}
		);

		assertLocalCodeAttribute(builtProduct.get());
		assertLocalCodeAttribute(productSchema());
	}

	@DisplayName("keep a local attribute that replaces a changed and removed global one in the same update")
	@Test
	void shouldKeepLocalAttributeReplacingChangedGlobalOne() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_CODE, String.class, whichIs -> whichIs.withDescription(CHANGED_DESCRIPTION)
					)
					.withoutAttribute(ATTRIBUTE_CODE)
					.withEntitySchema(
						Entities.PRODUCT,
						whichIs -> whichIs.withoutAttribute(ATTRIBUTE_CODE).withAttribute(ATTRIBUTE_CODE, Integer.class)
					)
					.updateVia(session);
			}
		);

		final AttributeSchemaContract code = assertLocalCodeAttribute(productSchema());
		// the change of the global attribute is not carried into the local one either
		assertNull(code.getDescription());
	}

	@DisplayName("keep an entity's own attribute named like a global one it does not use, when the global one changes")
	@Test
	void shouldKeepOwnAttributeNamedLikeGlobalOneWhenGlobalOneChangesOrGoes() {
		// the category has an attribute of its own named `sku` before the catalog declares a global `sku`
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withAttribute(ATTRIBUTE_SKU, Integer.class)
					.updateVia(session);
				session.getCatalogSchema().openForWrite().withAttribute(ATTRIBUTE_SKU, String.class).updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT).withGlobalAttribute(ATTRIBUTE_SKU).updateVia(session);
			}
		);

		updateCatalogSchema(new ModifyAttributeSchemaDescriptionMutation(ATTRIBUTE_SKU, CHANGED_DESCRIPTION));
		assertNull(categorySku().getDescription());
		assertEquals(CHANGED_DESCRIPTION, productSchema().getAttribute(ATTRIBUTE_SKU).orElseThrow().getDescription());

		updateCatalogSchema(new RemoveAttributeSchemaMutation(ATTRIBUTE_SKU));
		assertEquals(Integer.class, categorySku().getType());
		assertTrue(productSchema().getAttribute(ATTRIBUTE_SKU).isEmpty());
	}

	/**
	 * Returns the category's own `sku` attribute as the catalog holds it now.
	 *
	 * @return the attribute
	 */
	@Nonnull
	private AttributeSchemaContract categorySku() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchemaOrThrowException(Entities.CATEGORY)
					.getAttribute(ATTRIBUTE_SKU)
					.orElseThrow();
			}
		);
	}

	@DisplayName("refuse to change a global attribute in an entity after removing it in the same update")
	@Test
	void shouldRefuseChangingGlobalAttributeInEntityAfterRemovingIt() {
		// the removal takes the attribute from the entity right away - as the replay of the WAL does, which applies
		// every mutation as an update of its own - so the change that follows has nothing to change
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> updateCatalogSchema(
				new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE),
				new ModifyEntitySchemaMutation(
					Entities.PRODUCT, new ModifyAttributeSchemaDescriptionMutation(ATTRIBUTE_CODE, CHANGED_DESCRIPTION)
				)
			)
		);
	}

	@DisplayName("roll back a transaction that caught the refusal of a schema update, and keep the catalog replayable")
	@Test
	void shouldRollBackTransactionThatCaughtRefusedSchemaUpdate() {
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		final AtomicReference<Throwable> closingError = new AtomicReference<>();
		try {
			this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// the application catches the refusal and goes on - but the transaction already holds the
					// removal of the global attribute and its cascade, and committing them would write a batch
					// whose replay refuses it again
					assertThrows(
						InvalidSchemaMutationException.class,
						() -> session.updateCatalogSchema(
							new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE),
							new ModifyEntitySchemaMutation(
								Entities.PRODUCT,
								new ModifyAttributeSchemaDescriptionMutation(ATTRIBUTE_CODE, CHANGED_DESCRIPTION)
							)
						)
					);
				},
				CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
			);
		} catch (RuntimeException ex) {
			closingError.set(ex);
		}
		assertInstanceOf(RollbackException.class, closingError.get(), () -> "Closed with " + closingError.get());

		assertTrue(productSchema().getAttribute(ATTRIBUTE_CODE).isPresent());
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		assertTrue(productSchema().getAttribute(ATTRIBUTE_CODE).isPresent());
	}

	@DisplayName("keep the local replacement of a global attribute in a live catalog and after a restart")
	@Test
	void shouldKeepCascadeAndLocalReplacementInLiveCatalogAndAfterRestart() {
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withoutAttribute(ATTRIBUTE_NAME)
					.withoutAttribute(ATTRIBUTE_CODE)
					.withEntitySchema(
						Entities.PRODUCT,
						whichIs -> whichIs.withoutAttribute(ATTRIBUTE_CODE).withAttribute(ATTRIBUTE_CODE, Integer.class)
					)
					.updateVia(session);
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
		assertLocalCodeAttribute(productSchema());
		assertTrue(productSchema().getAttribute(ATTRIBUTE_NAME).isEmpty());

		// the restart replays the WAL, mutation by mutation
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());

		assertLocalCodeAttribute(productSchema());
		assertTrue(productSchema().getAttribute(ATTRIBUTE_NAME).isEmpty());
	}

	@DisplayName("remove a global attribute from an entity renamed later in the same update")
	@Test
	void shouldRemoveGlobalAttributeFromEntityRenamedLater() {
		updateCatalogSchema(
			new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE),
			new ModifyEntitySchemaNameMutation(Entities.PRODUCT, RENAMED_PRODUCT, false)
		);

		final EntitySchemaContract renamed = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchemaOrThrowException(RENAMED_PRODUCT);
			}
		);
		assertTrue(renamed.getAttribute(ATTRIBUTE_CODE).isEmpty(), () -> "Kept " + renamed.getAttributes().keySet());
		assertTrue(renamed.getAttribute(ATTRIBUTE_NAME).isPresent());
	}

	/**
	 * Asserts that the entity schema has a local `code` attribute of the type the update gave it.
	 *
	 * @param entitySchema the entity schema to check
	 * @return the attribute
	 */
	@Nonnull
	private static AttributeSchemaContract assertLocalCodeAttribute(@Nonnull EntitySchemaContract entitySchema) {
		final AttributeSchemaContract code = entitySchema.getAttribute(ATTRIBUTE_CODE)
			.orElseThrow(
				() -> new AssertionError("Missing local attribute `code`: " + entitySchema.getAttributes().keySet())
			);
		assertFalse(code instanceof GlobalAttributeSchemaContract, "Expected a local attribute, got " + code);
		assertEquals(Integer.class, code.getType());
		return code;
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
