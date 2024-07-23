/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetry;
import io.evitadb.externalApi.grpc.testUtils.GrpcAssertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Random;
import java.util.UUID;

/**
 * This test verifies functionalities of methods in {@link GrpcQueryTelemetryBuilder} class.
 *
 * @author Tomáš Pozler, 2022
 */
class GrpcQueryTelemetryBuilderTest {
	private static final Random random = new Random();
	private static final int queryPhaseCount = QueryPhase.values().length;

	@Test
	void buildQueryTelemetry() {
		final QueryTelemetry queryTelemetry = createRandomQueryTelemetry();
		final GrpcQueryTelemetry grpcQueryTelemetry = GrpcQueryTelemetryBuilder.buildQueryTelemetry(queryTelemetry);
		GrpcAssertions.assertQueryTelemetry(queryTelemetry, grpcQueryTelemetry);
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
