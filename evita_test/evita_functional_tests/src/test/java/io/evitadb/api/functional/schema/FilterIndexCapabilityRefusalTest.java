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
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedFilterCapabilities;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaFilterableMutation;
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

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the refusal that protects users from a silently-incomplete substring index.
 *
 * The index backing a {@link FilterIndexCapability} is built incrementally as entities are indexed, and no reindexing
 * machinery exists that could back-fill one for entities already stored. Declaring the capability on a populated
 * collection would therefore produce an index that answers only for entities written *after* the schema change -
 * queries would silently return fewer results than they should. The engine refuses instead, at the one place every
 * route into the schema passes through.
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
class FilterIndexCapabilityRefusalTest implements EvitaTestSupport {

	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final int CATEGORY_PK = 1;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FilterIndexCapabilityRefusal");
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
			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs.filterable(FilterIndexCapability.SUBSTRING)
						)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iPhone 15")
					);
				}
			);

			FilterIndexCapabilityRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEquals(
						Set.of(FilterIndexCapability.SUBSTRING),
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getFilterCapabilities()
					);
				}
			);
		}

		@Test
		@DisplayName("should accept the capability on a collection emptied of all its entities")
		void shouldAcceptCapabilityOnEmptiedCollection() {
			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
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
			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(Entities.PRODUCT, 1);
				}
			);

			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs.filterable(FilterIndexCapability.SUBSTRING).nullable()
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
				() -> FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withAttribute(
								ATTRIBUTE_NAME, String.class,
								whichIs -> whichIs.filterable(FilterIndexCapability.SUBSTRING)
							)
							.updateVia(session);
					}
				)
			);
			assertTrue(exception.getMessage().contains(FilterIndexCapability.SUBSTRING.name()));
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
				() -> FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withAttribute(
								"description", String.class,
								whichIs -> whichIs.filterable(FilterIndexCapability.SUBSTRING).nullable()
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
				() -> FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withAttribute(
								ATTRIBUTE_NAME, String.class,
								whichIs -> whichIs.filterableInScope(
									new ScopedFilterCapabilities(
										Scope.ARCHIVED, FilterIndexCapability.SUBSTRING
									),
									new ScopedFilterCapabilities(Scope.LIVE)
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
				() -> FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
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
										thatIs -> thatIs.filterable(FilterIndexCapability.SUBSTRING).nullable()
									)
							)
							.updateVia(session);
					}
				)
			);
			assertTrue(exception.getMessage().contains(REFERENCE_CATEGORIES));
			assertTrue(exception.getMessage().contains(FilterIndexCapability.SUBSTRING.name()));
			// the message must point at the way out, not merely refuse
			assertTrue(exception.getMessage().contains("entity attributes only"));
		}

		@Test
		@DisplayName("should refuse the capability on a reference attribute of a populated collection")
		void shouldRefuseCapabilityOnReferenceAttributeOfPopulatedCollection() {
			populateWithFilterableReferenceAttribute();

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withReferenceToEntity(
								REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
								whichIs -> whichIs.withAttribute(
									ATTRIBUTE_CODE, String.class,
									thatIs -> thatIs.filterable(FilterIndexCapability.SUBSTRING).nullable()
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

			FilterIndexCapabilityRefusalTest.this.evita.queryCatalog(
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
			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
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
				() -> FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateCatalogSchema(
							SetAttributeSchemaFilterableMutation.fromCapabilities(
								ATTRIBUTE_NAME,
								new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
							)
						);
					}
				)
			);

			// the whole cascade must have been refused - not the catalog schema only, and not "all but the first
			// collection visited". Every one of the three schemas has to read exactly as it did before.
			FilterIndexCapabilityRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(
						session.getCatalogSchema()
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getFilterCapabilities().isEmpty(),
						"the catalog schema must not keep the refused capability"
					);
					assertTrue(
						session.getEntitySchemaOrThrow(Entities.CATEGORY)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getFilterCapabilities().isEmpty(),
						"the empty collection must not keep the capability the populated one refused"
					);
					assertTrue(
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getFilterCapabilities().isEmpty(),
						"the populated collection must be unchanged"
					);
				}
			);
		}

		@Test
		@DisplayName("should apply the cascade to every collection when all of them are empty")
		void shouldApplyCascadeToEveryCollectionWhenAllAreEmpty() {
			// the positive control - the preflight must not turn a legal cascade into a refusal
			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
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

			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateCatalogSchema(
						SetAttributeSchemaFilterableMutation.fromCapabilities(
							ATTRIBUTE_NAME,
							new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
						)
					);
				}
			);

			FilterIndexCapabilityRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEquals(
						Set.of(FilterIndexCapability.SUBSTRING),
						session.getEntitySchemaOrThrow(Entities.CATEGORY)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getFilterCapabilities()
					);
					assertEquals(
						Set.of(FilterIndexCapability.SUBSTRING),
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getFilterCapabilities()
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
			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
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
		@DisplayName("should allow removing the capability from a populated collection")
		void shouldAllowRemovingCapabilityFromPopulatedCollection() {
			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs.filterable(FilterIndexCapability.SUBSTRING)
						)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iPhone 15")
					);
				}
			);

			// dropping an index needs no data, so the refusal is deliberately one-directional
			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable())
						.updateVia(session);
				}
			);

			FilterIndexCapabilityRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getFilterCapabilities().isEmpty()
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
			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_CODE, String.class,
							whichIs -> whichIs.filterable(FilterIndexCapability.SUBSTRING).nullable()
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

			FilterIndexCapabilityRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EntitySchemaContract schema = session.getEntitySchemaOrThrow(Entities.PRODUCT);
					assertTrue(
						schema.getAttribute("productCode").orElseThrow()
							.getFilterCapabilitiesInScope(Scope.LIVE)
							.contains(FilterIndexCapability.SUBSTRING)
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
			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// declared while the collection is still empty, which is the supported way to get the capability
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(
							ATTRIBUTE_CODE, String.class,
							whichIs -> whichIs.filterable(FilterIndexCapability.SUBSTRING).nullable()
						)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_CODE, "iphone-15")
					);
				}
			);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
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
				() -> FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withAttribute(
								ATTRIBUTE_NAME, String.class,
								whichIs -> whichIs.filterable(FilterIndexCapability.SUBSTRING).nullable()
							)
							.updateVia(session);
					},
					CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
				)
			);
			assertTrue(exception.getMessage().contains(FilterIndexCapability.SUBSTRING.name()));
		}

		@Test
		@DisplayName("should still accept the capability on an empty collection")
		void shouldStillAcceptCapabilityOnEmptyCollectionAfterGoingLive() {
			// the positive control for the test above - without it that one would pass just as happily against a
			// refusal that fired unconditionally in transactional mode, which would be the worse of the two bugs
			goLiveWithEmptyProductCollection();

			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntitySchemaOrThrow(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(
							ATTRIBUTE_NAME, String.class,
							whichIs -> whichIs.filterable(FilterIndexCapability.SUBSTRING).nullable()
						)
						.updateVia(session);
				},
				CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
			);

			FilterIndexCapabilityRefusalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEquals(
						Set.of(FilterIndexCapability.SUBSTRING),
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getAttribute(ATTRIBUTE_NAME).orElseThrow()
							.getFilterCapabilities()
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
				() -> FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.upsertEntity(
							session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_NAME, "iPhone 15")
						);
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withAttribute(
								ATTRIBUTE_NAME, String.class,
								whichIs -> whichIs.filterable(FilterIndexCapability.SUBSTRING).nullable()
							)
							.updateVia(session);
					},
					CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
				)
			);
			// asserted on the message, not on the type alone: a transactional session has other reasons to refuse a
			// schema alteration, and any of them would satisfy a bare `assertThrows` while proving nothing
			assertTrue(exception.getMessage().contains(FilterIndexCapability.SUBSTRING.name()));
			assertTrue(exception.getMessage().contains("already contains entities"));
		}

		/**
		 * Defines the product schema with a plainly filterable `name` attribute, stores one entity in it and takes the
		 * catalog live, so that a test meets the transactional emptiness read rather than the warm-up one.
		 */
		private void goLiveWithPlainFilterableAttributeAndOneEntity() {
			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
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
			FilterIndexCapabilityRefusalTest.this.evita.updateCatalog(
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
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable())
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
