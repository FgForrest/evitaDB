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

package io.evitadb.api.functional.query;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.QueryTelemetryContent;
import io.evitadb.api.requestResponse.extraResult.FormulaPlan;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.StepMetric;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.attributeStartsWith;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.queryTelemetry;
import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REQUIRE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the *shape* of the {@link QueryTelemetry} tree that a client actually receives - specifically that the tree
 * handed to the client is rooted at {@link QueryPhase#OVERALL} and therefore still carries the whole
 * {@link QueryPhase#PLANNING} subtree.
 *
 * This is deliberately separate from asserting that some phase exists *somewhere* in the tree. A tree-walking
 * `containsPhase` check - the shape used by {@link PreferIndexScanDebugModeFunctionalTest} - cannot see this class of
 * defect: when the root is truncated to the `EXECUTION` node, every execution-phase step it looks for is still
 * reachable below that node, so the walk keeps succeeding while the planning half of the tree has silently vanished
 * from the response. The planning half is the part that carries the index-selection decision and its estimated costs,
 * which is the most valuable diagnostic the telemetry produces, so its loss is invisible exactly where it matters.
 *
 * The three assertions here are not redundant. The first catches a truncated root directly. The second catches the
 * *reachability* regression - a future refactor could hand back an `OVERALL`-labelled node that is not the real root,
 * which the first assertion alone would not notice. The third pins the payload the planning subtree exists to deliver.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Query telemetry is delivered rooted at the OVERALL phase")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REQUIRE)
class QueryTelemetryRootFunctionalTest implements EvitaTestSupport {

	private static final String ATTRIBUTE_CODE = "code";
	private static final int PRODUCT_COUNT = 4;
	/**
	 * `SelectionFormula#toString()`, which is what the plan reports as that node's description. Kept here rather
	 * than referenced from the engine class so that a change to the wording surfaces as a failure in the test that
	 * documents the behaviour, rather than silently following it.
	 */
	private static final String PREFETCH_FORMULA_DESCRIPTION = "APPLY PREDICATE ON PREFETCHED ENTITIES IF POSSIBLE";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("QueryTelemetryRoot");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		seed();
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("the root node reports the OVERALL phase")
	void shouldRootTelemetryAtOverallPhase() {
		final QueryTelemetry telemetry = queryTelemetryOf();
		assertEquals(
			QueryPhase.OVERALL, telemetry.getOperation(),
			"The telemetry handed to the client must be the root of the tree - a node reporting `" +
				telemetry.getOperation() + "` means the response carries only a subtree!"
		);

		// the root is captured while it is still open - `fabricateExtraResults` runs before the telemetry is
		// finalized - so its duration is only ever written by the later `finalizeTelemetry()` pass mutating the
		// already-handed-out object. A zero here would mean the client gets the tree without its total time
		assertTrue(
			telemetry.getSpentTime() > 0,
			"The root step must report a duration - the client reads it as the total query time!"
		);
	}

	@Test
	@DisplayName("the root node is stamped with the wall-clock instant the query began")
	void shouldStampRootTelemetryWithWallClockStart() {
		final OffsetDateTime before = OffsetDateTime.now();
		final QueryTelemetry telemetry = queryTelemetryOf();
		final OffsetDateTime after = OffsetDateTime.now();

		final OffsetDateTime startedAt = telemetry.getStartedAt();
		assertNotNull(
			startedAt,
			"The root step must carry the wall-clock instant of the query start - it is what anchors the whole " +
				"telemetry tree in time!"
		);
		assertFalse(
			startedAt.isBefore(before) || startedAt.isAfter(after),
			"The wall-clock stamp must be taken while the query runs, but " + startedAt + " lies outside <" +
				before + ", " + after + ">!"
		);

		// only the root anchors the tree - inner steps derive their position from `startedAt` plus their own offset
		for (final QueryTelemetry step : telemetry.getSteps()) {
			assertNull(step.getStartedAt(), "Only the root step may carry the wall-clock stamp!");
		}
	}

	@Test
	@DisplayName("the planning subtree survives into the delivered tree")
	void shouldExposePlanningSubtreeInTelemetry() {
		final QueryTelemetry telemetry = queryTelemetryOf();
		assertTrue(
			containsPhase(telemetry, QueryPhase.PLANNING),
			"The `PLANNING` step must be reachable from the delivered telemetry root!"
		);
		assertTrue(
			containsPhase(telemetry, QueryPhase.PLANNING_INDEX_USAGE),
			"The `PLANNING_INDEX_USAGE` step must be reachable - it records which indexes the planner considered!"
		);
		// asserting the planning half alone would still pass on a node that merely *looks* like the root, so pin
		// the execution half too - only the genuine root parents both
		assertTrue(
			containsPhase(telemetry, QueryPhase.EXECUTION),
			"The `EXECUTION` step must be reachable from the same root that carries `PLANNING`!"
		);
	}

	@Test
	@DisplayName("the selected index and its estimated cost reach the client")
	void shouldExposeSelectedIndexArgumentInTelemetry() {
		final QueryTelemetry telemetry = queryTelemetryOf();
		final QueryTelemetry planningFilter = findPhase(telemetry, QueryPhase.PLANNING_FILTER);
		assertNotNull(planningFilter, "The `PLANNING_FILTER` step must be reachable from the delivered root!");

		// this argument is written when the step is *finished* (`popStep(message)`), so it is the one piece of
		// planner reasoning - the chosen index plus its estimated cost - that escapes the engine to the client
		final String[] arguments = planningFilter.getArguments();
		assertEquals(
			1, arguments.length,
			"The `PLANNING_FILTER` step must carry exactly one argument naming the selected index!"
		);
		assertTrue(
			arguments[0].startsWith("Selected index:"),
			"The `PLANNING_FILTER` argument must name the selected index but was `" + arguments[0] + "`!"
		);
	}

	@Test
	@DisplayName("the query level metrics reach the client on the root node")
	void shouldExposeQueryMetricsOnTelemetryRoot() {
		final QueryTelemetry telemetry = queryTelemetryOf();

		// This is what pins the mutation window, and it is the reason this test lives here rather than beside the
		// unit tests. The metrics are recorded onto the root *after* `fabricateExtraResults` has already put that
		// root into the response - so the assertion can only pass because what travelled into the response is a
		// reference to the very node the engine keeps writing into, exactly as the root's `spentTime` relies on.
		// A well-meaning "return an immutable telemetry tree" refactor would empty every one of these while every
		// other test in this class kept passing.
		assertEquals(
			PRODUCT_COUNT, telemetry.getMetric(StepMetric.ACTUAL_CARDINALITY).orElseThrow(
				() -> new AssertionError("The root step must report how many records the filter matched!")
			),
			"All seeded products match the filter, so the actual cardinality must be the whole collection!"
		);
		assertEquals(
			PRODUCT_COUNT, telemetry.getMetric(StepMetric.RECORDS_RETURNED).orElseThrow(
				() -> new AssertionError("The root step must report how many records were handed back!")
			),
			"The result fits in a single page, so every matching record must have been returned!"
		);

		// the remaining values are real measurements whose exact magnitude depends on the data and the plan, so
		// only their presence is pinned - an unrecorded metric here would mean the engine stopped publishing it
		for (final StepMetric metric : StepMetric.values()) {
			assertTrue(
				telemetry.getMetric(metric).isPresent(),
				"The root step must report `" + metric + "` - every query level metric is computed either way!"
			);
		}
	}

	@Test
	@DisplayName("metrics stay on the root and are not repeated down the tree")
	void shouldNotRepeatQueryMetricsOnInnerSteps() {
		assertOnlyRootCarriesMetrics(queryTelemetryOf());
	}

	@Test
	@DisplayName("a nested query does not attach its own metrics to the step that spawned it")
	void shouldNotRecordMetricsForNestedQueries() {
		// This is the one place the "root only" contract could plausibly break. A nested query gets its own
		// `QueryPlanningContext`, and `EntityCollection#createQueryContext` seeds that context with the *current
		// step* rather than the tree root - so `getTelemetryRoot()` on a nested context returns an inner node. The
		// contract survives only because nested queries are planned through `QueryPlanner#planNestedQuery` and
		// never reach `QueryPlan#execute`, which is where metrics are recorded. That is a non-local fact spread
		// over three classes, so it is pinned here rather than reasoned about: a nested query really does run.
		final QueryTelemetry telemetry = queryTelemetryOfQueryWithNestedQuery();
		assertTrue(
			containsPhase(telemetry, QueryPhase.FETCHING_REFERENCES),
			"The query must actually fetch referenced entities, or it does not exercise a nested query at all!"
		);
		assertOnlyRootCarriesMetrics(telemetry);
	}

	@Test
	@DisplayName("no formula plan is built for a query that did not ask for one")
	void shouldNotBuildFormulaPlanUnlessRequested() {
		// the zero-cost guarantee for this feature, stated as an observable: plain `queryTelemetry()` walks no
		// formula tree and allocates no plan, anywhere in the profile
		assertNoPlanInSubtree(queryTelemetryOf());
	}

	@Test
	@DisplayName("the executed formula plan reaches the client on the root node")
	void shouldDeliverExecutedFormulaPlanOnRoot() {
		final FormulaPlan plan = queryTelemetryWithPlanOf().getPlan();
		assertNotNull(plan, "The root must carry the plan that was actually executed!");
		assertNotNull(plan.description(), "The described occurrence of a formula must say what it is!");
		assertNull(plan.refTo(), "The plan root can never be a back-reference - nothing precedes it!");
		// the winning plan is rendered after execution, so unlike the alternatives below it really has run
		assertNotNull(plan.actualCost(), "The executed plan must report the cost it really incurred!");
		assertNotNull(plan.resultCount(), "The executed plan must report how many records it produced!");
	}

	@Test
	@DisplayName("a rejected plan alternative is described but never executed")
	void shouldDescribeRejectedAlternativesWithoutExecutingThem() {
		// This is the assertion the whole non-forcing renderer exists for. The planner builds and costs one formula
		// per candidate index but executes only the winner; a renderer that called `compute()` to fill in the
		// numbers would make asking for a profile run the plans the engine had deliberately decided to skip -
		// telemetry would stop observing the query and start changing it. An alternative reporting a null
		// `actualCost` is the observable proof that never happened.
		final QueryTelemetry alternative = findPhase(
			queryTelemetryWithPlanOf(), QueryPhase.PLANNING_FILTER_ALTERNATIVE
		);
		assertNotNull(
			alternative,
			"The query must actually consider an index alternative, or this test asserts nothing!"
		);
		final FormulaPlan plan = alternative.getPlan();
		assertNotNull(plan, "A considered alternative must carry the candidate formula it was costed on!");
		assertNotNull(plan.description(), "The candidate must say what it is, even though it never ran!");
		// the estimate exists without running anything - it is what the candidate was ranked on
		assertNull(plan.actualCost(), "A plan rendered during planning cannot have a real cost - nothing has run!");
		assertNull(plan.resultCount(), "A plan rendered during planning cannot have a result count!");
	}

	@Test
	@DisplayName("a plan filtered over prefetched bodies reports its index branch as never executed")
	void shouldReportTheIndexBranchAsUnexecutedWhenFilteringOverPrefetchedBodies() {
		// The second way a node of the *winning* plan legitimately reports no outcome numbers, and the one most
		// likely to be misread. When the planner decides it is cheaper to fetch a handful of entity bodies and
		// filter over those, `SelectionFormula` evaluates its alternative and never computes its delegate - so
		// the entire index sub-tree below it is honestly reported as never having run, in a plan that did run.
		// Read against the `PREFETCHED` metric, which is what says why: without it this is indistinguishable
		// from a rejected alternative at a glance.
		final QueryTelemetry telemetry = queryTelemetryOfPrefetchedQuery();

		assertEquals(
			1L, telemetry.getMetric(StepMetric.PREFETCHED).orElseThrow(
				() -> new AssertionError("The root step must report whether the query filtered over prefetched bodies!")
			),
			"This query must actually take the prefetch path, or the assertions below assert nothing!"
		);

		final FormulaPlan plan = telemetry.getPlan();
		assertNotNull(plan, "The root must carry the plan that was actually executed!");

		final FormulaPlan prefetchNode = findPlanNodeByDescription(plan, PREFETCH_FORMULA_DESCRIPTION);
		assertNotNull(
			prefetchNode,
			"The executed plan must contain the prefetch-capable node, or the query did not take that path!"
		);
		// the node itself ran - it produced the result the query was answered from
		assertNotNull(prefetchNode.actualCost(), "The prefetch node itself really ran and must report its cost!");
		assertNotNull(prefetchNode.resultCount(), "The prefetch node itself really ran and must report a count!");

		// ...and everything below it did not, because the alternative answered the query instead
		assertFalse(
			prefetchNode.children().isEmpty(),
			"The prefetch node must still describe the index branch it skipped - structure is not outcome!"
		);
		for (final FormulaPlan skipped : prefetchNode.children()) {
			assertNull(
				skipped.actualCost(),
				"The index branch was skipped in favour of prefetched bodies and cannot have a real cost!"
			);
			assertNull(
				skipped.resultCount(),
				"The index branch was skipped in favour of prefetched bodies and cannot have a result count!"
			);
			assertNotNull(
				skipped.description(),
				"A skipped branch must still say what it would have done - that is the point of describing it!"
			);
		}
	}

	/**
	 * Asserts that no step anywhere in the passed tree carries a formula plan.
	 *
	 * @param step root of the subtree to check
	 */
	private static void assertNoPlanInSubtree(@Nonnull QueryTelemetry step) {
		assertNull(
			step.getPlan(),
			"Step " + step.getOperation() + " built a formula plan for a query that never asked for one!"
		);
		for (final QueryTelemetry innerStep : step.getSteps()) {
			assertNoPlanInSubtree(innerStep);
		}
	}

	/**
	 * Asserts that the passed tree carries query level metrics on its root and nowhere else, at any depth.
	 *
	 * The recursion is the point. Metrics describe the query as a whole, so a copy landing on an inner step would
	 * attribute the whole query's cardinality to whichever phase happened to be looked at - and the steps that could
	 * plausibly acquire one, through a nested query's own planning context, sit several levels down where a
	 * direct-children check could never see them.
	 *
	 * @param telemetry root of the tree to check
	 */
	private static void assertOnlyRootCarriesMetrics(@Nonnull QueryTelemetry telemetry) {
		assertTrue(telemetry.hasMetrics(), "The root step must carry the query level metrics!");
		for (final QueryTelemetry step : telemetry.getSteps()) {
			assertNoMetricsInSubtree(step);
		}
	}

	/**
	 * Asserts that neither the passed step nor anything nested below it carries metrics.
	 *
	 * @param step subtree to check
	 */
	private static void assertNoMetricsInSubtree(@Nonnull QueryTelemetry step) {
		assertFalse(
			step.hasMetrics(),
			"Only the root step may carry query level metrics, but `" + step.getOperation() + "` carries some!"
		);
		for (final QueryTelemetry child : step.getSteps()) {
			assertNoMetricsInSubtree(child);
		}
	}

	/**
	 * Builds a handful of products carrying a filterable code attribute in a warm-up catalog.
	 */
	private void seed() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// the brand collection exists so that a `referenceContent` query has somewhere to fetch from, which
				// is what makes the engine plan and run a genuinely nested query
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.updateVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.withReferenceToEntity(
						Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
						ReferenceSchemaEditor::indexed
					)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.BRAND, 1)
						.setAttribute(ATTRIBUTE_CODE, "garmin")
				);

				for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, pk)
							.setAttribute(ATTRIBUTE_CODE, "garmin-" + pk)
							.setReference(Entities.BRAND, 1)
					);
				}
			}
		);
	}

	/**
	 * Runs a query that forces a real index selection and returns the telemetry it produced.
	 *
	 * @return the telemetry extra result of the executed query
	 */
	@Nonnull
	private QueryTelemetry queryTelemetryOf() {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final EvitaResponse<EntityReference> response = session.query(
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(attributeStartsWith(ATTRIBUTE_CODE, "garmin")),
					require(queryTelemetry())
				),
				EntityReference.class
			);
			final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
			assertNotNull(telemetry, "Query telemetry must be present - it is the observable this test reads!");
			return telemetry;
		}
	}

	/**
	 * Runs a query that pulls in referenced entity bodies, which makes the engine plan and run a nested query, and
	 * returns the telemetry it produced.
	 *
	 * @return the telemetry extra result of the executed query
	 */
	@Nonnull
	private QueryTelemetry queryTelemetryOfQueryWithNestedQuery() {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final EvitaResponse<SealedEntity> response = session.query(
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(attributeStartsWith(ATTRIBUTE_CODE, "garmin")),
					require(
						entityFetch(referenceContent(Entities.BRAND, entityFetch(attributeContent(ATTRIBUTE_CODE)))),
						queryTelemetry()
					)
				),
				SealedEntity.class
			);
			final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
			assertNotNull(telemetry, "Query telemetry must be present - it is the observable this test reads!");
			return telemetry;
		}
	}

	/**
	 * Runs a query the planner answers by prefetching entity bodies rather than by consulting indexes: a small,
	 * conjunctive primary-key set combined with a body requirement is the shape that makes prefetching
	 * worthwhile, which is what puts a `SelectionFormula` into the plan and leaves its delegate uncomputed.
	 *
	 * @return the telemetry extra result of the executed query
	 */
	@Nonnull
	private QueryTelemetry queryTelemetryOfPrefetchedQuery() {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final EvitaResponse<SealedEntity> response = session.query(
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							entityPrimaryKeyInSet(1, 2),
							attributeStartsWith(ATTRIBUTE_CODE, "garmin")
						)
					),
					require(
						// the cost-based selector would not bother prefetching for a catalog this small, so the
						// path is forced rather than coaxed - the point of the test is the shape of the plan on
						// that path, not the heuristic that picks it
						debug(DebugMode.PREFER_PREFETCHING),
						entityFetch(attributeContent(ATTRIBUTE_CODE)),
						queryTelemetry(QueryTelemetryContent.PLAN)
					)
				),
				SealedEntity.class
			);
			final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
			assertNotNull(telemetry, "Query telemetry must be present - it is the observable this test reads!");
			return telemetry;
		}
	}

	/**
	 * Walks a formula plan depth-first and returns the first node whose description matches exactly.
	 *
	 * @param plan        the plan node to start from
	 * @param description the description to look for
	 * @return the matching node, or `null` when the plan contains none
	 */
	@Nullable
	private static FormulaPlan findPlanNodeByDescription(@Nonnull FormulaPlan plan, @Nonnull String description) {
		if (description.equals(plan.description())) {
			return plan;
		}
		for (final FormulaPlan child : plan.children()) {
			final FormulaPlan found = findPlanNodeByDescription(child, description);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	/**
	 * Runs the same query as {@link #queryTelemetryOf()} but additionally asking for the formula plan.
	 *
	 * @return the telemetry extra result of the executed query
	 */
	@Nonnull
	private QueryTelemetry queryTelemetryWithPlanOf() {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final EvitaResponse<EntityReference> response = session.query(
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(attributeStartsWith(ATTRIBUTE_CODE, "garmin")),
					require(queryTelemetry(QueryTelemetryContent.PLAN))
				),
				EntityReference.class
			);
			final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
			assertNotNull(telemetry, "Query telemetry must be present - it is the observable this test reads!");
			return telemetry;
		}
	}

	/**
	 * Walks the telemetry tree looking for a step of the given phase.
	 *
	 * @param telemetry the telemetry node to start from
	 * @param phase     the phase to look for
	 * @return true when the phase is present anywhere in the tree
	 */
	private static boolean containsPhase(@Nonnull QueryTelemetry telemetry, @Nonnull QueryPhase phase) {
		return findPhase(telemetry, phase) != null;
	}

	/**
	 * Walks the telemetry tree and returns the first step of the given phase.
	 *
	 * @param telemetry the telemetry node to start from
	 * @param phase     the phase to look for
	 * @return the first matching step, or `null` when the phase is absent from the tree
	 */
	@Nullable
	private static QueryTelemetry findPhase(@Nonnull QueryTelemetry telemetry, @Nonnull QueryPhase phase) {
		if (telemetry.getOperation() == phase) {
			return telemetry;
		}
		for (final QueryTelemetry step : telemetry.getSteps()) {
			final QueryTelemetry found = findPhase(step, phase);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

}
