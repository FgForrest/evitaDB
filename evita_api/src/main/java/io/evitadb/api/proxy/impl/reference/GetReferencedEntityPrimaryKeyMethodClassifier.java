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

package io.evitadb.api.proxy.impl.reference;

import io.evitadb.api.proxy.impl.ProxyUtils;
import io.evitadb.api.proxy.impl.ProxyUtils.ResultWrapper;
import io.evitadb.api.proxy.impl.SealedEntityReferenceProxyState;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKeyRef;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.function.ExceptionRethrowingBiFunction;
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
 * Identifies methods that are used to get referenced entity primary key from an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetReferencedEntityPrimaryKeyMethodClassifier extends DirectMethodClassification<Object, SealedEntityReferenceProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetReferencedEntityPrimaryKeyMethodClassifier INSTANCE = new GetReferencedEntityPrimaryKeyMethodClassifier();

	/**
	 * Tries to identify referenced entity primary key request from the class field related to the constructor parameter.
	 *
	 * @param expectedType     class the constructor belongs to
	 * @param parameter        constructor parameter
	 * @param reflectionLookup reflection lookup
	 * @return attribute name derived from the annotation if found
	 */
	@Nullable
	public static <T> ExceptionRethrowingBiFunction<EntityContract, ReferenceContract, Object> getExtractorIfPossible(
		@Nonnull Class<T> expectedType,
		@Nonnull Parameter parameter,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		final String parameterName = parameter.getName();
		final Class<?> parameterType = parameter.getType();

		final ReferencedEntity referencedEntity = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, ReferencedEntity.class);
		if (Number.class.isAssignableFrom(toWrappedForm(parameterType)) && referencedEntity != null || (
			PrimaryKeyRef.POSSIBLE_ARGUMENT_NAMES.contains(parameterName))) {
			//noinspection unchecked,rawtypes
			return (sealedEntity, reference) -> toTargetType(reference.getReferencedPrimaryKey(), (Class) parameterType);
		} else {
			return null;
		}
	}

	public GetReferencedEntityPrimaryKeyMethodClassifier() {
		super(
			"getReferencedEntityPrimaryKey",
			(method, proxyState) -> {
				// we are interested only in abstract methods with no arguments.
				if (
					!ClassUtils.isAbstractOrDefault(method) ||
						method.getParameterCount() > 0 ||
						method.isAnnotationPresent(CreateWhenMissing.class) ||
						method.isAnnotationPresent(RemoveWhenExists.class)) {
					return null;
				}

				// we try to find appropriate annotations on the method, if no Evita annotation is found it tries
				// to match the method by its name
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final ReferencedEntity referencedEntity = reflectionLookup.getAnnotationInstanceForProperty(method, ReferencedEntity.class);
				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
				@SuppressWarnings("rawtypes") final Class wrappedGenericType = getWrappedGenericType(method, proxyState.getProxyClass());
				final ResultWrapper resultWrapper = ProxyUtils.createOptionalWrapper(method, wrappedGenericType);
				@SuppressWarnings("rawtypes") final Class valueType = wrappedGenericType == null ? returnType : wrappedGenericType;

				final Optional<String> propertyName = ReflectionLookup.getPropertyNameFromMethodNameIfPossible(method.getName());
				if (Number.class.isAssignableFrom(toWrappedForm(valueType)) && referencedEntity != null || (
					!reflectionLookup.hasAnnotationInSamePackage(method, ReferencedEntity.class) &&
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
							theState.getReference().getReferencedPrimaryKey(), valueType
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
