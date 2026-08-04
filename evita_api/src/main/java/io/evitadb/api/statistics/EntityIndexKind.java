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

/**
 * Kind of entity index, as reported by {@link CollectionIndexSummary.IndexKindCount}.
 *
 * **Why this mirrors an engine enum**
 *
 * The engine's `io.evitadb.index.EntityIndexType` lives in `evita_engine`, which the API module must not depend on.
 * This enum is its API-side counterpart, mapped by an exhaustive switch on the engine side so that adding a kind there
 * fails to compile until it is added here too.
 *
 * **The mirror is deliberately not one-to-one.** This is new API and does not carry deprecated engine values: the
 * engine's `REFERENCED_HIERARCHY_NODE` was merged into `REFERENCED_ENTITY` in 2024.12 because it held the same data,
 * and it has no counterpart here. The engine-side mapping folds it into {@link #REFERENCED_ENTITY}, which is what the
 * engine itself does whenever it reads a legacy index part, rather than failing a statistics call on a catalog old
 * enough to still name it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see CollectionIndexSummary
 */
public enum EntityIndexKind {

	/**
	 * Index covering all entities of the collection.
	 */
	GLOBAL,

	/**
	 * Index covering entities that reference *any* entity of a particular referenced entity type.
	 */
	REFERENCED_ENTITY_TYPE,

	/**
	 * Index covering entities that reference one particular referenced entity.
	 */
	REFERENCED_ENTITY,

	/**
	 * Index covering entities that reference any entity belonging to a particular group entity type.
	 */
	REFERENCED_GROUP_ENTITY_TYPE,

	/**
	 * Index covering entities that reference one particular group entity.
	 */
	REFERENCED_GROUP_ENTITY

}
