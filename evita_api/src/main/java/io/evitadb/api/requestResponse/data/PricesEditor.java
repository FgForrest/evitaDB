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

import io.evitadb.api.exception.AmbiguousPriceException;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.Prices;
import io.evitadb.dataType.DateTimeRange;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.stream.Stream;

/**
 * Contract for classes that allow creating / updating or removing information about prices in {@link Entity} instance.
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link PricesContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface PricesEditor<W extends PricesEditor<W>> extends PricesContract {

	/**
	 * Creates or updates price with key properties: priceId, priceList, currency.
	 * Beware! If priceId and currency stays the same, but only price list changes (although it's unlikely), you need
	 * to {@link #removePrice(int, String, Currency)} first and set it with new price list.
	 *
	 * @param priceId         - identification of the price in the external systems
	 * @param priceList       - identification of the price list (either external or internal {@link Entity#getPrimaryKey()}
	 * @param currency        - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param priceWithoutTax - price without tax
	 * @param taxRate         - tax percentage (i.e. for 19% it'll be 19.00)
	 * @param priceWithTax    - price with tax
	 * @param sellable        - controls whether price is subject to filtering / sorting logic ({@see Price#sellable})
	 * @return builder instance to allow command chaining
	 * @throws AmbiguousPriceException when there are two prices in same price list and currency which validities overlap
	 */
	W setPrice(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		boolean sellable
	) throws AmbiguousPriceException;

	/**
	 * Creates or updates price with key properties: priceId, priceList, currency.
	 * Beware! If priceId and currency stays the same, but only price list changes (although it's unlikely), you need
	 * to {@link #removePrice(int, String, Currency)} first and set it with new price list.
	 *
	 * @param priceId         - identification of the price in the external systems
	 * @param priceList       - identification of the price list (either external or internal {@link Entity#getPrimaryKey()}
	 * @param currency        - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param innerRecordId   - sub-record identification {@see Price#innerRecordId}, must be positive value
	 * @param priceWithoutTax - price without tax
	 * @param taxRate         - tax rate percentage (i.e. for 19% it'll be 19.00)
	 * @param priceWithTax    - price with tax
	 * @param sellable        - controls whether price is subject to filtering / sorting logic ({@see Price#sellable})
	 * @return builder instance to allow command chaining
	 * @throws AmbiguousPriceException when there are two prices in same price list and currency which validities overlap
	 */
	W setPrice(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		boolean sellable
	) throws AmbiguousPriceException;

	/**
	 * Creates or updates price with key properties: priceId, priceList, currency.
	 * Beware! If priceId and currency stays the same, but only price list changes (although it's unlikely), you need
	 * to {@link #removePrice(int, String, Currency)} first and set it with new price list.
	 *
	 * @param priceId         - identification of the price in the external systems
	 * @param priceList       - identification of the price list (either external or internal {@link Entity#getPrimaryKey()}
	 * @param currency        - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param priceWithoutTax - price without tax
	 * @param taxRate         - tax rate percentage (i.e. for 19% it'll be 19.00)
	 * @param priceWithTax    - price with tax
	 * @param validity        - date and time interval for which the price is valid (inclusive)
	 * @param sellable        - controls whether price is subject to filtering / sorting logic ({@see Price#sellable})
	 * @return builder instance to allow command chaining
	 * @throws AmbiguousPriceException when there are two prices in same price list and currency which validities overlap
	 */
	W setPrice(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean sellable
	) throws AmbiguousPriceException;

	/**
	 * Creates or updates price with key properties: priceId, priceList, currency.
	 * Beware! If priceId and currency stays the same, but only price list changes (although it's unlikely), you need
	 * to {@link #removePrice(int, String, Currency)} first and set it with new price list.
	 *
	 * @param priceId         - identification of the price in the external systems
	 * @param priceList       - identification of the price list (either external or internal {@link Entity#getPrimaryKey()}
	 * @param currency        - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param innerRecordId   - sub-record identification {@see Price#innerRecordId}, must be positive value
	 * @param priceWithoutTax - price without tax
	 * @param taxRate         - tax rate percentage (i.e. for 19% it'll be 19.00)
	 * @param priceWithTax    - price with tax
	 * @param validity        - date and time interval for which the price is valid (inclusive)
	 * @param indexed         - controls whether price is subject to filtering / sorting logic ({@see Price#indexed})
	 * @return builder instance to allow command chaining
	 * @throws AmbiguousPriceException when there are two prices in same price list and currency which validities overlap
	 */
	W setPrice(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean indexed
	) throws AmbiguousPriceException;

	/**
	 * Creates or updates price with key properties: priceId, priceList, currency.
	 * Beware! If priceId and currency stays the same, but only price list changes (although it's unlikely), you need
	 * to {@link #removePrice(int, String, Currency)} first and set it with new price list.
	 *
	 * @param price - the price to set
	 * @return builder instance to allow command chaining
	 * @throws AmbiguousPriceException when there are two prices in same price list and currency which validities overlap
	 */
	default W setPrice(
		@Nonnull PriceContract price
	) throws AmbiguousPriceException {
		return setPrice(
			price.priceId(), price.priceList(), price.currency(), price.innerRecordId(),
			price.priceWithoutTax(), price.taxRate(), price.priceWithTax(), price.validity(), price.indexed()
		);
	}

	/**
	 * Removes existing price by specifying key properties.
	 *
	 * @param priceId   - identification of the price in the external systems
	 * @param priceList - identification of the price list (either external or internal {@link Entity#getPrimaryKey()}
	 * @param currency  - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @return builder instance to allow command chaining
	 */
	W removePrice(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency
	);

	/**
	 * Removes existing price by specifying key properties.
	 *
	 * @param priceKey - key properties of the price
	 * @return builder instance to allow command chaining
	 */
	default W removePrice(
		@Nonnull PriceKey priceKey
	) {
		return removePrice(priceKey.priceId(), priceKey.priceList(), priceKey.currency());
	}

	/**
	 * Removes all prices that are currently set on the entity. Method removes only those prices, that are returned
	 * by the method {@link #getPrices()}, so that if the entity prices are limited to specific currency, price list
	 * or validity, only those prices are removed and not all present on the entity actually.
	 *
	 * @return builder instance to allow command chaining
	 */
	default W removeAllPrices() {
		for (PriceContract price : getPrices()) {
			removePrice(price.priceId(), price.priceList(), price.currency());
		}
		//noinspection unchecked
		return (W) this;
	}

	/**
	 * Sets behaviour for prices that has {@link Price#innerRecordId()} set in terms of computing the "selling" price.
	 */
	W setPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling);

	/**
	 * Removes previously set behaviour for prices with {@link Price#innerRecordId()}. You should ensure that
	 * the entity has no prices with non-null {@link Price#innerRecordId()}.
	 */
	W removePriceInnerRecordHandling();

	/**
	 * This is helper method that allows to purge all methods, that were not overwritten
	 * (i.e. {@link #setPrice(int, String, Currency, Integer, BigDecimal, BigDecimal, BigDecimal, boolean)}
	 * by instance of this editor/builder class. It's handy if you know that whenever any price is updated in the entity
	 * you also update all other prices (i.e. all prices are rewritten). By using this method you don't need to care about
	 * purging the previous set of superfluous prices.
	 *
	 * This method is analogical to following process:
	 *
	 * - clear all prices
	 * - set them all from scratch
	 *
	 * Now you can simply:
	 *
	 * - set all prices
	 * - remove all non "touched" prices
	 *
	 * Even if you set the price exactly the same (i.e. in reality it doesn't change), it'll remain - because it was
	 * "touched". This mechanism is here because we want to avoid price removal and re-insert due to optimistic locking
	 * which is supported on by-price level.
	 */
	W removeAllNonTouchedPrices();

	/**
	 * Interface that simply combines writer and builder contracts together.
	 */
	interface PricesBuilder extends PricesEditor<PricesEditor.PricesBuilder>, BuilderContract<Prices> {

		@Nonnull
		@Override
		Stream<? extends LocalMutation<?, ?>> buildChangeSet();

	}

}
