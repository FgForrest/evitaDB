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
 * Which of an attribute's index structures a cardinality reading describes. One attribute can be indexed several ways
 * at once - a `filterable` **and** `sortable` attribute has both a filter and a sort index - and their distinct-value
 * counts are not interchangeable, so a reading always names the structure it came from.
 *
 * The chain index (used for ordered references) has no value dimension at all: it stores predecessor links between
 * records rather than values, so "distinct values" is not a question that can be asked of it and it is deliberately
 * absent from this enum.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see CollectionIndexCardinality
 */
public enum AttributeIndexType {

	/**
	 * The unique index - a value maps to at most one record. Its distinct-value count and the number of records it
	 * covers are equal by construction, which is exactly what makes a discrepancy between them worth noticing.
	 */
	UNIQUE,

	/**
	 * The filter index - the inverted index used to resolve equality, prefix and range predicates. This is the
	 * structure whose selectivity the `INDEX_CARDINALITY` component exists to expose.
	 */
	FILTER,

	/**
	 * The sort index - records ordered by their attribute value. Its distinct-value count says how many ordering
	 * groups exist; a sort index with a handful of distinct values sorts within large blocks of ties.
	 */
	SORT

}
