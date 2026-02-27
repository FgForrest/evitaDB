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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClientConnectionOptions} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ClientConnectionOptions")
class ClientConnectionOptionsTest {

	@Test
	@DisplayName("should initialize all defaults via builder")
	void shouldInitDefaults() {
		final ClientConnectionOptions options = ClientConnectionOptions.builder().build();

		assertNotNull(options.clientId());
		assertTrue(
			options.clientId().startsWith("gRPC client at ") ||
				options.clientId().equals("Generic gRPC client")
		);
		assertEquals(ClientConnectionOptions.DEFAULT_HOST, options.host());
		assertEquals(ClientConnectionOptions.DEFAULT_PORT, options.port());
		assertEquals(ClientConnectionOptions.DEFAULT_SYSTEM_API_PORT, options.systemApiPort());
	}

	@Test
	@DisplayName("should initialize all defaults via no-arg constructor")
	void shouldInitDefaultsViaNoArgConstructor() {
		final ClientConnectionOptions options = new ClientConnectionOptions();

		assertNotNull(options.clientId());
		assertEquals("localhost", options.host());
		assertEquals(5555, options.port());
		assertEquals(5555, options.systemApiPort());
	}

	@Nested
	@DisplayName("Builder setters")
	class BuilderSettersTest {

		@Test
		@DisplayName("should set custom host via builder")
		void shouldSetCustomHost() {
			final ClientConnectionOptions options =
				ClientConnectionOptions.builder()
					.host("myserver.example.com")
					.build();

			assertEquals("myserver.example.com", options.host());
		}

		@Test
		@DisplayName("should set custom port via builder")
		void shouldSetCustomPort() {
			final ClientConnectionOptions options =
				ClientConnectionOptions.builder()
					.port(9999)
					.build();

			assertEquals(9999, options.port());
		}

		@Test
		@DisplayName("should set custom system API port via builder")
		void shouldSetCustomSystemApiPort() {
			final ClientConnectionOptions options =
				ClientConnectionOptions.builder()
					.systemApiPort(8080)
					.build();

			assertEquals(8080, options.systemApiPort());
		}

		@Test
		@DisplayName("should set custom client ID via builder")
		void shouldSetCustomClientId() {
			final ClientConnectionOptions options =
				ClientConnectionOptions.builder()
					.clientId("my-custom-client")
					.build();

			assertEquals("my-custom-client", options.clientId());
		}
	}

	@Nested
	@DisplayName("Builder copy constructor")
	class BuilderCopyTest {

		@Test
		@DisplayName("should copy all fields from source")
		void shouldCopyAllFieldsFromSource() {
			final ClientConnectionOptions source =
				ClientConnectionOptions.builder()
					.clientId("test-client")
					.host("remotehost")
					.port(1234)
					.systemApiPort(5678)
					.build();

			final ClientConnectionOptions copy = ClientConnectionOptions.builder(source).build();

			assertEquals("test-client", copy.clientId());
			assertEquals("remotehost", copy.host());
			assertEquals(1234, copy.port());
			assertEquals(5678, copy.systemApiPort());
		}

		@Test
		@DisplayName("should allow overriding single field in copy")
		void shouldAllowOverridingSingleFieldInCopy() {
			final ClientConnectionOptions source =
				ClientConnectionOptions.builder()
					.host("original-host")
					.port(1111)
					.build();

			final ClientConnectionOptions modified =
				ClientConnectionOptions.builder(source)
					.port(2222)
					.build();

			assertEquals("original-host", modified.host());
			assertEquals(2222, modified.port());
		}
	}
}
