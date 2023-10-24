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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.OptionalInt;

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

	public SetAttributeMethodClassifier() {
		super(
			"setAttribute",
			(method, proxyState) -> {
				final int valueParameterPosition;
				final OptionalInt localeParameterPosition;
				// We only want to handle methods with exactly one parameter, or two parameters of which one is Locale
				if (method.getParameterCount() == 1 && Serializable.class.isAssignableFrom(method.getParameterTypes()[0]) && !Locale.class.isAssignableFrom(method.getParameterTypes()[0])) {
					valueParameterPosition = 0;
					localeParameterPosition = OptionalInt.empty();
				} else if ((method.getParameterCount() == 2 && Arrays.stream(method.getParameterTypes()).allMatch(Serializable.class::isAssignableFrom))) {
					int lp = -1;
					for (int i = 0; i < method.getParameterTypes().length; i++) {
						 if (Locale.class.isAssignableFrom(method.getParameterTypes()[i])) {
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
					final String attributeName = attributeSchema.getName();
					// now we need to identify the argument type
					final Parameter valueParameter = method.getParameters()[valueParameterPosition];
					if (attributeSchema.isLocalized()) {
						Assert.isTrue(localeParameterPosition.isPresent(), "Localized attribute `" + attributeSchema.getName() + "` must have a locale parameter!");
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
					} else {
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
		);
	}

	/**
	 * Provides implementation for setting attribute value from a single value returning a void return type.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param attributeName name of the attribute to be set
	 * @param plainType expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setAttributeAsValueWithVoidResult(
		int valueParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			if (value == null) {
				entityBuilder.removeAttribute(attributeName);
			} else {
				entityBuilder.setAttribute(
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
	 * @param attributeName name of the attribute to be set
	 * @param plainType expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setAttributeAsValueWithBuilderResult(
		int valueParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			if (value == null) {
				entityBuilder.removeAttribute(attributeName);
			} else {
				entityBuilder.setAttribute(
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
	 * @param attributeName name of the attribute to be set
	 * @param plainType expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setAttributeAsCollectionWithVoidResult(
		int valueParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
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
			return null;
		};
	}

	/**
	 * Provides implementation for setting attribute array value from a collection value returning a proxy object
	 * return type allowing to create a builder pattern in the model objects.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param attributeName name of the attribute to be set
	 * @param plainType expected type of the attribute
	 * @return implementation of the method call
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setAttributeAsCollectionWithBuilderResult(
		int valueParameterPosition,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final Object value = args[valueParameterPosition];
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
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
			return proxy;
		};
	}

	/**
	 * Provides implementation for setting localized attribute value from a single value returning a void return type.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param localeParameterPosition index of the {@link Locale} parameter among method parameters
	 * @param attributeName name of the attribute to be set
	 * @param plainType expected type of the attribute
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
			final Object value = args[valueParameterPosition];
			final Locale locale = (Locale) args[localeParameterPosition];
			Assert.notNull(locale, "Locale must not be null!");
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			if (value == null) {
				entityBuilder.removeAttribute(attributeName);
			} else {
				entityBuilder.setAttribute(
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
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param localeParameterPosition index of the {@link Locale} parameter among method parameters
	 * @param attributeName name of the attribute to be set
	 * @param plainType expected type of the attribute
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
			final Object value = args[valueParameterPosition];
			final Locale locale = (Locale) args[localeParameterPosition];
			Assert.notNull(locale, "Locale must not be null!");
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			if (value == null) {
				entityBuilder.removeAttribute(attributeName);
			} else {
				entityBuilder.setAttribute(
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
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param localeParameterPosition index of the {@link Locale} parameter among method parameters
	 * @param attributeName name of the attribute to be set
	 * @param plainType expected type of the attribute
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
			final Object value = args[valueParameterPosition];
			final Locale locale = (Locale) args[localeParameterPosition];
			Assert.notNull(locale, "Locale must not be null!");
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
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
			return null;
		};
	}

	/**
	 * Provides implementation for setting localized attribute array value from a collection value returning the proxy
	 * object return type allowing to create a builder pattern in the model objects.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param localeParameterPosition index of the {@link Locale} parameter among method parameters
	 * @param attributeName name of the attribute to be set
	 * @param plainType expected type of the attribute
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
			final Object value = args[valueParameterPosition];
			final Locale locale = (Locale) args[localeParameterPosition];
			Assert.notNull(locale, "Locale must not be null!");
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
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
			return proxy;
		};
	}

}
