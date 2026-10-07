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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.exception.ReferenceComponentNotIndexedException;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.functional.reference.ReferenceIndexedComponentsTestSupport.describe;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins that a reflected reference which declares its indexed components explicitly but inherits its indexed scopes
 * gets the default component `REFERENCED_ENTITY` in every scope it becomes indexed in through the reference it
 * reflects - and that an archived owner of such a reference is therefore found.
 *
 * Without the default, the scope the explicit components do not name lands indexed with no component. It builds no
 * reduced index of either family, so the reflected rows of every entity in that scope are indexed nowhere: the
 * reference reports itself indexed in `ARCHIVED` while no archived owner can ever be found through it. That is the
 * mechanism behind reflected references on archived owners "not being indexed at all".
 *
 * Each route by which such a scope can appear is pinned separately, because each is completed at a different place:
 *
 * - the indexing mutation that makes the components explicit - completed by the mutation itself;
 * - the create mutation that declares them explicit from the start - completed when the new reference is first bound
 *   to the reference it reflects;
 * - the reference it reflects gaining a scope later - completed when the change cascades to the reflected reference;
 * - a self-referencing pair created in one batch - completed when the collection re-binds what the batch touched.
 *
 * The routes are driven with raw mutations because the schema builder always sends explicit scopes along with
 * explicit components, so it never produces inherited scopes with explicit components; the external APIs and the
 * write-ahead log carry exactly these mutations.
 *
 * Every entity is written after the schema is complete, because a schema change does not index entities that are
 * already stored - the query would miss them for that reason alone, and prove nothing about the components.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reflected reference with explicit components and inherited scopes")
@Tag(ENGINE)
@Tag(REFERENCE)
@Tag(SCHEMA)
class ReflectedReferenceDefaultComponentsFunctionalTest implements EvitaTestSupport {

	private static final String PRODUCT = "Product";
	private static final String CATEGORY = "Category";
	/**
	 * The original reference on the product collection.
	 */
	private static final String REF_CATEGORIES = "categories";
	/**
	 * The reflected reference on the category collection, mirroring {@link #REF_CATEGORIES}.
	 */
	private static final String REF_PRODUCTS = "products";
	/**
	 * A reference of the category collection to itself, the original end of the self-referencing pair.
	 */
	private static final String REF_RELATED = "related";
	/**
	 * The reflected reference mirroring {@link #REF_RELATED} on the same collection.
	 */
	private static final String REF_RELATED_BY = "relatedBy";
	/**
	 * Live category, referenced by {@link #PRODUCT_OF_LIVE_CATEGORY}.
	 */
	private static final int LIVE_CATEGORY_PK = 1;
	/**
	 * Category archived after the data is written, referenced by {@link #PRODUCT_OF_ARCHIVED_CATEGORY}.
	 */
	private static final int ARCHIVED_CATEGORY_PK = 2;
	private static final int PRODUCT_OF_LIVE_CATEGORY = 10;
	private static final int PRODUCT_OF_ARCHIVED_CATEGORY = 11;
	/**
	 * The expected components of the reflected reference once every route has completed them.
	 */
	private static final String BOTH_SCOPES_WITH_ENTITY_COMPONENT =
		"LIVE=[REFERENCED_ENTITY] ARCHIVED=[REFERENCED_ENTITY]";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReflectedReferenceDefaultComponents");
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

	@Test
	@DisplayName("should default the scope left uncovered by the indexing mutation that makes components explicit")
	void shouldDefaultTheUncoveredScopeWhenTheIndexingMutationMakesComponentsExplicit() {
		assertArchivedOwnerFound(
			session -> {
				defineCollections(session, Scope.LIVE, Scope.ARCHIVED);
				session.getEntitySchemaOrThrowException(CATEGORY)
					.openForWrite()
					.withReflectedReferenceToEntity(
						REF_PRODUCTS, PRODUCT, REF_CATEGORIES, whichIs -> whichIs.withAttributesInherited()
					)
					.updateVia(session);
				// scopes stay inherited (NULL), components become explicit for LIVE alone
				session.updateEntitySchema(
					new ModifyEntitySchemaMutation(
						CATEGORY,
						new SetReferenceSchemaIndexedMutation(REF_PRODUCTS, null, liveOnlyEntityComponent())
					)
				);
			}
		);
	}

	@Test
	@DisplayName("should default the scope left uncovered by the create mutation when the reference is first bound")
	void shouldDefaultTheUncoveredScopeWhenTheCreateMutationDeclaresComponentsExplicit() {
		assertArchivedOwnerFound(
			session -> {
				defineCollections(session, Scope.LIVE, Scope.ARCHIVED);
				session.updateEntitySchema(
					new ModifyEntitySchemaMutation(
						CATEGORY,
						new CreateReflectedReferenceSchemaMutation(
							REF_PRODUCTS, null, null, null, PRODUCT, REF_CATEGORIES,
							// scopes inherited, components explicit for LIVE alone
							null, liveOnlyEntityComponent(),
							null, null,
							ScopedHistogramIndexDefinition.EMPTY, ScopedBucketedPartially.EMPTY,
							AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT, null
						)
					)
				);
			}
		);
	}

	@Test
	@DisplayName("should default the scope gained when the reference it reflects is indexed there later")
	void shouldDefaultTheScopeGainedWhenTheReflectedReferenceIsIndexedThereLater() {
		assertArchivedOwnerFound(
			session -> {
				defineCollections(session, Scope.LIVE);
				session.getEntitySchemaOrThrowException(CATEGORY)
					.openForWrite()
					.withReflectedReferenceToEntity(
						REF_PRODUCTS, PRODUCT, REF_CATEGORIES, whichIs -> whichIs.withAttributesInherited()
					)
					.updateVia(session);
				session.updateEntitySchema(
					new ModifyEntitySchemaMutation(
						CATEGORY,
						new SetReferenceSchemaIndexedMutation(REF_PRODUCTS, null, liveOnlyEntityComponent())
					)
				);
				assertEquals(
					"LIVE=[REFERENCED_ENTITY]",
					describe(
						session.getEntitySchemaOrThrowException(CATEGORY).getReferenceOrThrowException(REF_PRODUCTS)
					),
					"The premise is a reflected reference indexed in LIVE alone before its original gains ARCHIVED"
				);
				// the original gains ARCHIVED, and the reflected reference inherits the scope from it
				session.getEntitySchemaOrThrowException(PRODUCT)
					.openForWrite()
					.withReferenceToEntity(
						REF_CATEGORIES, CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * A self-referencing pair created in one batch - the original reference and its reflection on the same
	 * collection. The create mutation looks the original up in the catalog schema, which does not hold it yet, so the
	 * reflected reference stays unbound until the collection re-binds the references the batch touched; that
	 * re-binding is where the uncovered scope has to be completed.
	 */
	@Test
	@DisplayName("should default the scope left uncovered by a self-referencing pair created in one batch")
	void shouldDefaultTheUncoveredScopeOfASelfReferencingPairCreatedInOneBatch() {
		final Map<String, String> inSession = this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(CATEGORY).withoutGeneratedPrimaryKey().updateVia(session);
				final LocalEntitySchemaMutation[] originalReference = session.getEntitySchemaOrThrowException(CATEGORY)
					.openForWrite()
					.withReferenceToEntity(
						REF_RELATED, CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					.toMutation()
					.orElseThrow()
					.getSchemaMutations();
				session.updateEntitySchema(
					new ModifyEntitySchemaMutation(
						CATEGORY,
						ArrayUtils.mergeArrays(
							originalReference,
							new LocalEntitySchemaMutation[]{
								new CreateReflectedReferenceSchemaMutation(
									REF_RELATED_BY, null, null, null, CATEGORY, REF_RELATED,
									// scopes inherited, components explicit for LIVE alone
									null, liveOnlyEntityComponent(),
									null, null,
									ScopedHistogramIndexDefinition.EMPTY, ScopedBucketedPartially.EMPTY,
									AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT, null
								)
							}
						)
					)
				);
				final Map<String, String> result = new TreeMap<>();
				result.put("components", describeRelatedBy(session));
				// category 1 relates to 2, which is then archived; 3 relates to 4, both live
				session.upsertEntity(session.createNewEntity(CATEGORY, 2));
				session.upsertEntity(session.createNewEntity(CATEGORY, 4));
				session.upsertEntity(session.createNewEntity(CATEGORY, 1).setReference(REF_RELATED, 2));
				session.upsertEntity(session.createNewEntity(CATEGORY, 3).setReference(REF_RELATED, 4));
				session.archiveEntity(CATEGORY, 2);
				result.put("archived", relatedByOwners(session, Scope.ARCHIVED, 1).toString());
				result.put("live", relatedByOwners(session, Scope.LIVE, 3).toString());
				return result;
			}
		);
		assertEquals(
			Map.of(
				"components", BOTH_SCOPES_WITH_ENTITY_COMPONENT,
				"archived", List.of(2).toString(),
				"live", List.of(4).toString()
			),
			inSession,
			"The self-referencing reflection must carry REFERENCED_ENTITY in both scopes and find the archived category"
		);

		// the batch stores the schema the collection re-bound, and a restart re-binds without filling anything in
		restart();
		final Map<String, String> reloaded = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Map<String, String> result = new TreeMap<>();
				result.put("components", describeRelatedBy(session));
				result.put("archived", relatedByOwnersOrRefusal(session, Scope.ARCHIVED, 1));
				result.put("live", relatedByOwnersOrRefusal(session, Scope.LIVE, 3));
				return result;
			}
		);
		assertEquals(
			Map.of(
				"components", BOTH_SCOPES_WITH_ENTITY_COMPONENT,
				"archived", List.of(2).toString(),
				"live", List.of(4).toString()
			),
			reloaded,
			"The reloaded catalog must carry the default component in ARCHIVED and find the archived category"
		);
	}

	/**
	 * A self-referencing pair whose original gains a scope later, in a schema change of its own that touches the
	 * original alone. The change and the re-binding of the reflected reference it cascades to happen in the same
	 * collection, so the completed reflected reference has to survive the schema that change itself stores - in the
	 * session, and after a restart re-binds the reflected reference to the original without filling anything in.
	 */
	@Test
	@DisplayName("should default the scope gained when the self-referenced original is indexed there later")
	void shouldDefaultTheScopeGainedWhenTheSelfReferencedReferenceIsIndexedThereLater() {
		final Map<String, String> inSession = this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_RELATED, CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE)
					)
					.updateVia(session);
				session.getEntitySchemaOrThrowException(CATEGORY)
					.openForWrite()
					.withReflectedReferenceToEntity(
						REF_RELATED_BY, CATEGORY, REF_RELATED, whichIs -> whichIs.withAttributesInherited()
					)
					.updateVia(session);
				// scopes stay inherited (NULL), components become explicit for LIVE alone
				session.updateEntitySchema(
					new ModifyEntitySchemaMutation(
						CATEGORY,
						new SetReferenceSchemaIndexedMutation(REF_RELATED_BY, null, liveOnlyEntityComponent())
					)
				);
				final Map<String, String> result = new TreeMap<>();
				result.put("premise", describeRelatedBy(session));
				// the original alone gains ARCHIVED, and the reflected reference inherits the scope from it
				session.getEntitySchemaOrThrowException(CATEGORY)
					.openForWrite()
					.withReferenceToEntity(
						REF_RELATED, CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					.updateVia(session);
				result.put("components", describeRelatedBy(session));
				// category 1 relates to 2, which is then archived
				session.upsertEntity(session.createNewEntity(CATEGORY, 2));
				session.upsertEntity(session.createNewEntity(CATEGORY, 1).setReference(REF_RELATED, 2));
				session.archiveEntity(CATEGORY, 2);
				return result;
			}
		);
		assertEquals(
			Map.of("premise", "LIVE=[REFERENCED_ENTITY]", "components", BOTH_SCOPES_WITH_ENTITY_COMPONENT),
			inSession,
			"The reflected reference must inherit ARCHIVED from the original it reflects, with the default component"
		);

		restart();
		final Map<String, String> reloaded = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Map<String, String> result = new TreeMap<>();
				result.put("components", describeRelatedBy(session));
				result.put("archived", relatedByOwnersOrRefusal(session, Scope.ARCHIVED, 1));
				return result;
			}
		);
		assertEquals(
			Map.of("components", BOTH_SCOPES_WITH_ENTITY_COMPONENT, "archived", List.of(2).toString()),
			reloaded,
			"The reloaded catalog must carry the default component in ARCHIVED and find the archived category"
		);
	}

	/**
	 * Describes the {@link #REF_RELATED_BY} reference of the category collection.
	 *
	 * @param session session to read the schema through
	 * @return the rendered components, see {@link ReferenceIndexedComponentsTestSupport#describe(ReferenceSchemaContract)}
	 */
	@Nonnull
	private static String describeRelatedBy(@Nonnull EvitaSessionContract session) {
		return describe(session.getEntitySchemaOrThrowException(CATEGORY).getReferenceOrThrowException(REF_RELATED_BY));
	}

	/**
	 * Closes the engine and opens a new one over the same storage, so that the catalog is loaded from disk.
	 */
	private void restart() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Same as {@link #relatedByOwners(EvitaSessionContract, Scope, int)}, but renders a refusal of the query as the
	 * simple name of the exception, so that an assertion over a reloaded catalog shows which scope lost its component.
	 *
	 * @param session     session to query through
	 * @param scope       the scope to query
	 * @param relatedToPk primary key the reflected row points at
	 * @return primary keys of the owning categories, or the name of the refusal
	 */
	@Nonnull
	private static String relatedByOwnersOrRefusal(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope scope,
		int relatedToPk
	) {
		try {
			return relatedByOwners(session, scope, relatedToPk).toString();
		} catch (ReferenceComponentNotIndexedException refusal) {
			return refusal.getClass().getSimpleName();
		}
	}

	/**
	 * Finds the categories owning a {@link #REF_RELATED_BY} row that points at the given category, in one scope.
	 *
	 * @param session     session to query through
	 * @param scope       the scope to query
	 * @param relatedToPk primary key the reflected row points at
	 * @return primary keys of the owning categories
	 */
	@Nonnull
	private static List<Integer> relatedByOwners(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope scope,
		int relatedToPk
	) {
		return categoryPks(
			session,
			query(
				collection(CATEGORY),
				filterBy(scope(scope), referenceHaving(REF_RELATED_BY, entityPrimaryKeyInSet(relatedToPk)))
			)
		);
	}

	/**
	 * Runs the schema definition, asserts the reflected reference ends up with explicit components and inherited
	 * scopes, both scopes carrying `REFERENCED_ENTITY`, then writes the data, archives one category and asserts that
	 * each category is found in its own scope through the reflected reference - inside the session, after it
	 * committed, which also proves the session close accepted the schema, and after the engine restarts and loads the
	 * catalog from disk.
	 *
	 * @param schemaDefinition defines the product and category collections and the reflected reference
	 */
	private void assertArchivedOwnerFound(@Nonnull Consumer<EvitaSessionContract> schemaDefinition) {
		final Map<String, String> inSession = this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				schemaDefinition.accept(session);
				final ReferenceSchemaContract reflected = session.getEntitySchemaOrThrowException(CATEGORY)
					.getReferenceOrThrowException(REF_PRODUCTS);
				final Map<String, String> result = new TreeMap<>();
				result.put(
					"shape",
					"inheritedScopes=" + ((ReflectedReferenceSchemaContract) reflected).isIndexedInherited() +
						" inheritedComponents=" +
						((ReflectedReferenceSchemaContract) reflected).isIndexedComponentsInherited()
				);
				result.put("components", describe(reflected));

				session.upsertEntity(session.createNewEntity(CATEGORY, LIVE_CATEGORY_PK));
				session.upsertEntity(session.createNewEntity(CATEGORY, ARCHIVED_CATEGORY_PK));
				session.upsertEntity(
					session.createNewEntity(PRODUCT, PRODUCT_OF_LIVE_CATEGORY)
						.setReference(REF_CATEGORIES, LIVE_CATEGORY_PK)
				);
				session.upsertEntity(
					session.createNewEntity(PRODUCT, PRODUCT_OF_ARCHIVED_CATEGORY)
						.setReference(REF_CATEGORIES, ARCHIVED_CATEGORY_PK)
				);
				session.archiveEntity(CATEGORY, ARCHIVED_CATEGORY_PK);
				result.putAll(findOwners(session));
				return result;
			}
		);
		assertEquals(
			Map.of(
				"shape", "inheritedScopes=true inheritedComponents=false",
				"components", BOTH_SCOPES_WITH_ENTITY_COMPONENT,
				"archived", List.of(ARCHIVED_CATEGORY_PK).toString(),
				"live", List.of(LIVE_CATEGORY_PK).toString()
			),
			inSession,
			"The reflected reference must inherit its scopes, declare its components, carry REFERENCED_ENTITY in " +
				"both scopes, and find each category in its own scope - the archived one only because ARCHIVED got " +
				"the default component"
		);
		assertEquals(
			Map.of("archived", List.of(ARCHIVED_CATEGORY_PK).toString(), "live", List.of(LIVE_CATEGORY_PK).toString()),
			this.evita.queryCatalog(
				TEST_CATALOG,
				(Function<EvitaSessionContract, Map<String, String>>)
					ReflectedReferenceDefaultComponentsFunctionalTest::findOwners
			),
			"The committed catalog must answer the same"
		);

		// a restart loads the schema from disk and re-binds the reflected reference without filling anything in, so
		// it proves the default components were stored rather than recomputed - and the indexes built from them too
		restart();
		assertEquals(
			Map.of(
				"components", BOTH_SCOPES_WITH_ENTITY_COMPONENT,
				"archived", List.of(ARCHIVED_CATEGORY_PK).toString(),
				"live", List.of(LIVE_CATEGORY_PK).toString()
			),
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Map<String, String> result = new TreeMap<>(findOwners(session));
					result.put(
						"components",
						describe(
							session.getEntitySchemaOrThrowException(CATEGORY).getReferenceOrThrowException(REF_PRODUCTS)
						)
					);
					return result;
				}
			),
			"The reloaded catalog must carry the default components it stored and answer the same"
		);
	}

	/**
	 * Finds the category owning a reflected row that points at each product, once per scope.
	 *
	 * @param session session to query through
	 * @return `archived` and `live` mapped to the rendered primary keys found in that scope
	 */
	@Nonnull
	private static Map<String, String> findOwners(@Nonnull EvitaSessionContract session) {
		final Map<String, String> result = new TreeMap<>();
		result.put(
			"archived",
			categoryPks(
				session,
				query(
					collection(CATEGORY),
					filterBy(
						scope(Scope.ARCHIVED),
						referenceHaving(REF_PRODUCTS, entityPrimaryKeyInSet(PRODUCT_OF_ARCHIVED_CATEGORY))
					)
				)
			).toString()
		);
		result.put(
			"live",
			categoryPks(
				session,
				query(
					collection(CATEGORY),
					filterBy(
						scope(Scope.LIVE),
						referenceHaving(REF_PRODUCTS, entityPrimaryKeyInSet(PRODUCT_OF_LIVE_CATEGORY))
					)
				)
			).toString()
		);
		return result;
	}

	/**
	 * Runs the query and returns the matched primary keys.
	 *
	 * @param session    session to query through
	 * @param queryToRun the query to execute
	 * @return primary keys of the matched entities
	 */
	@Nonnull
	private static List<Integer> categoryPks(@Nonnull EvitaSessionContract session, @Nonnull Query queryToRun) {
		return session.queryEntityReference(queryToRun)
			.getRecordData()
			.stream()
			.map(EntityReferenceContract::getPrimaryKey)
			.toList();
	}

	/**
	 * Defines the category collection and the product collection with its {@link #REF_CATEGORIES} reference indexed
	 * in the given scopes.
	 *
	 * @param session       session to write through
	 * @param indexedScopes scopes the original reference is indexed in
	 */
	private static void defineCollections(@Nonnull EvitaSessionContract session, @Nonnull Scope... indexedScopes) {
		session.defineEntitySchema(CATEGORY).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REF_CATEGORIES, CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringInScope(indexedScopes)
			)
			.updateVia(session);
	}

	/**
	 * Explicit components naming {@link Scope#LIVE} alone - every other indexed scope is left uncovered.
	 *
	 * @return the component declaration
	 */
	@Nonnull
	private static ScopedReferenceIndexedComponents[] liveOnlyEntityComponent() {
		return new ScopedReferenceIndexedComponents[]{
			new ScopedReferenceIndexedComponents(
				Scope.LIVE, new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY}
			)
		};
	}

}
