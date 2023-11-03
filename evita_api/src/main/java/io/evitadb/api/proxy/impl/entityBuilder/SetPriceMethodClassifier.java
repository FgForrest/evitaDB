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

import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.annotation.Price;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;
import one.edee.oss.proxycian.utils.GenericsUtils;
import one.edee.oss.proxycian.utils.GenericsUtils.GenericBundle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * Identifies methods that are used to set entity prices into an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SetPriceMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final SetPriceMethodClassifier INSTANCE = new SetPriceMethodClassifier();

	private static final MethodHandle PRICE_CONSTRUCTOR_HANDLE;
	private static final MethodHandle PRICE_KEY_CONSTRUCTOR_HANDLE;

	static {
		try {
			final Constructor<?> priceConstructor = io.evitadb.api.requestResponse.data.structure.Price.class.getConstructor(
				int.class, String.class, Currency.class, Integer.class,
				BigDecimal.class, BigDecimal.class, BigDecimal.class,
				DateTimeRange.class, boolean.class
			);
			PRICE_CONSTRUCTOR_HANDLE = MethodHandles.lookup().unreflectConstructor(priceConstructor);

			final Constructor<?> priceKey = PriceKey.class.getConstructor(
				int.class, String.class, Currency.class
			);
			PRICE_KEY_CONSTRUCTOR_HANDLE = MethodHandles.lookup().unreflectConstructor(priceKey);
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new IllegalStateException("Unable to initialize PRICE_CONSTRUCTOR_HANDLE!", e);
		}
	}

	/**
	 * Creates invocation handler for setting price inner record handling.
	 *
	 * @param returnType method return type for recognizing builder pattern
	 * @param proxyClass proxy class
	 * @return invocation handler
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setPriceInnerRecordHandling(
		@Nonnull Class<?> returnType,
		@Nonnull Class<?> proxyClass
	) {
		if (returnType.equals(proxyClass)) {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				theState.getEntityBuilder().setPriceInnerRecordHandling((PriceInnerRecordHandling) args[0]);
				return proxy;
			};
		} else {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				theState.getEntityBuilder().setPriceInnerRecordHandling((PriceInnerRecordHandling) args[0]);
				return null;
			};
		}
	}

	/**
	 * Creates invocation handler for setting price.
	 *
	 * @param method     method that is being classified
	 * @param returnType method return type for recognizing builder pattern
	 * @param proxyClass proxy class
	 * @return invocation handler
	 */
	@Nullable
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> upsertPrice(
		@Nonnull Method method,
		@Nonnull Class<?> returnType,
		@Nonnull Class<?> proxyClass,
		@Nonnull Price priceAnnotation
	) {
		return createPriceExtractor(method, proxyClass, priceAnnotation)
			.map(priceExtractor -> {
				final CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> invocationHandler;
				if (returnType.equals(proxyClass)) {
					invocationHandler = (proxy, theMethod, args, theState, invokeSuper) -> {
						final PriceContract thePrice = priceExtractor.apply(args);
						theState.getEntityBuilder().setPrice(thePrice);
						return proxy;
					};
				} else {
					invocationHandler = (proxy, theMethod, args, theState, invokeSuper) -> {
						final PriceContract thePrice = priceExtractor.apply(args);
						theState.getEntityBuilder().setPrice(thePrice);
						return null;
					};
				}
				return invocationHandler;
			})
			.orElse(null);
	}

	/**
	 * Creates invocation handler for setting all prices of the entity.
	 *
	 * @param method     method that is being classified
	 * @param returnType method return type for recognizing builder pattern
	 * @param proxyClass proxy class
	 * @return invocation handler
	 */
	@Nullable
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setPricesAsArray(
		@Nonnull Method method,
		@Nonnull Class<?> returnType,
		@Nonnull Class<?> proxyClass,
		@Nonnull Price priceAnnotation
	) {
		if (!priceAnnotation.priceList().isBlank()) {
			throw new EntityClassInvalidException(
				proxyClass,
				"Unable to set all prices via. method `" + method.toGenericString() + "` because the prices may " +
					"contain different price lists, but the price list is fixed via. annotation."
			);
		}
		if (PriceContract.class.isAssignableFrom(method.getParameterTypes()[0].getComponentType())) {
			if (returnType.equals(proxyClass)) {
				return (proxy, theMethod, args, theState, invokeSuper) -> {
					final EntityBuilder entityBuilder = theState.getEntityBuilder();
					final boolean initialBuilder = entityBuilder instanceof InitialEntityBuilder;
					if (initialBuilder) {
						entityBuilder.removeAllPrices();
					}
					final Object[] thePrices = (Object[]) args[0];
					for (Object thePrice : thePrices) {
						entityBuilder.setPrice((PriceContract) thePrice);
					}
					if (!initialBuilder) {
						entityBuilder.removeAllNonTouchedPrices();
					}
					return proxy;
				};
			} else {
				return (proxy, theMethod, args, theState, invokeSuper) -> {
					final EntityBuilder entityBuilder = theState.getEntityBuilder();
					final boolean initialBuilder = entityBuilder instanceof InitialEntityBuilder;
					if (initialBuilder) {
						entityBuilder.removeAllPrices();
					}
					final Object[] thePrices = (Object[]) args[0];
					for (Object thePrice : thePrices) {
						entityBuilder.setPrice((PriceContract) thePrice);
					}
					if (!initialBuilder) {
						entityBuilder.removeAllNonTouchedPrices();
					}
					return null;
				};
			}
		} else {
			return null;
		}
	}

	/**
	 * Creates invocation handler for setting all prices of the entity.
	 *
	 * @param method     method that is being classified
	 * @param returnType method return type for recognizing builder pattern
	 * @param proxyClass proxy class
	 * @return invocation handler
	 */
	@SuppressWarnings("rawtypes")
	@Nullable
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setPricesAsCollection(
		@Nonnull Method method,
		@Nonnull Class<?> returnType,
		@Nonnull Class<?> proxyClass,
		@Nonnull Price priceAnnotation
	) {
		if (!priceAnnotation.priceList().isBlank()) {
			throw new EntityClassInvalidException(
				proxyClass,
				"Unable to set all prices via. method `" + method.toGenericString() + "` because the prices may " +
					"contain different price lists, but the price list is fixed via. annotation."
			);
		}
		final List<GenericBundle> genericType = GenericsUtils.getGenericType(proxyClass, method.getGenericParameterTypes()[0]);
		if (!genericType.isEmpty() && PriceContract.class.isAssignableFrom(genericType.get(0).getResolvedType())) {
			if (returnType.equals(proxyClass)) {
				return (proxy, theMethod, args, theState, invokeSuper) -> {
					final EntityBuilder entityBuilder = theState.getEntityBuilder();
					final boolean initialBuilder = entityBuilder instanceof InitialEntityBuilder;
					if (initialBuilder) {
						entityBuilder.removeAllPrices();
					}
					final Collection thePrices = (Collection) args[0];
					for (Object thePrice : thePrices) {
						entityBuilder.setPrice((PriceContract) thePrice);
					}
					if (!initialBuilder) {
						entityBuilder.removeAllNonTouchedPrices();
					}
					return proxy;
				};
			} else {
				return (proxy, theMethod, args, theState, invokeSuper) -> {
					final EntityBuilder entityBuilder = theState.getEntityBuilder();
					final Collection thePrices = (Collection) args[0];
					for (Object thePrice : thePrices) {
						entityBuilder.setPrice((PriceContract) thePrice);
					}
					entityBuilder.removeAllNonTouchedPrices();
					return null;
				};
			}
		} else {
			return null;
		}
	}

	/**
	 * Creates function that converts method arguments to constructor call of
	 * {@link io.evitadb.api.requestResponse.data.structure.Price#Price(int, String, Currency, Integer, BigDecimal, BigDecimal, BigDecimal, DateTimeRange, boolean)}.
	 *
	 * @param method     method that is being classified
	 * @param proxyClass proxy class
	 * @return function that converts method arguments to constructor call of the {@link io.evitadb.api.requestResponse.data.structure.Price} record
	 */
	@Nonnull
	private static Optional<Function<Object[], PriceContract>> createPriceExtractor(
		@Nonnull Method method,
		@Nonnull Class<?> proxyClass,
		@Nonnull Price priceAnnotation
	) {
		final String fixedPriceList = priceAnnotation.priceList();
		final Function<Object[], PriceContract> priceExtractor;
		final int parameterCount = method.getParameterCount();
		if (parameterCount == 1 && PriceContract.class.isAssignableFrom(method.getParameterTypes()[0])) {
			priceExtractor = args -> (PriceContract) args[0];
		} else if (parameterCount > 0) {
			final List<RecognizedParameter> recognizedParameters = new ArrayList<>(9);
			final Parameter[] methodParameters = method.getParameters();
			for (int i = 0; i < methodParameters.length; i++) {
				final Parameter parameter = methodParameters[i];
				final int argumentIndex = i;
				final int recognizedBefore = recognizedParameters.size();
				if (NumberUtils.isIntConvertibleNumber(parameter.getType())) {
					switch (parameter.getName()) {
						case "id" ->
							recognizedParameters.add(new RecognizedParameter(0, args -> EvitaDataTypes.toTargetType((Serializable) args[argumentIndex], int.class)));
						case "priceId" ->
							recognizedParameters.add(new RecognizedParameter(0, args -> EvitaDataTypes.toTargetType((Serializable) args[argumentIndex], int.class)));
						case "innerRecordId" ->
							recognizedParameters.add(new RecognizedParameter(3, args -> EvitaDataTypes.toTargetType((Serializable) args[argumentIndex], int.class)));
						case "taxRate" ->
							recognizedParameters.add(new RecognizedParameter(5, args -> EvitaDataTypes.toTargetType((Serializable) args[argumentIndex], BigDecimal.class)));
						case "priceWithoutTax" ->
							recognizedParameters.add(new RecognizedParameter(4, args -> EvitaDataTypes.toTargetType((Serializable) args[argumentIndex], BigDecimal.class)));
						case "priceWithTax" ->
							recognizedParameters.add(new RecognizedParameter(6, args -> EvitaDataTypes.toTargetType((Serializable) args[argumentIndex], BigDecimal.class)));
					}
				} else if (boolean.class.equals(parameter.getType()) || Boolean.class.equals(parameter.getType())) {
					recognizedParameters.add(new RecognizedParameter(8, args -> EvitaDataTypes.toTargetType((Serializable) args[argumentIndex], boolean.class)));
				} else if (String.class.isAssignableFrom(parameter.getType())) {
					if (parameter.getName().equals("currency") || parameter.getName().equals("currencyCode")) {
						recognizedParameters.add(new RecognizedParameter(2, args -> Currency.getInstance((String) args[argumentIndex])));
					} else if (fixedPriceList.isBlank()) {
						recognizedParameters.add(new RecognizedParameter(1, args -> args[argumentIndex]));
					} else {
						throw new EntityClassInvalidException(
							proxyClass,
							"Unable to create price record via. method `" + method.toGenericString() + "` because it contains " +
								"price list, but the price list is fixed via. annotation."
						);
					}
				} else if (BigDecimal.class.isAssignableFrom(parameter.getType())) {
					switch (parameter.getName()) {
						case "taxRate" ->
							recognizedParameters.add(new RecognizedParameter(5, args -> args[argumentIndex]));
						case "priceWithoutTax" ->
							recognizedParameters.add(new RecognizedParameter(4, args -> args[argumentIndex]));
						case "priceWithTax" ->
							recognizedParameters.add(new RecognizedParameter(6, args -> args[argumentIndex]));
					}
				} else if (Currency.class.isAssignableFrom(parameter.getType())) {
					recognizedParameters.add(new RecognizedParameter(2, args -> args[argumentIndex]));
				} else if (DateTimeRange.class.isAssignableFrom(parameter.getType())) {
					recognizedParameters.add(new RecognizedParameter(7, args -> args[argumentIndex]));
				}
				// when we don't recognize parameter, we must not match the method at all
				if (recognizedParameters.size() == recognizedBefore) {
					return Optional.empty();
				}
			}

			final Set<Integer> recognizedParameterLocations = recognizedParameters.stream()
				.map(RecognizedParameter::position)
				.collect(Collectors.toSet());
			if (!recognizedParameterLocations.contains(0)) {
				throw new EntityClassInvalidException(
					proxyClass,
					"Unable to create price record via. method `" + method.toGenericString() + "` because it doesn't contain price id."
				);
			}
			if (!recognizedParameterLocations.contains(1)) {
				if (fixedPriceList.isBlank()) {
					throw new EntityClassInvalidException(
						proxyClass,
						"Unable to create price record via. method `" + method.toGenericString() + "` because it doesn't contain price list."
					);
				} else {
					// by default price list is fixed
					recognizedParameters.add(
						new RecognizedParameter(1, args -> fixedPriceList)
					);
				}
			}
			if (!recognizedParameterLocations.contains(2)) {
				throw new EntityClassInvalidException(
					proxyClass,
					"Unable to create price record via. method `" + method.toGenericString() + "` because it doesn't contain currency."
				);
			}
			if (!recognizedParameterLocations.contains(3)) {
				// by default inner record id is NULL
				recognizedParameters.add(
					new RecognizedParameter(3, args -> null)
				);
			}
			if (!recognizedParameterLocations.contains(4)) {
				throw new EntityClassInvalidException(
					proxyClass,
					"Unable to create price record via. method `" + method.toGenericString() + "` because it doesn't contain price without tax."
				);
			}
			if (!recognizedParameterLocations.contains(5)) {
				throw new EntityClassInvalidException(
					proxyClass,
					"Unable to create price record via. method `" + method.toGenericString() + "` because it doesn't contain tax rate."
				);
			}
			if (!recognizedParameterLocations.contains(6)) {
				throw new EntityClassInvalidException(
					proxyClass,
					"Unable to create price record via. method `" + method.toGenericString() + "` because it doesn't contain price with tax."
				);
			}
			if (!recognizedParameterLocations.contains(7)) {
				// by default validity is NULL
				recognizedParameters.add(
					new RecognizedParameter(7, args -> null)
				);
			}
			if (!recognizedParameterLocations.contains(8)) {
				// by default prices are sellable
				recognizedParameters.add(
					new RecognizedParameter(8, args -> true)
				);
			}

			final String methodSignature = method.toGenericString();
			priceExtractor = args -> {
				// translate method arguments to constructor arguments
				final Object[] constructorArgs = new Object[9];
				for (RecognizedParameter recognizedParameter : recognizedParameters) {
					if (recognizedParameter != null) {
						constructorArgs[recognizedParameter.position()] = recognizedParameter.argumentExtractor().apply(args);
					}
				}
				try {
					// and now create the Price
					return (PriceContract) PRICE_CONSTRUCTOR_HANDLE.invokeWithArguments(constructorArgs);
				} catch (Throwable e) {
					throw new EntityClassInvalidException(
						proxyClass,
						"Unable to create price record via. constructor via" +
							" method `" + methodSignature + "` due to: " + e.getMessage()
					);
				}
			};
		} else {
			priceExtractor = null;
		}
		return ofNullable(priceExtractor);
	}

	/**
	 * Creates invocation handler for removing existing price.
	 *
	 * @param method     method that is being classified
	 * @param returnType method return type for recognizing builder pattern
	 * @param proxyClass proxy class
	 * @return invocation handler
	 */
	@Nullable
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removePrice(
		@Nonnull Method method,
		@Nonnull Class<?> returnType,
		@Nonnull Class<?> proxyClass,
		@Nonnull Price priceAnnotation
	) {
		return createPriceKeyExtractor(method, proxyClass, priceAnnotation)
			.map(priceKeyExtractor -> {
				final CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> invocationHandler;
				if (returnType.equals(proxyClass)) {
					invocationHandler = (proxy, theMethod, args, theState, invokeSuper) -> {
						final PriceKey thePriceKey = priceKeyExtractor.apply(args);
						theState.getEntityBuilder().removePrice(thePriceKey);
						return proxy;
					};
				} else {
					invocationHandler = (proxy, theMethod, args, theState, invokeSuper) -> {
						final PriceKey thePriceKey = priceKeyExtractor.apply(args);
						theState.getEntityBuilder().removePrice(thePriceKey);
						return null;
					};
				}
				return invocationHandler;
			})
			.orElse(null);
	}

	/**
	 * Creates invocation handler for removing existing price.
	 *
	 * @param returnType method return type for recognizing builder pattern
	 * @param proxyClass proxy class
	 * @return invocation handler
	 */
	@Nullable
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeAllPrices(
		@Nonnull Class<?> returnType,
		@Nonnull Class<?> proxyClass,
		@Nonnull Price priceAnnotation
	) {
		final String fixedPriceList = priceAnnotation.priceList();
		if (fixedPriceList.isBlank()) {
			if (returnType.equals(proxyClass)) {
				return (proxy, theMethod, args, theState, invokeSuper) -> {
					theState.getEntityBuilder().removeAllPrices();
					return proxy;
				};
			} else {
				return (proxy, theMethod, args, theState, invokeSuper) -> {
					theState.getEntityBuilder().removeAllPrices();
					return null;
				};
			}
		} else {
			return removeAllMatchingPrices(
				returnType, proxyClass,
				(args, priceContract) -> fixedPriceList.equals(priceContract.priceList())
			);
		}
	}

	/**
	 * Creates invocation handler for removing multiple existing prices by matching their currency or price list.
	 *
	 * @param method     method that is being classified
	 * @param returnType method return type for recognizing builder pattern
	 * @param proxyClass proxy class
	 * @return invocation handler
	 */
	@Nullable
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeMultiplePrices(
		@Nonnull Method method,
		@Nonnull Class<?> returnType,
		@Nonnull Class<?> proxyClass,
		@Nonnull Price priceAnnotation
	) {
		final BiPredicate<Object[], PriceContract> removalPredicate = createPredicateForArguments(method, priceAnnotation);
		if (removalPredicate == null) {
			return null;
		} else {
			return removeAllMatchingPrices(returnType, proxyClass, removalPredicate);
		}
	}

	/**
	 * Returns invocation handler for removing multiple existing prices by matching the passed predicate.
	 *
	 * @param returnType       method return type for recognizing builder pattern
	 * @param proxyClass       proxy class
	 * @param removalPredicate the predicate that matches the prices to be removed
	 * @return invocation handler
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeAllMatchingPrices(
		@Nonnull Class<?> returnType,
		@Nonnull Class<?> proxyClass,
		@Nonnull BiPredicate<Object[], PriceContract> removalPredicate
	) {
		if (returnType.equals(proxyClass)) {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				final EntityBuilder entityBuilder = theState.getEntityBuilder();
				final List<PriceContract> pricesToRemove = entityBuilder.getPrices()
					.stream()
					.filter(it -> removalPredicate.test(args, it))
					.toList();
				for (PriceContract priceContract : pricesToRemove) {
					entityBuilder.removePrice(priceContract.priceKey());
				}
				return proxy;
			};
		} else {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				final EntityBuilder entityBuilder = theState.getEntityBuilder();
				final List<PriceContract> pricesToRemove = entityBuilder.getPrices()
					.stream()
					.filter(it -> removalPredicate.test(args, it))
					.toList();
				for (PriceContract priceContract : pricesToRemove) {
					entityBuilder.removePrice(priceContract.priceKey());
				}
				return null;
			};
		}
	}

	/**
	 * Creates function that converts method arguments to constructor call of
	 * {@link PriceKey#PriceKey(int, String, Currency)}.
	 *
	 * @param method     method that is being classified
	 * @param proxyClass proxy class
	 * @return function that converts method arguments to constructor call of the {@link PriceKey} record
	 */
	@Nonnull
	private static Optional<Function<Object[], PriceKey>> createPriceKeyExtractor(
		@Nonnull Method method,
		@Nonnull Class<?> proxyClass,
		@Nonnull Price priceAnnotation
	) {
		final String fixedPriceList = priceAnnotation.priceList();
		final Function<Object[], PriceKey> priceKeyExtractor;
		final int parameterCount = method.getParameterCount();
		if (parameterCount == 1 && PriceKey.class.isAssignableFrom(method.getParameterTypes()[0])) {
			priceKeyExtractor = args -> (PriceKey) args[0];
		} else if (parameterCount > 0) {
			final List<RecognizedParameter> recognizedParameters = new ArrayList<>(9);
			final Parameter[] methodParameters = method.getParameters();
			for (int i = 0; i < methodParameters.length; i++) {
				final Parameter parameter = methodParameters[i];
				final int argumentIndex = i;
				final int recognizedBefore = recognizedParameters.size();
				if (NumberUtils.isIntConvertibleNumber(parameter.getType())) {
					switch (parameter.getName()) {
						case "id" ->
							recognizedParameters.add(new RecognizedParameter(0, args -> EvitaDataTypes.toTargetType((Serializable) args[argumentIndex], int.class)));
						case "priceId" ->
							recognizedParameters.add(new RecognizedParameter(0, args -> EvitaDataTypes.toTargetType((Serializable) args[argumentIndex], int.class)));
					}
				} else if (String.class.isAssignableFrom(parameter.getType())) {
					if (parameter.getName().equals("currency")) {
						recognizedParameters.add(new RecognizedParameter(2, args -> Currency.getInstance((String) args[argumentIndex])));
					} else if (fixedPriceList.isBlank()) {
						recognizedParameters.add(new RecognizedParameter(1, args -> args[argumentIndex]));
					} else {
						throw new EntityClassInvalidException(
							proxyClass,
							"Unable to remove price via. method `" + method.toGenericString() + "` because it contains " +
								"price list, but the price list is fixed via. annotation."
						);
					}
				} else if (Currency.class.isAssignableFrom(parameter.getType())) {
					recognizedParameters.add(new RecognizedParameter(2, args -> args[argumentIndex]));
				}
				// when we don't recognize parameter, we must not match the method at all
				if (recognizedParameters.size() == recognizedBefore) {
					return Optional.empty();
				}
			}

			final Set<Integer> recognizedParameterLocations = recognizedParameters.stream()
				.map(RecognizedParameter::position)
				.collect(Collectors.toSet());
			if (!recognizedParameterLocations.contains(0)) {
				throw new EntityClassInvalidException(
					proxyClass,
					"Unable to create price record via. method `" + method.toGenericString() + "` because it doesn't contain price id."
				);
			}
			if (!recognizedParameterLocations.contains(1)) {
				if (fixedPriceList.isBlank()) {
					throw new EntityClassInvalidException(
						proxyClass,
						"Unable to create price record via. method `" + method.toGenericString() + "` because it doesn't contain price list."
					);
				} else {
					// by default price list is fixed
					recognizedParameters.add(
						new RecognizedParameter(1, args -> fixedPriceList)
					);
				}
			}
			if (!recognizedParameterLocations.contains(2)) {
				throw new EntityClassInvalidException(
					proxyClass,
					"Unable to create price record via. method `" + method.toGenericString() + "` because it doesn't contain currency."
				);
			}

			final String methodSignature = method.toGenericString();
			priceKeyExtractor = args -> {
				// translate method arguments to constructor arguments
				final Object[] constructorArgs = new Object[3];
				for (RecognizedParameter recognizedParameter : recognizedParameters) {
					if (recognizedParameter != null) {
						constructorArgs[recognizedParameter.position()] = recognizedParameter.argumentExtractor().apply(args);
					}
				}
				try {
					// and now create the PriceKey
					return (PriceKey) PRICE_KEY_CONSTRUCTOR_HANDLE.invokeWithArguments(constructorArgs);
				} catch (Throwable e) {
					throw new EntityClassInvalidException(
						proxyClass,
						"Unable to create price key record via. constructor via" +
							" method `" + methodSignature + "` due to: " + e.getMessage()
					);
				}
			};
		} else {
			priceKeyExtractor = null;
		}
		return ofNullable(priceKeyExtractor);
	}

	/**
	 * Creates function that extracts price list and / or currency from method arguments (or both).
	 *
	 * @param method method that is being classified
	 * @return function that extracts price list and / or currency from method arguments (or both)
	 */
	@Nullable
	private static BiPredicate<Object[], PriceContract> createPredicateForArguments(
		@Nonnull Method method,
		@Nonnull Price priceAnnotation
	) {
		final String fixedPriceList = priceAnnotation.priceList();
		BiPredicate<Object[], PriceContract> removalPredicate = fixedPriceList.isBlank() ?
			(args, priceContract) -> true : (args, priceContract) -> priceContract.priceList().equals(fixedPriceList);
		final Parameter[] methodParameters = method.getParameters();
		for (int i = 0; i < methodParameters.length; i++) {
			final Parameter parameter = methodParameters[i];
			final int argumentIndex = i;
			if (NumberUtils.isIntConvertibleNumber(parameter.getType())) {
				switch (parameter.getName()) {
					case "id" ->
						removalPredicate = removalPredicate.and((args, price) -> price.priceId() == EvitaDataTypes.toTargetType((Serializable) args[argumentIndex], int.class));
					case "priceId" ->
						removalPredicate = removalPredicate.and((args, price) -> price.priceId() == EvitaDataTypes.toTargetType((Serializable) args[argumentIndex], int.class));
					default -> {
						return null;
					}
				}
			} else if (String.class.isAssignableFrom(parameter.getType())) {
				if (parameter.getName().equals("currency")) {
					removalPredicate = removalPredicate.and((args, price) -> price.currency().equals(Currency.getInstance((String) args[argumentIndex])));
				} else if (fixedPriceList.isBlank()) {
					removalPredicate = removalPredicate.and((args, price) -> price.priceList().equals(args[argumentIndex]));
				} else {
					throw new EntityClassInvalidException(
						method.getDeclaringClass(),
						"Unable to remove price via. method `" + method.toGenericString() + "` because it contains " +
							"price list, but the price list is fixed via. annotation."
					);
				}
			} else if (Currency.class.isAssignableFrom(parameter.getType())) {
				removalPredicate = removalPredicate.and((args, price) -> price.currency().equals(args[argumentIndex]));
			} else {
				return null;
			}
		}
		return removalPredicate;
	}

	public SetPriceMethodClassifier() {
		super(
			"setPrice",
			(method, proxyState) -> {
				final int parameterCount = method.getParameterCount();
				final Class<?> returnType = method.getReturnType();
				final Class<?> proxyClass = proxyState.getProxyClass();
				final Class<?>[] parameterTypes = method.getParameterTypes();

				// if the method has only one parameter and it is PriceInnerRecordHandling, we handle it separately
				if (parameterCount == 1 && PriceInnerRecordHandling.class.isAssignableFrom(parameterTypes[0])) {
					return setPriceInnerRecordHandling(returnType, proxyClass);
				}

				// first we need to identify whether the method returns a parent entity
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final Price price = reflectionLookup.getAnnotationInstanceForProperty(method, Price.class);
				if (price == null || !proxyState.getEntitySchema().isWithPrice()) {
					return null;
				}

				if (method.isAnnotationPresent(RemoveWhenExists.class)) {
					if (parameterCount == 0) {
						return removeAllPrices(returnType, proxyClass, price);
					} else {
						return ofNullable(removePrice(method, returnType, proxyClass, price))
							.orElseGet(() -> removeMultiplePrices(method, returnType, proxyClass, price));
					}
				} else {
					if (parameterCount == 1 && parameterTypes[0].isArray()) {
						return setPricesAsArray(method, returnType, proxyClass, price);
					} else if (parameterCount == 1 && Collection.class.isAssignableFrom(parameterTypes[0])) {
						return setPricesAsCollection(method, returnType, proxyClass, price);
					} else {
						return upsertPrice(method, returnType, proxyClass, price);
					}
				}
			}
		);
	}

	/**
	 * Wraps a recognized parameter in method signature
	 *
	 * @param position          represents constructor argument position in {@link Price(int, String, Currency, Integer, BigDecimal, BigDecimal, BigDecimal, DateTimeRange, boolean)}
	 * @param argumentExtractor extracts the argument from method arguments
	 */
	private record RecognizedParameter(
		int position,
		@Nonnull Function<Object[], Object> argumentExtractor
	) {
	}

}
