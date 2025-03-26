/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.api.requestResponse.chunk;


import io.evitadb.api.requestResponse.EvitaRequest.ResultForm;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.GenericEvitaInternalError;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * DefaultSlicer is a concrete implementation of the Slicer interface.
 * It calculates the offset and limit for paginating data based on the given EvitaRequest.
 * The class supports result forms of PAGINATED_LIST and STRIP_LIST.
 * For unsupported result forms, it throws a GenericEvitaInternalError.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DefaultSlicer implements Slicer {

	public static final Slicer INSTANCE = new DefaultSlicer();

	@Nonnull
	@Override
	public OffsetAndLimit calculateOffsetAndLimit(@Nonnull ResultForm resultForm, int offset, int limit, int totalRecordCount) {
		switch (resultForm) {
			case PAGINATED_LIST -> {
				final int calculatedOffset = PaginatedList.getFirstItemNumberForPage(offset, limit);
				return new OffsetAndLimit(
					Math.max(0, calculatedOffset < totalRecordCount ? calculatedOffset : 0),
					limit,
					calculatedOffset < totalRecordCount ? offset : 1,
					PaginatedList.getLastPageNumber(limit, totalRecordCount),
					totalRecordCount
				);
			}
			case STRIP_LIST -> {
				return new OffsetAndLimit(offset, limit, totalRecordCount);
			}
			default -> throw new GenericEvitaInternalError(
				"Unsupported result form: " + resultForm
			);
		}
	}

}
