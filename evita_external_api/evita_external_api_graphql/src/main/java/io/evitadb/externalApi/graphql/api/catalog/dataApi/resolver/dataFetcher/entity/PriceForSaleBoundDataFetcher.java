/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity;

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceRangeForSale;
import io.evitadb.api.requestResponse.data.PriceRangeForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.PrefetchedPriceForSale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches a single bound (lowest or highest) of the price-for-sale range for an entity. Two singleton instances
 * back the flattened sibling fields `priceForSaleMin` and `priceForSaleMax` exposed on the GraphQL entity type.
 *
 * The bound is wrapped in a {@link PrefetchedPriceForSale} so that the existing
 * {@link AccompanyingPriceDataFetcher} keeps serving `accompanyingPrice` selections unchanged. Argument
 * resolution (currency, validIn, priceLists, locale, accompanying-price requests) is inherited from
 * {@link AbstractPriceForSaleDataFetcher}.
 *
 * The underlying engine call computes the full {@link PriceRangeForSale}; the per-entity request-scoped cache on
 * {@link io.evitadb.api.requestResponse.data.PriceForSaleContextWithCachedResult} ensures that fetching both
 * `priceForSaleMin` and `priceForSaleMax` on the same entity runs the price-for-sale computation once.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PriceForSaleBoundDataFetcher extends AbstractPriceForSaleDataFetcher<PriceContract> {

	private static final PriceForSaleBoundDataFetcher MIN_INSTANCE = new PriceForSaleBoundDataFetcher(true);
	private static final PriceForSaleBoundDataFetcher MAX_INSTANCE = new PriceForSaleBoundDataFetcher(false);

	/**
	 * `true` to expose the lowest bound, `false` for the highest.
	 */
	private final boolean lowest;

	private PriceForSaleBoundDataFetcher(boolean lowest) {
		this.lowest = lowest;
	}

	@Nonnull
	public static PriceForSaleBoundDataFetcher getMinInstance() {
		return MIN_INSTANCE;
	}

	@Nonnull
	public static PriceForSaleBoundDataFetcher getMaxInstance() {
		return MAX_INSTANCE;
	}

	@Nullable
	@Override
	protected PriceContract computeDefaultPrices(@Nonnull EntityDecorator entity) {
		final Optional<PriceRangeForSale> range = entity.getPriceRangeForSaleIfAvailable();
		if (range.isEmpty()) {
			return null;
		}
		// reuse the engine-pre-resolved accompanying prices that were requested via `accompanyingPriceContent`
		// in the require clause — they are cached on the price-for-sale context and shared across both bounds
		final Map<String, Optional<PriceContract>> accompanyingPrices = entity
			.getPriceForSaleWithAccompanyingPricesIfAvailable()
			.map(PriceForSaleWithAccompanyingPrices::accompanyingPrices)
			.orElse(Map.of());
		return new PrefetchedPriceForSale(pickBound(range.get()), entity, accompanyingPrices);
	}

	@Nullable
	@Override
	protected PriceContract computePrices(@Nonnull EntityDecorator entity,
	                                      @Nonnull String[] desiredPriceLists,
	                                      @Nonnull Currency desiredCurrency,
	                                      @Nullable OffsetDateTime desiredValidIn,
	                                      @Nonnull AccompanyingPrice[] desiredAccompanyingPrices) {
		final Optional<PriceRangeForSaleWithAccompanyingPrices> range = entity.getPriceRangeForSaleWithAccompanyingPrices(
			desiredCurrency,
			desiredValidIn,
			desiredPriceLists,
			desiredAccompanyingPrices
		);
		return range
			.map(it -> new PrefetchedPriceForSale(pickBound(it.priceRange()), entity, it.accompanyingPrices()))
			.orElse(null);
	}

	@Nonnull
	private PriceContract pickBound(@Nonnull PriceRangeForSale range) {
		return this.lowest ? range.lowestPrice() : range.highestPrice();
	}
}
