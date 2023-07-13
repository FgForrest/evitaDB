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

package io.evitadb.api.proxy.impl.entity;

import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.annotation.AssociatedData;
import io.evitadb.api.requestResponse.data.annotation.AssociatedDataRef;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.DirectMethodClassification;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Identifies methods that are used to get associated data from an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetAssociatedDataMethodClassifier extends DirectMethodClassification<EntityClassifier, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetAssociatedDataMethodClassifier INSTANCE = new GetAssociatedDataMethodClassifier();

	/**
	 * Provides default value (according to a schema or implicit rules) instead of null.
	 */
	@Nonnull
	public static UnaryOperator<Serializable> createDefaultValueProvider(Class<? extends Serializable> returnType) {
		final UnaryOperator<Serializable> defaultValueProvider;
		if (boolean.class.equals(returnType)) {
			defaultValueProvider = o -> o == null ? false : o;
		} else {
			defaultValueProvider = UnaryOperator.identity();
		}
		return defaultValueProvider;
	}

	/**
	 * Retrieves appropriate associated data schema from the annotations on the method. If no Evita annotation is found
	 * it tries to match the associated data name by the name of the method.
	 */
	@Nullable
	private static AssociatedDataSchemaContract getAssociatedDataSchema(
		@Nonnull Method method,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EntitySchemaContract entitySchema
	) {
		final AssociatedData associatedDataInstance = reflectionLookup.getAnnotationInstance(method, AssociatedData.class);
		final AssociatedDataRef associatedDataRefInstance = reflectionLookup.getAnnotationInstance(method, AssociatedDataRef.class);
		if (associatedDataInstance != null) {
			return entitySchema.getAssociatedDataOrThrowException(associatedDataInstance.name());
		} else if (associatedDataRefInstance != null) {
			return entitySchema.getAssociatedDataOrThrowException(associatedDataRefInstance.value());
		} else if (!reflectionLookup.hasAnnotationInSamePackage(method, AssociatedData.class)) {
			return ReflectionLookup.getPropertyNameFromMethodNameIfPossible(method.getName())
				.flatMap(
					associatedDataName -> entitySchema.getAssociatedDataByName(
						associatedDataName,
						NamingConvention.CAMEL_CASE
					)
				)
				.orElse(null);
		} else {
			return null;
		}
	}

	public GetAssociatedDataMethodClassifier() {
		super(
			"getAssociatedData",
			(method, proxyState) -> {
				// we only want to handle methods that are not abstract and have at most one parameter of type Locale.
				if (!ClassUtils.isAbstractOrDefault(method) ||
					method.getParameterCount() > 1 ||
					(method.getParameterCount() == 1 && !method.getParameterTypes()[0].equals(Locale.class))
				) {
					return null;
				}
				// now we need to identify associated data schema that is being requested
				final AssociatedDataSchemaContract associatedDataSchema = getAssociatedDataSchema(
					method, proxyState.getReflectionLookup(), proxyState.getEntitySchema()
				);
				// if not found, this method is not classified by this implementation
				if (associatedDataSchema == null) {
					return null;
				} else {
					// finally provide implementation that will retrieve the associated data from the entity
					final String cleanAssociatedDataName = associatedDataSchema.getName();
					@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
					@SuppressWarnings("unchecked")
					final UnaryOperator<Serializable> defaultValueProvider = createDefaultValueProvider(returnType);
					//noinspection unchecked
					return method.getParameterCount() == 0 ?
						(entityClassifier, theMethod, args, theState, invokeSuper) -> theState.getSealedEntity().getAssociatedDataValue(cleanAssociatedDataName)
							.map(AssociatedDataValue::value)
							.map(defaultValueProvider)
							.map(it -> ComplexDataObjectConverter.getOriginalForm(it, returnType, theState.getReflectionLookup()))
							.orElse(null) :
						(entityClassifier, theMethod, args, theState, invokeSuper) -> theState.getSealedEntity().getAssociatedDataValue(cleanAssociatedDataName, (Locale) args[0])
							.map(AssociatedDataValue::value)
							.map(defaultValueProvider)
							.map(it -> ComplexDataObjectConverter.getOriginalForm(it, returnType, theState.getReflectionLookup()))
							.orElse(null);
				}
			}
		);
	}

}
