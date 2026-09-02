/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.api.catalog.dataApi.dto;

import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.StepMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies that {@link QueryTelemetryDto} normalizes the raw {@link System#nanoTime()} readings of
 * {@link QueryTelemetry#getStart()} into offsets relative to the root step. The raw readings are taken on the server
 * and carry no epoch, so they are meaningless to a REST / GraphQL client - only the offset is.
 *
 * The same boundary is where the two values the engine does not carry are derived - `selfTime` and the wall-clock
 * `startedAt` rendering - so those are pinned here as well. All of it is asserted against hand-built trees with
 * constant timings rather than a measured query: the numbers a real query produces are not reproducible, which would
 * leave nothing to compare the derived values against.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(EXTERNAL_API)
@Tag(QUERY)
@DisplayName("QueryTelemetry DTO conversion for the JSON based APIs")
class QueryTelemetryDtoTest {
	/**
	 * Arbitrary but realistic `nanoTime` base - deliberately large, so that a conversion which forgets to subtract
	 * the root start cannot accidentally produce the expected offsets.
	 */
	private static final long BASE_NANO_TIME = 987_654_321_000L;

	/**
	 * The core property, asserted at every depth of the tree at once. The fourth level is what makes it more than a
	 * spot check: a conversion that normalized against the immediate parent instead of the root would still produce
	 * a zero root and correct first-level offsets, and only start disagreeing further down.
	 */
	@Test
	@DisplayName("Root step reports zero start and descendants report their offset from it")
	void shouldNormalizeStartToOffsetFromRootThroughWholeTree() {
		final QueryTelemetryDto converted = QueryTelemetryDto.from(createDeepTelemetryTree());

		// the root is by definition the zero point of the query
		assertEquals(0L, converted.start());

		final QueryTelemetryDto planning = childAt(converted, 0);
		assertEquals(100L, planning.start());

		final QueryTelemetryDto planningFilter = childAt(planning, 0);
		assertEquals(150L, planningFilter.start());

		// fourth level - guards the recursion actually threads the root start all the way down
		final QueryTelemetryDto planningFilterAlternative = childAt(planningFilter, 0);
		assertEquals(175L, planningFilterAlternative.start());

		final QueryTelemetryDto execution = childAt(converted, 1);
		assertEquals(400L, execution.start());
	}

	/**
	 * Guards the other side of the conversion: everything that is *not* the start must survive it verbatim. Phase,
	 * duration and arguments are checked on a nested node as well as on the root, since the recursive branch and
	 * the entry point are separate code paths and only one of them would break.
	 */
	@Test
	@DisplayName("Normalization leaves the remaining contents of the node untouched")
	void shouldKeepOtherPropertiesIntactWhenNormalizingStart() {
		final QueryTelemetryDto converted = QueryTelemetryDto.from(createDeepTelemetryTree());

		assertEquals(QueryPhase.OVERALL.toString(), converted.operation());
		assertEquals(500L, converted.spentTime());
		assertTrue(converted.formattedSpentTime().length() > 0);
		assertEquals(List.of(), converted.arguments());

		final QueryTelemetryDto planningFilter = childAt(childAt(converted, 0), 0);
		assertEquals(QueryPhase.PLANNING_FILTER.toString(), planningFilter.operation());
		assertEquals(30L, planningFilter.spentTime());
		assertEquals(List.of("Selected index: PRODUCT"), planningFilter.arguments());
	}

	/**
	 * Pins the derivation itself, and pins that it subtracts *direct* children only. The tree is deep enough for the
	 * difference to show: an implementation summing all descendants would compute the root as `500 - 220` instead of
	 * `500 - 180`, double counting the time already accounted for one level down.
	 */
	@Test
	@DisplayName("Self time is the step's own time, with the time claimed by its children taken out")
	void shouldDeriveSelfTimeAsSpentTimeLessChildrenTime() {
		final QueryTelemetryDto converted = QueryTelemetryDto.from(createDeepTelemetryTree());

		// root spends 500 with children claiming 100 (PLANNING) + 80 (EXECUTION)
		assertEquals(320L, converted.selfTime());
		assertTrue(converted.formattedSelfTime().length() > 0);

		// PLANNING spends 100 with its sole child claiming 30
		final QueryTelemetryDto planning = childAt(converted, 0);
		assertEquals(70L, planning.selfTime());

		// PLANNING_FILTER spends 30 with its sole child claiming 10
		final QueryTelemetryDto planningFilter = childAt(planning, 0);
		assertEquals(20L, planningFilter.selfTime());
	}

	/**
	 * The boundary case of the derivation - with no children there is nothing to subtract, so the two durations must
	 * coincide. It is the shape most steps in a real profile have, and the one a client is most likely to render
	 * without checking, so it must not come out as zero.
	 */
	@Test
	@DisplayName("A childless step spends all of its time on itself")
	void shouldReportSelfTimeEqualToSpentTimeForLeafStep() {
		final QueryTelemetryDto converted = QueryTelemetryDto.from(createDeepTelemetryTree());

		final QueryTelemetryDto execution = childAt(converted, 1);
		assertEquals(List.of(), execution.steps());
		assertEquals(execution.spentTime(), execution.selfTime());
	}

	/**
	 * Pins the clamp, which exists precisely because this input cannot arise from measuring - steps nest on a stack,
	 * so a child can never outlast its parent. Deserialization and hand-built trees carry no such guarantee, and a
	 * negative duration reaching a client is nonsense it has no way to render. Written with the deserializing
	 * constructor rather than the shared fixture, since the fixture deliberately builds only consistent trees.
	 */
	@Test
	@DisplayName("Self time never goes negative on a hand-assembled tree whose children outlast their parent")
	void shouldClampSelfTimeAtZeroWhenChildrenOutlastParent() {
		// the engine cannot produce this - steps nest on a stack - but a deserialized or hand-built tree can
		final QueryTelemetry overlongChild = new QueryTelemetry(
			QueryPhase.EXECUTION, BASE_NANO_TIME, 900L, new String[0], new QueryTelemetry[0]
		);
		final QueryTelemetry root = new QueryTelemetry(
			QueryPhase.OVERALL, BASE_NANO_TIME, 100L, new String[0], new QueryTelemetry[]{overlongChild}
		);

		assertEquals(0L, QueryTelemetryDto.from(root).selfTime());
	}

	/**
	 * Pins both halves of the wall-clock contract: the root renders the instant in ISO-8601 offset form, and no
	 * other node repeats it. The "only" half is the one worth a test - a conversion that propagated the stamp down
	 * the tree would still satisfy every client that reads it from the root, while doubling the payload of a large
	 * profile and inviting clients to trust a value that says nothing about when that particular step ran.
	 */
	@Test
	@DisplayName("Wall-clock start of the query is carried on the root step in ISO-8601 form")
	void shouldExposeStartedAtOnRootStepOnly() {
		final OffsetDateTime startedAt = OffsetDateTime.of(2026, 7, 30, 12, 34, 56, 0, ZoneOffset.ofHours(2));
		final QueryTelemetryDto converted = QueryTelemetryDto.from(createDeepTelemetryTree(startedAt));

		assertEquals(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(startedAt), converted.startedAt());

		// only the root anchors the tree in time - every other node derives its position from `startedAt` + `start`
		final QueryTelemetryDto planning = childAt(converted, 0);
		assertNull(planning.startedAt());
		assertNull(childAt(planning, 0).startedAt());
		assertNull(childAt(converted, 1).startedAt());
	}

	/**
	 * A tree assembled without the stamp - which is what any node built through the non-root constructors is, and
	 * what deserialized older payloads are - must convert rather than fail while formatting a `null` instant.
	 */
	@Test
	@DisplayName("A tree with no wall-clock stamp converts without one")
	void shouldTolerateMissingStartedAt() {
		final QueryTelemetryDto converted = QueryTelemetryDto.from(createDeepTelemetryTree());

		assertNull(converted.startedAt());
	}

	/**
	 * The degenerate tree the engine legitimately produces - a query whose planning short-circuits, or a dry run,
	 * yields a bare root with no steps at all. It has to normalize to zero and keep its duration, because this is a
	 * shape clients will actually receive and not an artificial edge case.
	 */
	@Test
	@DisplayName("A childless root still normalizes to zero")
	void shouldNormalizeStartOfSoleRootStep() {
		final QueryTelemetry lonelyRoot = new QueryTelemetry(
			QueryPhase.OVERALL, BASE_NANO_TIME, 42L, new String[0], new QueryTelemetry[0]
		);

		final QueryTelemetryDto converted = QueryTelemetryDto.from(lonelyRoot);

		assertEquals(0L, converted.start());
		assertEquals(42L, converted.spentTime());
		assertEquals(List.of(), converted.steps());
	}

	/**
	 * Pins that a step the engine measured nothing on publishes no metrics object at all, rather than one whose
	 * every property is null. This is the shape of nearly every node in a real profile - metrics live on the root -
	 * so getting it wrong would attach an empty object to every node of a large tree.
	 */
	@Test
	@DisplayName("A step with no recorded measurement carries no metrics object")
	void shouldOmitMetricsForStepWithoutMeasurements() {
		final QueryTelemetryDto converted = QueryTelemetryDto.from(createDeepTelemetryTree());

		assertNull(converted.metrics());
		assertNull(childAt(converted, 0).metrics());
		assertNull(childAt(childAt(converted, 0), 0).metrics());
	}

	/**
	 * Pins the mapping of the whole metric vocabulary in one pass, each with a distinct value so that a conversion
	 * which crosses two of them over cannot pass by coincidence. The flag is asserted as a real boolean rather than
	 * the `1` the engine packs it as - unpacking is the boundary's job, and leaving it to clients is exactly the
	 * kind of decoding this DTO exists to remove.
	 */
	@Test
	@DisplayName("Recorded metrics are published as named values, with the flag unpacked to a boolean")
	void shouldPublishRecordedMetricsAsNamedValues() {
		final QueryTelemetry root = createDeepTelemetryTree();
		root
			.recordMetric(StepMetric.ESTIMATED_CARDINALITY, 1_000L)
			.recordMetric(StepMetric.ACTUAL_CARDINALITY, 42L)
			.recordMetric(StepMetric.ESTIMATED_COST, 585L)
			.recordMetric(StepMetric.ACTUAL_COST, 610L)
			.recordMetric(StepMetric.RECORDS_RETURNED, 20L)
			.recordMetric(StepMetric.IO_FETCH_COUNT, 7L)
			.recordMetric(StepMetric.IO_FETCHED_SIZE_BYTES, 4_096L)
			.recordMetric(StepMetric.PREFETCHED, true);

		final QueryTelemetryMetricsDto metrics = QueryTelemetryDto.from(root).metrics();

		assertNotNull(metrics);
		assertEquals(1_000L, metrics.estimatedCardinality());
		assertEquals(42L, metrics.actualCardinality());
		assertEquals(585L, metrics.estimatedCost());
		assertEquals(610L, metrics.actualCost());
		assertEquals(20L, metrics.recordsReturned());
		assertEquals(7L, metrics.ioFetchCount());
		assertEquals(4_096L, metrics.ioFetchedSizeBytes());
		assertEquals(Boolean.TRUE, metrics.prefetched());
	}

	/**
	 * The distinction the whole optional shape exists for. `ioFetchCount` of zero is a real measurement - a query
	 * answered from indexes performs no storage reads - while an unrecorded `recordsReturned` means the engine never
	 * counted it. A conversion that defaulted absent metrics to zero would report a query that returned nothing.
	 */
	@Test
	@DisplayName("A metric measured as zero is published, one never measured stays null")
	void shouldDistinguishMeasuredZeroFromUnmeasuredMetric() {
		final QueryTelemetry root = createDeepTelemetryTree();
		root
			.recordMetric(StepMetric.IO_FETCH_COUNT, 0L)
			.recordMetric(StepMetric.PREFETCHED, false);

		final QueryTelemetryMetricsDto metrics = QueryTelemetryDto.from(root).metrics();

		assertNotNull(metrics);
		assertEquals(0L, metrics.ioFetchCount());
		assertEquals(Boolean.FALSE, metrics.prefetched());
		assertNull(metrics.recordsReturned());
		assertNull(metrics.estimatedCardinality());
	}

	/**
	 * Metrics belong to the node they were recorded on and must not be hoisted to the root or pushed down the tree.
	 * The engine records them on the root today, but nothing in the format says it always will - item 4 of the
	 * originating issue names per-node `FETCHING` metrics as the next step - so the conversion has to be positional
	 * rather than assume where they live.
	 */
	@Test
	@DisplayName("Metrics stay on the step they were recorded on")
	void shouldKeepMetricsOnTheirOwnStep() {
		final QueryTelemetry root = createDeepTelemetryTree();
		root.getSteps().get(1).recordMetric(StepMetric.IO_FETCH_COUNT, 3L);

		final QueryTelemetryDto converted = QueryTelemetryDto.from(root);

		assertNull(converted.metrics());
		assertNull(childAt(converted, 0).metrics());
		assertNotNull(childAt(converted, 1).metrics());
		assertEquals(3L, childAt(converted, 1).metrics().ioFetchCount());
	}

	/**
	 * Builds a deterministic four level deep telemetry tree whose starts are known constants, so that the expected
	 * offsets can be asserted exactly.
	 *
	 * @return a tree without the wall-clock stamp, i.e. the shape a non-root construction produces
	 */
	@Nonnull
	private static QueryTelemetry createDeepTelemetryTree() {
		return createDeepTelemetryTree(null);
	}

	/**
	 * Builds the same tree as {@link #createDeepTelemetryTree()}, additionally stamping the root with the passed
	 * wall-clock instant of the query start.
	 *
	 * The tree mirrors the shape of a real profile - a deep `PLANNING` branch beside a shallow `EXECUTION` one - and
	 * its durations are chosen so that no two steps share a `spentTime` or a `selfTime`, and so that no step with
	 * children has the two equal - a conversion that mixes two nodes up therefore cannot pass by accident.
	 *
	 * @param startedAt wall-clock instant to stamp the root with, or `null` for a tree with no anchor
	 * @return the root of the freshly built tree
	 */
	@Nonnull
	private static QueryTelemetry createDeepTelemetryTree(@Nullable OffsetDateTime startedAt) {
		final QueryTelemetry planningFilterAlternative = new QueryTelemetry(
			QueryPhase.PLANNING_FILTER_ALTERNATIVE, BASE_NANO_TIME + 175L, 10L,
			new String[0], new QueryTelemetry[0]
		);
		final QueryTelemetry planningFilter = new QueryTelemetry(
			QueryPhase.PLANNING_FILTER, BASE_NANO_TIME + 150L, 30L,
			new String[] {"Selected index: PRODUCT"}, new QueryTelemetry[] {planningFilterAlternative}
		);
		final QueryTelemetry planning = new QueryTelemetry(
			QueryPhase.PLANNING, BASE_NANO_TIME + 100L, 100L,
			new String[0], new QueryTelemetry[] {planningFilter}
		);
		final QueryTelemetry execution = new QueryTelemetry(
			QueryPhase.EXECUTION, BASE_NANO_TIME + 400L, 80L,
			new String[0], new QueryTelemetry[0]
		);
		return new QueryTelemetry(
			QueryPhase.OVERALL, BASE_NANO_TIME, 500L, startedAt,
			new String[0], new QueryTelemetry[] {planning, execution}
		);
	}

	/**
	 * Reads a single child of a converted node, keeping the assertions above readable when they walk several levels
	 * down. It intentionally does not guard the index - an out-of-range access means the conversion dropped a step,
	 * which is a failure worth surfacing as loudly as possible.
	 *
	 * @param parent converted node to descend from
	 * @param index  position of the child among {@link QueryTelemetryDto#steps()}
	 * @return the child at that position
	 */
	@Nonnull
	private static QueryTelemetryDto childAt(@Nonnull QueryTelemetryDto parent, int index) {
		return parent.steps().get(index);
	}
}
