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

package io.evitadb.api.proxy.impl.entityBuilder;

import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;
import one.edee.oss.proxycian.utils.GenericsUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.evitadb.api.proxy.impl.entity.GetAssociatedDataMethodClassifier.getAssociatedDataSchema;

/**
 * Identifies methods that are used to set entity associated data into an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SetAssociatedDataMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final SetAssociatedDataMethodClassifier INSTANCE = new SetAssociatedDataMethodClassifier();

	public SetAssociatedDataMethodClassifier() {
		super(
			"setAssociatedData",
			(method, proxyState) -> {
				final int valueParameterPosition;
				final OptionalInt localeParameterPosition;
				// We only want to handle methods with exactly one parameter, or two parameters of which one is Locale
				final Class<?>[] parameterTypes = method.getParameterTypes();
				if (method.getParameterCount() == 1 &&
					(Serializable.class.isAssignableFrom(parameterTypes[0]) ||
						Collection.class.isAssignableFrom(parameterTypes[0]) ||
						(parameterTypes[0].isArray() && Serializable.class.isAssignableFrom(parameterTypes[0].getComponentType()))
					)
				) {
					if (Locale.class.isAssignableFrom(parameterTypes[0])) {
						valueParameterPosition = -1;
						localeParameterPosition = OptionalInt.of(0);
					} else {
						valueParameterPosition = 0;
						localeParameterPosition = OptionalInt.empty();
					}
				} else if (
					(method.getParameterCount() == 2 &&
						Arrays.stream(parameterTypes)
							.allMatch(
								it -> Serializable.class.isAssignableFrom(it) ||
									Collection.class.isAssignableFrom(it) ||
									(it.isArray() && Serializable.class.isAssignableFrom(it.getComponentType()))
							)
					)
				) {
					int lp = -1;
					for (int i = 0; i < parameterTypes.length; i++) {
						 if (Locale.class.isAssignableFrom(parameterTypes[i])) {
							 lp = i;
							 break;
						 }
					}
					if (lp == -1) {
						return null;
					} else {
						localeParameterPosition = OptionalInt.of(lp);
						valueParameterPosition = lp == 0 ? 1 : 0;
					}
				} else {
					localeParameterPosition = OptionalInt.empty();
					valueParameterPosition = -1;
				}

				// now we need to identify associatedData schema that is being requested
				final AssociatedDataSchemaContract associatedDataSchema = getAssociatedDataSchema(
					method, proxyState.getReflectionLookup(),
					proxyState.getEntitySchema()
				);
				// if not found, this method is not classified by this implementation
				if (associatedDataSchema == null) {
					return null;
				} else {
					// finally provide implementation that will retrieve the associatedData from the entity
					final String associatedDataName = associatedDataSchema.getName();
					if (associatedDataSchema.isLocalized()) {
						Assert.isTrue(
							localeParameterPosition.isPresent(),
							"localized associated data `" + associatedDataSchema.getName() + "` must have a locale parameter!"
						);
						if (method.isAnnotationPresent(RemoveWhenExists.class)) {
							if (method.getReturnType().equals(proxyState.getProxyClass())) {
								return removeLocalizedAssociatedDataWithBuilderResult(associatedDataName);
							} else if (method.getReturnType().equals(void.class)) {
								return removeLocalizedAssociatedDataWithVoidResult(associatedDataName);
							} else if (Collection.class.isAssignableFrom(method.getReturnType())) {
								//noinspection rawtypes
								final Class expectedType = GenericsUtils.getMethodReturnType(proxyState.getProxyClass(), method);
								if (ComplexDataObject.class.equals(associatedDataSchema.getPlainType())) {
									//noinspection unchecked
									return removeLocalizedAssociatedDataWithComplexDataObjectCollectionResult(
										associatedDataName, associatedDataSchema.getType(),
										(value, reflectionLookup) -> ComplexDataObjectConverter.getOriginalForm(value, expectedType, reflectionLookup)
									);
								} else {
									//noinspection unchecked
									return removeLocalizedAssociatedDataWithValueCollectionResult(
										associatedDataName,
										(value, reflectionLookup) -> EvitaDataTypes.toTargetType(value, expectedType)
									);
								}
							} else {
								//noinspection rawtypes
								final Class expectedType = method.getReturnType();
								//noinspection unchecked
								return removeLocalizedAssociatedDataWithValueResult(
									associatedDataName,
									ComplexDataObject.class.equals(associatedDataSchema.getPlainType()) ?
										(value, reflectionLookup) -> ComplexDataObjectConverter.getOriginalForm(value, expectedType, reflectionLookup) :
										(value, reflectionLookup) -> EvitaDataTypes.toTargetType(value, expectedType)
								);
							}
						} else {
							// now we need to identify the argument type
							final Parameter valueParameter = method.getParameters()[valueParameterPosition];
							// prepare the conversion function
							if (Collection.class.isAssignableFrom(valueParameter.getType()) && !ComplexDataObject.class.equals(associatedDataSchema.getPlainType())) {
								final Function<Serializable, Serializable> converterFct = it -> EvitaDataTypes.toTargetType(it, associatedDataSchema.getPlainType());
								if (method.getReturnType().equals(proxyState.getProxyClass())) {
									return setLocalizedAssociatedDataAsCollectionWithBuilderResult(
										valueParameterPosition, localeParameterPosition.getAsInt(), associatedDataName,
										converterFct, associatedDataSchema.getPlainType()
									);
								} else {
									return setLocalizedAssociatedDataAsCollectionWithVoidResult(
										valueParameterPosition, localeParameterPosition.getAsInt(), associatedDataName,
										converterFct, associatedDataSchema.getPlainType()
									);
								}
							} else {
								final Function<Serializable, Serializable> converterFct = getConverterFunction(
									proxyState.getProxyClass(), valueParameter, associatedDataSchema.getPlainType()
								);
								if (method.getReturnType().equals(proxyState.getProxyClass())) {
									return setLocalizedAssociatedDataAsValueWithBuilderResult(
										valueParameterPosition, localeParameterPosition.getAsInt(), associatedDataName, converterFct
									);
								} else {
									return setLocalizedAssociatedDataAsValueWithVoidResult(
										valueParameterPosition, localeParameterPosition.getAsInt(), associatedDataName, converterFct
									);
								}
							}
						}
					} else {
						if (method.isAnnotationPresent(RemoveWhenExists.class)) {
							Assert.isTrue(
								method.getParameterCount() == 0,
								"Non-localized associated data `" + associatedDataSchema.getName() + "` must not have a locale parameter!"
							);

							if (method.getReturnType().equals(proxyState.getProxyClass())) {
								return removeAssociatedDataWithBuilderResult(associatedDataName);
							} else if (method.getReturnType().equals(void.class)) {
								return removeAssociatedDataWithVoidResult(associatedDataName);
							} else if (Collection.class.isAssignableFrom(method.getReturnType())) {
								//noinspection rawtypes
								final Class expectedType = GenericsUtils.getMethodReturnType(proxyState.getProxyClass(), method);
								if (ComplexDataObject.class.equals(associatedDataSchema.getPlainType())) {
									//noinspection unchecked
									return removeAssociatedDataWithComplexDataObjectCollectionResult(
										associatedDataName,
										(value, reflectionLookup) -> ComplexDataObjectConverter.getOriginalForm(value, expectedType, reflectionLookup)
									);
								} else {
									//noinspection unchecked
									return removeAssociatedDataWithValueCollectionResult(
										associatedDataName, associatedDataSchema.getType(),
										(value, reflectionLookup) -> EvitaDataTypes.toTargetType(value, expectedType)
									);
								}
							} else {
								//noinspection rawtypes
								final Class expectedType = method.getReturnType();
								//noinspection unchecked
								return removeAssociatedDataWithValueResult(
									associatedDataName,
									ComplexDataObject.class.equals(associatedDataSchema.getPlainType()) ?
										(value, reflectionLookup) -> ComplexDataObjectConverter.getOriginalForm(value, expectedType, reflectionLookup) :
										(value, reflectionLookup) -> EvitaDataTypes.toTargetType(value, expectedType)
								);
							}
						} else {
							Assert.isTrue(
								method.getParameterCount() == 1,
								"Non-localized associated data `" + associatedDataSchema.getName() + "` must not have a locale parameter!"
							);
							// now we need to identify the argument type
							final Parameter valueParameter = method.getParameters()[valueParameterPosition];
							// prepare the conversion function
							if (Collection.class.isAssignableFrom(valueParameter.getType()) && !ComplexDataObject.class.equals(associatedDataSchema.getPlainType())) {
								final Function<Serializable, Serializable> converterFct = it -> EvitaDataTypes.toTargetType(it, associatedDataSchema.getPlainType());
								if (method.getReturnType().equals(proxyState.getProxyClass())) {
									return setAssociatedDataAsCollectionWithBuilderResult(
										valueParameterPosition, associatedDataName,
										converterFct, associatedDataSchema.getPlainType()
									);
								} else {
									return setAssociatedDataAsCollectionWithVoidResult(
										valueParameterPosition, associatedDataName,
										converterFct, associatedDataSchema.getPlainType()
									);
								}
							} else {
								final Function<Serializable, Serializable> converterFct = getConverterFunction(
									proxyState.getProxyClass(), valueParameter, associatedDataSchema.getPlainType()
								);
								if (method.getReturnType().equals(proxyState.getProxyClass())) {
									return setAssociatedDataAsValueWithBuilderResult(
										valueParameterPosition, associatedDataName, converterFct
									);
								} else {
									return setAssociatedDataAsValueWithVoidResult(
										valueParameterPosition, associatedDataName, converterFct
									);
								}
							}
						}
					}
				}
			}
		);
	}

	/**
	 * Method returns conversion function for associated data.
	 * @param proxyClass proxy class
	 * @param valueParameter value parameter
	 * @param associatedSchemaType associated schema type
	 * @return conversion function
	 */
	@Nonnull
	private static Function<Serializable, Serializable> getConverterFunction(
		@Nonnull Class<?> proxyClass,
		@Nonnull Parameter valueParameter,
		@Nonnull Class<? extends Serializable> associatedSchemaType
	) {
		final Function<Serializable, Serializable> converterFct;
		if (ComplexDataObject.class.equals(associatedSchemaType)) {
			if (Collection.class.isAssignableFrom(valueParameter.getType())) {
				final Class<?> collectionType = GenericsUtils.getGenericTypeFromCollection(proxyClass, valueParameter.getParameterizedType());
				final Object[] exampleArray = (Object[]) Array.newInstance(collectionType, 0);
				converterFct = it -> {
					final Collection<?> argument = (Collection<?>) it;
					return ComplexDataObjectConverter.getSerializableForm(argument.toArray(exampleArray));
				};
			} else {
				converterFct = ComplexDataObjectConverter::getSerializableForm;
			}
		} else {
			converterFct = it -> EvitaDataTypes.toTargetType(it, associatedSchemaType);
		}
		return converterFct;
	}

	/**
	 * Provides implementation for setting associated data value from a single value returning a void return type.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param associatedDataName name of the associated data to be set
	 * @param valueConverter lambda that converts the value to the target type
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setAssociatedDataAsValueWithVoidResult(
		int valueParameterPosition,
		@Nonnull String associatedDataName,
		@Nonnull Function<Serializable, Serializable> valueConverter
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			if (value == null) {
				entityBuilder.removeAssociatedData(associatedDataName);
			} else {
				entityBuilder.setAssociatedData(
					associatedDataName,
					valueConverter.apply((Serializable) value)
				);
			}
			return null;
		};
	}

	/**
	 * Provides implementation for setting associated data value from a single value returning a proxy object return type
	 * allowing to create a builder pattern in the model objects.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param associatedDataName name of the associated data to be set
	 * @param valueConverter lambda that converts the value to the target type
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setAssociatedDataAsValueWithBuilderResult(
		int valueParameterPosition,
		@Nonnull String associatedDataName,
		@Nonnull Function<Serializable, Serializable> valueConverter
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			if (value == null) {
				entityBuilder.removeAssociatedData(associatedDataName);
			} else {
				entityBuilder.setAssociatedData(
					associatedDataName,
					valueConverter.apply((Serializable) value)
				);
			}
			return proxy;
		};
	}

	/**
	 * Provides implementation for setting associated data array value from a collection value returning a void return type.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param associatedDataName name of the associated data to be set
	 * @param valueConverter lambda that converts the value to the target type
	 * @param plainType expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setAssociatedDataAsCollectionWithVoidResult(
		int valueParameterPosition,
		@Nonnull String associatedDataName,
		@Nonnull Function<Serializable, Serializable> valueConverter,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			if (value == null) {
				entityBuilder.removeAssociatedData(associatedDataName);
			} else {
				//noinspection unchecked,rawtypes
				entityBuilder.setAssociatedData(
					associatedDataName,
					((Collection) value).stream()
						.map(it -> valueConverter.apply((Serializable) it))
						.toArray(cnt -> Array.newInstance(plainType, cnt))
				);
			}
			return null;
		};
	}

	/**
	 * Provides implementation for setting associated data array value from a collection value returning a proxy object
	 * return type allowing to create a builder pattern in the model objects.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param associatedDataName name of the associated data to be set
	 * @param valueConverter lambda that converts the value to the target type
	 * @param plainType expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setAssociatedDataAsCollectionWithBuilderResult(
		int valueParameterPosition,
		@Nonnull String associatedDataName,
		@Nonnull Function<Serializable, Serializable> valueConverter,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			if (value == null) {
				entityBuilder.removeAssociatedData(associatedDataName);
			} else {
				//noinspection unchecked,rawtypes
				entityBuilder.setAssociatedData(
					associatedDataName,
					((Collection) value).stream()
						.map(it -> valueConverter.apply((Serializable) it))
						.toArray(cnt -> Array.newInstance(plainType, cnt))
				);
			}
			return proxy;
		};
	}

	/**
	 * Provides implementation for setting localized associated data value from a single value returning a void return type.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param localeParameterPosition index of the {@link Locale} parameter among method parameters
	 * @param associatedDataName name of the associated data to be set
	 * @param valueConverter lambda that converts the value to the target type
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setLocalizedAssociatedDataAsValueWithVoidResult(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String associatedDataName,
		@Nonnull Function<Serializable, Serializable> valueConverter
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final Locale locale = (Locale) args[localeParameterPosition];
			Assert.notNull(locale, "Locale must not be null!");
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			if (value == null) {
				entityBuilder.removeAssociatedData(associatedDataName);
			} else {
				entityBuilder.setAssociatedData(
					associatedDataName, locale,
					valueConverter.apply((Serializable) value)
				);
			}
			return null;
		};
	}

	/**
	 * Provides implementation for setting localized associated data value from a single value returning the proxy object
	 * return type allowing to create a builder pattern in the model objects.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param localeParameterPosition index of the {@link Locale} parameter among method parameters
	 * @param associatedDataName name of the associated data to be set
	 * @param valueConverter lambda that converts the value to the target type
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setLocalizedAssociatedDataAsValueWithBuilderResult(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String associatedDataName,
		@Nonnull Function<Serializable, Serializable> valueConverter
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final Locale locale = (Locale) args[localeParameterPosition];
			Assert.notNull(locale, "Locale must not be null!");
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			if (value == null) {
				entityBuilder.removeAssociatedData(associatedDataName);
			} else {
				entityBuilder.setAssociatedData(
					associatedDataName, locale,
					valueConverter.apply((Serializable) value)
				);
			}
			return proxy;
		};
	}

	/**
	 * Provides implementation for setting localized associated data array value from a collection value returning a void
	 * return type.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param localeParameterPosition index of the {@link Locale} parameter among method parameters
	 * @param associatedDataName name of the associated data to be set
	 * @param valueConverter lambda that converts the value to the target type
	 * @param plainType expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setLocalizedAssociatedDataAsCollectionWithVoidResult(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String associatedDataName,
		@Nonnull Function<Serializable, Serializable> valueConverter,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final Locale locale = (Locale) args[localeParameterPosition];
			Assert.notNull(locale, "Locale must not be null!");
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			if (value == null) {
				entityBuilder.removeAssociatedData(associatedDataName, locale);
			} else {
				//noinspection unchecked,rawtypes
				final Object[] valueArray = ((Collection) value).stream()
					.map(it -> valueConverter.apply((Serializable) it))
					.toArray(cnt -> Array.newInstance(plainType, cnt));
				entityBuilder.setAssociatedData(associatedDataName, locale, valueArray);
			}
			return null;
		};
	}

	/**
	 * Provides implementation for setting localized associated data array value from a collection value returning the proxy
	 * object return type allowing to create a builder pattern in the model objects.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param localeParameterPosition index of the {@link Locale} parameter among method parameters
	 * @param associatedDataName name of the associated data to be set
	 * @param valueConverter lambda that converts the value to the target type
	 * @param plainType expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setLocalizedAssociatedDataAsCollectionWithBuilderResult(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String associatedDataName,
		@Nonnull Function<Serializable, Serializable> valueConverter,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final Locale locale = (Locale) args[localeParameterPosition];
			Assert.notNull(locale, "Locale must not be null!");
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			if (value == null) {
				entityBuilder.removeAssociatedData(associatedDataName, locale);
			} else {
				//noinspection unchecked,rawtypes
				entityBuilder.setAssociatedData(
					associatedDataName, locale,
					((Collection) value).stream()
						.map(it -> valueConverter.apply((Serializable) value))
						.toArray(cnt -> Array.newInstance(plainType, cnt))
				);
			}
			return proxy;
		};
	}

	/**
	 * Provides implementation for removing localized associated data value returning the proxy object return type allowing
	 * to create a builder pattern in the model objects.
	 *
	 * @param associatedDataName name of the associated data to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeLocalizedAssociatedDataWithBuilderResult(
		@Nonnull String associatedDataName
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			removeLocalizedAssociatedData(associatedDataName, args, theState);
			return proxy;
		};
	}

	/**
	 * Provides implementation for removing localized associated data value returning a void return type.
	 *
	 * @param associatedDataName name of the associated data to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeLocalizedAssociatedDataWithVoidResult(
		@Nonnull String associatedDataName
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			removeLocalizedAssociatedData(associatedDataName, args, theState);
			return null;
		};
	}

	/**
	 * Provides implementation for removing localized associated data value returning the proxy object return type allowing
	 * to create a builder pattern in the model objects.
	 *
	 * @param associatedDataName name of the associated data to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeLocalizedAssociatedDataWithValueCollectionResult(
		@Nonnull String associatedDataName,
		@Nonnull BiFunction<Serializable, ReflectionLookup, Serializable> converter
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object[] removedArray = (Object[]) removeLocalizedAssociatedDataAndReturnIt(associatedDataName, args, theState);
			return Arrays.stream(removedArray).map(it -> converter.apply((Serializable) it, theState.getReflectionLookup())).toList();
		};
	}

	/**
	 * Provides implementation for removing localized associated data value returning the proxy object return type allowing
	 * to create a builder pattern in the model objects.
	 *
	 * @param associatedDataName name of the associated data to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeLocalizedAssociatedDataWithComplexDataObjectCollectionResult(
		@Nonnull String associatedDataName,
		@Nonnull Class<?> schemaType,
		@Nonnull BiFunction<Serializable, ReflectionLookup, Serializable> converter
	) {
		Assert.isTrue(
			schemaType.isArray(),
			"Localized associatedData `" + associatedDataName + "` must be an array in order collection could be returned!"
		);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ComplexDataObject removedValue = (ComplexDataObject) removeLocalizedAssociatedDataAndReturnIt(associatedDataName, args, theState);
			final Serializable result = converter.apply(removedValue, theState.getReflectionLookup());
			return result.getClass().isArray() ? Arrays.stream((Object[]) result).toList() : List.of(result);
		};
	}

	/**
	 * Provides implementation for removing localized associatedData value returning the proxy object return type allowing
	 * to create a builder pattern in the model objects.
	 *
	 * @param associatedDataName name of the associated data to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeLocalizedAssociatedDataWithValueResult(
		@Nonnull String associatedDataName,
		@Nonnull BiFunction<Serializable, ReflectionLookup, Serializable> converter
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> converter.apply(
			removeLocalizedAssociatedDataAndReturnIt(associatedDataName, args, theState),
			theState.getReflectionLookup()
		);
	}

	/**
	 * Removes localized associatedData from the entity.
	 * @param associatedDataName name of the associated data to be set
	 * @param args method arguments
	 * @param theState proxy state
	 */
	private static void removeLocalizedAssociatedData(
		@Nonnull String associatedDataName,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final Locale locale = (Locale) args[0];
		Assert.notNull(locale, "Locale must not be null!");
		final EntityBuilder entityBuilder = theState.getEntityBuilder();
		entityBuilder.removeAssociatedData(associatedDataName, locale);
	}

	/**
	 * Removes localized associated data from the entity and returns its value.
	 * @param associatedDataName name of the associated data to be set
	 * @param args method arguments
	 * @param theState proxy state
	 */
	@Nullable
	private static Serializable removeLocalizedAssociatedDataAndReturnIt(
		@Nonnull String associatedDataName,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final Locale locale = (Locale) args[0];
		Assert.notNull(locale, "Locale must not be null!");
		final EntityBuilder entityBuilder = theState.getEntityBuilder();
		final Serializable associatedDataToRemove = entityBuilder.getAssociatedData(associatedDataName, locale);
		if (associatedDataToRemove != null) {
			entityBuilder.removeAssociatedData(associatedDataName, locale);
			return associatedDataToRemove;
		} else {
			return null;
		}
	}

	/**
	 * Provides implementation for removing associated data value returning the proxy object return type allowing to create
	 * a builder pattern in the model objects.
	 *
	 * @param associatedDataName name of the associated data to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeAssociatedDataWithBuilderResult(
		@Nonnull String associatedDataName
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			removeAssociatedData(associatedDataName, theState);
			return proxy;
		};
	}

	/**
	 * Provides implementation for removing associated data value returning a void return type.
	 *
	 * @param associatedDataName name of the associated data to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeAssociatedDataWithVoidResult(
		@Nonnull String associatedDataName
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			removeAssociatedData(associatedDataName, theState);
			return null;
		};
	}

	/**
	 * Provides implementation for removing associated data value returning the proxy object return type allowing to create
	 * a builder pattern in the model objects.
	 *
	 * @param associatedDataName name of the associated data to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeAssociatedDataWithValueCollectionResult(
		@Nonnull String associatedDataName,
		@Nonnull Class<?> schemaType,
		@Nonnull BiFunction<Serializable, ReflectionLookup, Serializable> converter
	) {
		Assert.isTrue(
			schemaType.isArray(),
			" associatedData `" + associatedDataName + "` must be an array in order collection could be returned!"
		);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object[] removedArray = (Object[]) removeAssociatedDataAndReturnIt(associatedDataName, theState);
			return Arrays.stream(removedArray).map(it -> converter.apply((Serializable) it, theState.getReflectionLookup())).toList();
		};
	}

	/**
	 * Provides implementation for removing associated data value returning the proxy object return type allowing to create
	 * a builder pattern in the model objects.
	 *
	 * @param associatedDataName name of the associated data to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeAssociatedDataWithComplexDataObjectCollectionResult(
		@Nonnull String associatedDataName,
		@Nonnull BiFunction<Serializable, ReflectionLookup, Serializable> converter
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ComplexDataObject removedValue = (ComplexDataObject) removeLocalizedAssociatedDataAndReturnIt(associatedDataName, args, theState);
			final Serializable result = converter.apply(removedValue, theState.getReflectionLookup());
			return result.getClass().isArray() ? Arrays.stream((Object[]) result).toList() : List.of(result);
		};
	}

	/**
	 * Provides implementation for removing associated data value returning the proxy object return type allowing to create
	 * a builder pattern in the model objects.
	 *
	 * @param associatedDataName name of the associated data to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeAssociatedDataWithValueResult(
		@Nonnull String associatedDataName,
		@Nonnull BiFunction<Serializable, ReflectionLookup, Serializable> converter
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> converter.apply(
			removeAssociatedDataAndReturnIt(associatedDataName, theState),
			theState.getReflectionLookup()
		);
	}

	/**
	 * Removes  associated data from the entity.
	 * @param associatedDataName name of the associated data to be set
	 * @param theState proxy state
	 */
	private static void removeAssociatedData(
		@Nonnull String associatedDataName,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.getEntityBuilder();
		entityBuilder.removeAssociatedData(associatedDataName);
	}

	/**
	 * Removes  associated data from the entity and returns its value.
	 * @param associatedDataName name of the associated data to be set
	 * @param theState proxy state
	 */
	@Nullable
	private static Serializable removeAssociatedDataAndReturnIt(
		@Nonnull String associatedDataName,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.getEntityBuilder();
		final Serializable associatedDataToRemove = entityBuilder.getAssociatedData(associatedDataName);
		if (associatedDataToRemove != null) {
			entityBuilder.removeAssociatedData(associatedDataName);
			return associatedDataToRemove;
		} else {
			return null;
		}
	}

}
