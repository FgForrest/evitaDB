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

package io.evitadb.driver.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClientTimeoutOptions} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ClientTimeoutOptions")
class ClientTimeoutOptionsTest {

	@Test
	@DisplayName("should initialize all defaults via builder")
	void shouldInitDefaults() {
		final ClientTimeoutOptions options = ClientTimeoutOptions.builder().build();

		assertEquals(ClientTimeoutOptions.DEFAULT_TIMEOUT, options.timeout());
		assertEquals(ClientTimeoutOptions.DEFAULT_TIMEOUT_UNIT, options.timeoutUnit());
		assertEquals(ClientTimeoutOptions.DEFAULT_STREAMING_TIMEOUT, options.streamingTimeout());
		assertEquals(ClientTimeoutOptions.DEFAULT_STREAMING_TIMEOUT_UNIT, options.streamingTimeoutUnit());
	}

	@Test
	@DisplayName("should initialize all defaults via no-arg constructor")
	void shouldInitDefaultsViaNoArgConstructor() {
		final ClientTimeoutOptions options = new ClientTimeoutOptions();

		assertEquals(5, options.timeout());
		assertEquals(TimeUnit.SECONDS, options.timeoutUnit());
		assertEquals(3600, options.streamingTimeout());
		assertEquals(TimeUnit.SECONDS, options.streamingTimeoutUnit());
	}

	@Nested
	@DisplayName("Builder setters")
	class BuilderSettersTest {

		@Test
		@DisplayName("should set custom timeout via builder")
		void shouldSetCustomTimeout() {
			final ClientTimeoutOptions options =
				ClientTimeoutOptions.builder()
					.timeout(10, TimeUnit.MINUTES)
					.build();

			assertEquals(10, options.timeout());
			assertEquals(TimeUnit.MINUTES, options.timeoutUnit());
		}

		@Test
		@DisplayName("should set custom streaming timeout via builder")
		void shouldSetCustomStreamingTimeout() {
			final ClientTimeoutOptions options =
				ClientTimeoutOptions.builder()
					.streamingTimeout(30, TimeUnit.MINUTES)
					.build();

			assertEquals(30, options.streamingTimeout());
			assertEquals(TimeUnit.MINUTES, options.streamingTimeoutUnit());
		}

		@Test
		@DisplayName("should set both timeouts independently via builder")
		void shouldSetBothTimeoutsIndependently() {
			final ClientTimeoutOptions options =
				ClientTimeoutOptions.builder()
					.timeout(5, TimeUnit.SECONDS)
					.streamingTimeout(1, TimeUnit.HOURS)
					.build();

			assertEquals(5, options.timeout());
			assertEquals(TimeUnit.SECONDS, options.timeoutUnit());
			assertEquals(1, options.streamingTimeout());
			assertEquals(TimeUnit.HOURS, options.streamingTimeoutUnit());
		}
	}

	@Nested
	@DisplayName("Builder copy constructor")
	class BuilderCopyTest {

		@Test
		@DisplayName("should copy all fields from source")
		void shouldCopyAllFieldsFromSource() {
			final ClientTimeoutOptions source =
				ClientTimeoutOptions.builder()
					.timeout(30, TimeUnit.MINUTES)
					.streamingTimeout(2, TimeUnit.HOURS)
					.build();

			final ClientTimeoutOptions copy = ClientTimeoutOptions.builder(source).build();

			assertEquals(30, copy.timeout());
			assertEquals(TimeUnit.MINUTES, copy.timeoutUnit());
			assertEquals(2, copy.streamingTimeout());
			assertEquals(TimeUnit.HOURS, copy.streamingTimeoutUnit());
		}

		@Test
		@DisplayName("should allow overriding single timeout in copy")
		void shouldAllowOverridingSingleTimeoutInCopy() {
			final ClientTimeoutOptions source =
				ClientTimeoutOptions.builder()
					.timeout(10, TimeUnit.SECONDS)
					.streamingTimeout(60, TimeUnit.SECONDS)
					.build();

			final ClientTimeoutOptions modified =
				ClientTimeoutOptions.builder(source)
					.timeout(30, TimeUnit.SECONDS)
					.build();

			assertEquals(30, modified.timeout());
			assertEquals(TimeUnit.SECONDS, modified.timeoutUnit());
			// streaming timeout should remain unchanged
			assertEquals(60, modified.streamingTimeout());
			assertEquals(TimeUnit.SECONDS, modified.streamingTimeoutUnit());
		}
	}
}
