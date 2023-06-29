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
import io.evitadb.function.ExceptionRethrowingSupplier;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.lang.reflect.Modifier;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * Generic utils for Java {@link Class}es.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ClassUtils {

	/**
	 * Whether class is interface or abstract class.
	 */
	public static boolean isAbstract(@Nonnull Class<?> clazz) {
		return Modifier.isAbstract(clazz.getModifiers());
	}

	/**
	 * Method will check whether particular class is present on classpath and if so, it will evaluate lambda expression
	 * and return its result wrapped in {@link Optional}. If class is not present on classpath, {@link Optional#empty()}.
	 */
	public static <T> Optional<T> whenPresentOnClasspath(@Nonnull String className, @Nonnull ExceptionRethrowingSupplier<T> factory) {
		try {
			Class.forName(className);
			try {
				return of(factory.get());
			} catch (Exception e) {
				throw new EvitaInternalError(
					"Failed to evaluate lambda expression when a class `" + className + "` is present on classpath.",
					"Internal error.", e
				);
			}
		} catch (ClassNotFoundException e) {
			return empty();
		}
	}
}
