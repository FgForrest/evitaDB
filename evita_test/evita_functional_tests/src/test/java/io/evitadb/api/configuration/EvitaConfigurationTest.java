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
 * Tests for {@link EvitaConfiguration} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("EvitaConfiguration")
class EvitaConfigurationTest {

	@Test
	@DisplayName("should initialize all defaults via builder")
	void shouldInitDefaults() {
		final EvitaConfiguration options =
			EvitaConfiguration.builder().build();

		assertNotNull(options.storage().storageDirectory());
		assertTrue(
			options.server().requestThreadPool()
				.minThreadCount() > 0
		);
		assertFalse(options.cache().enabled());
	}

	@Nested
	@DisplayName("plainName method")
	class PlainNameTest {

		@Test
		@DisplayName(
			"should return default name when using default"
		)
		void shouldReturnDefaultNameWhenUsingDefault() {
			final EvitaConfiguration config =
				EvitaConfiguration.builder().build();

			// name should have hash appended
			assertTrue(
				config.name().startsWith(
					EvitaConfiguration.DEFAULT_SERVER_NAME + "-"
				)
			);
			// plainName strips the hash
			assertEquals(
				EvitaConfiguration.DEFAULT_SERVER_NAME,
				config.plainName()
			);
		}

		@Test
		@DisplayName(
			"should return custom name as-is"
		)
		void shouldReturnCustomNameAsIs() {
			final EvitaConfiguration config =
				EvitaConfiguration.builder()
					.name("myServer")
					.build();

			assertEquals("myServer", config.name());
			assertEquals("myServer", config.plainName());
		}
	}

	@Nested
	@DisplayName("Builder copy constructor")
	class BuilderCopyTest {

		@Test
		@DisplayName(
			"should copy all fields from source configuration"
		)
		void shouldCopyAllFieldsFromSource() {
			final EvitaConfiguration source =
				EvitaConfiguration.builder()
					.name("testServer")
					.cache(
						CacheOptions.builder()
							.enabled(true)
							.build()
					)
					.build();

			final EvitaConfiguration copy =
				EvitaConfiguration.builder(source).build();

			assertEquals(source.name(), copy.name());
			assertEquals(
				source.cache().enabled(),
				copy.cache().enabled()
			);
			assertEquals(
				source.storage().storageDirectory(),
				copy.storage().storageDirectory()
			);
		}
	}

	@Nested
	@DisplayName("Convenience builder methods")
	class ConvenienceBuilderTest {

		@Test
		@DisplayName(
			"should set server options via builder"
		)
		void shouldSetServerOptionsViaBuilder() {
			final ServerOptions serverOptions =
				ServerOptions.builder()
					.readOnly(true)
					.build();

			final EvitaConfiguration config =
				EvitaConfiguration.builder()
					.server(serverOptions)
					.build();

			assertTrue(config.server().readOnly());
		}

		@Test
		@DisplayName(
			"should set transaction options via builder"
		)
		void shouldSetTransactionOptionsViaBuilder() {
			final TransactionOptions txOptions =
				TransactionOptions.builder()
					.walFileCountKept(4)
					.build();

			final EvitaConfiguration config =
				EvitaConfiguration.builder()
					.transaction(txOptions)
					.build();

			assertEquals(
				4, config.transaction().walFileCountKept()
			);
		}

		@Test
		@DisplayName(
			"should set export options via builder"
		)
		void shouldSetExportOptionsViaBuilder() {
			final EvitaConfiguration config =
				EvitaConfiguration.builder()
					.export(DefaultExportOptions.INSTANCE)
					.build();

			assertEquals(
				"default",
				config.export().getImplementationCode()
			);
		}
	}

	@Nested
	@DisplayName("Name validation")
	class NameValidationTest {

		@Test
		@DisplayName(
			"should validate modified name passes " +
				"classifier check"
		)
		void shouldValidateModifiedNamePasses() {
			// The default name gets a hex hash appended,
			// which should pass validation
			assertDoesNotThrow(
				() -> EvitaConfiguration.builder().build()
			);
		}

		@Test
		@DisplayName(
			"should accept custom valid server name"
		)
		void shouldAcceptCustomValidServerName() {
			assertDoesNotThrow(
				() -> EvitaConfiguration.builder()
					.name("my-server-123")
					.build()
			);
		}
	}
}
