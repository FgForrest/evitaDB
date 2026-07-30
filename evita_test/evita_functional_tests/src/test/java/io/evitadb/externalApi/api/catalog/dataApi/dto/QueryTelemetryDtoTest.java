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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies that {@link QueryTelemetryDto} normalizes the raw {@link System#nanoTime()} readings of
 * {@link QueryTelemetry#getStart()} into offsets relative to the root step. The raw readings are taken on the server
 * and carry no epoch, so they are meaningless to a REST / GraphQL client - only the offset is.
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

	@Test
	@DisplayName("A tree with no wall-clock stamp converts without one")
	void shouldTolerateMissingStartedAt() {
		final QueryTelemetryDto converted = QueryTelemetryDto.from(createDeepTelemetryTree());

		assertNull(converted.startedAt());
	}

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
	 * Builds a deterministic four level deep telemetry tree whose starts are known constants, so that the expected
	 * offsets can be asserted exactly.
	 */
	@Nonnull
	private static QueryTelemetry createDeepTelemetryTree() {
		return createDeepTelemetryTree(null);
	}

	/**
	 * Builds the same tree as {@link #createDeepTelemetryTree()}, additionally stamping the root with the passed
	 * wall-clock instant of the query start.
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

	@Nonnull
	private static QueryTelemetryDto childAt(@Nonnull QueryTelemetryDto parent, int index) {
		return parent.steps().get(index);
	}
}
