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
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaAcceleratedMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.trigram.TrigramCodec;
import io.evitadb.index.trigram.TrigramIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.TreeSet;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContains;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how a schema change declaring a {@link AttributeFilterAccelerator} behaves - above all over a collection that
 * already holds entities.
 *
 * **Such a change is accepted, never refused.** Bringing indexes in line with a changed schema is the reindexing work
 * of issue #409; until it exists evitaDB accepts every schema change and leaves what is already indexed in its old
 * shape. For the substring accelerator that shape is a *dormant* one: an attribute whose shared value tree already
 * holds values gets no accelerator, and `attributeContains` keeps scanning it. That is slower but never wrong - and
 * "never wrong" is what these tests assert first, by querying the values written before and after the declaration and
 * by reopening the catalog, which used to fail on exactly this shape. (Until 2026-10 the engine refused the change; the
 * class keeps its name because decision records cite its methods.)
 *
 * The declarations that ARE refused are refused for reasons that have nothing to do with data - an accelerator on a
 * reference attribute, or on an attribute with no filter index - and are pinned here too.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Filter index accelerator declared by a schema change")
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
	/**
	 * The substring every value written by the populated-collection tests contains. Values are lower-case ASCII on
	 * purpose: the shared value tree stores them unchanged, so the trigrams asserted below are exactly theirs.
	 */
	private static final String COMMON_SUBSTRING = "phone";

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
		@DisplayName("should accept the capability on an empty collection and serve from the first entity")
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
			assertNotNull(
				trigramIndexOf(ATTRIBUTE_NAME),
				"declared before the first value, the accelerator must be active - the positive control for the " +
					"dormant cases below"
			);
		}

		@Test
		@DisplayName("should serve from the next write once the collection was emptied of all its entities")
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
			// removing the existing entities drops the attribute's value tree, which is what ends dormancy
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
							whichIs -> whichIs
								.filterable()
								.acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
								.nullable()
						)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2).setAttribute(ATTRIBUTE_NAME, "pixel phone")
					);
				}
			);
			assertNotNull(trigramIndexOf(ATTRIBUTE_NAME));
		}
	}

	@Nested
	@DisplayName("declared on a populated collection")
	class DeclaredOverStoredEntities {

		@Test
		@DisplayName("should accept the capability, keep it dormant and keep answering every value")
		void shouldAcceptAddingCapabilityToExistingEntityAttributeAndKeepItDormant() {
			populateWithPlainFilterableAttribute();

			declareSubstringAccelerator();
			// the write that follows the declaration is the one the refusal used to shield: attaching the accelerator
			// to the populated tree would have failed it with an internal error
			upsertProductName(2, "pixel phone");

			assertEquals(
				Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
				acceleratorsOfName(Scope.LIVE)
			);
			assertNull(
				trigramIndexOf(ATTRIBUTE_NAME),
				"an index created now would miss the value stored before the declaration - it must stay dormant"
			);
			assertEquals(Set.of(1, 2), productsContaining(COMMON_SUBSTRING));
		}

		@Test
		@DisplayName("should open the catalog again with the dormant accelerator and keep answering every value")
		void shouldOpenTheCatalogAgainWithTheDormantAccelerator() {
			populateWithPlainFilterableAttribute();
			declareSubstringAccelerator();
			upsertProductName(2, "pixel phone");

			// the load used to treat a populated tree carrying no value ids under a declaring attribute as corruption
			// and fail the catalog - that is exactly the shape an accelerator declared over stored values comes back in
			reopenOverTheSameStorage();

			assertNull(
				trigramIndexOf(ATTRIBUTE_NAME), "the tree still carries no ids, so the accelerator stays dormant"
			);
			assertEquals(Set.of(1, 2), productsContaining(COMMON_SUBSTRING));
		}

		@Test
		@DisplayName("should serve a new attribute declaring the capability from its first value")
		void shouldAcceptCreatingNewAttributeDeclaringCapability() {
			populateWithPlainFilterableAttribute();

			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(
							ATTRIBUTE_CODE, String.class,
							whichIs -> whichIs
								.filterable()
								.acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
								.nullable()
						)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2)
							.setAttribute(ATTRIBUTE_NAME, "pixel phone")
							.setAttribute(ATTRIBUTE_CODE, "abcdef")
					);
				}
			);

			// no entity held a value of the brand new attribute, so its tree was empty when the accelerator attached
			final TrigramIndex codeIndex = trigramIndexOf(ATTRIBUTE_CODE);
			assertNotNull(codeIndex, "a declaration over an empty tree is active, whatever else the collection holds");
			assertEquals(1, codeIndex.cardinalityOf(trigram("abc")));
		}

		@Test
		@DisplayName("should accept the capability in the archived scope")
		void shouldAcceptAddingCapabilityInArchivedScope() {
			populateWithPlainFilterableAttribute();

			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs
								.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
								.acceleratedForInScope(Scope.ARCHIVED, AttributeFilterAccelerator.SUBSTRING_SEARCH)
						)
						.updateVia(session);
				}
			);

			assertEquals(
				Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
				acceleratorsOfName(Scope.ARCHIVED)
			);
		}

		@Test
		@DisplayName("should derive the accelerator from the ids a withdrawn declaration left behind, at the next load")
		void shouldDeriveTheAcceleratorFromTheKeptIdsAfterAWithdrawal() {
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
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iphone phone")
					);
				}
			);
			// withdrawn, and written through: that write drops the accelerator, while the populated tree keeps its ids
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
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2).setAttribute(ATTRIBUTE_NAME, "pixel phone")
					);
				}
			);
			assertNull(trigramIndexOf(ATTRIBUTE_NAME));

			declareSubstringAccelerator();
			upsertProductName(3, "galaxy phone");
			// a fresh index here would hold only the value written from now on - the two stored ones would vanish from
			// every accelerated substring query, so the write path leaves it dormant
			assertNull(trigramIndexOf(ATTRIBUTE_NAME));
			assertEquals(Set.of(1, 2, 3), productsContaining(COMMON_SUBSTRING));

			reopenOverTheSameStorage();

			final TrigramIndex derived = trigramIndexOf(ATTRIBUTE_NAME);
			assertNotNull(derived, "the tree kept its ids through the withdrawal, so the load derives the accelerator");
			assertEquals(1, derived.cardinalityOf(trigram("iph")), "the value stored before the withdrawal is posted");
			assertEquals(1, derived.cardinalityOf(trigram("pix")), "the value written while withdrawn is posted");
			assertEquals(1, derived.cardinalityOf(trigram("gal")), "the value written while dormant is posted");
			assertEquals(Set.of(1, 2, 3), productsContaining(COMMON_SUBSTRING));
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
										thatIs -> thatIs
											.filterable()
											.acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
											.nullable()
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
									thatIs -> thatIs
										.filterable()
										.acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
										.nullable()
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
		@DisplayName("should apply the cascade to every collection, populated or not")
		void shouldApplyCascadeToEveryCollectionHoweverPopulated() {
			// a catalog-level change to a global attribute fans out into one mutation per consuming collection; with no
			// collection refusing its share any more, an empty CATEGORY and a populated PRODUCT must both take it
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
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iphone phone")
					);
				}
			);

			applySubstringAcceleratorToTheGlobalAttribute();

			AttributeFilterAcceleratorRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEquals(
						Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
						session.getCatalogSchema().getAttribute(ATTRIBUTE_NAME).orElseThrow().getAccelerators()
					);
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
			assertEquals(Set.of(1), productsContaining(COMMON_SUBSTRING));
		}

		@Test
		@DisplayName("should apply the cascade to every collection when all of them are empty")
		void shouldApplyCascadeToEveryCollectionWhenAllAreEmpty() {
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

			applySubstringAcceleratorToTheGlobalAttribute();

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

		/**
		 * Declares the substring accelerator on the catalog-level `name` attribute, which cascades into every
		 * collection that uses it.
		 */
		private void applySubstringAcceleratorToTheGlobalAttribute() {
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
		}
	}

	@Nested
	@DisplayName("unrelated schema changes")
	class UnrelatedChanges {

		@Test
		@DisplayName("should allow an ordinary schema change on a populated collection")
		void shouldAllowOrdinarySchemaChangeOnPopulatedCollection() {
			populateWithPlainFilterableAttribute();

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
			// moment CATEGORY's own mutation is applied the reflected reference is still UNRESOLVED. It also declares
			// attributes of its own alongside an inheritance filter, which is what makes
			// `ReflectedReferenceSchema#getAttributes` throw rather than answer empty while the target is missing - any
			// check walking the collection's attributes during the schema change must cope with that shape
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

			// withdrawal has to be stated explicitly - restating `filterable()` says nothing about the accelerator
			// axis, which is exactly what stops an unrelated schema edit from silently deleting an index
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
			// The end-to-end shape of the value id drop path. The trigram substring index DOES register a value id
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
			// `ModifyAttributeSchemaNameMutation` does not remove what it renames.
			// `EntityAttributeSchemaMutation#replaceAttributeIfDifferent` filters the existing attributes by the
			// *updated* name, so for a rename nothing is filtered out and the schema ends up carrying both. That is a
			// pre-existing defect of the rename mutation and has nothing to do with filter accelerators - pinned here
			// because the next test reasons about the duplicate it creates
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_CODE, String.class,
							whichIs -> whichIs
								.filterable()
								.acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
								.nullable()
						)
						.updateVia(session);
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
						"the renamed-from attribute is gone - the rename mutation no longer duplicates, so the test " +
							"below no longer exercises a second accelerated attribute and needs revisiting"
					);
				}
			);
		}

		@Test
		@DisplayName("should accept renaming an attribute that declares the capability on a populated collection")
		void shouldAcceptRenamingAnAttributeThatDeclaresTheCapabilityOnPopulatedCollection() {
			// the duplicating rename creates a second accelerated attribute on a populated collection - accepted, and
			// the stored value keeps being found through the attribute that actually holds it
			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_CODE, String.class,
							whichIs -> whichIs
								.filterable()
								.acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
								.nullable()
						)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_CODE, "iphone-15")
					);
				}
			);

			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
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
					assertTrue(
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getAttribute("productCode").orElseThrow()
							.getAcceleratorsInScope(Scope.LIVE)
							.contains(AttributeFilterAccelerator.SUBSTRING_SEARCH)
					);
				}
			);
			assertEquals(Set.of(1), productsWhose(ATTRIBUTE_CODE, "phone"));
		}
	}

	@Nested
	@DisplayName("declared after the catalog went live")
	@Tag(TRANSACTION)
	class AfterGoingLive {

		@Test
		@DisplayName("should accept the capability on a populated collection and keep accepting writes")
		void shouldAcceptCapabilityOnPopulatedCollectionAfterGoingLive() {
			// inside a transaction the attach to a populated tree used to fail every later write to the attribute with
			// an internal error - the write path's back-fill refuses to run in a transaction. Dormancy never attaches
			goLiveWithPlainFilterableAttributeAndOneEntity();

			declareSubstringAccelerator();
			upsertProductName(2, "pixel phone");

			assertNull(trigramIndexOf(ATTRIBUTE_NAME));
			assertEquals(Set.of(1, 2), productsContaining(COMMON_SUBSTRING));

			reopenOverTheSameStorage();
			assertEquals(Set.of(1, 2), productsContaining(COMMON_SUBSTRING));
		}

		@Test
		@DisplayName("should accept the capability on an empty collection and serve from the first entity")
		void shouldStillAcceptCapabilityOnEmptyCollectionAfterGoingLive() {
			goLiveWithEmptyProductCollection();

			declareSubstringAccelerator();
			upsertProductName(1, "iphone phone");

			assertEquals(
				Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
				acceleratorsOfName(Scope.LIVE)
			);
			assertNotNull(trigramIndexOf(ATTRIBUTE_NAME), "an empty tree attaches inside a transaction just as well");
		}

		@Test
		@DisplayName("should accept the capability when an entity was upserted earlier in the same transaction")
		void shouldAcceptTheCapabilityWhenEntitiesWereInsertedEarlierInTheSameTransaction() {
			// the upsert belongs to the version being prepared, so the tree is already populated within the transaction
			// when the next write meets the declaration - dormant, and every value still found
			goLiveWithEmptyProductCollection();

			AttributeFilterAcceleratorRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iphone phone")
					);
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs
								.filterable()
								.acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
								.nullable()
						)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2).setAttribute(ATTRIBUTE_NAME, "pixel phone")
					);
				},
				CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
			);

			assertNull(trigramIndexOf(ATTRIBUTE_NAME));
			assertEquals(Set.of(1, 2), productsContaining(COMMON_SUBSTRING));
		}

		/**
		 * Defines the product schema with a plainly filterable `name` attribute, stores one entity in it and takes the
		 * catalog live, so that a test meets the transactional write path rather than the warm-up one.
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
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iphone phone")
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

			// the barrier is asserted explicitly, not merely the exception: `SetAttributeSchemaAcceleratedMutation`
			// deliberately skips its own pre-flight applicability check today (its sibling
			// `CreateAttributeSchemaMutation` already has one), so a refusal here is confirmed post-exchange rather
			// than assumed - see CatalogUnpublishableBarrierAssertions
			final InvalidSchemaMutationException exception =
				CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
					AttributeFilterAcceleratorRefusalTest.this.evita, TEST_CATALOG,
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
			// asserted on the message rather than on the type alone: a refusal of another rule would satisfy a bare
			// assertThrows while proving something else entirely
			assertTrue(exception.getMessage().contains(AttributeFilterAccelerator.SUBSTRING_SEARCH.name()));
			assertTrue(exception.getMessage().contains("no filter index"));

			// reopening over the same storage directory is the only honest way to ask what the refused session
			// actually left behind - the in-memory catalog would answer for a schema that was never written. The
			// barrier assertion above already waited for the deactivation to settle, so unlike the general case
			// `reopenOverTheSameStorage` documents, this reopen is not racing it
			AttributeFilterAcceleratorRefusalTest.this.reopenOverTheSameStorage();

			AttributeFilterAcceleratorRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// the refusal precedes the refused session's own write - a warming-up close validates before
					// `Catalog#flush` - and it cannot take back the schema exchange that
					// `EntityCollection#updateSchema` has already performed on the running catalog. What keeps that
					// exchange off the disk is the unpublishable barrier the refusal raises: warm-up catalog
					// termination consults it and skips its flush, as does every other route that would write a
					// bootstrap record. The reopened catalog therefore carries the newest state that reached the
					// disk. See WarmUpRefusedSchemaPersistenceTest for the same guarantee proven on a second,
					// unrelated validation rule
					assertEquals(
						Set.of(),
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getAttribute(ATTRIBUTE_CODE).orElseThrow()
							.getAcceleratorsInScope(Scope.LIVE)
					);
				}
			);
		}
	}

	/**
	 * Packs a three-character lower-case ASCII string into the trigram key the index posts under.
	 *
	 * @param text the three characters of the trigram
	 * @return the packed key
	 */
	private static long trigram(@Nonnull String text) {
		return TrigramCodec.pack(text.charAt(0), text.charAt(1), text.charAt(2));
	}

	/**
	 * @param scope the scope to read the declaration of
	 * @return the accelerators the product's `name` attribute declares in `scope`, as the schema reads now
	 */
	@Nonnull
	private Set<AttributeFilterAccelerator> acceleratorsOfName(@Nonnull Scope scope) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchemaOrThrow(Entities.PRODUCT)
					.getAttribute(ATTRIBUTE_NAME).orElseThrow()
					.getAcceleratorsInScope(scope);
			}
		);
	}

	/**
	 * Declares the substring accelerator on the product's `name` attribute, keeping it filterable.
	 */
	private void declareSubstringAccelerator() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrow(Entities.PRODUCT)
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_NAME, String.class,
						whichIs -> whichIs
							.filterable()
							.acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
							.nullable()
					)
					.updateVia(session);
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
	}

	/**
	 * Stores a product carrying the given `name`.
	 *
	 * @param primaryKey the product's primary key
	 * @param name       the value of its `name` attribute
	 */
	private void upsertProductName(int primaryKey, @Nonnull String name) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, primaryKey).setAttribute(ATTRIBUTE_NAME, name)
				);
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
	}

	/**
	 * @param substring the text to look for
	 * @return primary keys of the products whose `name` contains `substring`
	 */
	@Nonnull
	private Set<Integer> productsContaining(@Nonnull String substring) {
		return productsWhose(ATTRIBUTE_NAME, substring);
	}

	/**
	 * @param attributeName the attribute to search
	 * @param substring     the text to look for
	 * @return primary keys of the products whose attribute contains `substring`
	 */
	@Nonnull
	private Set<Integer> productsWhose(@Nonnull String attributeName, @Nonnull String substring) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> primaryKeys = new TreeSet<>();
				for (final EntityReferenceContract reference : session.queryListOfEntityReferences(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeContains(attributeName, substring))
					)
				)) {
					primaryKeys.add(reference.getPrimaryKey());
				}
				return primaryKeys;
			}
		);
	}

	/**
	 * Returns the substring accelerator the live global index of the product collection currently holds for an
	 * attribute, read from the catalog instance the next query would see.
	 *
	 * @param attributeName the language-agnostic entity attribute
	 * @return the accelerator, or `null` when none is built - never declared, withdrawn, or dormant
	 */
	@Nullable
	private TrigramIndex trigramIndexOf(@Nonnull String attributeName) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT)
			.orElseThrow();
		final GlobalEntityIndex globalIndex = collection.getGlobalIndexIfExists().orElseThrow();
		return globalIndex.getTrigramIndex(new AttributeIndexKey(null, attributeName, null));
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
	 * collection is non-empty when the test then declares a capability.
	 */
	private void populateWithPlainFilterableAttribute() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().nullable())
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iphone phone")
				);
			}
		);
	}

	/**
	 * Closes the running instance and opens a fresh one over the same storage directory, leaving the catalog loaded
	 * and queryable however the previous instance shut down.
	 *
	 * After a refusal that raised the unpublishable barrier, the barrier schedules a deactivation - so whether this
	 * test's `close()` outruns that deactivation is a race, and both outcomes are correct. When the deactivation lands
	 * first the `INACTIVE` state is persisted and the reopened engine leaves the catalog unloaded; when the close wins,
	 * the catalog comes back loaded. Both sides read the same bootstrap record, which is the state under assertion, so
	 * the reopen simply has to tolerate either.
	 */
	private void reopenOverTheSameStorage() {
		this.evita.close();
		this.evita = new Evita(configuration());
		// deterministic rather than a wait: the constructor schedules the initial catalog load and this joins the
		// futures it created, so the state read below is the settled one
		this.evita.waitUntilFullyInitialized();
		if (this.evita.getCatalogState(TEST_CATALOG).orElse(null) == CatalogState.INACTIVE) {
			CatalogUnpublishableBarrierAssertions.activateWithConflictRetry(this.evita, TEST_CATALOG);
		}
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
