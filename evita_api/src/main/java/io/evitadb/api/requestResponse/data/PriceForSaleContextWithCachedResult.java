/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleContext;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Optional.ofNullable;

/**
 * Describes context for computation of price for sale.
 */
public class PriceForSaleContextWithCachedResult implements PriceForSaleContext {
	/**
	 * List of price lists sorted by priority.
	 */
	@Nullable private final String[] priceListPriority;
	/**
	 * Currency used for price for sale calculation.
	 */
	@Nullable private final Currency currency;
	/**
	 * Moment used for price for sale calculation.
	 */
	@Nullable private final OffsetDateTime atTheMoment;
	/**
	 * List of accompanying prices that should be computed together with price for sale.
	 */
	@Nullable private final AccompanyingPrice[] accompanyingPrices;
	/**
	 * Cached result of price for sale calculation.
	 */
	private AtomicReference<PriceForSaleWithAccompanyingPrices> cachedResult;

	public PriceForSaleContextWithCachedResult(
		@Nullable String[] priceListPriority,
		@Nullable Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nullable AccompanyingPrice[] accompanyingPrices
	) {
		this.priceListPriority = priceListPriority;
		this.currency = currency;
		this.atTheMoment = atTheMoment;
		this.accompanyingPrices = accompanyingPrices;
	}

	public PriceForSaleContextWithCachedResult(
		@Nullable String[] priceListPriority,
		@Nullable Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nullable AccompanyingPrice[] accompanyingPrices,
		@Nonnull PriceForSaleWithAccompanyingPrices priceForSaleWithAccompanyingPrices
	) {
		this(priceListPriority, currency, atTheMoment, accompanyingPrices);
		this.cachedResult = new AtomicReference<>(priceForSaleWithAccompanyingPrices);
	}

	@Nonnull
	@Override
	public Optional<String[]> priceListPriority() {
		return ofNullable(this.priceListPriority);
	}

	@Nonnull
	@Override
	public Optional<Currency> currency() {
		return ofNullable(this.currency);
	}

	@Nonnull
	@Override
	public Optional<OffsetDateTime> atTheMoment() {
		return ofNullable(this.atTheMoment);
	}

	@Nonnull
	@Override
	public Optional<AccompanyingPrice[]> accompanyingPrices() {
		return ofNullable(this.accompanyingPrices);
	}

	/**
	 * Checks if the provided parameters match the current instance's attributes.
	 *
	 * @param currency the currency to compare with the instance's currency, must not be null
	 * @param atTheMoment the date and time to compare with the instance's date and time, can be null
	 * @param priceListPriority the price list priorities to compare with the instance's price list priorities, must not be null
	 * @return true if all provided parameters match the instance's attributes; false otherwise
	 */
	public boolean matches(
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority
	) {
		return Objects.equals(this.currency, currency) &&
			Objects.equals(this.atTheMoment, atTheMoment) &&
			Objects.deepEquals(this.priceListPriority, priceListPriority);
	}

	/**
	 * Computes price for sale with accompanying prices from the provided collection of prices.
	 * If the result is already cached, it returns the cached value. Method doesn't check the input price collection
	 * and inner record handling strategy consistency against cached value. Method is expected to be always called
	 * with the same parameters as the first call.
	 *
	 * @return an Optional containing the computed PriceForSaleWithAccompanyingPrices or empty if no valid price is found
	 */
	@Nonnull
	public Optional<PriceForSaleWithAccompanyingPrices> compute(
		@Nonnull Collection<PriceContract> prices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling
	) {
		if (this.cachedResult == null) {
			this.cachedResult = new AtomicReference<>(
				PricesContract.computePriceForSale(
					prices,
					innerRecordHandling,
					ofNullable(this.currency).orElseThrow(ContextMissingException::new),
					this.atTheMoment,
					ofNullable(this.priceListPriority).orElseThrow(ContextMissingException::new),
					Objects::nonNull,
					ofNullable(this.accompanyingPrices)
						.orElse(PricesContract.NO_ACCOMPANYING_PRICES)
				).orElse(null)
			);
		}
		return ofNullable(this.cachedResult.get());
	}
}
