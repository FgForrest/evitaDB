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

import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * The {@link CatalogStatisticsComponent#INDEX_SUMMARY} component of one entity collection - how many indexes it holds,
 * broken down by kind and scope.
 *
 * This is what turns the historically opaque single `indexCount` into something a developer can act on: forty thousand
 * `REFERENCED_ENTITY` indexes and forty `GLOBAL` ones are very different situations that used to render as the same
 * number.
 *
 * **Why the breakdown is not available at the catalog level** - producing it means one pass over the index keys of the
 * collection. That is cheap for one collection and not something to do for every collection of a catalog on every
 * polled refresh, so {@link IndexSummaryStatistics} reports the plain total (an `O(1)` map size per collection) and
 * the breakdown is fetched per collection. The distinct-value cardinality *inside* each index is more expensive still
 * and lives in a separate component ({@link CatalogStatisticsComponent#INDEX_CARDINALITY}).
 *
 * @param totalIndexCount total number of indexes in this collection
 * @param byKindAndScope  one entry per (kind, scope) pair that has at least one index; pairs with no index are omitted
 *                        rather than reported as zero
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CollectionIndexSummary(
	int totalIndexCount,
	@Nonnull IndexKindCount[] byKindAndScope
) {

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final CollectionIndexSummary that = (CollectionIndexSummary) o;
		return this.totalIndexCount == that.totalIndexCount &&
			Arrays.equals(this.byKindAndScope, that.byKindAndScope);
	}

	@Override
	public int hashCode() {
		return 31 * this.totalIndexCount + Arrays.hashCode(this.byKindAndScope);
	}

	@Nonnull
	@Override
	public String toString() {
		return "CollectionIndexSummary{totalIndexCount=" + this.totalIndexCount +
			", byKindAndScope=" + Arrays.toString(this.byKindAndScope) + '}';
	}

	/**
	 * Number of indexes of one kind within one scope.
	 *
	 * @param indexKind kind of the index
	 * @param scope     scope the indexes belong to
	 * @param count     how many such indexes exist
	 */
	public record IndexKindCount(
		@Nonnull EntityIndexKind indexKind,
		@Nonnull Scope scope,
		int count
	) {
	}

}
