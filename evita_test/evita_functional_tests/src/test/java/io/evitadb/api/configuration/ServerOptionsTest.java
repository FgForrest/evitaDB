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

package io.evitadb.api.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ServerOptions} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ServerOptions")
class ServerOptionsTest {

	@Test
	@DisplayName(
		"should initialize request thread pool defaults"
	)
	void shouldInitDefaults() {
		final ServerOptions options =
			ServerOptions.builder().build();

		assertTrue(
			options.requestThreadPool().minThreadCount() > 0
		);
		assertTrue(
			options.requestThreadPool().maxThreadCount()
				>= options.requestThreadPool().minThreadCount()
		);
		assertEquals(
			8, options.requestThreadPool().threadPriority()
		);
		assertEquals(
			100, options.requestThreadPool().queueSize()
		);
		assertEquals(
			1200,
			options.closeSessionsAfterSecondsOfInactivity()
		);
	}

	@Nested
	@DisplayName("Timeout defaults")
	class TimeoutDefaultsTest {

		@Test
		@DisplayName("should have default query timeout")
		void shouldHaveDefaultQueryTimeout() {
			final ServerOptions options =
				ServerOptions.builder().build();

			assertEquals(
				ServerOptions
					.DEFAULT_QUERY_TIMEOUT_IN_MILLISECONDS,
				options.queryTimeoutInMilliseconds()
			);
		}

		@Test
		@DisplayName(
			"should have default transaction timeout"
		)
		void shouldHaveDefaultTransactionTimeout() {
			final ServerOptions options =
				ServerOptions.builder().build();

			assertEquals(
				ServerOptions
					.DEFAULT_TRANSACTION_TIMEOUT_IN_MILLISECONDS,
				options.transactionTimeoutInMilliseconds()
			);
		}

		@Test
		@DisplayName(
			"should have default session inactivity timeout"
		)
		void shouldHaveDefaultSessionInactivityTimeout() {
			final ServerOptions options =
				ServerOptions.builder().build();

			assertEquals(
				ServerOptions
					.DEFAULT_CLOSE_SESSIONS_AFTER_SECONDS_OF_INACTIVITY,
				options.closeSessionsAfterSecondsOfInactivity()
			);
		}
	}

	@Nested
	@DisplayName("Null parameter handling")
	class NullParameterHandlingTest {

		@Test
		@DisplayName(
			"should use default thread pools when null"
		)
		void shouldUseDefaultThreadPoolsWhenNull() {
			final ServerOptions options = new ServerOptions(
				null, null, null,
				ServerOptions
					.DEFAULT_QUERY_TIMEOUT_IN_MILLISECONDS,
				ServerOptions
					.DEFAULT_TRANSACTION_TIMEOUT_IN_MILLISECONDS,
				ServerOptions
					.DEFAULT_CLOSE_SESSIONS_AFTER_SECONDS_OF_INACTIVITY,
				null, null,
				false, false, false
			);

			assertNotNull(options.requestThreadPool());
			assertNotNull(options.transactionThreadPool());
			assertNotNull(options.serviceThreadPool());
		}

		@Test
		@DisplayName(
			"should use defaults for CDC and traffic " +
				"recording when null"
		)
		void shouldUseDefaultsForCdcAndTrafficWhenNull() {
			final ServerOptions options = new ServerOptions(
				null, null, null,
				ServerOptions
					.DEFAULT_QUERY_TIMEOUT_IN_MILLISECONDS,
				ServerOptions
					.DEFAULT_TRANSACTION_TIMEOUT_IN_MILLISECONDS,
				ServerOptions
					.DEFAULT_CLOSE_SESSIONS_AFTER_SECONDS_OF_INACTIVITY,
				null, null,
				false, false, false
			);

			assertNotNull(options.changeDataCapture());
			assertNotNull(options.trafficRecording());
		}
	}

	@Nested
	@DisplayName("Boolean defaults")
	class BooleanDefaultsTest {

		@Test
		@DisplayName("should not be read-only by default")
		void shouldNotBeReadOnlyByDefault() {
			final ServerOptions options =
				ServerOptions.builder().build();

			assertFalse(options.readOnly());
		}

		@Test
		@DisplayName("should not be quiet by default")
		void shouldNotBeQuietByDefault() {
			final ServerOptions options =
				ServerOptions.builder().build();

			assertFalse(options.quiet());
		}
	}

	@Nested
	@DisplayName("Builder copy constructor")
	class BuilderCopyTest {

		@Test
		@DisplayName(
			"should copy all fields from source"
		)
		void shouldCopyAllFieldsFromSource() {
			final ServerOptions source =
				ServerOptions.builder()
					.queryTimeoutInMilliseconds(10_000L)
					.transactionTimeoutInMilliseconds(
						60_000L
					)
					.closeSessionsAfterSecondsOfInactivity(
						300
					)
					.readOnly(true)
					.quiet(true)
					.build();

			final ServerOptions copy =
				ServerOptions.builder(source).build();

			assertEquals(
				10_000L,
				copy.queryTimeoutInMilliseconds()
			);
			assertEquals(
				60_000L,
				copy.transactionTimeoutInMilliseconds()
			);
			assertEquals(
				300,
				copy.closeSessionsAfterSecondsOfInactivity()
			);
			assertTrue(copy.readOnly());
			assertTrue(copy.quiet());
		}
	}

	@Nested
	@DisplayName("Builder setters")
	class BuilderSettersTest {

		@Test
		@DisplayName(
			"should set custom thread pool via builder"
		)
		void shouldSetCustomThreadPoolViaBuilder() {
			final ThreadPoolOptions customPool =
				ThreadPoolOptions.requestThreadPoolBuilder()
					.minThreadCount(2)
					.maxThreadCount(4)
					.build();

			final ServerOptions options =
				ServerOptions.builder()
					.requestThreadPool(customPool)
					.build();

			assertEquals(
				2,
				options.requestThreadPool().minThreadCount()
			);
			assertEquals(
				4,
				options.requestThreadPool().maxThreadCount()
			);
		}

		@Test
		@DisplayName(
			"should set CDC options via builder"
		)
		void shouldSetCdcOptionsViaBuilder() {
			final ChangeDataCaptureOptions cdcOptions =
				ChangeDataCaptureOptions.builder()
					.enabled(false)
					.build();

			final ServerOptions options =
				ServerOptions.builder()
					.changeDataCapture(cdcOptions)
					.build();

			assertFalse(
				options.changeDataCapture().enabled()
			);
		}

		@Test
		@DisplayName(
			"should set traffic recording options via builder"
		)
		void shouldSetTrafficRecordingViaBuilder() {
			final TrafficRecordingOptions trafficOptions =
				TrafficRecordingOptions.builder()
					.enabled(true)
					.build();

			final ServerOptions options =
				ServerOptions.builder()
					.trafficRecording(trafficOptions)
					.build();

			assertTrue(
				options.trafficRecording().enabled()
			);
		}
	}
}
