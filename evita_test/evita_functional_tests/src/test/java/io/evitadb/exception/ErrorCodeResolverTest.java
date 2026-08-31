/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.exception;

import io.evitadb.utils.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the degenerate stack traces {@link ErrorCodeResolver} has to survive - the ones a normally-thrown exception
 * never produces, and which therefore go unexercised by the ordinary error-code tests.
 *
 * All of them matter for one reason: an error code travels to a client inside the gRPC status message and is parsed
 * back out of it with an anchored `\w+:\w+:\w+` pattern. A code that cannot satisfy that shape does not degrade to a
 * worse code - it degrades to no code at all.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ErrorCodeResolver degenerate stack traces")
@Tag(CONTRACT)
@Tag(MANAGEMENT)
class ErrorCodeResolverTest {
	/**
	 * The shape every code must keep, mirroring the pattern the gRPC driver parses codes with.
	 */
	private static final String WIRE_SHAPE = "\\w+:\\w+:\\w+";

	@Nested
	@DisplayName("no usable frame")
	class NoUsableFrameTests {

		@Test
		@DisplayName("Should fall back to the unknown code when the stack trace is empty")
		void shouldFallBackWhenStackTraceIsEmpty() {
			final GenericEvitaInternalError error = new GenericEvitaInternalError("Whatever");
			error.setStackTrace(new StackTraceElement[0]);
			assertEquals(ErrorCodeResolver.UNKNOWN_ERROR_CODE, error.getErrorCode());
		}

		@Test
		@DisplayName("Should fall back to the unknown code when every frame is the assertion helper")
		void shouldFallBackWhenEveryFrameIsAssertionHelper() {
			// the skip loop runs off the end of a non-empty array - a different branch from the empty-array case
			final String assertClass = Assert.class.getName();
			final GenericEvitaInternalError error = new GenericEvitaInternalError("Whatever");
			error.setStackTrace(new StackTraceElement[]{
				new StackTraceElement(assertClass, "isPremiseValid", "Assert.java", 90),
				new StackTraceElement(assertClass, "notNull", "Assert.java", 55)
			});
			assertEquals(ErrorCodeResolver.UNKNOWN_ERROR_CODE, error.getErrorCode());
		}

		@Test
		@DisplayName("Should keep the unknown code itself within the wire shape")
		void shouldKeepUnknownCodeWithinWireShape() {
			assertTrue(
				ErrorCodeResolver.UNKNOWN_ERROR_CODE.matches(WIRE_SHAPE),
				"the sentinel must survive the same parse a real code does, or it degrades to no code at all"
			);
		}
	}

	@Nested
	@DisplayName("frames without line numbers")
	class MissingLineNumberTests {

		@Test
		@DisplayName("Should keep the wire shape when the frame carries no line number")
		void shouldKeepWireShapeForNegativeLineNumber() {
			// -1 is what a class compiled without a LineNumberTable reports
			final GenericEvitaInternalError error = new GenericEvitaInternalError("Whatever");
			error.setStackTrace(new StackTraceElement[]{
				new StackTraceElement("com.example.Generated", "invoke", null, -1)
			});
			assertTrue(error.getErrorCode().matches(WIRE_SHAPE), error.getErrorCode());
		}

		@Test
		@DisplayName("Should keep the wire shape for a native frame")
		void shouldKeepWireShapeForNativeFrame() {
			// -2 is what a native method frame reports
			final GenericEvitaInternalError error = new GenericEvitaInternalError("Whatever");
			error.setStackTrace(new StackTraceElement[]{
				new StackTraceElement("com.example.Native", "invoke", null, -2)
			});
			assertTrue(error.getErrorCode().matches(WIRE_SHAPE), error.getErrorCode());
		}

		@Test
		@DisplayName("Should still distinguish two frames that differ only in class")
		void shouldStillDistinguishFramesWithoutLineNumbers() {
			final GenericEvitaInternalError first = new GenericEvitaInternalError("Whatever");
			first.setStackTrace(new StackTraceElement[]{
				new StackTraceElement("com.example.One", "invoke", null, -1)
			});
			final GenericEvitaInternalError second = new GenericEvitaInternalError("Whatever");
			second.setStackTrace(new StackTraceElement[]{
				new StackTraceElement("com.example.Two", "invoke", null, -1)
			});
			assertTrue(!first.getErrorCode().equals(second.getErrorCode()));
		}
	}
}
