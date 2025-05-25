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

package io.evitadb.core.cache.payload;

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredOutPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.predicate.PriceAmountPredicate;
import io.evitadb.core.query.algebra.price.termination.PlainPriceTerminationFormulaWithPriceFilter;
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
 * {@link PriceEvaluationContext} and bitmap of entity primary keys that were filtered out by the original formula.
 * Formula is used from {@link PlainPriceTerminationFormulaWithPriceFilter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class FlattenedFormulaWithFilteredOutRecords extends FlattenedFormula implements PriceTerminationFormula, Formula {
	@Serial private static final long serialVersionUID = -1357022866282833762L;
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
	public static int estimateSize(@Nonnull long[] transactionalIds, @Nonnull Bitmap computationalResult, @Nonnull Bitmap recordsFilteredOutByPredicate, @Nonnull PriceEvaluationContext priceEvaluationContext) {
		return FlattenedFormula.estimateSize(transactionalIds, computationalResult) +
			RoaringBitmapBackedBitmap.getRoaringBitmap(recordsFilteredOutByPredicate).getSizeInBytes() +
			priceEvaluationContext.estimateSize();
	}

	public FlattenedFormulaWithFilteredOutRecords(
		long formulaHash, long transactionalIdHash,
		@Nonnull long[] originalBitmapIds,
		@Nonnull Bitmap memoizedResult,
		@Nonnull Bitmap recordsFilteredOutByPredicate,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nullable QueryPriceMode queryPriceMode,
		@Nullable BigDecimal from,
		@Nullable BigDecimal to,
		int indexedPricePlaces
	) {
		super(formulaHash, transactionalIdHash, originalBitmapIds, memoizedResult);
		this.recordsFilteredOutByPredicate = recordsFilteredOutByPredicate;
		this.priceEvaluationContext = priceEvaluationContext;
		this.queryPriceMode = queryPriceMode;
		this.from = from;
		this.to = to;
		this.indexedPricePlaces = indexedPricePlaces;
	}

	@Nullable
	@Override
	public PriceAmountPredicate getRequestedPredicate() {
		if (this.memoizedPredicate == null) {
			final int fromAsInt = this.from == null ? Integer.MIN_VALUE : NumberUtils.convertToInt(this.from, this.indexedPricePlaces);
			final int toAsInt = this.to == null ? Integer.MAX_VALUE : NumberUtils.convertToInt(this.to, this.indexedPricePlaces);
			this.memoizedPredicate = new PriceAmountPredicate(
				this.queryPriceMode, this.from, this.to, this.indexedPricePlaces,
				amount -> {
					final int amountAsInt = NumberUtils.convertToInt(amount, this.indexedPricePlaces);
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
				this.recordHash, this.transactionalIdHash, this.transactionalDataIds, this.recordsFilteredOutByPredicate,
				this.recordsFilteredOutByPredicate, this.priceEvaluationContext, this.queryPriceMode, this.from, this.to, this.indexedPricePlaces
			);
		}
		return this.memoizedClone;
	}

}
