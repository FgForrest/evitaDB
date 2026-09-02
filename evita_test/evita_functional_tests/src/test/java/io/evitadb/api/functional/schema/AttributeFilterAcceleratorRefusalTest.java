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

import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaAcceleratedMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
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
import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the refusal that protects users from a silently-incomplete substring index.
 *
 * The index backing a {@link AttributeFilterAccelerator} is built incrementally as entities are indexed, and no
 * reindexing machinery exists that could back-fill one for entities already stored. Declaring the capability on a
 * populated collection would therefore produce an index that answers only for entities written *after* the schema
 * change - queries would silently return fewer results than they should. The engine refuses instead, at the one
 * place every route into the schema passes through.
 *
 * The mirror-image case matters just as much and is asserted here too: declaring the capability **before** any data
 * goes in must work, and so must every schema change that does not add a capability, however populated the collection
 * is. A refusal that also fired on those would be a far worse bug than the one it prevents.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Filter index capability refusal")
@Tag(ENGINE)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class AttributeFilterAcceleratorRefusalTest implements EvitaTestSupport {

	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String REFERENCE_CATEGORIES = "categories";
	/** The PRODUCT-side reference the reflected one below points at - deliberately never declared. */
	private static final String REFERENCE_PRODUCT_CATEGORY = "productCategory";
	/** The CATEGORY-side reflected reference, declared before its target and therefore momentarily unresolved. */
	private static final String REFERENCE_REFLECTED_PRODUCTS = "productsInCategory";
	/** An attribute the reflected reference declares as its own, so its attribute set is not trivially empty. */
	private static final String ATTRIBUTE_MARKET = "market";
	/** The attribute the reflected reference excludes from inheritance, which is what makes it hold a filter. */
	private static final String ATTRIBUTE_NOT_INHERITED = "notInherited";
	private static final int CATEGORY_PK = 1;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("AttributeFilterAcceleratorRefusal");
		this.evita = new Evita(configuration());
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
		@DisplayName("should accept the capability on an empty collection and keep it after entities arrive")
		void shouldAcceptCapabilityOnEmptyCollectionAndKeepIt() {
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
						)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iPhone 15")
					);
				}
			);

			AttributeFilterAcceleratorRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEquals(
						Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getAccelerators()
					);
				}
			);
		}

		@Test
		@DisplayName("should accept the capability on a collection emptied of all its entities")
		void shouldAcceptCapabilityOnEmptiedCollection() {
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().nullable())
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iPhone 15")
					);
				}
			);
			// removing the existing entities is the documented way out of the refusal - prove it actually works
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(Entities.PRODUCT, 1);
				}
			);

			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH).nullable()
						)
						.updateVia(session);
				}
			);
		}
	}

	@Nested
	@DisplayName("declared on a populated collection")
	class DeclaredTooLate {

		@Test
		@DisplayName("should refuse adding the capability to an existing entity attribute")
		void shouldRefuseAddingCapabilityToExistingEntityAttribute() {
			populateWithPlainFilterableAttribute();

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withAttribute(
								ATTRIBUTE_NAME, String.class,
								whichIs -> whichIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
							)
							.updateVia(session);
					}
				)
			);
			assertTrue(exception.getMessage().contains(AttributeFilterAccelerator.SUBSTRING_SEARCH.name()));
			assertTrue(exception.getMessage().contains(ATTRIBUTE_NAME));
			// the message has to say what to do about it, not merely that it was refused
			assertTrue(exception.getMessage().contains("before inserting data"));
		}

		@Test
		@DisplayName("should refuse creating a new attribute that already declares the capability")
		void shouldRefuseCreatingNewAttributeDeclaringCapability() {
			populateWithPlainFilterableAttribute();

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withAttribute(
								"description", String.class,
								whichIs -> whichIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH).nullable()
							)
							.updateVia(session);
					}
				)
			);
		}

		@Test
		@DisplayName("should refuse adding the capability in the archived scope")
		void shouldRefuseAddingCapabilityInArchivedScope() {
			populateWithPlainFilterableAttribute();

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withAttribute(
								ATTRIBUTE_NAME, String.class,
								whichIs -> whichIs
									.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
									.acceleratedForInScope(
										Scope.ARCHIVED, AttributeFilterAccelerator.SUBSTRING_SEARCH
									)
							)
							.updateVia(session);
					}
				)
			);
			assertTrue(exception.getMessage().contains(Scope.ARCHIVED.name()));
		}
	}

	@Nested
	@DisplayName("declared on a reference attribute")
	class OnReferenceAttribute {

		@Test
		@DisplayName("should refuse the capability on a reference attribute of an empty collection")
		void shouldRefuseCapabilityOnReferenceAttributeOfEmptyCollection() {
			// nothing here is about data - the index that would serve the capability is maintained on the entity's
			// global index and never sees reference attribute values, so an empty collection is refused just the same
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(Entities.CATEGORY)
							.withoutGeneratedPrimaryKey()
							.updateVia(session);
						session.defineEntitySchema(Entities.PRODUCT)
							.withoutGeneratedPrimaryKey()
							.withReferenceToEntity(
								REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
								whichIs -> whichIs
									.indexedForFilteringAndPartitioning()
									.withAttribute(
										ATTRIBUTE_CODE, String.class,
										thatIs -> thatIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH).nullable()
									)
							)
							.updateVia(session);
					}
				)
			);
			assertTrue(exception.getMessage().contains(REFERENCE_CATEGORIES));
			assertTrue(exception.getMessage().contains(AttributeFilterAccelerator.SUBSTRING_SEARCH.name()));
			// the message must point at the way out, not merely refuse
			assertTrue(exception.getMessage().contains("entity attributes only"));
		}

		@Test
		@DisplayName("should refuse the capability on a reference attribute of a populated collection")
		void shouldRefuseCapabilityOnReferenceAttributeOfPopulatedCollection() {
			populateWithFilterableReferenceAttribute();

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withReferenceToEntity(
								REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
								whichIs -> whichIs.withAttribute(
									ATTRIBUTE_CODE, String.class,
									thatIs -> thatIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH).nullable()
								)
							)
							.updateVia(session);
					}
				)
			);
			assertTrue(exception.getMessage().contains(REFERENCE_CATEGORIES));
		}

		@Test
		@DisplayName("should leave a plainly filterable reference attribute alone")
		void shouldLeavePlainlyFilterableReferenceAttributeAlone() {
			// the refusal is about capabilities alone - plain filterability on a reference attribute still works,
			// on a populated collection and all
			populateWithFilterableReferenceAttribute();

			AttributeFilterAcceleratorRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getReference(REFERENCE_CATEGORIES).orElseThrow()
							.getAttribute(ATTRIBUTE_CODE).orElseThrow()
							.isFilterable()
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("declared on a global attribute shared by several collections")
	class GlobalAttributeCascade {

		@Test
		@DisplayName("should leave every collection untouched when one of them refuses the cascade")
		void shouldLeaveEveryCollectionUntouchedWhenOneRefusesTheCascade() {
			// a catalog-level change to a global attribute fans out into one mutation per consuming collection, and
			// they are applied one at a time - each exchanging its schema and persisting it. Before the preflight, a
			// refusal from the *second* collection could not undo the first, leaving the catalog saying "failed"
			// while one collection kept the capability. CATEGORY is deliberately empty and PRODUCT populated, so the
			// refusal comes from the second one visited under at least one legal iteration order.
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().nullable())
						.updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY)
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute(ATTRIBUTE_NAME)
						.updateVia(session);
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute(ATTRIBUTE_NAME)
						.updateVia(session);
					// only PRODUCT gets data - CATEGORY stays empty and would accept the capability on its own
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iPhone 15")
					);
				}
			);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateCatalogSchema(
							new SetAttributeSchemaAcceleratedMutation(
								ATTRIBUTE_NAME,
								new ScopedAttributeFilterAccelerators(
									Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH
								)
							)
						);
					}
				)
			);

			// the whole cascade must have been refused - not the catalog schema only, and not "all but the first
			// collection visited". Every one of the three schemas has to read exactly as it did before.
			AttributeFilterAcceleratorRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(
						session.getCatalogSchema()
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getAccelerators().isEmpty(),
						"the catalog schema must not keep the refused capability"
					);
					assertTrue(
						session.getEntitySchemaOrThrow(Entities.CATEGORY)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getAccelerators().isEmpty(),
						"the empty collection must not keep the capability the populated one refused"
					);
					assertTrue(
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getAccelerators().isEmpty(),
						"the populated collection must be unchanged"
					);
				}
			);
		}

		@Test
		@DisplayName("should apply the cascade to every collection when all of them are empty")
		void shouldApplyCascadeToEveryCollectionWhenAllAreEmpty() {
			// the positive control - the preflight must not turn a legal cascade into a refusal
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().nullable())
						.updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY)
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute(ATTRIBUTE_NAME)
						.updateVia(session);
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute(ATTRIBUTE_NAME)
						.updateVia(session);
				}
			);

			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateCatalogSchema(
						new SetAttributeSchemaAcceleratedMutation(
							ATTRIBUTE_NAME,
							new ScopedAttributeFilterAccelerators(
								Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH
							)
						)
					);
				}
			);

			AttributeFilterAcceleratorRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEquals(
						Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
						session.getEntitySchemaOrThrow(Entities.CATEGORY)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getAccelerators()
					);
					assertEquals(
						Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getAccelerators()
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("unrelated schema changes")
	class UnrelatedChanges {

		@Test
		@DisplayName("should allow an ordinary schema change on a populated collection")
		void shouldAllowOrdinarySchemaChangeOnPopulatedCollection() {
			populateWithPlainFilterableAttribute();

			// nothing here adds a capability, so the refusal must not fire however populated the collection is
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterable().nullable())
						.updateVia(session);
				}
			);
		}

		@Test
		@DisplayName("should allow a schema change on a collection carrying an unresolved reflected reference")
		void shouldAllowSchemaChangeWithUnresolvedReflectedReference() {
			// CATEGORY declares its reflected reference BEFORE PRODUCT declares the reference it reflects, so at the
			// moment CATEGORY's own mutation is verified the reflected reference is still UNRESOLVED. It also
			// declares attributes of its own alongside an inheritance filter, which is what makes
			// `ReflectedReferenceSchema#getAttributes` throw rather than answer empty while the target is missing -
			// the refusal check must skip such a reference rather than walk it. Before the skip existed this block
			// threw "Attributes of the reflected reference are inherited from the target reference, but the
			// reflected reference is not available!" from inside the refusal walk
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.CATEGORY)
						.withReflectedReferenceToEntity(
							REFERENCE_REFLECTED_PRODUCTS, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
							whichIs -> whichIs.withAttributesInheritedExcept(ATTRIBUTE_NOT_INHERITED)
								.withAttribute(
									ATTRIBUTE_MARKET, String.class,
									thatIs -> thatIs.filterable().withDefaultValue("CZ")
								)
						)
						.updateVia(session);
					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.indexedForFilteringAndPartitioning()
								.withAttribute(
									ATTRIBUTE_NOT_INHERITED, String.class,
									thatIs -> thatIs.filterable().withDefaultValue("default")
								)
						)
						.updateVia(session);
				}
			);

			// and an ordinary, capability-free schema change on the reflecting collection must still go through
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.CATEGORY)
						.openForWrite()
						.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
						.updateVia(session);
				}
			);

			AttributeFilterAcceleratorRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EntitySchemaContract schema = session.getEntitySchemaOrThrow(Entities.CATEGORY);
					assertTrue(
						schema.getAttribute(ATTRIBUTE_CODE).isPresent(),
						"the unrelated attribute must have been added"
					);
				}
			);
		}

		@Test
		@DisplayName("should allow removing the capability from a populated collection")
		void shouldAllowRemovingCapabilityFromPopulatedCollection() {
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
						)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iPhone 15")
					);
				}
			);

			// dropping an index needs no data, so the refusal is deliberately one-directional. Withdrawal has to be
			// stated explicitly though - restating `filterable()` says nothing about the accelerator axis, which is
			// exactly what stops an unrelated schema edit from silently deleting an index
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs
								.filterable()
								.nonAcceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
						)
						.updateVia(session);
				}
			);

			AttributeFilterAcceleratorRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getAccelerators().isEmpty()
					);
				}
			);
		}

		@Test
		@DisplayName("should keep the shared value tree usable when the capability is dropped from populated data")
		void shouldKeepSharedValueTreeConsistentWhenCapabilityIsDroppedFromPopulatedCollection() {
			// The end-to-end shape of the value id drop path. Removal is deliberately legal on a populated
			// collection (the sibling test above pins that), and the trigram substring index DOES register a value id
			// consumer, so the withdrawal below reaches `InvertedIndex#detachValueIdConsumer` for real - through the
			// next write to the attribute, which is where `GlobalEntityIndex#reconcileTrigramIndexAbsence` observes
			// it. What that call must NOT do is take the id column off a populated tree: the drop dirties no leaf
			// page, so the ids already written would outlive it on disk while the root's high-water returned to
			// unassigned, and the loader refuses that pairing outright - the catalog would stop opening. It keeps the
			// column and drops only the consumer, which is why the write below still finds a tree that stamps the
			// value it is asked to stamp.
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
						)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iPhone 15")
					);
				}
			);

			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::filterable)
						.updateVia(session);
					// writing THROUGH the tree the drop just touched is the part that matters: a tree left disagreeing
					// with its own consumer registry fails on the next value it is asked to stamp, not on the drop
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2).setAttribute(ATTRIBUTE_NAME, "Pixel 9")
					);
				}
			);

			AttributeFilterAcceleratorRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEquals(
						"iPhone 15",
						session.getEntity(Entities.PRODUCT, 1, attributeContentAll())
							.orElseThrow().getAttribute(ATTRIBUTE_NAME),
						"the entity written before the capability was dropped must survive the drop"
					);
					assertEquals(
						"Pixel 9",
						session.getEntity(Entities.PRODUCT, 2, attributeContentAll())
							.orElseThrow().getAttribute(ATTRIBUTE_NAME),
						"the collection must still accept writes after the capability was dropped"
					);
				}
			);
		}

		@Test
		@DisplayName("should keep the original attribute when one carrying the capability is renamed")
		void shouldKeepOriginalAttributeWhenOneCarryingTheCapabilityIsRenamed() {
			// the premise of the test below, asserted rather than assumed: `ModifyAttributeSchemaNameMutation` does
			// not remove what it renames. `EntityAttributeSchemaMutation#replaceAttributeIfDifferent` filters the
			// existing attributes by the *updated* name, so for a rename nothing is filtered out and the schema ends
			// up carrying both. That is a pre-existing defect of the rename mutation and has nothing to do with
			// filter index capabilities - but it is what makes the refusal below correct rather than a false
			// positive, so it is pinned here. Fix the duplication and this test fails, which is the intent: the
			// refusal below has to be revisited in the same breath.
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_CODE, String.class,
							whichIs -> whichIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH).nullable()
						)
						.updateVia(session);
					// renamed while still empty, so the refusal cannot be what we observe here
					session.updateEntitySchema(
						new ModifyEntitySchemaMutation(
							Entities.PRODUCT,
							new ModifyAttributeSchemaNameMutation(ATTRIBUTE_CODE, "productCode")
						)
					);
				}
			);

			AttributeFilterAcceleratorRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EntitySchemaContract schema = session.getEntitySchemaOrThrow(Entities.PRODUCT);
					assertTrue(
						schema.getAttribute("productCode").orElseThrow()
							.getAcceleratorsInScope(Scope.LIVE)
							.contains(AttributeFilterAccelerator.SUBSTRING_SEARCH)
					);
					assertTrue(
						schema.getAttribute(ATTRIBUTE_CODE).isPresent(),
						"the renamed-from attribute is gone - the rename mutation no longer duplicates, so the " +
							"refusal on a populated collection has become a false positive and needs revisiting"
					);
				}
			);
		}

		@Test
		@DisplayName("should refuse renaming an attribute that declares the capability on a populated collection")
		void shouldRefuseRenamingAnAttributeThatDeclaresTheCapabilityOnPopulatedCollection() {
			// not a false positive, however much it reads like one: because the rename above leaves the original in
			// place, the resulting schema genuinely holds a *second* attribute declaring the capability, and that
			// one's index would have to be built over entities that are already stored. Refusing is the only honest
			// answer available while the rename duplicates. The advice in the message is what reads oddly here, and
			// that is a symptom of the duplication rather than of this check.
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// declared while the collection is still empty, which is the supported way to get the capability
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_CODE, String.class,
							whichIs -> whichIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH).nullable()
						)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_CODE, "iphone-15")
					);
				}
			);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateEntitySchema(
							new ModifyEntitySchemaMutation(
								Entities.PRODUCT,
								new ModifyAttributeSchemaNameMutation(ATTRIBUTE_CODE, "productCode")
							)
						);
					}
				)
			);
			// pinned on the attribute name as well: it must be the *new* attribute that is refused, since that is
			// the one whose index does not exist. A refusal naming the original would mean something else fired
			assertTrue(exception.getMessage().contains("productCode"));
			assertTrue(exception.getMessage().contains("already contains entities"));
		}
	}

	@Nested
	@DisplayName("declared after the catalog went live")
	@Tag(TRANSACTION)
	class AfterGoingLive {

		@Test
		@DisplayName("should still refuse the capability on a populated collection")
		void shouldStillRefuseCapabilityOnPopulatedCollectionAfterGoingLive() {
			// every other test here alters the schema of a warm-up catalog, where the emptiness the refusal rests on
			// is answered differently: `EntityCollection#isEmpty` delegates to the persistence service at the
			// catalog's version, which is a genuinely different read once the catalog is transactional. This whole
			// test class exists for that refusal, so it has to be proven on this side of go-live too
			goLiveWithPlainFilterableAttributeAndOneEntity();

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withAttribute(
								ATTRIBUTE_NAME, String.class,
								whichIs -> whichIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH).nullable()
							)
							.updateVia(session);
					},
					CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
				)
			);
			assertTrue(exception.getMessage().contains(AttributeFilterAccelerator.SUBSTRING_SEARCH.name()));
		}

		@Test
		@DisplayName("should still accept the capability on an empty collection")
		void shouldStillAcceptCapabilityOnEmptyCollectionAfterGoingLive() {
			// the positive control for the test above - without it that one would pass just as happily against a
			// refusal that fired unconditionally in transactional mode, which would be the worse of the two bugs
			goLiveWithEmptyProductCollection();

			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH).nullable()
						)
						.updateVia(session);
				},
				CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
			);

			AttributeFilterAcceleratorRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEquals(
						Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getAccelerators()
					);
				}
			);
		}

		@Test
		@DisplayName("should refuse the capability when an entity was upserted earlier in the same transaction")
		void shouldRefuseTheCapabilityWhenEntitiesWereInsertedEarlierInTheSameTransaction() {
			// the sharpest case of all, because the upsert belongs to the version being *prepared* rather than to the
			// committed one. Were the emptiness question answered from the committed catalog the collection would
			// still read as empty here, the capability would be let through, and the transaction would commit a
			// collection whose entities predate the index meant to cover them - the exact state the refusal exists to
			// make unreachable. It is answered from the transaction's own view instead, so the refusal fires.
			//
			// Its counterfactual is `shouldStillAcceptCapabilityOnEmptyCollectionAfterGoingLive` above: identical
			// setup and identical alteration, differing only in this upsert, and it is accepted. The pair is what
			// makes this a test of the upsert being seen rather than of a refusal that fires whenever it can.
			goLiveWithEmptyProductCollection();

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.upsertEntity(
							session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iPhone 15")
						);
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withAttribute(
								ATTRIBUTE_NAME, String.class,
								whichIs -> whichIs.filterable().acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH).nullable()
							)
							.updateVia(session);
					},
					CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
				)
			);
			// asserted on the message, not on the type alone: a transactional session has other reasons to refuse a
			// schema alteration, and any of them would satisfy a bare `assertThrows` while proving nothing
			assertTrue(exception.getMessage().contains(AttributeFilterAccelerator.SUBSTRING_SEARCH.name()));
			assertTrue(exception.getMessage().contains("already contains entities"));
		}

		/**
		 * Defines the product schema with a plainly filterable `name` attribute, stores one entity in it and takes the
		 * catalog live, so that a test meets the transactional emptiness read rather than the warm-up one.
		 */
		private void goLiveWithPlainFilterableAttributeAndOneEntity() {
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().nullable())
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iPhone 15")
					);
					session.goLiveAndClose();
				}
			);
		}

		/**
		 * Defines the product schema with a plainly filterable `name` attribute and takes the catalog live without
		 * storing anything, so that the collection is empty on the transactional side.
		 */
		private void goLiveWithEmptyProductCollection() {
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().nullable())
						.updateVia(session);
					session.goLiveAndClose();
				}
			);
		}
	}

	@Nested
	@DisplayName("submitted as a raw schema mutation")
	class RawMutations {

		@Test
		@DisplayName("should refuse an accelerator declared on an attribute with no filter index")
		void shouldRefuseAnAcceleratorDeclaredOnAnAttributeWithNoFilterIndex() {
			// the builder cannot express this state at all - it refuses the chain while assembling it - so the
			// mutation has to be submitted raw. Every other route into the schema (gRPC, REST, GraphQL, the WAL)
			// carries mutations the same way, which is what makes this the shape worth pinning
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::nullable)
						.updateVia(session);
				}
			);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateEntitySchema(
							new ModifyEntitySchemaMutation(
								Entities.PRODUCT,
								new SetAttributeSchemaAcceleratedMutation(
									ATTRIBUTE_CODE,
									new ScopedAttributeFilterAccelerators(
										Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH
									)
								)
							)
						);
					}
				)
			);
			// asserted on the message rather than on the type alone: the collection-emptiness refusal throws the
			// same exception, and it would satisfy a bare assertThrows while proving something else entirely
			assertTrue(exception.getMessage().contains(AttributeFilterAccelerator.SUBSTRING_SEARCH.name()));
			assertTrue(exception.getMessage().contains("no filter index"));

			// reopening over the same storage directory is the only honest way to ask what the refused session
			// actually left behind - the in-memory catalog would answer for a schema that was never written
			AttributeFilterAcceleratorRefusalTest.this.evita.close();
			AttributeFilterAcceleratorRefusalTest.this.evita = new Evita(configuration());

			AttributeFilterAcceleratorRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// the refusal above precedes the refused session's own write - a warming-up close validates
					// before `Catalog#flush` - but it cannot take back the schema exchange that
					// `EntityCollection#updateSchema` has already performed on the running catalog, and warm-up
					// catalog termination flushes whatever sits in memory without consulting the same rule. So a
					// clean shutdown still writes the orphan out, and the reopened catalog is past the schema
					// version gate that decides whether to validate at all, which is why it loads without
					// complaint. Asserted rather than left to be discovered: closing the gap needs an undo for a
					// warm-up schema exchange, which does not exist today, and validating each
					// `updateEntitySchema` batch instead would refuse the legitimate sequence of declaring the
					// accelerator in one call and the filterability that licenses it in the next. Tracked as #1466 -
					// flip this assertion to `Set.of()` when that issue is closed
					assertEquals(
						Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getAttribute(ATTRIBUTE_CODE).orElseThrow()
							.getAcceleratorsInScope(Scope.LIVE)
					);
				}
			);
		}
	}

	/**
	 * Defines a product schema carrying a plainly filterable reference attribute and stores one product referencing a
	 * category, so that the collection is non-empty when a test then tries to add a capability to that attribute.
	 */
	private void populateWithFilterableReferenceAttribute() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(
								ATTRIBUTE_CODE, String.class,
								thatIs -> thatIs.filterable().nullable()
							)
					)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, CATEGORY_PK));
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1).setReference(REFERENCE_CATEGORIES, CATEGORY_PK)
				);
			}
		);
	}

	/**
	 * Defines the product schema with a plainly filterable `name` attribute and stores one entity in it, so that the
	 * collection is non-empty when the test then tries to add a capability.
	 */
	private void populateWithPlainFilterableAttribute() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::filterable)
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iPhone 15")
				);
			}
		);
	}

	/**
	 * Builds the throw-away embedded configuration this test runs against.
	 *
	 * @return the configuration; never null
	 */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

}
