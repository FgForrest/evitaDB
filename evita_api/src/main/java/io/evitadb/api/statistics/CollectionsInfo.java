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

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * The {@link CatalogStatisticsComponent#COLLECTIONS} component - the inventory of the catalog's entity collections.
 *
 * This is deliberately the *only* per-collection list a catalog-level response carries, and it holds no statistics:
 * it answers "which collections exist", which is what a client needs before it can ask for any of them. The counters
 * of a single collection are fetched by naming it - see {@link CollectionHeaderInfo}.
 *
 * **Cost** - a read of the already-loaded collection map, independent of collection size.
 *
 * **Reading for a degraded catalog**
 *
 * Not delivered for an unusable catalog; the component status carries {@link ComponentAvailability#CATALOG_UNUSABLE}.
 * {@link CatalogIdentity#entityCollectionCount()} is no substitute - it reads `-1` in exactly that case.
 *
 * @param collections one entry per entity collection the catalog holds
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CollectionsInfo(
	@Nonnull CollectionInfo[] collections
) {

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		return Arrays.equals(this.collections, ((CollectionsInfo) o).collections);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(this.collections);
	}

	@Nonnull
	@Override
	public String toString() {
		return "CollectionsInfo{collections=" + Arrays.toString(this.collections) + '}';
	}

	/**
	 * Identification of a single entity collection - what to pass to a collection-level statistics call, and the
	 * internal primary key the engine knows the entity type by.
	 *
	 * @param entityType           name of the entity collection
	 * @param entityTypePrimaryKey internal primary key assigned to the entity type itself
	 */
	public record CollectionInfo(
		@Nonnull String entityType,
		int entityTypePrimaryKey
	) {
	}

}
