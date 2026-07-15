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

package io.evitadb.api.requestResponse.mutation.conflict;

import javax.annotation.Nullable;


/**
 * This interface is used to mark keys that are used to identify conflicts in the evitaDB system.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ConflictKey {

	/**
	 * Returns the immediate ancestor of this key in the conflict containment hierarchy, or {@code null} when this key
	 * already sits at the top of the field-derivable chain.
	 *
	 * The chain climbs from the finest granularity toward the coarsest **within a single entity / collection**:
	 * reference-attribute → reference → entity; attribute / associated-data / price / price-inner-record-handling /
	 * hierarchy → entity; entity → collection. A range-constrained delta additionally reaches the absolute
	 * attribute-level key it can conflict with (see {@link AttributeDeltaConflictKey#parentConflictKey()}).
	 *
	 * The walk deliberately stops at {@link CollectionConflictKey}: the collection → catalog step is not
	 * field-derivable ({@link CollectionConflictKey} carries no catalog name), so catalog-wide containment is handled
	 * by the matcher as a dedicated {@link CatalogConflictKey} special case rather than through this chain.
	 *
	 * The returned parent is understood to *contain* the receiver: any conflict recorded on the parent scope implies a
	 * conflict on this finer key. Walking {@code parentConflictKey()} until it yields {@code null} therefore
	 * enumerates every coarser scope that, if independently written, conflicts with this key.
	 *
	 * @return the containing parent key, or {@code null} at the top of the derivable chain
	 */
	@Nullable
	default ConflictKey parentConflictKey() {
		return null;
	}

	/**
	 * Returns the entity type this key is scoped to, or {@code null} for a catalog-wide key that is not
	 * bound to any single collection. Every key except {@link CatalogConflictKey} is a record carrying an
	 * {@code entityType} component, so the record accessor satisfies this method automatically; the
	 * catalog-wide key falls back to this {@code null} default.
	 *
	 * Used by the conflict-reporting path to look up the entity schema and resolve which policy was in
	 * force; it is not consulted during matching.
	 *
	 * @return the scoped entity type, or {@code null} for a catalog-wide key
	 */
	@Nullable
	default String entityType() {
		return null;
	}

}
