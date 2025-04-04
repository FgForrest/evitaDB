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


import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.query.require.Strip;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.StripList;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Contains transformation function that wraps list of references into {@link StripList} implementation as
 * requested by {@link Strip} requirement constraint in particular {@link ReferenceContent}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class StripTransformer implements ChunkTransformer {
	@Getter private final Strip strip;

	@Nonnull
	@Override
	public DataChunk<ReferenceContract> createChunk(@Nonnull List<ReferenceContract> referenceContracts) {
		return new StripList<>(
			this.strip.getOffset(),
			this.strip.getLimit(),
			referenceContracts.size(),
			referenceContracts.isEmpty() ?
				referenceContracts :
				referenceContracts.subList(
					Math.min(this.strip.getOffset(), referenceContracts.size() - 1),
					Math.min(this.strip.getOffset() + this.strip.getLimit(), referenceContracts.size())
				)
		);
	}

}
