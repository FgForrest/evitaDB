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

package io.evitadb.index.attribute;

/**
 * Describes the sort direction a {@link SortedRecordsSupplier} serves together with how its base record-id / position
 * arrays realize that direction. It collapses what used to be two entangled boolean flags (`descending` plus
 * `descendingFromAscendingArrays`) into a single value, so the one nonsensical combination — ascending yet deriving
 * from the ascending arrays — cannot be expressed at all.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum SortDirectionBacking {

	/**
	 * Ascending order; the supplier's base arrays already ARE the ascending arrays, indexed and emitted directly.
	 */
	ASCENDING,

	/**
	 * Descending order served from the supplier's OWN reversed / inverted arrays (e.g. the per-transaction sort layer
	 * or the chain index), which it indexes and emits directly — no transform applied.
	 */
	DESCENDING_OWN_ARRAYS,

	/**
	 * Descending order derived on demand from the SHARED ascending arrays (the committed snapshot's ascending cache)
	 * by the `recordCount - 1 - position` transform, so no reversed / inverted copy is ever materialized.
	 */
	DESCENDING_MIRRORS_ASCENDING;

	/**
	 * Whether this supplier serves the descending order (regardless of how its arrays back that direction).
	 *
	 * @return `true` for either descending backing, `false` for {@link #ASCENDING}
	 */
	public boolean isDescending() {
		return this != ASCENDING;
	}

	/**
	 * Whether the descending direction is produced by mirroring the shared ascending arrays on demand, rather than by
	 * indexing the supplier's own reversed / inverted arrays.
	 *
	 * @return `true` only for {@link #DESCENDING_MIRRORS_ASCENDING}
	 */
	public boolean mirrorsAscendingArrays() {
		return this == DESCENDING_MIRRORS_ASCENDING;
	}

}
