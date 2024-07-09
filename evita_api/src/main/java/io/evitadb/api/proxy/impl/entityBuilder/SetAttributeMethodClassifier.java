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

package io.evitadb.api.proxy.impl.entityBuilder;

import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;
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
import java.util.Locale;

import static io.evitadb.api.proxy.impl.MethodArgumentsParser.parseArguments;
import static io.evitadb.api.proxy.impl.entity.GetAttributeMethodClassifier.getAttributeSchema;

/**
 * Identifies methods that are used to set entity attributes into an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SetAttributeMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final SetAttributeMethodClassifier INSTANCE = new SetAttributeMethodClassifier();

	/**
	 * Provides implementation for setting attribute value from a single value returning a void return type.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param attributeName          name of the attribute to be set
	 * @param plainType              expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setAttributeAsValueWithVoidResult(
		int valueParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setAttributeAsValue(attributeName, plainType, theState, args[valueParameterPosition]);
			return null;
		};
	}

	/**
	 * Provides implementation for setting attribute value from a single value returning a proxy object return type
	 * allowing to create a builder pattern in the model objects.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param attributeName          name of the attribute to be set
	 * @param plainType              expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setAttributeAsValueWithBuilderResult(
		int valueParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setAttributeAsValue(attributeName, plainType, theState, args[valueParameterPosition]);
			return proxy;
		};
	}

	/**
	 * Sets the value of the specified attribute using a single value.
	 *
	 * @param attributeName The name of the associated data to be set.
	 * @param plainType     The expected type of the associated data.
	 * @param theState      The proxy state.
	 * @param value         The value to be set.
	 */
	private static void setAttributeAsValue(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType,
		@Nonnull SealedEntityProxyState theState,
		@Nullable Object value
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		if (value == null) {
			entityBuilder.removeAttribute(attributeName);
		} else {
			entityBuilder.setAttribute(
				attributeName,
				EvitaDataTypes.toTargetType((Serializable) value, plainType)
			);
		}
	}

	/**
	 * Provides implementation for setting attribute array value from a collection value returning a void return type.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param attributeName          name of the attribute to be set
	 * @param plainType              expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setAttributeAsCollectionWithVoidResult(
		int valueParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setAttributeAsCollection(attributeName, plainType, theState, args[valueParameterPosition]);
			return null;
		};
	}

	/**
	 * Provides implementation for setting attribute array value from a collection value returning a proxy object
	 * return type allowing to create a builder pattern in the model objects.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param attributeName          name of the attribute to be set
	 * @param plainType              expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setAttributeAsCollectionWithBuilderResult(
		int valueParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setAttributeAsCollection(attributeName, plainType, theState, args[valueParameterPosition]);
			return proxy;
		};
	}

	/**
	 * Sets the value of the specified attribute using an array value.
	 *
	 * @param attributeName The name of the attribute to be set.
	 * @param plainType     The expected type of the attribute.
	 * @param theState      The proxy state.
	 * @param value         The value to be set.
	 */
	private static void setAttributeAsCollection(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType,
		@Nonnull SealedEntityProxyState theState,
		@Nullable Object value
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		if (value == null) {
			entityBuilder.removeAttribute(attributeName);
		} else {
			//noinspection unchecked,rawtypes
			entityBuilder.setAttribute(
				attributeName,
				((Collection) value).stream()
					.map(it -> EvitaDataTypes.toTargetType((Serializable) it, plainType))
					.toArray(cnt -> Array.newInstance(plainType, cnt))
			);
		}
	}

	/**
	 * Provides implementation for setting localized attribute value from a single value returning a void return type.
	 *
	 * @param valueParameterPosition  index of the value parameter among method parameters
	 * @param localeParameterPosition index of the {@link Locale} parameter among method parameters
	 * @param attributeName           name of the attribute to be set
	 * @param plainType               expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setLocalizedAttributeAsValueWithVoidResult(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setLocalizedAttributeAsValue(valueParameterPosition, localeParameterPosition, attributeName, plainType, args, theState);
			return null;
		};
	}

	/**
	 * Provides implementation for setting localized attribute value from a single value returning the proxy object
	 * return type allowing to create a builder pattern in the model objects.
	 *
	 * @param valueParameterPosition  index of the value parameter among method parameters
	 * @param localeParameterPosition index of the {@link Locale} parameter among method parameters
	 * @param attributeName           name of the attribute to be set
	 * @param plainType               expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setLocalizedAttributeAsValueWithBuilderResult(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setLocalizedAttributeAsValue(valueParameterPosition, localeParameterPosition, attributeName, plainType, args, theState);
			return proxy;
		};
	}

	/**
	 * Sets the localized attribute value from a single value.
	 *
	 * @param valueParameterPosition  The index of the value parameter among the method parameters
	 * @param localeParameterPosition The index of the Locale parameter among the method parameters
	 * @param attributeName           The name of the attribute to be set.
	 * @param plainType               The expected type of the attribute.
	 * @param args                    The array of method arguments
	 * @param theState                The SealedEntityProxyState object
	 */
	private static void setLocalizedAttributeAsValue(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final Object value = args[valueParameterPosition];
		final Locale locale = (Locale) args[localeParameterPosition];
		Assert.notNull(locale, "Locale must not be null!");
		final EntityBuilder entityBuilder = theState.entityBuilder();
		if (value == null) {
			entityBuilder.removeAttribute(attributeName);
		} else {
			entityBuilder.setAttribute(
				attributeName, locale,
				EvitaDataTypes.toTargetType((Serializable) value, plainType)
			);
		}
	}

	/**
	 * Provides implementation for setting localized attribute array value from a collection value returning a void
	 * return type.
	 *
	 * @param valueParameterPosition  index of the value parameter among method parameters
	 * @param localeParameterPosition index of the {@link Locale} parameter among method parameters
	 * @param attributeName           name of the attribute to be set
	 * @param plainType               expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setLocalizedAttributeAsCollectionWithVoidResult(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setLocalizedAttributeAsCollection(valueParameterPosition, localeParameterPosition, attributeName, plainType, args, theState);
			return null;
		};
	}

	/**
	 * Provides implementation for setting localized attribute array value from a collection value returning the proxy
	 * object return type allowing to create a builder pattern in the model objects.
	 *
	 * @param valueParameterPosition  index of the value parameter among method parameters
	 * @param localeParameterPosition index of the {@link Locale} parameter among method parameters
	 * @param attributeName           name of the attribute to be set
	 * @param plainType               expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setLocalizedAttributeAsCollectionWithBuilderResult(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setLocalizedAttributeAsCollection(valueParameterPosition, localeParameterPosition, attributeName, plainType, args, theState);
			return proxy;
		};
	}

	/**
	 * Sets the localized associated data as a collection value and returns the proxy object.
	 *
	 * @param valueParameterPosition  The index of the value parameter among the method parameters.
	 * @param localeParameterPosition The index of the Locale parameter among the method parameters.
	 * @param attributeName The name of the attribute to be set.
	 * @param plainType     The expected type of the attribute.
	 * @param plainType               The expected type of the attribute.
	 * @param args                    The arguments passed to the method.
	 * @param theState                The SealedEntityProxyState object containing the entity state.
	 */
	private static void setLocalizedAttributeAsCollection(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final Object value = args[valueParameterPosition];
		final Locale locale = (Locale) args[localeParameterPosition];
		Assert.notNull(locale, "Locale must not be null!");
		final EntityBuilder entityBuilder = theState.entityBuilder();
		if (value == null) {
			entityBuilder.removeAttribute(attributeName, locale);
		} else {
			//noinspection unchecked,rawtypes
			entityBuilder.setAttribute(
				attributeName, locale,
				((Collection) value).stream()
					.map(it -> EvitaDataTypes.toTargetType((Serializable) it, plainType))
					.toArray(cnt -> Array.newInstance(plainType, cnt))
			);
		}
	}

	/**
	 * Provides implementation for removing localized attribute value returning the proxy object return type allowing
	 * to create a builder pattern in the model objects.
	 *
	 * @param attributeName name of the attribute to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeLocalizedAttributeWithBuilderResult(
		@Nonnull String attributeName
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			removeLocalizedAttribute(attributeName, args, theState);
			return proxy;
		};
	}

	/**
	 * Provides implementation for removing localized attribute value returning a void return type.
	 *
	 * @param attributeName name of the attribute to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeLocalizedAttributeWithVoidResult(
		@Nonnull String attributeName
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			removeLocalizedAttribute(attributeName, args, theState);
			return null;
		};
	}

	/**
	 * Provides implementation for removing localized attribute value returning the proxy object return type allowing
	 * to create a builder pattern in the model objects.
	 *
	 * @param attributeName name of the attribute to be set
	 * @return implementation of the method call
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeLocalizedAttributeWithValueCollectionResult(
		@Nonnull String attributeName,
		@Nonnull Class<?> schemaType,
		@Nonnull Class plainType
	) {
		Assert.isTrue(
			schemaType.isArray(),
			"Localized attribute `" + attributeName + "` must be an array in order collection could be returned!"
		);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object[] removedArray = (Object[]) removeLocalizedAttributeAndReturnIt(attributeName, args, theState);
			return Arrays.stream(removedArray).map(it -> EvitaDataTypes.toTargetType((Serializable) it, plainType)).toList();
		};
	}

	/**
	 * Provides implementation for removing localized attribute value returning the proxy object return type allowing
	 * to create a builder pattern in the model objects.
	 *
	 * @param attributeName name of the attribute to be set
	 * @return implementation of the method call
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeLocalizedAttributeWithValueResult(
		@Nonnull String attributeName,
		@Nonnull Class plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> EvitaDataTypes.toTargetType(
			removeLocalizedAttributeAndReturnIt(attributeName, args, theState), plainType
		);
	}

	/**
	 * Removes localized attribute from the entity.
	 *
	 * @param attributeName name of the attribute to be set
	 * @param args          method arguments
	 * @param theState      proxy state
	 */
	private static void removeLocalizedAttribute(
		@Nonnull String attributeName,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final Locale locale = (Locale) args[0];
		Assert.notNull(locale, "Locale must not be null!");
		final EntityBuilder entityBuilder = theState.entityBuilder();
		entityBuilder.removeAttribute(attributeName, locale);
	}

	/**
	 * Removes localized attribute from the entity and returns its value.
	 *
	 * @param attributeName name of the attribute to be set
	 * @param args          method arguments
	 * @param theState      proxy state
	 */
	@Nullable
	private static Serializable removeLocalizedAttributeAndReturnIt(
		@Nonnull String attributeName,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final Locale locale = (Locale) args[0];
		Assert.notNull(locale, "Locale must not be null!");
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final Serializable attributeToRemove = entityBuilder.getAttribute(attributeName, locale);
		if (attributeToRemove != null) {
			entityBuilder.removeAttribute(attributeName, locale);
			return attributeToRemove;
		} else {
			return null;
		}
	}

	/**
	 * Provides implementation for removing attribute value returning the proxy object return type allowing to create
	 * a builder pattern in the model objects.
	 *
	 * @param attributeName name of the attribute to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeAttributeWithBuilderResult(
		@Nonnull String attributeName
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			removeAttribute(attributeName, theState);
			return proxy;
		};
	}

	/**
	 * Provides implementation for removing attribute value returning a void return type.
	 *
	 * @param attributeName name of the attribute to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeAttributeWithVoidResult(
		@Nonnull String attributeName
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			removeAttribute(attributeName, theState);
			return null;
		};
	}

	/**
	 * Provides implementation for removing attribute value returning the proxy object return type allowing to create
	 * a builder pattern in the model objects.
	 *
	 * @param attributeName name of the attribute to be set
	 * @return implementation of the method call
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeAttributeWithValueCollectionResult(
		@Nonnull String attributeName,
		@Nonnull Class<?> schemaType,
		@Nonnull Class plainType
	) {
		Assert.isTrue(
			schemaType.isArray(),
			" attribute `" + attributeName + "` must be an array in order collection could be returned!"
		);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object[] removedArray = (Object[]) removeAttributeAndReturnIt(attributeName, theState);
			return Arrays.stream(removedArray).map(it -> EvitaDataTypes.toTargetType((Serializable) it, plainType)).toList();
		};
	}

	/**
	 * Provides implementation for removing attribute value returning the proxy object return type allowing to create
	 * a builder pattern in the model objects.
	 *
	 * @param attributeName name of the attribute to be set
	 * @return implementation of the method call
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeAttributeWithValueResult(
		@Nonnull String attributeName,
		@Nonnull Class plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> EvitaDataTypes.toTargetType(
			removeAttributeAndReturnIt(attributeName, theState), plainType
		);
	}

	/**
	 * Removes  attribute from the entity.
	 *
	 * @param attributeName name of the attribute to be set
	 * @param theState      proxy state
	 */
	private static void removeAttribute(
		@Nonnull String attributeName,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		entityBuilder.removeAttribute(attributeName);
	}

	/**
	 * Removes  attribute from the entity and returns its value.
	 *
	 * @param attributeName name of the attribute to be set
	 * @param theState      proxy state
	 */
	@Nullable
	private static Serializable removeAttributeAndReturnIt(
		@Nonnull String attributeName,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final Serializable attributeToRemove = entityBuilder.getAttribute(attributeName);
		if (attributeToRemove != null) {
			entityBuilder.removeAttribute(attributeName);
			return attributeToRemove;
		} else {
			return null;
		}
	}

	public SetAttributeMethodClassifier() {
		super(
			"setAttribute",
			(method, proxyState) -> parseArguments(
				method,
				argType -> (EvitaDataTypes.isSupportedTypeOrItsArrayOrEnum(argType)) || EvitaDataTypes.isEnumOrArrayOfEnums(argType) || Collection.class.isAssignableFrom(argType)
			)
				.map(
					parsedArguments -> {
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
							final String attributeName = attributeSchema.getName();
							// LOCALIZED ATTRIBUTE
							if (attributeSchema.isLocalized()) {
								Assert.isTrue(
									parsedArguments.localeParameterPosition().isPresent(),
									"Localized attribute `" + attributeSchema.getName() + "` must have a locale parameter!"
								);
								if (method.isAnnotationPresent(RemoveWhenExists.class)) {
									// REMOVAL
									if (method.getReturnType().equals(proxyState.getProxyClass())) {
										return removeLocalizedAttributeWithBuilderResult(attributeName);
									} else if (method.getReturnType().equals(void.class)) {
										return removeLocalizedAttributeWithVoidResult(attributeName);
									} else if (Collection.class.isAssignableFrom(method.getReturnType())) {
										return removeLocalizedAttributeWithValueCollectionResult(
											attributeName,
											attributeSchema.getType(),
											GenericsUtils.getMethodReturnType(proxyState.getProxyClass(), method)
										);
									} else {
										return removeLocalizedAttributeWithValueResult(attributeName, method.getReturnType());
									}
								} else {
									// SETTING VALUE
									final Parameter valueParameter = method.getParameters()[parsedArguments.valueParameterPosition()];
									if (Collection.class.isAssignableFrom(valueParameter.getType())) {
										if (method.getReturnType().equals(proxyState.getProxyClass())) {
											return setLocalizedAttributeAsCollectionWithBuilderResult(
												parsedArguments.valueParameterPosition(),
												parsedArguments.localeParameterPosition().getAsInt(),
												attributeName,
												attributeSchema.getPlainType()
											);
										} else {
											return setLocalizedAttributeAsCollectionWithVoidResult(
												parsedArguments.valueParameterPosition(),
												parsedArguments.localeParameterPosition().getAsInt(),
												attributeName,
												attributeSchema.getPlainType()
											);
										}
									} else {
										if (method.getReturnType().equals(proxyState.getProxyClass())) {
											return setLocalizedAttributeAsValueWithBuilderResult(
												parsedArguments.valueParameterPosition(),
												parsedArguments.localeParameterPosition().getAsInt(),
												attributeName,
												attributeSchema.getPlainType()
											);
										} else {
											return setLocalizedAttributeAsValueWithVoidResult(
												parsedArguments.valueParameterPosition(),
												parsedArguments.localeParameterPosition().getAsInt(),
												attributeName,
												attributeSchema.getPlainType()
											);
										}
									}
								}
							} else {
								if (method.isAnnotationPresent(RemoveWhenExists.class)) {
									// REMOVAL
									Assert.isTrue(
										method.getParameterCount() == 0,
										"Non-localized attribute `" + attributeSchema.getName() + "` must not have a locale parameter!"
									);

									if (method.getReturnType().equals(proxyState.getProxyClass())) {
										return removeAttributeWithBuilderResult(attributeName);
									} else if (method.getReturnType().equals(void.class)) {
										return removeAttributeWithVoidResult(attributeName);
									} else if (Collection.class.isAssignableFrom(method.getReturnType())) {
										return removeAttributeWithValueCollectionResult(
											attributeName,
											attributeSchema.getType(),
											GenericsUtils.getMethodReturnType(proxyState.getProxyClass(), method)
										);
									} else {
										return removeAttributeWithValueResult(attributeName, method.getReturnType());
									}
								} else {
									// SETTING VALUE
									final Parameter valueParameter = method.getParameters()[parsedArguments.valueParameterPosition()];
									Assert.isTrue(
										method.getParameterCount() == 1,
										"Non-localized attribute `" + attributeSchema.getName() + "` must not have a locale parameter!"
									);

									if (Collection.class.isAssignableFrom(valueParameter.getType())) {
										if (method.getReturnType().equals(proxyState.getProxyClass())) {
											return setAttributeAsCollectionWithBuilderResult(
												parsedArguments.valueParameterPosition(),
												attributeName,
												attributeSchema.getPlainType()
											);
										} else {
											return setAttributeAsCollectionWithVoidResult(
												parsedArguments.valueParameterPosition(),
												attributeName,
												attributeSchema.getPlainType()
											);
										}
									} else {
										if (method.getReturnType().equals(proxyState.getProxyClass())) {
											return setAttributeAsValueWithBuilderResult(
												parsedArguments.valueParameterPosition(),
												attributeName,
												attributeSchema.getPlainType()
											);
										} else {
											return setAttributeAsValueWithVoidResult(
												parsedArguments.valueParameterPosition(),
												attributeName,
												attributeSchema.getPlainType()
											);
										}
									}
								}
							}
						}
					}
				)
				.orElse(null)
		);
	}

}
