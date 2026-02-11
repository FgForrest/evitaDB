/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.spi.store.catalog.wal.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EngineTransactionChanges} record,
 * focusing on correctness of the `toString()` output
 * formatting -- specifically the missing opening parenthesis
 * and missing space bugs before mutation info.
 */
@DisplayName("EngineTransactionChanges")
class EngineTransactionChangesTest {

	private static final OffsetDateTime COMMIT_TIME =
		OffsetDateTime.of(
			2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC
		);

	@Nested
	@DisplayName("toString() formatting")
	class ToStringFormatting {

		@Test
		@DisplayName(
			"Should have balanced parentheses around mutation info"
		)
		void shouldHaveBalancedParenthesesAroundMutationInfo() {
			final EngineTransactionChanges changes =
				new EngineTransactionChanges(
					5L, COMMIT_TIME, 10,
					1536L, new String[]{"change1"}
				);

			final String result = changes.toString();

			// must contain opening paren before mutation count
			assertTrue(
				result.contains("(10 mutations,"),
				"Expected '(' before mutation count"
					+ " but got: " + result
			);
		}

		@Test
		@DisplayName(
			"Should have space before mutation info parenthesis"
		)
		void shouldHaveSpaceBeforeMutationInfoParenthesis() {
			final EngineTransactionChanges changes =
				new EngineTransactionChanges(
					5L, COMMIT_TIME, 10,
					1536L, new String[]{"change1"}
				);

			final String result = changes.toString();

			// must have space before '(' for readability
			assertTrue(
				result.contains(" (10 mutations,"),
				"Expected space before '('"
					+ " but got: " + result
			);
		}

		@Test
		@DisplayName(
			"Should have space before processing lag section"
		)
		void shouldHaveSpaceBeforeProcessingLagSection() {
			final EngineTransactionChanges changes =
				new EngineTransactionChanges(
					5L, COMMIT_TIME, 10,
					1536L, new String[]{"change1"}
				);

			final OffsetDateTime processedTime =
				COMMIT_TIME.plusSeconds(5);
			final String result =
				changes.toString(processedTime);

			// must have space before "(processing lag"
			assertTrue(
				result.contains(" (processing lag"),
				"Expected space before '(processing lag'"
					+ " but got: " + result
			);
		}

		@Test
		@DisplayName(
			"Should format correctly without processed timestamp"
		)
		void shouldFormatCorrectlyWithoutProcessedTimestamp() {
			final EngineTransactionChanges changes =
				new EngineTransactionChanges(
					5L, COMMIT_TIME, 10,
					1536L, new String[]{"change1"}
				);

			final String result = changes.toString();

			final String expected =
				"Transaction to version: 5"
					+ ", committed at "
					+ "2025-01-01T00:00:00Z"
					+ " (10 mutations, 1 KB):\n"
					+ "change1";

			assertEquals(expected, result);
		}

		@Test
		@DisplayName(
			"Should format correctly with processed timestamp"
		)
		void shouldFormatCorrectlyWithProcessedTimestamp() {
			final EngineTransactionChanges changes =
				new EngineTransactionChanges(
					5L, COMMIT_TIME, 10,
					1536L, new String[]{"change1"}
				);

			final OffsetDateTime processedTime =
				COMMIT_TIME.plusSeconds(5);
			final String result =
				changes.toString(processedTime);

			final String expected =
				"Transaction to version: 5"
					+ ", committed at "
					+ "2025-01-01T00:00:00Z"
					+ " (processing lag 5s)"
					+ " (10 mutations, 1 KB):\n"
					+ "change1";

			assertEquals(expected, result);
		}
	}
}
