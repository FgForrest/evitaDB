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

/**
 * Identifies which schema layer supplied the effective entity-level {@link ConflictResolution} once the
 * precedence chain has been walked. Reported alongside a detected conflict so an operator can see *why* a
 * given policy was in force without having to inspect every schema level by hand.
 *
 * The precedence chain is a whole-record override — the most specific non-null resolution wins entirely —
 * so exactly one of these layers is the source of the resolved policy:
 *
 * - {@link #ENTITY_SCHEMA} — the entity schema declared its own resolution, overriding the catalog and
 *   engine defaults.
 * - {@link #CATALOG_SCHEMA} — the entity schema was silent, so the catalog schema's resolution applied.
 * - {@link #ENGINE_DEFAULT} — neither schema declared a resolution, so the engine-wide default applied.
 *
 * Per-item {@link ConflictResolutionOverride} refinements (attribute / reference / associated-data level)
 * are applied at key-emit time on top of this entity-level baseline; they are not reconstructed by the
 * diagnostic path, which reports the baseline layer that the item override refines.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum ConflictResolutionLayer {

	/**
	 * The resolved policy came from the entity schema's own {@link ConflictResolution}.
	 */
	ENTITY_SCHEMA,

	/**
	 * The resolved policy came from the catalog schema's {@link ConflictResolution} because the entity
	 * schema declared none.
	 */
	CATALOG_SCHEMA,

	/**
	 * The resolved policy came from the engine-wide default because neither schema declared one.
	 */
	ENGINE_DEFAULT

}
