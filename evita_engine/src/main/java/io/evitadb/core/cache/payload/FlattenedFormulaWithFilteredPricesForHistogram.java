/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.math.BigDecimal;

/**
 * Cache payload sibling of {@link FlattenedFormulaWithFilteredPricesAndFilteredOutRecords} that additionally
 * carries the per-inner-record winning price records consumed by the price histogram for
 * {@link PriceInnerRecordHandling#LOWEST_PRICE} entities.
 *
 * Why a sibling class rather than extending the parent payload:
 *
 * - The existing class' serialization signature must not change — cached entries produced by
 *   `dev` and earlier releases would otherwise become unreadable.
 * - Non-histogram queries against `LOWEST_PRICE` entities must continue to flatten into the
 *   pre-existing sibling so they pay no extra memory cost. The selection is driven by the
 *   planner-set `collectPerInnerRecordPrices` flag on
 *   `io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula`.
 *
 * The class is on a hot deserialization path; only the minimum fields needed for the histogram
 * are added. {@link #estimateSize} accepts the per-inner-record array length directly from the
 * caller — no iteration, no allocations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FlattenedFormulaWithFilteredPricesForHistogram
	extends FlattenedFormulaWithFilteredPricesAndFilteredOutRecords {

	/**
	 * Serial version UID for serialization compatibility. Distinct from the parent payload's UID so the
	 * deserializer never mistakes a histogram-aware cache entry for a plain
	 * {@link FlattenedFormulaWithFilteredPricesAndFilteredOutRecords} record (which would silently miss the
	 * per-inner-record side-output).
	 */
	@Serial private static final long serialVersionUID = 8429561245103938421L;

	/**
	 * Per-inner-record winning price records exposed via
	 * {@link #getFilteredPriceRecordsForHistogram(QueryExecutionContext)}. Sibling of
	 * {@link FlattenedFormulaWithFilteredPricesAndFilteredOutRecords#getFilteredPriceRecords(QueryExecutionContext)}
	 * which remains the per-entity view.
	 */
	@Nonnull private final FilteredPriceRecords perInnerRecordPriceRecords;

	/**
	 * Method returns gross estimation of the in-memory size of this instance. Extends the parent estimate
	 * by the per-inner-record price record contribution; `perInnerRecordPriceCount` must be supplied by
	 * the caller (typically the length of the already-built funnel array on
	 * `io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula`) so this method
	 * remains allocation-free.
	 *
	 * The delta over the parent estimate is `perInnerRecordPriceCount * PRICE_RECORD_SIZE` plus a single
	 * {@link MemoryMeasuringConstants#REFERENCE_SIZE} accounting for the additional
	 * `perInnerRecordPriceRecords` field slot on this sibling payload.
	 *
	 * @param transactionalIds              identifiers of transactional sources contributing to this payload
	 * @param computationalResult           the bitmap of entity primary keys produced by the cached computation
	 * @param perInnerRecordPriceCount      length of the per-inner-record funnel array (used directly, no iteration)
	 * @param recordsFilteredOutByPredicate the bitmap of records excluded by the selling price predicate
	 * @param priceEvaluationContext        price evaluation context capturing the cached formula identity
	 * @return gross in-memory size estimate (parent contribution + per-inner-record records + one reference slot)
	 */
	public static int estimateSize(
		@Nonnull long[] transactionalIds,
		@Nonnull Bitmap computationalResult,
		int perInnerRecordPriceCount,
		@Nonnull Bitmap recordsFilteredOutByPredicate,
		@Nonnull PriceEvaluationContext priceEvaluationContext
	) {
		return FlattenedFormulaWithFilteredPricesAndFilteredOutRecords.estimateSize(
			transactionalIds, computationalResult, recordsFilteredOutByPredicate, priceEvaluationContext
		) + perInnerRecordPriceCount * FlattenedFormulaWithFilteredPrices.PRICE_RECORD_SIZE
			+ MemoryMeasuringConstants.REFERENCE_SIZE;
	}

	/**
	 * Constructs the histogram-aware flattened formula payload. All parent-class fields are forwarded to
	 * {@link FlattenedFormulaWithFilteredPricesAndFilteredOutRecords}; the additional
	 * `perInnerRecordPriceRecords` is stored locally and immediately prepared for serialization via
	 * {@link FilteredPriceRecords#prepareForFlattening()} so the cache writer can snapshot it without a
	 * separate preparation step.
	 *
	 * @param formulaHash                   stable identity hash of the originating formula
	 * @param transactionalIdHash           hash mixing the catalog-state transactional ids
	 * @param originalBitmapIds             distinct sorted transactional ids contributing to this payload
	 * @param memoizedResult                the bitmap of entity primary keys produced by the cached computation
	 * @param filteredPriceRecords          per-entity winning price records exposed via the parent accessor
	 * @param perInnerRecordPriceRecords    per-inner-record winning price records exposed via the histogram accessor
	 * @param recordsFilteredOutByPredicate the bitmap of records excluded by the selling price predicate
	 * @param priceEvaluationContext        price evaluation context capturing the cached formula identity
	 * @param queryPriceMode                query price mode (WITH/WITHOUT tax) of the originating query
	 * @param from                          lower bound of the originating price-between predicate (nullable)
	 * @param to                            upper bound of the originating price-between predicate (nullable)
	 * @param indexedPricePlaces            number of decimal places used by the entity collection for price indexing
	 */
	public FlattenedFormulaWithFilteredPricesForHistogram(
		long formulaHash,
		long transactionalIdHash,
		@Nonnull long[] originalBitmapIds,
		@Nonnull Bitmap memoizedResult,
		@Nonnull FilteredPriceRecords filteredPriceRecords,
		@Nonnull FilteredPriceRecords perInnerRecordPriceRecords,
		@Nonnull Bitmap recordsFilteredOutByPredicate,
		@Nonnull PriceEvaluationContext priceEvaluationContext,
		@Nullable QueryPriceMode queryPriceMode,
		@Nullable BigDecimal from,
		@Nullable BigDecimal to,
		int indexedPricePlaces
	) {
		super(
			formulaHash, transactionalIdHash, originalBitmapIds, memoizedResult, filteredPriceRecords,
			recordsFilteredOutByPredicate, priceEvaluationContext, queryPriceMode, from, to, indexedPricePlaces
		);
		this.perInnerRecordPriceRecords = perInnerRecordPriceRecords;
		this.perInnerRecordPriceRecords.prepareForFlattening();
	}

	/**
	 * Returns the per-inner-record winning price records that were serialized alongside this flattened payload.
	 * The result is already prepared (sorted / indexed) for the histogram computer — no computation occurs.
	 */
	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecordsForHistogram(@Nonnull QueryExecutionContext context) {
		return this.perInnerRecordPriceRecords;
	}

	/**
	 * Context-free accessor used by the cache serializer to snapshot the per-inner-record records into the
	 * binary cache format. The serializer has no
	 * {@link io.evitadb.core.query.QueryExecutionContext} to hand to
	 * {@link #getFilteredPriceRecordsForHistogram(QueryExecutionContext)}; routing through that nonnull
	 * parameter would force passing a sentinel `null`, breaking the contract on the parent accessor.
	 *
	 * @return the per-inner-record price records held by this payload
	 */
	@Nonnull
	public FilteredPriceRecords getPerInnerRecordPriceRecords() {
		return this.perInnerRecordPriceRecords;
	}

	@Override
	public boolean exposesPerInnerRecordHistogramRecords() {
		return true;
	}

}
