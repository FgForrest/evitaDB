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

package io.evitadb.api.proxy.impl;

import io.evitadb.utils.ArrayUtils;
import one.edee.oss.proxycian.utils.GenericsUtils.GenericBundle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static one.edee.oss.proxycian.utils.GenericsUtils.getMethodReturnType;
import static one.edee.oss.proxycian.utils.GenericsUtils.getNestedMethodReturnTypes;

/**
 * Class contains utility methods for working with generics.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ProxyUtils {

	/**
	 * Returns the wrapped generic type of the method if it is an Optional or OptionalInt or OptionalLong.
	 */
	public static Class<?> getWrappedGenericType(
		@Nonnull Method method,
		@Nonnull Class<?> ownerClass
	) {
		final Class<?> returnType = method.getReturnType();
		final Class<?> wrappedType;
		if (Optional.class.isAssignableFrom(returnType)) {
			wrappedType = getMethodReturnType(ownerClass, method);
		} else if (OptionalInt.class.isAssignableFrom(returnType)) {
			wrappedType = int.class;
		} else if (OptionalLong.class.isAssignableFrom(returnType)) {
			wrappedType = long.class;
		} else {
			wrappedType = null;
		}
		return wrappedType;
	}

	/**
	 * Returns multiple wrapped generic type of the method.
	 */
	public static Class<?>[] getResolvedTypes(
		@Nonnull Method method,
		@Nonnull Class<?> ownerClass
	) {
		final List<Class<?>> collectedTypes = new LinkedList<>();
		collectedTypes.add(method.getReturnType());

		List<GenericBundle> nestedMethodReturnTypes = getNestedMethodReturnTypes(ownerClass, method);
		while (!nestedMethodReturnTypes.isEmpty()) {
			collectedTypes.add(nestedMethodReturnTypes.get(0).getResolvedType());
			final GenericBundle[] genericTypes = nestedMethodReturnTypes.get(0).getGenericTypes();
			nestedMethodReturnTypes = ArrayUtils.isEmpty(genericTypes) ? Collections.emptyList() : Arrays.asList(genericTypes);
		}

		return collectedTypes.toArray(Class[]::new);
	}

	/**
	 * Creates a function that wraps the result into an Optional or primitive optional.
	 */
	@Nonnull
	public static UnaryOperator<Object> createOptionalWrapper(@Nullable Class<?> optionalType) {
		if (optionalType == null) {
			return value -> value;
		} else if (int.class == optionalType) {
			return value -> value == null ? OptionalInt.empty() : OptionalInt.of((Integer)value);
		} else if (long.class == optionalType) {
			return value -> value == null ? OptionalLong.empty() : OptionalLong.of((Long)value);
		} else {
			return Optional::ofNullable;
		}
	}

	public record ReturnTypeInfo(
		@Nonnull Function<Serializable, Object> resultWrapper,
		@Nullable Class<?> collectionType,
		@Nonnull Class<?> elementType
	) {
	}

}