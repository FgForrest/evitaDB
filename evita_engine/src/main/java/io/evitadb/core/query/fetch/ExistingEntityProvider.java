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


import io.evitadb.api.requestResponse.data.SealedEntity;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Interface allows accessing the already fetched bodies of entities in existing data structure. It allows
 * accessing existing entities in {@link SealedEntity} in case the reference entity loader is used for enrichment
 * only.
 */
interface ExistingEntityProvider {

	/**
	 * Return parent entity from the existing {@link SealedEntity} or empty result.
	 */
	@Nonnull
	Optional<SealedEntity> getExistingParentEntity(int primaryKey);

	/**
	 * Return entity from the existing {@link SealedEntity} or empty result.
	 */
	@Nonnull
	Optional<SealedEntity> getExistingEntity(@Nonnull String referenceName, int primaryKey);

	/**
	 * Return entity group from the existing {@link SealedEntity} or empty result.
	 */
	@Nonnull
	Optional<SealedEntity> getExistingGroupEntity(@Nonnull String referenceName, int primaryKey);

}
