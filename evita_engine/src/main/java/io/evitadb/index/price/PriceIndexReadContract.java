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

package io.evitadb.index.price;


import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.index.price.model.PriceIndexKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Currency;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * This interface defines read methods for the price index. It allows reading prices from the index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface PriceIndexReadContract {

	/**
	 * Returns collection of all {@link PriceListAndCurrencyPriceSuperIndex indexes} maintained by this price index.
	 *
	 * **The returned collection is a map view the backing map caches and then keeps**, so a caller that walks the
	 * indexes exactly once - a flush, a manifest capture - should use {@link #forEachPriceListAndCurrencyIndex}
	 * instead and leave nothing behind. This accessor is right for the query path, which asks the same index over
	 * and over and is what that caching is for.
	 */
	@Nonnull
	Collection<? extends PriceListAndCurrencyPriceIndex> getPriceListAndCurrencyIndexes();

	/**
	 * Hands every {@link PriceListAndCurrencyPriceIndex} maintained by this price index to `consumer`, without
	 * materialising a collection to walk - the once-through counterpart of
	 * {@link #getPriceListAndCurrencyIndexes()}. Iterates nothing at all for a price index that holds none.
	 *
	 * @param consumer invoked once per maintained price-list-and-currency index
	 */
	void forEachPriceListAndCurrencyIndex(@Nonnull Consumer<? super PriceListAndCurrencyPriceIndex> consumer);

	/**
	 * Returns stream of all {@link PriceListAndCurrencyPriceSuperIndex indexes} that relates to passed currency.
	 */
	@Nonnull
	Stream<? extends PriceListAndCurrencyPriceIndex> getPriceIndexesStream(
		@Nonnull Currency currency,
		@Nonnull PriceInnerRecordHandling innerRecordHandling
	);

	/**
	 * Returns stream of all {@link PriceListAndCurrencyPriceSuperIndex indexes} that relates to passed price list.
	 */
	@Nonnull
	Stream<? extends PriceListAndCurrencyPriceIndex> getPriceIndexesStream(
		@Nonnull String priceListName,
		@Nonnull PriceInnerRecordHandling innerRecordHandling
	);

	/**
	 * Returns price index by its price list name and currency.
	 */
	@Nullable
	PriceListAndCurrencyPriceIndex getPriceIndex(
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nonnull PriceInnerRecordHandling innerRecordHandling
	);

	/**
	 * Returns price index by its price list name and currency.
	 */
	@Nullable
	PriceListAndCurrencyPriceIndex getPriceIndex(@Nonnull PriceIndexKey priceListAndCurrencyKey);

	/**
	 * Returns true if there are no price indexes available.
	 */
	boolean isPriceIndexEmpty();

}
