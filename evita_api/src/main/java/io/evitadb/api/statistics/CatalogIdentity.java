/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.statistics;

import io.evitadb.api.CatalogState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@link CatalogStatisticsComponent#IDENTITY} component - who this catalog is and what mode it is running in.
 *
 * This component is **always delivered**, whether or not the client asked for it: no other component of
 * {@link CatalogStatistics} can be interpreted without knowing which catalog produced it and whether that catalog is
 * usable at all.
 *
 * **Reading for a degraded catalog**
 *
 * When `unusable` is true the catalog could not be loaded. `catalogId` and `catalogState` may be null, `catalogVersion`
 * and `entityCollectionCount` read `-1`, and `transactional` / `goingLive` read false. `catalogName` and `readOnly`
 * remain valid - they are known without loading anything.
 *
 * @param catalogId             unique identifier of the catalog; null when the catalog is unusable and its id could
 *                              not be determined
 * @param catalogName           name the catalog is addressed by; always present, even for a corrupted catalog
 * @param catalogState          current lifecycle state; null when the state could not be determined
 * @param catalogVersion        current version of the catalog, incremented on every commit; `-1` when unusable
 * @param readOnly              true when the catalog rejects mutations
 * @param unusable              true when the catalog is corrupted and could not be loaded
 * @param transactional         true when writes go through the transactional pipeline; false in `WARMING_UP`, where
 *                              writes are applied in bulk with no transactional guarantees
 * @param goingLive             true while the catalog is transitioning out of `WARMING_UP` into `ALIVE`
 * @param entityCollectionCount number of entity collections in the catalog; `-1` when unusable. Reported explicitly
 *                              rather than derived from the collection list, so it survives the corrupted case where
 *                              that list is empty
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CatalogIdentity(
	@Nullable UUID catalogId,
	@Nonnull String catalogName,
	@Nullable CatalogState catalogState,
	long catalogVersion,
	boolean readOnly,
	boolean unusable,
	boolean transactional,
	boolean goingLive,
	int entityCollectionCount
) {

	/**
	 * Returns the catalog identifier when it could be determined.
	 *
	 * @return the catalog id, empty for a corrupted catalog whose id is unknown
	 */
	@Nonnull
	public Optional<UUID> catalogIdIfKnown() {
		return Optional.ofNullable(this.catalogId);
	}

	/**
	 * Returns the catalog lifecycle state when it could be determined.
	 *
	 * @return the catalog state, empty when it is unknown
	 */
	@Nonnull
	public Optional<CatalogState> catalogStateIfKnown() {
		return Optional.ofNullable(this.catalogState);
	}

}
