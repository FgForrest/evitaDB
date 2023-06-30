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

package io.evitadb.api.proxy.impl.method;

import io.evitadb.api.exception.AssociatedDataNotFoundException;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.annotation.AssociatedData;
import io.evitadb.api.requestResponse.data.annotation.AssociatedDataRef;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.DirectMethodClassification;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetAssociatedDataMethodClassifier extends DirectMethodClassification<EntityClassifier, SealedEntityProxyState> {
	public static final GetAssociatedDataMethodClassifier INSTANCE = new GetAssociatedDataMethodClassifier();

	public GetAssociatedDataMethodClassifier() {
		super(
			"getAssociatedData",
			(method, proxyState) -> {
				if (method.getParameterCount() > 1 || (method.getParameterCount() == 1 && !method.getParameterTypes()[0].equals(Locale.class))) {
					return null;
				}
				final AssociatedDataSchemaContract associatedDataSchema = getAssociatedDataName(
					method, proxyState.getReflectionLookup(), proxyState.getEntitySchema()
				);
				if (associatedDataSchema == null) {
					return null;
				} else {
					final String cleanAssociatedDataName = associatedDataSchema.getName();
					@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
					//noinspection unchecked
					return method.getParameterCount() == 0 ?
						(entityClassifier, theMethod, args, theState, invokeSuper) -> theState.getAssociatedData(cleanAssociatedDataName, returnType) :
						(entityClassifier, theMethod, args, theState, invokeSuper) -> theState.getAssociatedData(cleanAssociatedDataName, (Locale) args[0], returnType);
				}
			}
		);
	}

	@Nullable
	private static AssociatedDataSchemaContract getAssociatedDataName(
		@Nonnull Method method,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EntitySchemaContract entitySchema
	) {
		final AssociatedData associatedDataInstance = reflectionLookup.getAnnotationInstance(method, AssociatedData.class);
		final AssociatedDataRef associatedDataRefInstance = reflectionLookup.getAnnotationInstance(method, AssociatedDataRef.class);
		if (associatedDataInstance != null) {
			return getAssociatedDataSchemaContractOrThrow(entitySchema, associatedDataInstance.name());
		} else if (associatedDataRefInstance != null) {
			return getAssociatedDataSchemaContractOrThrow(entitySchema, associatedDataRefInstance.value());
		} else if (!reflectionLookup.hasAnnotationInSamePackage(method, AssociatedData.class)) {
			return entitySchema.getAssociatedDataByName(
					ReflectionLookup.getPropertyNameFromMethodName(method.getName()),
					NamingConvention.CAMEL_CASE
				)
				.orElse(null);
		} else {
			return null;
		}
	}

	@Nonnull
	private static AssociatedDataSchemaContract getAssociatedDataSchemaContractOrThrow(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String associatedDataName
	) {
		final Optional<AssociatedDataSchemaContract> result;
		result = entitySchema.getAssociatedData(associatedDataName);
		return result
			.orElseThrow(
				() -> new AssociatedDataNotFoundException(associatedDataName, entitySchema)
			);
	}

}
