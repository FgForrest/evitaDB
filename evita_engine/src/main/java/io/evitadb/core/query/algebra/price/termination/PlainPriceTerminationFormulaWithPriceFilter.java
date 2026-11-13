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

import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
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
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;

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
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} as the identity discriminator.
	 */
	private static final long CLASS_ID = -2971377943765598548L;

	/**
	 * Price evaluation context allows optimizing formula tree in the such way, that terminating formula with same
	 * context will be replaced by single instance - taking advantage of result memoization.
	 */
	private final PriceEvaluationContext priceEvaluationContext;
	/**
	 * Price filter is used to filter out entities which price doesn't match the predicate.
	 */
	@Getter private final PriceRecordPredicate pricePredicate;
	/**
	 * Contains array of price records that links to the price ids produced by {@link Formula#compute()} method. This array
	 * is available once the {@link Formula#compute()} method has been called.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private ResolvedFilteredPriceRecords filteredPriceRecords;

	public PlainPriceTerminationFormulaWithPriceFilter(
		@Nonnull Formula containerFormula,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nonnull PriceRecordPredicate pricePredicate
	) {
		this.priceEvaluationContext = priceEvaluationContext;
		this.pricePredicate = pricePredicate;
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
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		getDelegate().initialize(executionContext);
		super.initialize(executionContext);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new PlainPriceTerminationFormulaWithPriceFilter(
			innerFormulas[0], this.priceEvaluationContext, this.pricePredicate
		);
	}

	@Override
	public long getOperationCost() {
		return 3205;
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
			// collect price iterators ordered by price list importance
			final PriceRecordLookup[] priceRecordIterators = new PriceRecordLookup[filteredPriceRecordAccessors.size()];
			int idx = 0;
			for (FilteredPriceRecordAccessor accessor : filteredPriceRecordAccessors) {
				priceRecordIterators[idx++] = accessor.getFilteredPriceRecords(this.executionContext)
					.getPriceRecordsLookup();
			}

			// create array for the lowest prices by entity
			final CompositeObjectArray<PriceRecordContract> priceRecordsFunnel = new CompositeObjectArray<>(PriceRecordContract.class, false);

			// create new roaring bitmap builder for matching records
			final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
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
	protected long getClassId() {
		return CLASS_ID;
	}

	/** Returns detached computation inputs, with formula operands carried by the ordered child keys. */
	@Nonnull
	@Override
	protected IdentityRecord identity() {
		return new Identity(this.priceEvaluationContext,
			this.pricePredicate.identity());
	}

	/**
	 * Detached computation inputs.
	 *
	 * @param priceEvaluationContext validity and ordered target price indexes
	 * @param pricePredicate detached price range predicate
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nonnull PricePredicate.Identity pricePredicate) implements IdentityRecord {
		/** Returns the declaring holder identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every component in declaration order. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.priceEvaluationContext);
			accumulator.add(this.pricePredicate);
		}
	}

}
