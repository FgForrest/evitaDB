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

package io.evitadb.core.cache.payload;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.core.query.algebra.price.termination.PriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PriceWrappingFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

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
	 * Records that has been filtered out by the original formula. Copies {@link PriceTerminationFormula#getRecordsFilteredOutByPredicate()}.
	 */
	@Getter @Nonnull private final Bitmap recordsFilteredOutByPredicate;
	/**
	 * Price evaluation context. Copies {@link PriceWrappingFormula#getPriceEvaluationContext()}.
	 */
	@Getter @Nonnull private final PriceEvaluationContext priceEvaluationContext;

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public static int estimateSize(@Nonnull long[] transactionalIds, @Nonnull Bitmap computationalResult, @Nonnull Bitmap recordsFilteredOutByPredicate, @Nonnull PriceEvaluationContext priceEvaluationContext) {
		return FlattenedFormula.estimateSize(transactionalIds, computationalResult) +
			RoaringBitmapBackedBitmap.getRoaringBitmap(recordsFilteredOutByPredicate).getSizeInBytes() +
			computationalResult.size() * FlattenedFormulaWithFilteredPrices.PRICE_RECORD_SIZE +
			priceEvaluationContext.estimateSize();
	}

	public FlattenedFormulaWithFilteredPricesAndFilteredOutRecords(long formulaHash, long transactionalIdHash, @Nonnull long[] originalBitmapIds, @Nonnull Bitmap memoizedResult, @Nonnull FilteredPriceRecords filteredPriceRecords, @Nonnull Bitmap recordsFilteredOutByPredicate, @Nonnull PriceEvaluationContext priceEvaluationContext) {
		super(formulaHash, transactionalIdHash, originalBitmapIds, memoizedResult);
		this.filteredPriceRecords = filteredPriceRecords;
		this.recordsFilteredOutByPredicate = recordsFilteredOutByPredicate;
		this.priceEvaluationContext = priceEvaluationContext;
		this.filteredPriceRecords.prepareForFlattening();
	}

	@Nonnull
	@Override
	public Formula getCloneWithPricePredicateFilteredOutResults() {
		return new FlattenedFormulaWithFilteredPricesAndFilteredOutRecords(
			recordHash, transactionalIdHash, transactionalDataIds, recordsFilteredOutByPredicate,
			filteredPriceRecords, recordsFilteredOutByPredicate, priceEvaluationContext
		);
	}

}
