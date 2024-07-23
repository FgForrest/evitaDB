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

import io.evitadb.api.proxy.impl.ProxyUtils;
import io.evitadb.api.proxy.impl.ProxyUtils.ResultWrapper;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKeyRef;
import io.evitadb.function.ExceptionRethrowingFunction;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.DirectMethodClassification;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Parameter;
import java.util.Optional;

import static io.evitadb.api.proxy.impl.ProxyUtils.getWrappedGenericType;
import static io.evitadb.dataType.EvitaDataTypes.toTargetType;
import static io.evitadb.dataType.EvitaDataTypes.toWrappedForm;

/**
 * Identifies methods that are used to get entity primary key from an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetPrimaryKeyMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetPrimaryKeyMethodClassifier INSTANCE = new GetPrimaryKeyMethodClassifier();

	/**
	 * Tries to identify primary key request from the class field related to the constructor parameter.
	 *
	 * @param expectedType class the constructor belongs to
	 * @param parameter constructor parameter
	 * @param reflectionLookup reflection lookup
	 * @return attribute name derived from the annotation if found
	 */
	@Nullable
	public static <T> ExceptionRethrowingFunction<EntityContract, Object> getExtractorIfPossible(
		@Nonnull Class<T> expectedType,
		@Nonnull Parameter parameter,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		final String parameterName = parameter.getName();
		final Class<?> parameterType = parameter.getType();

		final PrimaryKey primaryKey = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, PrimaryKey.class);
		final PrimaryKeyRef primaryKeyRef = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, PrimaryKeyRef.class);

		if (primaryKey != null ||
			primaryKeyRef != null ||
			(PrimaryKeyRef.POSSIBLE_ARGUMENT_NAMES.contains(parameterName) &&
				(Integer.class.isAssignableFrom(parameterType) ||
					int.class.isAssignableFrom(parameterType)))) {
			return EntityContract::getPrimaryKey;
		} else {
			return null;
		}
	}

	public GetPrimaryKeyMethodClassifier() {
		super(
			"getPrimaryKey",
			(method, proxyState) -> {
				// We are interested only in abstract methods without arguments
				if (method.getParameterCount() > 0) {
					return null;
				}
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				// we try to find appropriate annotations on the method, if no Evita annotation is found it tries
				// to match the method by its name
				final PrimaryKey primaryKey = reflectionLookup.getAnnotationInstanceForProperty(method, PrimaryKey.class);
				final PrimaryKeyRef primaryKeyRef = reflectionLookup.getAnnotationInstanceForProperty(method, PrimaryKeyRef.class);
				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
				@SuppressWarnings("rawtypes") final Class wrappedGenericType = getWrappedGenericType(method, proxyState.getProxyClass());
				final ResultWrapper resultWrapper = ProxyUtils.createOptionalWrapper(method, wrappedGenericType);
				@SuppressWarnings("rawtypes") final Class valueType = wrappedGenericType == null ? returnType : wrappedGenericType;

				final Optional<String> propertyName = ReflectionLookup.getPropertyNameFromMethodNameIfPossible(method.getName());
				if (primaryKey != null || primaryKeyRef != null || (
					!reflectionLookup.hasAnnotationForPropertyInSamePackage(method, PrimaryKey.class) &&
						Number.class.isAssignableFrom(toWrappedForm(valueType)) &&
						ClassUtils.isAbstract(method) &&
						propertyName
							.map(PrimaryKeyRef.POSSIBLE_ARGUMENT_NAMES::contains)
							.orElse(false)
				)
				) {
					// method matches - provide implementation
					//noinspection unchecked
					return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
						() -> toTargetType(
							theState.getPrimaryKey(), valueType
						)
					);
				} else {
					// this method is not classified by this implementation
					return null;
				}
			}
		);
	}

}
