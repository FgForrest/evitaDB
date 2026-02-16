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

package io.evitadb.api.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExportOptions} and {@link DefaultExportOptions}.
 *
 * @author Claude
 */
@DisplayName("ExportOptions")
class ExportOptionsTest {

	@Nested
	@DisplayName("DefaultExportOptions singleton")
	class DefaultExportOptionsTest {

		@Test
		@DisplayName(
			"should return 'default' implementation code"
		)
		void shouldReturnDefaultImplementationCode() {
			assertEquals(
				"default",
				DefaultExportOptions.INSTANCE
					.getImplementationCode()
			);
		}

		@Test
		@DisplayName("should have null enabled by default")
		void shouldHaveNullEnabledByDefault() {
			assertNull(
				DefaultExportOptions.INSTANCE.getEnabled()
			);
		}

		@Test
		@DisplayName("should have default size limit")
		void shouldHaveDefaultSizeLimit() {
			assertEquals(
				ExportOptions.DEFAULT_SIZE_LIMIT_BYTES,
				DefaultExportOptions.INSTANCE
					.getSizeLimitBytes()
			);
		}

		@Test
		@DisplayName(
			"should have default history expiration"
		)
		void shouldHaveDefaultHistoryExpiration() {
			assertEquals(
				ExportOptions
					.DEFAULT_HISTORY_EXPIRATION_SECONDS,
				DefaultExportOptions.INSTANCE
					.getHistoryExpirationSeconds()
			);
		}

		@Test
		@DisplayName("should not throw on validateWhenEnabled")
		void shouldNotThrowOnValidateWhenEnabled() {
			assertDoesNotThrow(
				() -> DefaultExportOptions.INSTANCE
					.validateWhenEnabled()
			);
		}
	}

	@Nested
	@DisplayName("ExportOptions constants")
	class ConstantsTest {

		@Test
		@DisplayName("should have 1GB default size limit")
		void shouldHave1GbDefaultSizeLimit() {
			assertEquals(
				1_073_741_824L,
				ExportOptions.DEFAULT_SIZE_LIMIT_BYTES
			);
		}

		@Test
		@DisplayName(
			"should have 7 days default history expiration"
		)
		void shouldHave7DaysDefaultHistoryExpiration() {
			assertEquals(
				604_800L,
				ExportOptions.DEFAULT_HISTORY_EXPIRATION_SECONDS
			);
		}
	}
}
