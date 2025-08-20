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


import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.chunk.ChunkTransformer;
import io.evitadb.api.requestResponse.chunk.PageTransformer;
import io.evitadb.api.requestResponse.data.structure.Entity.ChunkTransformerAccessor;
import io.evitadb.utils.ArrayUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * This implementation of {@link ChunkTransformerAccessor} is used on server side and it is capable of transforming
 * {@link PageTransformer} into {@link PageTransformerWithSlicer} if the page transformer contains gaps.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class ServerChunkTransformerAccessor implements ChunkTransformerAccessor {
	private final EvitaRequest evitaRequest;

	@Nonnull
	@Override
	public ChunkTransformer apply(@Nonnull String referenceName) {
		final ChunkTransformer transformer = this.evitaRequest.getReferenceChunkTransformer(referenceName);
		return convertIfNecessary(transformer);
	}

	/**
	 * Converts a given {@link ChunkTransformer} into a specialized {@link PageTransformerWithSlicer} if it is an instance
	 * of {@link PageTransformer} and contains non-empty gaps. Otherwise, returns the provided transformer as is.
	 *
	 * @param transformer the {@link ChunkTransformer} instance to be evaluated and potentially transformed
	 * @return a {@link PageTransformerWithSlicer} if the provided transformer is a {@link PageTransformer} with gaps,
	 *         or the original {@link ChunkTransformer} if no transformation is performed
	 */
	@Nonnull
	public static ChunkTransformer convertIfNecessary(@Nonnull ChunkTransformer transformer) {
		if (transformer instanceof PageTransformer pageTransformer && !ArrayUtils.isEmpty(pageTransformer.getGaps())) {
			return new PageTransformerWithSlicer(pageTransformer.getPage(), new ExpressionBasedSlicer(pageTransformer.getGaps()));
		} else {
			return transformer;
		}
	}

}
