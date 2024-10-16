/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.query.slice;


import io.evitadb.api.query.expression.evaluate.SingleVariableEvaluationContext;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.ConditionalGap;
import io.evitadb.api.requestResponse.EvitaRequest.ResultForm;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Arrays;

/**
 * ExpressionBasedSlicer is an implementation of the Slicer interface that calculates pagination offsets and limits
 * based on conditional gaps defined in the constructor.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class ExpressionBasedSlicer implements Slicer {
	/**
	 * Name of the variable keeping the examined page number that can be used in the conditional expressions.
	 */
	private static final String VAR_PAGE_NUMBER = "pageNumber";
	/**
	 * Array of conditional gaps that defines pagination offsets and limits based on specific conditions.
	 *
	 * Each ConditionalGap in the array represents a gap with a specified size and an associated expression
	 * that is evaluated to determine whether the gap should be applied during the pagination calculation.
	 */
	private final ConditionalGap[] conditionalGaps;

	@Nonnull
	@Override
	public OffsetAndLimit calculateOffsetAndLimit(@Nonnull EvitaRequest evitaRequest, int totalRecordCount) {
		Assert.isPremiseValid(
			evitaRequest.getResultForm() == ResultForm.PAGINATED_LIST,
			"Spacing can be calculated only for paginated list!"
		);
		// input parameters defined in the query
		final int pageNumber = evitaRequest.getStart();
		final int requestedPageSize = evitaRequest.getLimit();

		// determine possible page number ranges
		final int[] activeGaps = new int[this.conditionalGaps.length];
		final BigDecimalNumberRange[] pageNumberRanges = new BigDecimalNumberRange[this.conditionalGaps.length];
		for (int i = 0; i < this.conditionalGaps.length; i++) {
			pageNumberRanges[i] = this.conditionalGaps[i].expression().determinePossibleRange();
			activeGaps[i] = 1;
		}

		// moving offset for the current page
		int accumulatedOffset = 0;
		// number of records on previous page
		int previousPageSize = 0;
		// last offset taken from accumulatedOffset providing that page number is less than requested one
		int offset = 0;
		// remembered size of the page for the first page
		int firstPageSize = -1;
		// size of the page taken from previousPageSize providing that page number is less than requested one
		int pageSize = 0;
		// number of the last page
		int lastPageNumber = 1;
		do {
			// accumulated page size
			int gapSize = 0;
			boolean activeGapCountDecreased = false;
			// iterate over conditional gaps
			for (int i = 0; i < this.conditionalGaps.length; i++) {
				final ConditionalGap conditionalGap = this.conditionalGaps[i];
				final BigDecimalNumberRange pageNumberRange = pageNumberRanges[i];
				final BigDecimal pageNumberAsBigDecimal = new BigDecimal(String.valueOf(lastPageNumber));
				if (pageNumberRange.isWithin(pageNumberAsBigDecimal)) {
					// when the expression is evaluated to TRUE
					final Expression expression = conditionalGap.expression();
					if (Boolean.TRUE.equals(expression.compute(new SingleVariableEvaluationContext(VAR_PAGE_NUMBER, lastPageNumber)))) {
						// add the gap size to the accumulated gap size
						gapSize += conditionalGap.size();
					}
				} else if (activeGaps[i] == 1 && pageNumberRange.compareTo(BigDecimalNumberRange.between(pageNumberAsBigDecimal, pageNumberAsBigDecimal)) < 0) {
					// if the page number is greater than the maximum possible page number, decrease the number of active gaps
					activeGaps[i] = 0;
					activeGapCountDecreased = true;
				}
			}
			// update temporary "moving" variables
			accumulatedOffset += previousPageSize;
			previousPageSize = requestedPageSize - gapSize;
			// remember page size of the very first page
			if (firstPageSize == -1) {
				firstPageSize = pageSize;
			}
			// if the page number is less than the requested one, remember the offset and page size
			if (lastPageNumber <= pageNumber) {
				offset = accumulatedOffset;
				pageSize = previousPageSize;
			}
			//
			if (activeGapCountDecreased && Arrays.stream(activeGaps).allMatch(value -> value == 0)) {
				// if there are no active gaps, stop the loop and return the offset and limit for the last page
				offset += Math.max(0, pageNumber - lastPageNumber) * pageSize;
				lastPageNumber += (int) Math.ceil((float) (totalRecordCount - accumulatedOffset) / (float) requestedPageSize) + 1;
				break;
			} else {
				// increment the page number
				lastPageNumber++;
			}
		// stop when the accumulated offset is greater than the total record count
		} while (accumulatedOffset < totalRecordCount);

		// return the offset and limit
		return offset > totalRecordCount ?
			new OffsetAndLimit(0, firstPageSize, lastPageNumber - 2) :
			new OffsetAndLimit(offset, pageSize, lastPageNumber - 2);
	}

}
