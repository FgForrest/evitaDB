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

import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.proxy.impl.ProxyUtils;
import io.evitadb.api.proxy.impl.ProxyUtils.OptionalProducingOperator;
import io.evitadb.api.proxy.impl.ProxyUtils.ResultWrapper;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.query.require.AccompanyingPriceContent;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.annotation.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.Price;
import io.evitadb.api.requestResponse.data.annotation.PriceForSale;
import io.evitadb.api.requestResponse.data.annotation.PriceForSaleRef;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.function.ExceptionRethrowingFunction;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.CollectorUtils;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.api.proxy.impl.ProxyUtils.getResolvedTypes;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Identifies methods that are used to get prices from an sealed entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetPriceMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetPriceMethodClassifier INSTANCE = new GetPriceMethodClassifier();

	/**
	 * Tries to identify price from the class field related to the constructor parameter.
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
		final Class<?> specificType = resolvedTypes.length == 2 ? resolvedTypes[1] : parameterType.getComponentType();

		if (!(PriceContract.class.equals(parameterType) || PriceContract.class.equals(specificType))) {
			return null;
		}

		final boolean sellingPrice;
		final PriceForSale priceForSaleInstance = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, PriceForSale.class);
		if (priceForSaleInstance != null) {
			sellingPrice = true;
		} else {
			final PriceForSaleRef priceForSaleRefInstance = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, PriceForSaleRef.class);
			sellingPrice = priceForSaleRefInstance != null;
		}
		final Price priceInstance = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, Price.class);
		final String priceList = priceInstance == null ? null : of(priceInstance.priceList()).filter(it -> !it.isBlank()).orElse(null);

		if (sellingPrice) {
			if (PriceContract.class.equals(parameterType)) {
				return entity -> entity.isPriceForSaleContextAvailable() ? entity.getPriceForSale().orElse(null) : null;
			} else if (parameterType.isArray()) {
				return entity -> entity.isPriceForSaleContextAvailable() ? entity.getAllPricesForSale().toArray(PriceContract[]::new) : null;
			} else if (Set.class.equals(parameterType)) {
				return entity -> entity.isPriceForSaleContextAvailable() ? entity.getAllPricesForSale().stream().collect(CollectorUtils.toUnmodifiableLinkedHashSet()) : Collections.emptySet();
			} else if (List.class.equals(parameterType) || Collection.class.equals(parameterType)) {
				return entity -> entity.isPriceForSaleContextAvailable() ? entity.getAllPricesForSale() : Collections.emptyList();
			} else {
				throw new EntityClassInvalidException(
					expectedType,
					"Unsupported data type `" + parameterType + "` for price for sale in entity `" + schema.getName() +
						"` related to constructor parameter `" + parameterName + "`!"
				);
			}
		} else if (priceInstance != null) {
			if (priceList == null) {
				if (PriceContract.class.equals(parameterType)) {
					return entity -> entity.pricesAvailable() ? entity.getPrices().stream().filter(Droppable::exists).findFirst().orElse(null) : null;
				} else if (parameterType.isArray()) {
					return entity -> entity.pricesAvailable() ? entity.getPrices().stream().filter(Droppable::exists).toArray(PriceContract[]::new) : null;
				} else if (Set.class.equals(parameterType)) {
					return entity -> entity.pricesAvailable() ? entity.getPrices().stream().filter(Droppable::exists).collect(CollectorUtils.toUnmodifiableLinkedHashSet()) : Collections.emptySet();
				} else if (List.class.equals(parameterType) || Collection.class.equals(parameterType)) {
					return entity -> entity.pricesAvailable() ? entity.getPrices().stream().filter(Droppable::exists).toList() : Collections.emptyList();
				} else {
					throw new EntityClassInvalidException(
						expectedType,
						"Unsupported data type `" + parameterType + "` for price in entity `" + schema.getName() +
							"` related to constructor parameter `" + parameterName + "`!"
					);
				}
			} else {
				if (PriceContract.class.equals(parameterType)) {
					return entity -> entity.pricesAvailable() ? entity.getPrices(priceList).stream().filter(Droppable::exists).findFirst().orElse(null) : null;
				} else if (parameterType.isArray()) {
					return entity -> entity.pricesAvailable() ? entity.getPrices(priceList).stream().filter(Droppable::exists).toArray(PriceContract[]::new) : null;
				} else if (Set.class.equals(parameterType)) {
					return entity -> entity.pricesAvailable() ? entity.getPrices(priceList).stream().filter(Droppable::exists).collect(CollectorUtils.toUnmodifiableLinkedHashSet()) : Collections.emptySet();
				} else if (List.class.equals(parameterType) || Collection.class.equals(parameterType)) {
					return entity -> entity.pricesAvailable() ? entity.getPrices(priceList).stream().filter(Droppable::exists).toList() : Collections.emptyList();
				} else {
					throw new EntityClassInvalidException(
						expectedType,
						"Unsupported data type `" + parameterType + "` for price in entity `" + schema.getName() +
							"` related to constructor parameter `" + parameterName + "`!"
					);
				}
			}
		}

		return null;
	}

	/**
	 * Method collects and returns an index allowing to get a lambda that takes array of method call argument and
	 * returns an argument of particular type which matches the key in the index.
	 *
	 * @param proxyClass is used only for exception messages
	 * @param method     the analyzed method
	 * @return index allowing to get a lambda that takes array of method call argument and returns an argument of
	 * particular type which matches the key in the index
	 */
	@Nonnull
	private static Map<Class<?>, Function<Object[], Object>> collectPriceArgumentFetchers(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method,
		@Nonnull Class<? extends Annotation> annotation
	) {
		final Class<?>[] parameterTypes = method.getParameterTypes();
		final Map<Class<?>, Function<Object[], Object>> argumentFetchers = CollectionUtils.createHashMap(parameterTypes.length);
		for (int i = 0; i < parameterTypes.length; i++) {
			final Class<?> parameterType = parameterTypes[i];
			final int argumentIndex = i;
			if (String.class.isAssignableFrom(parameterType)) {
				argumentFetchers.compute(
					String[].class,
					(theType, existingSupplier) -> {
						if (existingSupplier == null) {
							return args -> new String[]{(String) args[argumentIndex]};
						} else {
							throw new EntityClassInvalidException(
								proxyClass,
								"Entity class type `" + proxyClass + "` method `" + method + "` is annotated " +
									"with @" + annotation.getSimpleName() + " annotation, and contains multiple price lists (String) arguments! " +
									"You need to provide either a String array argument or a single String " +
									"argument representing price list! "
							);
						}
					}
				);
			} else if (String[].class.isAssignableFrom(parameterType)) {
				argumentFetchers.compute(
					String[].class,
					(theType, existingSupplier) -> {
						if (existingSupplier == null) {
							return args -> (String[]) args[argumentIndex];
						} else {
							throw new EntityClassInvalidException(
								proxyClass,
								"Entity class type `" + proxyClass + "` method `" + method + "` is annotated " +
									"with @" + annotation.getSimpleName() + " annotation, and contains multiple price lists (String[]) arguments! " +
									"You need to provide only a single String array argument representing price list " +
									"in a prioritized order!"
							);
						}
					}
				);
			} else if (OffsetDateTime.class.isAssignableFrom(parameterType)) {
				argumentFetchers.compute(
					OffsetDateTime.class,
					(theType, existingSupplier) -> {
						if (existingSupplier == null) {
							return args -> (OffsetDateTime) args[argumentIndex];
						} else {
							throw new EntityClassInvalidException(
								proxyClass,
								"Entity class type `" + proxyClass + "` method `" + method + "` is annotated " +
									"with @" + annotation.getSimpleName() + " annotation, and contains multiple date and time " +
									"(OffsetDateTime) arguments! You need to provide only a single temporal " +
									" argument representing a date and time the price for sale must be valid for!"
							);
						}
					}
				);
			} else if (Currency.class.isAssignableFrom(parameterType)) {
				argumentFetchers.compute(
					Currency.class,
					(theType, existingSupplier) -> {
						if (existingSupplier == null) {
							return args -> (Currency) args[argumentIndex];
						} else {
							throw new EntityClassInvalidException(
								proxyClass,
								"Entity class type `" + proxyClass + "` method `" + method + "` is annotated " +
									"with @" + annotation.getSimpleName() + " annotation, and contains multiple currency " +
									"(Currency) arguments! You need to provide only a single currency you require " +
									"price for sale to be in!"
							);
						}
					}
				);
			} else if (NumberUtils.isIntConvertibleNumber(parameterType)) {
				argumentFetchers.compute(
					int.class,
					(theType, existingSupplier) -> {
						if (existingSupplier == null) {
							return args -> EvitaDataTypes.toTargetType((Serializable) args[argumentIndex], int.class);
						} else {
							throw new EntityClassInvalidException(
								proxyClass,
								"Entity class type `" + proxyClass + "` method `" + method + "` is annotated " +
									"with @" + annotation.getSimpleName() + " annotation, and contains multiple currency " +
									"(Currency) arguments! You need to provide only a single currency you require " +
									"price for sale to be in!"
							);
						}
					}
				);
			} else {
				throw new EntityClassInvalidException(
					proxyClass,
					"Entity class type `" + proxyClass + "` method `" + method + "` is annotated " +
						"with @" + annotation.getSimpleName() + " annotation, and contains unsupported argument type `" + parameterType +
						"`!"
				);
			}
		}
		return argumentFetchers;
	}

	/**
	 * Creates a {@link Predicate} implementation that allows to filter a stream of {@link PriceContract} to match
	 * the arguments extracted from the current method call.
	 */
	@Nullable
	private static Predicate<PriceContract> getPriceContractPredicate(
		@Nonnull Map<Class<?>, Function<Object[], Object>> argumentFetchers,
		@Nonnull Object[] args
	) {
		final List<Predicate<PriceContract>> pricePredicates = new LinkedList<>();
		ofNullable(argumentFetchers.get(int.class))
			.map(it -> (int) it.apply(args))
			.map(it -> (Predicate<PriceContract>) priceContract -> it.equals(priceContract.priceId()))
			.ifPresent(pricePredicates::add);
		ofNullable(argumentFetchers.get(String[].class))
			.map(it -> (String[]) it.apply(args))
			.filter(it -> !ArrayUtils.isEmpty(it))
			.map(it -> {
				final Set<String> allowedPriceLists = CollectionUtils.createHashSet(it.length);
				Collections.addAll(allowedPriceLists, it);
				return (Predicate<PriceContract>) priceContract -> allowedPriceLists.contains(priceContract.priceList());
			})
			.ifPresent(pricePredicates::add);
		ofNullable(argumentFetchers.get(String.class))
			.map(it -> (String) it.apply(args))
			.map(it -> (Predicate<PriceContract>) priceContract -> it.equals(priceContract.priceList()))
			.ifPresent(pricePredicates::add);
		ofNullable(argumentFetchers.get(Currency.class))
			.map(it -> (Currency) it.apply(args))
			.map(it -> (Predicate<PriceContract>) priceContract -> it.equals(priceContract.currency()))
			.ifPresent(pricePredicates::add);
		ofNullable(argumentFetchers.get(OffsetDateTime.class))
			.map(it -> (OffsetDateTime) it.apply(args))
			.map(it -> (Predicate<PriceContract>) priceContract -> priceContract.validAt(it))
			.ifPresent(pricePredicates::add);

		return pricePredicates
			.stream()
			.reduce(Predicate::and)
			.orElse(null);
	}

	/**
	 * Creates an implementation of the method returning a single price for sale that matches either original query
	 * when the entity was fetched or matches the context passed in the method call arguments.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singlePriceForSaleResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method,
		@Nonnull Function<EntityContract, PriceContract> priceForSaleSupplier,
		@Nonnull ResultWrapper resultWrapper
	) {
		if (method.getParameterCount() == 0) {
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
				() -> priceForSaleSupplier.apply(theState.entity())
			);
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectPriceArgumentFetchers(proxyClass, method, PriceForSale.class);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final String[] priceLists = ofNullable(argumentFetchers.get(String[].class))
					.map(it -> (String[]) it.apply(args))
					.orElseThrow();
				final Currency currency = ofNullable(argumentFetchers.get(Currency.class))
					.map(it -> (Currency) it.apply(args))
					.orElseThrow();
				final OffsetDateTime moment = ofNullable(argumentFetchers.get(OffsetDateTime.class))
					.map(it -> (OffsetDateTime) it.apply(args))
					.orElse(null);
				return resultWrapper.wrap(
					() -> theState.entity()
						.getPriceForSale(currency, moment, priceLists)
						.orElse(null)
				);
			};
		}
	}

	/**
	 * Creates an implementation of the method returning a list of prices for sale that match the context passed in
	 * the method call arguments.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfPriceForSaleResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method,
		@Nonnull Function<EntityContract, Collection<PriceContract>> priceForSaleSupplier,
		@Nonnull ResultWrapper resultWrapper
	) {
		if (method.getParameterCount() == 0) {
			return (entityClassifier, theMethod, args, theState, invokeSuper) ->
				resultWrapper.wrap(() -> theState.entity().getAllPricesForSale());
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectPriceArgumentFetchers(proxyClass, method, PriceForSale.class);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final Predicate<PriceContract> pricePredicate = getPriceContractPredicate(argumentFetchers, args);

				final Collection<PriceContract> allPricesForSale = priceForSaleSupplier.apply(theState.entity());
				return resultWrapper.wrap(
					() -> pricePredicate == null ?
						allPricesForSale :
						allPricesForSale.stream().filter(pricePredicate).toList()
				);
			};
		}
	}

	/**
	 * Creates an implementation of the method returning a set of prices for sale that match the context passed in
	 * the method call arguments.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfPriceForSaleResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method,
		@Nonnull Function<EntityContract, Collection<PriceContract>> priceForSaleSupplier,
		@Nonnull ResultWrapper resultWrapper
	) {
		if (method.getParameterCount() == 0) {
			return (entityClassifier, theMethod, args, theState, invokeSuper) ->
				resultWrapper.wrap(
					() -> theState.entity()
						.getAllPricesForSale()
						.stream()
						.collect(CollectorUtils.toUnmodifiableLinkedHashSet())
				);
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectPriceArgumentFetchers(proxyClass, method, PriceForSale.class);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final Predicate<PriceContract> pricePredicate = getPriceContractPredicate(argumentFetchers, args);

				final Collection<PriceContract> allPricesForSale = priceForSaleSupplier.apply(theState.entity());
				return resultWrapper.wrap(
					() -> pricePredicate == null ?
						allPricesForSale.stream().collect(CollectorUtils.toUnmodifiableLinkedHashSet()) :
						allPricesForSale.stream().filter(pricePredicate).collect(CollectorUtils.toUnmodifiableLinkedHashSet())
				);
			};
		}
	}

	/**
	 * Creates an implementation of the method returning a array of prices for sale that match the context passed in
	 * the method call arguments.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> arrayOfPriceForSaleResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method,
		@Nonnull Function<EntityContract, Collection<PriceContract>> priceForSaleSupplier,
		@Nonnull ResultWrapper resultWrapper
	) {
		if (method.getParameterCount() == 0) {
			return (entityClassifier, theMethod, args, theState, invokeSuper) ->
				resultWrapper.wrap(
					() -> theState.entity()
						.getAllPricesForSale()
						.toArray(PriceContract[]::new)
				);
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectPriceArgumentFetchers(proxyClass, method, PriceForSale.class);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final Predicate<PriceContract> pricePredicate = getPriceContractPredicate(argumentFetchers, args);

				final Collection<PriceContract> allPricesForSale = priceForSaleSupplier.apply(theState.entity());
				return resultWrapper.wrap(
					() -> pricePredicate == null ?
						allPricesForSale.toArray(PriceContract[]::new) :
						allPricesForSale.stream().filter(pricePredicate).toArray(PriceContract[]::new)
				);
			};
		}
	}

	/**
	 * Creates an implementation of the method returning a single of price that match the context passed in
	 * the method call arguments.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singlePriceResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method,
		@Nullable String priceList,
		@Nonnull Function<EntityContract, Stream<PriceContract>> priceSupplier,
		@Nonnull ResultWrapper resultWrapper
	) {
		if (method.getParameterCount() == 0) {
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
				() -> {
					final List<PriceContract> prices = priceSupplier.apply(theState.entity())
						.filter(it -> priceList == null || priceList.equals(it.priceList()))
						.limit(2)
						.toList();
					if (prices.isEmpty()) {
						return null;
					} else if (prices.size() == 1) {
						return prices.get(0);
					} else {
						throw new UnexpectedResultCountException(
							(int) theState.entity()
								.getPrices()
								.stream()
								.filter(it -> priceList == null || priceList.equals(it.priceList()))
								.count()
						);
					}
				}
			);
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectPriceArgumentFetchers(proxyClass, method, Price.class);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
				() -> {
					final Predicate<PriceContract> argumentPredicate = getPriceContractPredicate(argumentFetchers, args);
					final Predicate<PriceContract> pricePredicate = ofNullable(priceList)
						.map(it -> {
							final Predicate<PriceContract> basePredicate = price -> priceList.equals(price.priceList());
							return argumentPredicate == null ? basePredicate : basePredicate.and(argumentPredicate);
						})
						.orElse(argumentPredicate);
					final Stream<PriceContract> allPrices = priceSupplier.apply(theState.entity());

					final List<PriceContract> matchingPrices = pricePredicate == null ?
						allPrices.toList() : allPrices.filter(pricePredicate).limit(2).toList();

					if (matchingPrices.isEmpty()) {
						return null;
					} else if (matchingPrices.size() == 1) {
						return matchingPrices.get(0);
					} else {
						throw new UnexpectedResultCountException(
							matchingPrices.size() +
								(pricePredicate == null ?
									(int) priceSupplier.apply(theState.entity()).count() :
									(int) priceSupplier.apply(theState.entity()).filter(pricePredicate).count()
								)
						);
					}
				}
			);
		}
	}

	/**
	 * Creates an implementation of the method returning a list of prices that match the context passed in
	 * the method call arguments.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfPriceResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method,
		@Nullable String priceList,
		@Nonnull Function<EntityContract, Stream<PriceContract>> priceSupplier,
		@Nonnull ResultWrapper resultWrapper
	) {
		if (method.getParameterCount() == 0) {
			if (priceList == null) {
				return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
					() -> priceSupplier.apply(theState.entity()).toList()
				);
			} else {
				return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
					() -> priceSupplier.apply(theState.entity())
						.filter(it -> priceList.equals(it.priceList()))
						.toList()
				);
			}
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectPriceArgumentFetchers(proxyClass, method, Price.class);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final Predicate<PriceContract> argumentPredicate = getPriceContractPredicate(argumentFetchers, args);
				final Predicate<PriceContract> pricePredicate = ofNullable(priceList)
					.map(it -> {
						final Predicate<PriceContract> basePredicate = price -> priceList.equals(price.priceList());
						return argumentPredicate == null ? basePredicate : basePredicate.and(argumentPredicate);
					})
					.orElse(argumentPredicate);

				final Stream<PriceContract> allPrices = priceSupplier.apply(theState.entity());
				return resultWrapper.wrap(
					() -> pricePredicate == null ?
						allPrices.toList() : allPrices.filter(pricePredicate).limit(2).toList()
				);
			};
		}
	}

	/**
	 * Creates an implementation of the method returning a set of prices that match the context passed in
	 * the method call arguments.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfPriceResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method,
		@Nullable String priceList,
		@Nonnull Function<EntityContract, Stream<PriceContract>> priceSupplier,
		@Nonnull ResultWrapper resultWrapper
	) {
		if (method.getParameterCount() == 0) {
			if (priceList == null) {
				return (entityClassifier, theMethod, args, theState, invokeSuper) ->
					resultWrapper.wrap(
						() -> priceSupplier.apply(theState.entity())
							.collect(CollectorUtils.toUnmodifiableLinkedHashSet())
					);
			} else {
				return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
					() -> priceSupplier.apply(theState.entity())
						.filter(it -> priceList.equals(it.priceList()))
						.collect(CollectorUtils.toUnmodifiableLinkedHashSet())
				);
			}
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectPriceArgumentFetchers(proxyClass, method, Price.class);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final Predicate<PriceContract> argumentPredicate = getPriceContractPredicate(argumentFetchers, args);
				final Predicate<PriceContract> pricePredicate = ofNullable(priceList)
					.map(it -> {
						final Predicate<PriceContract> basePredicate = price -> priceList.equals(price.priceList());
						return argumentPredicate == null ? basePredicate : basePredicate.and(argumentPredicate);
					})
					.orElse(argumentPredicate);
				final Collection<PriceContract> allPrices = theState.entity().getPrices();

				return resultWrapper.wrap(
					() -> pricePredicate == null ?
						allPrices.stream().collect(CollectorUtils.toUnmodifiableLinkedHashSet()) :
						allPrices.stream().filter(pricePredicate).collect(CollectorUtils.toUnmodifiableLinkedHashSet())
				);
			};
		}
	}

	/**
	 * Creates an implementation of the method returning a array of prices that match the context passed in
	 * the method call arguments.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> arrayOfPriceResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method,
		@Nullable String priceList,
		@Nonnull Function<EntityContract, Stream<PriceContract>> priceSupplier,
		@Nonnull ResultWrapper resultWrapper
	) {
		if (method.getParameterCount() == 0) {
			if (priceList == null) {
				return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
					() -> priceSupplier.apply(theState.entity())
						.toArray(PriceContract[]::new)
				);
			} else {
				return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
					() -> priceSupplier.apply(theState.entity())
						.filter(it -> priceList.equals(it.priceList()))
						.toArray(PriceContract[]::new)
				);
			}
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectPriceArgumentFetchers(proxyClass, method, Price.class);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final Predicate<PriceContract> argumentPredicate = getPriceContractPredicate(argumentFetchers, args);
				final Predicate<PriceContract> pricePredicate = ofNullable(priceList)
					.map(it -> {
						final Predicate<PriceContract> basePredicate = price -> priceList.equals(price.priceList());
						return argumentPredicate == null ? basePredicate : basePredicate.and(argumentPredicate);
					})
					.orElse(argumentPredicate);

				final Stream<PriceContract> allPrices = priceSupplier.apply(theState.entity());
				return resultWrapper.wrap(
					() -> pricePredicate == null ?
						allPrices.toArray(PriceContract[]::new) :
						allPrices.filter(pricePredicate).toArray(PriceContract[]::new)
				);
			};
		}
	}

	public GetPriceMethodClassifier() {
		super(
			"getPrices",
			(method, proxyState) -> {
				// now we need to identify whether the method should be implemeted by this classifier
				// it must be annotated appropriately
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final PriceForSale priceForSale = reflectionLookup.getAnnotationInstanceForProperty(method, PriceForSale.class);
				final PriceForSaleRef priceForSaleRef = reflectionLookup.getAnnotationInstanceForProperty(method, PriceForSaleRef.class);
				final AccompanyingPrice accompanyingPrice = reflectionLookup.getAnnotationInstanceForProperty(method, AccompanyingPrice.class);
				final Price price = reflectionLookup.getAnnotationInstanceForProperty(method, Price.class);

				if (
					priceForSale == null && priceForSaleRef == null && accompanyingPrice == null && price == null ||
						method.isAnnotationPresent(CreateWhenMissing.class) ||
						method.isAnnotationPresent(RemoveWhenExists.class)
				) {
					return null;
				}

				// now we need to identify the return type
				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
				final Class<?>[] resolvedTypes = getResolvedTypes(method, proxyState.getProxyClass());
				final ResultWrapper resultWrapper = ProxyUtils.createOptionalWrapper(
					method,
					Optional.class.isAssignableFrom(resolvedTypes[0]) ? Optional.class : null
				);
				final int index = Optional.class.isAssignableFrom(resolvedTypes[0]) ? 1 : 0;

				@SuppressWarnings("rawtypes") final Class collectionType;
				@SuppressWarnings("rawtypes") final Class itemType;
				if (Collection.class.equals(resolvedTypes[index]) || List.class.isAssignableFrom(resolvedTypes[index]) || Set.class.isAssignableFrom(resolvedTypes[index])) {
					collectionType = resolvedTypes[index];
					itemType = resolvedTypes.length > index + 1 ? resolvedTypes[index + 1] : PriceContract.class;
				} else if (resolvedTypes[index].isArray()) {
					collectionType = resolvedTypes[index];
					itemType = returnType.getComponentType();
				} else {
					collectionType = null;
					itemType = resolvedTypes[index];
				}

				// and verify that the return type is valid
				if (void.class.equals(itemType) || proxyState.getProxyClass().equals(itemType)) {
					// the method probably represents a mutation (setter)
					return null;
				} else {
					Assert.isTrue(
						PriceContract.class.isAssignableFrom(itemType),
						() -> new EntityClassInvalidException(
							proxyState.getProxyClass(),
							"Entity class type `" + proxyState.getProxyClass() + "` method `" + method + "` is annotated " +
								"with price annotation, but the return class `" + returnType + "`. Only PriceContract return " +
								"type is supported!"
						)
					);
				}

				if (priceForSale != null || priceForSaleRef != null) {

					// if the method is annotated with @PriceForSale or @PriceForSaleRef, then we need to return
					// the price for sale in the requested form
					if (collectionType == null) {
						return singlePriceForSaleResult(
							proxyState.getProxyClass(), method,
							sealedEntity -> resultWrapper instanceof OptionalProducingOperator ?
								sealedEntity.getPriceForSaleIfAvailable().orElse(null) :
								sealedEntity.getPriceForSale().orElse(null),
							resultWrapper
						);
					} else {
						final Function<EntityContract, Collection<PriceContract>> priceForSaleSupplier =
							resultWrapper instanceof OptionalProducingOperator ?
								sealedEntity -> sealedEntity.pricesAvailable() && sealedEntity.isPriceForSaleContextAvailable() ?
									sealedEntity.getAllPricesForSale() : Collections.emptyList() :
								PricesContract::getAllPricesForSale;

						if (collectionType.isArray()) {
							return arrayOfPriceForSaleResult(proxyState.getProxyClass(), method, priceForSaleSupplier, resultWrapper);
						} else if (Set.class.isAssignableFrom(collectionType)) {
							return setOfPriceForSaleResult(proxyState.getProxyClass(), method, priceForSaleSupplier, resultWrapper);
						} else {
							return listOfPriceForSaleResult(proxyState.getProxyClass(), method, priceForSaleSupplier, resultWrapper);
						}
					}
				} else if (accompanyingPrice != null) {
					// we need to provide access to the price that is marked as accompanying price
					final String accompanyingPriceName = accompanyingPrice.name().isBlank() ?
						AccompanyingPriceContent.DEFAULT_ACCOMPANYING_PRICE : accompanyingPrice.name();
					return singlePriceForSaleResult(
						proxyState.getProxyClass(), method,
						sealedEntity -> resultWrapper instanceof OptionalProducingOperator ?
							sealedEntity.getAccompanyingPriceIfAvailable(accompanyingPriceName).orElse(null) :
							sealedEntity.getAccompanyingPrice(accompanyingPriceName).orElse(null),
						resultWrapper
					);
				} else {
					// otherwise we need to provide access to all prices in the entity
					final String priceList = price.priceList().isBlank() ?
						null : price.priceList();

					final Function<EntityContract, Stream<PriceContract>> priceSupplier =
						resultWrapper instanceof OptionalProducingOperator ?
							sealedEntity -> sealedEntity.pricesAvailable() ?
								sealedEntity.getPrices().stream().filter(Droppable::exists) : Stream.empty() :
							theEntity -> theEntity.getPrices().stream().filter(Droppable::exists);

					if (collectionType == null) {
						return singlePriceResult(proxyState.getProxyClass(), method, priceList, priceSupplier, resultWrapper);
					} else if (collectionType.isArray()) {
						return arrayOfPriceResult(proxyState.getProxyClass(), method, priceList, priceSupplier, resultWrapper);
					} else if (Set.class.isAssignableFrom(collectionType)) {
						return setOfPriceResult(proxyState.getProxyClass(), method, priceList, priceSupplier, resultWrapper);
					} else {
						return listOfPriceResult(proxyState.getProxyClass(), method, priceList, priceSupplier, resultWrapper);
					}
				}
			}
		);
	}

}
