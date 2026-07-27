/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.algebra.price;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.priceRecord.PriceRecord;

import javax.annotation.Nonnull;

/**
 * Interface marks formulas that work with prices and provide access to {@link PriceRecord} that are connected with
 * those prices (price ids). This is crucial to funnel down rather big sets kept in {@link PriceListAndCurrencyPriceIndex indexes}
 * so that additional logic that needs to work with the prices (mainly sorting) could perform quickly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface FilteredPriceRecordAccessor {

	/**
	 * Returns array of {@link PriceRecord price records} that are connected with price ids that are produced by
	 * {@link Formula#compute()} method of the formula or different computational method.
	 * This is crucial to funnel down rather big sets kept in {@link PriceListAndCurrencyPriceIndex indexes} so that
	 * additional logic that needs to work with the prices (mainly sorting) could perform quickly.
	 *
	 * @param context runtime query execution context that may carry lazy resolution state
	 * @return the per-entity winning price records aligned with this accessor's computed bitmap
	 */
	@Nonnull
	FilteredPriceRecords getFilteredPriceRecords(@Nonnull QueryExecutionContext context);

	/**
	 * Returns price records intended for `priceHistogram` consumption. The default implementation delegates
	 * to {@link #getFilteredPriceRecords(QueryExecutionContext)} — this is the correct behaviour for every
	 * handling mode where the histogram data point is the entity-level price for sale
	 * ({@link PriceInnerRecordHandling#NONE} and {@link PriceInnerRecordHandling#SUM}).
	 *
	 * The accessor only needs to override this method when its handling mode produces additional data
	 * points that the histogram should reflect. Currently this is {@link PriceInnerRecordHandling#LOWEST_PRICE}:
	 * `io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula` discards the
	 * per-inner-record winning prices once the lowest one wins, while the histogram is supposed to answer
	 * *"what prices are reachable in the candidate pool?"* — which for LOWEST_PRICE means one bucket data
	 * point per inner record id, not one per entity. The override is gated on a planner-driven flag so
	 * non-histogram queries pay zero overhead.
	 *
	 * Implementations must apply the same `individualPricePredicate` filtering as the per-entity records
	 * (e.g. discount eligibility filtering) but must NOT apply the `sellingPricePredicate` (price-between
	 * slider) — the histogram is supposed to expose the full pool independent of the user's price handle.
	 *
	 * **Contract**: any implementation that overrides this method MUST return a
	 * `ResolvedFilteredPriceRecords`. Downstream consumers (notably
	 * {@link io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords#mergePerInnerRecordHistogramRecords})
	 * assume the per-inner-record records are eagerly available so the histogram baseline can be assembled
	 * without re-running a price lookup against an index. Returning a lazy or non-resolved variant raises
	 * a {@link io.evitadb.exception.GenericEvitaInternalError} at merge time.
	 *
	 * @param context runtime query execution context that may carry lazy resolution state
	 * @return the price records suitable for histogram bucketing (per-entity by default, possibly per-inner-record)
	 */
	@Nonnull
	default FilteredPriceRecords getFilteredPriceRecordsForHistogram(@Nonnull QueryExecutionContext context) {
		return getFilteredPriceRecords(context);
	}

	/**
	 * Capability probe used by `PriceHistogramComputer` to decide whether it may bypass the per-entity
	 * `FilteredPriceRecordsCollector` and read the per-inner-record records directly from each accessor.
	 *
	 * Returns `true` only when {@link #getFilteredPriceRecordsForHistogram(QueryExecutionContext)} is
	 * guaranteed to expose the per-inner-record histogram side-output for this accessor's scope:
	 *
	 * - For most accessors the default is `false` — they would just delegate to
	 *   {@link #getFilteredPriceRecords(QueryExecutionContext)} which is per-entity, so the histogram
	 *   must keep going through the collector to preserve the per-entity-with-relaxed-baseline shape.
	 * - `LowestPriceTerminationFormula` returns `true` exactly when its `collectPerInnerRecordPrices`
	 *   flag was set at construction (by the filter planner when histogram is requested AND the LP is
	 *   built outside `userFilter` scope).
	 * - `FlattenedFormulaWithFilteredPricesForHistogram` (the cached form of the above) always returns
	 *   `true`.
	 * - Wrapper accessors (e.g. `SelectionFormula`) propagate the capability from their inner
	 *   accessors so the histogram bypass continues to fire even when prefetch wiring inserts a
	 *   wrapper between the histogram and the histogram-aware termination formula.
	 *
	 * Implementations that override this method must keep its evaluation allocation-free — the probe
	 * is called once per accessor during histogram planning, but cumulatively across multi-tenant
	 * traffic it must not introduce churn.
	 *
	 * @return `true` when this accessor publishes a per-inner-record histogram side-output via
	 *         {@link #getFilteredPriceRecordsForHistogram(QueryExecutionContext)}; `false` otherwise
	 */
	default boolean exposesPerInnerRecordHistogramRecords() {
		return false;
	}

}
