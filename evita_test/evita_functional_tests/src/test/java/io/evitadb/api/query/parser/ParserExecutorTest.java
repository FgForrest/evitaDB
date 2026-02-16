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

package io.evitadb.api.query.parser;

import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.GenericEvitaInternalError;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ParserExecutor} verifying thread-local context
 * management and exception handling during query parsing.
 *
 * @author evitaDB
 */
@DisplayName("ParserExecutor")
class ParserExecutorTest {

	@Nested
	@DisplayName("execute() method")
	class ExecuteMethod {

		@Test
		@DisplayName("should return result from supplier")
		void shouldReturnResultFromSupplier() {
			final ParseContext context = new ParseContext();

			final String result = ParserExecutor.execute(context, () -> "hello");

			assertEquals("hello", result);
		}

		@Test
		@DisplayName("should set thread-local context during execution")
		void shouldSetThreadLocalContextDuringExecution() {
			final ParseContext context = new ParseContext();

			final ParseContext captured = ParserExecutor.execute(context, ParserExecutor::getContext);

			assertSame(context, captured);
		}

		@Test
		@DisplayName("should clear context after successful execution")
		void shouldClearContextAfterSuccessfulExecution() {
			final ParseContext context = new ParseContext();

			ParserExecutor.execute(context, () -> "done");

			assertThrows(EvitaInternalError.class, ParserExecutor::getContext);
		}

		@Test
		@DisplayName("should clear context after failed execution")
		void shouldClearContextAfterFailedExecution() {
			final ParseContext context = new ParseContext();

			assertThrows(GenericEvitaInternalError.class, () ->
				ParserExecutor.execute(context, () -> {
					throw new RuntimeException("boom");
				})
			);

			assertThrows(EvitaInternalError.class, ParserExecutor::getContext);
		}

		@Test
		@DisplayName("should rethrow EvitaSyntaxException as-is")
		void shouldRethrowEvitaSyntaxExceptionAsIs() {
			final ParseContext context = new ParseContext();
			final EvitaSyntaxException original = new EvitaSyntaxException(1, 0, "test error");

			final EvitaSyntaxException thrown = assertThrows(
				EvitaSyntaxException.class,
				() -> ParserExecutor.execute(context, () -> {
					throw original;
				})
			);

			assertSame(original, thrown);
		}

		@Test
		@DisplayName("should unwrap EvitaSyntaxException from ParseCancellationException")
		void shouldUnwrapEvitaSyntaxFromParseCancellation() {
			final ParseContext context = new ParseContext();
			final EvitaSyntaxException inner = new EvitaSyntaxException(2, 5, "syntax problem");

			final EvitaSyntaxException thrown = assertThrows(
				EvitaSyntaxException.class,
				() -> ParserExecutor.execute(context, () -> {
					throw new ParseCancellationException(inner);
				})
			);

			assertSame(inner, thrown);
		}

		@Test
		@DisplayName("should wrap ParseCancellationException with non-EvitaSyntax cause into GenericEvitaInternalError")
		void shouldWrapParseCancellationWithOtherCause() {
			final ParseContext context = new ParseContext();
			final RuntimeException cause = new RuntimeException("unexpected");

			final GenericEvitaInternalError thrown = assertThrows(
				GenericEvitaInternalError.class,
				() -> ParserExecutor.execute(context, () -> {
					throw new ParseCancellationException(cause);
				})
			);

			assertSame(cause, thrown.getCause());
			assertTrue(thrown.getPublicMessage().contains("Internal error occurred during query parsing."));
		}

		@Test
		@DisplayName("should wrap ParseCancellationException with null cause into GenericEvitaInternalError")
		void shouldHandleParseCancellationWithNullCause() {
			final ParseContext context = new ParseContext();

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> ParserExecutor.execute(context, () -> {
					throw new ParseCancellationException();
				})
			);
			assertEquals("Internal error occurred during query parsing.", error.getPublicMessage());
		}

		@Test
		@DisplayName("should wrap other exceptions into GenericEvitaInternalError")
		void shouldWrapOtherExceptionsIntoInternalError() {
			final ParseContext context = new ParseContext();
			final IllegalStateException cause = new IllegalStateException("bad state");

			final GenericEvitaInternalError thrown = assertThrows(
				GenericEvitaInternalError.class,
				() -> ParserExecutor.execute(context, () -> {
					throw cause;
				})
			);

			assertSame(cause, thrown.getCause());
		}

		@Test
		@DisplayName("should throw EvitaSyntaxException when supplier returns null")
		void shouldThrowWhenSupplierReturnsNull() {
			final ParseContext context = new ParseContext();

			final EvitaSyntaxException thrown = assertThrows(
				EvitaSyntaxException.class,
				() -> ParserExecutor.execute(context, () -> null)
			);

			assertTrue(thrown.getMessage().contains("Result of parse execution is null"));
		}
	}

	@Nested
	@DisplayName("getContext() method")
	class GetContextMethod {

		@Test
		@DisplayName("should return context during execution")
		void shouldReturnContextDuringExecution() {
			final ParseContext context = new ParseContext("arg1");

			final ParseContext result = ParserExecutor.execute(context, ParserExecutor::getContext);

			assertSame(context, result);
		}

		@Test
		@DisplayName("should throw when called outside execution")
		void shouldThrowWhenCalledOutsideExecution() {
			final EvitaInternalError thrown = assertThrows(EvitaInternalError.class, ParserExecutor::getContext);

			assertTrue(thrown.getMessage().contains("Missing query parse context"));
		}
	}
}
