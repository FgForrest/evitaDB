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
 * ReferencedEntityPredecessor is an opposite of the {@link Predecessor} data type. It is a special data type allowing
 * to create consistent or semi-consistent linked lists in evitaDB and sort by the order of the elements in the list.
 * ReferencedEntityPredecessor can be used only in reference attributes and represents a reference to another entity of
 * the same referenced entity type.
 *
 * Let's have a relation between products and category. Each products can have a reference to a category it belongs to
 * and there is {@link Predecessor} attribute that indicates the order of the products in the category:
 *
 * Product PK | Category FK | Predecessor
 * ---------------------------------------
 * 5          | 1           | HEAD (-1)
 * 3          | 1           | 5
 * 1          | 1           | 3
 * 4          | 1           | 1
 *
 * If the reference is made as bi-directional and we want to view the products from the category perspective including
 * the order of the products, we must use the ReferencedEntityPredecessor attribute:
 *
 * Category PK | Product FK | ReferencedEntityPredecessor
 * -------------------------------------------------------
 * 1           | 5          | HEAD (-1)
 * 1           | 3          | 5
 * 1           | 1          | 3
 * 1           | 4          | 1
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public record ReferencedEntityPredecessor(
	int predecessorPk
) implements Serializable, ChainableType {
	public static final ReferencedEntityPredecessor HEAD = new ReferencedEntityPredecessor();

	/**
	 * Head entity constructor.
	 */
	public ReferencedEntityPredecessor() {
		this(HEAD_PK);
	}

	/**
	 * Constructor for successor entity.
	 *
	 * @param predecessorPk id of the predecessor
	 */
	public ReferencedEntityPredecessor {}

}
