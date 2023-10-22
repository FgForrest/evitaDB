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
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.dataType.ComplexDataObject;
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
import java.util.function.Function;

import static io.evitadb.api.proxy.impl.entity.GetAssociatedDataMethodClassifier.getAssociatedDataSchema;

/**
 * Identifies methods that are used to set entity primary key from an entity and provides their implementation.
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
					// now we need to identify the argument type
					final Parameter valueParameter = method.getParameters()[valueParameterPosition];
					// prepare the conversion function
					final Function<Serializable, Serializable> converterFct = ComplexDataObject.class.equals(associatedDataSchema.getPlainType()) ?
						Function.identity() : it -> EvitaDataTypes.toTargetType(it, associatedDataSchema.getPlainType());
					
					if (associatedDataSchema.isLocalized()) {
						Assert.isTrue(localeParameterPosition.isPresent(), "localized associated data `" + associatedDataSchema.getName() + "` must have a locale parameter!");
						if (Collection.class.isAssignableFrom(valueParameter.getType())) {
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
					} else {
						Assert.isTrue(
							method.getParameterCount() == 1,
							"Non-localized associated data `" + associatedDataSchema.getName() + "` must not have a locale parameter!"
						);

						if (Collection.class.isAssignableFrom(valueParameter.getType())) {
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
		);
	}

	/**
	 * Provides implementation for setting associated data value from a single value returning a void return type.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param associatedDataName name of the associatedData to be set
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
	 * @param associatedDataName name of the associatedData to be set
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
	 * @param associatedDataName name of the associatedData to be set
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
						.map(it -> valueConverter.apply((Serializable) value))
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
	 * @param associatedDataName name of the associatedData to be set
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
						.map(it -> valueConverter.apply((Serializable) value))
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
	 * @param associatedDataName name of the associatedData to be set
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
	 * @param associatedDataName name of the associatedData to be set
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
	 * @param associatedDataName name of the associatedData to be set
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
				entityBuilder.setAssociatedData(
					associatedDataName, locale,
					((Collection) value).stream()
						.map(it -> valueConverter.apply((Serializable) value))
						.toArray(cnt -> Array.newInstance(plainType, cnt))
				);
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
	 * @param associatedDataName name of the associatedData to be set
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

}
