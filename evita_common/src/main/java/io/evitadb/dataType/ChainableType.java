/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.dataType;


/**
 * Common ancestor for the {@link Predecessor} and {@link ReferencedEntityPredecessor} data types.
 * It is a special data type allowing to create consistent or semi-consistent linked lists in evitaDB and sort
 * by the order of the elements in the list.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ChainableType {
	int HEAD_PK = -1;

	/**
	 * Returns true if this is the head of the list.
	 * @return true if this is the head of the list
	 */
	default boolean isHead() {
		return predecessorPk() == HEAD_PK;
	}

	/**
	 * Returns the predecessor primary key. If this is the head of the list, the primary key is -1.
	 * @return the predecessor primary key
	 */
	int predecessorPk();

}
