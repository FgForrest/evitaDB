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

package io.evitadb.utils;

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test verifies contract of {@link Assert} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Assert contract tests")
class AssertTest {

	@Nested
	@DisplayName("notNull tests")
	class NotNullTests {

		@Test
		@DisplayName("Should pass when object is not null")
		void shouldPassWhenObjectIsNotNull() {
			assertDoesNotThrow(() -> Assert.notNull("test", "Object must not be null"));
		}

		@Test
		@DisplayName("Should throw EvitaInvalidUsageException when object is null")
		void shouldThrowEvitaInvalidUsageExceptionWhenObjectIsNull() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> Assert.notNull(null, "Object must not be null")
			);
			assertEquals("Object must not be null", exception.getPublicMessage());
		}

		@Test
		@DisplayName("Should use supplier message lazily")
		void shouldUseSupplierMessageLazily() {
			final AtomicBoolean supplierCalled = new AtomicBoolean(false);
			assertDoesNotThrow(() -> Assert.notNull("test", () -> {
				supplierCalled.set(true);
				return "Should not be called";
			}));
			assertFalse(supplierCalled.get(), "Supplier should not be called when object is not null");

			final AtomicBoolean supplierCalledOnNull = new AtomicBoolean(false);
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> Assert.notNull(null, () -> {
					supplierCalledOnNull.set(true);
					return "Lazy message";
				})
			);
			assertTrue(supplierCalledOnNull.get(), "Supplier should be called when object is null");
			assertEquals("Lazy message", exception.getPublicMessage());
		}

		@Test
		@DisplayName("Should throw custom exception from factory")
		void shouldThrowCustomExceptionFromFactory() {
			final CustomInvalidUsageException exception = assertThrows(
				CustomInvalidUsageException.class,
				() -> Assert.notNull(null, () -> new CustomInvalidUsageException("Custom message"))
			);
			assertEquals("Custom message", exception.getPublicMessage());
		}
	}

	@Nested
	@DisplayName("isTrue tests")
	class IsTrueTests {

		@Test
		@DisplayName("Should pass when condition is true")
		void shouldPassWhenConditionIsTrue() {
			assertDoesNotThrow(() -> Assert.isTrue(true, "Condition must be true"));
		}

		@Test
		@DisplayName("Should throw EvitaInvalidUsageException when condition is false")
		void shouldThrowEvitaInvalidUsageExceptionWhenConditionIsFalse() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> Assert.isTrue(false, "Condition must be true")
			);
			assertEquals("Condition must be true", exception.getPublicMessage());
		}

		@Test
		@DisplayName("Should use supplier message lazily")
		void shouldUseSupplierMessageLazily() {
			final AtomicBoolean supplierCalled = new AtomicBoolean(false);
			assertDoesNotThrow(() -> Assert.isTrue(true, () -> {
				supplierCalled.set(true);
				return "Should not be called";
			}));
			assertFalse(supplierCalled.get(), "Supplier should not be called when condition is true");

			final AtomicBoolean supplierCalledOnFalse = new AtomicBoolean(false);
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> Assert.isTrue(false, () -> {
					supplierCalledOnFalse.set(true);
					return "Lazy message";
				})
			);
			assertTrue(supplierCalledOnFalse.get(), "Supplier should be called when condition is false");
			assertEquals("Lazy message", exception.getPublicMessage());
		}

		@Test
		@DisplayName("Should throw custom exception from factory")
		void shouldThrowCustomExceptionFromFactory() {
			final CustomInvalidUsageException exception = assertThrows(
				CustomInvalidUsageException.class,
				() -> Assert.isTrue(false, () -> new CustomInvalidUsageException("Custom message"))
			);
			assertEquals("Custom message", exception.getPublicMessage());
		}
	}

	@Nested
	@DisplayName("isPremiseValid tests")
	class IsPremiseValidTests {

		@Test
		@DisplayName("Should pass when premise is true")
		void shouldPassWhenPremiseIsTrue() {
			assertDoesNotThrow(() -> Assert.isPremiseValid(true, "Premise must be valid"));
		}

		@Test
		@DisplayName("Should throw GenericEvitaInternalError when premise is false")
		void shouldThrowGenericEvitaInternalErrorWhenPremiseIsFalse() {
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> Assert.isPremiseValid(false, "Premise must be valid")
			);
			assertEquals("Premise must be valid", exception.getPublicMessage());
		}

		@Test
		@DisplayName("Should use supplier message lazily")
		void shouldUseSupplierMessageLazily() {
			final AtomicBoolean supplierCalled = new AtomicBoolean(false);
			assertDoesNotThrow(() -> Assert.isPremiseValid(true, () -> {
				supplierCalled.set(true);
				return "Should not be called";
			}));
			assertFalse(supplierCalled.get(), "Supplier should not be called when premise is valid");

			final AtomicBoolean supplierCalledOnFalse = new AtomicBoolean(false);
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> Assert.isPremiseValid(false, () -> {
					supplierCalledOnFalse.set(true);
					return "Lazy message";
				})
			);
			assertTrue(supplierCalledOnFalse.get(), "Supplier should be called when premise is invalid");
			assertEquals("Lazy message", exception.getPublicMessage());
		}

		@Test
		@DisplayName("Should throw custom exception from factory")
		void shouldThrowCustomExceptionFromFactory() {
			final CustomInternalError exception = assertThrows(
				CustomInternalError.class,
				() -> Assert.isPremiseValid(false, () -> new CustomInternalError("Custom internal error"))
			);
			assertEquals("Custom internal error", exception.getPublicMessage());
		}
	}

	/**
	 * Custom exception for testing exception factory functionality.
	 */
	private static class CustomInvalidUsageException extends EvitaInvalidUsageException {
		private static final long serialVersionUID = 1L;

		public CustomInvalidUsageException(String publicMessage) {
			super(publicMessage);
		}
	}

	/**
	 * Custom internal error for testing exception factory functionality.
	 */
	private static class CustomInternalError extends GenericEvitaInternalError {
		private static final long serialVersionUID = 1L;

		public CustomInternalError(String publicMessage) {
			super(publicMessage);
		}
	}
}
