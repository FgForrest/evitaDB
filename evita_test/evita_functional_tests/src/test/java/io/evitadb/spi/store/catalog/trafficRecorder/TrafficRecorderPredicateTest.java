/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.spi.store.catalog.trafficRecorder;

import io.evitadb.api.requestResponse.trafficRecording.SessionStartContainer;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Predicate;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRAFFIC_ENGINE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the intended filtering semantics of {@link TrafficRecorder#createRequestPredicate}: different
 * criteria in a {@link TrafficRecordingCaptureRequest} are combined with logical AND (a recording must match
 * every specified criterion), while the values within a single multi-valued criterion are combined with OR.
 * The interface javadoc previously claimed logical OR across criteria, which contradicts the implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(TRAFFIC_ENGINE)
class TrafficRecorderPredicateTest {

	private static SessionStartContainer sessionStart(@Nonnull UUID sessionId) {
		return new SessionStartContainer(sessionId, 0, 1L, OffsetDateTime.now());
	}

	@Test
	@DisplayName("Different criteria are combined with AND - a record matching only one criterion is excluded")
	void shouldCombineDifferentCriteriaWithAnd() {
		final UUID wantedSession = UUID.randomUUID();
		final Predicate<TrafficRecording> predicate = TrafficRecorder.createRequestPredicate(
			TrafficRecordingCaptureRequest.builder()
				.type(TrafficRecordingType.SESSION_START)
				.sessionId(wantedSession)
				.build(),
			TrafficRecorder.StreamDirection.FORWARD
		);

		// matches BOTH the type and the session id -> included
		assertTrue(
			predicate.test(sessionStart(wantedSession)),
			"A record matching every criterion must be included."
		);
		// matches the type but NOT the session id -> excluded under AND (would be INCLUDED under OR)
		assertFalse(
			predicate.test(sessionStart(UUID.randomUUID())),
			"A record matching only the type but not the session id must be excluded - criteria are AND-ed, " +
				"not OR-ed."
		);
	}

	@Test
	@DisplayName("Values within a single multi-valued criterion are combined with OR")
	void shouldCombineValuesWithinOneCriterionWithOr() {
		final UUID sessionA = UUID.randomUUID();
		final UUID sessionB = UUID.randomUUID();
		final Predicate<TrafficRecording> predicate = TrafficRecorder.createRequestPredicate(
			TrafficRecordingCaptureRequest.builder()
				.sessionId(sessionA, sessionB)
				.build(),
			TrafficRecorder.StreamDirection.FORWARD
		);

		assertTrue(predicate.test(sessionStart(sessionA)), "Either requested session id must match (OR within the criterion).");
		assertTrue(predicate.test(sessionStart(sessionB)), "Either requested session id must match (OR within the criterion).");
		assertFalse(predicate.test(sessionStart(UUID.randomUUID())), "A session id in neither requested value must not match.");
	}

	@Test
	@DisplayName("An empty request matches everything")
	void shouldMatchEverythingWhenNoCriteriaSet() {
		final Predicate<TrafficRecording> predicate = TrafficRecorder.createRequestPredicate(
			TrafficRecordingCaptureRequest.builder().build(),
			TrafficRecorder.StreamDirection.FORWARD
		);
		assertTrue(predicate.test(sessionStart(UUID.randomUUID())), "An empty request must not filter anything out.");
	}
}
