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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityHasNoPricesException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Contract for classes that allow reading information about prices in {@link Entity} instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface PricesContract extends Versioned, Serializable {

	/**
	 * Computes a price for which the entity should be sold. Only indexed prices in requested currency, valid
	 * at the passed moment are taken into an account. Prices are also limited by the passed set of price lists and
	 * the first price found in the order of the requested price list ids will be returned.
	 */
	@Nonnull
	static Optional<PriceContract> computePriceForSale(@Nonnull Collection<PriceContract> entityPrices, @Nonnull PriceInnerRecordHandling innerRecordHandling, @Nonnull Currency currency, @Nullable OffsetDateTime atTheMoment, @Nonnull String[] priceListPriority, @Nonnull Predicate<PriceContract> filterPredicate) {
		if (entityPrices.isEmpty()) {
			return empty();
		}

		final Map<Serializable, Integer> pLists = createHashMap(priceListPriority.length);
		for (int i = 0; i < priceListPriority.length; i++) {
			final Serializable pList = priceListPriority[i];
			pLists.put(pList, i);
		}
		final Stream<PriceContract> pricesStream = entityPrices
			.stream()
			.filter(PriceContract::exists)
			.filter(PriceContract::sellable)
			.filter(it -> currency.equals(it.currency()))
			.filter(it -> ofNullable(atTheMoment).map(mmt -> it.validity() == null || it.validity().isValidFor(mmt)).orElse(true))
			.filter(it -> pLists.containsKey(it.priceList()));

		switch (innerRecordHandling) {
			case NONE -> {
				return pricesStream
					.min(Comparator.comparing(o -> pLists.get(o.priceList())))
					.filter(filterPredicate);
			}
			case FIRST_OCCURRENCE -> {
				final Map<Integer, List<PriceContract>> pricesByInnerId = pricesStream
					.collect(Collectors.groupingBy(it -> ofNullable(it.innerRecordId()).orElse(0)));
				return pricesByInnerId
					.values()
					.stream()
					.map(prices -> prices.stream()
						.min(Comparator.comparing(o -> pLists.get(o.priceList())))
						.orElse(null))
					.filter(Objects::nonNull)
					.filter(filterPredicate)
					.min(Comparator.comparing(PriceContract::priceWithTax));
			}
			case SUM -> {
				final List<PriceContract> innerRecordPrices = pricesStream
					.collect(Collectors.groupingBy(it -> ofNullable(it.innerRecordId()).orElse(0)))
					.values()
					.stream()
					.map(prices -> prices.stream()
						.min(Comparator.comparing(o -> pLists.get(o.priceList())))
						.orElse(null))
					.filter(Objects::nonNull)
					.toList();
				if (innerRecordPrices.isEmpty()) {
					return empty();
				} else {
					final PriceContract firstPrice = innerRecordPrices.get(0);
					// create virtual sum price
					final Price resultPrice = new Price(
						1, firstPrice.priceKey(), null,
						innerRecordPrices.stream().map(PriceContract::priceWithoutTax).reduce(BigDecimal::add).orElse(BigDecimal.ZERO),
						innerRecordPrices.stream().map(PriceContract::taxRate).reduce((tax, tax2) -> {
							Assert.isTrue(tax.compareTo(tax2) == 0, "Prices have to have same tax rate in order to compute selling price!");
							return tax;
						}).orElse(BigDecimal.ZERO),
						innerRecordPrices.stream().map(PriceContract::priceWithTax).reduce(BigDecimal::add).orElse(BigDecimal.ZERO),
						// computed virtual price has always no validity
						null,
						true
					);
					return filterPredicate.test(resultPrice) ? of(resultPrice) : empty();
				}
			}
			default -> throw new EvitaInternalError("Unknown price inner record handling mode: " + innerRecordHandling);
		}
	}

	/**
	 * Returns true if single price differs between first and second instance.
	 */
	static boolean anyPriceDifferBetween(@Nonnull PricesContract first, @Nonnull PricesContract second) {
		final Collection<PriceContract> thisValues = first.pricesAvailable() ? first.getPrices() : Collections.emptyList();
		final Collection<PriceContract> otherValues = second.pricesAvailable() ? second.getPrices() : Collections.emptyList();

		if (thisValues.size() != otherValues.size()) {
			return true;
		} else {
			return thisValues
				.stream()
				.anyMatch(it -> it.differsFrom(second.getPrice(it.priceId(), it.priceList(), it.currency()).orElse(null)));
		}
	}

	/**
	 * Returns true if entity prices were fetched along with the entity. Calling this method before calling any
	 * other method that requires prices to be fetched will allow you to avoid {@link ContextMissingException}.
	 *
	 * Method also returns false if the prices are not enabled for the entity by the schema. Checking this method
	 * also allows you to avoid {@link EntityHasNoPricesException} in such case.
	 */
	boolean pricesAvailable();

	/**
	 * Returns price by its business key identification.
	 *
	 * @param priceKey business key of the price
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nullable
	Optional<PriceContract> getPrice(@Nonnull PriceKey priceKey)
		throws ContextMissingException;

	/**
	 * Returns price by its business key identification.
	 *
	 * @param priceId   - identification of the price in the external systems
	 * @param priceList - identification of the price list (either external or internal)
	 * @param currency  - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	Optional<PriceContract> getPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency)
		throws ContextMissingException;

	/**
	 * Returns price by its business key identification.
	 *
	 * @param priceList - identification of the price list (either external or internal)
	 * @param currency  - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @throws UnexpectedResultCountException when there is more than one price for the specified price list and currency
	 * @throws ContextMissingException        when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	Optional<PriceContract> getPrice(@Nonnull String priceList, @Nonnull Currency currency)
		throws UnexpectedResultCountException, ContextMissingException;

	/**
	 * Returns all prices from the specified price list.
	 *
	 * @param priceList - identification of the price list (either external or internal)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default Collection<PriceContract> getPrices(@Nonnull String priceList) throws ContextMissingException {
		return getPrices()
			.stream()
			.filter(it -> priceList.equals(it.priceList()))
			.collect(Collectors.toList());
	}

	/**
	 * Returns all prices from the specified currency.
	 *
	 * @param currency - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default Collection<PriceContract> getPrices(@Nonnull Currency currency) throws ContextMissingException {
		return getPrices()
			.stream()
			.filter(it -> currency.equals(it.currency()))
			.collect(Collectors.toList());
	}

	/**
	 * Returns all prices from the specified currency.
	 *
	 * @param currency  - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param priceList - identification of the price list (either external or internal)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default Collection<PriceContract> getPrices(@Nonnull Currency currency, @Nonnull String priceList) throws ContextMissingException {
		return getPrices()
			.stream()
			.filter(it -> currency.equals(it.currency()) && priceList.equals(it.priceList()))
			.collect(Collectors.toList());
	}

	/**
	 * Returns a price for which the entity should be sold. Only indexed prices in requested currency, valid
	 * at the passed moment are taken into an account. Prices are also limited by the passed set of price lists and
	 * the first price found in the order of the requested price list ids will be returned.
	 *
	 * @param currency          - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param atTheMoment       - identification of the moment when the entity is about to be sold
	 * @param priceListPriority - identification of the price list (either external or internal)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default Optional<PriceContract> getPriceForSale(@Nonnull Currency currency, @Nullable OffsetDateTime atTheMoment, @Nonnull String... priceListPriority) throws ContextMissingException {
		return computePriceForSale(getPrices(), getPriceInnerRecordHandling(), currency, atTheMoment, priceListPriority, Objects::nonNull);
	}

	/**
	 * Returns true if the entity has context available so that calling {@link #getPriceForSale()} is possible without
	 * throwing an exception. The exception {@link ContextMissingException} might be still thrown from other methods
	 * when the input arguments refer to the data that might exist but were not fetched along with the entity.
	 *
	 * @see #pricesAvailable() for checking whether any of the prices were fetched
	 */
	boolean isPriceForSaleContextAvailable();

	/**
	 * Returns a price for which the entity should be sold. This method can be used only in context of a {@link Query}
	 * with price related constraints so that `currency` and `priceList` priority can be extracted from the query.
	 * The moment is either extracted from the query as well (if present) or current date and time is used.
	 *
	 * @throws ContextMissingException when entity is not related to any {@link Query} or the query
	 *                                 lacks price related constraints
	 */
	@Nonnull
	Optional<PriceContract> getPriceForSale() throws ContextMissingException;

	/**
	 * Returns a price for which the entity should be sold. This method can be used only in context of a {@link Query}
	 * with price related constraints so that `currency` and `priceList` priority can be extracted from the query.
	 * The moment is either extracted from the query as well (if present) or current date and time is used.
	 *
	 * This method is similar to {@link #getPriceForSale()} but doesn't throw an exception when the context is not
	 * present in the input query and just returns NULL result instead.
	 */
	@Nonnull
	Optional<PriceContract> getPriceForSaleIfAvailable();

	/**
	 * Returns all prices for which the entity could be sold. This method can be used in context of a {@link Query}
	 * with price related constraints so that `currency` and `priceList` priority can be extracted from the query.
	 * The moment is either extracted from the query as well (if present) or current date and time is used.
	 *
	 * The method differs from {@link #getPriceForSale()} in the sense of never returning {@link ContextMissingException}
	 * and returning list of all possibly matching selling prices (not only single one). Returned list may be also
	 * empty if there is no such price.
	 *
	 * @param currency          - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param atTheMoment       - identification of the moment when the entity is about to be sold
	 * @param priceListPriority - identification of the price list (either external or internal)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default List<PriceContract> getAllPricesForSale(@Nullable Currency currency, @Nullable OffsetDateTime atTheMoment, @Nullable String... priceListPriority)
		throws ContextMissingException {
		final Map<Serializable, Integer> pLists;
		if (ArrayUtils.isEmpty(priceListPriority)) {
			pLists = Collections.emptyMap();
		} else {
			pLists = createHashMap(priceListPriority.length);
			for (int i = 0; i < priceListPriority.length; i++) {
				pLists.put(priceListPriority[i], i);
			}
		}
		final Stream<PriceContract> priceStream = getPrices()
			.stream()
			.filter(PriceContract::exists)
			.filter(PriceContract::sellable)
			.filter(it -> currency == null || currency.equals(it.currency()))
			.filter(it -> ofNullable(atTheMoment).map(mmt -> it.validity() == null || it.validity().isValidFor(mmt)).orElse(true))
			.filter(it -> pLists.isEmpty() || pLists.containsKey(it.priceList()));
		if (pLists.isEmpty()) {
			return priceStream.collect(Collectors.toCollection(ArrayList::new));
		} else if (getPriceInnerRecordHandling() == PriceInnerRecordHandling.NONE) {
			return priceStream
				.min(Comparator.comparing(o -> pLists.get(o.priceList())))
				.map(List::of)
				.orElse(Collections.emptyList());
		} else {
			return new ArrayList<>(
				priceStream
					.collect(
						Collectors.toMap(
							price -> ofNullable(price.innerRecordId()).orElse(Integer.MIN_VALUE),
							Function.identity(),
							BinaryOperator.minBy(Comparator.comparingInt(o -> pLists.get(o.priceList())))
						)
					).values()
			);
		}
	}

	/**
	 * Returns all prices for which the entity could be sold. This method can be used in context of a {@link Query}
	 * with price related constraints so that `currency` and `priceList` priority can be extracted from the query.
	 * The moment is either extracted from the query as well (if present) or current date and time is used.
	 *
	 * The method differs from {@link #getPriceForSale()} in the sense of never returning {@link ContextMissingException}
	 * and returning list of all possibly matching selling prices (not only single one). Returned list may be also
	 * empty if there is no such price.
	 *
	 * @throws ContextMissingException when no prices were fetched along with entity but might exist, but is not thrown
	 *                                 when some (but not all) prices were fetched along with entity
	 */
	@Nonnull
	List<PriceContract> getAllPricesForSale() throws ContextMissingException;

	/**
	 * Returns a price for which the entity should be sold. Only indexed prices in requested currency, valid
	 * at the passed moment are taken into an account. Prices are also limited by the passed set of price lists and
	 * the first price found in the order of the requested price list ids will be returned.
	 *
	 * @param from              - lower bound of the price (inclusive)
	 * @param to                - upper bound of the price (inclusive)
	 * @param currency          - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param queryPriceMode    - controls whether price with or without tax is used
	 * @param atTheMoment       - identification of the moment when the entity is about to be sold
	 * @param priceListPriority - identification of the price list (either external or internal)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	default boolean hasPriceInInterval(@Nonnull BigDecimal from, @Nonnull BigDecimal to, @Nonnull QueryPriceMode queryPriceMode, @Nonnull Currency currency, @Nullable OffsetDateTime atTheMoment, @Nonnull String... priceListPriority)
		throws ContextMissingException {

		final Collection<PriceContract> entityPrices = getPrices();
		if (entityPrices.isEmpty()) {
			return false;
		}

		switch (getPriceInnerRecordHandling()) {
			case NONE, SUM -> {
				return getPriceForSale(currency, atTheMoment, priceListPriority)
					.map(it -> queryPriceMode == QueryPriceMode.WITHOUT_TAX ? it.priceWithoutTax() : it.priceWithTax())
					.map(it -> from.compareTo(it) <= 0 && to.compareTo(it) >= 0)
					.orElse(false);
			}
			case FIRST_OCCURRENCE -> {
				final Map<Serializable, Integer> pLists = createHashMap(priceListPriority.length);
				for (int i = 0; i < priceListPriority.length; i++) {
					final Serializable pList = priceListPriority[i];
					pLists.put(pList, i);
				}
				final Map<Integer, List<PriceContract>> pricesByInnerRecordId = entityPrices
					.stream()
					.filter(PriceContract::exists)
					.filter(PriceContract::sellable)
					.filter(it -> currency.equals(it.currency()))
					.filter(it -> ofNullable(atTheMoment).map(mmt -> it.validity() == null || it.validity().isValidFor(mmt)).orElse(true))
					.filter(it -> pLists.containsKey(it.priceList()))
					.collect(Collectors.groupingBy(it -> ofNullable(it.innerRecordId()).orElse(0)));
				return pricesByInnerRecordId
					.values()
					.stream()
					.anyMatch(prices -> prices.stream()
						.min(Comparator.comparing(o -> pLists.get(o.priceList())))
						.map(it -> queryPriceMode == QueryPriceMode.WITHOUT_TAX ? it.priceWithoutTax() : it.priceWithTax())
						.map(it -> from.compareTo(it) <= 0 && to.compareTo(it) >= 0)
						.orElse(null));
			}
			default ->
				throw new EvitaInternalError("Unknown price inner record handling mode: " + getPriceInnerRecordHandling());
		}
	}

	/**
	 * Returns a price for which the entity should be sold. Only indexed prices in requested currency, valid
	 * at the passed moment are taken into an account. Prices are also limited by the passed set of price lists and
	 * the first price found in the order of the requested price list ids will be returned.
	 *
	 * @param from           - lower bound of the price (inclusive)
	 * @param to             - upper bound of the price (inclusive)
	 * @param queryPriceMode - controls whether price with or without tax is used
	 * @throws ContextMissingException when entity is not related to any {@link Query} or the query
	 *                                 lacks price related constraints
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	boolean hasPriceInInterval(@Nonnull BigDecimal from, @Nonnull BigDecimal to, @Nonnull QueryPriceMode queryPriceMode)
		throws ContextMissingException;

	/**
	 * Returns all prices of the entity.
	 *
	 * @throws ContextMissingException when no prices were not fetched along with entity but might exist, the exception
	 *                                 is not thrown when some (but not all) prices were fetched along with entity
	 */
	@Nonnull
	Collection<PriceContract> getPrices() throws ContextMissingException;

	/**
	 * Returns price inner record handling that controls how prices that share same `inner entity id` will behave during
	 * filtering and sorting.
	 */
	@Nonnull
	PriceInnerRecordHandling getPriceInnerRecordHandling();

}
