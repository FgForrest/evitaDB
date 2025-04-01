/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.sort.generic;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.sort.ConditionalSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.utils.SortUtils;
import io.evitadb.index.bitmap.BaseBitmap;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.IntConsumer;

/**
 * Ancestor for sharing the same logic among multiple {@link Sorter} implementations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public abstract class AbstractRecordsSorter implements Sorter {

	/**
	 * Completes the result by cutting the result smaller than requested count but appending all not found record ids
	 * before the cut.
	 */
	protected static int returnResultAppendingUnknown(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull RoaringBitmap notFoundRecords,
		@Nullable Sorter unknownRecordIdsSorter,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak,
		int skipped,
		@Nonnull int[] buffer,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		unknownRecordIdsSorter = ConditionalSorter.getFirstApplicableSorter(queryContext, unknownRecordIdsSorter);
		final int finalResultPeak;
		if (peak < result.length && !notFoundRecords.isEmpty()) {
			if (unknownRecordIdsSorter == null) {
				finalResultPeak = SortUtils.appendNotFoundResult(
					result, peak,
					Math.max(0, startIndex - peak - skipped),
					Math.max(0, endIndex - peak - skipped),
					notFoundRecords, buffer, skippedRecordsConsumer
				);
			} else {
				finalResultPeak = unknownRecordIdsSorter.sortAndSlice(
					queryContext, new ConstantFormula(new BaseBitmap(notFoundRecords)),
					startIndex, endIndex, result, peak, skipped, skippedRecordsConsumer
				);
			}
		} else {
			finalResultPeak = peak;
		}
		return finalResultPeak;
	}

}
