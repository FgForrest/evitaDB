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

package io.evitadb.api.requestResponse.data;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Map;
import java.util.Optional;

/**
 * Combines a {@link PriceRangeForSale} with the map of accompanying prices that were computed alongside the
 * price for sale. The accompanying prices follow exactly the same selection rules as
 * {@link PricesContract.PriceForSaleWithAccompanyingPrices#accompanyingPrices()} returns, so the two methods
 * are interchangeable in terms of the accompanying-price contents — only the price-range information is
 * added on top.
 *
 * Bound-vs-accompanying scope: under `LOWEST_PRICE`, `accompanyingPrices` are scoped to the inner record of
 * the **selling** price (i.e. the cheapest variant), not to the inner record of `priceRange.highestPrice()`.
 * The same map is returned regardless of which bound the consumer subsequently surfaces (`priceForSaleMin`
 * vs `priceForSaleMax`). This is intentional — the engine resolves accompanying prices once per request and
 * the bound fields are presentation-layer views over the same resolved context. Consumers wanting the
 * accompanying prices of a specific variant should query that variant directly rather than via the bound
 * field.
 *
 * @param priceRange         price range for sale (lowest/highest/priceForSale)
 * @param accompanyingPrices map of accompanying prices keyed by their requested
 *                           {@link PricesContract.AccompanyingPrice#priceName()}; values are wrapped in
 *                           {@link Optional} so that absent accompanying prices may be represented
 *                           explicitly. See the "Bound-vs-accompanying scope" note above for how the map
 *                           relates to the range bounds under `LOWEST_PRICE`
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record PriceRangeForSaleWithAccompanyingPrices(
	@Nonnull PriceRangeForSale priceRange,
	@Nonnull Map<String, Optional<PriceContract>> accompanyingPrices
) implements Serializable {
}
