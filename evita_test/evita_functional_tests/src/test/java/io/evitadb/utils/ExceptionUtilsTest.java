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
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.DATA_TYPE;

/**
 * Tests for {@link ExceptionUtils} verifying root cause extraction,
 * completion exception unwrapping, and cause chain inspection.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ExceptionUtils contract tests")
@Tag(ENGINE)
@Tag(DATA_TYPE)
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

	@Nested
	@DisplayName("Cause chain instance lookup")
	class FindInCauseChainTest {

		@Test
		@DisplayName("should return the matching instance itself, not merely a verdict")
		void shouldReturnMatchingInstance() {
			// the point of this method over causeChainContains: callers need state off the instance
			final IOException rootCause = new IOException("IO error");
			final RuntimeException topLevel = new RuntimeException("top", rootCause);

			assertSame(rootCause, ExceptionUtils.findInCauseChain(topLevel, IOException.class));
		}

		@Test
		@DisplayName("should return the throwable itself when it already matches")
		void shouldReturnThrowableItselfWhenMatching() {
			final IOException exception = new IOException("IO error");

			assertSame(exception, ExceptionUtils.findInCauseChain(exception, IOException.class));
		}

		@Test
		@DisplayName("should return the outermost match when several are present")
		void shouldReturnOutermostMatch() {
			final IllegalStateException rootCause = new IllegalStateException("inner");
			final IllegalStateException intermediate = new IllegalStateException("outer", rootCause);
			final RuntimeException topLevel = new RuntimeException("top", intermediate);

			assertSame(intermediate, ExceptionUtils.findInCauseChain(topLevel, IllegalStateException.class));
		}

		@Test
		@DisplayName("should return null when the type is absent from the chain")
		void shouldReturnNullWhenTypeAbsent() {
			final RuntimeException topLevel = new RuntimeException("top", new IOException("io"));

			assertNull(ExceptionUtils.findInCauseChain(topLevel, CancellationException.class));
		}

		@Test
		@DisplayName("should terminate on a two-element circular reference when the type is absent")
		void shouldTerminateOnCircularReferenceWhenTypeAbsent() {
			// `A -> B -> A` is the shape a self-reference guard (`current.getCause() == current`)
			// misses; only the visited-set traversal terminates here
			final CircularException exception1 = new CircularException("Exception 1");
			final CircularException exception2 = new CircularException("Exception 2", exception1);
			exception1.initCause(exception2);

			assertNull(ExceptionUtils.findInCauseChain(exception1, CancellationException.class));
		}

		@Test
		@DisplayName("should find the type inside a circular reference chain")
		void shouldFindTypeInCircularReferenceChain() {
			final CircularException exception1 = new CircularException("Exception 1");
			final CircularException exception2 = new CircularException("Exception 2", exception1);
			exception1.initCause(exception2);

			assertSame(exception1, ExceptionUtils.findInCauseChain(exception1, CircularException.class));
		}

		@Test
		@DisplayName("should match a superclass of an exception in the chain")
		void shouldMatchSuperclassInCauseChain() {
			// the top level is deliberately *not* a RuntimeException, so the match can only come from
			// the cause - otherwise this would pass without walking the chain at all
			final IllegalArgumentException rootCause = new IllegalArgumentException("arg");
			final IOException topLevel = new IOException("top", rootCause);

			assertSame(rootCause, ExceptionUtils.findInCauseChain(topLevel, RuntimeException.class));
		}
	}

	@Nested
	@DisplayName("Completion / execution wrapper unwrapping")
	class UnwrapCompletionWrappersTest {

		@Test
		@DisplayName("should return the throwable as-is when it is not a wrapper")
		void shouldReturnThrowableAsIsWhenNotWrapper() {
			final IllegalStateException domain = new IllegalStateException("domain failure");

			final Throwable result = ExceptionUtils.unwrapCompletionWrappers(domain);

			assertSame(domain, result);
		}

		@Test
		@DisplayName("should peel a single CompletionException layer")
		void shouldPeelSingleCompletionExceptionLayer() {
			final IllegalArgumentException domain = new IllegalArgumentException("domain failure");
			final CompletionException wrapper = new CompletionException(domain);

			final Throwable result = ExceptionUtils.unwrapCompletionWrappers(wrapper);

			assertSame(domain, result);
		}

		@Test
		@DisplayName("should peel a single ExecutionException layer")
		void shouldPeelSingleExecutionExceptionLayer() {
			final IOException domain = new IOException("domain failure");
			final ExecutionException wrapper = new ExecutionException(domain);

			final Throwable result = ExceptionUtils.unwrapCompletionWrappers(wrapper);

			assertSame(domain, result);
		}

		@Test
		@DisplayName("should peel multiple nested CompletionException layers")
		void shouldPeelMultipleNestedCompletionExceptionLayers() {
			final CancellationException domain = new CancellationException("cancelled");
			final CompletionException wrapper1 = new CompletionException(domain);
			final CompletionException wrapper2 = new CompletionException(wrapper1);
			final CompletionException wrapper3 = new CompletionException(wrapper2);

			final Throwable result = ExceptionUtils.unwrapCompletionWrappers(wrapper3);

			assertSame(domain, result);
		}

		@Test
		@DisplayName("should peel mixed CompletionException and ExecutionException layers")
		void shouldPeelMixedCompletionAndExecutionLayers() {
			final IllegalStateException domain = new IllegalStateException("domain failure");
			final CompletionException inner = new CompletionException(domain);
			final ExecutionException middle = new ExecutionException(inner);
			final CompletionException outer = new CompletionException(middle);

			final Throwable result = ExceptionUtils.unwrapCompletionWrappers(outer);

			assertSame(domain, result);
		}

		@Test
		@DisplayName("should stop at the first non-wrapper even when its cause is itself a wrapper")
		void shouldStopAtFirstNonWrapperEvenWhenCauseIsWrapper() {
			// Domain exception happens to carry a CompletionException as its own cause —
			// unwrapCompletionWrappers must NOT peel past the domain exception.
			final CompletionException deepHidden = new CompletionException(new IOException("hidden"));
			final IllegalStateException domain = new IllegalStateException("domain failure", deepHidden);
			final CompletionException wrapper = new CompletionException(domain);

			final Throwable result = ExceptionUtils.unwrapCompletionWrappers(wrapper);

			assertSame(domain, result);
			// The domain exception's own cause chain remains intact.
			assertSame(deepHidden, result.getCause());
		}

		@Test
		@DisplayName("should return outermost wrapper when it has a null cause")
		void shouldReturnOutermostWrapperWhenCauseIsNull() {
			final NoCauseCompletionException wrapperWithoutCause = new NoCauseCompletionException();

			final Throwable result = ExceptionUtils.unwrapCompletionWrappers(wrapperWithoutCause);

			assertSame(wrapperWithoutCause, result);
		}

		@Test
		@DisplayName("should peel until the deepest wrapper when its cause is null")
		void shouldStopAtDeepestWrapperWithNullCause() {
			final NoCauseCompletionException deepest = new NoCauseCompletionException();
			final CompletionException middle = new CompletionException(deepest);
			final CompletionException outer = new CompletionException(middle);

			final Throwable result = ExceptionUtils.unwrapCompletionWrappers(outer);

			assertSame(deepest, result);
		}

		@Test
		@DisplayName("should terminate without infinite loop on a wrapper cycle")
		void shouldTerminateOnWrapperCycle() {
			// Build a 2-node cycle: a → b → a. The no-cause subclass exposes the protected no-arg
			// constructor so we can install causes via initCause after construction.
			final NoCauseCompletionException ex1 = new NoCauseCompletionException();
			final NoCauseCompletionException ex2 = new NoCauseCompletionException();
			ex1.initCause(ex2);
			ex2.initCause(ex1);

			final Throwable result = ExceptionUtils.unwrapCompletionWrappers(ex1);

			// Cycle detected — must return one of the two wrappers (not loop forever, not throw).
			assertTrue(result == ex1 || result == ex2);
		}

		@Test
		@DisplayName("should return original throwable when depth cap is reached")
		void shouldReturnOriginalWhenDepthCapReached() {
			// Build a chain of strictly-more-than-MAX_DEPTH wrappers above a domain exception so the
			// loop cannot peel through to the domain — the depth cap fires and returns the original.
			Throwable current = new IllegalStateException("never reached");
			for (int i = 0; i < ExceptionUtils.UNWRAP_COMPLETION_WRAPPERS_MAX_DEPTH + 5; i++) {
				current = new CompletionException(current);
			}
			final Throwable top = current;

			final Throwable result = ExceptionUtils.unwrapCompletionWrappers(top);

			assertSame(top, result);
		}

		@Test
		@DisplayName("should peel a chain just below the depth cap")
		void shouldPeelChainJustBelowDepthCap() {
			// MAX_DEPTH - 1 wrappers above a domain exception. The loop spends MAX_DEPTH - 1 peels
			// to reach the domain layer and one final check iteration to recognize and return it.
			final IllegalStateException domain = new IllegalStateException("just below cap");
			Throwable current = domain;
			for (int i = 0; i < ExceptionUtils.UNWRAP_COMPLETION_WRAPPERS_MAX_DEPTH - 1; i++) {
				current = new CompletionException(current);
			}

			final Throwable result = ExceptionUtils.unwrapCompletionWrappers(current);

			assertSame(domain, result);
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

	/**
	 * Test-only subclass that exposes {@link CompletionException}'s protected no-arg constructor
	 * so the cycle and null-cause tests can install a cause post-construction via `initCause`.
	 */
	private static class NoCauseCompletionException extends CompletionException {
		@Serial private static final long serialVersionUID = 5723985482014104221L;

		NoCauseCompletionException() {
			super();
		}
	}
}
