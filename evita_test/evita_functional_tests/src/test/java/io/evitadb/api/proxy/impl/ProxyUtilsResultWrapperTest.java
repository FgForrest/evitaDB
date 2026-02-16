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

package io.evitadb.api.proxy.impl;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.proxy.impl.ProxyUtils.OptionalProducingOperator;
import io.evitadb.api.proxy.impl.ProxyUtils.ResultWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProxyUtils#createOptionalWrapper(Method, Class)} and the various
 * {@link ResultWrapper} implementations used for wrapping proxy method results.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ProxyUtils ResultWrapper")
class ProxyUtilsResultWrapperTest {

	/**
	 * Helper interface with various method signatures for creating wrappers.
	 */
	@SuppressWarnings("unused")
	private interface WrapperTestMethods {
		String noException();
		String declaresIOException() throws IOException;
		String declaresRuntimeException() throws IllegalStateException;
		Optional<String> optionalNoException();
		Optional<String> optionalDeclaresIOException() throws IOException;
		OptionalInt optionalIntNoException();
		OptionalInt optionalIntDeclaresIOException() throws IOException;
		OptionalLong optionalLongNoException();
		OptionalLong optionalLongDeclaresIOException() throws IOException;
	}

	private static Method getMethod(String name, Class<?>... paramTypes) {
		try {
			return WrapperTestMethods.class.getDeclaredMethod(name, paramTypes);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}

	@Nested
	@DisplayName("Swallowing wrappers (no declared exceptions)")
	class SwallowingWrappers {

		@Test
		@DisplayName("should return value directly when no exception")
		void shouldReturnValueDirectlyWhenNoException() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("noException"), null
			);
			final Object result = wrapper.wrap(() -> "hello");
			assertEquals("hello", result);
		}

		@Test
		@DisplayName("should return null on ContextMissingException")
		void shouldReturnNullOnContextMissingException() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("noException"), null
			);
			final Object result = wrapper.wrap(() -> {
				throw new ContextMissingException();
			});
			assertNull(result);
		}

		@Test
		@DisplayName("should not be OptionalProducingOperator")
		void shouldNotBeOptionalProducingOperator() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("noException"), null
			);
			assertTrue(
				!(wrapper instanceof OptionalProducingOperator),
				"Unary wrapper should not be OptionalProducingOperator"
			);
		}
	}

	@Nested
	@DisplayName("Rethrowing wrappers (declared exceptions)")
	class RethrowingWrappers {

		@Test
		@DisplayName("should return value when no exception thrown")
		void shouldReturnValueWhenNoExceptionThrown() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("declaresIOException"), null
			);
			final Object result = wrapper.wrap(() -> "hello");
			assertEquals("hello", result);
		}

		@Test
		@DisplayName("should rethrow declared exception type")
		void shouldRethrowDeclaredException() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("declaresIOException"), null
			);
			assertThrows(
				RuntimeException.class,
				() -> wrapper.wrap(() -> {
					throw new RuntimeException(new IOException("test"));
				})
			);
		}

		@Test
		@DisplayName("should rethrow RuntimeException even if not declared")
		void shouldRethrowRuntimeExceptionEvenIfNotDeclared() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("declaresIOException"), null
			);
			// ContextMissingException is a RuntimeException - it should be rethrown
			// even though it's not in the declared exceptions list
			assertThrows(
				ContextMissingException.class,
				() -> wrapper.wrap(() -> {
					throw new ContextMissingException();
				})
			);
		}
	}

	@Nested
	@DisplayName("Optional wrappers")
	class OptionalWrappers {

		@Test
		@DisplayName("should wrap non-null value into Optional")
		void shouldWrapNonNullValueIntoOptional() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalNoException"), String.class
			);
			final Object result = wrapper.wrap(() -> "value");
			assertInstanceOf(Optional.class, result);
			assertEquals(Optional.of("value"), result);
		}

		@Test
		@DisplayName("should wrap null into Optional.empty()")
		void shouldWrapNullIntoOptionalEmpty() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalNoException"), String.class
			);
			final Object result = wrapper.wrap(() -> null);
			assertInstanceOf(Optional.class, result);
			assertEquals(Optional.empty(), result);
		}

		@Test
		@DisplayName("should return Optional.empty() on ContextMissingException")
		void shouldReturnOptionalEmptyOnContextMissingException() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalNoException"), String.class
			);
			final Object result = wrapper.wrap(() -> {
				throw new ContextMissingException();
			});
			assertEquals(Optional.empty(), result);
		}

		@Test
		@DisplayName("should wrap empty collection into Optional.empty()")
		void shouldWrapEmptyCollectionIntoOptionalEmpty() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalNoException"), String.class
			);
			final Object result = wrapper.wrap(Collections::emptyList);
			assertEquals(Optional.empty(), result);
		}

		@Test
		@DisplayName("should wrap non-empty collection into Optional")
		void shouldWrapNonEmptyCollectionIntoOptional() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalNoException"), String.class
			);
			final List<String> list = List.of("a", "b");
			final Object result = wrapper.wrap(() -> list);
			assertInstanceOf(Optional.class, result);
			assertEquals(Optional.of(list), result);
		}

		@Test
		@DisplayName("should be OptionalProducingOperator")
		void shouldBeOptionalProducingOperator() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalNoException"), String.class
			);
			assertInstanceOf(OptionalProducingOperator.class, wrapper);
		}
	}

	@Nested
	@DisplayName("OptionalInt wrappers")
	class OptionalIntWrappers {

		@Test
		@DisplayName("should wrap Integer into OptionalInt")
		void shouldWrapIntegerIntoOptionalInt() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalIntNoException"), int.class
			);
			final Object result = wrapper.wrap(() -> 42);
			assertInstanceOf(OptionalInt.class, result);
			assertEquals(OptionalInt.of(42), result);
		}

		@Test
		@DisplayName("should wrap null into OptionalInt.empty()")
		void shouldWrapNullIntoOptionalIntEmpty() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalIntNoException"), int.class
			);
			final Object result = wrapper.wrap(() -> null);
			assertEquals(OptionalInt.empty(), result);
		}

		@Test
		@DisplayName("should return OptionalInt.empty() on ContextMissingException")
		void shouldReturnOptionalIntEmptyOnContextMissingException() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalIntNoException"), int.class
			);
			final Object result = wrapper.wrap(() -> {
				throw new ContextMissingException();
			});
			assertEquals(OptionalInt.empty(), result);
		}

		@Test
		@DisplayName("should be OptionalProducingOperator")
		void shouldBeOptionalProducingOperator() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalIntNoException"), int.class
			);
			assertInstanceOf(OptionalProducingOperator.class, wrapper);
		}
	}

	@Nested
	@DisplayName("OptionalLong wrappers")
	class OptionalLongWrappers {

		@Test
		@DisplayName("should wrap Long into OptionalLong")
		void shouldWrapLongIntoOptionalLong() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalLongNoException"), long.class
			);
			final Object result = wrapper.wrap(() -> 99L);
			assertInstanceOf(OptionalLong.class, result);
			assertEquals(OptionalLong.of(99L), result);
		}

		@Test
		@DisplayName("should wrap null into OptionalLong.empty()")
		void shouldWrapNullIntoOptionalLongEmpty() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalLongNoException"), long.class
			);
			final Object result = wrapper.wrap(() -> null);
			assertEquals(OptionalLong.empty(), result);
		}

		@Test
		@DisplayName("should return OptionalLong.empty() on ContextMissingException")
		void shouldReturnOptionalLongEmptyOnContextMissingException() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalLongNoException"), long.class
			);
			final Object result = wrapper.wrap(() -> {
				throw new ContextMissingException();
			});
			assertEquals(OptionalLong.empty(), result);
		}

		@Test
		@DisplayName("should be OptionalProducingOperator")
		void shouldBeOptionalProducingOperator() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalLongNoException"), long.class
			);
			assertInstanceOf(OptionalProducingOperator.class, wrapper);
		}
	}

	@Nested
	@DisplayName("Rethrowing Optional wrappers")
	class RethrowingOptionalWrappers {

		@Test
		@DisplayName("should rethrow declared exception from Optional wrapper")
		void shouldRethrowDeclaredException() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalDeclaresIOException"), String.class
			);
			assertThrows(
				RuntimeException.class,
				() -> wrapper.wrap(() -> {
					throw new RuntimeException(new IOException("test"));
				})
			);
		}

		@Test
		@DisplayName("should rethrow declared exception from OptionalInt wrapper")
		void shouldRethrowDeclaredExceptionFromOptionalInt() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalIntDeclaresIOException"), int.class
			);
			assertNotNull(wrapper);
		}

		@Test
		@DisplayName("should rethrow declared exception from OptionalLong wrapper")
		void shouldRethrowDeclaredExceptionFromOptionalLong() {
			final ResultWrapper wrapper = ProxyUtils.createOptionalWrapper(
				getMethod("optionalLongDeclaresIOException"), long.class
			);
			assertNotNull(wrapper);
		}
	}
}
