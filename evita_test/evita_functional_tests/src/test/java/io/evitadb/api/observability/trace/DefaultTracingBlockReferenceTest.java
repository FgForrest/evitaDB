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

package io.evitadb.api.observability.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Tests for {@link DefaultTracingBlockReference} verifying that
 * the no-op (null object) implementation satisfies the
 * {@link TracingBlockReference} contract without throwing
 * exceptions or producing side effects.
 *
 * @author evitaDB
 */
@DisplayName("DefaultTracingBlockReference - no-op tracing")
class DefaultTracingBlockReferenceTest {

	@Nested
	@DisplayName("No-op behavior")
	class NoOpBehavior {

		@Test
		@DisplayName(
			"setError() does not throw"
		)
		void shouldNotThrowOnSetError() {
			final DefaultTracingBlockReference ref =
				new DefaultTracingBlockReference();

			assertDoesNotThrow(
				() -> ref.setError(new RuntimeException("test"))
			);
		}

		@Test
		@DisplayName(
			"detachScope() does not throw"
		)
		void shouldNotThrowOnDetachScope() {
			final DefaultTracingBlockReference ref =
				new DefaultTracingBlockReference();

			assertDoesNotThrow(ref::detachScope);
		}

		@Test
		@DisplayName(
			"end() does not throw"
		)
		void shouldNotThrowOnEnd() {
			final DefaultTracingBlockReference ref =
				new DefaultTracingBlockReference();

			assertDoesNotThrow(ref::end);
		}

		@Test
		@DisplayName(
			"close() does not throw"
		)
		void shouldNotThrowOnClose() {
			final DefaultTracingBlockReference ref =
				new DefaultTracingBlockReference();

			assertDoesNotThrow(ref::close);
		}

		@Test
		@DisplayName(
			"try-with-resources completes without error"
		)
		void shouldWorkWithTryWithResources() {
			assertDoesNotThrow(() -> {
				try (
					TracingBlockReference ref =
						new DefaultTracingBlockReference()
				) {
					ref.setError(
						new IllegalStateException("ignored")
					);
				}
			});
		}

		@Test
		@DisplayName(
			"close() after detachScope() + end()"
		)
		void shouldNotThrowOnCloseAfterDetachAndEnd() {
			final DefaultTracingBlockReference ref =
				new DefaultTracingBlockReference();

			assertDoesNotThrow(() -> {
				ref.detachScope();
				ref.end();
				ref.close();
			});
		}
	}

	@Nested
	@DisplayName("Multiple call safety")
	class MultipleCallSafety {

		@Test
		@DisplayName(
			"multiple close() calls do not throw"
		)
		void shouldNotThrowOnMultipleClose() {
			final DefaultTracingBlockReference ref =
				new DefaultTracingBlockReference();

			assertDoesNotThrow(() -> {
				ref.close();
				ref.close();
				ref.close();
			});
		}

		@Test
		@DisplayName(
			"multiple setError() calls do not throw"
		)
		void shouldNotThrowOnMultipleSetError() {
			final DefaultTracingBlockReference ref =
				new DefaultTracingBlockReference();

			assertDoesNotThrow(() -> {
				ref.setError(new RuntimeException("first"));
				ref.setError(new RuntimeException("second"));
			});
		}

		@Test
		@DisplayName(
			"multiple detachScope() calls do not throw"
		)
		void shouldNotThrowOnMultipleDetachScope() {
			final DefaultTracingBlockReference ref =
				new DefaultTracingBlockReference();

			assertDoesNotThrow(() -> {
				ref.detachScope();
				ref.detachScope();
			});
		}

		@Test
		@DisplayName(
			"multiple end() calls do not throw"
		)
		void shouldNotThrowOnMultipleEnd() {
			final DefaultTracingBlockReference ref =
				new DefaultTracingBlockReference();

			assertDoesNotThrow(() -> {
				ref.end();
				ref.end();
			});
		}
	}
}
