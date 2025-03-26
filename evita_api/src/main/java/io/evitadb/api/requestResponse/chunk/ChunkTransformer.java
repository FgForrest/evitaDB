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


import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.dataType.DataChunk;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Implementations of this interface are responsible for slicing the complete list of references into smaller chunk
 * that was requested in the query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ChunkTransformer {

	/**
	 * Slices the complete list of references according to the requirements of the transformer.
	 * This method expects the complete list of references to be passed in and returns the sliced list.
	 *
	 * @param referenceContracts the complete list of references
	 * @return the sliced list of references
	 */
	@Nonnull
	DataChunk<ReferenceContract> createChunk(@Nonnull List<ReferenceContract> referenceContracts);

}
