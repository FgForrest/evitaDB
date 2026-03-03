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

package io.evitadb.externalApi.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DefaultExternalApiTracingContext} verifying that the NOOP implementation directly invokes
 * the provided lambdas without tracing overhead.
 *
 * @author evitaDB
 */
@DisplayName("DefaultExternalApiTracingContext - NOOP passthrough")
class DefaultExternalApiTracingContextTest {

	@Test
	@DisplayName("returns Object.class as context type")
	void shouldReturnObjectClassAsContextType() {
		assertEquals(Object.class, DefaultExternalApiTracingContext.INSTANCE.contextType());
	}

	@Test
	@DisplayName("returns async lambda result directly")
	void shouldReturnAsyncLambdaResultDirectly() throws ExecutionException, InterruptedException {
		final DefaultExternalApiTracingContext ctx = DefaultExternalApiTracingContext.INSTANCE;
		final Object context = new Object();

		final CompletableFuture<String> result = ctx.executeWithinBlockAsync(
			"test", context, () -> CompletableFuture.completedFuture("result")
		);

		assertEquals("result", result.get());
	}

	@Test
	@DisplayName("runs runnable variant")
	void shouldRunRunnableVariant() {
		final DefaultExternalApiTracingContext ctx = DefaultExternalApiTracingContext.INSTANCE;
		final Object context = new Object();
		final AtomicBoolean called = new AtomicBoolean(false);

		ctx.executeWithinBlock("test", context, () -> called.set(true));

		assertTrue(called.get(), "Runnable should have been called");
	}

	@Test
	@DisplayName("returns supplier variant result")
	void shouldReturnSupplierVariant() {
		final DefaultExternalApiTracingContext ctx = DefaultExternalApiTracingContext.INSTANCE;
		final Object context = new Object();

		final String value = ctx.executeWithinBlock("test", context, () -> "value");

		assertEquals("value", value);
	}

	@Nested
	@DisplayName("convertClientId")
	class ConvertClientId {

		private final DefaultExternalApiTracingContext ctx = DefaultExternalApiTracingContext.INSTANCE;

		@Test
		@DisplayName("formats with valid client ID")
		void shouldFormatWithValidClientId() {
			assertEquals("GraphQL|my-client.1", ctx.convertClientId("GraphQL", "my-client.1"));
		}

		@Test
		@DisplayName("uses default when client ID is null")
		void shouldUseDefaultWhenClientIdIsNull() {
			assertEquals("REST|unknown", ctx.convertClientId("REST", null));
		}

		@Test
		@DisplayName("sanitizes special characters")
		void shouldSanitizeSpecialCharacters() {
			assertEquals("gRPC|client-name-1-2", ctx.convertClientId("gRPC", "client@name#1!2"));
		}

		@Test
		@DisplayName("sanitizes spaces")
		void shouldSanitizeSpaces() {
			assertEquals("REST|my-client", ctx.convertClientId("REST", "my client"));
		}

		@Test
		@DisplayName("handles empty client ID")
		void shouldHandleEmptyClientId() {
			assertEquals("REST|", ctx.convertClientId("REST", ""));
		}

		@Test
		@DisplayName("preserves allowed characters")
		void shouldPreserveAllowedCharacters() {
			assertEquals("REST|abc-XYZ_123.test", ctx.convertClientId("REST", "abc-XYZ_123.test"));
		}
	}

	@Nested
	@DisplayName("executeWithinBlockAsync default")
	class ExecuteWithinBlockAsyncDefault {

		@Test
		@DisplayName("propagates exceptional future")
		void shouldPropagateExceptionalFuture() {
			final DefaultExternalApiTracingContext ctx = DefaultExternalApiTracingContext.INSTANCE;
			final Object context = new Object();
			final RuntimeException cause = new RuntimeException("async fail");

			final CompletableFuture<String> failed = new CompletableFuture<>();
			failed.completeExceptionally(cause);

			final CompletableFuture<String> result = ctx.executeWithinBlockAsync(
				"test", context, () -> failed
			);

			assertTrue(result.isCompletedExceptionally(), "Result should be exceptionally completed");
			try {
				result.get();
			} catch (ExecutionException ex) {
				assertSame(cause, ex.getCause(), "Should propagate original cause");
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
