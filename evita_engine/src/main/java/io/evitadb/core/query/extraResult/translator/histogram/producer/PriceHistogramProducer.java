/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordsLookupResult;
import io.evitadb.core.query.algebra.price.termination.PriceTerminationFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.core.query.extraResult.CacheableExtraResultProducer;
import io.evitadb.core.query.extraResult.ExtraResultCacheAccessor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
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
public class PriceHistogramProducer implements CacheableExtraResultProducer {
	/**
	 * Bucket count contains desired count of histogram columns=buckets. Output histogram bucket count must never exceed
	 * this value, but might be optimized to lower count when there are big gaps between columns.
	 */
	private final int bucketCount;
	/**
	 * Reference to the query context that allows to access entity bodies.
	 */
	@Nonnull private final QueryContext queryContext;
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
	/**
	 * Provides access to the default extra result computer logic that allows to store or withdraw extra results
	 * from cache.
	 */
	@Nonnull private final ExtraResultCacheAccessor extraResultCacheAccessor;


	@Nullable
	@Override
	public <T extends Serializable> EvitaResponseExtraResult fabricate(@Nonnull List<T> entities) {
		// contains flag whether there was at least one formula with price predicate that filtered out some entity pks
		final AtomicBoolean filteredRecordsFound = new AtomicBoolean();
		final AtomicReference<Predicate<BigDecimal>> requestedPricePredicate = new AtomicReference<>();
		// create clone of the current formula in a such way that all price termination formulas within user filter
		// that filtered out entity primary keys based on price predicate (price between query) produce just
		// the excluded records - this way we can compute remainder to the current filtering result and get all data
		// for price histogram ignoring the price between filtering query
		final Formula formulaWithFilteredOutResults = FormulaCloner.clone(
			filteringFormula, (formulaCloner, theFormula) -> {
				if (theFormula instanceof UserFilterFormula) {
					// we need to reconstruct the user filter formula
					final Formula updatedUserFilterFormula = FormulaCloner.clone(
						theFormula,
						innerFormula -> {
							if (innerFormula instanceof PriceTerminationFormula priceTerminationFormula) {
								ofNullable(priceTerminationFormula.getRequestedPredicate())
									.ifPresent(requestedPricePredicate::set);
								final Bitmap filteredOutRecords = priceTerminationFormula.getRecordsFilteredOutByPredicate();
								Assert.isPremiseValid(
									filteredOutRecords != null,
									"Compute was not yet called on price termination formula, this is not expected!"
								);
								if (filteredOutRecords.isEmpty()) {
									return EmptyFormula.INSTANCE;
								} else {
									filteredRecordsFound.set(true);
									return priceTerminationFormula.getCloneWithPricePredicateFilteredOutResults();
								}
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

		final CacheableHistogramContract optimalHistogram = extraResultCacheAccessor.analyse(
			queryContext.getSchema().getName(),
			new PriceHistogramComputer(
				bucketCount,
				queryContext.getSchema().getIndexedPricePlaces(),
				queryContext.getQueryPriceMode(),
				filteringFormula,
				filteredRecordsFound.get() ? formulaWithFilteredOutResults : null,
				filteredPriceRecordAccessors, priceRecordsLookupResult
			)
		).compute();
		if (optimalHistogram == CacheableHistogramContract.EMPTY) {
			return null;
		} else {
			// create histogram DTO for the output
			return new PriceHistogram(
				optimalHistogram.convertToHistogram(
					ofNullable(requestedPricePredicate.get())
						.orElseGet(() -> threshold -> false)
				)
			);
		}
	}

	@Nonnull
	@Override
	public ExtraResultProducer cloneInstance(@Nonnull ExtraResultCacheAccessor cacheAccessor) {
		return new PriceHistogramProducer(
			bucketCount, queryContext, filteringFormula, filteredPriceRecordAccessors, priceRecordsLookupResult, cacheAccessor
		);
	}
}