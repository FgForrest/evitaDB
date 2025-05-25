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
import io.evitadb.exception.GenericEvitaInternalError;
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
import java.util.Objects;
import java.util.function.Consumer;

/**
 * PlainPriceTerminationFormulaWithPriceFilter translates price ids produced by delegate formula to entity ids. It may
 * also filter out entity ids which don't pass {@link #pricePredicate} predicate test.
 *
 * This formula consumes and produces {@link Formula} of reduced {@link PriceRecord#entityPrimaryKey() entity ids}
 * which price passes the {@link #pricePredicate} predicate. It uses  information from underlying formulas that implement
 * {@link FilteredPriceRecordAccessor#getFilteredPriceRecords(QueryExecutionContext)}  to access the appropriate price for filtering purposes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PlainPriceTerminationFormulaWithPriceFilter extends AbstractCacheableFormula implements FilteredPriceRecordAccessor, PriceTerminationFormula {
	private static final long CLASS_ID = -2971377943765598548L;

	/**
	 * Price evaluation context allows optimizing formula tree in the such way, that terminating formula with same
	 * context will be replaced by single instance - taking advantage of result memoization.
	 */
	@Getter private final PriceEvaluationContext priceEvaluationContext;
	/**
	 * Price filter is used to filter out entities which price doesn't match the predicate.
	 */
	@Getter private final PriceRecordPredicate pricePredicate;
	/**
	 * Contains array of price records that links to the price ids produced by {@link Formula#compute()} method. This array
	 * is available once the {@link Formula#compute()} method has been called.
	 */
	private ResolvedFilteredPriceRecords filteredPriceRecords;
	/**
	 * Bitmap is initialized (non-null) after {@link Formula#compute()} method is called and contains set of entity primary
	 * keys that were excluded due to {@link #pricePredicate} query. This information is reused in
	 * {@link PriceHistogramProducer} to avoid duplicate computation - price histogram must not take price predicate
	 * into an account.
	 */
	@Getter private Bitmap recordsFilteredOutByPredicate;

	public PlainPriceTerminationFormulaWithPriceFilter(
		@Nonnull Formula containerFormula,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nonnull PriceRecordPredicate pricePredicate
	) {
		super(null);
		this.priceEvaluationContext = priceEvaluationContext;
		this.pricePredicate = pricePredicate;
		this.initFields(containerFormula);
	}

	private PlainPriceTerminationFormulaWithPriceFilter(
		@Nullable Consumer<CacheableFormula> computationCallback,
		@Nonnull Formula containerFormula,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nonnull PriceRecordPredicate pricePredicate
	) {
		super(computationCallback);
		this.pricePredicate = pricePredicate;
		this.priceEvaluationContext = priceEvaluationContext;
		this.initFields(containerFormula);
	}

	private PlainPriceTerminationFormulaWithPriceFilter(
		@Nullable Consumer<CacheableFormula> computationCallback,
		@Nonnull Formula containerFormula,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nonnull PriceRecordPredicate pricePredicate,
		@Nonnull Bitmap recordsFilteredOutByPredicate
	) {
		super(recordsFilteredOutByPredicate, computationCallback);
		this.pricePredicate = pricePredicate;
		this.priceEvaluationContext = priceEvaluationContext;
		this.recordsFilteredOutByPredicate = recordsFilteredOutByPredicate;
		this.initFields(containerFormula);
	}

	@Nullable
	@Override
	public PriceAmountPredicate getRequestedPredicate() {
		return this.pricePredicate.getRequestedPredicate();
	}

	/**
	 * Returns delegate formula of this container.
	 */
	@Nonnull
	public Formula getDelegate() {
		return this.innerFormulas[0];
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContextt) {
		getDelegate().initialize(this.executionContext);
		super.initialize(this.executionContext);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new PlainPriceTerminationFormulaWithPriceFilter(
			this.computationCallback, innerFormulas[0], this.priceEvaluationContext, this.pricePredicate
		);
	}

	@Override
	public long getOperationCost() {
		return 3205;
	}

	@Nonnull
	@Override
	public Formula getCloneWithPricePredicateFilteredOutResults() {
		return new PlainPriceTerminationFormulaWithPriceFilter(
			this.computationCallback,
			this.innerFormulas[0],
			this.priceEvaluationContext,
			PricePredicate.ALL_RECORD_FILTER,
			this.recordsFilteredOutByPredicate
		);
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(@Nonnull Consumer<CacheableFormula> selfOperator, @Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new PlainPriceTerminationFormulaWithPriceFilter(
			selfOperator,
			innerFormulas[0],
			this.priceEvaluationContext, this.pricePredicate
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
		return this.pricePredicate.toString();
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
			this.pricePredicate.getQueryPriceMode(),
			this.pricePredicate.getFrom(),
			this.pricePredicate.getTo(),
			this.pricePredicate.getIndexedPricePlaces()
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

			// create new roaring bitmap builder for matching records
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			// create new roaring bitmap builder for records excluded by predicate
			final RoaringBitmapWriter<RoaringBitmap> predicateExcludedWriter = RoaringBitmapBackedBitmap.buildWriter();
			final int[] buffer = SharedBufferPool.INSTANCE.obtain();
			try {
				final BatchArrayIterator entityIdIterator = new RoaringBitmapBatchArrayIterator(
					computedRoaringBitmap.getBatchIterator(), buffer
				);
				// iterate through all entity ids
				while (entityIdIterator.hasNext()) {
					final int[] batch = entityIdIterator.nextBatch();
					final int lastExpectedEntity = entityIdIterator.getPeek() > 0 ? batch[entityIdIterator.getPeek() - 1] : -1;
					for (int i = 0; i < entityIdIterator.getPeek(); i++) {
						final int entityId = batch[i];

						boolean noPriceFoundAtAll = true;
						for (PriceRecordLookup priceRecordIt : priceRecordIterators) {
							final boolean anyPriceFound = priceRecordIt.forEachPriceOfEntity(
								entityId,
								lastExpectedEntity,
								foundPrice -> {
									// write entity primary key for the price located on found index if it passes predicate
									if (this.pricePredicate.test(foundPrice)) {
										writer.add(entityId);
										priceRecordsFunnel.add(foundPrice);
									} else {
										predicateExcludedWriter.add(entityId);
									}
								}
							);
							if (anyPriceFound) {
								noPriceFoundAtAll = false;
								break;
							}
						}

						if (noPriceFoundAtAll) {
							throw new GenericEvitaInternalError("No price found for entity with id " + entityId + "!");
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
				this.pricePredicate.computeHash(hashFunction)
			}
		);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

}
