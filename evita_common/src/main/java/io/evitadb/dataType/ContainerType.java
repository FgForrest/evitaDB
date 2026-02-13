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

import javax.annotation.Nonnull;

/**
 * Enumeration of internal evitaDB data container types. These types represent the major structural elements
 * within the evitaDB data model and are used for categorizing mutations, change capture events, and data site
 * identifiers in the evitaDB change data capture (CDC) system.
 *
 * Each container type corresponds to a distinct data structure in the evitaDB entity model, from the top-level
 * catalog down to individual attributes, prices, and references.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public enum ContainerType {

	/**
	 * Catalog container - similar to a relational database schema. Represents the top-level data container
	 * that holds collections of entities and their schemas.
	 */
	CATALOG,
	/**
	 * Entity container - similar to a relational database table (or better - a set of inter-related tables).
	 * Represents a single entity instance with all its attributes, prices, references, and associated data.
	 */
	ENTITY,
	/**
	 * Attribute container - similar to a relational database column. Represents a single named attribute
	 * with a typed value attached to an entity.
	 */
	ATTRIBUTE,
	/**
	 * Associated data container - similar to an unstructured JSON document stored in a relational database column.
	 * Represents complex, schema-less data structures attached to an entity for storing auxiliary information
	 * that doesn't require indexing or filtering.
	 */
	ASSOCIATED_DATA,
	/**
	 * Price container - fixed structure data type, could be represented as a row in a specialized table in relational
	 * database. Represents a single price point for an entity with price list, currency, validity, and amount.
	 */
	PRICE,
	/**
	 * Reference container - similar to a foreign key in relational database or a binding table in a many-to-many
	 * relationship. Represents a link from one entity to another, optionally with attributes attached to the
	 * relationship itself.
	 */
	REFERENCE

}
