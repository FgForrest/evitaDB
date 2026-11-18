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
import io.evitadb.api.exception.SortableAttributeCompoundSchemaException;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
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
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that removing an attribute a sortable attribute compound is made of is refused for entity, reference and
 * global attributes, preserving the schema and subsequent writes. Removing the compound first permits the attribute
 * removal, including when both removals are submitted in one schema update - but not from one schema post-processor,
 * which emits the removal of the global attribute first.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Removing an attribute")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class AttributeRemovalTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String COMPOUND_CODE_LABEL = "codeLabel";
	private static final String COMPOUND_LABEL_CODE = "labelCode";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final int STORED_PRODUCT_PK = 1;
	private static final int NEW_PRODUCT_PK = 2;
	private static final int CATEGORY_PK = 10;
	private TestPaths paths;
	private Evita evita;

	/**
	 * Creates an isolated catalog in the same warmup state as the attribute rename tests.
	 */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("AttributeRemovalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	/**
	 * Closes the engine and removes the directories allocated for this test.
	 */
	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * A raw entity mutation must preserve the compound's attribute and allow inserts, updates and compound ordering.
	 */
	@DisplayName("refuse a raw removal of an entity attribute used by a sortable attribute compound")
	@Test
	void shouldRefuseRemovingEntityAttributeUsedByCompound() {
		defineProduct(false);
		final EntitySchemaContract originalSchema = productSchema();
		final SortableAttributeCompoundSchemaException exception = assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateEntitySchema(
						new ModifyEntitySchemaMutation(
							Entities.PRODUCT, new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE)
						)
					);
				}
			)
		);

		assertTrue(exception.getMessage().contains(COMPOUND_CODE_LABEL), exception::getMessage);
		assertEquals(originalSchema, productSchema());
		assertProductCompoundPresent();
		assertLaterUpsertSucceeds();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(
					STORED_PRODUCT_PK,
					session.upsertEntity(
						session.getEntity(Entities.PRODUCT, STORED_PRODUCT_PK, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setAttribute(ATTRIBUTE_LABEL, "updated")
					).getPrimaryKey()
				);
			}
		);
		final List<Integer> orderedPrimaryKeys = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<Integer> primaryKeys = new ArrayList<>();
				for (final EntityReferenceContract entity : session.queryListOfEntityReferences(
					query(collection(Entities.PRODUCT), orderBy(attributeNatural(COMPOUND_CODE_LABEL)))
				)) {
					primaryKeys.add(entity.getPrimaryKey());
				}
				return primaryKeys;
			}
		);
		assertEquals(List.of(STORED_PRODUCT_PK, NEW_PRODUCT_PK), orderedPrimaryKeys);
	}

	/**
	 * A raw reference attribute removal must preserve the reference compound and permit another reference write.
	 */
	@DisplayName("refuse a raw removal of a reference attribute used by a sortable attribute compound")
	@Test
	void shouldRefuseRemovingReferenceAttributeUsedByCompound() {
		defineProduct(false);
		defineReference();
		final EntitySchemaContract originalSchema = productSchema();
		final SortableAttributeCompoundSchemaException exception = assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateEntitySchema(
						new ModifyEntitySchemaMutation(
							Entities.PRODUCT,
							new ModifyReferenceAttributeSchemaMutation(
								REFERENCE_CATEGORIES, new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE)
							)
						)
					);
				}
			)
		);

		assertTrue(exception.getMessage().contains(COMPOUND_CODE_LABEL), exception::getMessage);
		assertEquals(originalSchema, productSchema());
		final ReferenceSchemaContract reference = productSchema().getReferenceOrThrowException(REFERENCE_CATEGORIES);
		assertTrue(reference.getAttribute(ATTRIBUTE_CODE).isPresent());
		assertTrue(reference.getSortableAttributeCompound(COMPOUND_CODE_LABEL).isPresent());
		assertLaterReferenceUpsertSucceeds();
	}

	/**
	 * Removing a global attribute through the catalog builder must refuse its cascade into an entity compound.
	 */
	@DisplayName("refuse a catalog builder removal of a global attribute used by an entity compound")
	@Test
	void shouldRefuseRemovingGlobalAttributeViaCatalogBuilder() {
		defineProduct(true);
		final CatalogSchemaContract originalCatalogSchema = catalogSchema();
		final EntitySchemaContract originalEntitySchema = productSchema();
		final SortableAttributeCompoundSchemaException exception = assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema().openForWrite().withoutAttribute(ATTRIBUTE_CODE).updateVia(session);
				}
			)
		);

		assertTrue(exception.getMessage().contains(COMPOUND_CODE_LABEL), exception::getMessage);
		assertEquals(originalCatalogSchema, catalogSchema());
		assertEquals(originalEntitySchema, productSchema());
		assertTrue(catalogSchema().getAttribute(ATTRIBUTE_CODE).isPresent());
		assertProductCompoundPresent();
		assertLaterUpsertSucceeds();
	}

	/**
	 * Removing a global attribute through a raw catalog mutation must preserve both catalog and entity schemas.
	 */
	@DisplayName("refuse a raw catalog removal of a global attribute used by an entity compound")
	@Test
	void shouldRefuseRemovingGlobalAttributeViaRawMutation() {
		defineProduct(true);
		final CatalogSchemaContract originalCatalogSchema = catalogSchema();
		final EntitySchemaContract originalEntitySchema = productSchema();
		final SortableAttributeCompoundSchemaException exception = assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateCatalogSchema(new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE));
				}
			)
		);

		assertTrue(exception.getMessage().contains(COMPOUND_CODE_LABEL), exception::getMessage);
		assertEquals(originalCatalogSchema, catalogSchema());
		assertEquals(originalEntitySchema, productSchema());
		assertTrue(catalogSchema().getAttribute(ATTRIBUTE_CODE).isPresent());
		assertProductCompoundPresent();
		assertLaterUpsertSucceeds();
	}

	/**
	 * A refused global removal must leave every entity schema using the attribute unchanged, not only the one whose
	 * compound refuses it - the removal reaches the entity schemas one after another, so the entity schemas handled
	 * before the refusing one would otherwise lose the attribute while the catalog keeps it. Each entity type takes
	 * its turn as the refusing one, so that whatever order the catalog lists them in, some run has entity schemas
	 * without the compound before the refusing one.
	 *
	 * @param refusingEntityType the entity type whose sortable attribute compound is made of the attribute
	 */
	@DisplayName("refuse a global attribute removal before any entity schema using it changes")
	@ParameterizedTest(name = "refused by {0}")
	@ValueSource(strings = {Entities.PRODUCT, Entities.CATEGORY, Entities.STORE, Entities.BRAND})
	void shouldLeaveEveryEntityUnchangedWhenGlobalRemovalIsRefused(@Nonnull String refusingEntityType) {
		final List<String> entityTypes = List.of(Entities.PRODUCT, Entities.CATEGORY, Entities.STORE, Entities.BRAND);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema().openForWrite()
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.nullable().sortable())
					.updateVia(session);
				for (final String entityType : entityTypes) {
					final EntitySchemaBuilder builder = session.defineEntitySchema(entityType)
						.withGlobalAttribute(ATTRIBUTE_CODE);
					if (entityType.equals(refusingEntityType)) {
						builder.withAttribute(ATTRIBUTE_LABEL, String.class, whichIs -> whichIs.nullable().sortable())
							.withSortableAttributeCompound(
								COMPOUND_CODE_LABEL, attributeElement(ATTRIBUTE_CODE), attributeElement(ATTRIBUTE_LABEL)
							);
					}
					builder.updateVia(session);
				}
			}
		);

		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateCatalogSchema(new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE));
				}
			)
		);

		assertTrue(catalogSchema().getAttribute(ATTRIBUTE_CODE).isPresent());
		for (final String entityType : entityTypes) {
			final EntitySchemaContract schema = this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntitySchemaOrThrowException(entityType);
				}
			);
			assertTrue(schema.getAttribute(ATTRIBUTE_CODE).isPresent(), entityType);
		}
	}

	/**
	 * The refusal names every compound made of the attribute, so that all of them can be removed before the attribute.
	 */
	@DisplayName("name every sortable attribute compound made of the attribute in the refusal")
	@Test
	void shouldNameEveryCompoundWhenAttributeIsPartOfSeveralCompounds() {
		defineProduct(false);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.openForWrite()
					.withSortableAttributeCompound(
						COMPOUND_LABEL_CODE, attributeElement(ATTRIBUTE_LABEL), attributeElement(ATTRIBUTE_CODE)
					)
					.updateVia(session);
			}
		);

		final SortableAttributeCompoundSchemaException exception = assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateEntitySchema(
						new ModifyEntitySchemaMutation(
							Entities.PRODUCT, new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE)
						)
					);
				}
			)
		);

		assertTrue(exception.getMessage().contains("sortable attribute compounds `"), exception::getMessage);
		assertTrue(exception.getMessage().contains("`" + COMPOUND_CODE_LABEL + "`"), exception::getMessage);
		assertTrue(exception.getMessage().contains("`" + COMPOUND_LABEL_CODE + "`"), exception::getMessage);
		assertTrue(productSchema().getAttribute(ATTRIBUTE_CODE).isPresent());
	}

	/**
	 * A live catalog writes the global removal to the write-ahead log together with the entity schema removal it
	 * cascades into, and the replay - the incorporation into the trunk as well as a restart - cascades the global
	 * removal again before it reaches the recorded one, which then finds the attribute gone and must leave it so.
	 */
	@DisplayName("remove a global attribute in a live catalog and keep the removal after a restart")
	@Test
	void shouldRemoveGlobalAttributeInLiveCatalogAndAfterRestart() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema().openForWrite()
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.nullable())
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_LABEL, String.class, whichIs -> whichIs.nullable())
					.updateVia(session);
			}
		);
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.updateCatalogSchema(new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE));
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
		assertTrue(catalogSchema().getAttribute(ATTRIBUTE_CODE).isEmpty());
		assertEquals(Set.of(ATTRIBUTE_LABEL), productSchema().getAttributes().keySet());

		// the restart replays the WAL, mutation by mutation
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());

		assertTrue(catalogSchema().getAttribute(ATTRIBUTE_CODE).isEmpty());
		assertEquals(Set.of(ATTRIBUTE_LABEL), productSchema().getAttributes().keySet());
	}

	/**
	 * Ordered raw mutations may remove an entity compound and then its attribute in one entity schema update.
	 */
	@DisplayName("remove an entity compound before its attribute in one raw schema update")
	@Test
	void shouldRemoveEntityCompoundBeforeAttributeInOneUpdate() {
		defineProduct(false);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.updateEntitySchema(
					new ModifyEntitySchemaMutation(
						Entities.PRODUCT,
						new RemoveSortableAttributeCompoundSchemaMutation(COMPOUND_CODE_LABEL),
						new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE)
					)
				);
			}
		);

		assertTrue(productSchema().getSortableAttributeCompound(COMPOUND_CODE_LABEL).isEmpty());
		assertTrue(productSchema().getAttribute(ATTRIBUTE_CODE).isEmpty());
		assertLaterUpsertSucceeds();
	}

	/**
	 * One catalog update may remove an entity compound before removing its global attribute and cascading that removal.
	 */
	@DisplayName("remove an entity compound before its global attribute in one raw catalog update")
	@Test
	void shouldRemoveEntityCompoundBeforeGlobalAttributeInOneUpdate() {
		defineProduct(true);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.updateCatalogSchema(
					new ModifyEntitySchemaMutation(
						Entities.PRODUCT, new RemoveSortableAttributeCompoundSchemaMutation(COMPOUND_CODE_LABEL)
					),
					new RemoveAttributeSchemaMutation(ATTRIBUTE_CODE)
				);
			}
		);

		assertTrue(catalogSchema().getAttribute(ATTRIBUTE_CODE).isEmpty());
		assertTrue(productSchema().getAttribute(ATTRIBUTE_CODE).isEmpty());
		assertTrue(productSchema().getSortableAttributeCompound(COMPOUND_CODE_LABEL).isEmpty());
		assertLaterUpsertSucceeds();
	}

	/**
	 * The reference builder may remove a compound and then its attribute in one entity schema update.
	 */
	@DisplayName("remove a reference compound before its attribute in one builder update")
	@Test
	void shouldRemoveReferenceCompoundBeforeAttributeInOneUpdate() {
		defineProduct(false);
		defineReference();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.openForWrite()
					.withReferenceTo(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withoutSortableAttributeCompound(COMPOUND_CODE_LABEL)
							.withoutAttribute(ATTRIBUTE_CODE)
					)
					.updateVia(session);
			}
		);

		final ReferenceSchemaContract reference = productSchema().getReferenceOrThrowException(REFERENCE_CATEGORIES);
		assertTrue(reference.getSortableAttributeCompound(COMPOUND_CODE_LABEL).isEmpty());
		assertTrue(reference.getAttribute(ATTRIBUTE_CODE).isEmpty());
		assertLaterReferenceUpsertSucceeds();
	}

	/**
	 * A schema post-processor removing a compound and the global attribute it is made of in one analysis of a model
	 * class is refused before any schema changes: the analysis emits the mutations of the catalog schema before those
	 * of the entity schema, so the removal of the global attribute still finds the compound. The compound has to be
	 * removed in an update of its own first.
	 */
	@DisplayName("refuse a post-processor removing an entity compound and its global attribute, leaving both in place")
	@Test
	void shouldRefusePostProcessorRemovingEntityCompoundAndGlobalAttribute() {
		defineProduct(true);

		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchemaFromModelClass(
						LabelledProductModel.class,
						(catalogBuilder, entityBuilder) -> {
							entityBuilder.withoutSortableAttributeCompound(COMPOUND_CODE_LABEL);
							catalogBuilder.withoutAttribute(ATTRIBUTE_CODE);
						}
					);
				}
			)
		);

		assertTrue(catalogSchema().getAttribute(ATTRIBUTE_CODE).isPresent());
		assertProductCompoundPresent();
		assertLaterUpsertSucceeds();
	}

	/**
	 * A schema post-processor removing the compound of one entity and the global attribute another entity's compound
	 * is still made of is refused before any schema changes, the compound of the analysed entity included.
	 */
	@DisplayName("refuse a post-processor global removal another entity's compound blocks, leaving every schema as it is")
	@Test
	void shouldLeaveSchemasUnchangedWhenPostProcessorGlobalRemovalIsRefused() {
		defineProduct(true);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND)
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_LABEL, String.class, whichIs -> whichIs.nullable().sortable())
					.withSortableAttributeCompound(
						COMPOUND_CODE_LABEL, attributeElement(ATTRIBUTE_CODE), attributeElement(ATTRIBUTE_LABEL)
					)
					.updateVia(session);
			}
		);

		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchemaFromModelClass(
						LabelledProductModel.class,
						(catalogBuilder, entityBuilder) -> {
							entityBuilder.withoutSortableAttributeCompound(COMPOUND_CODE_LABEL);
							catalogBuilder.withoutAttribute(ATTRIBUTE_CODE);
						}
					);
				}
			)
		);

		assertTrue(catalogSchema().getAttribute(ATTRIBUTE_CODE).isPresent());
		assertProductCompoundPresent();
	}

	/**
	 * A schema post-processor re-creating the compound over the global attribute it removes is refused before any
	 * schema changes: the compound would name the missing attribute.
	 */
	@DisplayName("refuse a post-processor that re-creates the compound over the global attribute it removes")
	@Test
	void shouldRefusePostProcessorRecreatingCompoundOverRemovedGlobalAttribute() {
		defineProduct(true);

		assertThrows(
			SortableAttributeCompoundSchemaException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchemaFromModelClass(
						LabelledProductModel.class,
						(catalogBuilder, entityBuilder) -> {
							entityBuilder.withoutSortableAttributeCompound(COMPOUND_CODE_LABEL)
								.withSortableAttributeCompound(
									COMPOUND_CODE_LABEL,
									attributeElement(ATTRIBUTE_LABEL), attributeElement(ATTRIBUTE_CODE)
								);
							catalogBuilder.withoutAttribute(ATTRIBUTE_CODE);
						}
					);
				}
			)
		);

		assertTrue(catalogSchema().getAttribute(ATTRIBUTE_CODE).isPresent());
		assertProductCompoundPresent();
	}

	/**
	 * Defines the two nullable, sortable product attributes and their compound, and stores one product.
	 *
	 * @param globalCode whether code is declared globally and used by the entity
	 */
	private void defineProduct(boolean globalCode) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				if (globalCode) {
					session.getCatalogSchema().openForWrite()
						.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.nullable().sortable())
						.updateVia(session);
				}
				final EntitySchemaBuilder builder = session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey();
				if (globalCode) {
					builder.withGlobalAttribute(ATTRIBUTE_CODE);
				} else {
					builder.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.nullable().sortable());
				}
				builder.withAttribute(ATTRIBUTE_LABEL, String.class, whichIs -> whichIs.nullable().sortable())
					.withSortableAttributeCompound(
						COMPOUND_CODE_LABEL, attributeElement(ATTRIBUTE_CODE), attributeElement(ATTRIBUTE_LABEL)
					)
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, STORED_PRODUCT_PK)
						.setAttribute(ATTRIBUTE_CODE, "a")
						.setAttribute(ATTRIBUTE_LABEL, "stored")
				);
			}
		);
	}

	/**
	 * Adds an indexed reference to an external category type with its own attributes and compound.
	 */
	private void defineReference() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.openForWrite()
					.withReferenceTo(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFiltering()
							.withAttribute(ATTRIBUTE_CODE, String.class, attribute -> attribute.nullable().sortable())
							.withAttribute(ATTRIBUTE_LABEL, String.class, attribute -> attribute.nullable().sortable())
							.withSortableAttributeCompound(
								COMPOUND_CODE_LABEL, attributeElement(ATTRIBUTE_CODE), attributeElement(ATTRIBUTE_LABEL)
							)
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Verifies that the entity attribute and the compound referring to it are both still declared.
	 */
	private void assertProductCompoundPresent() {
		final EntitySchemaContract schema = productSchema();
		assertTrue(schema.getAttribute(ATTRIBUTE_CODE).isPresent());
		assertTrue(schema.getSortableAttributeCompound(COMPOUND_CODE_LABEL).isPresent());
	}

	/**
	 * Inserts a product setting only label and checks its returned primary key and stored value.
	 */
	private void assertLaterUpsertSucceeds() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(
					NEW_PRODUCT_PK,
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, NEW_PRODUCT_PK).setAttribute(ATTRIBUTE_LABEL, "new")
					).getPrimaryKey()
				);
				assertEquals(
					"new",
					session.getEntity(Entities.PRODUCT, NEW_PRODUCT_PK, entityFetchAllContent())
						.orElseThrow().getAttribute(ATTRIBUTE_LABEL, String.class)
				);
			}
		);
	}

	/**
	 * Inserts a product with a reference setting only its remaining label attribute and verifies the stored value.
	 */
	private void assertLaterReferenceUpsertSucceeds() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(
					NEW_PRODUCT_PK,
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, NEW_PRODUCT_PK)
							.setReference(
								REFERENCE_CATEGORIES, CATEGORY_PK,
								whichIs -> whichIs.setAttribute(ATTRIBUTE_LABEL, "new reference")
							)
					).getPrimaryKey()
				);
				assertEquals(
					"new reference",
					session.getEntity(Entities.PRODUCT, NEW_PRODUCT_PK, entityFetchAllContent())
						.orElseThrow().getReference(REFERENCE_CATEGORIES, CATEGORY_PK)
						.orElseThrow().getAttribute(ATTRIBUTE_LABEL, String.class)
				);
			}
		);
	}

	/**
	 * Returns the current product entity schema.
	 *
	 * @return the schema held by the catalog
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
	 * Returns the current catalog schema.
	 *
	 * @return the catalog schema
	 */
	@Nonnull
	private CatalogSchemaContract catalogSchema() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getCatalogSchema();
			}
		);
	}

	/**
	 * Model of the product declaring only the label the product schema already holds.
	 */
	@Entity(name = Entities.PRODUCT)
	public interface LabelledProductModel {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

		/**
		 * Returns the label.
		 */
		@Attribute(name = ATTRIBUTE_LABEL, sortable = true, nullable = true)
		@Nullable
		String getLabel();

	}
}
