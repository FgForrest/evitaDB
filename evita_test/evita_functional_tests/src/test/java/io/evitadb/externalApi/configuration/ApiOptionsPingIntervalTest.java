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

package io.evitadb.externalApi.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the server-side keep-alive ping configuration of {@link ApiOptions} — the counterpart of the client-side
 * {@link io.evitadb.driver.config.ClientConnectionOptions} coverage. The ping interval is the stall budget that
 * governs when Armeria closes an unacknowledged connection; `0` disables the server ping entirely (the default,
 * matching gRPC's convention that keep-alive is the client's responsibility) and any negative value falls back to
 * that default. A positive value is passed through verbatim.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ApiOptions keep-alive ping interval")
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
class ApiOptionsPingIntervalTest {

	@Test
	@DisplayName("Defaults the server ping to disabled (0) via the no-arg constructor")
	void shouldDefaultPingIntervalToDisabledViaNoArgConstructor() {
		assertEquals(ApiOptions.DEFAULT_PING_INTERVAL, new ApiOptions().pingIntervalMillis());
		assertEquals(0, new ApiOptions().pingIntervalMillis());
	}

	@Test
	@DisplayName("Defaults the server ping to disabled (0) via the builder")
	void shouldDefaultPingIntervalToDisabledViaBuilder() {
		assertEquals(0, ApiOptions.builder().build().pingIntervalMillis());
	}

	@Test
	@DisplayName("Passes a positive ping interval through verbatim")
	void shouldSetCustomPingInterval() {
		assertEquals(1000, ApiOptions.builder().pingIntervalMillis(1000).build().pingIntervalMillis());
	}

	@Test
	@DisplayName("Keeps an explicit zero (disabled) ping interval")
	void shouldKeepExplicitDisabledPingInterval() {
		assertEquals(0, ApiOptions.builder().pingIntervalMillis(0).build().pingIntervalMillis());
	}

	@Test
	@DisplayName("Falls back to the default for a negative ping interval")
	void shouldClampNegativePingIntervalToDefault() {
		// 0 is meaningful (disables the ping); only a negative value is invalid and falls back to the default
		assertEquals(
			ApiOptions.DEFAULT_PING_INTERVAL,
			ApiOptions.builder().pingIntervalMillis(-1).build().pingIntervalMillis()
		);
	}

	@Test
	@DisplayName("Preserves the ping interval through the copy builder")
	void shouldPreservePingIntervalInCopyBuilder() {
		final ApiOptions source = ApiOptions.builder().pingIntervalMillis(2500).build();
		assertEquals(2500, ApiOptions.builder(source).build().pingIntervalMillis());
	}
}
