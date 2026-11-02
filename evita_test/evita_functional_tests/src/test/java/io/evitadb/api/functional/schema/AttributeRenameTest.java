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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ModifyAttributeSchemaNameMutation} moves an attribute to its new name instead of adding a copy
 * next to it - for a global attribute and the entities using it, an entity attribute, a reference attribute and an
 * attribute a reflected reference declares - that it refuses a name another attribute holds and an attribute
 * a sortable attribute compound is made of, and that it warns when the collection holds entities whose values stay
 * under the old name.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Renaming an attribute")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class AttributeRenameTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_EAN = "ean";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String ATTRIBUTE_TITLE = "title";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_NOTE = "note";
	private static final String ATTRIBUTE_MEMO = "memo";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String REFERENCE_PRODUCTS = "products";
	private static final String WARNING_PREFIX = "Attribute `";
	private static final String RENAMED_TO = " was renamed to `";
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("AttributeRenameTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.nullable())
					.updateVia(session);
				session.defineEntitySchema(Entities.CATEGORY)
					.withReflectedReferenceToEntity(
						REFERENCE_PRODUCTS, Entities.PRODUCT, REFERENCE_CATEGORIES,
						whichIs -> whichIs.withAttribute(
							ATTRIBUTE_NOTE, String.class, attribute -> attribute.nullable()
						)
					)
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_LABEL, String.class, whichIs -> whichIs.nullable().sortable())
					.withAttribute(ATTRIBUTE_ORDER, Integer.class, whichIs -> whichIs.nullable().sortable())
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFiltering()
							.withAttribute(ATTRIBUTE_PRIORITY, Integer.class, attribute -> attribute.nullable())
					)
					.updateVia(session);
			}
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@DisplayName("remove a global attribute under its old name from the catalog and the entities using it")
	@Test
	void shouldRemoveGlobalAttributeUnderItsOldName() {
		renameGlobal(ATTRIBUTE_CODE, ATTRIBUTE_EAN);

		assertEquals(
			Set.of(ATTRIBUTE_EAN),
			this.evita.queryCatalog(TEST_CATALOG, session -> {
				return new TreeSet<>(session.getCatalogSchema().getAttributes().keySet());
			})
		);
		assertEquals(Set.of(ATTRIBUTE_EAN, ATTRIBUTE_LABEL, ATTRIBUTE_ORDER), attributesOf(productSchema()));
	}

	@DisplayName("remove an entity attribute under its old name")
	@Test
	void shouldRemoveEntityAttributeUnderItsOldName() {
		renameInProduct(new ModifyAttributeSchemaNameMutation(ATTRIBUTE_LABEL, ATTRIBUTE_TITLE));

		assertEquals(Set.of(ATTRIBUTE_CODE, ATTRIBUTE_TITLE, ATTRIBUTE_ORDER), attributesOf(productSchema()));
	}

	@DisplayName("remove a reference attribute under its old name")
	@Test
	void shouldRemoveReferenceAttributeUnderItsOldName() {
		renameInProduct(
			new ModifyReferenceAttributeSchemaMutation(
				REFERENCE_CATEGORIES, new ModifyAttributeSchemaNameMutation(ATTRIBUTE_PRIORITY, ATTRIBUTE_ORDER)
			)
		);

		assertEquals(
			Set.of(ATTRIBUTE_ORDER),
			new TreeSet<>(productSchema().getReferenceOrThrowException(REFERENCE_CATEGORIES).getAttributes().keySet())
		);
	}

	@DisplayName("remove an attribute a reflected reference declares under its old name")
	@Test
	void shouldRemoveDeclaredAttributeOfReflectedReferenceUnderItsOldName() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.updateEntitySchema(
					new ModifyEntitySchemaMutation(
						Entities.CATEGORY,
						new ModifyReferenceAttributeSchemaMutation(
							REFERENCE_PRODUCTS, new ModifyAttributeSchemaNameMutation(ATTRIBUTE_NOTE, ATTRIBUTE_MEMO)
						)
					)
				);
			}
		);

		final Set<String> attributes = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return new TreeSet<>(
					session.getEntitySchemaOrThrowException(Entities.CATEGORY)
						.getReferenceOrThrowException(REFERENCE_PRODUCTS)
						.getAttributes()
						.keySet()
				);
			}
		);
		assertTrue(attributes.contains(ATTRIBUTE_MEMO), () -> "Attributes: " + attributes);
		assertFalse(attributes.contains(ATTRIBUTE_NOTE), () -> "Attributes: " + attributes);
	}

	@DisplayName("refuse the name of another attribute of the entity")
	@Test
	void shouldRefuseNameOfAnotherEntityAttribute() {
		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> renameInProduct(new ModifyAttributeSchemaNameMutation(ATTRIBUTE_LABEL, ATTRIBUTE_CODE))
		);

		assertTrue(exception.getMessage().contains("`" + ATTRIBUTE_CODE + "` already exists"), exception::getMessage);
		assertEquals(Set.of(ATTRIBUTE_CODE, ATTRIBUTE_LABEL, ATTRIBUTE_ORDER), attributesOf(productSchema()));
	}

	@DisplayName("refuse the name of another attribute of the reference")
	@Test
	void shouldRefuseNameOfAnotherReferenceAttribute() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.openForWrite()
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withAttribute(
							ATTRIBUTE_ORDER, Integer.class, attribute -> attribute.nullable()
						)
					)
					.updateVia(session);
			}
		);

		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> renameInProduct(
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_CATEGORIES, new ModifyAttributeSchemaNameMutation(ATTRIBUTE_PRIORITY, ATTRIBUTE_ORDER)
				)
			)
		);

		assertTrue(exception.getMessage().contains("`" + ATTRIBUTE_ORDER + "` already exists"), exception::getMessage);
	}

	@DisplayName("refuse a global name an entity using the attribute holds for an attribute of its own")
	@Test
	void shouldRefuseGlobalNameAnEntityHoldsForItsOwnAttribute() {
		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> renameGlobal(ATTRIBUTE_CODE, ATTRIBUTE_LABEL)
		);

		assertTrue(exception.getMessage().contains("`" + ATTRIBUTE_LABEL + "` already exists"), exception::getMessage);
		assertEquals(Set.of(ATTRIBUTE_CODE, ATTRIBUTE_LABEL, ATTRIBUTE_ORDER), attributesOf(productSchema()));
	}

	@DisplayName("refuse a global rename before any entity schema using the attribute changes")
	@Test
	void shouldLeaveEveryEntityUnchangedWhenGlobalRenameIsRefused() {
		// the brand refusing the rename is listed after the product and the store, so that the rename reaches entity
		// schemas accepting it before the refusing one
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.STORE).withGlobalAttribute(ATTRIBUTE_CODE).updateVia(session);
				session.defineEntitySchema(Entities.BRAND)
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.nullable())
					.updateVia(session);
			}
		);

		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> renameGlobal(ATTRIBUTE_CODE, ATTRIBUTE_EAN)
		);

		assertTrue(exception.getMessage().contains("`" + ATTRIBUTE_EAN + "` already exists"), exception::getMessage);
		assertEquals(Set.of(ATTRIBUTE_CODE, ATTRIBUTE_LABEL, ATTRIBUTE_ORDER), attributesOf(productSchema()));
		assertEquals(Set.of(ATTRIBUTE_CODE), attributesOf(schemaOf(Entities.STORE)));
	}

	@DisplayName("refuse to rename an attribute a sortable attribute compound is made of")
	@Test
	void shouldRefuseRenamingAttributeOfSortableAttributeCompound() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.openForWrite()
					.withSortableAttributeCompound(
						"labelOrder", attributeElement(ATTRIBUTE_LABEL), attributeElement(ATTRIBUTE_ORDER)
					)
					.updateVia(session);
			}
		);

		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> renameInProduct(new ModifyAttributeSchemaNameMutation(ATTRIBUTE_LABEL, ATTRIBUTE_TITLE))
		);

		assertTrue(exception.getMessage().contains("`labelOrder`"), exception::getMessage);
		assertEquals(Set.of(ATTRIBUTE_CODE, ATTRIBUTE_LABEL, ATTRIBUTE_ORDER), attributesOf(productSchema()));
	}

	@DisplayName("warn that the values of stored entities stay under the old name")
	@Test
	void shouldWarnThatValuesOfStoredEntitiesStayUnderOldName() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT).setAttribute(ATTRIBUTE_LABEL, "stored")
				);
			}
		);

		final List<String> warnings = captureWarnings(
			() -> {
				renameInProduct(new ModifyAttributeSchemaNameMutation(ATTRIBUTE_LABEL, ATTRIBUTE_TITLE));
				renameInProduct(
					new ModifyReferenceAttributeSchemaMutation(
						REFERENCE_CATEGORIES, new ModifyAttributeSchemaNameMutation(ATTRIBUTE_PRIORITY, ATTRIBUTE_NOTE)
					)
				);
				renameGlobal(ATTRIBUTE_CODE, ATTRIBUTE_EAN);
			}
		);

		assertEquals(
			List.of(
				"Attribute `label` of entity `PRODUCT` was renamed to `title`",
				"Attribute `priority` of reference `categories` of entity `PRODUCT` was renamed to `note`",
				"Attribute `code` of entity `PRODUCT` was renamed to `ean`"
			),
			warnings
		);
	}

	@DisplayName("not warn over a collection without entities")
	@Test
	void shouldNotWarnOverCollectionWithoutEntities() {
		final List<String> warnings = captureWarnings(
			() -> {
				renameInProduct(new ModifyAttributeSchemaNameMutation(ATTRIBUTE_LABEL, ATTRIBUTE_TITLE));
				renameGlobal(ATTRIBUTE_CODE, ATTRIBUTE_EAN);
			}
		);

		assertEquals(List.of(), warnings);
	}

	/**
	 * Renames the global attribute in the catalog schema.
	 *
	 * @param name    the current name
	 * @param newName the new name
	 */
	private void renameGlobal(@Nonnull String name, @Nonnull String newName) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.updateCatalogSchema(new ModifyAttributeSchemaNameMutation(name, newName));
			}
		);
	}

	/**
	 * Applies the mutation to the product entity schema.
	 *
	 * @param mutation the mutation to apply
	 */
	private void renameInProduct(@Nonnull LocalEntitySchemaMutation mutation) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.updateEntitySchema(new ModifyEntitySchemaMutation(Entities.PRODUCT, mutation));
			}
		);
	}

	/**
	 * Returns the product entity schema as the catalog holds it now.
	 *
	 * @return the product entity schema
	 */
	@Nonnull
	private EntitySchemaContract productSchema() {
		return schemaOf(Entities.PRODUCT);
	}

	/**
	 * Returns the entity schema of the entity type as the catalog holds it now.
	 *
	 * @param entityType the entity type
	 * @return the entity schema
	 */
	@Nonnull
	private EntitySchemaContract schemaOf(@Nonnull String entityType) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchemaOrThrowException(entityType);
			}
		);
	}

	/**
	 * Returns the names of the attributes of the entity schema.
	 *
	 * @param schema the entity schema
	 * @return the names, sorted
	 */
	@Nonnull
	private static Set<String> attributesOf(@Nonnull EntitySchemaContract schema) {
		return new TreeSet<>(schema.getAttributes().keySet());
	}

	/**
	 * Runs the action and returns the part of each rename warning the entity collection logged meanwhile that names the
	 * attribute, the entity and the new name - the advice that follows may be reworded.
	 *
	 * @param action the action to run
	 * @return the captured warnings, in the order they were logged
	 */
	@Nonnull
	private static List<String> captureWarnings(@Nonnull Runnable action) {
		final List<String> warnings = new ArrayList<>();
		final ReentrantLock warningsLock = new ReentrantLock();
		final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
			@Override
			protected void append(ILoggingEvent eventObject) {
				final String message = eventObject.getFormattedMessage();
				final int renamedTo = message.indexOf(RENAMED_TO);
				if (eventObject.getLevel() == Level.WARN && message.startsWith(WARNING_PREFIX) && renamedTo > 0) {
					warningsLock.lock();
					try {
						warnings.add(message.substring(0, message.indexOf('`', renamedTo + RENAMED_TO.length()) + 1));
					} finally {
						warningsLock.unlock();
					}
				}
			}
		};
		final Logger logger = assertInstanceOf(
			Logger.class,
			LoggerFactory.getLogger(EntityCollection.class),
			"Logback backend required to capture log output."
		);
		appender.start();
		logger.addAppender(appender);
		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
		warningsLock.lock();
		try {
			return new ArrayList<>(warnings);
		} finally {
			warningsLock.unlock();
		}
	}

}
