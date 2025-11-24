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

package io.evitadb.core.query.fetch;


import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.core.query.response.ServerEntityDecorator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * The carrier DTO for carrying all prefetched entities and groups for specific reference.
 *
 * @param entityIndex      prefetched entity bodies indexed by {@link EntityContract#getPrimaryKey()}
 * @param validityMapping  see detailed description in {@link ValidEntityToReferenceMapping}
 * @param entityGroupIndex prefetched entity group bodies indexed by {@link EntityContract#getPrimaryKey()}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
record PrefetchedEntities(
	@Nonnull Map<Integer, ServerEntityDecorator> entityIndex,
	@Nullable ValidEntityToReferenceMapping validityMapping,
	@Nonnull Map<Integer, ServerEntityDecorator> entityGroupIndex,
	@Nullable ReferenceComparator referenceComparator
) {

	/**
	 * Looks up the prefetched body by primary key in the index.
	 */
	@Nullable
	public SealedEntity getEntity(int entityPrimaryKey) {
		return this.entityIndex.get(entityPrimaryKey);
	}

	/**
	 * Looks up the prefetched body by primary key in the index.
	 */
	@Nullable
	public SealedEntity getGroupEntity(int entityPrimaryKey) {
		return this.entityGroupIndex.get(entityPrimaryKey);
	}

}
