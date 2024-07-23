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

package io.evitadb.index.price;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.store.entity.model.entity.price.PriceInternalIdContainer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Currency;
import java.util.stream.Stream;

/**
 * PriceIndexContract describes the API of {@link PriceIndexContract} that maintains data structures for fast
 * accessing entity prices. Interface describes both read and write access to the index.
 *
 * Purpose of this contract interface is to ease using {@link @lombok.experimental.Delegate} annotation
 * in {@link io.evitadb.index.EntityIndex} and minimize the amount of the code in this complex class by automatically
 * delegating all {@link PriceIndexContract} methods to the {@link PriceIndexContract} implementation that is part
 * of this index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface PriceIndexContract {

	/**
	 * Returns collection of all {@link PriceListAndCurrencyPriceSuperIndex indexes} maintained by this price index.
	 */
	@Nonnull
	Collection<? extends PriceListAndCurrencyPriceIndex> getPriceListAndCurrencyIndexes();

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
	 * Method registers new price to the index.
	 */
	@Nonnull
	PriceInternalIdContainer addPrice(
		int entityPrimaryKey,
		@Nullable Integer internalPriceId,
		@Nonnull PriceKey priceKey,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax
	);

	/**
	 * Method removes registered price from the index.
	 */
	void priceRemove(
		int entityPrimaryKey,
		int internalPriceId,
		@Nonnull PriceKey priceKey,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax
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
