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

import com.carrotsearch.hppc.IntCollection;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.ObjectContainer;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.SharedBufferPool;
import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.PriceRecordLookup;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.predicate.PriceAmountPredicate;
import io.evitadb.core.query.algebra.price.predicate.PriceRecordPredicate;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.dataType.iterator.BatchArrayIterator;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.iterator.RoaringBitmapBatchArrayIterator;
import io.evitadb.index.price.model.priceRecord.CumulatedVirtualPriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * SumPriceTerminationFormula aggregates all filtered prices by their entity ids and sums up their prices creating new
 * virtual price for the entity. This virtual price can be used for sorting products by the price. It may also filter
 * out entity ids which don't pass {@link #sellingPricePredicate} predicate test.
 *
 * This formula consumes and produces {@link Formula} of {@link PriceRecord#entityPrimaryKey() entity ids}. It uses
 * information from underlying formulas that implement {@link FilteredPriceRecordAccessor#getFilteredPriceRecords(QueryExecutionContext)}
 * to compute virtual (sum of) prices of entity ids across all of their inner records.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class SumPriceTerminationFormula extends AbstractCacheableFormula implements FilteredPriceRecordAccessor, PriceTerminationFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = 8387802561001219891L;
	/**
	 * Default predicate that accepts all price records without any filtering and reports zero missing component price.
	 */
	private static final SumPredicate<PriceRecordContract> ALL_MATCHING_PREDICATE = new SumPredicate<>() {
		@Override
		public int getMissingComponentsPrice(@Nonnull IntCollection includedInnerRecordIds) {
			return 0;
		}

		@Override
		public boolean test(PriceRecordContract priceRecordContract) {
			return true;
		}
	};

	/**
	 * Price evaluation context allows optimizing formula tree in the such way, that terminating formula with same
	 * context will be replaced by single instance - taking advantage of result memoization.
	 */
	private final PriceEvaluationContext priceEvaluationContext;
	/**
	 * Contains query price mode of the current query.
	 */
	private final QueryPriceMode queryPriceMode;
	/**
	 * Price filter is used to filter out entities which final selling price doesn't match the predicate.
	 */
	@Getter private final PriceRecordPredicate sellingPricePredicate;
	/**
	 * Predicate that filters out individual prices from being calculated in selling price.
	 */
	private final SumPredicate<PriceRecordContract> individualPricePredicate;
	/**
	 * Function retrieves the proper price from the price record.
	 */
	private final ToIntFunction<PriceRecordContract> transformer;
	/**
	 * Contains array of price records that links to the price ids produced by {@link Formula#compute()} method. This array
	 * is available once the {@link Formula#compute()} method has been called.
	 */
	private FilteredPriceRecords filteredPriceRecords;

	public SumPriceTerminationFormula(
		@Nonnull Formula containerFormula,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull PriceRecordPredicate sellingPricePredicate
	) {
		this.sellingPricePredicate = sellingPricePredicate;
		this.individualPricePredicate = ALL_MATCHING_PREDICATE;
		this.priceEvaluationContext = priceEvaluationContext;
		this.queryPriceMode = queryPriceMode;
		if (queryPriceMode == QueryPriceMode.WITH_TAX) {
			this.transformer = PriceRecordContract::priceWithTax;
		} else {
			this.transformer = PriceRecordContract::priceWithoutTax;
		}
		this.initFields(containerFormula);
	}

	private SumPriceTerminationFormula(
		@Nonnull Formula containerFormula,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull PriceRecordPredicate sellingPricePredicate,
		@Nonnull SumPredicate<PriceRecordContract> individualPricePredicate
	) {
		this.sellingPricePredicate = sellingPricePredicate;
		this.individualPricePredicate = individualPricePredicate;
		this.priceEvaluationContext = priceEvaluationContext;
		this.queryPriceMode = queryPriceMode;
		if (queryPriceMode == QueryPriceMode.WITH_TAX) {
			this.transformer = PriceRecordContract::priceWithTax;
		} else {
			this.transformer = PriceRecordContract::priceWithoutTax;
		}
		this.initFields(containerFormula);
	}

	/**
	 * Creates a new instance of SumPriceTerminationFormula with the specified individual price predicate and
	 * retains the existing delegate formula, price evaluation context, query price mode,
	 * and selling price predicate.
	 *
	 * @param individualPricePredicate the predicate to filter individual price records; must not be null
	 * @return a new instance of SumPriceTerminationFormula with the specified individual price predicate
	 */
	@Nonnull
	public SumPriceTerminationFormula withIndividualPricePredicate(@Nonnull SumPredicate<PriceRecordContract> individualPricePredicate) {
		return new SumPriceTerminationFormula(
			getDelegate(),
			this.priceEvaluationContext,
			this.queryPriceMode,
			this.sellingPricePredicate,
			individualPricePredicate
		);
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

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		getDelegate().initialize(executionContext);
		super.initialize(executionContext);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new SumPriceTerminationFormula(
			innerFormulas[0],
			this.priceEvaluationContext, this.queryPriceMode,
			this.sellingPricePredicate, this.individualPricePredicate
		);
	}

	@Override
	public long getOperationCost() {
		return 18077;
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

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		// retrieve filtered entity ids from the delegate formula
		final PersistentRoaringBitmap computedRoaringBitmap = RoaringBitmapBackedBitmap.getRoaringBitmap(getDelegate().compute());

		// if there are any entities found
		if (!computedRoaringBitmap.isEmpty()) {
			// collect all FilteredPriceRecordAccessor that were involved in computing delegate result
			final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors = FormulaFinder.find(
				getDelegate(), FilteredPriceRecordAccessor.class, LookUp.SHALLOW
			);
			// create array for the virtual - cumulated prices
			final CompositeObjectArray<PriceRecordContract> priceRecordsFunnel = new CompositeObjectArray<>(PriceRecordContract.class, false);
			// collect price iterators ordered by price list importance
			final PriceRecordLookup[] priceRecordIterators = new PriceRecordLookup[filteredPriceRecordAccessors.size()];
			int idx = 0;
			for (FilteredPriceRecordAccessor accessor : filteredPriceRecordAccessors) {
				priceRecordIterators[idx++] = accessor.getFilteredPriceRecords(this.executionContext)
					.getPriceRecordsLookup();
			}
			// create helper associative index for looking up index of the appropriate price by entity id in the priceRecordsFunnel
			final IntObjectMap<PriceRecordContract> entityInnerRecordPrice = new IntObjectHashMap<>();
			// create new roaring bitmap builder
			final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();

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

						int cumulatedPrice = 0;
						final IntObjectMap<PriceRecordContract> includedInnerRecordIds = new IntObjectHashMap<>(entityInnerRecordPrice.size());
						final ObjectContainer<PriceRecordContract> values = entityInnerRecordPrice.values();
						for (ObjectCursor<PriceRecordContract> value : values) {
							// we need to filter the price using individual price predicate
							// this handles the situation when we want to consider only prices that relate
							// for previously selected selling price (e.g. when we calculate the discount)
							if (this.individualPricePredicate.test(value.value)) {
								includedInnerRecordIds.put(
									value.value.innerRecordId(),
									value.value
								);
								cumulatedPrice += this.transformer.applyAsInt(value.value);
							}
						}
						// now include selling prices of those components, that were not found in current price lists
						cumulatedPrice += this.individualPricePredicate.getMissingComponentsPrice(includedInnerRecordIds.keys());

						// if the cumulated price was calculated from at least one price record
						if (!includedInnerRecordIds.isEmpty()) {
							final PriceRecordContract virtualPriceRecord = new CumulatedVirtualPriceRecord(
								entityId, cumulatedPrice, this.queryPriceMode,
								includedInnerRecordIds
							);
							if (this.sellingPricePredicate.test(virtualPriceRecord)) {
								// if so - entity id continues to output of this formula
								writer.add(entityId);
								// from now on - work with the lowest entity price grouped by inner record
								priceRecordsFunnel.add(virtualPriceRecord);
							}
						}
					}
				}
			} finally {
				SharedBufferPool.INSTANCE.free(buffer);
			}

			// now produce filtered virtual (accumulated) price records
			this.filteredPriceRecords = new ResolvedFilteredPriceRecords(
				priceRecordsFunnel.toArray(),
				SortingForm.ENTITY_PK
			);

			// wrap result into the bitmap
			return new BaseBitmap(writer.get());
		} else {
			this.filteredPriceRecords = new ResolvedFilteredPriceRecords();
			return EmptyBitmap.INSTANCE;
		}
	}

	@Override
	public int getEstimatedCardinality() {
		int sum = 0;
		for (int i = 0; i < this.innerFormulas.length; i++) {
			sum += this.innerFormulas[i].getEstimatedCardinality();
		}
		return sum;
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

	/**
	 * The SumPredicate interface extends the Predicate interface and adds a method to calculate the cumulated price
	 * of entity components that were not matched by the predicate. This ensures that unmatched components are still
	 * considered in the final price.
	 *
	 * @param <T> the type of objects that the predicate will be tested against
	 */
	public interface SumPredicate<T> extends Predicate<T> {

		/**
		 * Returns cumulated price for all entity components that were not matched by the predicate, so that they wouldn't
		 * be omitted from the final price.
		 *
		 * @param includedInnerRecordIds the set of inner record ids that were included in the final price
		 * @return cumulated price for all entity components that were not matched by the predicate
		 */
		int getMissingComponentsPrice(@Nonnull IntCollection includedInnerRecordIds);

	}

}
