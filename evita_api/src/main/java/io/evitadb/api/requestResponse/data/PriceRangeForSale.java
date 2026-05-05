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

/**
 * Holds the full price range for an entity together with the resolved selling price. The range is computed
 * using the same currency / valid-in / price-list filters as the selling price and is therefore directly
 * comparable.
 *
 * Strategy semantics:
 *
 * - `NONE`: `lowestPrice == highestPrice == priceForSale` — there is exactly one selling price candidate.
 * - `LOWEST_PRICE`: `lowestPrice == priceForSale` (the cheapest per-inner-record selling price),
 *   `highestPrice` is the most expensive per-inner-record selling price computed with identical filter rules.
 * - `SUM`: `lowestPrice` is the cheapest per-inner-record component, `highestPrice` is the most expensive
 *   per-inner-record component, `priceForSale` is the
 *   {@link io.evitadb.api.requestResponse.data.structure.CumulatedPrice} over all components.
 *
 * The bounds are always concrete, indexed inner-record prices that satisfy the same filter as the selling
 * price.
 *
 * Predicate-filter asymmetry under `LOWEST_PRICE`: the `filterPredicate` passed to
 * {@link PricesContract#computePriceRangeForSale} is a selling-price scoped filter inherited from the legacy
 * {@code getPriceForSale*} API. It applies to `lowestPrice` (which equals the selling price by construction)
 * but **not** to `highestPrice` — the upper bound describes the unfiltered indexed catalogue spread for the
 * resolved currency / valid-in / price-list filters, which is the value e-commerce front-ends typically need
 * for "price from / to" displays. Symmetric clipping would amount to "the highest price that still passes the
 * selling-price filter", which is rarely useful and would break the invariant that the bounds describe the
 * real catalogue spread.
 *
 * @param lowestPrice  the lowest selling price (or component price for `SUM`); under `LOWEST_PRICE` this is
 *                     the cheapest indexed candidate that **passes** the selling-price `filterPredicate`
 * @param highestPrice the highest selling price (or component price for `SUM`); under `LOWEST_PRICE` this is
 *                     the most expensive indexed candidate **regardless** of the `filterPredicate` — see the
 *                     "Predicate-filter asymmetry" note above
 * @param priceForSale the resolved selling price (per-strategy semantics — see above)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record PriceRangeForSale(
	@Nonnull PriceContract lowestPrice,
	@Nonnull PriceContract highestPrice,
	@Nonnull PriceContract priceForSale
) implements Serializable {
}
