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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/**
 * Prices are specific to a very few entities, but because correct price computation is very complex in e-commerce systems
 * and highly affects performance of the entities filtering and sorting, they deserve first class support in entity model.
 * It is pretty common in B2B systems single product has assigned dozens of prices for the different customers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface PriceContract extends Versioned, Droppable, Serializable, Comparable<PriceContract>, ContentComparator<PriceContract> {

	/**
	 * Returns primary identification of the price.
	 * Price is uniquely identified by combination: priceId, priceList, currency.
	 */
	@Nonnull
	PriceKey priceKey();

	/**
	 * Contains the identification of the price in the external systems. This ID is expected to be used for
	 * synchronization of the price in relation to the primary source of the prices. The price with the same ID must
	 * be unique within the same entity. The prices with the same ID in multiple entities should represent the same
	 * price in terms of other values - such as validity, currency, price list, the price itself, and all other
	 * properties. These values can be different for a limited time (for example, the prices of Entity A and Entity B
	 * can be the same, but Entity A is updated in a different session/transaction and at a different time than
	 * Entity B).
	 */
	int priceId();

	/**
	 * Contains identification of the price list in the external system. Each price must reference a price list. Price list
	 * identification may refer to another Evita entity or may contain any external price list identification
	 * (for example id or unique name of the price list in the external system).
	 *
	 * Single entity is expected to have single price for the price list unless there is {@link #validity()} specified.
	 * In other words there is no sense to have multiple concurrently valid prices for the same entity that have roots
	 * in the same price list.
	 */
	@Nonnull
	String priceList();

	/**
	 * Identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217).
	 */
	@Nonnull
	Currency currency();

	/**
	 * Returns three letter form identifying the currency according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217).
	 */
	@Nonnull
	default String currencyCode() {
		return currency().getCurrencyCode();
	}

	/**
	 * Some special products (such as master products, or product sets) may contain prices of all "subordinate" products
	 * so that the aggregating product can represent them in certain views on the product. In that case there is need
	 * to distinguish the projected prices of the subordinate product in the one, that represents them.
	 */
	@Nullable
	Integer innerRecordId();

	/**
	 * Method allow to check if the price relates to the another price in terms of the inner record ID equality.
	 * Some price implementation might implement more complex logic to determine the relation.
	 *
	 * @param anotherPrice another price to check relation with
	 * @return true if the price relates to the another price
	 */
	boolean relatesTo(@Nonnull PriceContract anotherPrice);

	/**
	 * Price without tax.
	 */
	@Nonnull
	BigDecimal priceWithoutTax();

	/**
	 * Tax percentage (i.e. for 19% it'll be 19.00)
	 */
	@Nonnull
	BigDecimal taxRate();

	/**
	 * Price with tax.
	 */
	@Nonnull
	BigDecimal priceWithTax();

	/**
	 * Date and time interval for which the price is valid (inclusive).
	 */
	@Nullable
	DateTimeRange validity();

	/**
	 * Returns true if price is valid at the passed date and time.
	 */
	default boolean validAt(@Nonnull OffsetDateTime theMoment) {
		return Optional.ofNullable(validity()).map(it -> it.isValidFor(theMoment)).orElse(true);
	}

	/**
	 * Controls whether price is subject to filtering / sorting logic, non indexed prices will be fetched along with
	 * entity but won't be considered when evaluating search {@link io.evitadb.api.query.Query}. These prices may be
	 * used for "informational" prices such as reference price (the crossed out price often found on e-commerce sites
	 * as "usual price") but are not used as the "selling" price.
	 */
	boolean indexed();

	/**
	 * Renamed to {@link #indexed()}.
	 */
	@Deprecated(since = "2024.9", forRemoval = true)
	default boolean sellable() {
		return indexed();
	}

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	int estimateSize();

	/**
	 * Comparison uses price key for comparation.
	 */
	@Override
	default int compareTo(PriceContract o) {
		return priceKey().compareTo(o.priceKey());
	}

	/**
	 * Returns true if this price differs in any business related data from the other value.
	 */
	@Override
	default boolean differsFrom(@Nullable PriceContract otherPrice) {
		if (otherPrice == null) return true;
		if (!Objects.equals(innerRecordId(), otherPrice.innerRecordId())) return true;
		if (!Objects.equals(priceWithoutTax(), otherPrice.priceWithoutTax())) return true;
		if (!Objects.equals(priceWithTax(), otherPrice.priceWithTax())) return true;
		if (!Objects.equals(taxRate(), otherPrice.taxRate())) return true;
		if (!Objects.equals(validity(), otherPrice.validity())) return true;
		if (indexed() != otherPrice.indexed()) return true;
		return dropped() != otherPrice.dropped();
	}
}
