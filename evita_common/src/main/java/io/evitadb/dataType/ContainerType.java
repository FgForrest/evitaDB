/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * The container type describes internal evitaDB data structures.
 */
@RequiredArgsConstructor
public enum ContainerType {

	/**
	 * Catalog - similar to relational database schema.
	 */
	CATALOG,
	/**
	 * Entity - similar to relational database table (or better - set of inter-related tables).
	 */
	ENTITY,
	/**
	 * Attribute - similar to relational database column.
	 */
	ATTRIBUTE,
	/**
	 * Reference - similar to an unstructured JSON document in relational database column.
	 */
	ASSOCIATED_DATA,
	/**
	 * Price - fixed structure data type, could be represented as row in a specialized table in relational database.
	 */
	PRICE,
	/**
	 * Reference - similar to a foreign key in relational database or a binding table in many-to-many relationship.
	 */
	REFERENCE

}
