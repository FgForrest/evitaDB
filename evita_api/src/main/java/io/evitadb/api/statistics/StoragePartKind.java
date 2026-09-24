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
 * What a storage part fundamentally *is* - the coarse axis of {@link StoragePartGroup}, and the one a composition
 * table folds to when it wants three rows rather than fourteen.
 *
 * The three kinds answer three different questions, which is why they are not one scale:
 *
 * - {@link #ENTITY_DATA} is what the client wrote. It shrinks only by storing less.
 * - {@link #INDEX} is what the engine derived from it to answer queries. It shrinks by indexing less - a schema
 *   decision the operator can take without losing any data.
 * - {@link #METADATA} is what the store needs to describe itself. Nothing can be done about it, and it is reported
 *   so the shares add up rather than because anybody can act on it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see StoragePartGroup
 */
public enum StoragePartKind {

	/**
	 * The entity's own persisted state - its body, attributes, associated data, prices and references. These are the
	 * bytes the client handed to evitaDB.
	 */
	ENTITY_DATA,

	/**
	 * Structures the engine derived from entity data so it can answer queries without scanning it. Every byte here is
	 * reconstructible from {@link #ENTITY_DATA} by a full reindex, and every byte here was asked for by a schema flag.
	 */
	INDEX,

	/**
	 * Schema and header records - what the data store needs to describe itself. Present in every data store, dominant
	 * in none, and not attributable to any single entity.
	 */
	METADATA

}
