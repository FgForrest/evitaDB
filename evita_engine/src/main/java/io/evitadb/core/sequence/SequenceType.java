/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.sequence;

import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.Transaction;

/**
 * This enum represents various type of sequences used in {@link EntityCollection}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public enum SequenceType {
	/**
	 * {@link EntityCollection} type.
	 */
	ENTITY_COLLECTION,
	/**
	 * {@link Entity} type.
	 */
	ENTITY,
	/**
	 * {@link io.evitadb.index.EntityIndex} type.
	 */
	INDEX,
	/**
	 * {@link Transaction} type.
	 */
	TRANSACTION
}
