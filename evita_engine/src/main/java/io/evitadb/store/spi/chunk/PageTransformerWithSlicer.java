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

package io.evitadb.store.spi.chunk;


import io.evitadb.api.query.require.Page;
import io.evitadb.api.requestResponse.EvitaRequest.ResultForm;
import io.evitadb.api.requestResponse.chunk.ChunkTransformer;
import io.evitadb.api.requestResponse.chunk.OffsetAndLimit;
import io.evitadb.api.requestResponse.chunk.PageTransformer;
import io.evitadb.api.requestResponse.chunk.Slicer;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.dataType.DataChunk;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * This is extension of {@link PageTransformer} that uses {@link Slicer} to calculate offset and limit for the
 * result set.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class PageTransformerWithSlicer implements ChunkTransformer {
	@Getter private final Page page;
	@Getter private final Slicer slicer;

	public PageTransformerWithSlicer(@Nonnull Page page, @Nonnull Slicer slicer) {
		this.page = page;
		this.slicer = slicer;
	}

	@Nonnull
	@Override
	public DataChunk<ReferenceContract> createChunk(@Nonnull List<ReferenceContract> referenceContracts) {
		final OffsetAndLimit offsetAndLimit = this.slicer.calculateOffsetAndLimit(
			ResultForm.PAGINATED_LIST, this.page.getPageNumber(), this.page.getPageSize(), referenceContracts.size()
		);
		return PageTransformer.getReferenceContractDataChunk(
			referenceContracts,
			offsetAndLimit.pageNumber(),
			this.page.getPageSize(),
			offsetAndLimit.lastPageNumber(),
			offsetAndLimit.offset(),
			offsetAndLimit.limit(),
			referenceContracts.size()
		);
	}

}
