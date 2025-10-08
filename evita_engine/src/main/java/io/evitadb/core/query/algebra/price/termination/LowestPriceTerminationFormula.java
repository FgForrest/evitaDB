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

package io.evitadb.core.query.algebra.price.termination;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.ObjectContainer;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.cache.payload.FlattenedFormula;
import io.evitadb.core.cache.payload.FlattenedFormulaWithFilteredPricesAndFilteredOutRecords;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.SharedBufferPool;
import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.PriceRecordLookup;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.predicate.PriceAmountPredicate;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.predicate.PriceRecordPredicate;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramProducer;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.dataType.iterator.BatchArrayIterator;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.iterator.RoaringBitmapBatchArrayIterator;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * LowestPriceTerminationFormula picks lowest filtered price for each entity id as a representative price for it.
 * It may also filter out entity ids which don't pass {@link #sellingPricePredicate} predicate test.
 *
 * This formula consumes and produces {@link Formula} of {@link PriceRecord#entityPrimaryKey() entity ids}. It uses
 * information from underlying formulas that implement {@link FilteredPriceRecordAccessor#getFilteredPriceRecords(QueryExecutionContext)}
 * to access the lowest price of each entity/inner record id combination for filtering purposes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class LowestPriceTerminationFormula extends AbstractCacheableFormula implements FilteredPriceRecordAccessor, PriceTerminationFormula {
	private static final long CLASS_ID = -4905806490462655316L;
	private static final Predicate<PriceRecordContract> ALL_MATCHING_PREDICATE = priceContract -> true;

	/**
	 * Price evaluation context allows optimizing formula tree in the such way, that terminating formula with same
	 * context will be replaced by single instance - taking advantage of result memoization.
	 */
	@Getter private final PriceEvaluationContext priceEvaluationContext;
	/**
	 * Contains query price mode of the current query.
	 */
	@Getter private final QueryPriceMode queryPriceMode;
	/**
	 * Price filter is used to filter out entities which final selling price doesn't match the predicate.
	 */
	@Getter private final PriceRecordPredicate sellingPricePredicate;
	/**
	 * Predicate that filters out individual prices from being calculated in selling price.
	 */
	private final Predicate<PriceRecordContract> individualPricePredicate;
	/**
	 * Comparator used for selecting the lowest price for each entity id among all prices for different inner record ids.
	 */
	private final Comparator<PriceRecordContract> priceRecordComparator;
	/**
	 * Contains array of price records that links to the price ids produced by {@link Formula#compute()} method. This array
	 * is available once the {@link Formula#compute()} method has been called.
	 */
	private FilteredPriceRecords filteredPriceRecords;
	/**
	 * Bitmap is initialized (non-null) after {@link Formula#compute()} method is called and contains set of entity primary
	 * keys that were excluded due to {@link #sellingPricePredicate} query. This information is reused in
	 * {@link PriceHistogramProducer} to avoid duplicate computation - price histogram must not take price predicate
	 * into an account.
	 */
	@Nullable @Getter private Bitmap recordsFilteredOutByPredicate;

	public LowestPriceTerminationFormula(
		@Nonnull Formula containerFormula,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull PriceRecordPredicate sellingPricePredicate
	) {
		super(null);
		this.sellingPricePredicate = sellingPricePredicate;
		this.individualPricePredicate = ALL_MATCHING_PREDICATE;
		this.priceEvaluationContext = priceEvaluationContext;
		this.queryPriceMode = queryPriceMode;
		this.priceRecordComparator = queryPriceMode == QueryPriceMode.WITH_TAX ?
			Comparator.comparingInt(PriceRecordContract::priceWithTax) :
			Comparator.comparingInt(PriceRecordContract::priceWithoutTax);
		this.initFields(containerFormula);
	}

	private LowestPriceTerminationFormula(
		@Nullable Consumer<CacheableFormula> computationCallback,
		@Nonnull Formula containerFormula,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull PriceRecordPredicate sellingPricePredicate,
		@Nonnull Predicate<PriceRecordContract> individualPricePredicate
	) {
		super(computationCallback);
		this.sellingPricePredicate = sellingPricePredicate;
		this.individualPricePredicate = individualPricePredicate;
		this.priceEvaluationContext = priceEvaluationContext;
		this.queryPriceMode = queryPriceMode;
		this.priceRecordComparator = queryPriceMode == QueryPriceMode.WITH_TAX ?
			Comparator.comparingInt(PriceRecordContract::priceWithTax) :
			Comparator.comparingInt(PriceRecordContract::priceWithoutTax);
		this.initFields(containerFormula);
	}

	private LowestPriceTerminationFormula(
		@Nullable Consumer<CacheableFormula> computationCallback,
		@Nonnull Formula containerFormula,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull PriceRecordPredicate sellingPricePredicate,
		@Nonnull Predicate<PriceRecordContract> individualPricePredicate,
		@Nullable Bitmap recordsFilteredOutByPredicate
	) {
		super(recordsFilteredOutByPredicate, computationCallback);
		this.sellingPricePredicate = sellingPricePredicate;
		this.individualPricePredicate = individualPricePredicate;
		this.priceEvaluationContext = priceEvaluationContext;
		this.queryPriceMode = queryPriceMode;
		this.priceRecordComparator = queryPriceMode == QueryPriceMode.WITH_TAX ?
			Comparator.comparingInt(PriceRecordContract::priceWithTax) :
			Comparator.comparingInt(PriceRecordContract::priceWithoutTax);
		this.recordsFilteredOutByPredicate = recordsFilteredOutByPredicate;
		this.initFields(containerFormula);
	}

	/**
	 * Creates a new instance of LowestPriceTerminationFormula with the specified individual price predicate and
	 * retains the existing computation callback, delegate formula, price evaluation context, query price mode,
	 * and selling price predicate.
	 *
	 * @param individualPricePredicate the predicate to filter individual price records; must not be null
	 * @return a new instance of LowestPriceTerminationFormula with the specified individual price predicate
	 */
	@Nonnull
	public LowestPriceTerminationFormula withIndividualPricePredicate(
		@Nonnull Predicate<PriceRecordContract> individualPricePredicate
	) {
		return new LowestPriceTerminationFormula(
			this.computationCallback,
			getDelegate(),
			this.priceEvaluationContext,
			this.queryPriceMode,
			this.sellingPricePredicate,
			individualPricePredicate
		);
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		getDelegate().initialize(executionContext);
		super.initialize(executionContext);
	}

	@Nullable
	@Override
	public PriceAmountPredicate getRequestedPredicate() {
		return this.sellingPricePredicate.getRequestedPredicate();
	}

	/**
	 * Returns delegate formula of this container.
	 */
	public Formula getDelegate() {
		return this.innerFormulas[0];
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new LowestPriceTerminationFormula(
			this.computationCallback,
			innerFormulas[0],
			this.priceEvaluationContext, this.queryPriceMode, this.sellingPricePredicate, this.individualPricePredicate
		);
	}

	@Override
	public long getOperationCost() {
		return 18203;
	}

	@Nonnull
	@Override
	public Formula getCloneWithPricePredicateFilteredOutResults() {
		return new LowestPriceTerminationFormula(
			this.computationCallback, this.innerFormulas[0],
			this.priceEvaluationContext, this.queryPriceMode, PricePredicate.ALL_RECORD_FILTER, this.individualPricePredicate,
			this.recordsFilteredOutByPredicate
		);
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(@Nonnull Consumer<CacheableFormula> selfOperator, @Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new LowestPriceTerminationFormula(
			selfOperator,
			innerFormulas[0],
			this.priceEvaluationContext, this.queryPriceMode, this.sellingPricePredicate, this.individualPricePredicate
		);
	}

	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecords(@Nonnull QueryExecutionContext context) {
		if (this.filteredPriceRecords == null) {
			// init the records first
			compute();
		}
		return this.filteredPriceRecords;
	}

	@Override
	public String toString() {
		return this.sellingPricePredicate.toString();
	}

	@Override
	public FlattenedFormula toSerializableFormula(long formulaHash, @Nonnull LongHashFunction hashFunction) {
		return new FlattenedFormulaWithFilteredPricesAndFilteredOutRecords(
			formulaHash,
			getTransactionalIdHash(),
			Arrays.stream(gatherTransactionalIds())
				.distinct()
				.sorted()
				.toArray(),
			compute(),
			getFilteredPriceRecords(this.executionContext),
			Objects.requireNonNull(getRecordsFilteredOutByPredicate()),
			getPriceEvaluationContext(),
			this.sellingPricePredicate.getQueryPriceMode(),
			this.sellingPricePredicate.getFrom(),
			this.sellingPricePredicate.getTo(),
			this.sellingPricePredicate.getIndexedPricePlaces()
		);
	}

	@Override
	public int getSerializableFormulaSizeEstimate() {
		return FlattenedFormulaWithFilteredPricesAndFilteredOutRecords.estimateSize(
			gatherTransactionalIds(),
			compute(),
			Objects.requireNonNull(getRecordsFilteredOutByPredicate()),
			getPriceEvaluationContext()
		);
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		// retrieve filtered entity ids from the delegate formula
		final RoaringBitmap computedRoaringBitmap = RoaringBitmapBackedBitmap.getRoaringBitmap(getDelegate().compute());

		// if there are any entities found
		if (!computedRoaringBitmap.isEmpty()) {
			// collect all FilteredPriceRecordAccessor that were involved in computing delegate result
			final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors = FormulaFinder.find(
				getDelegate(), FilteredPriceRecordAccessor.class, LookUp.SHALLOW
			);
			// collect price iterators ordered by price list importance
			final PriceRecordLookup[] priceRecordIterators = filteredPriceRecordAccessors
				.stream()
				.map(it -> it.getFilteredPriceRecords(this.executionContext))
				.map(FilteredPriceRecords::getPriceRecordsLookup)
				.toArray(PriceRecordLookup[]::new);
			// create array for the lowest prices by entity
			final CompositeObjectArray<PriceRecordContract> priceRecordsFunnel = new CompositeObjectArray<>(PriceRecordContract.class, false);
			// create helper associative index for looking up index of the lowest price by entity id in the priceRecordsFunnel
			final IntObjectMap<PriceRecordContract> entityInnerRecordPrice = new IntObjectHashMap<>();
			// create new roaring bitmap builder
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			// create new roaring bitmap builder for records excluded by predicate
			final RoaringBitmapWriter<RoaringBitmap> predicateExcludedWriter = RoaringBitmapBackedBitmap.buildWriter();
			final int[] buffer = SharedBufferPool.INSTANCE.obtain();
			try {
				final BatchArrayIterator entityIdIterator = new RoaringBitmapBatchArrayIterator(
					computedRoaringBitmap.getBatchIterator(), buffer
				);
				// go through all entity primary keys
				while (entityIdIterator.hasNext()) {
					final int[] batch = entityIdIterator.nextBatch();
					final int lastExpectedEntity = entityIdIterator.getPeek() > 0 ? batch[entityIdIterator.getPeek() - 1] : -1;
					for (int i = 0; i < entityIdIterator.getPeek(); i++) {
						final int entityId = batch[i];
						// clear working inner record identity map
						entityInnerRecordPrice.clear();

						// now iterate over price sets in price list priority
						for (final PriceRecordLookup priceRecords : priceRecordIterators) {
							priceRecords.forEachPriceOfEntity(
								entityId,
								lastExpectedEntity,
								foundPrice -> {
									// record price found for this inner entity id - but only if not already present
									// if it's present it means the price was already found in more prioritized price list
									final int innerRecordId = foundPrice.innerRecordId();
									final PriceRecordContract innerRecordPrice = entityInnerRecordPrice.get(innerRecordId);
									// we need to filter the price using individual price predicate
									// this handles the situation when we want to consider only prices that relate
									// for previously selected selling price (e.g. when we calculate the discount)
									if (innerRecordPrice == null && this.individualPricePredicate.test(foundPrice)) {
										entityInnerRecordPrice.put(innerRecordId, foundPrice);
									}
								}
							);
						}

						Assert.isPremiseValid(
							this.individualPricePredicate != ALL_MATCHING_PREDICATE || !entityInnerRecordPrice.isEmpty(),
							"Price for entity with PK " + entityId + " unexpectedly not found!"
						);

						// locate the lowest price of entity id that passes the filter
						boolean anyPriceMatchesTheFilter = false;
						PriceRecordContract lowestPrice = null;
						final ObjectContainer<PriceRecordContract> values = entityInnerRecordPrice.values();
						for (ObjectCursor<PriceRecordContract> value : values) {
							final PriceRecordContract innerRecordPrice = value.value;
							// test whether inner entity price matches the filter
							anyPriceMatchesTheFilter |= this.sellingPricePredicate.test(innerRecordPrice);
							if (lowestPrice == null || this.priceRecordComparator.compare(innerRecordPrice, lowestPrice) < 0) {
								lowestPrice = innerRecordPrice;
							}
						}
						if (anyPriceMatchesTheFilter) {
							// if so - entity id continues to output of this formula
							writer.add(entityId);
							// from now on - work with the lowest entity price grouped by inner record
							priceRecordsFunnel.add(lowestPrice);
						} else {
							predicateExcludedWriter.add(entityId);
						}
					}
				}
			} finally {
				SharedBufferPool.INSTANCE.free(buffer);
			}

			// remember the prices selected during computation
			this.filteredPriceRecords = new ResolvedFilteredPriceRecords(
				priceRecordsFunnel.toArray(),
				SortingForm.ENTITY_PK
			);

			// wrap result into the bitmap
			this.recordsFilteredOutByPredicate = new BaseBitmap(predicateExcludedWriter.get());
			return new BaseBitmap(writer.get());
		} else {
			this.filteredPriceRecords = new ResolvedFilteredPriceRecords();
			this.recordsFilteredOutByPredicate = EmptyBitmap.INSTANCE;
			return EmptyBitmap.INSTANCE;
		}
	}

	@Override
	public int getEstimatedCardinality() {
		return Arrays.stream(this.innerFormulas).mapToInt(Formula::getEstimatedCardinality).sum();
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			new long[]{
				this.priceEvaluationContext.computeHash(hashFunction),
				this.sellingPricePredicate.computeHash(hashFunction)
			}
		);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

}
