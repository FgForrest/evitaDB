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

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.DirectMethodClassification;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetAttributeMethodClassifier extends DirectMethodClassification<EntityClassifier, SealedEntityProxyState> {
	public static final GetAttributeMethodClassifier INSTANCE = new GetAttributeMethodClassifier();

	@Nullable
	private static AttributeSchemaContract getAttributeSchema(
		@Nonnull Method method,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		final Attribute attributeInstance = reflectionLookup.getAnnotationInstance(method, Attribute.class);
		final AttributeRef attributeRefInstance = reflectionLookup.getAnnotationInstance(method, AttributeRef.class);
		final Optional<AttributeSchemaContract> result;
		if (attributeInstance != null) {
			return getAttributeSchemaContractOrThrow(entitySchema, referenceSchema, attributeInstance.name());
		} else if (attributeRefInstance != null) {
			return getAttributeSchemaContractOrThrow(entitySchema, referenceSchema, attributeRefInstance.value());
		} else if (!reflectionLookup.hasAnnotationInSamePackage(method, Attribute.class)) {
			final Optional<String> attributeName = ReflectionLookup.getPropertyNameFromMethodNameIfPossible(method.getName());
			return attributeName
				.flatMap(
					attrName -> ofNullable(referenceSchema)
						.map(it -> it.getAttributeByName(attrName, NamingConvention.CAMEL_CASE))
						.orElseGet(() -> entitySchema.getAttributeByName(attrName, NamingConvention.CAMEL_CASE))
				)
				.orElse(null);
		} else {
			return null;
		}
	}

	@Nonnull
	private static AttributeSchemaContract getAttributeSchemaContractOrThrow(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull String attributeName
	) {
		final Optional<AttributeSchemaContract> result = ofNullable(referenceSchema)
			.map(it -> it.getAttribute(attributeName))
			.orElseGet(() -> entitySchema.getAttribute(attributeName));
		return result
			.orElseThrow(
				() -> referenceSchema == null ?
					new AttributeNotFoundException(attributeName, entitySchema) :
					new AttributeNotFoundException(attributeName, referenceSchema, entitySchema)
			);
	}

	public GetAttributeMethodClassifier() {
		super(
			"getAttribute",
			(method, proxyState) -> {
				if (method.getParameterCount() > 1 || (method.getParameterCount() == 1 && !method.getParameterTypes()[0].equals(Locale.class))) {
					return null;
				}
				final AttributeSchemaContract attributeSchema = getAttributeSchema(
					method, proxyState.getReflectionLookup(),
					proxyState.getEntitySchema(),
					proxyState.getReferenceSchema()
				);
				if (attributeSchema == null) {
					return null;
				} else {
					final String cleanAttributeName = attributeSchema.getName();
					final int indexedDecimalPlaces = attributeSchema.getIndexedDecimalPlaces();
					@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
					if (attributeSchema.isLocalized()) {
						//noinspection unchecked
						return method.getParameterCount() == 0 ?
							(entityClassifier, theMethod, args, theState, invokeSuper) -> EvitaDataTypes.toTargetType(
								theState.getAttribute(cleanAttributeName), returnType, indexedDecimalPlaces
							) :
							(entityClassifier, theMethod, args, theState, invokeSuper) -> EvitaDataTypes.toTargetType(
								theState.getAttribute(cleanAttributeName, (Locale) args[0]), returnType, indexedDecimalPlaces
							);
					} else {
						Assert.isTrue(
							method.getParameterCount() == 0,
							"Non-localized attribute `" + attributeSchema.getName() + "` must not have a locale parameter!"
						);
						//noinspection unchecked
						return (entityClassifier, theMethod, args, theState, invokeSuper) -> EvitaDataTypes.toTargetType(
							theState.getAttribute(cleanAttributeName), returnType, indexedDecimalPlaces
						);
					}
				}
			}
		);
	}

}
