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

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.impl.ProxyUtils;
import io.evitadb.api.proxy.impl.ProxyUtils.OptionalProducingOperator;
import io.evitadb.api.proxy.impl.ProxyUtils.ResultWrapper;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.ExistingEntityBuilder;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.function.ExceptionRethrowingFunction;
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
import java.util.stream.Collectors;

import static io.evitadb.api.proxy.impl.ProxyUtils.getResolvedTypes;
import static io.evitadb.dataType.EvitaDataTypes.toTargetType;
import static java.util.Optional.ofNullable;

/**
 * Identifies methods that are used to get attributes from an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetAttributeMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetAttributeMethodClassifier INSTANCE = new GetAttributeMethodClassifier();

	/**
	 * Provides default value (according to a schema or implicit rules) instead of null.
	 */
	@Nonnull
	public static UnaryOperator<Serializable> createDefaultValueProvider(@Nonnull AttributeSchemaContract attributeSchema, @Nonnull Class<?> returnType) {
		final UnaryOperator<Serializable> defaultValueProvider;
		if (boolean.class.equals(returnType)) {
			defaultValueProvider = attributeSchema.getDefaultValue() == null ?
				o -> o == null ? false : o :
				o -> o == null ? attributeSchema.getDefaultValue() : o;
		} else if (attributeSchema.getDefaultValue() != null) {
			defaultValueProvider = o -> o == null ? attributeSchema.getDefaultValue() : o;
		} else {
			defaultValueProvider = UnaryOperator.identity();
		}
		return defaultValueProvider;
	}

	/**
	 * Tries to identify attribute name from the class field related to the constructor parameter.
	 *
	 * @param expectedType     class the constructor belongs to
	 * @param parameter        constructor parameter
	 * @param reflectionLookup reflection lookup
	 * @return attribute name derived from the annotation if found
	 */
	@Nullable
	public static <T> ExceptionRethrowingFunction<EntityContract, Object> getExtractorIfPossible(
		@Nonnull Class<T> expectedType,
		@Nonnull Parameter parameter,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EntitySchemaContract schema
	) {
		final String parameterName = parameter.getName();
		final Class<?>[] resolvedTypes = getResolvedTypes(parameter, expectedType);
		final Class<?> parameterType = resolvedTypes[0];

		final Class<?> itemType = resolvedTypes.length > 1 ? resolvedTypes[1] : resolvedTypes[0];
		final AttributeSchemaContract attributeSchema = getAttributeSchema(
			expectedType, parameter,
			itemType,
			reflectionLookup,
			schema
		);

		if (attributeSchema != null) {
			final String attributeName = attributeSchema.getName();
			final UnaryOperator<Serializable> defaultValueProvider = createDefaultValueProvider(attributeSchema, itemType);
			if (attributeSchema.isLocalized()) {
				if (attributeSchema.getType().isArray()) {
					if (parameterType.isArray()) {
						if (attributeSchema.getType().isArray()) {
							return entity -> getLocalizedAttributeAsSingleValue(
								entity, attributeName, parameterType,
								attributeSchema.getIndexedDecimalPlaces(),
								defaultValueProvider
							);
						}
					} else if (Set.class.equals(parameterType)) {
						Assert.isTrue(resolvedTypes.length == 2, "Parameter `" + parameterName + "` in constructor class `" + expectedType + "` is expected to have a generic type!");
						final Class<?> specificType = resolvedTypes[1];
						return entity -> getLocalizedAttributeAsSet(
							entity, attributeName, specificType,
							attributeSchema.getIndexedDecimalPlaces(),
							defaultValueProvider
						);
					} else if (List.class.equals(parameterType) || Collection.class.equals(parameterType)) {
						Assert.isTrue(resolvedTypes.length == 2, "Parameter `" + parameterName + "` in constructor class `" + expectedType + "` is expected to have a generic type!");
						final Class<?> specificType = resolvedTypes[1];
						return entity -> getLocalizedAttributeAsList(
							entity, attributeName, specificType,
							attributeSchema.getIndexedDecimalPlaces(),
							defaultValueProvider
						);
					} else {
						throw new EntityClassInvalidException(
							expectedType,
							"Unsupported data type `" + parameterType + "` for attribute `" + attributeName + "` in entity `" + schema.getName() +
								"` related to constructor parameter `" + parameterName + "`!"
						);
					}
				} else {
					if (parameterType.isEnum()) {
						return entity -> getLocalizedAttributeAsAnEnum(
							entity, attributeName, parameterType, defaultValueProvider
						);
					} else {
						return entity -> getLocalizedAttributeAsSingleValue(
							entity, attributeName, parameterType, attributeSchema.getIndexedDecimalPlaces(),
							defaultValueProvider
						);
					}
				}
			} else {
				if (attributeSchema.getType().isArray()) {
					if (parameterType.isArray()) {
						return entity -> getAttributeAsSingleValue(
							entity, attributeName, parameterType,
							attributeSchema.getIndexedDecimalPlaces(),
							defaultValueProvider
						);
					} else if (Set.class.equals(parameterType)) {
						Assert.isTrue(resolvedTypes.length == 2, "Parameter `" + parameterName + "` in constructor class `" + expectedType + "` is expected to have a generic type!");
						final Class<?> specificType = resolvedTypes[1];
						return entity -> getAttributeAsSet(
							entity, attributeName, specificType,
							attributeSchema.getIndexedDecimalPlaces(),
							defaultValueProvider
						);
					} else if (List.class.equals(parameterType) || Collection.class.equals(parameterType)) {
						Assert.isTrue(resolvedTypes.length == 2, "Parameter `" + parameterName + "` in constructor class `" + expectedType + "` is expected to have a generic type!");
						final Class<?> specificType = resolvedTypes[1];
						return entity -> getAttributeAsList(
							entity, attributeName, specificType,
							attributeSchema.getIndexedDecimalPlaces(),
							defaultValueProvider
						);
					} else {
						throw new EntityClassInvalidException(
							expectedType,
							"Unsupported data type `" + parameterType + "` for attribute `" + attributeName + "` in entity `" + schema.getName() +
								"` related to constructor parameter `" + parameterName + "`!"
						);
					}
				} else {
					if (parameterType.isEnum()) {
						return entity -> getAttributeAsAnEnum(
							entity, attributeName, parameterType, defaultValueProvider
						);
					} else {
						return entity -> getAttributeAsSingleValue(
							entity, attributeName, parameterType, attributeSchema.getIndexedDecimalPlaces(),
							defaultValueProvider
						);
					}
				}
			}
		}

		return null;
	}

	/**
	 * Retrieves appropriate attribute schema from the annotations on the method. If no Evita annotation is found
	 * it tries to match the attribute name by the name of the method.
	 */
	@Nullable
	public static AttributeSchemaContract getAttributeSchema(
		@Nonnull Method method,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EntitySchemaContract entitySchema
	) {
		final Attribute attributeInstance = reflectionLookup.getAnnotationInstanceForProperty(method, Attribute.class);
		final AttributeRef attributeRefInstance = reflectionLookup.getAnnotationInstanceForProperty(method, AttributeRef.class);
		final Function<String, AttributeSchemaContract> schemaLocator = attributeName -> entitySchema.getAttribute(attributeName)
			.orElseThrow(() -> new AttributeNotFoundException(attributeName, entitySchema));
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
		} else if (!reflectionLookup.hasAnnotationForPropertyInSamePackage(method, Attribute.class) && ClassUtils.isAbstract(method)) {
			final Optional<String> attributeName = ReflectionLookup.getPropertyNameFromMethodNameIfPossible(method.getName());
			return attributeName
				.flatMap(attrName -> entitySchema.getAttributeByName(attrName, NamingConvention.CAMEL_CASE))
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
		@Nonnull Class<?> expectedType,
		@Nonnull Parameter parameter,
		@Nonnull Class<?> itemType,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EntitySchemaContract entitySchema
	) {
		final String parameterName = parameter.getName();
		final Attribute attributeInstance = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, Attribute.class);
		final AttributeRef attributeRefInstance = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, AttributeRef.class);
		final Function<String, AttributeSchemaContract> schemaLocator = attributeName -> entitySchema.getAttribute(attributeName)
			.orElse(null);
		if (attributeInstance != null) {
			return schemaLocator.apply(attributeInstance.name().isBlank() ? parameterName : attributeInstance.name());
		} else if (attributeRefInstance != null) {
			return schemaLocator.apply(
				attributeRefInstance.value().isBlank() ? parameterName : attributeRefInstance.value()
			);
		} else if (!reflectionLookup.hasAnnotationInSamePackage(expectedType, parameter, Attribute.class) &&
			(EvitaDataTypes.isSupportedTypeOrItsArray(itemType) || itemType.isEnum())) {
			return schemaLocator.apply(parameterName);
		} else {
			return null;
		}
	}

	/**
	 * Creates an implementation of the method returning an attribute as a requested type.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleResult(
		@Nonnull String cleanAttributeName,
		@Nonnull Method method,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull BiFunction<EntityContract, String, Serializable> attributeExtractor,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull ResultWrapper resultWrapper
	) {
		Assert.isTrue(
			method.getParameterCount() == 0,
			"Non-localized attribute `" + cleanAttributeName + "` must not have a locale parameter!"
		);
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
			() -> toTargetType(
				defaultValueProvider.apply(attributeExtractor.apply(theState.entity(), cleanAttributeName)),
				itemType, indexedDecimalPlaces
			)
		);
	}

	/**
	 * Creates an implementation of the method returning an attribute of an array type wrapped into a set.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfResults(
		@Nonnull String cleanAttributeName,
		@Nonnull Method method,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull BiFunction<EntityContract, String, Serializable> attributeExtractor,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull ResultWrapper resultWrapper
	) {
		Assert.isTrue(
			method.getParameterCount() == 0,
			"Non-localized attribute `" + cleanAttributeName + "` must not have a locale parameter!"
		);
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
			() -> ofNullable(
				toTargetType(
					defaultValueProvider.apply(attributeExtractor.apply(theState.entity(), cleanAttributeName)),
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
	 * Creates an implementation of the method returning an attribute of an array type wrapped into a list.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfResults(
		@Nonnull String cleanAttributeName,
		@Nonnull Method method,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull BiFunction<EntityContract, String, Serializable> attributeExtractor,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull ResultWrapper resultWrapper
	) {
		Assert.isTrue(
			method.getParameterCount() == 0,
			"Non-localized attribute `" + cleanAttributeName + "` must not have a locale parameter!"
		);
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
			() -> ofNullable(
				toTargetType(
					defaultValueProvider.apply(attributeExtractor.apply(theState.entity(), cleanAttributeName)),
					itemType, indexedDecimalPlaces
				)
			)
				.map(it -> List.of((Object[]) it))
				.orElse(Collections.emptyList())
		);
	}

	/**
	 * Creates an implementation of the method returning an attribute as a requested type.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleLocalizedResult(
		@Nonnull String cleanAttributeName,
		@Nonnull Method method,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull TriFunction<EntityContract, String, Locale, Serializable> localizedAttributeExtractor,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull ResultWrapper resultWrapper
	) {
		return method.getParameterCount() == 0 ?
			(entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final EntityContract sealedEntity = theState.entity();
				final Locale locale = getRequestedLocale(sealedEntity);
				if (locale != null) {
					return resultWrapper.wrap(
						() -> toTargetType(
							defaultValueProvider.apply(localizedAttributeExtractor.apply(sealedEntity, cleanAttributeName, locale)),
							itemType, indexedDecimalPlaces
						)
					);
				} else {
					return defaultValueProvider.apply(null);
				}
			} :
			(entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
				() -> toTargetType(
					defaultValueProvider.apply(
						localizedAttributeExtractor.apply(theState.entity(), cleanAttributeName, (Locale) args[0])
					),
					itemType, indexedDecimalPlaces
				)
			);
	}

	/**
	 * Retrieves the requested locale from the given entity, if applicable.
	 *
	 * @param entity the entity from which the requested locale is to be retrieved;
	 *               must not be null
	 * @return the requested locale if available, or null if the entity does not have a requested locale
	 */
	@Nullable
	private static Locale getRequestedLocale(@Nonnull EntityContract entity) {
		if (entity instanceof EntityDecorator entityDecorator) {
			return entityDecorator.getRequestedLocale();
		} else if (entity instanceof ExistingEntityBuilder entityBuilder) {
			return entityBuilder.getRequestedLocale();
		}
		return null;
	}

	/**
	 * Determines if multiple locales are requested for the given entity.
	 *
	 * @param entity the entity to check, must not be null
	 * @return true if multiple locales are requested, false otherwise
	 */
	private static boolean isMultipleLocalesRequested(@Nonnull EntityContract entity) {
		if (entity instanceof EntityDecorator entityDecorator) {
			return entityDecorator.isMultipleLocalesRequested();
		} else if (entity instanceof ExistingEntityBuilder entityBuilder) {
			return entityBuilder.isMultipleLocalesRequested();
		}
		return false;
	}

	/**
	 * Creates an implementation of the method returning an attribute of an array type wrapped into a set.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfLocalizedResults(
		@Nonnull String cleanAttributeName,
		@Nonnull Method method,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull TriFunction<EntityContract, String, Locale, Serializable> localizedAttributeExtractor,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull ResultWrapper resultWrapper
	) {
		return method.getParameterCount() == 0 ?
			(entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final EntityContract sealedEntity = theState.entity();
				final Locale locale = getRequestedLocale(sealedEntity);
				if (locale != null) {
					return resultWrapper.wrap(
						() -> ofNullable(
							toTargetType(
								defaultValueProvider.apply(localizedAttributeExtractor.apply(sealedEntity, cleanAttributeName, locale)),
								itemType, indexedDecimalPlaces
							)
						)
							.map(it -> arrayToSet((Object[]) it))
							.orElse(Collections.emptySet())
					);
				} else {
					return sealedEntity.getLocales()
						.stream()
						.map(
							theLocale -> resultWrapper.wrap(
								() -> ofNullable(
									toTargetType(
										defaultValueProvider.apply(localizedAttributeExtractor.apply(sealedEntity, cleanAttributeName, theLocale)),
										itemType, indexedDecimalPlaces
									)
								)
							)
						)
						.collect(Collectors.toSet());
				}
			} :
			(entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
				() -> ofNullable(
					toTargetType(
						defaultValueProvider.apply(
							localizedAttributeExtractor.apply(
								theState.entity(), cleanAttributeName, (Locale) args[0]
							)
						),
						itemType, indexedDecimalPlaces
					)
				)
					.map(it -> arrayToSet((Object[]) it))
					.orElse(Collections.emptySet())
			);
	}

	/**
	 * Method converts the input array into an output set
	 *
	 * @param value input array
	 * @return output set
	 */
	@Nonnull
	private static Set<Object> arrayToSet(Object[] value) {
		final Set<Object> result = CollectionUtils.createHashSet(value.length);
		Collections.addAll(result, value);
		return result;
	}

	/**
	 * Creates an implementation of the method returning an attribute of an array type wrapped into a list.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfLocalizedResults(
		@Nonnull String cleanAttributeName,
		@Nonnull Method method,
		@Nonnull Class<? extends Serializable> itemType,
		int indexedDecimalPlaces,
		@Nonnull TriFunction<EntityContract, String, Locale, Serializable> localizedAttributeExtractor,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider,
		@Nonnull ResultWrapper resultWrapper
	) {
		return method.getParameterCount() == 0 ?
			(entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final EntityContract sealedEntity = theState.entity();
				final Locale locale = getRequestedLocale(sealedEntity);
				if (locale != null) {
					return resultWrapper.wrap(
						() -> ofNullable(
							toTargetType(
								defaultValueProvider.apply(localizedAttributeExtractor.apply(sealedEntity, cleanAttributeName, locale)),
								itemType, indexedDecimalPlaces
							)
						)
							.map(it -> List.of((Object[]) it))
							.orElse(Collections.emptyList())
					);
				} else {
					return sealedEntity.getLocales()
						.stream()
						.map(
							theLocale -> resultWrapper.wrap(
								() -> ofNullable(
									toTargetType(
										defaultValueProvider.apply(localizedAttributeExtractor.apply(sealedEntity, cleanAttributeName, theLocale)),
										itemType, indexedDecimalPlaces
									)
								)
							)
						)
						.toList();
				}
			} :
			(entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
				() -> ofNullable(
					toTargetType(
						defaultValueProvider.apply(
							localizedAttributeExtractor.apply(
								theState.entity(), cleanAttributeName, (Locale) args[0]
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
	 * Returns an attribute value converted to a requestedType.
	 *
	 * @param entity               entity
	 * @param attributeName        attribute name
	 * @param requestedType        requested type
	 * @param indexedDecimalPlaces indexed decimal places
	 * @param defaultValueProvider default value provider
	 * @return attribute value converted to a requestedType
	 */
	@SuppressWarnings("rawtypes")
	@Nullable
	private static Serializable getAttributeAsSingleValue(
		@Nonnull EntityContract entity,
		@Nonnull String attributeName,
		@Nonnull Class requestedType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		if (entity.attributeAvailable(attributeName)) {
			//noinspection unchecked
			return EvitaDataTypes.toTargetType(
				defaultValueProvider.apply(
					entity.getAttributeValue(attributeName)
						.filter(Droppable::exists)
						.map(AttributeValue::value)
						.orElse(null)
				),
				requestedType,
				indexedDecimalPlaces
			);
		} else {
			return defaultValueProvider.apply(null);
		}
	}

	/**
	 * Returns a localized attribute value converted to a requestedType.
	 *
	 * @param entity               entity
	 * @param attributeName        attribute name
	 * @param requestedType        requested type
	 * @param indexedDecimalPlaces indexed decimal places
	 * @param defaultValueProvider default value provider
	 * @return attribute value converted to a requestedType
	 */
	@SuppressWarnings("rawtypes")
	@Nullable
	private static Serializable getLocalizedAttributeAsSingleValue(
		@Nonnull EntityContract entity,
		@Nonnull String attributeName,
		@Nonnull Class requestedType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		final Locale locale = getRequestedLocale(entity);
		if (locale == null && isMultipleLocalesRequested(entity) && entity.attributeAvailable(attributeName)) {
			if (requestedType.isArray()) {
				return entity.getAttributeLocales()
					.stream()
					.map(it -> {
						//noinspection DataFlowIssue,unchecked
						return EvitaDataTypes.toTargetType(
							defaultValueProvider.apply(
								entity.getAttributeValue(
										attributeName,
										locale
									)
									.filter(Droppable::exists)
									.map(AttributeValue::value)
									.orElse(null)
							),
							requestedType.getComponentType(),
							indexedDecimalPlaces
						);
					})
					.toArray(count -> (Serializable[]) Array.newInstance(requestedType.getComponentType(), count));
			} else {
				throw new EvitaInvalidUsageException(
					"Cannot initialize attribute `" + attributeName + "` in a constructor as a single value since " +
						"it could be localized to multiple locales and no locale was requested when fetching the entity!"
				);
			}
		} else if (locale != null && entity.attributeAvailable(attributeName, locale)) {
			//noinspection unchecked
			return toTargetType(
				defaultValueProvider.apply(
					entity.getAttributeValue(
							attributeName,
							locale
						)
						.filter(Droppable::exists)
						.map(AttributeValue::value)
						.orElse(null)
				),
				requestedType,
				indexedDecimalPlaces
			);
		} else {
			return defaultValueProvider.apply(null);
		}
	}

	/**
	 * Returns an attribute value array converted to a set of requestedType elements.
	 *
	 * @param entity               entity
	 * @param attributeName        attribute name
	 * @param requestedType        requested type
	 * @param indexedDecimalPlaces indexed decimal places
	 * @param defaultValueProvider default value provider
	 * @return attribute value converted to a requestedType
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nullable
	private static Set<?> getAttributeAsSet(
		@Nonnull EntityContract entity,
		@Nonnull String attributeName,
		@Nonnull Class requestedType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		if (entity.attributeAvailable(attributeName)) {
			final Serializable[] value = (Serializable[]) defaultValueProvider.apply(
				entity.getAttributeValue(attributeName)
					.filter(Droppable::exists)
					.map(AttributeValue::value)
					.orElse(null)
			);
			return Arrays.stream(value)
				.map(
					theValue -> EvitaDataTypes.toTargetType(
						theValue,
						requestedType,
						indexedDecimalPlaces
					)
				)
				.collect(Collectors.toSet());
		} else {
			final Serializable defaultValue = defaultValueProvider.apply(null);
			if (defaultValue == null) {
				return Collections.emptySet();
			} else {
				return Collections.singleton(defaultValue);
			}
		}
	}

	/**
	 * Returns a localized attribute value array converted to a set of requestedType elements.
	 *
	 * @param entity               entity
	 * @param attributeName        attribute name
	 * @param requestedType        requested type
	 * @param indexedDecimalPlaces indexed decimal places
	 * @param defaultValueProvider default value provider
	 * @return attribute value converted to a requestedType
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nullable
	private static Set<?> getLocalizedAttributeAsSet(
		@Nonnull EntityContract entity,
		@Nonnull String attributeName,
		@Nonnull Class requestedType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		final Locale locale = getRequestedLocale(entity);
		final Serializable[] value;
		if (locale == null && isMultipleLocalesRequested(entity) && entity.attributeAvailable(attributeName)) {
			throw new EvitaInvalidUsageException(
				"Cannot initialize attribute `" + attributeName + "` in a constructor as a set since " +
					"it could be localized to multiple locales, and it's expected to be an array data type. " +
					"When none or multiple locales was requested when fetching the entity, it would require concatenating " +
					"multiple arrays and losing the information about the associated locale!"
			);
		} else if (locale != null && entity.attributeAvailable(attributeName, locale)) {
			value = (Serializable[]) defaultValueProvider.apply(
				entity.getAttributeValue(
						attributeName,
						locale
					)
					.filter(Droppable::exists)
					.map(AttributeValue::value)
					.orElse(null)
			);
		} else {
			return Collections.emptySet();
		}

		return Arrays.stream(value)
			.map(
				theValue -> EvitaDataTypes.toTargetType(
					theValue,
					requestedType,
					indexedDecimalPlaces
				)
			)
			.collect(Collectors.toSet());
	}

	/**
	 * Returns an attribute value array converted to a list of requestedType elements.
	 *
	 * @param entity               entity
	 * @param attributeName        attribute name
	 * @param requestedType        requested type
	 * @param indexedDecimalPlaces indexed decimal places
	 * @param defaultValueProvider default value provider
	 * @return attribute value converted to a requestedType
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nullable
	private static List<?> getAttributeAsList(
		@Nonnull EntityContract entity,
		@Nonnull String attributeName,
		@Nonnull Class requestedType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		if (entity.attributeAvailable(attributeName)) {
			final Serializable[] value = (Serializable[]) defaultValueProvider.apply(
				entity.getAttributeValue(attributeName)
					.filter(Droppable::exists)
					.map(AttributeValue::value)
					.orElse(null)
			);
			return Arrays.stream(value)
				.map(
					theValue -> EvitaDataTypes.toTargetType(
						theValue,
						requestedType,
						indexedDecimalPlaces
					)
				)
				.toList();
		} else {
			final Serializable defaultValue = defaultValueProvider.apply(null);
			if (defaultValue == null) {
				return Collections.emptyList();
			} else {
				return Collections.singletonList(defaultValue);
			}
		}
	}

	/**
	 * Returns a localized attribute value array converted to a list of requestedType elements.
	 *
	 * @param entity               entity
	 * @param attributeName        attribute name
	 * @param requestedType        requested type
	 * @param indexedDecimalPlaces indexed decimal places
	 * @param defaultValueProvider default value provider
	 * @return attribute value converted to a requestedType
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nullable
	private static List<?> getLocalizedAttributeAsList(
		@Nonnull EntityContract entity,
		@Nonnull String attributeName,
		@Nonnull Class requestedType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		final Locale locale = getRequestedLocale(entity);
		final Serializable[] value;
		if (locale == null && isMultipleLocalesRequested(entity) && entity.attributeAvailable(attributeName)) {
			throw new EvitaInvalidUsageException(
				"Cannot initialize attribute `" + attributeName + "` in a constructor as a set since " +
					"it could be localized to multiple locales, and it's expected to be an array data type. " +
					"When none or multiple locales was requested when fetching the entity, it would require concatenating " +
					"multiple arrays and losing the information about the associated locale!"
			);
		} else if (locale != null && entity.attributeAvailable(attributeName, locale)) {
			value = (Serializable[]) defaultValueProvider.apply(
				entity.getAttributeValue(
						attributeName,
						locale
					)
					.filter(Droppable::exists)
					.map(AttributeValue::value)
					.orElse(null)
			);
		} else {
			return Collections.emptyList();
		}

		return Arrays.stream(value)
			.map(
				theValue -> EvitaDataTypes.toTargetType(
					theValue,
					requestedType,
					indexedDecimalPlaces
				)
			)
			.toList();
	}

	/**
	 * Returns an attribute value converted to a requested enum.
	 *
	 * @param entity               entity
	 * @param attributeName        attribute name
	 * @param parameterType        requested enum type
	 * @param defaultValueProvider default value provider
	 * @return attribute value converted to a requested enum
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nullable
	private static Enum<?> getAttributeAsAnEnum(
		@Nonnull EntityContract entity,
		@Nonnull String attributeName,
		@Nonnull Class parameterType,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		if (entity.attributeAvailable(attributeName)) {
			//noinspection unchecked
			return Enum.valueOf(
				parameterType,
				(String) defaultValueProvider.apply(
					entity.getAttributeValue(attributeName)
						.filter(Droppable::exists)
						.map(AttributeValue::value)
						.orElse(null)
				)
			);
		} else {
			return (Enum<?>) defaultValueProvider.apply(null);
		}
	}

	/**
	 * Returns a localized attribute value converted to a requested enum.
	 *
	 * @param entity               entity
	 * @param attributeName        attribute name
	 * @param parameterType        requested enum type
	 * @param defaultValueProvider default value provider
	 * @return attribute value converted to a requested enum
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nullable
	private static Enum<?> getLocalizedAttributeAsAnEnum(
		@Nonnull EntityContract entity,
		@Nonnull String attributeName,
		@Nonnull Class parameterType,
		@Nonnull UnaryOperator<Serializable> defaultValueProvider
	) {
		final Locale locale = getRequestedLocale(entity);
		if (locale == null && isMultipleLocalesRequested(entity) && entity.attributeAvailable(attributeName)) {
			throw new EvitaInvalidUsageException(
				"Cannot initialize attribute `" + attributeName + "` in a constructor as a single enum value since " +
					"it could be localized to multiple locales, and none or multiple locales was requested when fetching the entity!"
			);
		} else if (locale != null && entity.attributeAvailable(attributeName, locale)) {
			//noinspection unchecked
			return Enum.valueOf(
				parameterType,
				(String) defaultValueProvider.apply(
					entity.getAttributeValue(
							attributeName,
							locale
						)
						.filter(Droppable::exists)
						.map(AttributeValue::value)
						.orElse(null)
				)
			);
		} else {
			return (Enum<?>) defaultValueProvider.apply(null);
		}
	}

	public GetAttributeMethodClassifier() {
		super(
			"getAttribute",
			(method, proxyState) -> {
				// We only want to handle methods with no parameters or a single Locale parameter
				if (
					method.getParameterCount() > 1 ||
						(method.getParameterCount() == 1 && !method.getParameterTypes()[0].equals(Locale.class)) ||
						method.isAnnotationPresent(CreateWhenMissing.class) ||
						Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream).anyMatch(CreateWhenMissing.class::isInstance) ||
						method.isAnnotationPresent(RemoveWhenExists.class) ||
						Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream).anyMatch(RemoveWhenExists.class::isInstance)
				) {
					return null;
				}
				// now we need to identify attribute schema that is being requested
				final AttributeSchemaContract attributeSchema = getAttributeSchema(
					method, proxyState.getReflectionLookup(),
					proxyState.getEntitySchema()
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

					if (attributeSchema.isLocalized()) {

						final TriFunction<EntityContract, String, Locale, Serializable> localizedAttributeExtractor =
							resultWrapper instanceof OptionalProducingOperator ?
								(entity, attributeName, locale) -> entity.attributeAvailable(attributeName, locale) ? entity.getAttribute(attributeName, locale) : null :
								AttributesContract::getAttribute;

						if (collectionType != null && Set.class.isAssignableFrom(collectionType)) {
							//noinspection unchecked
							return setOfLocalizedResults(
								cleanAttributeName, method, itemType, indexedDecimalPlaces,
								localizedAttributeExtractor, defaultValueProvider, resultWrapper
							);
						} else if (collectionType != null) {
							//noinspection unchecked
							return listOfLocalizedResults(
								cleanAttributeName, method, itemType, indexedDecimalPlaces,
								localizedAttributeExtractor, defaultValueProvider, resultWrapper
							);
						} else {
							//noinspection unchecked
							return singleLocalizedResult(
								cleanAttributeName, method, itemType, indexedDecimalPlaces,
								localizedAttributeExtractor, defaultValueProvider, resultWrapper
							);
						}
					} else {
						Assert.isTrue(
							method.getParameterCount() == 0,
							"Non-localized attribute `" + attributeSchema.getName() + "` must not have a locale parameter!"
						);

						final BiFunction<EntityContract, String, Serializable> attributeExtractor =
							resultWrapper instanceof OptionalProducingOperator ?
								(entity, attributeName) -> entity.attributeAvailable(attributeName) ?
									entity.getAttributeValue(attributeName).filter(Droppable::exists).map(AttributeValue::value).orElse(null) : null :
								(theEntity, theAttributeName) -> theEntity.getAttributeValue(theAttributeName).filter(Droppable::exists).map(AttributeValue::value).orElse(null);

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
