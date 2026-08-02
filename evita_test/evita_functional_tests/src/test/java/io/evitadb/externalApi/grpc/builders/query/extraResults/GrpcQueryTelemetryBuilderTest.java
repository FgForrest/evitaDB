/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.grpc.builders.query.extraResults;

import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetry;
import io.evitadb.externalApi.grpc.testUtils.GrpcAssertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Tag;

import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies functionalities of methods in {@link GrpcQueryTelemetryBuilder} class.
 *
 * @author Tomáš Pozler, 2022
 */
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(QUERY)
class GrpcQueryTelemetryBuilderTest {
	private static final Random random = new Random();
	private static final int queryPhaseCount = QueryPhase.values().length;

	/**
	 * Arbitrary but realistic `nanoTime` base - deliberately large, so that a conversion which forgets to subtract
	 * the root start cannot accidentally produce the expected offsets.
	 */
	private static final long BASE_NANO_TIME = 987_654_321_000L;

	@Test
	void buildQueryTelemetry() {
		final QueryTelemetry queryTelemetry = createRandomQueryTelemetry();
		final GrpcQueryTelemetry grpcQueryTelemetry = GrpcQueryTelemetryBuilder.buildQueryTelemetry(queryTelemetry);
		GrpcAssertions.assertQueryTelemetry(queryTelemetry, grpcQueryTelemetry);
	}

	@Test
	void shouldNormalizeStartToOffsetFromRootThroughWholeTree() {
		final GrpcQueryTelemetry converted = GrpcQueryTelemetryBuilder.buildQueryTelemetry(createDeepQueryTelemetry());

		// the root is by definition the zero point of the query
		assertEquals(0L, converted.getStart());

		final GrpcQueryTelemetry planning = converted.getSteps(0);
		assertEquals(100L, planning.getStart());

		final GrpcQueryTelemetry planningFilter = planning.getSteps(0);
		assertEquals(150L, planningFilter.getStart());

		// fourth level - guards the recursion actually threads the root start all the way down
		assertEquals(175L, planningFilter.getSteps(0).getStart());

		assertEquals(400L, converted.getSteps(1).getStart());
	}

	@Test
	void shouldCarryStartedAtOnRootStepOnly() {
		final OffsetDateTime startedAt = OffsetDateTime.of(2026, 7, 30, 12, 34, 56, 0, ZoneOffset.ofHours(2));
		final GrpcQueryTelemetry converted = GrpcQueryTelemetryBuilder.buildQueryTelemetry(createDeepQueryTelemetry(startedAt));

		assertTrue(converted.hasStartedAt());
		assertEquals(startedAt, EvitaDataTypesConverter.toOffsetDateTime(converted.getStartedAt()));

		// only the root anchors the tree in time
		final GrpcQueryTelemetry planning = converted.getSteps(0);
		assertFalse(planning.hasStartedAt());
		assertFalse(planning.getSteps(0).hasStartedAt());
		assertFalse(converted.getSteps(1).hasStartedAt());
	}

	@Test
	void shouldTolerateMissingStartedAt() {
		assertFalse(GrpcQueryTelemetryBuilder.buildQueryTelemetry(createDeepQueryTelemetry()).hasStartedAt());
	}

	/**
	 * Builds a deterministic four level deep telemetry tree whose starts are known constants, so that the expected
	 * offsets can be asserted exactly.
	 */
	@Nonnull
	private static QueryTelemetry createDeepQueryTelemetry() {
		return createDeepQueryTelemetry(null);
	}

	/**
	 * Builds the same tree as {@link #createDeepQueryTelemetry()}, additionally stamping the root with the passed
	 * wall-clock instant of the query start.
	 */
	@Nonnull
	private static QueryTelemetry createDeepQueryTelemetry(@Nullable OffsetDateTime startedAt) {
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
	private QueryTelemetry createRandomQueryTelemetry() {
		final QueryTelemetry queryTelemetry = new QueryTelemetry(QueryPhase.EXECUTION, UUID.randomUUID().toString(), UUID.randomUUID().toString());
		for (int i = 0; i < random.nextInt(10); i++) {
			final QueryTelemetry step = queryTelemetry.addStep(getRandomQueryPhase(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
			if (random.nextBoolean()) {
				final QueryTelemetry subStep = step.addStep(getRandomQueryPhase(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
				if (random.nextBoolean()) {
					subStep.addStep(getRandomQueryPhase(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
				}
			}
		}
		return queryTelemetry.finish();
	}

	@Nonnull
	private QueryPhase getRandomQueryPhase() {
		return QueryPhase.values()[random.nextInt(queryPhaseCount)];
	}
}
