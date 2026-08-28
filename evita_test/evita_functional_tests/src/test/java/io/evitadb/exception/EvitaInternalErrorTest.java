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

package io.evitadb.exception;

import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serial;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies behaviour of {@link EvitaInternalError}, and in particular that
 * {@link EvitaError#getErrorCode()} identifies the place the exception was created.
 *
 * The assertions deliberately compare against `hashChars(class)` / `hashChars(method)` rather than against a
 * pre-computed code string: a literal code also encodes a line number, so it breaks whenever anything above it
 * moves, and - as the previous revision of this test showed - a literal happily locks in a code that points at the
 * exception machinery itself instead of at the caller.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("EvitaInternalError contract tests")
@Tag(CONTRACT)
@Tag(MANAGEMENT)
class EvitaInternalErrorTest {
	private static final String THIS_CLASS_HASH = StringUtils.hashChars(EvitaInternalErrorTest.class.getName());

	@Nonnull
	private static EvitaInternalError createAtSiteOne() {
		return new GenericEvitaInternalError("Whatever");
	}

	@Nonnull
	private static EvitaInternalError createAtSiteTwo() {
		return new GenericEvitaInternalError("Whatever");
	}

	@Nonnull
	private static EvitaInternalError raiseViaAssert() {
		try {
			Assert.isPremiseValid(false, "Whatever");
			throw new IllegalStateException("Assertion unexpectedly passed.");
		} catch (GenericEvitaInternalError ex) {
			return ex;
		}
	}

	@Nonnull
	private static EvitaInternalError raiseViaOtherAssert() {
		try {
			Assert.isPremiseValid(false, "Whatever else");
			throw new IllegalStateException("Assertion unexpectedly passed.");
		} catch (GenericEvitaInternalError ex) {
			return ex;
		}
	}

	@Nested
	@DisplayName("error code identifies the construction site")
	class ErrorCodeTests {

		@Test
		@DisplayName("Should attribute the code to the creating class and method")
		void shouldAttributeCodeToCreatingClassAndMethod() {
			final String[] parts = createAtSiteOne().getErrorCode().split(":");
			assertEquals(3, parts.length);
			assertEquals(THIS_CLASS_HASH, parts[0]);
			assertEquals(StringUtils.hashChars("createAtSiteOne"), parts[1]);
			assertTrue(Integer.parseInt(parts[2]) > 0);
		}

		@Test
		@DisplayName("Should produce different codes for different construction sites")
		void shouldProduceDifferentCodesForDifferentSites() {
			assertNotEquals(createAtSiteOne().getErrorCode(), createAtSiteTwo().getErrorCode());
		}

		@Test
		@DisplayName("Should produce a stable code for the same construction site")
		void shouldProduceStableCodeForSameSite() {
			assertEquals(createAtSiteOne().getErrorCode(), createAtSiteOne().getErrorCode());
		}

		@Test
		@DisplayName("Should return the same code on repeated reads")
		void shouldReturnSameCodeOnRepeatedReads() {
			final EvitaInternalError error = createAtSiteOne();
			assertEquals(error.getErrorCode(), error.getErrorCode());
		}
	}

	@Nested
	@DisplayName("assertion helper frames are skipped")
	class AssertionHelperTests {

		@Test
		@DisplayName("Should attribute an assertion failure to its caller, not to Assert")
		void shouldAttributeAssertionFailureToCaller() {
			final String[] parts = raiseViaAssert().getErrorCode().split(":");
			assertEquals(THIS_CLASS_HASH, parts[0]);
			assertEquals(StringUtils.hashChars("raiseViaAssert"), parts[1]);
		}

		@Test
		@DisplayName("Should distinguish two different assertion call sites")
		void shouldDistinguishTwoAssertionCallSites() {
			assertNotEquals(raiseViaAssert().getErrorCode(), raiseViaOtherAssert().getErrorCode());
		}
	}

	@Nested
	@DisplayName("subclasses and wire-supplied codes")
	class SubclassAndWireTests {

		@Test
		@DisplayName("Should attribute a subclass instance to its own construction site")
		void shouldAttributeSubclassToItsOwnSite() {
			// constructed inline here rather than through an outer helper, so the origin must name *this* nested
			// class - attribution is per creating frame, not per enclosing test class
			final String[] parts = new TestInternalError("Whatever").getErrorCode().split(":");
			assertEquals(StringUtils.hashChars(SubclassAndWireTests.class.getName()), parts[0]);
			assertEquals(StringUtils.hashChars("shouldAttributeSubclassToItsOwnSite"), parts[1]);
		}

		@Test
		@DisplayName("Should not share a code between a subclass and its parent")
		void shouldNotShareCodeBetweenSubclassAndParent() {
			assertNotEquals(new TestInternalError("Whatever").getErrorCode(), createAtSiteOne().getErrorCode());
		}

		@Test
		@DisplayName("Should preserve a code supplied from the wire")
		void shouldPreserveCodeSuppliedFromWire() {
			assertEquals(
				"deadbeef:cafebabe:42",
				GenericEvitaInternalError.createExceptionWithErrorCode("Whatever", "deadbeef:cafebabe:42")
					.getErrorCode()
			);
		}
	}

	/**
	 * Subclass used to prove that a nested type reports its own construction site rather than the code of the
	 * hierarchy root - the failure mode the previous implementation had for every internal error.
	 */
	private static class TestInternalError extends GenericEvitaInternalError {
		@Serial private static final long serialVersionUID = 6141847334832823348L;

		TestInternalError(@Nonnull String publicMessage) {
			super(publicMessage);
		}
	}
}
