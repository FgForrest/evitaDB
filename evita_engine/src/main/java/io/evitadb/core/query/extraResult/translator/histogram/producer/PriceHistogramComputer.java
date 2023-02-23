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

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordsLookupResult;
import io.evitadb.core.query.algebra.price.termination.PricePredicate;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.FlattenedHistogramComputer;
import io.evitadb.core.query.sort.price.FilteredPriceRecordsCollector;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.ArrayUtils;
import lombok.RequiredArgsConstructor;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import static java.util.Optional.ofNullable;

/**
 * DTO that aggregates all data necessary for computing histogram for prices.
 */
@RequiredArgsConstructor
public class PriceHistogramComputer implements CacheableEvitaResponseExtraResultComputer<HistogramContract> {
	/**
	 * Contains reference to the lambda that needs to be executed THE FIRST time the histogram produced by this computer
	 * instance is really computed (and memoized).
	 */
	private final Consumer<CacheableEvitaResponseExtraResultComputer<HistogramContract>> onComputationCallback;
	/**
	 * Bucket count contains desired count of histogram columns=buckets. Output histogram bucket count must never exceed
	 * this value, but might be optimized to lower count when there are big gaps between columns.
	 */
	private final int bucketCount;
	/**
	 * Contains {@link EntitySchema#getIndexedPricePlaces()} setting.
	 */
	private final int indexedPricePlaces;
	/**
	 * Contains query price mode of the current query.
	 */
	@Nonnull private final QueryPriceMode queryPriceMode;
	/**
	 * Contains filtering formula tree that was used to produce results so that computed sub-results can be used for
	 * sorting.
	 */
	@Nonnull private final Formula filteringFormula;
	/**
	 * Contains clone of the {@link #filteringFormula} in a such way that all price termination formulas within user
	 * filter that filtered out entity primary keys based on price predicate (price between query) produce just
	 * the excluded records - this way we can compute remainder to the current filtering result and get all data
	 * for price histogram ignoring the price between filtering query.
	 */
	@Nullable private final Formula filteringFormulaWithFilteredOutRecords;
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
	 * Contains memoized value of {@link #computeHash(LongHashFunction)} method.
	 */
	private Long memoizedHash;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} method.
	 */
	private long[] memoizedTransactionalIds;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} computed hash.
	 */
	private Long memoizedTransactionalIdHash;
	/**
	 * Contains price record array that all price records that represents source records for price histogram computation.
	 * It is initialized during {@link #compute()} method and result is memoized, so it's ensured it's computed only once.
	 */
	private PriceRecordContract[] memoizedPriceRecords;
	/**
	 * Contains result - computed histogram. The value is initialized during {@link #compute()} method, and it is
	 * memoized, so it's ensured it's computed only once.
	 */
	private HistogramContract memoizedResult;

	public PriceHistogramComputer(int bucketCount, int indexedPricePlaces, @Nonnull QueryPriceMode queryPriceMode, @Nonnull Formula filteringFormula, @Nullable Formula filteringFormulaWithFilteredOutRecords, @Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors, @Nullable FilteredPriceRecordsLookupResult priceRecordsLookupResult) {
		this.onComputationCallback = null;
		this.bucketCount = bucketCount;
		this.indexedPricePlaces = indexedPricePlaces;
		this.queryPriceMode = queryPriceMode;
		this.filteringFormula = filteringFormula;
		this.filteringFormulaWithFilteredOutRecords = filteringFormulaWithFilteredOutRecords;
		this.filteredPriceRecordAccessors = filteredPriceRecordAccessors;
		this.priceRecordsLookupResult = priceRecordsLookupResult;
	}

	@Override
	public long computeHash(@Nonnull LongHashFunction hashFunction) {
		if (this.memoizedHash == null) {
			this.memoizedHash = hashFunction.hashLongs(
				new long[] {
					bucketCount,
					queryPriceMode.ordinal(),
					filteringFormula.computeHash(hashFunction)
				}
			);
		}
		return this.memoizedHash;
	}

	@Override
	public long computeTransactionalIdHash(@Nonnull LongHashFunction hashFunction) {
		if (this.memoizedTransactionalIdHash == null) {
			this.memoizedTransactionalIdHash = hashFunction.hashLongs(
				Arrays.stream(gatherTransactionalIds())
					.distinct()
					.sorted()
					.toArray()
			);
		}
		return this.memoizedTransactionalIdHash;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		if (this.memoizedTransactionalIds == null) {
			this.memoizedTransactionalIds = filteringFormula.gatherTransactionalIds();
		}
		return this.memoizedTransactionalIds;
	}

	@Override
	public long getEstimatedCost() {
		return filteringFormula.compute().size() *
			(filteredPriceRecordAccessors.size() / 2) *
			getOperationCost();
	}

	@Override
	public long getCost() {
		return getPriceRecords().length * getOperationCost();
	}

	@Override
	public long getOperationCost() {
		return 11267;
	}

	@Override
	public long getCostToPerformanceRatio() {
		return getCost() / (getOperationCost() * bucketCount);
	}

	@Override
	public FlattenedHistogramComputer toSerializableResult(long extraResultHash, @Nonnull LongHashFunction hashFunction) {
		return new FlattenedHistogramComputer(
			extraResultHash,
			computeHash(hashFunction),
			Arrays.stream(gatherTransactionalIds())
				.distinct()
				.sorted()
				.toArray(),
			Objects.requireNonNull(compute())
		);
	}

	@Override
	public int getSerializableResultSizeEstimate() {
		return FlattenedHistogramComputer.estimateSize(
			gatherTransactionalIds(),
			compute()
		);
	}

	@Nonnull
	@Override
	public CacheableEvitaResponseExtraResultComputer<HistogramContract> getCloneWithComputationCallback(@Nonnull Consumer<CacheableEvitaResponseExtraResultComputer<HistogramContract>> selfOperator) {
		return new PriceHistogramComputer(
			selfOperator, bucketCount, indexedPricePlaces, queryPriceMode,
			filteringFormula, filteringFormulaWithFilteredOutRecords,
			filteredPriceRecordAccessors, priceRecordsLookupResult
		);
	}

	@Nonnull
	@Override
	public HistogramContract compute() {
		if (memoizedResult == null) {
			final PriceRecordContract[] priceRecords = getPriceRecords();
			if (!ArrayUtils.isEmpty(priceRecords)) {
				// initialize comparator and price extractor according to query price mode
				final Comparator<PriceRecordContract> priceComparator;
				final ToIntFunction<PriceRecordContract> priceRetriever;
				if (queryPriceMode == QueryPriceMode.WITH_TAX) {
					priceComparator = Comparator.comparingInt(PriceRecordContract::priceWithTax);
					priceRetriever = PriceRecordContract::priceWithTax;
				} else {
					priceComparator = Comparator.comparingInt(PriceRecordContract::priceWithoutTax);
					priceRetriever = PriceRecordContract::priceWithoutTax;
				}

				// sort prices by price in ascending order (histograms are always sorted from low to high value)
				Arrays.sort(priceRecords, priceComparator);

				// use histogram data cruncher to produce the histogram
				final HistogramDataCruncher<PriceRecordContract> optimalHistogram = HistogramDataCruncher.createOptimalHistogram(
					"price histogram", bucketCount, indexedPricePlaces, priceRecords,
					priceRetriever,
					value -> 1,
					value -> indexedPricePlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).scaleByPowerOfTen(-1 * indexedPricePlaces),
					value -> indexedPricePlaces == 0 ? value.intValueExact() : value.scaleByPowerOfTen(indexedPricePlaces).intValueExact()
				);

				// and finish
				this.memoizedResult = new Histogram(
					optimalHistogram.getHistogram(),
					optimalHistogram.getMaxValue()
				);
			} else {
				this.memoizedResult = HistogramContract.EMPTY;
			}

			ofNullable(onComputationCallback).ifPresent(it -> it.accept(this));
		}
		return this.memoizedResult;
	}

	/**
	 * Collects the price records to compute price histogram from. It finds out all price related formulas and extracts
	 * the price records that survived filtering. The logic also "disables" the {@link PricePredicate} used in formulas
	 * within {@link UserFilterFormula}. These must be ignored while computing price histogram.
	 */
	private PriceRecordContract[] getPriceRecords() {
		if (this.memoizedPriceRecords == null) {
			// create price records collector reusing existing data or computing them from scratch
			final FilteredPriceRecordsCollector priceRecordsCollector = this.priceRecordsLookupResult == null ?
				new FilteredPriceRecordsCollector(
					RoaringBitmapBackedBitmap.getRoaringBitmap(filteringFormula.compute()),
					filteredPriceRecordAccessors
				) :
				new FilteredPriceRecordsCollector(
					this.priceRecordsLookupResult,
					filteredPriceRecordAccessors
				);

			// collect all price records that match filtering formula computation (ignoring price between query)
			final PriceRecordContract[] priceRecords;
			if (filteringFormulaWithFilteredOutRecords == null) {
				// there were no entity pks filtered out due to price between query, we can simply reuse
				// the filtering query result
				priceRecords = priceRecordsCollector.getResult().getPriceRecords();
			} else {
				// now compute the remainder with altered filtering formula
				final Bitmap pricePredicateFilteredOutEntities = filteringFormulaWithFilteredOutRecords.compute();
				if (pricePredicateFilteredOutEntities.isEmpty()) {
					// we can simply reuse the filtering query result as is, nothing has been filtered out
					priceRecords = priceRecordsCollector.getResult().getPriceRecords();
				} else {
					// we have to combine filtering query result with computed remainder in order to get all price records
					// regardless of the price between query
					priceRecords = priceRecordsCollector.combineResultWithAndReturnPriceRecords(
						RoaringBitmapBackedBitmap.getRoaringBitmap(pricePredicateFilteredOutEntities)
					);
				}
			}
			this.memoizedPriceRecords = priceRecords;
		}
		return this.memoizedPriceRecords;
	}
}
