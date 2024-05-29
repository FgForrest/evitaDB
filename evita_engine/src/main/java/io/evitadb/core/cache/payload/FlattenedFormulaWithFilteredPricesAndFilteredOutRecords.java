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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.cache.payload;

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredOutPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.predicate.PriceAmountPredicate;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.core.query.algebra.price.termination.PriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PriceWrappingFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.MemoryMeasuringConstants;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.math.BigDecimal;

/**
 * Flattened formula represents a memoized form of original formula that contains already computed bitmap of results.
 * This variant of flattened formula keeps computed bitmap of integers and also information about
 * {@link FilteredPriceRecords}, {@link PriceEvaluationContext} and bitmap of entity primary keys that were filtered
 * out by the original formula. Formula originates from formulas that filter records by price.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class FlattenedFormulaWithFilteredPricesAndFilteredOutRecords extends FlattenedFormula implements FilteredPriceRecordAccessor, PriceTerminationFormula, Formula {
	@Serial private static final long serialVersionUID = -6052882250380556441L;
	/**
	 * Contains information about price records leading to a computed result.
	 * Copies {@link FilteredPriceRecordAccessor#getFilteredPriceRecords()}.
	 */
	@Getter @Nonnull private final FilteredPriceRecords filteredPriceRecords;
	/**
	 * Records that has been filtered out by the original formula.
	 * Copies {@link FilteredOutPriceRecordAccessor#getCloneWithPricePredicateFilteredOutResults()}.
	 */
	@Getter @Nonnull private final Bitmap recordsFilteredOutByPredicate;
	/**
	 * Price evaluation context. Copies {@link PriceWrappingFormula#getPriceEvaluationContext()}.
	 */
	@Getter @Nonnull private final PriceEvaluationContext priceEvaluationContext;
	/**
	 * Filtering query price mode.
	 */
	@Getter @Nullable private final QueryPriceMode queryPriceMode;
	/**
	 * Filtering threshold from.
	 */
	@Getter @Nullable private final BigDecimal from;
	/**
	 * Filtering threshold to.
	 */
	@Getter @Nullable private final BigDecimal to;
	/**
	 * Filtering indexed price modes.
	 */
	@Getter private final int indexedPricePlaces;
	/**
	 * Memoized predicate stored upon first calculation to lower computational resources.
	 */
	private PriceAmountPredicate memoizedPredicate;
	/**
	 * Memoized clone stored upon first calculation to lower computational resources.
	 */
	private Formula memoizedClone;

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public static int estimateSize(
		@Nonnull long[] transactionalIds,
		@Nonnull Bitmap computationalResult,
		@Nonnull Bitmap recordsFilteredOutByPredicate,
		@Nonnull PriceEvaluationContext priceEvaluationContext
	) {
		return FlattenedFormula.estimateSize(transactionalIds, computationalResult) +
			RoaringBitmapBackedBitmap.getRoaringBitmap(recordsFilteredOutByPredicate).getSizeInBytes() +
			computationalResult.size() * FlattenedFormulaWithFilteredPrices.PRICE_RECORD_SIZE +
			priceEvaluationContext.estimateSize() +
			// query price mode
			MemoryMeasuringConstants.INT_SIZE +
			// from and to
		    2 * MemoryMeasuringConstants.BIG_DECIMAL_SIZE +
			// indexed price places
			MemoryMeasuringConstants.INT_SIZE;
	}

	public FlattenedFormulaWithFilteredPricesAndFilteredOutRecords(
		long formulaHash,
		long transactionalIdHash,
		@Nonnull long[] originalBitmapIds,
		@Nonnull Bitmap memoizedResult,
		@Nonnull FilteredPriceRecords filteredPriceRecords,
		@Nonnull Bitmap recordsFilteredOutByPredicate,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nullable QueryPriceMode queryPriceMode,
		@Nullable BigDecimal from,
		@Nullable BigDecimal to,
		int indexedPricePlaces
	) {
		super(formulaHash, transactionalIdHash, originalBitmapIds, memoizedResult);
		this.filteredPriceRecords = filteredPriceRecords;
		this.recordsFilteredOutByPredicate = recordsFilteredOutByPredicate;
		this.priceEvaluationContext = priceEvaluationContext;
		this.filteredPriceRecords.prepareForFlattening();
		this.queryPriceMode = queryPriceMode;
		this.from = from;
		this.to = to;
		this.indexedPricePlaces = indexedPricePlaces;
	}

	@Nullable
	@Override
	public PriceAmountPredicate getRequestedPredicate() {
		if (this.memoizedPredicate == null) {
			final int fromAsInt = from == null ? Integer.MIN_VALUE : NumberUtils.convertToInt(from, indexedPricePlaces);
			final int toAsInt = to == null ? Integer.MAX_VALUE : NumberUtils.convertToInt(to, indexedPricePlaces);
			this.memoizedPredicate = new PriceAmountPredicate(
				queryPriceMode, from, to, indexedPricePlaces,
				amount -> {
					final int amountAsInt = NumberUtils.convertToInt(amount, indexedPricePlaces);
					return amountAsInt >= fromAsInt && amountAsInt <= toAsInt;
				}
			);
		}
		return this.memoizedPredicate;
	}

	@Nonnull
	@Override
	public Formula getCloneWithPricePredicateFilteredOutResults() {
		if (this.memoizedClone == null) {
			this.memoizedClone = new FlattenedFormulaWithFilteredOutRecords(
				recordHash, transactionalIdHash, transactionalDataIds, recordsFilteredOutByPredicate,
				recordsFilteredOutByPredicate, priceEvaluationContext, queryPriceMode, from, to, indexedPricePlaces
			);
		}
		return this.memoizedClone;
	}

}
