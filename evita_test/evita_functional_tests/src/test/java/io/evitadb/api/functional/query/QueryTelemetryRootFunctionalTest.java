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
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
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

import static io.evitadb.api.query.QueryConstraints.attributeStartsWith;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.queryTelemetry;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REQUIRE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

	/**
	 * Builds a handful of products carrying a filterable code attribute in a warm-up catalog.
	 */
	private void seed() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.updateVia(session);

				for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, pk)
							.setAttribute(ATTRIBUTE_CODE, "garmin-" + pk)
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
