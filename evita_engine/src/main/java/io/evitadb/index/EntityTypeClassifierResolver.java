/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.index;

import io.evitadb.core.catalog.Catalog;

import javax.annotation.Nonnull;

/**
 * Narrow view that translates between an entity type name and the compact integer primary key that the
 * catalog assigns to it. Catalog-wide index structures (namely the global unique index) store the compact
 * type primary key inside their packed tuples to save memory, but must occasionally translate it back to the
 * human-readable name (and vice versa). This resolver is the ONLY dependency such an index needs on the
 * surrounding catalog — the resolution is version-sensitive (a collection rename or delete changes the
 * name ↔ pk mapping), so it must always be supplied by the caller's current snapshot rather than captured as
 * a long-lived back-reference inside the index.
 *
 * Implemented by {@link Catalog}; the two methods delegate to its collection lookups.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface EntityTypeClassifierResolver {

	/**
	 * Translates an entity type name to the compact integer primary key stored in packed index tuples.
	 *
	 * @param entityType the entity type name
	 * @return the compact entity type primary key
	 */
	int toEntityTypePrimaryKey(@Nonnull String entityType);

	/**
	 * Translates the compact integer primary key stored in packed index tuples back to the entity type name.
	 *
	 * @param entityTypePrimaryKey the compact entity type primary key
	 * @return the entity type name
	 */
	@Nonnull
	String toEntityTypeName(int entityTypePrimaryKey);

}
