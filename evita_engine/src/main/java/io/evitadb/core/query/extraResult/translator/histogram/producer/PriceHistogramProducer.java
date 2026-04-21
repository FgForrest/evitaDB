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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.price.FilteredOutPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordsLookupResult;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.common.RangeCarrierGroup;
import io.evitadb.core.query.extraResult.translator.common.UserFilterRelaxer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.utils.Functions;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.function.Predicate;

import static java.util.Optional.ofNullable;

/**
 * This class contains logic that creates single {@link PriceHistogram} DTO requested
 * by {@link io.evitadb.api.query.require.PriceHistogram} require query in input query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class PriceHistogramProducer implements ExtraResultProducer {
	/**
	 * Bucket count contains desired count of histogram columns=buckets. Output histogram bucket count must never exceed
	 * this value, but might be optimized to lower count when there are big gaps between columns.
	 */
	private final int bucketCount;
	/**
	 * Contains behavior that was requested by the user in the query.
	 * @see HistogramBehavior
	 */
	@Nonnull private final HistogramBehavior behavior;
	/**
	 * Reference to the query context that allows to access entity bodies.
	 */
	@Nonnull private final QueryPlanningContext queryContext;
	/**
	 * Contains filtering formula tree that was used to produce results so that computed sub-results can be used for
	 * sorting.
	 */
	@Nonnull private final Formula filteringFormula;
	/**
	 * Contains list of all {@link FilteredPriceRecordAccessor} formulas that allow access to the {@link PriceRecord}
	 * used in filtering formula processing.
	 */
	@Nonnull private final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors;
	/**
	 * Contains existing {@link FilteredPriceRecordsLookupResult} if it was already produced by filtering or sorter logic.
	 * We can reuse already computed data in this producer and save precious ticks.
	 */
	@Nullable private final FilteredPriceRecordsLookupResult priceRecordsLookupResult;

	@Nullable
	@Override
	public <T extends Serializable> EvitaResponseExtraResult fabricate(@Nonnull QueryExecutionContext context) {
		// harvested now because UserFilterRelaxer is about to peel the price-between carrier that owns this predicate;
		// it drives the per-bucket `requested` flag on the output DTO
		final Predicate<BigDecimal> requestedPricePredicate = extractRequestedPricePredicate();

		// peel price-between carriers so the baseline does not contract under the user's own price handles; attribute
		// and facet carriers stay, so the histogram still narrows by slider and facet picks. The relaxed formula is the
		// "what prices would be reachable if the user cleared the price slider" set — the span the histogram needs.
		final Formula relaxedBaseline = UserFilterRelaxer.relax(
			this.filteringFormula, RangeCarrierGroup.PRICE_HISTOGRAM
		);
		final PriceHistogramComputer computer = getPriceHistogramComputer(relaxedBaseline);
		computer.initialize(context);
		final CacheableHistogramContract optimalHistogram = context.analyse(computer).compute();
		if (optimalHistogram == CacheableHistogramContract.EMPTY) {
			return null;
		} else {
			return new PriceHistogram(
				optimalHistogram.convertToHistogram(
					ofNullable(requestedPricePredicate).orElseGet(Functions::alwaysTrue)
				)
			);
		}
	}

	/**
	 * Wires the {@link PriceHistogramComputer} with the original {@link #filteringFormula} plus an
	 * optional supplementation formula that widens the histogram range beyond the user's price
	 * slider. The supplementation is set to `null` when `relaxedBaseline` is the same reference
	 * (no price-between carriers to peel) or {@link EmptyFormula#INSTANCE} (whole tree collapsed);
	 * otherwise the relaxed tree is passed through.
	 */
	@Nonnull
	private PriceHistogramComputer getPriceHistogramComputer(@Nonnull Formula relaxedBaseline) {
		final Formula filteringFormulaWithFilteredOutRecords;
		if (relaxedBaseline == this.filteringFormula || relaxedBaseline == EmptyFormula.INSTANCE) {
			filteringFormulaWithFilteredOutRecords = null;
		} else {
			filteringFormulaWithFilteredOutRecords = relaxedBaseline;
		}

		return new PriceHistogramComputer(
			this.bucketCount,
			this.behavior,
			this.queryContext.getSchema().getIndexedPricePlaces(),
			this.queryContext.getQueryPriceMode(),
			this.filteringFormula,
			filteringFormulaWithFilteredOutRecords,
			this.filteredPriceRecordAccessors, this.priceRecordsLookupResult
		);
	}

	/**
	 * Walks every {@link UserFilterFormula} in {@link #filteringFormula} looking for
	 * {@link FilteredOutPriceRecordAccessor} instances and returns the first non-null requested predicate it
	 * finds. Used to drive the per-bucket `requested` flag on the output histogram independent of the
	 * PRICE_HISTOGRAM relaxation that peels the underlying price-between carrier from the baseline formula.
	 */
	@Nullable
	private Predicate<BigDecimal> extractRequestedPricePredicate() {
		for (final UserFilterFormula userFilter : FormulaFinder.find(this.filteringFormula, UserFilterFormula.class, LookUp.DEEP)) {
			for (final FilteredOutPriceRecordAccessor accessor
					: FormulaFinder.find(userFilter, FilteredOutPriceRecordAccessor.class, LookUp.DEEP)) {
				final Predicate<BigDecimal> predicate = accessor.getRequestedPredicate();
				if (predicate != null) {
					return predicate;
				}
			}
		}
		return null;
	}

	@Nonnull
	@Override
	public String getDescription() {
		return "price histogram";
	}

}
