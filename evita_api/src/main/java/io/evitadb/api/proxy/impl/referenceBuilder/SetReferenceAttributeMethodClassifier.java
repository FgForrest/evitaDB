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

package io.evitadb.api.proxy.impl.referenceBuilder;

import io.evitadb.api.proxy.impl.SealedEntityReferenceProxyState;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
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
import java.util.OptionalInt;

import static io.evitadb.api.proxy.impl.reference.GetReferenceAttributeMethodClassifier.getAttributeSchema;

/**
 * Identifies methods that are used to set entity reference attributes into an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SetReferenceAttributeMethodClassifier extends DirectMethodClassification<Object, SealedEntityReferenceProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final SetReferenceAttributeMethodClassifier INSTANCE = new SetReferenceAttributeMethodClassifier();

	public SetReferenceAttributeMethodClassifier() {
		super(
			"setReferenceAttribute",
			(method, proxyState) -> {
				final int valueParameterPosition;
				final OptionalInt localeParameterPosition;
				// We only want to handle methods with exactly one parameter, or two parameters of which one is Locale
				final Class<?>[] parameterTypes = method.getParameterTypes();
				if (method.getParameterCount() == 1) {
					if (((EvitaDataTypes.isSupportedTypeOrItsArrayOrEnum(parameterTypes[0])) || Collection.class.isAssignableFrom(parameterTypes[0])) &&
						!Locale.class.isAssignableFrom(parameterTypes[0])) {
						valueParameterPosition = 0;
						localeParameterPosition = OptionalInt.empty();
					} else if (Locale.class.isAssignableFrom(parameterTypes[0])) {
						localeParameterPosition = OptionalInt.of(0);
						valueParameterPosition = -1;
					} else {
						localeParameterPosition = OptionalInt.empty();
						valueParameterPosition = -1;
					}
				} else if ((method.getParameterCount() == 2 &&
					Arrays.stream(parameterTypes)
						.allMatch(it -> Locale.class.isAssignableFrom(it) || Collection.class.isAssignableFrom(it) || EvitaDataTypes.isSupportedTypeOrItsArrayOrEnum(it)))
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
					final String attributeName = attributeSchema.getName();
					if (attributeSchema.isLocalized()) {
						Assert.isTrue(
							localeParameterPosition.isPresent(),
							"Localized attribute `" + attributeSchema.getName() + "` must have a locale parameter!"
						);
						if (method.isAnnotationPresent(RemoveWhenExists.class)) {
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
							// now we need to identify the argument type
							final Parameter valueParameter = method.getParameters()[valueParameterPosition];
							if (Collection.class.isAssignableFrom(valueParameter.getType())) {
								if (method.getReturnType().equals(proxyState.getProxyClass())) {
									return setLocalizedAttributeAsCollectionWithBuilderResult(
										valueParameterPosition, localeParameterPosition.getAsInt(), attributeName, attributeSchema.getPlainType()
									);
								} else {
									return setLocalizedAttributeAsCollectionWithVoidResult(
										valueParameterPosition, localeParameterPosition.getAsInt(), attributeName, attributeSchema.getPlainType()
									);
								}
							} else {
								if (method.getReturnType().equals(proxyState.getProxyClass())) {
									return setLocalizedAttributeAsValueWithBuilderResult(
										valueParameterPosition, localeParameterPosition.getAsInt(), attributeName, attributeSchema.getPlainType()
									);
								} else {
									return setLocalizedAttributeAsValueWithVoidResult(
										valueParameterPosition, localeParameterPosition.getAsInt(), attributeName, attributeSchema.getPlainType()
									);
								}
							}
						}
					} else {
						if (method.isAnnotationPresent(RemoveWhenExists.class)) {
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
							// now we need to identify the argument type
							final Parameter valueParameter = method.getParameters()[valueParameterPosition];
							Assert.isTrue(
								method.getParameterCount() == 1,
								"Non-localized attribute `" + attributeSchema.getName() + "` must not have a locale parameter!"
							);

							if (Collection.class.isAssignableFrom(valueParameter.getType())) {
								if (method.getReturnType().equals(proxyState.getProxyClass())) {
									return setAttributeAsCollectionWithBuilderResult(
										valueParameterPosition, attributeName, attributeSchema.getPlainType()
									);
								} else {
									return setAttributeAsCollectionWithVoidResult(
										valueParameterPosition, attributeName, attributeSchema.getPlainType()
									);
								}
							} else {
								if (method.getReturnType().equals(proxyState.getProxyClass())) {
									return setAttributeAsValueWithBuilderResult(
										valueParameterPosition, attributeName, attributeSchema.getPlainType()
									);
								} else {
									return setAttributeAsValueWithVoidResult(
										valueParameterPosition, attributeName, attributeSchema.getPlainType()
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
	 * Provides implementation for setting attribute value from a single value returning a void return type.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param attributeName          name of the attribute to be set
	 * @param plainType              expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setAttributeAsValueWithVoidResult(
		int valueParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			if (value == null) {
				referenceBuilder.removeAttribute(attributeName);
			} else {
				referenceBuilder.setAttribute(
					attributeName,
					EvitaDataTypes.toTargetType((Serializable) value, plainType)
				);
			}
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setAttributeAsValueWithBuilderResult(
		int valueParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			if (value == null) {
				referenceBuilder.removeAttribute(attributeName);
			} else {
				referenceBuilder.setAttribute(
					attributeName,
					EvitaDataTypes.toTargetType((Serializable) value, plainType)
				);
			}
			return proxy;
		};
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setAttributeAsCollectionWithVoidResult(
		int valueParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			if (value == null) {
				referenceBuilder.removeAttribute(attributeName);
			} else {
				//noinspection unchecked,rawtypes
				referenceBuilder.setAttribute(
					attributeName,
					((Collection) value).stream()
						.map(it -> EvitaDataTypes.toTargetType((Serializable) it, plainType))
						.toArray(cnt -> Array.newInstance(plainType, cnt))
				);
			}
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setAttributeAsCollectionWithBuilderResult(
		int valueParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			if (value == null) {
				referenceBuilder.removeAttribute(attributeName);
			} else {
				//noinspection unchecked,rawtypes
				referenceBuilder.setAttribute(
					attributeName,
					((Collection) value).stream()
						.map(it -> EvitaDataTypes.toTargetType((Serializable) it, plainType))
						.toArray(cnt -> Array.newInstance(plainType, cnt))
				);
			}
			return proxy;
		};
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setLocalizedAttributeAsValueWithVoidResult(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final Locale locale = (Locale) args[localeParameterPosition];
			Assert.notNull(locale, "Locale must not be null!");
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			if (value == null) {
				referenceBuilder.removeAttribute(attributeName);
			} else {
				referenceBuilder.setAttribute(
					attributeName, locale,
					EvitaDataTypes.toTargetType((Serializable) value, plainType)
				);
			}
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setLocalizedAttributeAsValueWithBuilderResult(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final Locale locale = (Locale) args[localeParameterPosition];
			Assert.notNull(locale, "Locale must not be null!");
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			if (value == null) {
				referenceBuilder.removeAttribute(attributeName);
			} else {
				referenceBuilder.setAttribute(
					attributeName, locale,
					EvitaDataTypes.toTargetType((Serializable) value, plainType)
				);
			}
			return proxy;
		};
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setLocalizedAttributeAsCollectionWithVoidResult(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final Locale locale = (Locale) args[localeParameterPosition];
			Assert.notNull(locale, "Locale must not be null!");
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			if (value == null) {
				referenceBuilder.removeAttribute(attributeName, locale);
			} else {
				//noinspection unchecked,rawtypes
				referenceBuilder.setAttribute(
					attributeName, locale,
					((Collection) value).stream()
						.map(it -> EvitaDataTypes.toTargetType((Serializable) it, plainType))
						.toArray(cnt -> Array.newInstance(plainType, cnt))
				);
			}
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setLocalizedAttributeAsCollectionWithBuilderResult(
		int valueParameterPosition,
		int localeParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final Locale locale = (Locale) args[localeParameterPosition];
			Assert.notNull(locale, "Locale must not be null!");
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			if (value == null) {
				referenceBuilder.removeAttribute(attributeName, locale);
			} else {
				//noinspection unchecked,rawtypes
				referenceBuilder.setAttribute(
					attributeName, locale,
					((Collection) value).stream()
						.map(it -> EvitaDataTypes.toTargetType((Serializable) it, plainType))
						.toArray(cnt -> Array.newInstance(plainType, cnt))
				);
			}
			return proxy;
		};
	}

	/**
	 * Provides implementation for removing localized attribute value returning the proxy object return type allowing
	 * to create a builder pattern in the model objects.
	 *
	 * @param attributeName name of the attribute to be set
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> removeLocalizedAttributeWithBuilderResult(
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> removeLocalizedAttributeWithVoidResult(
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> removeLocalizedAttributeWithValueCollectionResult(
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> removeLocalizedAttributeWithValueResult(
		@Nonnull String attributeName,
		@Nonnull Class plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> EvitaDataTypes.toTargetType(
			removeLocalizedAttributeAndReturnIt(attributeName, args, theState), plainType
		);
	}

	/**
	 * Removes localized attribute from the entity.
	 * @param attributeName name of the attribute to be set
	 * @param args method arguments
	 * @param theState proxy state
	 */
	private static void removeLocalizedAttribute(
		@Nonnull String attributeName,
		@Nonnull Object[] args,
		@Nonnull SealedEntityReferenceProxyState theState
	) {
		final Locale locale = (Locale) args[0];
		Assert.notNull(locale, "Locale must not be null!");
		final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
		referenceBuilder.removeAttribute(attributeName, locale);
	}

	/**
	 * Removes localized attribute from the entity and returns its value.
	 * @param attributeName name of the attribute to be set
	 * @param args method arguments
	 * @param theState proxy state
	 */
	@Nullable
	private static Serializable removeLocalizedAttributeAndReturnIt(
		@Nonnull String attributeName,
		@Nonnull Object[] args,
		@Nonnull SealedEntityReferenceProxyState theState
	) {
		final Locale locale = (Locale) args[0];
		Assert.notNull(locale, "Locale must not be null!");
		final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
		final Serializable attributeToRemove = referenceBuilder.getAttribute(attributeName, locale);
		if (attributeToRemove != null) {
			referenceBuilder.removeAttribute(attributeName, locale);
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> removeAttributeWithBuilderResult(
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> removeAttributeWithVoidResult(
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> removeAttributeWithValueCollectionResult(
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> removeAttributeWithValueResult(
		@Nonnull String attributeName,
		@Nonnull Class plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> EvitaDataTypes.toTargetType(
			removeAttributeAndReturnIt(attributeName, theState), plainType
		);
	}

	/**
	 * Removes  attribute from the entity.
	 * @param attributeName name of the attribute to be set
	 * @param theState proxy state
	 */
	private static void removeAttribute(
		@Nonnull String attributeName,
		@Nonnull SealedEntityReferenceProxyState theState
	) {
		final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
		referenceBuilder.removeAttribute(attributeName);
	}

	/**
	 * Removes  attribute from the entity and returns its value.
	 * @param attributeName name of the attribute to be set
	 * @param theState proxy state
	 */
	@Nullable
	private static Serializable removeAttributeAndReturnIt(
		@Nonnull String attributeName,
		@Nonnull SealedEntityReferenceProxyState theState
	) {
		final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
		final Serializable attributeToRemove = referenceBuilder.getAttribute(attributeName);
		if (attributeToRemove != null) {
			referenceBuilder.removeAttribute(attributeName);
			return attributeToRemove;
		} else {
			return null;
		}
	}

}
