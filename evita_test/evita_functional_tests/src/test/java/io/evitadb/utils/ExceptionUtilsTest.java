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

package io.evitadb.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serial;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ExceptionUtils} verifying root cause extraction,
 * completion exception unwrapping, and cause chain inspection.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ExceptionUtils functionality")
class ExceptionUtilsTest {

	@Nested
	@DisplayName("Root cause extraction")
	class GetRootCauseTest {

		@Test
		@DisplayName("should return the exception itself when it has no cause")
		void shouldReturnSameExceptionWhenNoCause() {
			final RuntimeException exception = new RuntimeException("Test exception");

			final Throwable rootCause = ExceptionUtils.getRootCause(exception);

			assertSame(exception, rootCause);
		}

		@Test
		@DisplayName("should traverse chain to find deepest cause")
		void shouldFindRootCauseInExceptionChain() {
			final IllegalArgumentException rootCause = new IllegalArgumentException("Root cause");
			final IllegalStateException intermediate = new IllegalStateException("Intermediate", rootCause);
			final RuntimeException topLevel = new RuntimeException("Top level", intermediate);

			final Throwable foundRootCause = ExceptionUtils.getRootCause(topLevel);

			assertSame(rootCause, foundRootCause);
		}

		@Test
		@DisplayName("should terminate without infinite loop on circular reference")
		void shouldHandleCircularReferenceInExceptionChain() {
			final CircularException exception1 = new CircularException("Exception 1");
			final CircularException exception2 = new CircularException("Exception 2", exception1);
			// create circular reference
			exception1.initCause(exception2);

			final Throwable rootCause = ExceptionUtils.getRootCause(exception1);

			// should not enter infinite loop; returns exception1 because the visited-set stops traversal there
			assertSame(exception1, rootCause);
		}

		@Test
		@DisplayName("should return single cause for two-level chain")
		void shouldReturnDirectCause() {
			final IOException cause = new IOException("IO failure");
			final RuntimeException wrapper = new RuntimeException("Wrapper", cause);

			final Throwable rootCause = ExceptionUtils.getRootCause(wrapper);

			assertSame(cause, rootCause);
		}
	}

	@Nested
	@DisplayName("Completion exception unwrapping")
	class UnwrapCompletionExceptionTest {

		@Test
		@DisplayName("should return supplier result when no exception thrown")
		void shouldReturnSupplierResultWhenNoExceptionThrown() {
			final String expectedResult = "test result";

			final String result = ExceptionUtils.unwrapCompletionException(() -> expectedResult);

			assertEquals(expectedResult, result);
		}

		@Test
		@DisplayName("should unwrap RuntimeException cause from CompletionException")
		void shouldUnwrapRuntimeExceptionFromCompletionException() {
			final IllegalArgumentException originalException = new IllegalArgumentException("Original exception");
			final CompletionException completionException = new CompletionException(originalException);

			final IllegalArgumentException thrownException = assertThrows(
				IllegalArgumentException.class,
				() -> ExceptionUtils.unwrapCompletionException(() -> { throw completionException; })
			);

			assertSame(originalException, thrownException);
		}

		@Test
		@DisplayName("should rethrow CompletionException when cause is checked exception")
		void shouldRethrowCompletionExceptionWhenCauseIsNotRuntimeException() {
			final Exception originalException = new Exception("Original exception");
			final CompletionException completionException = new CompletionException(originalException);

			final CompletionException thrownException = assertThrows(
				CompletionException.class,
				() -> ExceptionUtils.unwrapCompletionException(() -> { throw completionException; })
			);

			assertSame(completionException, thrownException);
			assertSame(originalException, thrownException.getCause());
		}

		@Test
		@DisplayName("should rethrow CompletionException when cause is null")
		void shouldRethrowCompletionExceptionWhenCauseIsNull() {
			final CompletionException completionException = new CompletionException(null);

			final CompletionException thrownException = assertThrows(
				CompletionException.class,
				() -> ExceptionUtils.unwrapCompletionException(() -> { throw completionException; })
			);

			assertSame(completionException, thrownException);
		}

		@Test
		@DisplayName("should pass through non-CompletionException as-is")
		void shouldPassThroughNonCompletionExceptions() {
			final RuntimeException originalException = new RuntimeException("Original exception");

			final RuntimeException thrownException = assertThrows(
				RuntimeException.class,
				() -> ExceptionUtils.unwrapCompletionException(() -> { throw originalException; })
			);

			assertSame(originalException, thrownException);
		}
	}

	@Nested
	@DisplayName("Cause chain type inspection")
	class CauseChainContainsTest {

		@Test
		@DisplayName("should match when exception itself is the target type")
		void shouldFindDirectMatchInCauseChain() {
			final CancellationException exception = new CancellationException("cancelled");

			assertTrue(ExceptionUtils.causeChainContains(exception, CancellationException.class));
		}

		@Test
		@DisplayName("should find target type nested deep in cause chain")
		void shouldFindNestedCauseInCauseChain() {
			final CancellationException rootCause = new CancellationException("cancelled");
			final RuntimeException intermediate = new RuntimeException("wrapper", rootCause);
			final CompletionException topLevel = new CompletionException(intermediate);

			assertTrue(ExceptionUtils.causeChainContains(topLevel, CancellationException.class));
		}

		@Test
		@DisplayName("should return false when target type is not in chain")
		void shouldReturnFalseWhenTypeNotInCauseChain() {
			final RuntimeException exception = new RuntimeException("not cancelled");

			assertFalse(ExceptionUtils.causeChainContains(exception, CancellationException.class));
		}

		@Test
		@DisplayName("should return false for single exception without matching type")
		void shouldReturnFalseForExceptionWithNoCause() {
			final IllegalArgumentException exception = new IllegalArgumentException("simple");

			assertFalse(ExceptionUtils.causeChainContains(exception, CancellationException.class));
		}

		@Test
		@DisplayName("should terminate on circular reference when type is absent")
		void shouldHandleCircularReferenceWhenTypeAbsent() {
			final CircularException exception1 = new CircularException("Exception 1");
			final CircularException exception2 = new CircularException("Exception 2", exception1);
			exception1.initCause(exception2);

			// should not enter infinite loop
			assertFalse(ExceptionUtils.causeChainContains(exception1, CancellationException.class));
		}

		@Test
		@DisplayName("should find type in circular reference chain when present")
		void shouldFindTypeInCircularReferenceChain() {
			final CircularException exception1 = new CircularException("Exception 1");
			final CircularException exception2 = new CircularException("Exception 2", exception1);
			exception1.initCause(exception2);

			assertTrue(ExceptionUtils.causeChainContains(exception1, CircularException.class));
		}

		@Test
		@DisplayName("should match superclass of an exception in the chain")
		void shouldMatchSuperclassInCauseChain() {
			// CancellationException extends IllegalStateException
			final CancellationException exception = new CancellationException("cancelled");
			final CompletionException wrapper = new CompletionException(exception);

			assertTrue(ExceptionUtils.causeChainContains(wrapper, IllegalStateException.class));
		}

		@Test
		@DisplayName("should match Throwable for any exception in chain")
		void shouldMatchThrowableForAnyException() {
			final RuntimeException exception = new RuntimeException("anything");

			assertTrue(ExceptionUtils.causeChainContains(exception, Throwable.class));
		}

		@Test
		@DisplayName("should return false for chain without matching subtype")
		void shouldReturnFalseWhenMultipleCausesNoneMatch() {
			final IOException rootCause = new IOException("IO error");
			final RuntimeException intermediate = new RuntimeException("wrapper", rootCause);
			final IllegalStateException topLevel = new IllegalStateException("top", intermediate);

			assertFalse(ExceptionUtils.causeChainContains(topLevel, CancellationException.class));
		}

		@Test
		@DisplayName("should match the exact intermediate cause type")
		void shouldMatchIntermediateCauseExactly() {
			final IOException rootCause = new IOException("IO error");
			final IllegalStateException intermediate = new IllegalStateException("state", rootCause);
			final RuntimeException topLevel = new RuntimeException("top", intermediate);

			assertTrue(ExceptionUtils.causeChainContains(topLevel, IllegalStateException.class));
		}
	}

	/**
	 * Custom exception that allows setting a cause after construction,
	 * enabling circular cause chain creation for testing.
	 */
	private static class CircularException extends Exception {
		@Serial private static final long serialVersionUID = -6294796000814985596L;

		CircularException(@Nonnull String message) {
			super(message);
		}

		CircularException(@Nonnull String message, @Nonnull Throwable cause) {
			super(message, cause);
		}
	}
}
