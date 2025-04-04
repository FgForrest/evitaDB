/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import java.io.Serializable;

/**
 * Predecessor is a special data type allowing to create consistent or semi-consistent linked lists in evitaDB and sort
 * by the order of the elements in the list. Predecessor represents a reference to another entity of the same type
 * as itself that is the predecessor of the entity it is attached to.
 *
 * Let's have this ordered sequence of entities: [5, 3, 1, 4]
 * This sequence can be translated to the following Predecessor attributes:
 *
 * Entity PK | Predecessor
 * ------------------------
 * 5         | HEAD (-1)
 * 3		 | 5
 * 1		 | 3
 * 4		 | 1
 *
 * As you can see, the Predecessor uses the primary key of the same entity as a pointer to its predecessor. The only
 * exception is the HEAD entity, which is a special entity representing the beginning of the list.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public record Predecessor(
	int predecessorPk
) implements Serializable, ChainableType {
	public static final Predecessor HEAD = new Predecessor();

	/**
	 * Head entity constructor.
	 */
	public Predecessor() {
		this(HEAD_PK);
	}

	/**
	 * Constructor for successor entity.
	 *
	 * @param predecessorPk id of the predecessor
	 */
	public Predecessor {}

}
