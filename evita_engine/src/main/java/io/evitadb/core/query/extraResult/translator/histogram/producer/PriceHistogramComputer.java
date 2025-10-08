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
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordsLookupResult;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogram;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.core.query.extraResult.translator.histogram.cache.FlattenedHistogramComputer;
import io.evitadb.core.query.sort.price.FilteredPriceRecordsCollector;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
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
public class PriceHistogramComputer implements CacheableEvitaResponseExtraResultComputer<CacheableHistogramContract> {
	/**
	 * Execution context from initialization phase.
	 */
	protected QueryExecutionContext context;
	/**
	 * Contains reference to the lambda that needs to be executed THE FIRST time the histogram produced by this computer
	 * instance is really computed (and memoized).
	 */
	private final Consumer<CacheableEvitaResponseExtraResultComputer<CacheableHistogramContract>> onComputationCallback;
	/**
	 * Bucket count contains desired count of histogram columns=buckets. Output histogram bucket count must never exceed
	 * this value, but might be optimized to lower count when there are big gaps between columns.
	 */
	private final int bucketCount;
	/**
	 * Contains behavior that was requested by the user in the query.
	 *
	 * @see HistogramBehavior
	 */
	@Nonnull private final HistogramBehavior behavior;
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
	 * Contains memoized value of {@link #getHash()} method.
	 */
	private final Long hash;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} method.
	 */
	private final long[] transactionalIds;
	/**
	 * Contains memoized value of {@link #getEstimatedCost()} ()}  of this formula.
	 */
	private final Long estimatedCost;
	/**
	 * Contains memoized value of {@link #getCost()}  of this formula.
	 */
	private Long cost;
	/**
	 * Contains memoized value of {@link #getCostToPerformanceRatio()} of this formula.
	 */
	private Long costToPerformance;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} computed hash.
	 */
	private final Long transactionalIdHash;
	/**
	 * Contains price record array that all price records that represents source records for price histogram computation.
	 * It is initialized during {@link #compute()} method and result is memoized, so it's ensured it's computed only once.
	 */
	private PriceRecordContract[] memoizedPriceRecords;
	/**
	 * Contains result - computed histogram. The value is initialized during {@link #compute()} method, and it is
	 * memoized, so it's ensured it's computed only once.
	 */
	private CacheableHistogramContract memoizedResult;

	public PriceHistogramComputer(
		int bucketCount,
		@Nonnull HistogramBehavior behavior,
		int indexedPricePlaces,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull Formula filteringFormula,
		@Nullable Formula filteringFormulaWithFilteredOutRecords,
		@Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors,
		@Nullable FilteredPriceRecordsLookupResult priceRecordsLookupResult
	) {
		this(
			null, bucketCount, behavior, indexedPricePlaces, queryPriceMode,
			filteringFormula, filteringFormulaWithFilteredOutRecords, filteredPriceRecordAccessors,
			priceRecordsLookupResult
		);
	}

	private PriceHistogramComputer(
		@Nullable Consumer<CacheableEvitaResponseExtraResultComputer<CacheableHistogramContract>> selfOperator,
		int bucketCount,
		@Nonnull HistogramBehavior behavior,
		int indexedPricePlaces,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull Formula filteringFormula,
		@Nullable Formula filteringFormulaWithFilteredOutRecords,
		@Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors,
		@Nullable FilteredPriceRecordsLookupResult priceRecordsLookupResult
	) {
		this.onComputationCallback = null;
		this.bucketCount = bucketCount;
		this.behavior = behavior;
		this.indexedPricePlaces = indexedPricePlaces;
		this.queryPriceMode = queryPriceMode;
		this.filteringFormula = filteringFormula;
		this.filteringFormulaWithFilteredOutRecords = filteringFormulaWithFilteredOutRecords;
		this.filteredPriceRecordAccessors = filteredPriceRecordAccessors;
		this.priceRecordsLookupResult = priceRecordsLookupResult;

		this.hash = HASH_FUNCTION.hashLongs(
			new long[]{
				bucketCount, behavior.ordinal(),
				queryPriceMode.ordinal(),
				filteringFormula.getHash()
			}
		);
		this.transactionalIds = filteringFormula.gatherTransactionalIds();
		this.transactionalIdHash = HASH_FUNCTION.hashLongs(
			Arrays.stream(this.transactionalIds)
				.distinct()
				.sorted()
				.toArray()
		);
		this.estimatedCost = filteringFormula.getEstimatedCardinality() *
			(filteredPriceRecordAccessors.size() / 2) *
			getOperationCost();
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		this.context = executionContext;
		this.filteringFormula.initialize(executionContext);
	}

	@Override
	public long getHash() {
		Assert.isPremiseValid(this.hash != null, "The computer must be initialized prior to calling getHash().");
		return this.hash;
	}

	@Override
	public long getTransactionalIdHash() {
		Assert.isPremiseValid(this.transactionalIdHash != null, "The computer must be initialized prior to calling getTransactionalIdHash().");
		return this.transactionalIdHash;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		Assert.isPremiseValid(this.transactionalIds != null, "The computer must be initialized prior to calling gatherTransactionalIds().");
		return this.transactionalIds;
	}

	@Override
	public long getEstimatedCost() {
		Assert.isPremiseValid(this.estimatedCost != null, "The computer must be initialized prior to calling getEstimatedCost().");
		return this.estimatedCost;
	}

	@Override
	public long getCost() {
		if (this.cost == null) {
			if (this.memoizedResult == null) {
				return Long.MAX_VALUE;
			} else {
				this.cost = getPriceRecords().length * getOperationCost();
			}
		}
		return this.cost;
	}

	@Override
	public long getOperationCost() {
		// if the behavior is optimized we add 33% penalty because some histograms would need to be computed twice
		return this.behavior == HistogramBehavior.STANDARD ? 7511 : 11267;
	}

	@Override
	public long getCostToPerformanceRatio() {
		if (this.costToPerformance == null) {
			if (this.memoizedResult == null) {
				return Long.MAX_VALUE;
			} else {
				this.costToPerformance = getCost() / (getOperationCost() * this.bucketCount);
			}
		}
		return this.costToPerformance;
	}

	@Override
	public FlattenedHistogramComputer toSerializableResult(long extraResultHash, @Nonnull LongHashFunction hashFunction) {
		return new FlattenedHistogramComputer(
			extraResultHash,
			getHash(),
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
	public CacheableEvitaResponseExtraResultComputer<CacheableHistogramContract> getCloneWithComputationCallback(
		@Nonnull Consumer<CacheableEvitaResponseExtraResultComputer<CacheableHistogramContract>> selfOperator
	) {
		return new PriceHistogramComputer(
			selfOperator, this.bucketCount, this.behavior, this.indexedPricePlaces, this.queryPriceMode,
			this.filteringFormula, this.filteringFormulaWithFilteredOutRecords,
			this.filteredPriceRecordAccessors, this.priceRecordsLookupResult
		);
	}

	@Nonnull
	@Override
	public CacheableHistogramContract compute() {
		if (this.memoizedResult == null) {
			final PriceRecordContract[] priceRecords = getPriceRecords();
			if (!ArrayUtils.isEmpty(priceRecords)) {
				// initialize comparator and price extractor according to query price mode
				final Comparator<PriceRecordContract> priceComparator;
				final ToIntFunction<PriceRecordContract> priceRetriever;
				if (this.queryPriceMode == QueryPriceMode.WITH_TAX) {
					priceComparator = Comparator.comparingInt(PriceRecordContract::priceWithTax);
					priceRetriever = PriceRecordContract::priceWithTax;
				} else {
					priceComparator = Comparator.comparingInt(PriceRecordContract::priceWithoutTax);
					priceRetriever = PriceRecordContract::priceWithoutTax;
				}

				// sort prices by price in ascending order (histograms are always sorted from low to high value)
				Arrays.sort(priceRecords, priceComparator);

				// use histogram data cruncher to produce the histogram
				final HistogramDataCruncher<PriceRecordContract> resultHistogram;
				if (this.behavior == HistogramBehavior.OPTIMIZED) {
					resultHistogram = HistogramDataCruncher.createOptimalHistogram(
						"price histogram", this.bucketCount, this.indexedPricePlaces, priceRecords,
						priceRetriever,
						value -> 1,
						value -> this.indexedPricePlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).scaleByPowerOfTen(-1 * this.indexedPricePlaces),
						value -> this.indexedPricePlaces == 0 ? value.intValueExact() : value.scaleByPowerOfTen(this.indexedPricePlaces).intValueExact()
					);
				} else {
					resultHistogram = new HistogramDataCruncher<>(
						"price histogram", this.bucketCount, this.indexedPricePlaces, priceRecords,
						priceRetriever,
						value -> 1,
						value -> this.indexedPricePlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).scaleByPowerOfTen(-1 * this.indexedPricePlaces),
						value -> this.indexedPricePlaces == 0 ? value.intValueExact() : value.scaleByPowerOfTen(this.indexedPricePlaces).intValueExact()
					);
				}

				// and finish
				this.memoizedResult = new CacheableHistogram(
					resultHistogram.getHistogram(),
					resultHistogram.getMaxValue()
				);
			} else {
				this.memoizedResult = CacheableHistogramContract.EMPTY;
			}

			ofNullable(this.onComputationCallback).ifPresent(it -> it.accept(this));
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
					RoaringBitmapBackedBitmap.getRoaringBitmap(this.filteringFormula.compute()),
					this.filteredPriceRecordAccessors,
					this.context
				) :
				new FilteredPriceRecordsCollector(
					this.priceRecordsLookupResult,
					this.filteredPriceRecordAccessors,
					this.context
				);

			// collect all price records that match filtering formula computation (ignoring price between query)
			final PriceRecordContract[] priceRecords;
			if (this.filteringFormulaWithFilteredOutRecords == null) {
				// there were no entity pks filtered out due to price between query, we can simply reuse
				// the filtering query result
				priceRecords = priceRecordsCollector.getResult().getPriceRecords();
			} else {
				// now compute the remainder with altered filtering formula
				final Bitmap pricePredicateFilteredOutEntities = this.filteringFormulaWithFilteredOutRecords.compute();
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
