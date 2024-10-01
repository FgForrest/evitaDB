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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityHasNoPricesException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.structure.CumulatedPrice;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
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
	AccompanyingPrice[] NO_ACCOMPANYING_PRICES = new AccompanyingPrice[0];

	/**
	 * Computes a price for which the entity should be sold. Only indexed prices in requested currency, valid
	 * at the passed moment are taken into an account. Prices are also limited by the passed set of price lists and
	 * the first price found in the order of the requested price list ids will be returned.
	 */
	@Nonnull
	static Optional<PriceContract> computePriceForSale(
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull Predicate<PriceContract> filterPredicate
	) {
		return computePriceForSale(
			entityPrices, innerRecordHandling, currency, atTheMoment, priceListPriority,
			filterPredicate, NO_ACCOMPANYING_PRICES
		)
			.map(PriceForSaleWithAccompanyingPrices::priceForSale);
	}

	/**
	 * Computes a price for which the entity should be sold. Only indexed prices in requested currency, valid
	 * at the passed moment are taken into an account. Prices are also limited by the passed set of price lists and
	 * the first price found in the order of the requested price list ids will be returned.
	 */
	@Nonnull
	static Optional<PriceForSaleWithAccompanyingPrices> computePriceForSale(
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull Predicate<PriceContract> filterPredicate,
		@Nonnull AccompanyingPrice[] accompanyingPrices
	) {
		if (entityPrices.isEmpty()) {
			return empty();
		}

		final Map<String, Integer> priorityIndex = getPriceListPriorityIndex(priceListPriority);
		final Stream<PriceContract> pricesStream = entityPrices
			.stream()
			.filter(PriceContract::exists)
			.filter(PriceContract::indexed)
			.filter(it -> currency.equals(it.currency()))
			.filter(it -> ofNullable(atTheMoment).map(mmt -> it.validity() == null || it.validity().isValidFor(mmt)).orElse(true));

		switch (innerRecordHandling) {
			case NONE -> {
				final Optional<PriceContract> priceForSale = pricesStream
					.filter(it -> priorityIndex.containsKey(it.priceList()))
					.min(Comparator.comparing(o -> priorityIndex.get(o.priceList())))
					.filter(filterPredicate);
				return priceForSale
					.map(
						priceContract -> new PriceForSaleWithAccompanyingPrices(
							priceContract,
							calculateAccompanyingPricesForNoneInnerRecordHandling(
								entityPrices, currency, atTheMoment, accompanyingPrices
							)
						)
					);
			}
			case LOWEST_PRICE -> {
				final Map<Integer, List<PriceContract>> pricesByInnerId = pricesStream
					.collect(Collectors.groupingBy(it -> ofNullable(it.innerRecordId()).orElse(0)));
				final Optional<PriceContract> priceForSale = pricesByInnerId
					.values()
					.stream()
					.map(prices -> prices.stream()
						.filter(it -> priorityIndex.containsKey(it.priceList()))
						.min(Comparator.comparing(o -> priorityIndex.get(o.priceList())))
						.orElse(null))
					.filter(Objects::nonNull)
					.filter(filterPredicate)
					.min(Comparator.comparing(PriceContract::priceWithTax));
				return priceForSale
					.map(
						priceContract -> new PriceForSaleWithAccompanyingPrices(
							priceContract,
							calculateAccompanyingPricesForLowestPriceInnerRecordHandling(
								pricesByInnerId.get(priceContract.innerRecordId()), accompanyingPrices
							)
						)
					);
			}
			case SUM -> {
				final List<PriceContract> pricesToSum = pricesStream
					.collect(Collectors.groupingBy(it -> ofNullable(it.innerRecordId()).orElse(0)))
					.values()
					.stream()
					.map(prices -> prices.stream()
						.filter(it -> priorityIndex.containsKey(it.priceList()))
						.min(Comparator.comparing(o -> priorityIndex.get(o.priceList())))
						.orElse(null))
					.filter(Objects::nonNull)
					.toList();
				if (pricesToSum.isEmpty()) {
					return empty();
				} else {
					final PriceContract priceForSale = calculateSumPrice(pricesToSum);
					return filterPredicate.test(priceForSale) ?
						of(
							new PriceForSaleWithAccompanyingPrices(
								priceForSale,
								calculateAccompanyingPricesForSumInnerRecordHandling(
									priceForSale,  entityPrices, currency, atTheMoment, accompanyingPrices
								)
							)
						) : empty();
				}
			}
			default ->
				throw new GenericEvitaInternalError("Unknown price inner record handling mode: " + innerRecordHandling);
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
	 * Method will calculate all required accompanying prices for the entity. The method is used when the entity
	 * has no inner record handling.
	 *
	 * @param entityPrices       source collection of all entity prices
	 * @param currency           currency used for price for sale calculation
	 * @param atTheMoment        moment used for price for sale calculation
	 * @param accompanyingPrices array of requirements for accompanying prices
	 * @return map of calculated accompanying prices
	 */
	@Nonnull
	private static Map<String, Optional<PriceContract>> calculateAccompanyingPricesForNoneInnerRecordHandling(
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull AccompanyingPrice[] accompanyingPrices
	) {
		if (accompanyingPrices.length > 0) {
			final List<PriceContract> accompanyingPriceBaseCollection = entityPrices
				.stream()
				.filter(PriceContract::exists)
				.filter(it -> currency.equals(it.currency()))
				.filter(it -> ofNullable(atTheMoment).map(mmt -> it.validity() == null || it.validity().isValidFor(mmt)).orElse(true))
				.toList();
			return Arrays.stream(accompanyingPrices)
				.collect(
					Collectors.toMap(
						AccompanyingPrice::priceName,
						accompanyingPrice -> {
							final Map<String, Integer> accompanyingPriorityIndex = getPriceListPriorityIndex(
								accompanyingPrice.priceListPriority()
							);
							return accompanyingPriceBaseCollection
								.stream()
								.filter(it -> accompanyingPriorityIndex.containsKey(it.priceList()))
								.min(Comparator.comparing(o -> accompanyingPriorityIndex.get(o.priceList())));
						}
					)
				);
		} else {
			return Collections.emptyMap();
		}
	}

	/**
	 * Method will calculate all required accompanying prices for the entity. The method is used when the entity
	 * has lowest price record handling.
	 *
	 * @param entityPrices       source collection of all entity prices
	 * @param accompanyingPrices array of requirements for accompanying prices
	 * @return map of calculated accompanying prices
	 */
	@Nonnull
	private static Map<String, Optional<PriceContract>> calculateAccompanyingPricesForLowestPriceInnerRecordHandling(
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull AccompanyingPrice[] accompanyingPrices
	) {
		if (accompanyingPrices.length > 0) {
			return Arrays.stream(accompanyingPrices)
				.collect(
					Collectors.toMap(
						AccompanyingPrice::priceName,
						accompanyingPrice -> {
							final Map<String, Integer> accompanyingPriorityIndex = getPriceListPriorityIndex(
								accompanyingPrice.priceListPriority()
							);
							return entityPrices.stream()
								.filter(it -> accompanyingPriorityIndex.containsKey(it.priceList()))
								.min(Comparator.comparing(o -> accompanyingPriorityIndex.get(o.priceList())));
						}
					)
				);
		} else {
			return Collections.emptyMap();
		}
	}

	/**
	 * Method will calculate all required accompanying prices for the entity. The method is used when the entity
	 * has no inner record handling.
	 *
	 * @param priceForSale       price for sale
	 * @param entityPrices       source collection of all entity prices
	 * @param currency           currency used for price for sale calculation
	 * @param atTheMoment        moment used for price for sale calculation
	 * @param accompanyingPrices array of requirements for accompanying prices
	 * @return map of calculated accompanying prices
	 */
	@Nonnull
	private static Map<String, Optional<PriceContract>> calculateAccompanyingPricesForSumInnerRecordHandling(
		@Nonnull PriceContract priceForSale,
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull AccompanyingPrice[] accompanyingPrices
	) {
		if (accompanyingPrices.length > 0) {
			final Collection<Map<String, PriceContract>> accompanyingPriceBaseCollection = entityPrices
				.stream()
				.filter(PriceContract::exists)
				.filter(it -> currency.equals(it.currency()))
				.filter(it -> ofNullable(atTheMoment).map(mmt -> it.validity() == null || it.validity().isValidFor(mmt)).orElse(true))
				.filter(it -> it.innerRecordId() == null || priceForSale.relatesTo(it))
				.collect(
					Collectors.groupingBy(
						it -> ofNullable(it.innerRecordId()).orElse(0),
						Collectors.toMap(PriceContract::priceList, Function.identity())
					)
				)
				.values();
			return Arrays.stream(accompanyingPrices)
				.collect(
					Collectors.toMap(
						AccompanyingPrice::priceName,
						accompanyingPrice -> {
							final Map<String, Integer> accompanyingPriorityIndex = getPriceListPriorityIndex(
								accompanyingPrice.priceListPriority()
							);
							final List<PriceContract> pricesToSum = accompanyingPriceBaseCollection
								.stream()
								.map(
									it -> it.values().stream().filter(prices -> accompanyingPriorityIndex.containsKey(prices.priceList()))
									.min(Comparator.comparing(o -> accompanyingPriorityIndex.get(o.priceList())))
								)
								.filter(Optional::isPresent)
								.map(Optional::get)
								.collect(Collectors.toCollection(ArrayList::new));

							if (pricesToSum.isEmpty()) {
								return empty();
							} else {
								if (priceForSale instanceof CumulatedPrice cumulatedPrice && pricesToSum.size() < cumulatedPrice.innerRecordPrices().size()) {
									// the reference prices are not complete,
									// we cannot calculate the sum price without adding prices of missing components
									final Set<Integer> componentsWithReferencePrice = pricesToSum
										.stream()
										.map(PriceContract::innerRecordId)
										.collect(Collectors.toSet());
									cumulatedPrice.innerRecordPrices().entrySet().stream()
										.filter(it -> !componentsWithReferencePrice.contains(it.getKey()))
										.map(Map.Entry::getValue)
										.forEach(pricesToSum::add);
								}
								return of(calculateSumPrice(pricesToSum));
							}
						}
					)
				);
		} else {
			return Collections.emptyMap();
		}
	}

	/**
	 * Calculates a virtual price that is a sum of all prices in the list.
	 *
	 * @param pricesToSum list of prices to sum
	 * @return virtual price that is a sum of all prices in the list
	 */
	@Nonnull
	private static PriceContract calculateSumPrice(@Nonnull List<PriceContract> pricesToSum) {
		final PriceContract firstPrice = pricesToSum.get(0);
		// create virtual sum price
		return new CumulatedPrice(
			1, firstPrice.priceKey(),
			pricesToSum.stream().collect(Collectors.toMap(PriceContract::innerRecordId, Function.identity())),
			pricesToSum.stream().map(PriceContract::priceWithoutTax).reduce(BigDecimal::add).orElse(BigDecimal.ZERO),
			pricesToSum.stream().map(PriceContract::taxRate).reduce((tax, tax2) -> {
				Assert.isTrue(tax.compareTo(tax2) == 0, "Prices have to have same tax rate in order to compute selling price!");
				return tax;
			}).orElse(BigDecimal.ZERO),
			pricesToSum.stream().map(PriceContract::priceWithTax).reduce(BigDecimal::add).orElse(BigDecimal.ZERO)
		);
	}

	/**
	 * Creates a map of price list priorities where the key is the price list and the value is the priority.
	 *
	 * @param priceListPriority array of price list priorities
	 * @return map of price list priorities
	 */
	@Nonnull
	private static Map<String, Integer> getPriceListPriorityIndex(@Nonnull String[] priceListPriority) {
		final Map<String, Integer> pLists = createHashMap(priceListPriority.length);
		for (int i = 0; i < priceListPriority.length; i++) {
			final String pList = priceListPriority[i];
			pLists.put(pList, i);
		}
		return pLists;
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
	default Optional<PriceContract> getPriceForSale(
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String... priceListPriority
	) throws ContextMissingException {
		return computePriceForSale(getPrices(), getPriceInnerRecordHandling(), currency, atTheMoment, priceListPriority, Objects::nonNull);
	}

	/**
	 * Returns a price for which the entity should be sold. Only indexed prices in requested currency, valid
	 * at the passed moment are taken into an account. Prices are also limited by the passed set of price lists and
	 * the first price found in the order of the requested price list ids will be returned.
	 *
	 * This method allows to calculate also additional accompanying prices that relate to the selected price for sale
	 * and adhere to particular price inner record handling logic.
	 *
	 * @param currency                  - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param atTheMoment               - identification of the moment when the entity is about to be sold
	 * @param priceListPriority         - identification of the price list (either external or internal)
	 * @param accompanyingPricesRequest - array of requirements for calculation of accompanying prices
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default Optional<PriceForSaleWithAccompanyingPrices> getPriceForSaleWithAccompanyingPrices(
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull AccompanyingPrice[] accompanyingPricesRequest
	) throws ContextMissingException {
		return computePriceForSale(
			getPrices(), getPriceInnerRecordHandling(), currency, atTheMoment, priceListPriority, Objects::nonNull,
			accompanyingPricesRequest
		);
	}

	/**
	 * Returns a price for which the entity should be sold. This method can be used only in context of a {@link Query}
	 * with price related constraints so that `currency` and `priceList` priority can be extracted from the query.
	 * The moment is either extracted from the query as well (if present) or current date and time is used.
	 *
	 * This method allows to calculate also additional accompanying prices that relate to the selected price for sale
	 * and adhere to particular price inner record handling logic.
	 *
	 * @param accompanyingPricesRequest - array of requirements for calculation of accompanying prices
	 * @throws ContextMissingException when entity is not related to any {@link Query} or the query
	 *                                 lacks price related constraints
	 */
	@Nonnull
	default Optional<PriceForSaleWithAccompanyingPrices> getPriceForSaleWithAccompanyingPrices(
		@Nonnull AccompanyingPrice[] accompanyingPricesRequest
	) throws ContextMissingException {
		final PriceForSaleContext context = getPriceForSaleContext().orElseThrow(ContextMissingException::new);
		return computePriceForSale(
			getPrices(), getPriceInnerRecordHandling(),
			context.currency(), context.atTheMoment(), context.priceListPriority(), Objects::nonNull,
			accompanyingPricesRequest
		);
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
	 * Returns context used for calculation of the {@link #getPriceForSale()} method. The context is available only
	 * when the entity is related to a {@link Query} with price related constraints so that `currency` and `priceList`
	 * priority can be extracted from the query.
	 *
	 * @return context used for calculation of the {@link #getPriceForSale()} method
	 */
	@Nonnull
	Optional<PriceForSaleContext> getPriceForSaleContext();

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
	default List<PriceContract> getAllPricesForSale(@Nonnull Currency currency, @Nullable OffsetDateTime atTheMoment, @Nonnull String... priceListPriority)
		throws ContextMissingException {

		final PriceInnerRecordHandling priceInnerRecordHandling = getPriceInnerRecordHandling();
		if (priceInnerRecordHandling == PriceInnerRecordHandling.LOWEST_PRICE) {
			// in case of lowest price inner record handling there might be multiple prices for sale - for each inner record id
			return getAllPricesForSaleForLowestPrice(
				PriceForSaleWithAccompanyingPrices::priceForSale, currency, atTheMoment, priceListPriority,
				NO_ACCOMPANYING_PRICES
			);
		} else {
			// in all other cases there will be always exactly one price - the selling one
			return getPriceForSale(currency, atTheMoment, priceListPriority).map(List::of).orElse(Collections.emptyList());
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
	 * This method allows to calculate also additional accompanying prices that relate to the selected price for sale
	 * and adhere to particular price inner record handling logic.
	 *
	 * @param currency                  - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param atTheMoment               - identification of the moment when the entity is about to be sold
	 * @param priceListPriority         - identification of the price list (either external or internal)
	 * @param accompanyingPricesRequest - array of requirements for calculation of accompanying prices
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default List<PriceForSaleWithAccompanyingPrices> getAllPricesForSaleWithAccompanyingPrices(
		@Nullable Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nullable String[] priceListPriority,
		@Nonnull AccompanyingPrice[] accompanyingPricesRequest
	) {
		final PriceInnerRecordHandling priceInnerRecordHandling = getPriceInnerRecordHandling();
		if (priceInnerRecordHandling == PriceInnerRecordHandling.LOWEST_PRICE) {
			// in case of lowest price inner record handling there might be multiple prices for sale - for each inner record id
			return getAllPricesForSaleForLowestPrice(
				Function.identity(), currency, atTheMoment, priceListPriority, accompanyingPricesRequest
			);
		} else {
			// in all other cases there will be always exactly one price - the selling one
			return getPriceForSaleWithAccompanyingPrices(
				currency, atTheMoment, priceListPriority, accompanyingPricesRequest
			)
				.map(List::of)
				.orElse(Collections.emptyList());
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
			case LOWEST_PRICE -> {
				final Map<String, Integer> pLists = getPriceListPriorityIndex(priceListPriority);
				final Map<Integer, List<PriceContract>> pricesByInnerRecordId = entityPrices
					.stream()
					.filter(PriceContract::exists)
					.filter(PriceContract::indexed)
					.filter(it -> currency.equals(it.currency()))
					.filter(it -> ofNullable(atTheMoment).map(mmt -> it.validity() == null || it.validity().isValidFor(mmt)).orElse(true))
					.filter(it -> pLists.containsKey(it.priceList()))
					.collect(Collectors.groupingBy(it -> ofNullable(it.innerRecordId()).orElse(0)));
				return pricesByInnerRecordId
					.values()
					.stream()
					.anyMatch(prices -> {
						final Optional<PriceContract> minPrice = prices.stream()
							.min(Comparator.comparing(o -> pLists.get(o.priceList())));
						return minPrice
							.map(it -> queryPriceMode == QueryPriceMode.WITHOUT_TAX ? it.priceWithoutTax() : it.priceWithTax())
							.map(it -> from.compareTo(it) <= 0 && to.compareTo(it) >= 0)
							.orElse(null);
					});
			}
			default ->
				throw new GenericEvitaInternalError("Unknown price inner record handling mode: " + getPriceInnerRecordHandling());
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

	/**
	 * Internal method that calculates accompanying prices for {@link PriceInnerRecordHandling#LOWEST_PRICE} strategy.
	 * For each inner record id it calculates price for sale, and for each accompanying price request, it calculates
	 * another price for the very same inner record is using different price lists setting and not taking the sellability
	 * of the price into an account.
	 *
	 * @param mapper             transformer function for the result type of the method
	 * @param currency           currency used for price for sale calculation
	 * @param atTheMoment        moment used for price for sale calculation
	 * @param priceListPriority  identification of the price lists (either external or internal) sorted by priority
	 * @param accompanyingPrices array of requirements for accompanying prices
	 * @param <T>                type of the result
	 * @return list of results of the calculation mapped by transformation function
	 */
	@Nonnull
	private <T> List<T> getAllPricesForSaleForLowestPrice(
		@Nonnull Function<PriceForSaleWithAccompanyingPrices, T> mapper,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull AccompanyingPrice... accompanyingPrices
	) {
		final Map<String, Integer> priorityIndex = getPriceListPriorityIndex(priceListPriority);
		final Map<Integer, List<PriceContract>> pricesByInnerId = getPrices()
			.stream()
			.filter(PriceContract::exists)
			.filter(it -> currency.equals(it.currency()))
			.filter(it -> ofNullable(atTheMoment).map(mmt -> it.validity() == null || it.validity().isValidFor(mmt)).orElse(true))
			.collect(Collectors.groupingBy(it -> ofNullable(it.innerRecordId()).orElse(0)));
		final List<PriceContract> pricesForSale = pricesByInnerId
			.values()
			.stream()
			.map(prices -> prices.stream()
				.filter(PriceContract::indexed)
				.filter(it -> priorityIndex.containsKey(it.priceList()))
				.min(Comparator.comparing(o -> priorityIndex.get(o.priceList())))
				.orElse(null))
			.filter(Objects::nonNull)
			.toList();
		return pricesForSale
			.stream()
			.map(
				priceContract -> new PriceForSaleWithAccompanyingPrices(
					priceContract,
					calculateAccompanyingPricesForLowestPriceInnerRecordHandling(
						pricesByInnerId.get(priceContract.innerRecordId()), accompanyingPrices
					)
				)
			)
			.map(mapper)
			.toList();
	}

	/**
	 * Describes requirement for computation of additional prices along with the price for sale.
	 *
	 * @param priceName         name of the price to distinguish it from other accompanying prices
	 * @param priceListPriority the priority of price lists that will be used to lookup for the price related to price
	 *                          for sale.
	 */
	record AccompanyingPrice(
		@Nonnull String priceName,
		@Nonnull String... priceListPriority
	) {
	}

	/**
	 * Return type where both the price for sale and accompanying prices are returned.
	 *
	 * @param priceForSale       price for which the entity should be sold
	 * @param accompanyingPrices accompanying prices that were computed along with the price for sale
	 */
	record PriceForSaleWithAccompanyingPrices(
		@Nonnull PriceContract priceForSale,
		@Nonnull Map<String, Optional<PriceContract>> accompanyingPrices
	) {
	}

	/**
	 * Describes context for computation of price for sale.
	 *
	 * @param priceListPriority list of price lists sorted by priority
	 * @param currency currency used for price for sale calculation
	 * @param atTheMoment moment used for price for sale calculation
	 */
	record PriceForSaleContext(
		@Nonnull String[] priceListPriority,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment
	) {}

}
