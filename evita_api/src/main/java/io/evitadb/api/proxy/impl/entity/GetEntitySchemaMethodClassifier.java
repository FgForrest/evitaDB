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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.proxy.WithEntitySchema;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.function.ExceptionRethrowingFunction;
import one.edee.oss.proxycian.DirectMethodClassification;
import one.edee.oss.proxycian.util.ReflectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;

/**
 * Identifies methods that are used to get entity schema from an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetEntitySchemaMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetEntitySchemaMethodClassifier INSTANCE = new GetEntitySchemaMethodClassifier();

	/**
	 * Tries to identify entity schema request from the class field related to the constructor parameter.
	 *
	 * @param parameter constructor parameter
	 * @return attribute name derived from the annotation if found
	 */
	@Nullable
	public static <T> ExceptionRethrowingFunction<EntityContract, Object> getExtractorIfPossible(
		@Nonnull Parameter parameter
	) {
		final Class<?> parameterType = parameter.getType();

		if (EntitySchemaContract.class.equals(parameterType)) {
			return EntityContract::getSchema;
		}
		return null;
	}

	public GetEntitySchemaMethodClassifier() {
		super(
			"getEntitySchema",
			(method, proxyState) -> {
				// We are interested only in abstract methods without arguments
				if (method.getParameterCount() > 0) {
					return null;
				}
				// we try to find appropriate annotations on the method, if no Evita annotation is found it tries
				// to match the method by its name
				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();

				if (ReflectionUtils.isMatchingMethodPresentOn(method, WithEntitySchema.class)) {
					return (entityClassifier, theMethod, args, theState, invokeSuper) -> theState.getEntitySchema();
				} else if (Modifier.isAbstract(method.getModifiers()) && EvitaSessionContract.class.equals(returnType)) {
					// method matches - provide implementation
					return (entityClassifier, theMethod, args, theState, invokeSuper) -> theState.getEntitySchema();
				}

				// this method is not classified by this implementation
				return null;
			}
		);
	}

}
