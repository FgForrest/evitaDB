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

package io.evitadb.core.collection;

import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.IndexActivity;

import javax.annotation.Nonnull;

/**
 * Describes one entity index in full - what it occupies and how well it discriminates.
 *
 * The third of the index projections, and the only one whose cost is proportional to an index's **contents** rather
 * than to the schema ({@link IndexCardinalityProjection}) or to the number of indexes
 * ({@link IndexBrowseProjection}). That is why it describes exactly one index, named by the caller: the heap estimate
 * walks everything the index holds, which took 151 ms on the largest index of a production catalog and ~4 µs on the
 * median one. Nothing here may be reachable in a loop over a collection's indexes.
 *
 * **It borrows rather than duplicates.** The cardinality readings come from
 * {@link IndexCardinalityProjection#describeIndex}, and the discriminator from
 * {@link IndexBrowseProjection#renderDiscriminator} - so a drill-down reports the same values, rendered the same way,
 * as the two components it is reached from. Reimplementing either here would let three surfaces disagree about one
 * index.
 *
 * **The cardinality is free, in cost-class terms.** Every reading it takes is either an `O(1)` counter or a walk over
 * the buckets of an inverted index - and the heap estimate traverses those same buckets to price them, so the
 * cardinality is a strict subset of work already being done. This is what makes carrying both in one response
 * defensible where carrying either across a whole collection is not.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see IndexDetail
 */
final class IndexDetailProjection {

	private IndexDetailProjection() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

	/**
	 * Describes the given index.
	 *
	 * @param entityType name of the collection holding the index
	 * @param index      the index to describe, already resolved by the caller from its primary key
	 * @return the full description of that one index
	 */
	@Nonnull
	static IndexDetail describe(@Nonnull String entityType, @Nonnull EntityIndex index) {
		// read off the live index rather than off a snapshot, which is exactly right for counters that are shared
		// across catalog versions and deliberately outside the transactional diff layer
		final IndexActivity activity = index.getActivity();
		return new IndexDetail(
			entityType,
			index.getPrimaryKey(),
			index.getHeapSizeInBytes(),
			IndexCardinalityProjection.describeIndex(
				index.getIndexKey(),
				IndexBrowseProjection.renderDiscriminator(index.getIndexKey()),
				index
			),
			activity.getQueryCount(),
			activity.getUpdateCount(),
			activity.getLastQueriedAt(),
			activity.getLastUpdatedAt()
		);
	}

}
