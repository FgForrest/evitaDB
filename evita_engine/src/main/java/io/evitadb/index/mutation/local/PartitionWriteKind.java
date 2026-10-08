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

package io.evitadb.index.mutation.local;

import io.evitadb.api.exception.ReferencePartitionReindexRequiredException;

/**
 * Says what a write path does to the reference partitions it acquires: whether it may put content into them, or only
 * takes content out of them. The acquisition of a partition (see
 * {@link EntityIndexLocalMutationExecutor#getPartitionForWrite}) needs it for one case only - a partition left as a
 * registry row by a reference that was indexed for filtering only and is indexed for partitioning now. Such a row holds
 * only what the former indexing kept of the partition: a write taking content out of the partition proceeds on the row
 * alone, a write putting content into it is refused (see {@link ReferencePartitionReindexRequiredException}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum PartitionWriteKind {

	/**
	 * The write may put content into the partition - an owner entering it, an attribute value upserted or changed by
	 * a delta, a locale, a price, a facet or a facet group added.
	 */
	ADDING,
	/**
	 * The write only takes content out of the partition - an owner leaving it, an attribute value, a locale, a price,
	 * a facet or a facet group removed - or puts nothing into it.
	 */
	REMOVING

}
