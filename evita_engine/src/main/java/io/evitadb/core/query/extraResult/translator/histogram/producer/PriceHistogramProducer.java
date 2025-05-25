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
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
		// contains flag whether there was at least one formula with price predicate that filtered out some entity pks
		final AtomicBoolean filteredRecordsFound = new AtomicBoolean();
		final AtomicReference<Predicate<BigDecimal>> requestedPricePredicate = new AtomicReference<>();
		final Formula formulaWithFilteredOutResults = getFormulaWithFilteredOutResults(requestedPricePredicate, filteredRecordsFound);

		final PriceHistogramComputer computer = new PriceHistogramComputer(
			this.bucketCount,
			this.behavior,
			this.queryContext.getSchema().getIndexedPricePlaces(),
			this.queryContext.getQueryPriceMode(),
			this.filteringFormula,
			filteredRecordsFound.get() ? formulaWithFilteredOutResults : null,
			this.filteredPriceRecordAccessors, this.priceRecordsLookupResult
		);
		computer.initialize(context);
		final CacheableHistogramContract optimalHistogram = context.analyse(computer).compute();
		if (optimalHistogram == CacheableHistogramContract.EMPTY) {
			return null;
		} else {
			// create histogram DTO for the output
			return new PriceHistogram(
				optimalHistogram.convertToHistogram(
					ofNullable(requestedPricePredicate.get())
						.orElseGet(() -> threshold -> true)
				)
			);
		}
	}

	/**
	 * Create clone of the current formula in a such way that all price termination formulas within user filter
	 * that filtered out entity primary keys based on price predicate (price between query) produce just
	 * the excluded records - this way we can compute remainder to the current filtering result and get all data
	 * for price histogram ignoring the price between filtering query.
	 *
	 * @param requestedPricePredicate The atomic reference to the predicate that determines the requested price.
	 * @param filteredRecordsFound    The atomic boolean that indicates whether filtered records were found.
	 * @return The formula with filtered out results.
	 */
	@Nonnull
	private Formula getFormulaWithFilteredOutResults(@Nonnull AtomicReference<Predicate<BigDecimal>> requestedPricePredicate, @Nonnull AtomicBoolean filteredRecordsFound) {
		return FormulaCloner.clone(
			this.filteringFormula, (formulaCloner, theFormula) -> {
				if (theFormula instanceof UserFilterFormula) {
					// we need to reconstruct the user filter formula
					final Formula updatedUserFilterFormula = FormulaCloner.clone(
						theFormula,
						innerFormula -> {
							if (innerFormula instanceof FilteredOutPriceRecordAccessor filteredOutPriceRecordAccessor) {
								ofNullable(filteredOutPriceRecordAccessor.getRequestedPredicate())
									.ifPresent(requestedPricePredicate::set);
								final Formula theResult = filteredOutPriceRecordAccessor.getCloneWithPricePredicateFilteredOutResults();
								filteredRecordsFound.set(theResult != EmptyFormula.INSTANCE);
								return theResult;
							} else {
								return innerFormula;
							}
						}
					);
					if (updatedUserFilterFormula.getInnerFormulas().length == 0) {
						// if there is no formula left in tue user filter container, leave it out entirely
						return null;
					} else {
						return updatedUserFilterFormula;
					}
				} else {
					return theFormula;
				}
			}
		);
	}

	@Nonnull
	@Override
	public String getDescription() {
		return "price histogram";
	}

}
