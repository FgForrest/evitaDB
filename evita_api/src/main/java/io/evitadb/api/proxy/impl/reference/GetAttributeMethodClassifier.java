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

package io.evitadb.api.proxy.impl.reference;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.proxy.impl.SealedEntityReferenceProxyState;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.DirectMethodClassification;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.evitadb.api.proxy.impl.entity.GetAttributeMethodClassifier.createDefaultValueProvider;

/**
 * Identifies methods that are used to get attributes from an reference and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetAttributeMethodClassifier extends DirectMethodClassification<Object, SealedEntityReferenceProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetAttributeMethodClassifier INSTANCE = new GetAttributeMethodClassifier();

	/**
	 * Retrieves appropriate attribute schema from the annotations on the method. If no Evita annotation is found
	 * it tries to match the attribute name by the name of the method.
	 */
	@Nullable
	private static AttributeSchemaContract getAttributeSchema(
		@Nonnull Method method,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		final Attribute attributeInstance = reflectionLookup.getAnnotationInstance(method, Attribute.class);
		final AttributeRef attributeRefInstance = reflectionLookup.getAnnotationInstance(method, AttributeRef.class);
		final Function<String, AttributeSchemaContract> schemaLocator = attributeName -> referenceSchema.getAttribute(attributeName).orElseThrow(
			() -> new AttributeNotFoundException(attributeName, referenceSchema, entitySchema)
		);
		if (attributeInstance != null) {
			return schemaLocator.apply(attributeInstance.name());
		} else if (attributeRefInstance != null) {
			return schemaLocator.apply(attributeRefInstance.value());
		} else if (!reflectionLookup.hasAnnotationInSamePackage(method, Attribute.class)) {
			final Optional<String> attributeName = ReflectionLookup.getPropertyNameFromMethodNameIfPossible(method.getName());
			return attributeName
				.flatMap(attrName -> referenceSchema.getAttributeByName(attrName, NamingConvention.CAMEL_CASE))
				.orElse(null);
		} else {
			return null;
		}
	}

	public GetAttributeMethodClassifier() {
		super(
			"getAttribute",
			(method, proxyState) -> {
				// We only want to handle non-abstract methods with no parameters or a single Locale parameter
				if (
					!ClassUtils.isAbstractOrDefault(method) ||
						method.getParameterCount() > 1 ||
						(method.getParameterCount() == 1 && !method.getParameterTypes()[0].equals(Locale.class))
				) {
					return null;
				}
				// now we need to identify attribute schema that is being requested
				final AttributeSchemaContract attributeSchema = getAttributeSchema(
					method, proxyState.getReflectionLookup(),
					proxyState.getEntitySchema(),
					proxyState.getReferenceSchema()
				);
				// if not found, this method is not classified by this implementation
				if (attributeSchema == null) {
					return null;
				} else {
					// finally provide implementation that will retrieve the attribute from the entity
					final String cleanAttributeName = attributeSchema.getName();
					final int indexedDecimalPlaces = attributeSchema.getIndexedDecimalPlaces();
					@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
					@SuppressWarnings("unchecked")
					final UnaryOperator<Serializable> defaultValueProvider = createDefaultValueProvider(attributeSchema, returnType);

					if (attributeSchema.isLocalized()) {
						//noinspection unchecked
						return method.getParameterCount() == 0 ?
							(entityClassifier, theMethod, args, theState, invokeSuper) -> EvitaDataTypes.toTargetType(
								defaultValueProvider.apply(theState.getReference().getAttribute(cleanAttributeName)),
								returnType, indexedDecimalPlaces
							) :
							(entityClassifier, theMethod, args, theState, invokeSuper) -> EvitaDataTypes.toTargetType(
								defaultValueProvider.apply(theState.getReference().getAttribute(cleanAttributeName, (Locale) args[0])),
								returnType, indexedDecimalPlaces
							);
					} else {
						Assert.isTrue(
							method.getParameterCount() == 0,
							"Non-localized attribute `" + attributeSchema.getName() + "` of reference `" + proxyState.getReferenceSchema().getName() + "` must not have a locale parameter!"
						);
						//noinspection unchecked
						return (entityClassifier, theMethod, args, theState, invokeSuper) -> EvitaDataTypes.toTargetType(
							defaultValueProvider.apply(theState.getReference().getAttribute(cleanAttributeName)),
							returnType, indexedDecimalPlaces
						);
					}
				}
			}
		);
	}

}
