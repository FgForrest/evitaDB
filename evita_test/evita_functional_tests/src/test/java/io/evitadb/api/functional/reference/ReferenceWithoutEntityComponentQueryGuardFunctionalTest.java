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
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.facetSummaryOfReference;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.fromRoot;
import static io.evitadb.api.query.QueryConstraints.groupHaving;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfReference;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithin;
import static io.evitadb.api.query.QueryConstraints.histogramHaving;
import static io.evitadb.api.query.QueryConstraints.inScope;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceProperty;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.REQUIRE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins what a catalog that already stores a reference indexed without
 * {@link ReferenceIndexedComponents#REFERENCED_ENTITY} does when it is queried (#1601).
 *
 * Such a catalog still loads - the schema rule that refuses the shape runs in `validate()`, never on load - but
 * nothing that needs the reduced entity indexes can be answered from it, because they were never built. Every query
 * path that reads them used to answer with an empty result, silently; a negation over one inverted into the whole
 * collection. Each path must now refuse loudly, naming the reference and the scope that lacks the component. Facet
 * computation reads a different structure and must keep working, which the controls below pin.
 *
 * **How the stored shape is produced.** The shape can no longer be committed through the public API, but validation
 * runs only when the session closes, so the session that defines it sees it - schema and indexes both - exactly as a
 * catalog loaded from disk would. Every test therefore defines the shape, writes data and queries it inside one
 * session, and learns the outcome after that session has closed.
 *
 * **Why `ALIVE`, deliberately.** A stored catalog is served in the transactional `ALIVE` state, so that is the state
 * whose query paths matter. It is also the state in which a refused close is a clean rollback: the invalid schema
 * never reaches the catalog, and {@link SessionClose} asserts precisely that. In `WARMING_UP` the same refusal would
 * instead deactivate the catalog. Each test still gets its own engine, because before the rule existed the close
 * *committed* the shape - a shared catalog would then carry it into every later test and make their results
 * meaningless.
 *
 * The fixture carries two references:
 *
 * - {@link #REF_GROUP_ONLY} - indexed in {@link Scope#LIVE} for the group component only. Its referenced entity is
 *   hierarchical, it has a group type, a sortable reference attribute and a bucketed histogram, so that every query
 *   path over a reference applies to it.
 * - {@link #REF_GROUP_ONLY_IN_ARCHIVE} - valid in {@link Scope#LIVE}, group-only in {@link Scope#ARCHIVED}. It pins
 *   that the refusal is per queried scope, and that narrowing the query to the valid scope - with `scope(...)` or with
 *   `inScope(...)` - is the workaround that answers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Query over a stored reference indexed without the entity component")
@Tag(ENGINE)
@Tag(REFERENCE)
@Tag(FILTER)
public class ReferenceWithoutEntityComponentQueryGuardFunctionalTest implements EvitaTestSupport {

	private static final String PRODUCT = "Product";
	private static final String CATEGORY = "Category";
	private static final String CATEGORY_GROUP = "CategoryGroup";

	/**
	 * Indexed in {@link Scope#LIVE} with `[REFERENCED_GROUP_ENTITY]` only - the shape #1601 is about.
	 */
	private static final String REF_GROUP_ONLY = "groupOnlyCategories";
	/**
	 * Indexed with `[REFERENCED_ENTITY]` in {@link Scope#LIVE} and `[REFERENCED_GROUP_ENTITY]` in
	 * {@link Scope#ARCHIVED}.
	 */
	private static final String REF_GROUP_ONLY_IN_ARCHIVE = "groupOnlyInArchiveCategories";

	/**
	 * Sortable reference attribute on {@link #REF_GROUP_ONLY}, used by `referenceProperty` ordering.
	 */
	private static final String ATTR_ORDER = "order";
	/**
	 * Filterable decimal reference attribute on {@link #REF_GROUP_ONLY}, the value source of {@link #HISTOGRAM_SHARE}.
	 * Never set - see {@link #upsertProduct}.
	 */
	private static final String ATTR_SHARE = "share";
	/**
	 * Bucketed histogram on {@link #REF_GROUP_ONLY}, used by `histogramHaving`.
	 */
	private static final String HISTOGRAM_SHARE = "shareHistogram";
	/**
	 * Output name of the `hierarchyOfReference` tree.
	 */
	private static final String TREE = "tree";

	/** Root category - parent of {@link #CHILD_CATEGORY_PK}. */
	private static final int ROOT_CATEGORY_PK = 1;
	/** Child of {@link #ROOT_CATEGORY_PK}, referenced by products 1 and 3. */
	private static final int CHILD_CATEGORY_PK = 2;
	/** A second root, referenced by product 2. */
	private static final int OTHER_ROOT_CATEGORY_PK = 3;
	/** Group of every row pointing at {@link #CHILD_CATEGORY_PK}. */
	private static final int GROUP_A_PK = 100;
	/** Group of every row pointing at {@link #OTHER_ROOT_CATEGORY_PK}. */
	private static final int GROUP_B_PK = 200;
	/** Live product referencing {@link #CHILD_CATEGORY_PK} in {@link #GROUP_A_PK}. */
	private static final int PRODUCT_A_PK = 1;
	/** Live product referencing {@link #OTHER_ROOT_CATEGORY_PK} in {@link #GROUP_B_PK}. */
	private static final int PRODUCT_B_PK = 2;
	/** Archived product referencing {@link #CHILD_CATEGORY_PK} in {@link #GROUP_A_PK}, exactly like product 1. */
	private static final int ARCHIVED_PRODUCT_PK = 3;

	private TestPaths paths;
	private Evita evita;
	/**
	 * The session the refusal tests query through. Held only while
	 * {@link #queryInStoredShapeSession(Function)} runs its body.
	 */
	private final AtomicReference<EvitaSessionContract> sessionInProgress = new AtomicReference<>();

	/**
	 * Starts a fresh engine and commits everything that is valid - the category tree, the category groups and a
	 * product collection with no references - then switches the catalog to `ALIVE`. The invalid references are
	 * added by each test in its own session, see {@link #queryInStoredShapeSession(Function)}.
	 */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReferenceWithoutEntityComponentQueryGuard");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(CATEGORY_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(CATEGORY).withoutGeneratedPrimaryKey().withHierarchy().updateVia(session);
				session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey().updateVia(session);

				session.upsertEntity(session.createNewEntity(CATEGORY_GROUP, GROUP_A_PK));
				session.upsertEntity(session.createNewEntity(CATEGORY_GROUP, GROUP_B_PK));
				session.upsertEntity(session.createNewEntity(CATEGORY, ROOT_CATEGORY_PK));
				session.upsertEntity(session.createNewEntity(CATEGORY, CHILD_CATEGORY_PK).setParent(ROOT_CATEGORY_PK));
				session.upsertEntity(session.createNewEntity(CATEGORY, OTHER_ROOT_CATEGORY_PK));
			}
		);
		this.evita.makeCatalogAlive(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * The premises every other test rests on, asserted inside the session that holds the stored shape. A fixture
	 * that quietly is not the schema it claims to be - or whose data is not where the queries look - would let every
	 * refusal test below pass or fail for a reason unrelated to the guard.
	 */
	@Nested
	@DisplayName("Fixture premises")
	class FixturePremises {

		@Test
		@Tag(SCHEMA)
		@DisplayName("should hold both references with the intended components and the features the queries need")
		void shouldHoldTheIntendedSchema() {
			final Map<String, String> actual = queryInStoredShapeSession(
				session -> {
					final SealedEntitySchema productSchema = session.getEntitySchemaOrThrowException(PRODUCT);
					final ReferenceSchemaContract groupOnly =
						productSchema.getReferenceOrThrowException(REF_GROUP_ONLY);
					final Map<String, String> description = new TreeMap<>();
					description.put(REF_GROUP_ONLY, describe(groupOnly));
					description.put(
						REF_GROUP_ONLY_IN_ARCHIVE,
						describe(productSchema.getReferenceOrThrowException(REF_GROUP_ONLY_IN_ARCHIVE))
					);
					description.put("groupType", String.valueOf(groupOnly.getReferencedGroupType()));
					description.put("faceted", String.valueOf(groupOnly.isFacetedInScope(Scope.LIVE)));
					description.put(
						"sortable",
						String.valueOf(
							groupOnly.getAttribute(ATTR_ORDER).orElseThrow().isSortableInScope(Scope.LIVE)
						)
					);
					description.put(
						"histogram",
						String.valueOf(groupOnly.getHistogramIndexDefinition(Scope.LIVE, HISTOGRAM_SHARE) != null)
					);
					description.put(
						"hierarchical",
						String.valueOf(session.getEntitySchemaOrThrowException(CATEGORY).isWithHierarchy())
					);
					return description;
				}
			);
			assertEquals(
				Map.of(
					REF_GROUP_ONLY, "LIVE=[REFERENCED_GROUP_ENTITY]",
					REF_GROUP_ONLY_IN_ARCHIVE, "LIVE=[REFERENCED_ENTITY] ARCHIVED=[REFERENCED_GROUP_ENTITY]",
					"groupType", CATEGORY_GROUP,
					"faceted", "true",
					"sortable", "true",
					"histogram", "true",
					"hierarchical", "true"
				),
				actual,
				"The schema the session actually holds is not the one the tests below assume"
			);
		}

		/**
		 * The rows exist and are readable - fetching a reference reads the entity body, not the reduced indexes. This
		 * is what makes an empty query answer wrong rather than merely unhelpful.
		 */
		@Test
		@DisplayName("should hold the rows the queries look for")
		void shouldHoldTheRowsTheQueriesLookFor() {
			final List<Integer> referenced = queryInStoredShapeSession(
				session -> fetchReferencedPks(
					session,
					query(
						collection(PRODUCT),
						filterBy(entityPrimaryKeyInSet(PRODUCT_A_PK)),
						require(entityFetch(referenceContent(REF_GROUP_ONLY)))
					)
				)
			);
			assertEquals(
				List.of(CHILD_CATEGORY_PK),
				referenced,
				"Product " + PRODUCT_A_PK + " must carry its one row on `" + REF_GROUP_ONLY + "`"
			);
		}

	}

	@Nested
	@DisplayName("Refused, naming the reference and the scope")
	class Refused {

		@Test
		@DisplayName("should refuse referenceHaving with groupHaving")
		void shouldRefuseGroupHaving() {
			assertRefused(
				() -> productPks(
					query(
						collection(PRODUCT),
						filterBy(referenceHaving(REF_GROUP_ONLY, groupHaving(entityPrimaryKeyInSet(GROUP_A_PK))))
					)
				),
				REF_GROUP_ONLY, Scope.LIVE,
				"product " + PRODUCT_A_PK + " carries group " + GROUP_A_PK + ", so an empty answer is wrong"
			);
		}

		@Test
		@DisplayName("should refuse referenceHaving with entityHaving")
		void shouldRefuseEntityHaving() {
			assertRefused(
				() -> productPks(
					query(
						collection(PRODUCT),
						filterBy(
							referenceHaving(REF_GROUP_ONLY, entityHaving(entityPrimaryKeyInSet(CHILD_CATEGORY_PK)))
						)
					)
				),
				REF_GROUP_ONLY, Scope.LIVE,
				"product " + PRODUCT_A_PK + " references category " + CHILD_CATEGORY_PK +
					", so an empty answer is wrong"
			);
		}

		@Test
		@DisplayName("should refuse a bare referenceHaving existence check")
		void shouldRefuseExistenceCheck() {
			assertRefused(
				() -> productPks(
					query(collection(PRODUCT), filterBy(referenceHaving(REF_GROUP_ONLY)))
				),
				REF_GROUP_ONLY, Scope.LIVE,
				"both live products carry a row, so an empty answer is wrong"
			);
		}

		/**
		 * The decisive shape. The planner shortcut never sees a negated constraint, so the translator's empty set is
		 * complemented into the whole collection - the exact opposite of the right answer, and indistinguishable
		 * from a working query.
		 */
		@Test
		@DisplayName("should refuse a negated referenceHaving rather than answer it with the whole collection")
		void shouldRefuseNegatedReferenceHaving() {
			assertRefused(
				() -> productPks(
					query(
						collection(PRODUCT),
						filterBy(
							not(referenceHaving(REF_GROUP_ONLY, groupHaving(entityPrimaryKeyInSet(GROUP_A_PK))))
						)
					)
				),
				REF_GROUP_ONLY, Scope.LIVE,
				"the right answer is [" + PRODUCT_B_PK + "], and the blind one is every product"
			);
		}

		@Test
		@Tag(HIERARCHY)
		@DisplayName("should refuse hierarchyWithin over the reference")
		void shouldRefuseHierarchyWithin() {
			assertRefused(
				() -> productPks(
					query(
						collection(PRODUCT),
						filterBy(hierarchyWithin(REF_GROUP_ONLY, entityPrimaryKeyInSet(ROOT_CATEGORY_PK)))
					)
				),
				REF_GROUP_ONLY, Scope.LIVE,
				"product " + PRODUCT_A_PK + " references a child of category " + ROOT_CATEGORY_PK +
					", so an empty answer is wrong"
			);
		}

		@Test
		@Tag(HIERARCHY)
		@Tag(REQUIRE)
		@DisplayName("should refuse the hierarchyOfReference extra result")
		void shouldRefuseHierarchyOfReference() {
			assertRefused(
				() -> {
					final EvitaResponse<EntityReferenceContract> response = currentSession().queryEntityReference(
						query(
							collection(PRODUCT),
							require(hierarchyOfReference(REF_GROUP_ONLY, fromRoot(TREE)))
						)
					);
					final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
					return hierarchy == null
						? "no hierarchy"
						: render(hierarchy.getReferenceHierarchy(REF_GROUP_ONLY, TREE));
				},
				REF_GROUP_ONLY, Scope.LIVE,
				"both live products reference a category, so a tree without them is wrong"
			);
		}

		/**
		 * `referenceContent` with a filter narrows the fetched rows through the reduced entity indexes, and a blind
		 * lookup hides every row of the entity rather than just the ones the filter rejects.
		 */
		@Test
		@Tag(REQUIRE)
		@DisplayName("should refuse referenceContent filtered by the referenced entity")
		void shouldRefuseFilteredReferenceContent() {
			assertRefused(
				() -> fetchReferencedPks(
					currentSession(),
					query(
						collection(PRODUCT),
						filterBy(entityPrimaryKeyInSet(PRODUCT_A_PK)),
						require(
							entityFetch(
								referenceContent(
									REF_GROUP_ONLY,
									filterBy(entityHaving(entityPrimaryKeyInSet(CHILD_CATEGORY_PK)))
								)
							)
						)
					)
				),
				REF_GROUP_ONLY, Scope.LIVE,
				"the right answer is [" + CHILD_CATEGORY_PK + "], and the blind one hides the row"
			);
		}

		@Test
		@Tag(ORDER)
		@DisplayName("should refuse ordering by referenceProperty")
		void shouldRefuseReferencePropertyOrdering() {
			assertRefused(
				() -> productPks(
					query(
						collection(PRODUCT),
						orderBy(referenceProperty(REF_GROUP_ONLY, attributeNatural(ATTR_ORDER, OrderDirection.DESC)))
					)
				),
				REF_GROUP_ONLY, Scope.LIVE,
				"the right order is [" + PRODUCT_B_PK + ", " + PRODUCT_A_PK + "], which the blind lookup cannot know"
			);
		}

		/**
		 * `histogramHaving` is rewritten into a `referenceHaving` over the same reference, so it inherits the same
		 * blindness - and must inherit the same refusal. The histogram holds no values on this shape (see
		 * {@link #upsertProduct}), so an empty answer happens to coincide with the data here; the refusal is still
		 * required, because the guard must judge the schema, not the contents of an index.
		 */
		@Test
		@Tag(HISTOGRAM)
		@DisplayName("should refuse histogramHaving over the reference")
		void shouldRefuseHistogramHaving() {
			assertRefused(
				() -> productPks(
					query(
						collection(PRODUCT),
						filterBy(histogramHaving(REF_GROUP_ONLY, HISTOGRAM_SHARE, 0, 20))
					)
				),
				REF_GROUP_ONLY, Scope.LIVE,
				"the reference lacks REFERENCED_ENTITY, so no answer over it can be trusted"
			);
		}

		/**
		 * `facetHaving` resolves its body against the reference's type index to learn which facets match, so over this
		 * shape it would select no facet at all and silently drop product {@link #PRODUCT_A_PK}.
		 */
		@Test
		@Tag(FACET)
		@DisplayName("should refuse facetHaving over the reference")
		void shouldRefuseFacetHaving() {
			assertRefused(
				() -> productPks(
					query(
						collection(PRODUCT),
						filterBy(userFilter(facetHaving(REF_GROUP_ONLY, entityPrimaryKeyInSet(CHILD_CATEGORY_PK))))
					)
				),
				REF_GROUP_ONLY, Scope.LIVE,
				"product " + PRODUCT_A_PK + " references category " + CHILD_CATEGORY_PK +
					", so an empty answer is wrong"
			);
		}

		/**
		 * The guard is per queried scope: the reference is valid in {@link Scope#LIVE}, but a query that also asks for
		 * {@link Scope#ARCHIVED} - where only the group component is indexed - cannot be answered for that scope and
		 * must say so, rather than quietly returning the live half.
		 */
		@Test
		@DisplayName("should refuse when any queried scope lacks the entity component")
		void shouldRefuseWhenOneOfTheQueriedScopesLacksTheComponent() {
			assertRefused(
				() -> productPks(
					query(
						collection(PRODUCT),
						filterBy(
							scope(Scope.LIVE, Scope.ARCHIVED),
							referenceHaving(
								REF_GROUP_ONLY_IN_ARCHIVE, entityHaving(entityPrimaryKeyInSet(CHILD_CATEGORY_PK))
							)
						)
					)
				),
				REF_GROUP_ONLY_IN_ARCHIVE, Scope.ARCHIVED,
				"archived product " + ARCHIVED_PRODUCT_PK + " references category " + CHILD_CATEGORY_PK +
					" too, so the live-only answer [" + PRODUCT_A_PK + "] is incomplete"
			);
		}

	}

	@Nested
	@DisplayName("Left alone")
	class Allowed {

		/**
		 * Facet computation reads the facet index, which does not depend on the indexed components, so the stored
		 * shape must keep answering it - the guard must not be placed so broadly that it takes facets down with the
		 * paths that really are blind.
		 */
		@Test
		@Tag(FACET)
		@Tag(REQUIRE)
		@DisplayName("should keep answering the facet summary of the reference")
		void shouldAnswerFacetSummary() {
			final Map<Integer, List<String>> facetsByGroup = queryInStoredShapeSession(
				session -> {
					final EvitaResponse<EntityReferenceContract> response = session.queryEntityReference(
						query(collection(PRODUCT), require(facetSummaryOfReference(REF_GROUP_ONLY)))
					);
					final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);
					assertNotNull(facetSummary, "The facet summary must be computed at all");
					final Map<Integer, List<String>> result = new TreeMap<>();
					for (int groupPk : new int[]{GROUP_A_PK, GROUP_B_PK}) {
						final FacetGroupStatistics groupStatistics =
							facetSummary.getFacetGroupStatistics(REF_GROUP_ONLY, groupPk);
						final List<String> facets = new ArrayList<>(2);
						if (groupStatistics != null) {
							for (FacetStatistics facet : groupStatistics.getFacetStatistics()) {
								facets.add(facet.getFacetEntity().getPrimaryKey() + "x" + facet.getCount());
							}
						}
						result.put(groupPk, facets);
					}
					return result;
				}
			);
			assertEquals(
				Map.of(
					GROUP_A_PK, List.of(CHILD_CATEGORY_PK + "x1"),
					GROUP_B_PK, List.of(OTHER_ROOT_CATEGORY_PK + "x1")
				),
				facetsByGroup,
				"Each live product contributes one facet in its own group - the facet index was built regardless of " +
					"the indexed components"
			);
		}

		/**
		 * Narrowing the query to the scope that carries the entity component is the first workaround the refusal
		 * message names.
		 */
		@Test
		@DisplayName("should answer when the query is narrowed with scope(...) to the valid scope")
		void shouldAnswerWhenNarrowedWithScope() {
			final List<Integer> answer = queryInStoredShapeSession(
				session -> queryProductPks(
					session,
					query(
						collection(PRODUCT),
						filterBy(
							scope(Scope.LIVE),
							referenceHaving(
								REF_GROUP_ONLY_IN_ARCHIVE, entityHaving(entityPrimaryKeyInSet(CHILD_CATEGORY_PK))
							)
						)
					)
				)
			);
			assertEquals(
				List.of(PRODUCT_A_PK),
				answer,
				"LIVE carries REFERENCED_ENTITY, so a query asking for LIVE alone is answerable and finds product " +
					PRODUCT_A_PK
			);
		}

		/**
		 * The second workaround: querying both scopes but applying the constraint only in the one that can answer it.
		 * The archived product is not constrained by `inScope(LIVE, ...)` at all, so it belongs to the answer - that is
		 * the documented meaning of `inScope`, not a leak.
		 */
		@Test
		@DisplayName("should answer when the constraint is wrapped in inScope(...) for the valid scope")
		void shouldAnswerWhenWrappedInInScope() {
			final List<Integer> answer = queryInStoredShapeSession(
				session -> queryProductPks(
					session,
					query(
						collection(PRODUCT),
						filterBy(
							scope(Scope.LIVE, Scope.ARCHIVED),
							inScope(
								Scope.LIVE,
								referenceHaving(
									REF_GROUP_ONLY_IN_ARCHIVE,
									entityHaving(entityPrimaryKeyInSet(CHILD_CATEGORY_PK))
								)
							)
						)
					)
				).stream().sorted().toList()
			);
			assertEquals(
				List.of(PRODUCT_A_PK, ARCHIVED_PRODUCT_PK),
				answer,
				"The constraint applies to LIVE only, where product " + PRODUCT_A_PK + " matches and product " +
					PRODUCT_B_PK + " does not; archived product " + ARCHIVED_PRODUCT_PK + " is unconstrained"
			);
		}

	}

	/**
	 * What the session close does with the stored shape. Validation walks the whole catalog schema, so any session
	 * that changes the schema of a catalog carrying the shape fails at close until the reference is fixed.
	 */
	@Nested
	@Tag(SCHEMA)
	@DisplayName("Session close")
	class SessionClose {

		@Test
		@DisplayName("should refuse the schema at close and roll the whole session back")
		void shouldRefuseTheSchemaAtCloseAndRollBack() {
			final RollbackException exception = assertThrows(
				RollbackException.class,
				() -> ReferenceWithoutEntityComponentQueryGuardFunctionalTest.this.evita.updateCatalog(
					TEST_CATALOG,
					ReferenceWithoutEntityComponentQueryGuardFunctionalTest::defineStoredShape
				),
				"A session leaving an indexed scope without REFERENCED_ENTITY must be refused when it closes"
			);
			assertInstanceOf(
				InvalidSchemaMutationException.class, exception.getCause(),
				"An ALIVE catalog reports a refused close as a rollback caused by the schema refusal"
			);
			final String message = exception.getCause().getMessage();
			for (String expected : List.of(
				"`" + REF_GROUP_ONLY + "`", "`" + REF_GROUP_ONLY_IN_ARCHIVE + "`", "`" + PRODUCT + "`",
				Scope.LIVE.name(), Scope.ARCHIVED.name(), "REFERENCED_ENTITY"
			)) {
				assertTrue(
					message.contains(expected),
					"The refusal must name `" + expected + "` - both references, their entity, the scope each " +
						"lacks the component in, and the component - was: " + message
				);
			}

			final List<String> afterRollback = ReferenceWithoutEntityComponentQueryGuardFunctionalTest.this.evita
				.queryCatalog(
					TEST_CATALOG,
					session -> {
						final List<String> state = new ArrayList<>(
							session.getEntitySchemaOrThrowException(PRODUCT).getReferences().keySet()
						);
						state.add("products=" + session.getEntityCollectionSize(PRODUCT));
						return state;
					}
				);
			assertEquals(
				List.of("products=0"),
				afterRollback,
				"The ALIVE catalog must roll the refused session back entirely - neither reference and none of " +
					"the products written alongside them may survive"
			);
		}

	}

	/**
	 * Defines the stored shape and data in a new session, applies the body to that session and returns its result.
	 *
	 * The close refusal that follows is caught here on purpose: {@link SessionClose} pins it on its own, and the tests
	 * using this method care only about what the session answered while the shape was in place. Anything else the
	 * close throws propagates.
	 *
	 * @param body what to do with the stored shape in place
	 * @param <T>  type of the body's result
	 * @return the body's result
	 */
	@Nullable
	private <T> T queryInStoredShapeSession(@Nonnull Function<EvitaSessionContract, T> body) {
		final AtomicReference<T> result = new AtomicReference<>();
		try {
			this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					defineStoredShape(session);
					this.sessionInProgress.set(session);
					try {
						result.set(body.apply(session));
					} finally {
						this.sessionInProgress.set(null);
					}
				}
			);
		} catch (RollbackException refusedAtClose) {
			// pinned by SessionClose - here only the in-session answer matters
		}
		return result.get();
	}

	/**
	 * Returns the session {@link #queryInStoredShapeSession(Function)} is currently running its body in.
	 *
	 * @return the session holding the stored shape
	 */
	@Nonnull
	private EvitaSessionContract currentSession() {
		final EvitaSessionContract session = this.sessionInProgress.get();
		assertNotNull(session, "The query must run inside the session that holds the stored shape");
		return session;
	}

	/**
	 * Runs the query inside the stored-shape session and asserts it is refused with an actionable message. When the
	 * query answers instead, the failure quotes the answer, so a regression shows what the blind lookup returned.
	 *
	 * @param query         runs the query in {@link #currentSession()} and renders its answer
	 * @param referenceName the reference the refusal must name
	 * @param scope         the scope the refusal must name as lacking the entity component
	 * @param whyAnAnswerIsWrong explains what the right answer would be and why the blind one is not it
	 */
	private void assertRefused(
		@Nonnull Supplier<Object> query,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull String whyAnAnswerIsWrong
	) {
		final Object outcome = queryInStoredShapeSession(
			session -> {
				try {
					return query.get();
				} catch (EvitaInvalidUsageException refusal) {
					return refusal;
				}
			}
		);
		if (!(outcome instanceof EvitaInvalidUsageException refusal)) {
			fail(
				"A query over `" + referenceName + "`, which lacks REFERENCED_ENTITY in scope " + scope +
					", must be refused, but it answered `" + outcome + "` - " + whyAnAnswerIsWrong
			);
			return;
		}
		final String message = refusal.getMessage();
		assertTrue(
			message.contains("`" + referenceName + "`"),
			"The refusal must name the reference `" + referenceName + "`, was: " + message
		);
		assertTrue(
			message.contains(scope.name()),
			"The refusal must name the scope `" + scope + "` that lacks the component, was: " + message
		);
		assertTrue(
			message.contains("REFERENCED_ENTITY"),
			"The refusal must name the missing component `REFERENCED_ENTITY`, was: " + message
		);
		assertTrue(
			message.contains("inScope"),
			"The refusal must name the `scope(...)` / `inScope(...)` workaround, was: " + message
		);
	}

	/**
	 * Adds the two invalid references to the product collection and writes the products that use them: two live
	 * products and one archived one. Runs in whatever session the caller passes, so the invalid schema is in place for
	 * the rest of that session and is refused only when it closes.
	 *
	 * @param session session to write through
	 */
	private static void defineStoredShape(@Nonnull EvitaSessionContract session) {
		session.getEntitySchemaOrThrowException(PRODUCT)
			.openForWrite()
			.withReferenceToEntity(
				REF_GROUP_ONLY, CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.faceted()
					.withGroupTypeRelatedToEntity(CATEGORY_GROUP)
					.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
					.withAttribute(ATTR_ORDER, Integer.class, thatIs -> thatIs.sortable().nullable())
					.withAttribute(
						ATTR_SHARE, BigDecimal.class,
						thatIs -> thatIs.filterable().indexDecimalPlaces(2).nullable()
					)
					.bucketed(
						HISTOGRAM_SHARE,
						ExpressionFactory.parse("$reference.attributes['" + ATTR_SHARE + "']")
					)
			)
			.withReferenceToEntity(
				REF_GROUP_ONLY_IN_ARCHIVE, CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
					.withGroupTypeRelatedToEntity(CATEGORY_GROUP)
					.indexedWithComponentsInScope(Scope.LIVE, ReferenceIndexedComponents.REFERENCED_ENTITY)
					.indexedWithComponentsInScope(Scope.ARCHIVED, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
			)
			.updateVia(session);

		upsertProduct(session, PRODUCT_A_PK, CHILD_CATEGORY_PK, GROUP_A_PK, 1);
		upsertProduct(session, PRODUCT_B_PK, OTHER_ROOT_CATEGORY_PK, GROUP_B_PK, 2);
		upsertProduct(session, ARCHIVED_PRODUCT_PK, CHILD_CATEGORY_PK, GROUP_A_PK, 3);
		session.archiveEntity(PRODUCT, ARCHIVED_PRODUCT_PK);
	}

	/**
	 * Creates one product carrying the same category and group on both references, so that which reference a query
	 * names is the only thing that varies between the assertions.
	 *
	 * The histogram source attribute {@link #ATTR_SHARE} is deliberately left unset. On this shape the write path
	 * refuses to store a histogram value at all - `ReferenceIndexMutator#insertSingleHistogramValue` reaches its
	 * ungrouped branch and throws for want of the `REFERENCED_ENTITY_TYPE` index - so a stored catalog of this shape
	 * holds a histogram definition and no values. The refusal of `histogramHaving` must therefore follow from the
	 * schema alone, never from what the histogram happens to contain.
	 *
	 * @param session    session to write through
	 * @param productPk  primary key of the created product
	 * @param categoryPk category referenced by both references
	 * @param groupPk    group assigned on both references
	 * @param order      value of the sortable reference attribute
	 */
	private static void upsertProduct(
		@Nonnull EvitaSessionContract session,
		int productPk,
		int categoryPk,
		int groupPk,
		int order
	) {
		session.upsertEntity(
			session.createNewEntity(PRODUCT, productPk)
				.setReference(
					REF_GROUP_ONLY, categoryPk,
					whichIs -> whichIs
						.setGroup(CATEGORY_GROUP, groupPk)
						.setAttribute(ATTR_ORDER, order)
				)
				.setReference(
					REF_GROUP_ONLY_IN_ARCHIVE, categoryPk,
					whichIs -> whichIs.setGroup(CATEGORY_GROUP, groupPk)
				)
		);
	}

	/**
	 * Runs the query in {@link #currentSession()} and returns the matched product primary keys.
	 *
	 * @param queryToRun the query to execute
	 * @return primary keys of the matched products, in the order the engine returned them
	 */
	@Nonnull
	private List<Integer> productPks(@Nonnull Query queryToRun) {
		return queryProductPks(currentSession(), queryToRun);
	}

	/**
	 * Runs the query and returns the matched product primary keys.
	 *
	 * @param session    session to query through
	 * @param queryToRun the query to execute
	 * @return primary keys of the matched products, in the order the engine returned them
	 */
	@Nonnull
	private static List<Integer> queryProductPks(@Nonnull EvitaSessionContract session, @Nonnull Query queryToRun) {
		return session.queryEntityReference(queryToRun)
			.getRecordData()
			.stream()
			.map(EntityReferenceContract::getPrimaryKey)
			.toList();
	}

	/**
	 * Fetches the single product the query selects and returns the primary keys of the categories its
	 * {@link #REF_GROUP_ONLY} rows point at.
	 *
	 * @param session    session to query through
	 * @param queryToRun a query selecting exactly one product and fetching its {@link #REF_GROUP_ONLY} rows
	 * @return referenced category primary keys, in the order the entity returned them
	 */
	@Nonnull
	private static List<Integer> fetchReferencedPks(@Nonnull EvitaSessionContract session, @Nonnull Query queryToRun) {
		final SealedEntity product = session.queryOneSealedEntity(queryToRun).orElseThrow();
		return product.getReferences(REF_GROUP_ONLY)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.toList();
	}

	/**
	 * Renders a hierarchy tree as nested primary keys, e.g. `[1[2], 3]`.
	 *
	 * @param levels the top level of the tree
	 * @return the rendered tree
	 */
	@Nonnull
	private static String render(@Nonnull List<LevelInfo> levels) {
		final StringBuilder result = new StringBuilder(32).append('[');
		for (int i = 0; i < levels.size(); i++) {
			final LevelInfo level = levels.get(i);
			if (i > 0) {
				result.append(", ");
			}
			result.append(level.entity().getPrimaryKey());
			if (!level.children().isEmpty()) {
				result.append(render(level.children()));
			}
		}
		return result.append(']').toString();
	}

	/**
	 * Renders the reference's indexed components per indexed scope, scopes and components both in a stable order so
	 * the expectation can be written literally.
	 *
	 * @param reference the reference schema to describe
	 * @return one `SCOPE=[COMPONENT, ...]` group per indexed scope, space separated
	 */
	@Nonnull
	private static String describe(@Nonnull ReferenceSchemaContract reference) {
		final StringBuilder result = new StringBuilder(64);
		for (Scope scope : Scope.values()) {
			if (!reference.isIndexedInScope(scope)) {
				continue;
			}
			if (!result.isEmpty()) {
				result.append(' ');
			}
			final Set<ReferenceIndexedComponents> components = reference.getIndexedComponents(scope);
			result.append(scope.name()).append('=').append(components.stream().map(Enum::name).sorted().toList());
		}
		return result.toString();
	}

}
