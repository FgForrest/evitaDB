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

package io.evitadb.externalApi.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ReadinessDiscoveryStallTracker}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReadinessDiscoveryStallTracker")
@Tag(EXTERNAL_API)
@Tag(OBSERVABILITY)
class ReadinessDiscoveryStallTrackerTest {

	@Test
	@DisplayName("does not warn before the grace period elapses")
	void shouldNotWarnBeforeGracePeriodElapses() {
		final ReadinessDiscoveryStallTracker tracker = new ReadinessDiscoveryStallTracker(Duration.ofSeconds(60));
		assertFalse(tracker.shouldWarnAboutStall());
		assertFalse(tracker.shouldWarnAboutStall());
	}

	@Test
	@DisplayName("warns exactly once after the grace period elapses")
	void shouldWarnExactlyOnceAfterGracePeriodElapses() throws InterruptedException {
		final ReadinessDiscoveryStallTracker tracker = new ReadinessDiscoveryStallTracker(Duration.ofMillis(20));
		assertFalse(tracker.shouldWarnAboutStall());

		Thread.sleep(50);

		assertTrue(tracker.shouldWarnAboutStall());
		assertFalse(tracker.shouldWarnAboutStall());
		assertFalse(tracker.shouldWarnAboutStall());
	}

}
