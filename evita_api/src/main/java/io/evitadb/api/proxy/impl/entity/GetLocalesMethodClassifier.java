/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.proxy.impl.entity;

import io.evitadb.api.proxy.WithLocales;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.function.ExceptionRethrowingFunction;
import one.edee.oss.proxycian.DirectMethodClassification;
import one.edee.oss.proxycian.util.ReflectionUtils;
import one.edee.oss.proxycian.utils.GenericsUtils;
import one.edee.oss.proxycian.utils.GenericsUtils.GenericBundle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static one.edee.oss.proxycian.utils.GenericsUtils.getMethodReturnType;

/**
 * Identifies methods that are used to get entity locales from an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetLocalesMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetLocalesMethodClassifier INSTANCE = new GetLocalesMethodClassifier();
	private static final Locale[] EMPTY_LOCALE_ARRAY = new Locale[0];

	/**
	 * Tries to identify locale request from the class field related to the constructor parameter.
	 *
	 * @param expectedType class the constructor belongs to
	 * @param parameter constructor parameter
	 * @return attribute name derived from the annotation if found
	 */
	@Nullable
	public static <T> ExceptionRethrowingFunction<EntityContract, Object> getExtractorIfPossible(
		@Nonnull Class<T> expectedType,
		@Nonnull Parameter parameter
	) {
		final Class<?> parameterType = parameter.getType();

		if (Collection.class.equals(parameterType) || Set.class.equals(parameterType)) {

			@SuppressWarnings("rawtypes") final Class wrappedGenericType = resolveParameterType(expectedType, parameter);
			if (Locale.class.equals(wrappedGenericType)) {
				// method matches - provide implementation
				return EntityContract::getLocales;
			}
		} else if (List.class.equals(parameterType)) {
			@SuppressWarnings("rawtypes") final Class wrappedGenericType = resolveParameterType(expectedType, parameter);
			if (Locale.class.equals(wrappedGenericType)) {
				// method matches - provide implementation
				return entity -> new ArrayList<>(entity.getLocales());
			}
		} else if (parameterType.isArray() && Locale.class.equals(parameterType.getComponentType())) {
			return entity -> entity.getLocales().toArray(EMPTY_LOCALE_ARRAY);
		}
		return null;
	}

	/**
	 * Method resolves possible generic type of the parameter.
	 * @param expectedType class the parameter belongs to
	 * @param parameter parameter
	 * @return resolved generic type of the parameter
	 */
	@Nullable
	private static Class<?> resolveParameterType(@Nonnull Class<?> expectedType, @Nonnull Parameter parameter) {
		final List<GenericBundle> resolvedTypes = GenericsUtils.getGenericType(expectedType, parameter.getParameterizedType());
		if (!resolvedTypes.isEmpty()) {
			return resolvedTypes.get(0).getResolvedType();
		}
		return null;
	}

	public GetLocalesMethodClassifier() {
		super(
			"getLocales",
			(method, proxyState) -> {
				// We are interested only in abstract methods without arguments
				if (method.getParameterCount() > 0) {
					return null;
				}
				// we try to find appropriate annotations on the method, if no Evita annotation is found it tries
				// to match the method by its name
				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();

				if (ReflectionUtils.isMatchingMethodPresentOn(method, WithLocales.class)) {
					if ("allLocales".equals(method.getName())) {
						return (entityClassifier, theMethod, args, theState, invokeSuper) -> theState.entity().getAllLocales();
					} else if ("locales".equals(method.getName())) {
						return (entityClassifier, theMethod, args, theState, invokeSuper) -> theState.entity().getLocales();
					}
				} else if (Modifier.isAbstract(method.getModifiers())) {
					if (Collection.class.equals(returnType) || Set.class.equals(returnType)) {
						@SuppressWarnings("rawtypes") final Class wrappedGenericType = getMethodReturnType(proxyState.getProxyClass(), method);
						if (Locale.class.equals(wrappedGenericType)) {
							// method matches - provide implementation
							return (entityClassifier, theMethod, args, theState, invokeSuper) -> theState.entity().getLocales();
						}
					} else if (List.class.equals(returnType)) {
						@SuppressWarnings("rawtypes") final Class wrappedGenericType = getMethodReturnType(proxyState.getProxyClass(), method);
						if (Locale.class.equals(wrappedGenericType)) {
							// method matches - provide implementation
							return (entityClassifier, theMethod, args, theState, invokeSuper) -> new ArrayList<>(theState.entity().getLocales());
						}
					} else if (returnType.isArray() && Locale.class.equals(returnType.getComponentType())) {
						// method matches - provide implementation
						return (entityClassifier, theMethod, args, theState, invokeSuper) -> theState.entity().getLocales().toArray(EMPTY_LOCALE_ARRAY);
					}
				}

				// this method is not classified by this implementation
				return null;
			}
		);
	}

}
