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

package io.evitadb.api.requestResponse.schema.dto;


import io.evitadb.api.query.filter.ReferenceHaving;

/**
 * This enum represents the type of index that should be created and maintained for a reference. It determines
 * the level of indexing optimization that will be applied to improve query performance when filtering by
 * {@link ReferenceHaving} constraints.
 *
 * The index type affects both memory/disk usage and query performance. Maintaining partitioned indexes provides
 * better query performance at the cost of increased storage requirements and maintenance overhead.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum ReferenceIndexType {

	/**
	 * Reference has no index available.
	 * This means that the reference cannot be used in any query filtering or sorting.
	 *
	 * Use this type when you do not need to filter nor sort by reference existence or any of the reference attributes,
	 * and you want to minimize memory and disk usage.
	 */
	NONE,

	/**
	 * Reference has only basic index available that is necessary for {@link ReferenceHaving} constraint interpretation.
	 * This is the minimal indexing level that allows filtering by reference existence and reference attributes.
	 *
	 * Use this type when you need basic reference filtering capabilities but want to minimize memory and disk usage.
	 * This is suitable for references that are not frequently used in complex queries or when storage optimization
	 * is more important than query performance.
	 *
	 * This is the recommended default indexing type for references and is sufficient for most use cases.
	 */
	FOR_FILTERING,

	/**
	 * Reference has basic index available that is necessary for {@link ReferenceHaving} constraint interpretation,
	 * and also partitioning indexes for the main entity type (i.e. entity type that contains the reference schema),
	 * which may greatly speed up the query execution when the reference is part of the query filtering.
	 *
	 * This advanced indexing creates additional data structures that allow for more efficient query execution
	 * by partitioning the data based on the reference relationships. This can significantly improve performance
	 * for complex queries that involve reference filtering, especially when dealing with large datasets.
	 *
	 * Use this type when reference filtering is frequently used in queries and query performance is critical.
	 * Be aware that this option requires more memory and disk space compared to {@link #FOR_FILTERING}.
	 */
	FOR_FILTERING_AND_PARTITIONING

}
