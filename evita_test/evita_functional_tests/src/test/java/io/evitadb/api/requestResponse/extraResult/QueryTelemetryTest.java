/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse.extraResult;

import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link QueryTelemetry} contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("QueryTelemetry")
class QueryTelemetryTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Constructor with operation and arguments")
	class LiveConstructor {

		@Test
		@DisplayName("should create telemetry with operation")
		void shouldCreateTelemetryWithOperation() {
			final QueryTelemetry telemetry = new QueryTelemetry(QueryPhase.OVERALL);
			assertEquals(QueryPhase.OVERALL, telemetry.getOperation());
			assertTrue(telemetry.getStart() > 0);
			assertEquals(0, telemetry.getSpentTime());
			assertTrue(telemetry.getSteps().isEmpty());
		}

		@Test
		@DisplayName("should create telemetry with arguments")
		void shouldCreateTelemetryWithArguments() {
			final QueryTelemetry telemetry = new QueryTelemetry(QueryPhase.PLANNING, "arg1", "arg2");
			assertEquals(QueryPhase.PLANNING, telemetry.getOperation());
			assertArrayEquals(new String[]{"arg1", "arg2"}, telemetry.getArguments());
		}

		@Test
		@DisplayName("should create telemetry with no arguments")
		void shouldCreateTelemetryWithNoArguments() {
			final QueryTelemetry telemetry = new QueryTelemetry(QueryPhase.EXECUTION);
			assertEquals(0, telemetry.getArguments().length);
		}
	}

	@Nested
	@DisplayName("Deserialization constructor")
	class DeserializationConstructor {

		@Test
		@DisplayName("should create telemetry from deserialized data")
		void shouldCreateTelemetryFromDeserializedData() {
			final QueryTelemetry step = new QueryTelemetry(
				QueryPhase.PLANNING_FILTER,
				100L, 500L,
				new String[]{"filter1"},
				new QueryTelemetry[0]
			);
			assertEquals(QueryPhase.PLANNING_FILTER, step.getOperation());
			assertEquals(100L, step.getStart());
			assertEquals(500L, step.getSpentTime());
			assertArrayEquals(new String[]{"filter1"}, step.getArguments());
			assertTrue(step.getSteps().isEmpty());
		}

		@Test
		@DisplayName("should populate steps from deserialized data")
		void shouldPopulateStepsFromDeserializedData() {
			final QueryTelemetry innerStep = new QueryTelemetry(
				QueryPhase.PLANNING_FILTER,
				100L, 200L,
				new String[]{},
				new QueryTelemetry[0]
			);
			final QueryTelemetry telemetry = new QueryTelemetry(
				QueryPhase.PLANNING,
				50L, 1000L,
				new String[]{},
				new QueryTelemetry[]{innerStep}
			);
			assertEquals(1, telemetry.getSteps().size());
			assertEquals(QueryPhase.PLANNING_FILTER, telemetry.getSteps().get(0).getOperation());
		}
	}

	@Nested
	@DisplayName("finish methods")
	class FinishMethods {

		@Test
		@DisplayName("should set spent time on finish")
		void shouldSetSpentTimeOnFinish() {
			final QueryTelemetry telemetry = new QueryTelemetry(QueryPhase.OVERALL);
			// small delay to ensure non-zero spent time
			final QueryTelemetry result = telemetry.finish();
			assertSame(telemetry, result);
			assertTrue(telemetry.getSpentTime() >= 0);
		}

		@Test
		@DisplayName("should set arguments on finish with arguments")
		void shouldSetArgumentsOnFinishWithArguments() {
			final QueryTelemetry telemetry = new QueryTelemetry(QueryPhase.OVERALL);
			telemetry.finish("result1", "result2");
			assertArrayEquals(new String[]{"result1", "result2"}, telemetry.getArguments());
		}

		@Test
		@DisplayName("finish with arguments should reject when arguments already set")
		void finishWithArgumentsShouldRejectWhenArgumentsAlreadySet() {
			final QueryTelemetry telemetry = new QueryTelemetry(QueryPhase.OVERALL, "existing");
			assertThrows(Exception.class, () -> telemetry.finish("new"));
		}

		@Test
		@DisplayName("should produce consistent timing when finish called twice")
		void shouldProduceConsistentTimingWhenFinishCalledTwice() {
			final long start = System.nanoTime();
			final QueryTelemetry telemetry = new QueryTelemetry(
				QueryPhase.OVERALL,
				start, 0L,
				new String[]{},
				new QueryTelemetry[0]
			);
			telemetry.finish();
			final long firstFinish = telemetry.getSpentTime();
			telemetry.finish();
			final long secondFinish = telemetry.getSpentTime();
			// With = assignment, finish() recalculates from start each time
			// both values should be similar (second slightly larger due to elapsed time)
			assertTrue(firstFinish >= 0);
			assertTrue(secondFinish >= firstFinish);
		}
	}

	@Nested
	@DisplayName("addStep methods")
	class AddStepMethods {

		@Test
		@DisplayName("should add step with operation and arguments")
		void shouldAddStepWithOperationAndArguments() {
			final QueryTelemetry telemetry = new QueryTelemetry(QueryPhase.OVERALL);
			final QueryTelemetry step = telemetry.addStep(QueryPhase.PLANNING, "arg1");
			assertNotNull(step);
			assertEquals(QueryPhase.PLANNING, step.getOperation());
			assertEquals(1, telemetry.getSteps().size());
		}

		@Test
		@DisplayName("should add existing telemetry as step")
		void shouldAddExistingTelemetryAsStep() {
			final QueryTelemetry telemetry = new QueryTelemetry(QueryPhase.OVERALL);
			final QueryTelemetry existingStep = new QueryTelemetry(QueryPhase.EXECUTION);
			telemetry.addStep(existingStep);
			assertEquals(1, telemetry.getSteps().size());
			assertSame(existingStep, telemetry.getSteps().get(0));
		}

		@Test
		@DisplayName("should support multiple nested steps")
		void shouldSupportMultipleNestedSteps() {
			final QueryTelemetry telemetry = new QueryTelemetry(QueryPhase.OVERALL);
			telemetry.addStep(QueryPhase.PLANNING);
			telemetry.addStep(QueryPhase.EXECUTION);
			telemetry.addStep(QueryPhase.FETCHING);
			assertEquals(3, telemetry.getSteps().size());
		}
	}

	@Nested
	@DisplayName("toString")
	class ToString {

		@Test
		@DisplayName("should produce non-empty string")
		void shouldProduceNonEmptyString() {
			final QueryTelemetry telemetry = new QueryTelemetry(QueryPhase.OVERALL);
			telemetry.finish();
			final String result = telemetry.toString();
			assertNotNull(result);
			assertFalse(result.isEmpty());
		}

		@Test
		@DisplayName("should contain operation name")
		void shouldContainOperationName() {
			final QueryTelemetry telemetry = new QueryTelemetry(QueryPhase.OVERALL);
			telemetry.finish();
			assertTrue(telemetry.toString().contains("OVERALL"));
		}

		@Test
		@DisplayName("should contain arguments")
		void shouldContainArguments() {
			final QueryTelemetry telemetry = new QueryTelemetry(
				QueryPhase.PLANNING,
				100L, 500L,
				new String[]{"myArgument"},
				new QueryTelemetry[0]
			);
			final String result = telemetry.toString();
			assertTrue(result.contains("myArgument"));
		}

		@Test
		@DisplayName("should indent nested steps")
		void shouldIndentNestedSteps() {
			final QueryTelemetry telemetry = new QueryTelemetry(
				QueryPhase.OVERALL,
				100L, 1000L,
				new String[]{},
				new QueryTelemetry[]{
					new QueryTelemetry(
						QueryPhase.PLANNING,
						100L, 500L,
						new String[]{},
						new QueryTelemetry[0]
					)
				}
			);
			final String result = telemetry.toString();
			// nested step should be indented
			assertTrue(result.contains("     PLANNING"));
		}
	}

	@Nested
	@DisplayName("equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal when same data")
		void shouldBeEqualWhenSameData() {
			final QueryTelemetry one = new QueryTelemetry(
				QueryPhase.OVERALL,
				100L, 500L,
				new String[]{"arg1"},
				new QueryTelemetry[0]
			);
			final QueryTelemetry two = new QueryTelemetry(
				QueryPhase.OVERALL,
				100L, 500L,
				new String[]{"arg1"},
				new QueryTelemetry[0]
			);
			assertEquals(one, two);
			assertEquals(one.hashCode(), two.hashCode());
		}

		@Test
		@DisplayName("should not be equal when different operation")
		void shouldNotBeEqualWhenDifferentOperation() {
			final QueryTelemetry one = new QueryTelemetry(
				QueryPhase.OVERALL,
				100L, 500L,
				new String[]{},
				new QueryTelemetry[0]
			);
			final QueryTelemetry two = new QueryTelemetry(
				QueryPhase.PLANNING,
				100L, 500L,
				new String[]{},
				new QueryTelemetry[0]
			);
			assertNotEquals(one, two);
		}
	}

	@Nested
	@DisplayName("QueryPhase enum")
	class QueryPhaseEnum {

		@Test
		@DisplayName("should have all expected phases")
		void shouldHaveAllExpectedPhases() {
			final QueryPhase[] phases = QueryPhase.values();
			assertTrue(phases.length > 0);
			// verify some key phases exist
			assertNotNull(QueryPhase.OVERALL);
			assertNotNull(QueryPhase.PLANNING);
			assertNotNull(QueryPhase.EXECUTION);
			assertNotNull(QueryPhase.FETCHING);
		}

		@Test
		@DisplayName("should resolve from name")
		void shouldResolveFromName() {
			assertEquals(QueryPhase.OVERALL, QueryPhase.valueOf("OVERALL"));
			assertEquals(QueryPhase.PLANNING, QueryPhase.valueOf("PLANNING"));
		}
	}
}
