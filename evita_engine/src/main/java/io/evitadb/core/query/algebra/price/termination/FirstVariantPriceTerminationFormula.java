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

package io.evitadb.core.query.algebra.price.termination;

import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.IntObjectWormMap;
import com.carrotsearch.hppc.ObjectContainer;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.cache.payload.FlattenedFormula;
import io.evitadb.core.cache.payload.FlattenedFormulaWithFilteredPricesAndFilteredOutRecords;
import io.evitadb.core.query.SharedBufferPool;
import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.PriceRecordLookup;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.innerRecordHandling.PriceHandlingContainerFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramProducer;
import io.evitadb.index.array.CompositeObjectArray;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.iterator.BatchArrayIterator;
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
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * FirstVariantPriceTerminationFormula picks lowest filtered price for each entity id as a representative price for it.
 * It may also filter out entity ids which don't pass {@link #pricePredicate} predicate test.
 *
 * This formula consumes and produces {@link Formula} of {@link PriceRecord#entityPrimaryKey() entity ids}. It uses
 * information from underlying formulas that implement {@link FilteredPriceRecordAccessor#getFilteredPriceRecords()}
 * to access the lowest price of each entity/inner record id combination for filtering purposes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FirstVariantPriceTerminationFormula extends AbstractCacheableFormula implements FilteredPriceRecordAccessor, PriceTerminationFormula {
	private static final long CLASS_ID = -4905806490462655316L;

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
	 * Price filter is used to filter out entities which price doesn't match the predicate.
	 */
	@Getter private final PricePredicate pricePredicate;
	/**
	 * Comparator used for selecting the lowest price for each entity id among all prices for different inner record ids.
	 */
	private final Comparator<PriceRecordContract> priceRecordComparator;
	/**
	 * Contains array of price records that links to the price ids produced by {@link #compute()} method. This array
	 * is available once the {@link #compute()} method has been called.
	 */
	private FilteredPriceRecords filteredPriceRecords;
	/**
	 * Bitmap is initialized (non-null) after {@link #compute()} method is called and contains set of entity primary
	 * keys that were excluded due to {@link #pricePredicate} query. This information is reused in
	 * {@link PriceHistogramProducer} to avoid duplicate computation - price histogram must not take price predicate
	 * into an account.
	 */
	@Getter private Bitmap recordsFilteredOutByPredicate;

	public FirstVariantPriceTerminationFormula(@Nonnull PriceHandlingContainerFormula containerFormula, @Nonnull PriceEvaluationContext priceEvaluationContext, @Nonnull QueryPriceMode queryPriceMode, @Nonnull PricePredicate pricePredicate) {
		super(null, containerFormula);
		this.pricePredicate = pricePredicate;
		this.priceEvaluationContext = priceEvaluationContext;
		this.queryPriceMode = queryPriceMode;
		this.priceRecordComparator = queryPriceMode == QueryPriceMode.WITH_TAX ?
			Comparator.comparingInt(PriceRecordContract::priceWithTax) :
			Comparator.comparingInt(PriceRecordContract::priceWithoutTax);
	}

	private FirstVariantPriceTerminationFormula(@Nullable Consumer<CacheableFormula> computationCallback, @Nonnull PriceHandlingContainerFormula containerFormula, @Nonnull PriceEvaluationContext priceEvaluationContext, @Nonnull QueryPriceMode queryPriceMode, @Nonnull PricePredicate pricePredicate) {
		super(computationCallback, containerFormula);
		this.pricePredicate = pricePredicate;
		this.priceEvaluationContext = priceEvaluationContext;
		this.queryPriceMode = queryPriceMode;
		this.priceRecordComparator = queryPriceMode == QueryPriceMode.WITH_TAX ?
			Comparator.comparingInt(PriceRecordContract::priceWithTax) :
			Comparator.comparingInt(PriceRecordContract::priceWithoutTax);
	}

	private FirstVariantPriceTerminationFormula(@Nullable Consumer<CacheableFormula> computationCallback, @Nonnull PriceHandlingContainerFormula containerFormula, @Nonnull PriceEvaluationContext priceEvaluationContext, @Nonnull QueryPriceMode queryPriceMode, @Nonnull PricePredicate pricePredicate, @Nullable Bitmap recordsFilteredOutByPredicate) {
		super(recordsFilteredOutByPredicate, computationCallback, containerFormula);
		this.pricePredicate = pricePredicate;
		this.priceEvaluationContext = priceEvaluationContext;
		this.queryPriceMode = queryPriceMode;
		this.priceRecordComparator = queryPriceMode == QueryPriceMode.WITH_TAX ?
			Comparator.comparingInt(PriceRecordContract::priceWithTax) :
			Comparator.comparingInt(PriceRecordContract::priceWithoutTax);
		this.recordsFilteredOutByPredicate = recordsFilteredOutByPredicate;
	}

	@Nullable
	@Override
	public Predicate<BigDecimal> getRequestedPredicate() {
		return pricePredicate.getRequestedPredicate();
	}

	/**
	 * Returns delegate formula of this container.
	 */
	public Formula getDelegate() {
		return ((PriceHandlingContainerFormula) this.innerFormulas[0]).getDelegate();
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new FirstVariantPriceTerminationFormula(
			computationCallback,
			(PriceHandlingContainerFormula) innerFormulas[0],
			priceEvaluationContext, queryPriceMode, pricePredicate
		);
	}

	@Override
	public long getOperationCost() {
		return 18203;
	}

	@Nonnull
	@Override
	public Formula getCloneWithPricePredicateFilteredOutResults() {
		return new FirstVariantPriceTerminationFormula(
			computationCallback, (PriceHandlingContainerFormula) innerFormulas[0],
			priceEvaluationContext, queryPriceMode, PricePredicate.NO_FILTER,
			recordsFilteredOutByPredicate
		);
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(@Nonnull Consumer<CacheableFormula> selfOperator, @Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new FirstVariantPriceTerminationFormula(
			selfOperator,
			(PriceHandlingContainerFormula) innerFormulas[0],
			priceEvaluationContext, queryPriceMode, pricePredicate
		);
	}

	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecords() {
		Assert.notNull(filteredPriceRecords, "Call #compute() method first!");
		return filteredPriceRecords;
	}

	@Override
	public String toString() {
		return pricePredicate.toString();
	}

	@Override
	public FlattenedFormula toSerializableFormula(long formulaHash, @Nonnull LongHashFunction hashFunction) {
		return new FlattenedFormulaWithFilteredPricesAndFilteredOutRecords(
			formulaHash,
			computeTransactionalIdHash(hashFunction),
			Arrays.stream(gatherTransactionalIds())
				.distinct()
				.sorted()
				.toArray(),
			compute(),
			getFilteredPriceRecords(),
			Objects.requireNonNull(getRecordsFilteredOutByPredicate()),
			getPriceEvaluationContext(),
			pricePredicate.getFrom(),
			pricePredicate.getTo()
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
				.map(FilteredPriceRecordAccessor::getFilteredPriceRecords)
				.map(FilteredPriceRecords::getPriceRecordsLookup)
				.toArray(PriceRecordLookup[]::new);
			// create array for the lowest prices by entity
			final CompositeObjectArray<PriceRecordContract> priceRecordsFunnel = new CompositeObjectArray<>(PriceRecordContract.class, false);
			// create helper associative index for looking up index of the lowest price by entity id in the priceRecordsFunnel
			final IntObjectMap<PriceRecordContract> entityInnerRecordPrice = new IntObjectWormMap<>();
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
									if (innerRecordPrice == null) {
										entityInnerRecordPrice.put(innerRecordId, foundPrice);
									}
								}
							);
						}

						Assert.isPremiseValid(
							!entityInnerRecordPrice.isEmpty(),
							"Price for entity with PK " + entityId + " unexpectedly not found!"
						);

						// locate the lowest price of entity id that passes the filter
						boolean anyPriceMatchesTheFilter = false;
						PriceRecordContract lowestPrice = null;
						final ObjectContainer<PriceRecordContract> values = entityInnerRecordPrice.values();
						for (ObjectCursor<PriceRecordContract> value : values) {
							final PriceRecordContract innerRecordPrice = value.value;
							// test whether inner entity price matches the filter
							anyPriceMatchesTheFilter |= pricePredicate.test(innerRecordPrice);
							if (lowestPrice == null || priceRecordComparator.compare(innerRecordPrice, lowestPrice) < 0) {
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
				priceEvaluationContext.computeHash(hashFunction),
				pricePredicate.computeHash(hashFunction)
			}
		);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

}
