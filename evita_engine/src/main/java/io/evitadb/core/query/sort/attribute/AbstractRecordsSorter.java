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

package io.evitadb.core.query.sort.attribute;

import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.sort.ConditionalSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.utils.SortUtils;
import io.evitadb.index.bitmap.BaseBitmap;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Ancestor for sharing the same logic among multiple {@link Sorter} implementations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
abstract class AbstractRecordsSorter implements Sorter {

	/**
	 * Completes the result by cutting the result smaller than requested count but appending all not found record ids
	 * before the cut.
	 */
	protected static int returnResultAppendingUnknown(
		@Nonnull QueryContext queryContext,
		@Nonnull RoaringBitmap notFoundRecords,
		@Nullable Sorter unknownRecordIdsSorter,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak,
		@Nonnull int[] buffer
	) {
		unknownRecordIdsSorter = ConditionalSorter.getFirstApplicableSorter(unknownRecordIdsSorter, queryContext);
		final int finalResultPeak;
		if (peak < result.length && !notFoundRecords.isEmpty()) {
			final int recomputedStartIndex = Math.max(0, startIndex - peak);
			final int recomputedEndIndex = Math.max(0, endIndex - peak);
			if (unknownRecordIdsSorter == null) {
				finalResultPeak = SortUtils.appendNotFoundResult(
					result, peak, recomputedStartIndex, recomputedEndIndex, notFoundRecords, buffer
				);
			} else {
				finalResultPeak = unknownRecordIdsSorter.sortAndSlice(
					queryContext, new ConstantFormula(new BaseBitmap(notFoundRecords)),
					recomputedStartIndex, recomputedEndIndex, result, peak
				);
			}
		} else {
			finalResultPeak = peak;
		}
		return finalResultPeak;
	}

}
