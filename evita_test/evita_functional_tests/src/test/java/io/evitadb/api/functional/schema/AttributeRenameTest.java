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
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDescriptionMutation;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * attribute a reflected reference declares - that it refuses a name another attribute holds, an attribute
 * a sortable attribute compound is made of and an attribute a reflected reference inherits, and that it warns when
 * the collection holds entities whose values stay under the old name.
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
	private static final String REFERENCE_STORES = "stores";
	private static final String REFERENCE_BRANDS = "brands";
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

	@DisplayName("rename a global attribute in a live catalog and keep the rename after a restart")
	@Test
	void shouldRenameGlobalAttributeInLiveCatalogAndAfterRestart() {
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.updateCatalogSchema(new ModifyAttributeSchemaNameMutation(ATTRIBUTE_CODE, ATTRIBUTE_EAN));
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
		assertEquals(Set.of(ATTRIBUTE_EAN, ATTRIBUTE_LABEL, ATTRIBUTE_ORDER), attributesOf(productSchema()));

		// the restart replays the WAL, mutation by mutation
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());

		assertEquals(
			Set.of(ATTRIBUTE_EAN),
			this.evita.queryCatalog(TEST_CATALOG, session -> {
				return new TreeSet<>(session.getCatalogSchema().getAttributes().keySet());
			})
		);
		assertEquals(Set.of(ATTRIBUTE_EAN, ATTRIBUTE_LABEL, ATTRIBUTE_ORDER), attributesOf(productSchema()));
	}

	@DisplayName("rename an attribute a reflected reference declares before its original reference exists")
	@Test
	void shouldRenameDeclaredAttributeOfReflectedReferenceBeforeItsOriginalExists() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND)
					.withReflectedReferenceToEntity(
						REFERENCE_STORES, Entities.STORE, REFERENCE_BRANDS,
						whichIs -> whichIs.withAttributesInherited()
							.withAttribute(ATTRIBUTE_NOTE, String.class, attribute -> attribute.nullable())
					)
					.updateVia(session);
				session.updateEntitySchema(
					new ModifyEntitySchemaMutation(
						Entities.BRAND,
						new ModifyReferenceAttributeSchemaMutation(
							REFERENCE_STORES, new ModifyAttributeSchemaNameMutation(ATTRIBUTE_NOTE, ATTRIBUTE_MEMO)
						)
					)
				);
				session.defineEntitySchema(Entities.STORE)
					.withReferenceToEntity(
						REFERENCE_BRANDS, Entities.BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFiltering()
					)
					.updateVia(session);
			}
		);

		final ReferenceSchemaContract reference = schemaOf(Entities.BRAND)
			.getReferenceOrThrowException(REFERENCE_STORES);
		assertTrue(reference.getAttribute(ATTRIBUTE_MEMO).isPresent());
		assertTrue(reference.getAttribute(ATTRIBUTE_NOTE).isEmpty());
	}

	/**
	 * The rename reaches the entity schemas one after another, so a refusal by one of them must leave every one
	 * unchanged, and the catalog too. Each entity type takes its turn as the refusing one, so that whatever order the
	 * catalog lists them in, some run has entity schemas accepting the rename before the refusing one.
	 *
	 * @param refusingEntityType the entity type that holds an attribute of its own under the new name
	 */
	@DisplayName("refuse a global rename before any entity schema using the attribute changes")
	@ParameterizedTest(name = "refused by {0}")
	@ValueSource(strings = {Entities.PRODUCT, Entities.CATEGORY, Entities.STORE, Entities.BRAND})
	void shouldLeaveEveryEntityUnchangedWhenGlobalRenameIsRefused(@Nonnull String refusingEntityType) {
		final List<String> entityTypes = List.of(Entities.PRODUCT, Entities.CATEGORY, Entities.STORE, Entities.BRAND);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (final String entityType : entityTypes) {
					final EntitySchemaBuilder builder = session.defineEntitySchema(entityType)
						.withGlobalAttribute(ATTRIBUTE_CODE);
					if (entityType.equals(refusingEntityType)) {
						builder.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.nullable());
					}
					builder.updateVia(session);
				}
			}
		);

		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> renameGlobal(ATTRIBUTE_CODE, ATTRIBUTE_EAN)
		);

		assertTrue(exception.getMessage().contains("`" + ATTRIBUTE_EAN + "` already exists"), exception::getMessage);
		assertEquals(Set.of(ATTRIBUTE_CODE), catalogAttributes());
		for (final String entityType : entityTypes) {
			final Set<String> attributes = attributesOf(schemaOf(entityType));
			assertTrue(attributes.contains(ATTRIBUTE_CODE), () -> entityType + ": " + attributes);
			assertEquals(
				entityType.equals(refusingEntityType), attributes.contains(ATTRIBUTE_EAN),
				() -> entityType + ": " + attributes
			);
		}
	}

	@DisplayName("refuse a global rename an entity compound refuses, leaving the catalog and the entity unchanged")
	@Test
	void shouldRefuseRenamingGlobalAttributeAnEntityCompoundIsMadeOf() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.openForWrite()
					.withSortableAttributeCompound(
						"codeLabel", attributeElement(ATTRIBUTE_CODE), attributeElement(ATTRIBUTE_LABEL)
					)
					.updateVia(session);
			}
		);
		final EntitySchemaContract originalSchema = productSchema();

		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> renameGlobal(ATTRIBUTE_CODE, ATTRIBUTE_EAN)
		);

		assertTrue(exception.getMessage().contains("`codeLabel`"), exception::getMessage);
		assertEquals(Set.of(ATTRIBUTE_CODE), catalogAttributes());
		assertEquals(originalSchema, productSchema());
	}

	@DisplayName("refuse to rename an attribute a sortable attribute compound of the reference is made of")
	@Test
	void shouldRefuseRenamingAttributeOfReferenceSortableAttributeCompound() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.openForWrite()
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.withAttribute(ATTRIBUTE_ORDER, Integer.class, attribute -> attribute.nullable())
							.withSortableAttributeCompound(
								"priorityOrder", attributeElement(ATTRIBUTE_PRIORITY), attributeElement(ATTRIBUTE_ORDER)
							)
					)
					.updateVia(session);
			}
		);

		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> renameInProduct(
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_CATEGORIES, new ModifyAttributeSchemaNameMutation(ATTRIBUTE_PRIORITY, ATTRIBUTE_NOTE)
				)
			)
		);

		assertTrue(exception.getMessage().contains("`priorityOrder`"), exception::getMessage);
		assertEquals(
			Set.of(ATTRIBUTE_ORDER, ATTRIBUTE_PRIORITY),
			new TreeSet<>(productSchema().getReferenceOrThrowException(REFERENCE_CATEGORIES).getAttributes().keySet())
		);
	}

	@DisplayName("refuse to rename an attribute a reflected reference inherits, in the reflected reference")
	@Test
	void shouldRefuseRenamingAttributeInheritedByReflectedReference() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withReflectedReferenceToEntity(
						REFERENCE_PRODUCTS, Entities.PRODUCT, REFERENCE_CATEGORIES,
						whichIs -> whichIs.withAttributesInherited()
					)
					.updateVia(session);
			}
		);
		assertEquals(Set.of(ATTRIBUTE_NOTE, ATTRIBUTE_PRIORITY), reflectedReferenceAttributes());

		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateEntitySchema(
						new ModifyEntitySchemaMutation(
							Entities.CATEGORY,
							new ModifyReferenceAttributeSchemaMutation(
								REFERENCE_PRODUCTS,
								new ModifyAttributeSchemaNameMutation(ATTRIBUTE_PRIORITY, ATTRIBUTE_ORDER)
							)
						)
					);
				}
			)
		);

		// the refusal points at the reference the attribute can be renamed in
		assertTrue(exception.getMessage().contains("`" + REFERENCE_CATEGORIES + "`"), exception::getMessage);
		assertEquals(Set.of(ATTRIBUTE_NOTE, ATTRIBUTE_PRIORITY), reflectedReferenceAttributes());
		assertEquals(
			Set.of(ATTRIBUTE_PRIORITY),
			new TreeSet<>(productSchema().getReferenceOrThrowException(REFERENCE_CATEGORIES).getAttributes().keySet())
		);
	}

	@DisplayName("rename an attribute between changes of attributes the entity schema builder applies together")
	@Test
	void shouldRenameAttributeAmongOtherAttributeMutationsOfBuilder() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.withMutations(
						new ModifyAttributeSchemaDescriptionMutation(ATTRIBUTE_LABEL, "before the rename"),
						new ModifyAttributeSchemaNameMutation(ATTRIBUTE_LABEL, ATTRIBUTE_TITLE),
						new ModifyAttributeSchemaDescriptionMutation(ATTRIBUTE_TITLE, "after the rename")
					)
					.updateVia(session);
			}
		);

		final EntitySchemaContract schema = productSchema();
		assertEquals(Set.of(ATTRIBUTE_CODE, ATTRIBUTE_TITLE, ATTRIBUTE_ORDER), attributesOf(schema));
		assertEquals("after the rename", schema.getAttribute(ATTRIBUTE_TITLE).orElseThrow().getDescription());
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

	@DisplayName("not warn about a rename to the name the attribute already has")
	@Test
	void shouldNotWarnAboutRenameToTheSameName() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT).setAttribute(ATTRIBUTE_LABEL, "stored")
				);
			}
		);

		final List<String> warnings = captureWarnings(
			() -> renameInProduct(new ModifyAttributeSchemaNameMutation(ATTRIBUTE_LABEL, ATTRIBUTE_LABEL))
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
	 * Returns the names of the global attributes the catalog schema holds now.
	 *
	 * @return the names, sorted
	 */
	@Nonnull
	private Set<String> catalogAttributes() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return new TreeSet<>(session.getCatalogSchema().getAttributes().keySet());
			}
		);
	}

	/**
	 * Returns the names of the attributes of the reflected reference of the category to the products as the catalog
	 * holds it now, the inherited ones included.
	 *
	 * @return the names, sorted
	 */
	@Nonnull
	private Set<String> reflectedReferenceAttributes() {
		return new TreeSet<>(
			schemaOf(Entities.CATEGORY).getReferenceOrThrowException(REFERENCE_PRODUCTS).getAttributes().keySet()
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
