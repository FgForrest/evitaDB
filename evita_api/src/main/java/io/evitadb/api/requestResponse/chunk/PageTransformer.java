/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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


import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaRequest.ConditionalGap;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Contains transformation function that wraps list of references into {@link PaginatedList} implementation as
 * requested by {@link Page} requirement constraint in particular {@link ReferenceContent}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class PageTransformer implements ChunkTransformer {
	@Getter private final Page page;
	@Getter private final ConditionalGap[] gaps;

	public PageTransformer(@Nonnull Page page, @Nonnull ConditionalGap[] gaps) {
		this.page = page;
		this.gaps = gaps;
	}

	@Nonnull
	@Override
	public DataChunk<ReferenceContract> createChunk(@Nonnull List<ReferenceContract> referenceContracts) {
		final int pageNumber = this.page.getPageNumber();
		final int pageSize = this.page.getPageSize();
		final int realPageNumber = PaginatedList.isRequestedResultBehindLimit(
			pageNumber,
			pageSize,
			referenceContracts.size()
		) ? 1 : pageNumber;
		return getReferenceContractDataChunk(
			referenceContracts,
			realPageNumber,
			pageSize,
			PaginatedList.getLastPageNumber(pageSize, referenceContracts.size()),
			PaginatedList.getFirstItemNumberForPage(realPageNumber, pageSize),
			pageSize,
			referenceContracts.size()
		);
	}

	/**
	 * Produces a paginated data chunk containing references from a given list of {@code ReferenceContract}.
	 * The method paginates the data based on the provided page number, total pages, offset, and limit.
	 *
	 * @param referenceContracts the list of {@code ReferenceContract} objects to be processed into a paginated data chunk
	 * @param pageNumber the current page number within the pagination context
	 * @param lastPageNumber the total number of pages calculated for the entire data
	 * @param offset the starting index from which to begin creating the data chunk
	 * @param limit the maximum number of items to include in the data chunk
	 * @return a {@code DataChunk} containing a subset of {@code ReferenceContract} items based on the pagination parameters
	 */
	@Nonnull
	public static DataChunk<ReferenceContract> getReferenceContractDataChunk(
		@Nonnull List<ReferenceContract> referenceContracts,
		int pageNumber,
		int pageSize,
		int lastPageNumber,
		int offset,
		int limit,
		int totalRecordCount
	) {
		return new PaginatedList<>(
			pageNumber,
			lastPageNumber,
			pageSize,
			totalRecordCount,
			referenceContracts.isEmpty() ?
				referenceContracts :
				referenceContracts.subList(
					offset,
					Math.min(offset + limit, referenceContracts.size())
				)
		);
	}

}
