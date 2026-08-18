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

package io.evitadb.core.query;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.indexSelection.IndexSelectionVisitor;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.usage.SchemaCapabilityKey;
import io.evitadb.index.usage.SchemaCapabilityKey.Capability;
import io.evitadb.index.usage.SchemaCapabilityUsage;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry.UsageEntry;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceProperty;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the query side of the schema-capability counters: what a **logical query** asks the schema for, collected
 * while it is planned.
 *
 * The thing being pinned is a deduplication, and it is easy to get wrong in a way nothing else notices. The planner
 * translates the whole filter **once per candidate index set**, so a capability recorded where the translation happens
 * would come out as *"how many alternatives the planner considered"* rather than *"how many queries asked for this"* -
 * a number that moves when the cost model or the fixture's data distribution changes, and that nobody can act on.
 * Every case below therefore plans a query with **more than one candidate index set** and asserts the capability
 * landed exactly once; the multi-candidate premise is itself asserted, so a fixture that quietly stopped producing
 * alternatives cannot make these tests pass vacuously.
 *
 * The complementary property - *requested* is not *chosen* - is asserted against the per-index counters of
 * {@link io.evitadb.index.IndexActivity} in `RequestedIsNotChosen`: a capability consulted on an index that then lost
 * the cost comparison still counts.
 *
 * **What one query asked for is read as the counts it moved**, because {@link QueryPlanBuilder#build()} drains the
 * accumulator into the holders and leaves the context holding nothing. Every case therefore snapshots the collection's
 * registry, runs one query, and asserts on the *difference* - which is what makes a case independent of the fixture's
 * own writes and of any query a sibling case ran before it.
 *
 * `Flush` covers the other half: that the drain happens exactly once per logical query even when the planner is made
 * to build the same plan several times.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see QueryPlanningContext#registerRequestedCapability(SchemaCapabilityUsage)
 * @see SchemaCapabilityUsage
 */
@DisplayName("Requested schema capabilities are accumulated once per logical query")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(ATTRIBUTE)
class RequestedCapabilityAccumulationTest implements EvitaTestSupport {
	private static final String CATALOG = "requestedCapabilityAccumulationTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String ATTRIBUTE_EAN = "ean";
	private static final String ATTRIBUTE_ORDER_IN_CATEGORY = "orderInCategory";
	private static final String COMPOUND_CODE_WITH_PRIORITY = "codeWithPriority";
	private static final int CATEGORY_COUNT = 4;
	private static final int PRODUCTS_PER_CATEGORY = 5;
	private static final int PRODUCT_COUNT = CATEGORY_COUNT * PRODUCTS_PER_CATEGORY;
	/** The category every multi-candidate query names, so the planner builds a plan around its own index. */
	private static final int QUERIED_CATEGORY = 2;

	/** Filtering by `code` - the capability most cases assert on. */
	private static final SchemaCapabilityKey CODE_FILTER = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_CODE, Capability.FILTER, Scope.LIVE
	);
	/** Ordering by `priority`. */
	private static final SchemaCapabilityKey PRIORITY_SORT = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_PRIORITY, Capability.SORT, Scope.LIVE
	);
	/** Ordering by the compound - the key an attribute of the same name would be indistinguishable from. */
	private static final SchemaCapabilityKey COMPOUND_SORT = SchemaCapabilityKey.sortableCompound(
		null, COMPOUND_CODE_WITH_PRIORITY, Scope.LIVE
	);
	/** Filtering by an attribute the `categories` reference declares. */
	private static final SchemaCapabilityKey ORDER_IN_CATEGORY_FILTER = SchemaCapabilityKey.referenceAttribute(
		REFERENCE_CATEGORIES, ATTRIBUTE_ORDER_IN_CATEGORY, Capability.FILTER, Scope.LIVE
	);
	/** Ordering by an attribute the `categories` reference declares. */
	private static final SchemaCapabilityKey ORDER_IN_CATEGORY_SORT = SchemaCapabilityKey.referenceAttribute(
		REFERENCE_CATEGORIES, ATTRIBUTE_ORDER_IN_CATEGORY, Capability.SORT, Scope.LIVE
	);
	/** The control: a filterable attribute of the same shape as `code` that no query below ever names. */
	private static final SchemaCapabilityKey EAN_FILTER = SchemaCapabilityKey.entityAttribute(
		ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE
	);

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("RequestedCapabilityAccumulationTest");
		this.evita = new Evita(getEvitaConfiguration());
		buildCatalog();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Deduplication")
	class Deduplication {

		@Test
		@DisplayName("A filter and an ordering each land once, however many candidate plans were costed")
		void shouldAccumulateEachCapabilityExactlyOncePerLogicalQuery() {
			final Query query = query(
				filterBy(
					and(
						attributeEquals(ATTRIBUTE_CODE, "product-3"),
						referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))
					)
				),
				orderBy(attributeNatural(ATTRIBUTE_PRIORITY, OrderDirection.DESC))
			);
			assertTrue(
				candidatePlanCountOf(query) > 1,
				"The fixture must make the planner cost more than one candidate index set, otherwise this test " +
					"cannot tell a deduplicated count from a raw one"
			);

			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedBy(query);

			assertEquals(
				2, requested.size(),
				"One logical query must move one capability's count per element it names - more entries means the " +
					"accumulator is counting candidate plans: " + requested
			);
			assertRequested(requested, CODE_FILTER);
			assertRequested(requested, PRIORITY_SORT);
		}

		@Test
		@DisplayName("Naming the same attribute in several constraints is still one request")
		void shouldAccumulateOneEntryForAnAttributeNamedRepeatedly() {
			final Query query = query(
				filterBy(
					and(
						attributeEquals(ATTRIBUTE_CODE, "product-3"),
						attributeEquals(ATTRIBUTE_CODE, "product-3"),
						referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))
					)
				)
			);

			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedBy(query);

			assertEquals(
				1, requested.size(),
				"Repeating a constraint must not repeat the request: " + requested
			);
			assertRequested(requested, CODE_FILTER);
		}

		@Test
		@DisplayName("A query naming no attribute at all accumulates nothing")
		void shouldAccumulateNothingForAQueryThatNamesNoAttribute() {
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedBy(
				query(filterBy(entityPrimaryKeyInSet(1, 2, 3)))
			);

			assertTrue(requested.isEmpty(), "Nothing was named, so nothing may be requested: " + requested);
		}

	}

	@Nested
	@DisplayName("Which element the request lands on")
	class Attribution {

		@Test
		@DisplayName("A sortable compound is counted as the compound, not as an attribute of the same name")
		void shouldAccumulateTheCompoundUnderItsOwnKind() {
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedBy(
				query(
					filterBy(referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))),
					orderBy(attributeNatural(COMPOUND_CODE_WITH_PRIORITY, OrderDirection.ASC))
				)
			);

			assertRequested(requested, COMPOUND_SORT);
			assertNotRequested(
				requested,
				SchemaCapabilityKey.entityAttribute(COMPOUND_CODE_WITH_PRIORITY, Capability.SORT, Scope.LIVE),
				"The compound was recorded as if it were an attribute, which pools it with an attribute that may " +
					"legitimately carry the same name"
			);
		}

		@Test
		@DisplayName("A reference attribute is counted on its reference, not on the entity")
		void shouldAccumulateAReferenceAttributeUnderItsReference() {
			final Map<SchemaCapabilityKey, Long> filtering = capabilitiesRequestedBy(
				query(
					filterBy(
						referenceHaving(REFERENCE_CATEGORIES, attributeEquals(ATTRIBUTE_ORDER_IN_CATEGORY, 1L))
					)
				)
			);
			assertRequested(filtering, ORDER_IN_CATEGORY_FILTER);
			assertNotRequested(
				filtering,
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_ORDER_IN_CATEGORY, Capability.FILTER, Scope.LIVE),
				"The reference's attribute was recorded as an attribute of the entity, which pools two elements the " +
					"schema keeps apart"
			);

			final Map<SchemaCapabilityKey, Long> ordering = capabilitiesRequestedBy(
				query(
					filterBy(referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))),
					orderBy(
						referenceProperty(
							REFERENCE_CATEGORIES, attributeNatural(ATTRIBUTE_ORDER_IN_CATEGORY, OrderDirection.ASC)
						)
					)
				)
			);
			assertRequested(ordering, ORDER_IN_CATEGORY_SORT);
		}

		@Test
		@DisplayName("An attribute no query names is not requested by one that names its neighbours")
		void shouldNotRequestACapabilityTheQueryNeverNames() {
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedBy(
				query(
					filterBy(attributeEquals(ATTRIBUTE_CODE, "product-3")),
					orderBy(attributeNatural(ATTRIBUTE_PRIORITY, OrderDirection.DESC))
				)
			);

			// `ean` is filterable, of the same shape as `code`, and written by every product the fixture upserts - so
			// an accumulator that recorded whatever the schema declares, or whatever a write touched, fails here
			assertNotRequested(
				requested, EAN_FILTER,
				"A filterable attribute nothing has asked for was requested anyway - a capability advancing on every " +
					"query, which is how this could plausibly go wrong, would make the whole reading useless"
			);
			// the same query's own capabilities did land, so the absence above is a real negative rather than an
			// accumulator that recorded nothing whatsoever
			assertRequested(requested, CODE_FILTER);
			assertRequested(requested, PRIORITY_SORT);
		}

	}

	@Nested
	@DisplayName("Requested is not chosen")
	class RequestedIsNotChosen {

		@Test
		@DisplayName("A capability consulted on an index that lost the cost comparison still counts")
		void shouldAccumulateEvenWhenThePlannerPicksADifferentIndex() {
			// the reference filter makes the planner build a full candidate plan around the global index and another
			// around the category's own reduced one, translating `code` against both. On this fixture the reduced one
			// wins, so the global index - where `code`'s filter index actually lives for the whole collection - is
			// consulted and discarded. The two readings are meant to disagree here, and that disagreement is the
			// point: `requested` says the query would break without `filterable()` on `code`, whatever plan won
			final Query query = query(
				filterBy(
					and(
						attributeEquals(ATTRIBUTE_CODE, "product-3"),
						referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))
					)
				)
			);
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedBy(query);

			assertEquals(
				1L, reducedIndexOfCategory(QUERIED_CATEGORY).getActivity().getQueryCount(),
				"The test needs a candidate to have won on the reduced index; a cost-model change that reverses this " +
					"surfaces here first, and what has to be re-pointed is which index is expected to lose"
			);
			assertEquals(
				0L, globalIndex().getActivity().getQueryCount(),
				"The global index must have lost the cost comparison, otherwise there is no discarded candidate left " +
					"for this case to be about"
			);
			assertRequested(requested, CODE_FILTER);
		}

	}

	@Nested
	@DisplayName("Flush onto the holders")
	class Flush {

		@Test
		@DisplayName("An executed query moves the capability's request count by one and its update count by none")
		void shouldCountOneRequestPerExecutedQuery() {
			// end to end, through a real session: what the operator will eventually read is the collection's registry,
			// not a context nobody outside the planner can reach
			final SchemaCapabilityUsage holder = holderOf(CODE_FILTER);
			final long requestedBefore = holder.getRequestedCount();
			final long updatedBefore = holder.getUpdatedCount();
			assertTrue(
				updatedBefore > 0L,
				"The fixture's upserts must already have counted maintenance on `code`, otherwise the update side " +
					"standing still below proves nothing about the two counters being independent"
			);

			executeQuery(query(filterBy(attributeEquals(ATTRIBUTE_CODE, "product-3"))));

			assertEquals(
				requestedBefore + 1, holder.getRequestedCount(),
				"One query filtering by `code` must land exactly one request on the registry the collection holds"
			);
			assertEquals(
				updatedBefore, holder.getUpdatedCount(),
				"Reading must not have counted as maintenance - the two sides answer different questions and a query " +
					"that bumped both would make the comparison between them meaningless"
			);
			assertTrue(
				holder.getLastRequestedAtMillis() >= holder.getObservedSinceMillis(),
				"A requested capability must carry the stamp of it, not the `never` sentinel"
			);
		}

		@Test
		@DisplayName("Building the winning plan empties the accumulator, so any further build counts nothing")
		void shouldLeaveTheAccumulatorEmptyAfterTheWinningPlanWasBuilt() {
			final SchemaCapabilityUsage holder = holderOf(CODE_FILTER);
			final long before = holder.getRequestedCount();
			final QueryPlanningContext context = planningContextFor(
				query(filterBy(attributeEquals(ATTRIBUTE_CODE, "product-3")))
			);

			QueryPlanner.planQuery(context);

			assertEquals(before + 1, holder.getRequestedCount(), "The built plan must have flushed what it collected");
			assertTrue(
				context.drainRequestedCapabilities().isEmpty(),
				"The build left the accumulator populated - anything building this plan again would count the same " +
					"logical query a second time"
			);
		}

		@Test
		@DisplayName("A query verified against every alternative plan is still one request")
		void shouldCountOnceUnderTheAlternativeIndexVerification() {
			assertCountedOnceWhileTheWinningPlanIsBuiltRepeatedly(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS);

			// the same query leaves the global index at zero without this mode - see `RequestedIsNotChosen` - so a
			// reading above zero is proof the losing candidate really was built and executed here
			assertTrue(
				globalIndex().getActivity().getQueryCount() > 0L,
				"The losing candidate was never built, so this mode verified nothing and the case is vacuous"
			);
		}

		@Test
		@DisplayName("A query verified against its cacheable variants is still one request")
		void shouldCountOnceUnderTheCachingTreeVerification() {
			// what this pins is the preferred plan being built a second time to be returned. The other half of the
			// mode - equipping each cacheable variant with a sorter of its own, which re-plans the ordering *after*
			// that build already drained - is not reached on this fixture, whose filter formulas hold no
			// `CacheableFormula` for `CacheableVariantsGeneratingVisitor` to vary; that residual is documented on
			// `QueryPlanningContext#drainRequestedCapabilities` rather than asserted here
			assertCountedOnceWhileTheWinningPlanIsBuiltRepeatedly(DebugMode.VERIFY_POSSIBLE_CACHING_TREES);
		}

		@Test
		@DisplayName("A query the planner answers without selecting any index counts nothing")
		void shouldCountNothingWhenNoIndexIsSelected() {
			// index selection comes back empty for a scope this catalog holds no index in, and the planner returns the
			// empty plan before the filter is translated even once - so `code` is named by the query and still must
			// not be counted
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByExecuting(
				Query.query(
					collection(ENTITY_PRODUCT),
					filterBy(and(scope(Scope.ARCHIVED), attributeEquals(ATTRIBUTE_CODE, "product-3")))
				)
			);

			assertTrue(
				requested.isEmpty(),
				"The empty-plan short-circuit counted a request: " + requested
			);
		}

		/**
		 * Runs one multi-candidate query under a verification debug mode and asserts the two readings part ways: the
		 * winning index counts a query per plan built, the capability counts once.
		 *
		 * The per-index reading is what keeps this from passing vacuously - it proves the debug mode really did build
		 * the winning plan more than once, which is the hazard being pinned. Without it a mode that silently stopped
		 * verifying anything would look like a successful deduplication.
		 *
		 * @param debugMode the verification mode to enable
		 */
		private void assertCountedOnceWhileTheWinningPlanIsBuiltRepeatedly(@Nonnull DebugMode debugMode) {
			final Map<SchemaCapabilityKey, Long> requested = capabilitiesRequestedByExecuting(
				Query.query(
					collection(ENTITY_PRODUCT),
					filterBy(
						and(
							attributeEquals(ATTRIBUTE_CODE, "product-3"),
							referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(QUERIED_CATEGORY))
						)
					),
					orderBy(attributeNatural(ATTRIBUTE_PRIORITY, OrderDirection.DESC)),
					require(debug(debugMode))
				)
			);

			assertTrue(
				reducedIndexOfCategory(QUERIED_CATEGORY).getActivity().getQueryCount() > 1L,
				"`" + debugMode + "` did not build the winning plan more than once, so this case proves nothing " +
					"about a double flush"
			);
			assertRequested(requested, CODE_FILTER);
			assertRequested(requested, PRIORITY_SORT);
		}

	}

	/**
	 * Plans one query in a context of its own and reports the request counts it moved on the collection's registry.
	 *
	 * @param query the query to plan
	 * @return the capabilities whose count the query moved, and by how much
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> capabilitiesRequestedBy(@Nonnull Query query) {
		final Map<SchemaCapabilityKey, Long> before = requestedCounts();
		QueryPlanner.planQuery(planningContextFor(query));
		return requestedCountsSince(before);
	}

	/**
	 * Same reading as {@link #capabilitiesRequestedBy(Query)}, but for a query that goes through a real session and is
	 * actually executed - the only way to reach the debug modes, which execute the plans they verify.
	 *
	 * @param query the query to execute
	 * @return the capabilities whose count the query moved, and by how much
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> capabilitiesRequestedByExecuting(@Nonnull Query query) {
		final Map<SchemaCapabilityKey, Long> before = requestedCounts();
		executeQuery(query);
		return requestedCountsSince(before);
	}

	/**
	 * Executes one query the way a client would.
	 *
	 * @param query the query to execute
	 */
	private void executeQuery(@Nonnull Query query) {
		this.evita.queryCatalog(
			CATALOG,
			session -> {
				session.queryList(query, EntityReference.class);
			}
		);
	}

	/**
	 * Reads every request count the collection's registry currently holds.
	 *
	 * @return the counts, keyed by capability
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> requestedCounts() {
		final Map<SchemaCapabilityKey, Long> result = new HashMap<>();
		for (final UsageEntry entry : productCollection().getUsageRegistry().listUsages()) {
			result.put(entry.key(), entry.usage().getRequestedCount());
		}
		return result;
	}

	/**
	 * Reports how much each request count has moved since the snapshot was taken, dropping the ones that did not move.
	 *
	 * A difference rather than an absolute reading, so that neither the fixture's own writes nor a query an earlier
	 * case in the same method ran can be mistaken for the query under test.
	 *
	 * @param before counts read before the query ran
	 * @return the capabilities whose count moved, and by how much
	 */
	@Nonnull
	private Map<SchemaCapabilityKey, Long> requestedCountsSince(@Nonnull Map<SchemaCapabilityKey, Long> before) {
		final Map<SchemaCapabilityKey, Long> result = new LinkedHashMap<>();
		for (final UsageEntry entry : productCollection().getUsageRegistry().listUsages()) {
			final long delta = entry.usage().getRequestedCount() - before.getOrDefault(entry.key(), 0L);
			if (delta != 0L) {
				result.put(entry.key(), delta);
			}
		}
		return result;
	}

	/**
	 * Counts the candidate index sets the planner would cost for the query - the multiplier every deduplication case
	 * needs to be greater than one to prove anything.
	 *
	 * Run against a context of its own, so the index selection it performs cannot disturb the context the assertions
	 * are made on.
	 *
	 * @param query the query to analyse
	 * @return how many interchangeable index sets its filter offers
	 */
	private int candidatePlanCountOf(@Nonnull Query query) {
		final QueryPlanningContext context = planningContextFor(query);
		final IndexSelectionVisitor visitor = new IndexSelectionVisitor(context);
		context.getFilterBy().accept(visitor);
		return visitor.getTargetIndexes().size();
	}

	/**
	 * Builds a planning context for the query exactly as the session would, against the live product collection.
	 *
	 * The session is a mock: planning reads the catalog, the collection and its indexes, and touches the session only
	 * through paths that degrade gracefully without one. What matters for these tests is that the *collection* - and
	 * therefore its usage registry - is the real one.
	 *
	 * @param query the query to plan
	 * @return a fresh context, having collected nothing yet
	 */
	@Nonnull
	private QueryPlanningContext planningContextFor(@Nonnull Query query) {
		return productCollection().createQueryContext(
			new EvitaRequest(
				query.normalizeQuery(),
				OffsetDateTime.now(),
				EntityReference.class,
				null
			),
			Mockito.mock(EvitaSession.class)
		);
	}

	/**
	 * Asserts one logical query moved the capability's request count by exactly one.
	 *
	 * @param requested what the query moved
	 * @param key       the capability that must have moved
	 */
	private void assertRequested(
		@Nonnull Map<SchemaCapabilityKey, Long> requested,
		@Nonnull SchemaCapabilityKey key
	) {
		assertEquals(
			1L, (long) requested.getOrDefault(key, 0L),
			"The capability " + key + " must be counted exactly once per logical query, whatever the planner did " +
				"on the way there: " + requested
		);
	}

	/**
	 * Asserts the query left the capability's request count where it was.
	 *
	 * Deliberately a statement about the count rather than about the registry holding an entry at all: the update side
	 * resolves holders of its own for every element a write touches, so the presence of an entry proves nothing about
	 * what a query asked for.
	 *
	 * @param requested what the query moved
	 * @param key       the capability that must not have moved
	 * @param message   what it means if it did
	 */
	private void assertNotRequested(
		@Nonnull Map<SchemaCapabilityKey, Long> requested,
		@Nonnull SchemaCapabilityKey key,
		@Nonnull String message
	) {
		assertEquals(0L, (long) requested.getOrDefault(key, 0L), message + ": " + requested);
	}

	/**
	 * Reads the holder the collection's registry keeps for the key, asserting on the way that the registry hands the
	 * same instance back on every resolve - the identity the accumulator's deduplication rests on.
	 *
	 * @param key the capability
	 * @return its holder
	 */
	@Nonnull
	private SchemaCapabilityUsage holderOf(@Nonnull SchemaCapabilityKey key) {
		final SchemaCapabilityUsageRegistry registry = productCollection().getUsageRegistry();
		final SchemaCapabilityUsage holder = registry.resolve(key);
		assertSame(holder, registry.resolve(key), "The registry hands out a different holder for the same key");
		return holder;
	}

	/**
	 * Reads the per-referenced-entity index covering one category, whose {@link io.evitadb.index.IndexActivity} tells
	 * whether the planner chose it.
	 *
	 * @param categoryPrimaryKey the category the index is bound to
	 * @return the index
	 */
	@Nonnull
	private EntityIndex reducedIndexOfCategory(int categoryPrimaryKey) {
		final EntityIndex index = productCollection().getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_CATEGORIES, categoryPrimaryKey))
			)
		);
		assertNotNull(index, "No per-referenced-entity index covers category " + categoryPrimaryKey);
		return index;
	}

	/**
	 * Reads the collection's global index - the one every candidate plan not built on a reduced index uses.
	 *
	 * @return the index
	 */
	@Nonnull
	private EntityIndex globalIndex() {
		final EntityIndex index = productCollection().getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		assertNotNull(index, "The product collection holds no global index");
		return index;
	}

	/**
	 * Looks the live product collection up behind the public API - the registry it holds is engine-internal state.
	 *
	 * @return the collection
	 */
	@Nonnull
	private EntityCollection productCollection() {
		return ((Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG))
			.getCollectionForEntityInternal(ENTITY_PRODUCT)
			.orElseThrow(
				() -> new AssertionError("Catalog `" + CATALOG + "` holds no collection `" + ENTITY_PRODUCT + "`")
			);
	}

	/**
	 * Wraps a filter in the collection header every case here shares.
	 *
	 * @param filterBy the filter
	 * @return the query
	 */
	@Nonnull
	private static Query query(@Nonnull FilterBy filterBy) {
		return Query.query(collection(ENTITY_PRODUCT), filterBy);
	}

	/**
	 * Wraps a filter and an ordering in the collection header every case here shares.
	 *
	 * @param filterBy the filter
	 * @param orderBy  the ordering
	 * @return the query
	 */
	@Nonnull
	private static Query query(@Nonnull FilterBy filterBy, @Nonnull OrderBy orderBy) {
		return Query.query(collection(ENTITY_PRODUCT), filterBy, orderBy);
	}

	/**
	 * Builds a fixture with several candidate index sets to plan against and one element of every kind the query side
	 * can request: an entity attribute filtered on, one ordered by, a sortable compound, a reference attribute usable
	 * both ways, and a filterable attribute nothing ever names.
	 */
	private void buildCatalog() {
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_CATEGORY).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(
						ATTRIBUTE_CODE, String.class,
						thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE)
					)
					.withAttribute(ATTRIBUTE_PRIORITY, Long.class, thatIs -> thatIs.sortableInScope(Scope.LIVE))
					.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE))
					.withSortableAttributeCompound(
						COMPOUND_CODE_WITH_PRIORITY,
						new AttributeElement[]{
							AttributeElement.attributeElement(ATTRIBUTE_CODE),
							AttributeElement.attributeElement(ATTRIBUTE_PRIORITY)
						},
						thatIs -> thatIs.indexedInScope(Scope.LIVE)
					)
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(
								ATTRIBUTE_ORDER_IN_CATEGORY, Long.class,
								thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE)
							)
					)
					.updateVia(session);
				for (int i = 1; i <= CATEGORY_COUNT; i++) {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, i));
				}
				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					final int productPrimaryKey = i;
					final int categoryPrimaryKey = ((i - 1) % CATEGORY_COUNT) + 1;
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, productPrimaryKey)
							.setAttribute(ATTRIBUTE_CODE, "product-" + productPrimaryKey)
							.setAttribute(ATTRIBUTE_PRIORITY, (long) productPrimaryKey)
							.setAttribute(ATTRIBUTE_EAN, "ean-" + productPrimaryKey)
							.setReference(
								REFERENCE_CATEGORIES, categoryPrimaryKey,
								whichIs -> whichIs.setAttribute(
									ATTRIBUTE_ORDER_IN_CATEGORY, (long) productPrimaryKey
								)
							)
					);
				}
			}
		);
	}

	/**
	 * Builds the configuration of the embedded instance this test runs against.
	 *
	 * @return configuration rooted at this test's directories
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.build()
			)
			.build();
	}

}
