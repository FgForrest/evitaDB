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

package io.evitadb.api.proxy.impl.entity;

import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.annotation.Price;
import io.evitadb.api.requestResponse.data.annotation.PriceForSale;
import io.evitadb.api.requestResponse.data.annotation.PriceForSaleRef;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;
import one.edee.oss.proxycian.utils.GenericsUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * TODO JNO - document me
 * TODO JNO - podporovat optional, možná i obecně pro atributy a tak
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetPriceMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	public static final GetPriceMethodClassifier INSTANCE = new GetPriceMethodClassifier();

	@Nonnull
	private static Map<Class<?>, Function<Object[], Object>> collectArgumentFetchers(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method
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
									"with @PriceForSale annotation, and contains multiple price lists (String) arguments! " +
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
									"with @PriceForSale annotation, and contains multiple price lists (String[]) arguments! " +
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
									"with @PriceForSale annotation, and contains multiple date and time " +
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
									"with @PriceForSale annotation, and contains multiple currency " +
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
						"with @PriceForSale annotation, and contains unsupported argument type `" + parameterType +
						"`!"
				);
			}
		}
		return argumentFetchers;
	}

	@Nullable
	private static Predicate<PriceContract> getPriceContractPredicate(
		@Nonnull Map<Class<?>, Function<Object[], Object>> argumentFetchers,
		@Nonnull Object[] args
	) {
		final List<Predicate<PriceContract>> pricePredicates = new LinkedList<>();
		ofNullable(argumentFetchers.get(String[].class))
			.map(it -> (String[]) it.apply(args))
			.filter(it -> !ArrayUtils.isEmpty(it))
			.map(it -> {
				final Set<String> allowedPriceLists = CollectionUtils.createHashSet(it.length);
				Collections.addAll(allowedPriceLists, it);
				return (Predicate<PriceContract>) priceContract -> allowedPriceLists.contains(priceContract.getPriceList());
			})
			.ifPresent(pricePredicates::add);
		ofNullable(argumentFetchers.get(Currency.class))
			.map(it -> (Currency) it.apply(args))
			.map(it -> (Predicate<PriceContract>) priceContract -> it.equals(priceContract.getCurrency()))
			.ifPresent(pricePredicates::add);
		ofNullable(argumentFetchers.get(OffsetDateTime.class))
			.map(it -> (OffsetDateTime) it.apply(args))
			.map(it -> (Predicate<PriceContract>) priceContract -> priceContract.isValidAt(it))
			.ifPresent(pricePredicates::add);

		return pricePredicates
			.stream()
			.reduce(Predicate::and)
			.orElse(null);
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singlePriceForSaleResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method
	) {
		if (method.getParameterCount() == 0) {
			return (entityClassifier, theMethod, args, theState, invokeSuper) ->
				theState.getSealedEntity().getPriceForSale().orElse(null);
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectArgumentFetchers(proxyClass, method);
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
				return theState.getSealedEntity()
					.getPriceForSale(currency, moment, priceLists)
					.orElse(null);
			};
		}
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfPriceForSaleResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method
	) {
		if (method.getParameterCount() == 0) {
			// TODO JNO - we should provide our own set, that reflects the entity builder state, but is immutable
			return (entityClassifier, theMethod, args, theState, invokeSuper) ->
				theState.getSealedEntity().getAllPricesForSale();
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectArgumentFetchers(proxyClass, method);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final Predicate<PriceContract> pricePredicate = getPriceContractPredicate(argumentFetchers, args);

				final List<PriceContract> allPricesForSale = theState.getSealedEntity().getAllPricesForSale();
				return pricePredicate == null ?
					allPricesForSale :
					allPricesForSale.stream().filter(pricePredicate).toList();
			};
		}
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfPriceForSaleResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method
	) {
		if (method.getParameterCount() == 0) {
			// TODO JNO - we should provide our own set, that reflects the entity builder state, but is immutable
			return (entityClassifier, theMethod, args, theState, invokeSuper) ->
				theState.getSealedEntity()
					.getAllPricesForSale()
					.stream()
					.collect(Collectors.toUnmodifiableSet());
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectArgumentFetchers(proxyClass, method);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final Predicate<PriceContract> pricePredicate = getPriceContractPredicate(argumentFetchers, args);

				final List<PriceContract> allPricesForSale = theState.getSealedEntity().getAllPricesForSale();
				return pricePredicate == null ?
					allPricesForSale.stream().collect(Collectors.toUnmodifiableSet()) :
					allPricesForSale.stream().filter(pricePredicate).collect(Collectors.toUnmodifiableSet());
			};
		}
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> arrayOfPriceForSaleResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method
	) {
		if (method.getParameterCount() == 0) {
			// TODO JNO - we should provide our own set, that reflects the entity builder state, but is immutable
			return (entityClassifier, theMethod, args, theState, invokeSuper) ->
				theState.getSealedEntity()
					.getAllPricesForSale()
					.toArray(PriceContract[]::new);
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectArgumentFetchers(proxyClass, method);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final Predicate<PriceContract> pricePredicate = getPriceContractPredicate(argumentFetchers, args);

				final List<PriceContract> allPricesForSale = theState.getSealedEntity().getAllPricesForSale();
				return pricePredicate == null ?
					allPricesForSale.toArray(PriceContract[]::new) :
					allPricesForSale.stream().filter(pricePredicate).toArray(PriceContract[]::new);
			};
		}
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singlePriceResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method,
		@Nullable String priceList
	) {
		if (method.getParameterCount() == 0) {
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final List<PriceContract> prices = theState.getSealedEntity()
					.getPrices()
					.stream()
					.filter(it -> priceList.equals(it.getPriceList()))
					.limit(2)
					.toList();
				if (prices.isEmpty()) {
					return null;
				} else if (prices.size() == 1) {
					return prices.get(0);
				} else {
					throw new UnexpectedResultCountException(
						(int) theState.getSealedEntity()
							.getPrices()
							.stream()
							.filter(it -> priceList.equals(it.getPriceList()))
							.count()
					);
				}
			};
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectArgumentFetchers(proxyClass, method);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final Predicate<PriceContract> argumentPredicate = getPriceContractPredicate(argumentFetchers, args);
				final Predicate<PriceContract> pricePredicate = ofNullable(priceList)
					.map(it -> {
						final Predicate<PriceContract> basePredicate = price -> priceList.equals(price.getPriceList());
						return argumentPredicate == null ? basePredicate : basePredicate.and(argumentPredicate);
					})
					.orElseGet(() -> argumentPredicate);
				final Collection<PriceContract> allPrices = theState.getSealedEntity().getPrices();

				final List<PriceContract> matchingPrices = pricePredicate == null ?
					(allPrices instanceof List<PriceContract> allPricesAsList ? allPricesAsList : allPrices.stream().toList()) :
					allPrices.stream().filter(pricePredicate).limit(2).toList();

				if (matchingPrices.isEmpty()) {
					return null;
				} else if (matchingPrices.size() == 1) {
					return matchingPrices.get(0);
				} else {
					throw new UnexpectedResultCountException(
						pricePredicate == null ?
							allPrices.size() :
							(int) allPrices.stream().filter(pricePredicate).count()
					);
				}
			};
		}
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfPriceResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method,
		@Nullable String priceList
	) {
		// TODO JNO - we should provide our own set, that reflects the entity builder state, but is immutable
		if (method.getParameterCount() == 0) {
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> theState.getSealedEntity()
				.getPrices()
				.stream()
				.filter(it -> priceList.equals(it.getPriceList()))
				.toList();
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectArgumentFetchers(proxyClass, method);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final Predicate<PriceContract> argumentPredicate = getPriceContractPredicate(argumentFetchers, args);
				final Predicate<PriceContract> pricePredicate = ofNullable(priceList)
					.map(it -> {
						final Predicate<PriceContract> basePredicate = price -> priceList.equals(price.getPriceList());
						return argumentPredicate == null ? basePredicate : basePredicate.and(argumentPredicate);
					})
					.orElseGet(() -> argumentPredicate);
				final Collection<PriceContract> allPrices = theState.getSealedEntity().getPrices();

				return pricePredicate == null ?
					(allPrices instanceof List<PriceContract> allPricesAsList ? allPricesAsList : allPrices.stream().toList()) :
					allPrices.stream().filter(pricePredicate).limit(2).toList();
			};
		}
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfPriceResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method,
		@Nullable String priceList
	) {
		// TODO JNO - we should provide our own set, that reflects the entity builder state, but is immutable
		if (method.getParameterCount() == 0) {
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> theState.getSealedEntity()
				.getPrices()
				.stream()
				.filter(it -> priceList.equals(it.getPriceList()))
				.collect(Collectors.toUnmodifiableSet());
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectArgumentFetchers(proxyClass, method);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final Predicate<PriceContract> argumentPredicate = getPriceContractPredicate(argumentFetchers, args);
				final Predicate<PriceContract> pricePredicate = ofNullable(priceList)
					.map(it -> {
						final Predicate<PriceContract> basePredicate = price -> priceList.equals(price.getPriceList());
						return argumentPredicate == null ? basePredicate : basePredicate.and(argumentPredicate);
					})
					.orElseGet(() -> argumentPredicate);
				final Collection<PriceContract> allPrices = theState.getSealedEntity().getPrices();

				return pricePredicate == null ?
					allPrices.stream().collect(Collectors.toUnmodifiableSet()) :
					allPrices.stream().filter(pricePredicate).collect(Collectors.toUnmodifiableSet());
			};
		}
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> arrayOfPriceResult(
		@Nonnull Class<?> proxyClass,
		@Nonnull Method method,
		@Nullable String priceList
	) {
		// TODO JNO - we should provide our own set, that reflects the entity builder state, but is immutable
		if (method.getParameterCount() == 0) {
			if (priceList == null) {
				return (entityClassifier, theMethod, args, theState, invokeSuper) -> theState.getSealedEntity()
					.getPrices()
					.toArray(PriceContract[]::new);
			} else {
				return (entityClassifier, theMethod, args, theState, invokeSuper) -> theState.getSealedEntity()
					.getPrices()
					.stream()
					.filter(it -> priceList.equals(it.getPriceList()))
					.toArray(PriceContract[]::new);
			}
		} else {
			final Map<Class<?>, Function<Object[], Object>> argumentFetchers = collectArgumentFetchers(proxyClass, method);
			return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
				final Predicate<PriceContract> argumentPredicate = getPriceContractPredicate(argumentFetchers, args);
				final Predicate<PriceContract> pricePredicate = ofNullable(priceList)
					.map(it -> {
						final Predicate<PriceContract> basePredicate = price -> priceList.equals(price.getPriceList());
						return argumentPredicate == null ? basePredicate : basePredicate.and(argumentPredicate);
					})
					.orElseGet(() -> argumentPredicate);
				final Collection<PriceContract> allPrices = theState.getSealedEntity().getPrices();

				return pricePredicate == null ?
					allPrices.toArray(PriceContract[]::new) :
					allPrices.stream().filter(pricePredicate).toArray(PriceContract[]::new);
			};
		}
	}

	public GetPriceMethodClassifier() {
		super(
			"getParentEntity",
			(method, proxyState) -> {
				if (!ClassUtils.isAbstractOrDefault(method)) {
					return null;
				}
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final PriceForSale priceForSale = reflectionLookup.getAnnotationInstance(method, PriceForSale.class);
				final PriceForSaleRef priceForSaleRef = reflectionLookup.getAnnotationInstance(method, PriceForSaleRef.class);
				final Price price = reflectionLookup.getAnnotationInstance(method, Price.class);

				if (priceForSale == null && priceForSaleRef == null && price == null) {
					return null;
				}

				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
				@SuppressWarnings("rawtypes") final Class collectionType;
				@SuppressWarnings("rawtypes") final Class itemType;
				if (Collection.class.equals(returnType) || List.class.isAssignableFrom(returnType) || Set.class.isAssignableFrom(returnType)) {
					collectionType = returnType;
					itemType = GenericsUtils.getGenericTypeFromCollection(method.getDeclaringClass(), method.getGenericReturnType());
				} else if (returnType.isArray()) {
					collectionType = returnType;
					itemType = returnType.getComponentType();
				} else {
					collectionType = null;
					itemType = returnType;
				}

				Assert.isTrue(
					PriceContract.class.isAssignableFrom(itemType),
					() -> new EntityClassInvalidException(
						proxyState.getProxyClass(),
						"Entity class type `" + proxyState.getProxyClass() + "` method `" + method + "` is annotated " +
							"with price annotation, but the return class `" + returnType + "`. Only PriceContract return " +
							"type is supported!"
					)
				);

				if (priceForSale != null || priceForSaleRef != null) {
					if (collectionType == null) {
						return singlePriceForSaleResult(proxyState.getProxyClass(), method);
					} else if (collectionType.isArray()) {
						return arrayOfPriceForSaleResult(proxyState.getProxyClass(), method);
					} else if (Set.class.isAssignableFrom(collectionType)) {
						return setOfPriceForSaleResult(proxyState.getProxyClass(), method);
					} else {
						return listOfPriceForSaleResult(proxyState.getProxyClass(), method);
					}
				} else {
					final String priceList = price.priceList().isBlank() ?
						null : price.priceList();

					if (collectionType == null) {
						return singlePriceResult(proxyState.getProxyClass(), method, priceList);
					} else if (collectionType.isArray()) {
						return arrayOfPriceResult(proxyState.getProxyClass(), method, priceList);
					} else if (Set.class.isAssignableFrom(collectionType)) {
						return setOfPriceResult(proxyState.getProxyClass(), method, priceList);
					} else {
						return listOfPriceResult(proxyState.getProxyClass(), method, priceList);
					}
				}
			}
		);
	}

}
