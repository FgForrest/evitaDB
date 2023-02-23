/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.utils;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * Assertion utils to verify expectations.
 * We know these functions are available in common libraries, but we try to keep our transitive dependencies as low as
 * possible so we rather went through duplication of the code.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class Assert {

	private Assert() {
	}

	/**
	 * Assert that an object is not NULL.
	 *
	 * @param object  the object to check
	 * @param message the exception message to use if the assertion fails
	 * @throws IllegalArgumentException if the object is NULL
	 */
	public static void notNull(Object object, @Nonnull String message) {
		if (object == null) {
			throw new EvitaInvalidUsageException(message);
		}
	}

	/**
	 * Assert that an object is not NULL.
	 *
	 * @param object  the object to check
	 * @param message the exception message to use if the assertion fails
	 * @throws IllegalArgumentException if the object is NULL
	 */
	public static void notNull(Object object, @Nonnull Supplier<String> message) {
		if (object == null) {
			throw new EvitaInvalidUsageException(message.get());
		}
	}

	/**
	 * Assert that an object is not NULL.
	 *
	 * @param object  the object to check
	 * @param exceptionFactory the exception to use if the assertion fails
	 * @throws IllegalArgumentException if the object is NULL
	 */
	public static void notNull(Object object, @Nonnull InvalidUsageExceptionFactory<?> exceptionFactory) {
		if (object == null) {
			throw exceptionFactory.get();
		}
	}

	/**
	 * Asserts that the first argument evaluates to true and if not, {@link EvitaInternalError} exception is thrown.
	 */
	public static void isPremiseValid(boolean theValue, @Nonnull String message) {
		if (!theValue) {
			throw new EvitaInternalError(message);
		}
	}

	/**
	 * Asserts that the first argument evaluates to true and if not, {@link EvitaInternalError} exception is thrown.
	 */
	public static void isPremiseValid(boolean theValue, @Nonnull Supplier<String> message) {
		if (!theValue) {
			throw new EvitaInternalError(message.get());
		}
	}

	/**
	 * Asserts that the first argument evaluates to true and if not, {@link EvitaInternalError} exception is thrown.
	 */
	public static void isPremiseValid(boolean theValue, @Nonnull InternalErrorFactory<?> exceptionFactory) {
		if (!theValue) {
			throw exceptionFactory.get();
		}
	}

	/**
	 * Asserts that the first argument evaluates to true and if not, {@link EvitaInvalidUsageException} exception is thrown.
	 */
	public static void isTrue(boolean theValue, @Nonnull String message) {
		if (!theValue) {
			throw new EvitaInvalidUsageException(message);
		}
	}

	/**
	 * Asserts that the first argument evaluates to true and if not, {@link EvitaInvalidUsageException} exception is thrown.
	 */
	public static void isTrue(boolean theValue, @Nonnull Supplier<String> message) {
		if (!theValue) {
			throw new EvitaInvalidUsageException(message.get());
		}
	}

	/**
	 * Asserts that the first argument evaluates to true and if not, exception is thrown. The exception is created
	 * by the supplier logic passed in the second argument.
	 */
	public static void isTrue(boolean theValue, @Nonnull InvalidUsageExceptionFactory<?> exceptionFactory) {
		if (!theValue) {
			throw exceptionFactory.get();
		}
	}

	@FunctionalInterface
	public interface InternalErrorFactory<T extends EvitaInternalError> extends Supplier<T> {}

	@FunctionalInterface
	public interface InvalidUsageExceptionFactory<T extends EvitaInvalidUsageException> extends Supplier<T> {}

}
