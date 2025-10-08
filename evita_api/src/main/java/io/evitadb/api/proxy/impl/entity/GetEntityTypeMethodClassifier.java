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

package io.evitadb.api.proxy.impl.entity;

import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.function.ExceptionRethrowingFunction;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.DirectMethodClassification;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Parameter;
import java.util.Optional;

/**
 * Identifies methods that are used to get entity type from an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetEntityTypeMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetEntityTypeMethodClassifier INSTANCE = new GetEntityTypeMethodClassifier();

	/**
	 * Tries to identify entity type request from the class field related to the constructor parameter.
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
		final Entity entity = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, Entity.class);
		final EntityRef entityRef = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, EntityRef.class);
		if (entity != null || entityRef != null ||
			(EntityRef.POSSIBLE_ARGUMENT_NAMES.contains(parameterName))) {
			if (String.class.isAssignableFrom(parameterType)) {
				return EntityClassifier::getType;
			} else if (parameterType.isEnum()) {
				//noinspection unchecked,rawtypes
				return (sealedEntity) -> Enum.valueOf((Class)parameterType, sealedEntity.getType());
			} else {
				return null;
			}
		} else {
			return null;
		}
	}

	public GetEntityTypeMethodClassifier() {
		super(
			"getEntityType",
			(method, proxyState) -> {
				// We are interested only in abstract methods without arguments
				if (method.getParameterCount() > 0) {
					return null;
				}
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				// we try to find appropriate annotations on the method, if no Evita annotation is found it tries
				// to match the method by its name
				final Entity entity = reflectionLookup.getAnnotationInstanceForProperty(method, Entity.class);
				final EntityRef entityRef = reflectionLookup.getAnnotationInstanceForProperty(method, EntityRef.class);
				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
				final Optional<String> propertyName = ReflectionLookup.getPropertyNameFromMethodNameIfPossible(method.getName());
				if (entity != null || entityRef != null || (
					!reflectionLookup.hasAnnotationForPropertyInSamePackage(method, Entity.class) &&
						(returnType.isEnum() || String.class.isAssignableFrom(returnType)) &&
						ClassUtils.isAbstract(method) &&
						propertyName
							.map(EntityRef.POSSIBLE_ARGUMENT_NAMES::contains)
							.orElse(false)
				)
				) {
					// method matches - provide implementation
					//noinspection unchecked
					return (entityClassifier, theMethod, args, theState, invokeSuper) -> EvitaDataTypes.toTargetType(
						theState.getType(), returnType
					);
				} else {
					// this method is not classified by this implementation
					return null;
				}
			}
		);
	}

}
