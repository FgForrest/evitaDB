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

package io.evitadb.api.proxy.impl.reference;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.proxy.impl.ProxyUtils;
import io.evitadb.api.proxy.impl.ProxyUtils.OptionalProducingOperator;
import io.evitadb.api.proxy.impl.ProxyUtils.ResultWrapper;
import io.evitadb.api.proxy.impl.SealedEntityReferenceProxyState;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.function.ExceptionRethrowingBiFunction;
import io.evitadb.function.TriFunction;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.evitadb.api.proxy.impl.ProxyUtils.getResolvedTypes;
import static io.evitadb.api.proxy.impl.entity.GetAttributeMethodClassifier.createDefaultValueProvider;
import static io.evitadb.dataType.EvitaDataTypes.toTargetType;
import static java.util.Optional.ofNullable;

/**
 * Identifies methods that are used to get attributes from an reference and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetReferenceAttributeMethodClassifier extends DirectMethodClassification<Object, SealedEntityReferenceProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetReferenceAttributeMethodClassifier INSTANCE = new GetReferenceAttributeMethodClassifier();

	/**
	 * Tries to identify reference attribute request from the class field related to the constructor parameter.
	 *
	 * @param expectedType     class the constructor belongs to
	 * @param parameter        constructor parameter
	 * @param reflectionLookup reflection lookup
	 * @param entitySchema     entity schema
	 * @param referenceSchema  reference schema
	 * @return attribute name derived from the annotation if found
	 */
	@Nullable
	public static <T> ExceptionRethrowingBiFunction<EntityContract, ReferenceContract, Object> getExtractorIfPossible(
		@Nonnull Class<T> expectedType,
		@Nonnull Parameter parameter,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		// now we need to identify attribute schema that is being requested
		final AttributeSchemaContract attributeSchema = getAttributeSchema(
			expectedType, parameter, reflectionLookup, entitySchema, referenceSchema
		);
		// if not found, this method is not classified by this implementation
		if (attributeSchema == null) {
			return null;
		} else {
			// finally provide implementation that will retrieve the attribute from the entity
			final String cleanAttributeName = attributeSchema.getName();
			final int indexedDecimalPlaces = attributeSchema.getIndexedDecimalPlaces();

			// now we need to identify the return type
			@SuppressWarnings("rawtypes") final Class parameterType = parameter.getType();
			final Class<?>[] resolvedTypes = getResolvedTypes(parameter, expectedType);
			final UnaryOperator<Serializable> defaultValueProvider = createDefaultValueProvider(attributeSchema, parameterType);

			@SuppressWarnings("rawtypes") final Class collectionType;
			@SuppressWarnings("rawtypes") final Class itemType;
			if (Collection.class.equals(resolvedTypes[0]) || List.class.isAssignableFrom(resolvedTypes[0]) || Set.class.isAssignableFrom(resolvedTypes[0])) {
				collectionType = resolvedTypes[0];
				itemType = Array.newInstance(resolvedTypes.length > 1 ? resolvedTypes[1] : Object.class, 0).getClass();
			} else if (resolvedTypes[0].isArray()) {
				collectionType = null;
				itemType = resolvedTypes[0];
			} else {
				collectionType = null;
				itemType = resolvedTypes[0];
			}

			if (attributeSchema.isLocalized()) {
				if (collectionType != null && Set.class.isAssignableFrom(collectionType)) {
					//noinspection unchecked
					return setOfLocalizedResults(
						cleanAttributeName, itemType, indexedDecimalPlaces, defaultValueProvider
					);
				} else if (collectionType != null) {
					//noinspection unchecked
					return listOfLocalizedResults(
						cleanAttributeName, itemType, indexedDecimalPlaces, defaultValueProvider
					);
				} else {
					//noinspection unchecked
					return singleLocalizedResult(
						cleanAttributeName, itemType, indexedDecimalPlaces, defaultValueProvider
					);
				}
			} else {
				if (collectionType != null && Set.class.isAssignableFrom(collectionType)) {
					//noinspection unchecked
					return setOfResults(
						cleanAttributeName, itemType, indexedDecimalPlaces, defaultValueProvider
					);
				} else if (collectionType != null) {
					//noinspection unchecked
					return listOfResults(
						cleanAttributeName, itemType, indexedDecimalPlaces, defaultValueProvider
					);
				} else {
					//noinspection unchecked
					return singleResult(
						cleanAttributeName, itemType, indexedDecimalPlaces, defaultValueProvider
					);
				}
			}
		}
	}

	/**
	 * Retrieves appropriate attribute schema from the annotations on the method. If no Evita annotation is found
	 * it tries to match the attribute name by the name of the method.
	 */
	@Nullable
	public static AttributeSchemaContract getAttributeSchema(
		@Nonnull Method method,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		final Attribute attributeInstance = reflectionLookup.getAnnotationInstanceForProperty(method, Attribute.class);
		final AttributeRef attributeRefInstance = reflectionLookup.getAnnotationInstanceForProperty(method, AttributeRef.class);
		final Function<String, AttributeSchemaContract> schemaLocator =
			attributeName -> ofNullable(referenceSchema)
				.flatMap(it -> it.getAttribute(attributeName))
				.orElseThrow(
					() -> referenceSchema == null ?
						new AttributeNotFoundException(attributeName, entitySchema) :
						new AttributeNotFoundException(attributeName, referenceSchema, entitySchema)
				);
		if (attributeInstance != null) {
			return schemaLocator.apply(
				ofNullable(attributeInstance.name())
					.filter(it -> !it.isBlank())
					.orElseGet(() -> ReflectionLookup.getPropertyNameFromMethodName(method.getName()))
			);
		} else if (attributeRefInstance != null) {
			return schemaLocator.apply(
				ofNullable(attributeRefInstance.value())
					.filter(it -> !it.isBlank())
					.orElseGet(() -> ReflectionLookup.getPropertyNameFromMethodName(method.getName()))
			);
		} else if (referenceSchema != null && !reflectionLookup.hasAnnotationInSamePackage(method, Attribute.class) && ClassUtils.isAbstract(method)) {
			final Optional<String> attributeName = ReflectionLookup.getPropertyNameFromMethodNameIfPossible(method.getName());
			return attributeName
				.flatMap(attrName -> referenceSchema.getAttributeByName(attrName, NamingConvention.CAMEL_CASE))
				.orElse(null);
		} else {
			return null;
		}
	}

	/**
	 * Retrieves appropriate attribute schema from the annotations on the method. If no Evita annotation is found
	 * it tries to match the attribute name by the name of the method.
	 */
	@Nullable
	private static AttributeSchemaContract getAttributeSchema(
		@Nonnull Class<?> expectedClass,
		@Nonnull Parameter parameter,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		final String parameterName = parameter.getName();
		final Attribute attributeInstance = reflectionLookup.getAnnotationInstanceForProperty(expectedClass, parameterName, Attribute.class);
		final AttributeRef attributeRefInstance = reflectionLookup.getAnnotationInstanceForProperty(expectedClass, parameterName, AttributeRef.class);
		final Function<String, AttributeSchemaContract> schemaLocator =
			attributeName -> ofNullable(referenceSchema)
				.flatMap(it -> it.getAttribute(attributeName))
				.orElseThrow(() -> referenceSchema == null ?
					new AttributeNotFoundException(attributeName, entitySchema) :
					new AttributeNotFoundException(attributeName, referenceSchema, entitySchema)
				);
		if (attributeInstance != null) {
			return schemaLocator.apply(attributeInstance.name());
		} else if (attributeRefInstance != null) {
			return schemaLocator.apply(
				attributeRefInstance.value().isBlank() ? parameterName : attributeRefInstance.value()
			);
		} else if (referenceSchema != null) {
			return referenceSchema.getAttributeByName(parameterName, NamingConvention.CAMEL_CASE)
				.orElse(null);
		} else {
			return null;
		}
	}

	/**
	 * Creates an implementation of the method returning an attribute as a requested type.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> singleResult(
		@Nonnull String attributeName,
		@Nonnull Method method,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull BiFunction<ReferenceContract, String, Serializable> attributeExtractor,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull ResultWrapper resultWrapper
	) {
		Assert.isTrue(
			method.getParameterCount() == 0,
			"Non-localized attribute `" + attributeName + "` must not have a locale parameter!"
		);
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
			() -> toTargetType(
				defaultValueProvider.apply(attributeExtractor.apply(theState.getReference(), attributeName)),
				itemType, indexedDecimalPlaces
			)
		);
	}

	/**
	 * Creates an implementation of the method returning an attribute as a requested type.
	 */
	@Nonnull
	private static ExceptionRethrowingBiFunction<SealedEntity, ReferenceContract, Object> singleResult(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		return (sealedEntity, reference) -> toTargetType(
			defaultValueProvider.apply(reference.getAttribute(attributeName)),
			itemType, indexedDecimalPlaces
		);
	}

	/**
	 * Creates an implementation of the method returning an attribute of an array type wrapped into a set.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setOfResults(
		@Nonnull String attributeName,
		@Nonnull Method method,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull BiFunction<ReferenceContract, String, Serializable> attributeExtractor,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull ResultWrapper resultWrapper
	) {
		Assert.isTrue(
			method.getParameterCount() == 0,
			"Non-localized attribute `" + attributeName + "` must not have a locale parameter!"
		);
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			extractAndWrapAttributes(attributeName, attributeExtractor, itemType, indexedDecimalPlaces, defaultValueProvider, resultWrapper, theState);
	}

	/**
	 * Extracts attributes using the provided attribute extractor function and wraps them with the result wrapper.
	 * If the attributes are multi-valued, they are converted into a set. If no value is found, it defaults to an empty set.
	 *
	 * @param attributeName        the name of the attribute to extract
	 * @param attributeExtractor   a bi-function to extract the attribute value based on the entity reference and attribute name
	 * @param itemType             the expected type of the extracted attribute
	 * @param indexedDecimalPlaces the number of decimal places for indexing numeric values if applicable
	 * @param defaultValueProvider a function to provide a default value when the attribute extraction returns null
	 * @param resultWrapper        the wrapper to process and wrap the result
	 * @param theState             the state containing the reference entity to extract attributes from
	 * @return a wrapped set of extracted attribute values or an empty set if no values are found
	 */
	@Nullable
	private static Object extractAndWrapAttributes(
		@Nonnull String attributeName,
		@Nonnull BiFunction<ReferenceContract, String, Serializable> attributeExtractor,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull ResultWrapper resultWrapper,
		@Nonnull SealedEntityReferenceProxyState theState
	) {
		return resultWrapper.wrap(
			() -> ofNullable(
				toTargetType(
					defaultValueProvider.apply(attributeExtractor.apply(theState.getReference(), attributeName)),
					itemType, indexedDecimalPlaces
				)
			)
				.map(Object[].class::cast)
				.map(it -> {
					final Set<Object> result = CollectionUtils.createHashSet(it.length);
					result.addAll(Arrays.asList(it));
					return result;
				})
				.orElse(Collections.emptySet())
		);
	}

	/**
	 * Creates an implementation of the method returning an attribute of an array type wrapped into a set.
	 */
	@Nonnull
	private static ExceptionRethrowingBiFunction<SealedEntity, ReferenceContract, Object> setOfResults(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		return (sealedEntity, reference) ->
			ofNullable(
				toTargetType(
					defaultValueProvider.apply(reference.getAttribute(attributeName)),
					itemType, indexedDecimalPlaces
				)
			)
				.map(Object[].class::cast)
				.map(it -> {
					final Set<Object> result = CollectionUtils.createHashSet(it.length);
					result.addAll(Arrays.asList(it));
					return result;
				})
				.orElse(Collections.emptySet());
	}

	/**
	 * Creates an implementation of the method returning an attribute of an array type wrapped into a list.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> listOfResults(
		@Nonnull String attributeName,
		@Nonnull Method method,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull BiFunction<ReferenceContract, String, Serializable> attributeExtractor,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull ResultWrapper resultWrapper
	) {
		Assert.isTrue(
			method.getParameterCount() == 0,
			"Non-localized attribute `" + attributeName + "` must not have a locale parameter!"
		);
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
			() -> ofNullable(
				toTargetType(
					defaultValueProvider.apply(attributeExtractor.apply(theState.getReference(), attributeName)),
					itemType, indexedDecimalPlaces
				)
			)
				.map(it -> List.of((Object[]) it))
				.orElse(Collections.emptyList())
		);
	}

	/**
	 * Creates an implementation of the method returning an attribute of an array type wrapped into a list.
	 */
	@Nonnull
	private static ExceptionRethrowingBiFunction<SealedEntity, ReferenceContract, Object> listOfResults(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		return (sealedEntity, reference) ->
			ofNullable(
				toTargetType(
					defaultValueProvider.apply(reference.getAttribute(attributeName)),
					itemType, indexedDecimalPlaces
				)
			)
				.map(Object[].class::cast)
				.map(List::of)
				.orElse(Collections.emptyList());
	}

	/**
	 * Creates an implementation of the method returning an attribute as a requested type.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> singleLocalizedResult(
		@Nonnull String attributeName,
		@Nonnull Method method,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull BiFunction<ReferenceContract, String, Serializable> attributeExtractor,
		@Nonnull TriFunction<ReferenceContract, String, Locale, Serializable> localizedAttributeExtractor,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull ResultWrapper resultWrapper
	) {
		return method.getParameterCount() == 0 ?
			(entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
				() -> toTargetType(
					defaultValueProvider.apply(attributeExtractor.apply(theState.getReference(), attributeName)),
					itemType, indexedDecimalPlaces
				)
			) :
			(entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
				() -> toTargetType(
					defaultValueProvider.apply(
						localizedAttributeExtractor.apply(theState.getReference(), attributeName, (Locale) args[0])
					),
					itemType, indexedDecimalPlaces
				)
			);
	}

	/**
	 * Creates an implementation of the method returning an attribute as a requested type.
	 */
	@Nonnull
	private static ExceptionRethrowingBiFunction<SealedEntity, ReferenceContract, Object> singleLocalizedResult(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		return (sealedEntity, reference) -> {
			final LocaleSerializablePredicate localePredicate = ((EntityDecorator) sealedEntity).getLocalePredicate();
			final Set<Locale> locales = localePredicate.getLocales();
			final Locale locale = locales != null && locales.size() == 1 ? locales.iterator().next() : localePredicate.getImplicitLocale();
			@SuppressWarnings({"unchecked", "rawtypes"}) final Class requestedType = ofNullable(itemType.getComponentType())
				.orElse((Class) itemType);

			if (locale == null && locales != null && locales.isEmpty()) {
				if (itemType.isArray()) {
					return sealedEntity.getAttributeLocales()
						.stream()
						.map(it -> {
							// noinspection rawtypes,DataFlowIssue,unchecked
							return toTargetType(
								defaultValueProvider.apply(
									reference.getAttribute(
										attributeName,
										locale,
										(Class) itemType.getComponentType()
									)
								),
								requestedType,
								indexedDecimalPlaces
							);
						})
						.toArray(count -> (Serializable[]) Array.newInstance(itemType.getComponentType(), count));
				} else {
					throw new EvitaInvalidUsageException(
						"Cannot initialize reference `" + reference.getReferenceName() + "` attribute `" + attributeName + "` " +
							"in a constructor as a single value since " +
							"it could localized to multiple locales and no locale was requested when fetching the entity!"
					);
				}
			} else if (locale != null) {
				// noinspection unchecked
				return toTargetType(
					reference.getAttribute(
						attributeName,
						locale,
						requestedType
					),
					requestedType,
					indexedDecimalPlaces
				);
			} else {
				// noinspection unchecked
				return defaultValueProvider.apply(
					toTargetType(
						defaultValueProvider.apply(reference.getAttribute(attributeName)),
						requestedType,
						indexedDecimalPlaces
					)
				);
			}
		};
	}

	/**
	 * Creates an implementation of the method returning an attribute of an array type wrapped into a set.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setOfLocalizedResults(
		@Nonnull String cleanAttributeName,
		@Nonnull Method method,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull BiFunction<ReferenceContract, String, Serializable> attributeExtractor,
		@Nonnull TriFunction<ReferenceContract, String, Locale, Serializable> localizedAttributeExtractor,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull ResultWrapper resultWrapper
	) {
		return method.getParameterCount() == 0 ?
			(entityClassifier, theMethod, args, theState, invokeSuper) -> extractAndWrapAttributes(cleanAttributeName, attributeExtractor, itemType, indexedDecimalPlaces, defaultValueProvider, resultWrapper, theState) :
			(entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
				() -> ofNullable(
					toTargetType(
						defaultValueProvider.apply(
							localizedAttributeExtractor.apply(
								theState.getReference(), cleanAttributeName, (Locale) args[0]
							)
						),
						itemType, indexedDecimalPlaces
					)
				)
					.map(Object[].class::cast)
					.map(it -> {
						final Set<Object> result = CollectionUtils.createHashSet(it.length);
						result.addAll(Arrays.asList(it));
						return result;
					})
					.orElse(Collections.emptySet())
			);
	}

	/**
	 * Creates an implementation of the method returning an attribute of an array type wrapped into a set.
	 */
	@Nonnull
	private static ExceptionRethrowingBiFunction<SealedEntity, ReferenceContract, Object> setOfLocalizedResults(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		return (sealedEntity, reference) -> getLocalizedAttributeAsObjectArray(
			attributeName, itemType, indexedDecimalPlaces, defaultValueProvider, sealedEntity, reference
		).map(
			it -> {
				final Set<Object> result = CollectionUtils.createHashSet(it.length);
				result.addAll(Arrays.asList(it));
				return result;
			}
		).orElseGet(Collections::emptySet);
	}

	/**
	 * Creates an implementation of the method returning an attribute of an array type wrapped into a list.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> listOfLocalizedResults(
		@Nonnull String attributeName,
		@Nonnull Method method,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull BiFunction<ReferenceContract, String, Serializable> attributeExtractor,
		@Nonnull TriFunction<ReferenceContract, String, Locale, Serializable> localizedAttributeExtractor,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull ResultWrapper resultWrapper
	) {
		return method.getParameterCount() == 0 ?
			(entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
				() -> ofNullable(
					toTargetType(
						defaultValueProvider.apply(attributeExtractor.apply(theState.getReference(), attributeName)),
						itemType, indexedDecimalPlaces
					)
				)
					.map(it -> List.of((Object[]) it))
					.orElse(Collections.emptyList())
			) :
			(entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
				() -> ofNullable(
					toTargetType(
						defaultValueProvider.apply(
							localizedAttributeExtractor.apply(
								theState.getReference(), attributeName, (Locale) args[0]
							)
						),
						itemType, indexedDecimalPlaces
					)
				)
					.map(it -> List.of((Object[]) it))
					.orElse(Collections.emptyList())
			);
	}

	/**
	 * Creates an implementation of the method returning an attribute of an array type wrapped into a set.
	 */
	@Nonnull
	private static ExceptionRethrowingBiFunction<SealedEntity, ReferenceContract, Object> listOfLocalizedResults(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		return (sealedEntity, reference) ->
			getLocalizedAttributeAsObjectArray(
				attributeName, itemType, indexedDecimalPlaces, defaultValueProvider, sealedEntity, reference
			)
				.map(List::of)
				.orElseGet(Collections::emptyList);
	}

	/**
	 * Resolves the used locale(s) and retrieves reference attribute as array of objects.
	 */
	@Nonnull
	private static Optional<Object[]> getLocalizedAttributeAsObjectArray(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull SealedEntity sealedEntity,
		@Nonnull ReferenceContract reference
	) {
		final LocaleSerializablePredicate localePredicate = ((EntityDecorator) sealedEntity).getLocalePredicate();
		final Set<Locale> locales = localePredicate.getLocales();
		final Locale locale = locales != null && locales.size() == 1 ? locales.iterator().next() : localePredicate.getImplicitLocale();

		final Object[] resultValue;
		if (locale == null && locales != null && locales.isEmpty()) {
			if (itemType.isArray()) {
				resultValue = sealedEntity.getAttributeLocales()
					.stream()
					.map(it -> {
						// noinspection rawtypes,DataFlowIssue,unchecked
						return defaultValueProvider.apply(
							toTargetType(
								reference.getAttribute(
									attributeName,
									locale,
									(Class) itemType.getComponentType()
								),
								(Class) itemType.getComponentType(),
								indexedDecimalPlaces
							)
						);
					})
					.toArray(count -> (Serializable[]) Array.newInstance(itemType.getComponentType(), count));
			} else {
				throw new EvitaInvalidUsageException(
					"Cannot initialize reference `" + reference.getReferenceName() + "` attribute `" + attributeName + "` " +
						"in a constructor as a single value since " +
						"it could localized to multiple locales and no locale was requested when fetching the entity!"
				);
			}
		} else if (locale != null) {
			// noinspection rawtypes,unchecked
			resultValue = (Object[]) toTargetType(
				defaultValueProvider.apply(
					reference.getAttribute(
						attributeName,
						locale,
						(Class) itemType.getComponentType()
					)
				),
				(Class) itemType.getComponentType(),
				indexedDecimalPlaces
			);
		} else {
			// noinspection rawtypes,unchecked
			resultValue = (Object[]) toTargetType(
				defaultValueProvider.apply(reference.getAttribute(attributeName)),
				(Class) itemType.getComponentType(),
				indexedDecimalPlaces
			);
		}
		return ofNullable(resultValue);
	}

	public GetReferenceAttributeMethodClassifier() {
		super(
			"getReferencedAttribute",
			(method, proxyState) -> {
				// We only want to handle non-abstract methods with no parameters or a single Locale parameter
				if (
					method.getParameterCount() > 1 ||
						method.isAnnotationPresent(CreateWhenMissing.class) ||
						Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream).anyMatch(CreateWhenMissing.class::isInstance) ||
						method.isAnnotationPresent(RemoveWhenExists.class) ||
						Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream).anyMatch(RemoveWhenExists.class::isInstance) ||
						(method.getParameterCount() == 1 && !method.getParameterTypes()[0].equals(Locale.class))
				) {
					return null;
				}
				// now we need to identify attribute schema that is being requested
				final ReferenceSchemaContract referenceSchema = proxyState.getReferenceSchema();
				final AttributeSchemaContract attributeSchema = getAttributeSchema(
					method, proxyState.getReflectionLookup(),
					proxyState.getEntitySchema(),
					referenceSchema
				);
				// if not found, this method is not classified by this implementation
				if (attributeSchema == null) {
					return null;
				} else {
					// finally provide implementation that will retrieve the attribute from the entity
					final String cleanAttributeName = attributeSchema.getName();
					final int indexedDecimalPlaces = attributeSchema.getIndexedDecimalPlaces();

					// now we need to identify the return type
					@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
					final Class<?>[] resolvedTypes = getResolvedTypes(method, proxyState.getProxyClass());
					final UnaryOperator<Serializable> defaultValueProvider = createDefaultValueProvider(attributeSchema, returnType);
					final ResultWrapper resultWrapper;
					final int index = Optional.class.isAssignableFrom(resolvedTypes[0]) ? 1 : 0;

					@SuppressWarnings("rawtypes") final Class collectionType;
					@SuppressWarnings("rawtypes") final Class itemType;
					if (Collection.class.equals(resolvedTypes[index]) || List.class.isAssignableFrom(resolvedTypes[index]) || Set.class.isAssignableFrom(resolvedTypes[index])) {
						collectionType = resolvedTypes[index];
						itemType = Array.newInstance(resolvedTypes.length > index + 1 ? resolvedTypes[index + 1] : Object.class, 0).getClass();
						resultWrapper = ProxyUtils.createOptionalWrapper(method, Optional.class.isAssignableFrom(resolvedTypes[0]) ? Optional.class : null);
					} else if (resolvedTypes[index].isArray()) {
						collectionType = null;
						itemType = resolvedTypes[index];
						resultWrapper = ProxyUtils.createOptionalWrapper(method, Optional.class.isAssignableFrom(resolvedTypes[0]) ? Optional.class : null);
					} else {
						collectionType = null;
						if (OptionalInt.class.isAssignableFrom(resolvedTypes[0])) {
							itemType = int.class;
							resultWrapper = ProxyUtils.createOptionalWrapper(method, itemType);
						} else if (OptionalLong.class.isAssignableFrom(resolvedTypes[0])) {
							itemType = long.class;
							resultWrapper = ProxyUtils.createOptionalWrapper(method, itemType);
						} else if (Optional.class.isAssignableFrom(resolvedTypes[0])) {
							itemType = resolvedTypes[index];
							resultWrapper = ProxyUtils.createOptionalWrapper(method, itemType);
						} else {
							itemType = resolvedTypes[index];
							resultWrapper = ProxyUtils.createOptionalWrapper(method, null);
						}
					}

					final BiFunction<ReferenceContract, String, Serializable> attributeExtractor =
						resultWrapper instanceof OptionalProducingOperator ?
							(reference, attributeName) -> reference.attributeAvailable(attributeName) ? reference.getAttribute(attributeName) : null :
							ReferenceContract::getAttribute;

					if (attributeSchema.isLocalized()) {

						final TriFunction<ReferenceContract, String, Locale, Serializable> localizedAttributeExtractor =
							resultWrapper instanceof OptionalProducingOperator ?
								(reference, attributeName, locale) -> reference.attributeAvailable(attributeName, locale) ? reference.getAttribute(attributeName, locale) : null :
								ReferenceContract::getAttribute;

						if (collectionType != null && Set.class.isAssignableFrom(collectionType)) {
							//noinspection unchecked
							return setOfLocalizedResults(
								cleanAttributeName, method, itemType, indexedDecimalPlaces,
								attributeExtractor, localizedAttributeExtractor, defaultValueProvider, resultWrapper
							);
						} else if (collectionType != null) {
							//noinspection unchecked
							return listOfLocalizedResults(
								cleanAttributeName, method, itemType, indexedDecimalPlaces,
								attributeExtractor, localizedAttributeExtractor, defaultValueProvider, resultWrapper
							);
						} else {
							//noinspection unchecked
							return singleLocalizedResult(
								cleanAttributeName, method, itemType, indexedDecimalPlaces,
								attributeExtractor, localizedAttributeExtractor, defaultValueProvider, resultWrapper
							);
						}
					} else {
						Assert.isTrue(
							method.getParameterCount() == 0,
							"Non-localized attribute `" + attributeSchema.getName() + "`" +
								(referenceSchema == null ? "" : " of reference `" + referenceSchema.getName() + "`") +
								" must not have a locale parameter!"
						);
						if (collectionType != null && Set.class.isAssignableFrom(collectionType)) {
							//noinspection unchecked
							return setOfResults(
								cleanAttributeName, method, itemType, indexedDecimalPlaces,
								attributeExtractor, defaultValueProvider, resultWrapper
							);
						} else if (collectionType != null) {
							//noinspection unchecked
							return listOfResults(
								cleanAttributeName, method, itemType, indexedDecimalPlaces,
								attributeExtractor, defaultValueProvider, resultWrapper
							);
						} else {
							//noinspection unchecked
							return singleResult(
								cleanAttributeName, method, itemType, indexedDecimalPlaces,
								attributeExtractor, defaultValueProvider, resultWrapper
							);
						}
					}
				}
			}
		);
	}

}
