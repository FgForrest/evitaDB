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
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator.ConsistencyChecks;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.functional.reference.ReferenceIndexedComponentsTestSupport.describe;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the schema rule: **every scope in which a reference is indexed must carry
 * {@link ReferenceIndexedComponents#REFERENCED_ENTITY} among its indexed components.**
 *
 * The entity component is what builds the reduced entity indexes, and every query path over a reference -
 * `referenceHaving`, `hierarchyWithin`, `hierarchyOfReference`, `referenceContent` with a filter, `referenceProperty`
 * ordering and `histogramHaving` - reads that family and nothing else. A scope indexed for the group component alone,
 * or indexed with no component at all, reports itself indexed while leaving every one of those paths blind: the
 * queries answered with an empty result, silently, and a negation over them inverted into the whole collection.
 * Answering such queries from the group family instead was rejected - rows without a group are indexed nowhere in
 * it, and group partitions are not injective - so the shape is refused rather than supported.
 *
 * The rule lives in `validate()`, which runs at session close, at `goLive` and before a mid-session flush - and
 * **never** on catalog load, so that a stored catalog that already carries the shape still opens. That placement is
 * why every refusal below is asserted in two halves: the session must get as far as its own end with the invalid
 * schema in place (a construction-time refusal would fail that half and could break loading), and only then does
 * the close refuse it. The one exception is the empty-component shape, which no session can produce any more and which
 * is therefore validated directly, in the form a stored catalog loads it.
 *
 * Every test runs in its own `WARMING_UP` catalog, because a refused warm-up close marks the catalog unpublishable
 * and hands it over for deactivation - a shared catalog would be unusable for every test after the first refusal.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Indexed reference scope must carry the REFERENCED_ENTITY component")
@Tag(ENGINE)
@Tag(SCHEMA)
@Tag(REFERENCE)
class IndexedScopeRequiresEntityComponentTest implements EvitaTestSupport {

	/**
	 * The reference every plain-reference case below declares on the product collection.
	 */
	private static final String REF_CATEGORIES = "categories";
	/**
	 * The reflected reference declared on the category collection, mirroring {@link #REF_CATEGORIES}.
	 */
	private static final String REF_REFLECTED_PRODUCTS = "productsInCategory";
	/**
	 * Group entity type of every reference in this class. Declared on every reference, because the group component is
	 * only meaningful - and only tempting to declare on its own - on a reference that has a group.
	 */
	private static final String CATEGORY_GROUP = "CategoryGroup";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("IndexedScopeRequiresEntityComponent");
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
	@DisplayName("Refused at session close")
	class Refused {

		@Test
		@DisplayName("should refuse a reference indexed only for REFERENCED_GROUP_ENTITY")
		void shouldRefuseGroupOnlyComponentsInTheDefaultScope() {
			final InvalidSchemaMutationException exception = assertRefusedAtClose(
				session -> defineProductWithCategories(
					session,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioning()
						.withGroupTypeRelatedToEntity(CATEGORY_GROUP)
						.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
				),
				session -> assertEquals(
					"LIVE=[REFERENCED_GROUP_ENTITY]",
					describe(session.getEntitySchemaOrThrowException(Entities.PRODUCT)
						.getReferenceOrThrowException(REF_CATEGORIES)),
					"The builder must accept the group-only shape into the session - refusing it at " +
						"construction time would also refuse it on catalog load, and stored catalogs with this " +
						"shape must keep loading"
				)
			);
			assertMessageNames(exception, REF_CATEGORIES, Entities.PRODUCT, Scope.LIVE);
		}

		/**
		 * The rule is per scope, not per reference: a reference that is valid in {@link Scope#LIVE} must still be
		 * refused for the one scope that lacks the entity component, and the message must name that scope - the
		 * user has no way to find it otherwise.
		 */
		@Test
		@DisplayName("should refuse a reference whose ARCHIVED scope is indexed only for the group component")
		void shouldRefuseGroupOnlyComponentsInOneOfTwoIndexedScopes() {
			final InvalidSchemaMutationException exception = assertRefusedAtClose(
				session -> defineProductWithCategories(
					session,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
						.withGroupTypeRelatedToEntity(CATEGORY_GROUP)
						.indexedWithComponentsInScope(Scope.LIVE, ReferenceIndexedComponents.REFERENCED_ENTITY)
						.indexedWithComponentsInScope(
							Scope.ARCHIVED, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						)
				),
				session -> assertEquals(
					"LIVE=[REFERENCED_ENTITY] ARCHIVED=[REFERENCED_GROUP_ENTITY]",
					describe(session.getEntitySchemaOrThrowException(Entities.PRODUCT)
						.getReferenceOrThrowException(REF_CATEGORIES)),
					"The session must hold the per-scope shape the test means to refuse"
				)
			);
			assertMessageNames(exception, REF_CATEGORIES, Entities.PRODUCT, Scope.ARCHIVED);
		}

		/**
		 * A reflected reference is validated by its own `validate()`, which does not call the plain reference's.
		 * Its *effective* components are what the indexer reads, so an explicit group-only declaration on the
		 * reflected side must be refused even though the reference it reflects is perfectly valid.
		 */
		@Test
		@DisplayName("should refuse a reflected reference whose effective components are group-only")
		void shouldRefuseReflectedReferenceWithGroupOnlyComponents() {
			final InvalidSchemaMutationException exception = assertRefusedAtClose(
				session -> {
					defineProductWithCategories(session, IndexedScopeRequiresEntityComponentTest::validBothComponents);
					session.getEntitySchemaOrThrowException(Entities.CATEGORY)
						.openForWrite()
						.withReflectedReferenceToEntity(
							REF_REFLECTED_PRODUCTS, Entities.PRODUCT, REF_CATEGORIES,
							whichIs -> whichIs
								.withAttributesInherited()
								.indexedInScope(Scope.LIVE)
								.indexedWithComponentsInScope(
									Scope.LIVE, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
						)
						.updateVia(session);
				},
				session -> {
					final ReferenceSchemaContract reflected = session
						.getEntitySchemaOrThrowException(Entities.CATEGORY)
						.getReferenceOrThrowException(REF_REFLECTED_PRODUCTS);
					assertTrue(
						reflected instanceof ReflectedReferenceSchemaContract,
						"The premise is a reflected reference, was: " + reflected.getClass().getSimpleName()
					);
					assertEquals(
						"LIVE=[REFERENCED_GROUP_ENTITY]",
						describe(reflected),
						"The reflected reference must carry the group-only shape as its effective components"
					);
				}
			);
			assertMessageNames(exception, REF_REFLECTED_PRODUCTS, Entities.CATEGORY, Scope.LIVE);
		}

		/**
		 * The second shape the rule covers: an indexed scope with no component at all. It builds no reduced index of
		 * either family and answers every reference query with nothing, so it must be refused exactly like the
		 * group-only shape.
		 *
		 * **The shape now arises only from a stored catalog.** Every schema-change route completes an indexed scope
		 * its explicit components leave empty with the default component - plain and reflected references alike - so
		 * no session can produce it any more. Catalogs written before that still store it, though: reflected
		 * references used to acquire it for every scope their explicit components did not name. Loading keeps it
		 * exactly as stored (`SchemaSerializationServiceTest` pins that), so the rule is exercised here on the
		 * schema as it looks after a load - built through the map overload of `_internalBuild` the Kryo reader uses
		 * and bound with the plain {@link ReflectedReferenceSchema#withReferencedSchema} that catalog load performs -
		 * and validated directly, because no session can hold it.
		 */
		@Test
		@DisplayName("should refuse a stored reflected reference indexed with an empty component set")
		void shouldRefuseReflectedReferenceIndexedWithEmptyComponents() {
			final Map<Scope, ReferenceIndexType> liveOnly = new EnumMap<>(Scope.class);
			liveOnly.put(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);
			final ReferenceSchema original = ReferenceSchema._internalBuild(
				REF_CATEGORIES, NamingConvention.generate(REF_CATEGORIES),
				null, null,
				Cardinality.ZERO_OR_MORE,
				Entities.CATEGORY, Collections.emptyMap(), true,
				null, Collections.emptyMap(), false,
				liveOnly,
				Map.of(Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY)),
				Collections.emptySet(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				ConflictResolutionOverride.INHERITED
			);
			final ReflectedReferenceSchema stored = ReflectedReferenceSchema._internalBuild(
				REF_REFLECTED_PRODUCTS, NamingConvention.generate(REF_REFLECTED_PRODUCTS),
				null, null,
				Entities.PRODUCT, REF_CATEGORIES,
				null,
				liveOnly, Map.of(Scope.LIVE, Set.of()), null, null, null, null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				null
			).withReferencedSchema(original);
			assertTrue(stored.isIndexedInScope(Scope.LIVE), "The premise is a reflected reference indexed in LIVE");
			assertEquals(
				Set.of(), stored.getIndexedComponents(Scope.LIVE),
				"The premise is an indexed LIVE scope with no component, kept exactly as stored"
			);

			// validation needs no more of the catalog than the reference the reflected one points at
			final EntitySchemaContract productSchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(productSchema.getReference(REF_CATEGORIES)).thenReturn(Optional.of(original));
			final EntitySchema categorySchema = EntitySchema._internalBuild(Entities.CATEGORY);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.getName()).thenReturn(TEST_CATALOG);
			Mockito.when(catalogSchema.getEntitySchema(Entities.PRODUCT)).thenReturn(Optional.of(productSchema));

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> stored.validate(catalogSchema, categorySchema),
				"A stored indexed scope without REFERENCED_ENTITY must be refused by validation"
			);
			assertMessageNames(exception, REF_REFLECTED_PRODUCTS, Entities.CATEGORY, Scope.LIVE);
		}

		/**
		 * The empty shape on a plain reference, which {@link ReferenceSchema#validate} checks on its own - the reflected
		 * reference above goes through a validation of its own. Built the way a catalog load builds it, through the map
		 * overload of `_internalBuild` the Kryo reader uses, and indexed in `LIVE` with the entity component and in
		 * `ARCHIVED` with none, so the refusal has to name the one scope that lacks it.
		 */
		@Test
		@DisplayName("should refuse a stored plain reference indexed with an empty component set")
		void shouldRefusePlainReferenceIndexedWithEmptyComponents() {
			final Map<Scope, ReferenceIndexType> bothScopes = new EnumMap<>(Scope.class);
			bothScopes.put(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);
			bothScopes.put(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING);
			final Map<Scope, Set<ReferenceIndexedComponents>> components = new EnumMap<>(Scope.class);
			components.put(Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY));
			components.put(Scope.ARCHIVED, Set.of());
			final ReferenceSchema stored = ReferenceSchema._internalBuild(
				REF_CATEGORIES, NamingConvention.generate(REF_CATEGORIES),
				null, null,
				Cardinality.ZERO_OR_MORE,
				Entities.CATEGORY, Collections.emptyMap(), false,
				null, Collections.emptyMap(), false,
				bothScopes,
				components,
				Collections.emptySet(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				ConflictResolutionOverride.INHERITED
			);
			assertEquals(
				"LIVE=[REFERENCED_ENTITY] ARCHIVED=[]",
				describe(stored),
				"The premise is an indexed ARCHIVED scope with no component, kept exactly as stored"
			);

			// the referenced type is not managed, so validation needs nothing of the catalog but its name
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.getName()).thenReturn(TEST_CATALOG);
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> stored.validate(catalogSchema, EntitySchema._internalBuild(Entities.PRODUCT)),
				"A stored indexed scope without REFERENCED_ENTITY must be refused by validation"
			);
			assertMessageNames(exception, REF_CATEGORIES, Entities.PRODUCT, Scope.ARCHIVED);
		}

		/**
		 * A stored reflected reference with explicit scopes, indexed in `ARCHIVED` with no component, switched to
		 * inherited scopes by `indexedInScope((Scope[]) null)` - the mutation that switch emits, followed by the
		 * re-binding the collection performs after every schema change. Neither may complete the stored empty scope:
		 * it never indexed anything, so `REFERENCED_ENTITY` there would claim indexes that were never built and silence
		 * both this rule and the query guard.
		 */
		@Test
		@DisplayName("should keep refusing a stored empty scope after a switch to inherited scopes")
		void shouldKeepRefusingAStoredEmptyScopeAfterSwitchingToInheritedScopes() {
			final Map<Scope, ReferenceIndexType> bothScopes = new EnumMap<>(Scope.class);
			bothScopes.put(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);
			bothScopes.put(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING);
			final ReferenceSchema original = ReferenceSchema._internalBuild(
				REF_CATEGORIES, NamingConvention.generate(REF_CATEGORIES),
				null, null,
				Cardinality.ZERO_OR_MORE,
				Entities.CATEGORY, Collections.emptyMap(), true,
				null, Collections.emptyMap(), false,
				bothScopes,
				Map.of(
					Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY),
					Scope.ARCHIVED, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				ConflictResolutionOverride.INHERITED
			);
			final ReflectedReferenceSchema stored = ReflectedReferenceSchema._internalBuild(
				REF_REFLECTED_PRODUCTS, NamingConvention.generate(REF_REFLECTED_PRODUCTS),
				null, null,
				Entities.PRODUCT, REF_CATEGORIES,
				null,
				bothScopes, Map.of(Scope.LIVE, Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY)),
				null, null, null, null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				null
			).withReferencedSchema(original);
			assertTrue(
				stored.isIndexedInScope(Scope.ARCHIVED), "The premise is a reflected reference indexed in ARCHIVED"
			);
			assertEquals(
				Set.of(), stored.getIndexedComponents(Scope.ARCHIVED),
				"The premise is an indexed ARCHIVED scope with no component, kept exactly as stored"
			);

			final EntitySchema categorySchema = EntitySchema._internalBuild(Entities.CATEGORY);
			final ReflectedReferenceSchema switched = ((ReflectedReferenceSchema) new SetReferenceSchemaIndexedMutation(
				REF_REFLECTED_PRODUCTS, (ScopedReferenceIndexType[]) null, ScopedReferenceIndexedComponents.EMPTY
			).mutate(categorySchema, stored, ConsistencyChecks.SKIP))
				.withReferencedSchemaAfterSchemaChange(original);
			assertTrue(
				switched.isIndexedInherited(), "The premise is a reflected reference now inheriting its scopes"
			);

			// validation needs no more of the catalog than the reference the reflected one points at
			final EntitySchemaContract productSchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(productSchema.getReference(REF_CATEGORIES)).thenReturn(Optional.of(original));
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.getName()).thenReturn(TEST_CATALOG);
			Mockito.when(catalogSchema.getEntitySchema(Entities.PRODUCT)).thenReturn(Optional.of(productSchema));

			assertEquals(
				Set.of(), switched.getIndexedComponents(Scope.ARCHIVED),
				"The stored empty ARCHIVED scope must keep no component"
			);
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> switched.validate(catalogSchema, categorySchema),
				"A stored indexed scope without REFERENCED_ENTITY must stay refused by validation"
			);
			assertMessageNames(exception, REF_REFLECTED_PRODUCTS, Entities.CATEGORY, Scope.ARCHIVED);
		}

	}

	@Nested
	@DisplayName("Accepted at session close")
	class Accepted {

		/**
		 * Every shape the rule must leave alone, in one session: the default components, both components, the two
		 * per-scope combinations that keep the entity component in every indexed scope, and a scope that is not
		 * indexed at all (the rule speaks about indexed scopes only). A rule written too broadly - for example one
		 * that forbade the group component outright, or demanded components in unindexed scopes - refuses this
		 * session.
		 */
		@Test
		@DisplayName("should accept every indexed scope that carries REFERENCED_ENTITY")
		void shouldAcceptEveryShapeThatKeepsTheEntityComponent() {
			final Map<String, String> actual = IndexedScopeRequiresEntityComponentTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					defineSupportingCollections(session);
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withReferenceToEntity(
							"defaultComponents", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs.indexedForFilteringAndPartitioning()
								.withGroupTypeRelatedToEntity(CATEGORY_GROUP)
						)
						.withReferenceToEntity(
							"bothComponents", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							IndexedScopeRequiresEntityComponentTest::validBothComponents
						)
						.withReferenceToEntity(
							"groupOnlyInArchive", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
								.withGroupTypeRelatedToEntity(CATEGORY_GROUP)
								.indexedWithComponentsInScope(
									Scope.LIVE, ReferenceIndexedComponents.REFERENCED_ENTITY
								)
								.indexedWithComponentsInScope(
									Scope.ARCHIVED,
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
						)
						.withReferenceToEntity(
							"groupOnlyInLive", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
								.withGroupTypeRelatedToEntity(CATEGORY_GROUP)
								.indexedWithComponentsInScope(
									Scope.LIVE,
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.indexedWithComponentsInScope(
									Scope.ARCHIVED, ReferenceIndexedComponents.REFERENCED_ENTITY
								)
						)
						.withReferenceToEntity(
							"notIndexed", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs.withGroupTypeRelatedToEntity(CATEGORY_GROUP)
						)
						.updateVia(session);
					return describeReferences(session, Entities.PRODUCT);
				}
			);
			assertEquals(
				Map.of(
					"defaultComponents", "LIVE=[REFERENCED_ENTITY]",
					"bothComponents", "LIVE=[REFERENCED_ENTITY, REFERENCED_GROUP_ENTITY]",
					"groupOnlyInArchive",
					"LIVE=[REFERENCED_ENTITY] ARCHIVED=[REFERENCED_ENTITY, REFERENCED_GROUP_ENTITY]",
					"groupOnlyInLive",
					"LIVE=[REFERENCED_ENTITY, REFERENCED_GROUP_ENTITY] ARCHIVED=[REFERENCED_ENTITY]",
					"notIndexed", ""
				),
				actual,
				"Every one of these shapes keeps REFERENCED_ENTITY in each indexed scope, so the session must close " +
					"and the schema must be exactly the one declared"
			);
			assertEquals(
				actual,
				IndexedScopeRequiresEntityComponentTest.this.evita.queryCatalog(
					TEST_CATALOG,
					(Function<EvitaSessionContract, Map<String, String>>)
						session -> describeReferences(session, Entities.PRODUCT)
				),
				"A session that closed without a refusal must have published exactly the schema it declared"
			);
		}

		/**
		 * The reflected counterpart of the case above: a reflected reference declaring both components explicitly,
		 * and one inheriting them from a valid source, must both be accepted.
		 */
		@Test
		@DisplayName("should accept reflected references whose effective components carry REFERENCED_ENTITY")
		void shouldAcceptReflectedReferencesThatKeepTheEntityComponent() {
			final Map<String, String> actual = IndexedScopeRequiresEntityComponentTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					defineProductWithCategories(session, IndexedScopeRequiresEntityComponentTest::validBothComponents);
					// a source reference can be reflected only once, so the inheriting case needs a second source
					session.getEntitySchemaOrThrowException(Entities.PRODUCT)
						.openForWrite()
						.withReferenceToEntity(
							"secondaryCategories", Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							IndexedScopeRequiresEntityComponentTest::validBothComponents
						)
						.updateVia(session);
					session.getEntitySchemaOrThrowException(Entities.CATEGORY)
						.openForWrite()
						.withReflectedReferenceToEntity(
							REF_REFLECTED_PRODUCTS, Entities.PRODUCT, REF_CATEGORIES,
							whichIs -> whichIs
								.withAttributesInherited()
								.indexedInScope(Scope.LIVE)
								.indexedWithComponentsInScope(
									Scope.LIVE,
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
						)
						.withReflectedReferenceToEntity(
							"inheritedProducts", Entities.PRODUCT, "secondaryCategories",
							whichIs -> whichIs.withAttributesInherited()
						)
						.updateVia(session);
					return describeReferences(session, Entities.CATEGORY);
				}
			);
			assertEquals(
				Map.of(
					REF_REFLECTED_PRODUCTS, "LIVE=[REFERENCED_ENTITY, REFERENCED_GROUP_ENTITY]",
					"inheritedProducts", "LIVE=[REFERENCED_ENTITY, REFERENCED_GROUP_ENTITY]"
				),
				actual,
				"Both reflected references keep REFERENCED_ENTITY in their only indexed scope, so the session must " +
					"close with them exactly as declared"
			);
		}

	}

	/**
	 * Runs the session, asserts that it reached its own end with the invalid schema in place, and returns the refusal
	 * its close raised.
	 *
	 * Reaching the end of the session is the half that tells the intended refusal apart from a construction-time
	 * one. Both throw {@link InvalidSchemaMutationException}, but a construction-time refusal also runs when a stored
	 * catalog is loaded, so it would make a catalog carrying the old shape unloadable.
	 *
	 * @param schemaDefinition defines the invalid schema
	 * @param inSessionPremise asserts, inside the same session, that the invalid schema really was accepted into it
	 * @return the refusal raised by the session close
	 */
	@Nonnull
	private InvalidSchemaMutationException assertRefusedAtClose(
		@Nonnull Consumer<EvitaSessionContract> schemaDefinition,
		@Nonnull Consumer<EvitaSessionContract> inSessionPremise
	) {
		final AtomicBoolean sessionReachedItsEnd = new AtomicBoolean(false);
		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					schemaDefinition.accept(session);
					inSessionPremise.accept(session);
					sessionReachedItsEnd.set(true);
				}
			),
			"An indexed scope without REFERENCED_ENTITY must be refused when the session closes"
		);
		assertTrue(
			sessionReachedItsEnd.get(),
			"The refusal must come from validation at session close, not from building the schema - the latter " +
				"would also run on catalog load, was: " + exception.getMessage()
		);
		return exception;
	}

	/**
	 * Asserts the refusal is actionable: it names the reference, the entity owning it, the scope that lacks the
	 * component, and the component itself.
	 *
	 * @param exception     the refusal raised by the session close
	 * @param referenceName the reference the user declared
	 * @param entityType    the entity type owning the reference
	 * @param scope         the indexed scope lacking the entity component
	 */
	private static void assertMessageNames(
		@Nonnull InvalidSchemaMutationException exception,
		@Nonnull String referenceName,
		@Nonnull String entityType,
		@Nonnull Scope scope
	) {
		final String message = exception.getMessage();
		assertTrue(
			message.contains("`" + referenceName + "`"),
			"The message must name the reference `" + referenceName + "`, was: " + message
		);
		assertTrue(
			message.contains("`" + entityType + "`"),
			"The message must name the entity `" + entityType + "`, was: " + message
		);
		assertTrue(
			message.contains(scope.name()),
			"The message must name the scope `" + scope + "` that lacks the component, was: " + message
		);
		assertTrue(
			message.contains("REFERENCED_ENTITY"),
			"The message must name the missing component `REFERENCED_ENTITY`, was: " + message
		);
	}

	/**
	 * Defines the category and category-group collections and a product collection carrying the
	 * {@link #REF_CATEGORIES} reference configured by the caller.
	 *
	 * @param session           session to write through
	 * @param referenceSettings configures the `categories` reference
	 */
	private static void defineProductWithCategories(
		@Nonnull EvitaSessionContract session,
		@Nonnull Consumer<ReferenceSchemaBuilder> referenceSettings
	) {
		defineSupportingCollections(session);
		session.defineEntitySchema(Entities.PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(REF_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE, referenceSettings)
			.updateVia(session);
	}

	/**
	 * Configures a reference indexed in the default scope for both components - the valid way to ask for group
	 * indexes.
	 *
	 * @param whichIs the reference builder to configure
	 */
	private static void validBothComponents(
		@Nonnull ReferenceSchemaBuilder whichIs
	) {
		whichIs.indexedForFilteringAndPartitioning()
			.withGroupTypeRelatedToEntity(CATEGORY_GROUP)
			.indexedWithComponents(
				ReferenceIndexedComponents.REFERENCED_ENTITY,
				ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
			);
	}

	/**
	 * Defines the collections the references point at: the category collection and the category group collection.
	 *
	 * @param session session to write through
	 */
	private static void defineSupportingCollections(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(CATEGORY_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(Entities.CATEGORY).withoutGeneratedPrimaryKey().updateVia(session);
	}

	/**
	 * Describes the indexed components of every reference of the given entity type.
	 *
	 * @param session    session to read through
	 * @param entityType entity type whose references are described
	 * @return reference name mapped to its `SCOPE=[COMPONENT, ...]` description
	 */
	@Nonnull
	private static Map<String, String> describeReferences(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType
	) {
		final Map<String, String> result = new TreeMap<>();
		for (ReferenceSchemaContract reference :
			session.getEntitySchemaOrThrowException(entityType).getReferences().values()) {
			result.put(reference.getName(), describe(reference));
		}
		return result;
	}

}
