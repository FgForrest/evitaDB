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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.cache.payload.FlattenedFormulaWithFilteredPricesForHistogram;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordsLookupResult;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogram;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.core.query.extraResult.translator.histogram.cache.FlattenedHistogramComputer;
import io.evitadb.core.query.sort.price.FilteredPriceRecordsCollector;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import static java.util.Optional.ofNullable;

/**
 * Computes the price histogram extra result from the filtering formula tree. The computer is instantiated by
 * {@link io.evitadb.core.query.extraResult.translator.histogram.PriceHistogramTranslator} and its
 * {@link #compute()} method is called lazily by the extra-result fabrication phase.
 *
 * The computation strategy depends on what {@link io.evitadb.api.requestResponse.data.PriceInnerRecordHandling}
 * the matched entities use:
 *
 * - **`NONE` / `SUM`**: the per-entity price records collected by the main formula path are fed directly to the
 *   histogram cruncher via the existing {@link FilteredPriceRecordsCollector} path.
 * - **`LOWEST_PRICE`**: the filter planner constructs every outer {@link LowestPriceTerminationFormula} in
 *   the filtering tree with its `collectPerInnerRecordPrices` flag enabled, so {@code computeInternal()}
 *   populates a per-inner-record side-output funnel alongside the regular per-entity result. Every
 *   {@link FilteredPriceRecordAccessor} in {@link #filteredPriceRecordAccessors} that exposes that
 *   side-output contributes one bucket data point per inner-record-id rather than one per entity.
 *
 * The two rules compose rather than exclude each other: a `filterBy` produces one price branch per
 * handling value present in the catalog, so a candidate pool mixing simple products with master/variant
 * products is the common case, not an exception. The per-inner-record records are collected first and the
 * per-entity collector then tops up exactly the entities they did not cover — see
 * {@link #collectPerInnerRecordHistogramRecords(List)}.
 *
 * The computed result is memoized in {@link #memoizedResult}; the intermediate price records array is memoized in
 * {@link #memoizedPriceRecords}. Both fields are `null` until {@link #compute()} is first invoked.
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

	/**
	 * Method creates instance of histogram data cruncher that computes optimal histogram for prices.
	 * Returns either {@link HistogramDataCruncher} or {@link EqualizedHistogramDataCruncher} based on behavior.
	 *
	 * @param bucketCount        requested number of buckets
	 * @param behavior           histogram behavior (STANDARD, OPTIMIZED, EQUALIZED, EQUALIZED_OPTIMIZED)
	 * @param indexedPricePlaces number of decimal places for price indexing
	 * @param priceRecords       sorted array of price records
	 * @param priceRetriever     function to extract price value from price record
	 * @return histogram data cruncher or null if price records are empty
	 */
	@Nullable
	private static HistogramDataCruncherContract<PriceRecordContract> createHistogramDataCruncher(
		int bucketCount,
		@Nonnull HistogramBehavior behavior,
		int indexedPricePlaces,
		@Nonnull PriceRecordContract[] priceRecords,
		@Nonnull ToIntFunction<PriceRecordContract> priceRetriever
	) {
		if (ArrayUtils.isEmpty(priceRecords)) {
			return null;
		}

		return switch (behavior) {
			case STANDARD -> new HistogramDataCruncher<>(
				"price histogram", bucketCount, indexedPricePlaces, priceRecords,
				priceRetriever,
				value -> 1,
				value -> indexedPricePlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).scaleByPowerOfTen(-1 * indexedPricePlaces),
				value -> indexedPricePlaces == 0 ? value.intValueExact() : value.scaleByPowerOfTen(indexedPricePlaces).intValueExact()
			);
			case OPTIMIZED -> HistogramDataCruncher.createOptimalHistogram(
				"price histogram", bucketCount, indexedPricePlaces, priceRecords,
				priceRetriever,
				value -> 1,
				value -> indexedPricePlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).scaleByPowerOfTen(-1 * indexedPricePlaces),
				value -> indexedPricePlaces == 0 ? value.intValueExact() : value.scaleByPowerOfTen(indexedPricePlaces).intValueExact()
			);
			case EQUALIZED -> new EqualizedHistogramDataCruncher<>(
				"price histogram", bucketCount, indexedPricePlaces, priceRecords,
				priceRetriever,
				value -> 1,
				value -> indexedPricePlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).scaleByPowerOfTen(-1 * indexedPricePlaces),
				EqualizedHistogramDataCruncher.BucketCountMode.EXACT
			);
			case EQUALIZED_OPTIMIZED -> new EqualizedHistogramDataCruncher<>(
				"price histogram",
				bucketCount,
				indexedPricePlaces,
				priceRecords,
				priceRetriever,
				value -> 1,
				value -> indexedPricePlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).scaleByPowerOfTen(-1 * indexedPricePlaces),
				EqualizedHistogramDataCruncher.BucketCountMode.ADAPTIVE
			);
		};
	}

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
		// construction-time only — stream OK here (allocation-free hot paths use this hash, but the hashing
		// itself runs exactly once per producer instance)
		this.transactionalIdHash = HASH_FUNCTION.hashLongs(
			Arrays.stream(this.transactionalIds)
				.distinct()
				.sorted()
				.toArray()
		);
		// floor((size + 1) / 2) — equivalent to ceil(size / 2.0) without floating-point arithmetic. The
		// previous formulation `size / 2` collapsed to 0 for the common single-accessor case, hiding the
		// per-record operation cost contribution from the planner's cost estimate.
		this.estimatedCost = filteringFormula.getEstimatedCardinality() *
			Math.max(1, (filteredPriceRecordAccessors.size() + 1) / 2) *
			getOperationCost();
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		this.context = executionContext;
		this.filteringFormula.initialize(executionContext);
		if (this.filteringFormulaWithFilteredOutRecords != null) {
			// the relaxed clone produced by UserFilterRelaxer reuses subtrees from filteringFormula,
			// but rebuilt container nodes (root and ancestors of any peeled UserFilter) are fresh
			// instances whose executionContext is null. Walking the relaxed tree here matches the
			// pattern in AttributeHistogramComputer and removes the contract violation hiding
			// behind the fact that today's container formulas happen not to read executionContext
			this.filteringFormulaWithFilteredOutRecords.initialize(executionContext);
		}
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
		// equalized variants have similar cost structure
		return switch (this.behavior) {
			case STANDARD, EQUALIZED -> 7511;
			case OPTIMIZED, EQUALIZED_OPTIMIZED -> 11267;
		};
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

				// create cruncher that will compute the histogram
				final HistogramDataCruncherContract<PriceRecordContract> histogramCruncher = createHistogramDataCruncher(
					this.bucketCount, this.behavior, this.indexedPricePlaces, priceRecords, priceRetriever
				);

				if (histogramCruncher != null) {
					this.memoizedResult = new CacheableHistogram(
						histogramCruncher.getHistogram(),
						histogramCruncher.getMaxValue()
					);
				} else {
					this.memoizedResult = CacheableHistogramContract.EMPTY;
				}
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
	 *
	 * Branching:
	 *
	 * - When **at least one** accessor in {@link #filteredPriceRecordAccessors} exposes the per-inner-record
	 *   side-output prepared at construction time by {@link LowestPriceTerminationFormula} (or its
	 *   flattened cache sibling {@link FlattenedFormulaWithFilteredPricesForHistogram}), those accessors
	 *   contribute one bucket data point per inner record id and every entity they do not cover is topped
	 *   up from the {@link FilteredPriceRecordsCollector} — see
	 *   {@link #collectPerInnerRecordHistogramRecords(List)}. The {@code PriceHistogramTranslator} collects
	 *   this accessor list from a {@code withoutUserFilter} view of the filtering tree so the inner
	 *   {@link LowestPriceTerminationFormula} produced by {@code priceBetween} (which would otherwise
	 *   double-count the same entities) does not show up here.
	 * - Otherwise (pure `NONE`/`SUM` handling, or no LOWEST_PRICE LP at all) the existing collector path
	 *   runs unchanged.
	 */
	private PriceRecordContract[] getPriceRecords() {
		if (this.memoizedPriceRecords == null) {
			final List<FilteredPriceRecordAccessor> histogramAccessors =
				FilteredPriceRecords.collectPerInnerRecordHistogramAccessors(this.filteredPriceRecordAccessors);
			this.memoizedPriceRecords = histogramAccessors.isEmpty() ?
				collectViaPriceRecordsCollector() :
				collectPerInnerRecordHistogramRecords(histogramAccessors);
		}
		return this.memoizedPriceRecords;
	}

	/**
	 * Assembles the histogram baseline from a candidate pool that may mix
	 * {@link io.evitadb.api.requestResponse.data.PriceInnerRecordHandling} values, in two passes:
	 *
	 * 1. the per-inner-record side-output of `histogramAccessors` (the `LOWEST_PRICE` branches) —
	 *    **narrowed to the entities the query actually matched**. A termination formula's side-output
	 *    covers everything its own price sub-tree matched and is not intersected with the non-price parts
	 *    of the query (an `entityPrimaryKeyInSet`, an attribute filter, a hierarchy constraint), so
	 *    without this narrowing the histogram would describe entities the query excluded;
	 * 2. the per-entity price for sale of every remaining entity, collected through the unchanged
	 *    {@link FilteredPriceRecordsCollector}. This covers the `NONE` and `SUM` branches, for which one
	 *    data point per entity is the correct contribution.
	 *
	 * The second pass runs over `baseline \ covered` rather than over the whole baseline, which is what
	 * makes double counting impossible by construction — including on the prefetch plan, where a
	 * `SelectionFormula`'s per-entity alternative would happily answer for a `LOWEST_PRICE` entity that
	 * pass 1 already expanded. For the same reason {@link #priceRecordsLookupResult} (computed by
	 * {@link io.evitadb.core.query.sort.price.FilteredPricesSorter} over the *full* accessor set and the
	 * *full* result) must not be reused here.
	 *
	 * @param histogramAccessors accessors exposing the per-inner-record side-output; never empty
	 * @return every price record the histogram should bucket
	 */
	@Nonnull
	private PriceRecordContract[] collectPerInnerRecordHistogramRecords(
		@Nonnull List<FilteredPriceRecordAccessor> histogramAccessors
	) {
		final PersistentRoaringBitmap baseline = computeHistogramBaseline();
		final PriceRecordContract[] perInnerRecordPrices = FilteredPriceRecords.mergePerInnerRecordHistogramRecords(
			histogramAccessors, this.context
		);

		// pass 1 — keep only the per-inner-record prices of entities that survived the whole filter, and
		// remember which entities they already account for. `covered` is built by incremental `add` rather
		// than through `RoaringBitmapBackedBitmap.buildWriter()`: the constant-memory writer materializes a
		// container as soon as the high bits change and therefore assumes ascending input, which the
		// concatenation of several accessors' side-outputs does not provide (each restarts at its own
		// lowest entity primary key)
		final PersistentRoaringBitmap covered = new PersistentRoaringBitmap();
		final PriceRecordContract[] narrowedPrices = new PriceRecordContract[perInnerRecordPrices.length];
		int narrowedCount = 0;
		for (final PriceRecordContract priceRecord : perInnerRecordPrices) {
			final int entityPrimaryKey = priceRecord.entityPrimaryKey();
			if (baseline.contains(entityPrimaryKey)) {
				narrowedPrices[narrowedCount++] = priceRecord;
				covered.add(entityPrimaryKey);
			}
		}

		// pass 2 — top up with the per-entity price for sale of everything pass 1 left uncovered
		final PersistentRoaringBitmap remainder = PersistentRoaringBitmap.andNot(baseline, covered);
		if (remainder.isEmpty()) {
			return narrowedCount == narrowedPrices.length ?
				narrowedPrices : Arrays.copyOf(narrowedPrices, narrowedCount);
		}
		final PriceRecordContract[] perEntityPrices = FilteredPriceRecords
			.collectFilteredPriceRecordsFromPriceRecordAccessors(
				this.filteredPriceRecordAccessors, remainder, this.context
			)
			.getPriceRecords();
		if (perEntityPrices.length == 0) {
			return narrowedCount == narrowedPrices.length ?
				narrowedPrices : Arrays.copyOf(narrowedPrices, narrowedCount);
		}

		final PriceRecordContract[] merged = new PriceRecordContract[narrowedCount + perEntityPrices.length];
		System.arraycopy(narrowedPrices, 0, merged, 0, narrowedCount);
		System.arraycopy(perEntityPrices, 0, merged, narrowedCount, perEntityPrices.length);
		return merged;
	}

	/**
	 * Returns the entity primary keys the histogram must describe — the filtering result widened by the
	 * entities the `priceBetween` slider inside `userFilter` filtered out, mirroring the union
	 * {@link #collectViaPriceRecordsCollector()} produces through
	 * {@link FilteredPriceRecordsCollector#combineResultWithAndReturnPriceRecords}. The histogram is
	 * supposed to answer *"what prices would be reachable if the user cleared the price slider"*, so the
	 * relaxed clone contributes to the baseline even though it never contributes to the query result.
	 */
	@Nonnull
	private PersistentRoaringBitmap computeHistogramBaseline() {
		final PersistentRoaringBitmap filteringResult = RoaringBitmapBackedBitmap.getRoaringBitmap(
			this.filteringFormula.compute()
		);
		if (this.filteringFormulaWithFilteredOutRecords == null) {
			return filteringResult;
		}
		final Bitmap pricePredicateFilteredOutEntities = this.filteringFormulaWithFilteredOutRecords.compute();
		return pricePredicateFilteredOutEntities.isEmpty() ?
			filteringResult :
			PersistentRoaringBitmap.or(
				filteringResult,
				RoaringBitmapBackedBitmap.getRoaringBitmap(pricePredicateFilteredOutEntities)
			);
	}

	/**
	 * Original per-entity collector path used for `NONE`/`SUM` handling — bypassed by the per-inner-record
	 * histogram path above when every accessor exposes the histogram-aware side-output. Three sub-branches:
	 *
	 * 1. **No price-between user filter** (`filteringFormulaWithFilteredOutRecords == null`) — the
	 *    filtering result itself is the full histogram baseline; the collector's per-entity output is
	 *    returned verbatim.
	 * 2. **Price-between user filter present, no records filtered out** — the filtering result still
	 *    covers the baseline since no entity was actually excluded by the price-between predicate; the
	 *    collector's output is returned verbatim, avoiding a redundant combine call.
	 * 3. **Price-between user filter present, with filtered-out records** — the relaxed remainder is
	 *    combined with the filtering-result records so the histogram reflects the entities that would be
	 *    reachable if the user cleared the price slider.
	 */
	@Nonnull
	private PriceRecordContract[] collectViaPriceRecordsCollector() {
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
		if (this.filteringFormulaWithFilteredOutRecords == null) {
			// there were no entity pks filtered out due to price between query, we can simply reuse
			// the filtering query result
			return priceRecordsCollector.getResult().getPriceRecords();
		}
		// now compute the remainder with altered filtering formula
		final Bitmap pricePredicateFilteredOutEntities = this.filteringFormulaWithFilteredOutRecords.compute();
		if (pricePredicateFilteredOutEntities.isEmpty()) {
			// we can simply reuse the filtering query result as is, nothing has been filtered out
			return priceRecordsCollector.getResult().getPriceRecords();
		}
		// we have to combine filtering query result with computed remainder in order to get all price records
		// regardless of the price between query
		return priceRecordsCollector.combineResultWithAndReturnPriceRecords(
			RoaringBitmapBackedBitmap.getRoaringBitmap(pricePredicateFilteredOutEntities)
		);
	}
}
