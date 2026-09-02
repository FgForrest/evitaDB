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

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Objects;

/**
 * One page of an index browse, and the catalog version it was cut from.
 *
 * **Why the catalog version is part of the answer.** A browse spans several calls, and the index set moves underneath
 * it: indexes are created and dropped as data is written. Two pages read at different catalog versions do not
 * describe one set, and unlike two disagreeing statistics snapshots - which are merely stale - two disagreeing pages
 * corrupt the client's picture of what exists. Comparing this value across pages is what lets a caller notice that and
 * restart, so it is reported rather than left for the caller to guess.
 *
 * **That comparison only discriminates once the catalog is alive.** The version advances per committed transaction,
 * and a {@link io.evitadb.api.CatalogState#WARMING_UP} catalog runs no transactions - so during a bulk load it stays
 * put while the index set churns faster than at any other time. Pages read then can differ while reporting the same
 * version, and a client following the protocol above gets a false all-clear exactly where it is least warranted.
 * There is no cheaper counter to substitute: what a caller actually wants to know is whether the *index set* moved,
 * and nothing tracks that separately.
 *
 * The page itself is internally consistent either way: the walk runs over a sealed view of the index map, so
 * {@link #totalRecordCount()} and the page contents always come from the same state even while writes are landing.
 * Warm-up costs cross-page comparison, never within-page coherence.
 *
 * @param catalogVersion   version of the catalog this page was read from; compare it across pages of one browse, but
 *                         see above for why that comparison is blind while the catalog is warming up
 * @param pageNumber       the page that was returned, 1-indexed, echoing the request
 * @param pageSize         the page size that was applied, echoing the request
 * @param totalRecordCount how many indexes matched the filters in total, across every page - not the number returned
 *                         in this one, and not the collection's total index count unless the browse was unfiltered
 * @param indexes          the indexes on this page, in the requested order; shorter than `pageSize` on the last page,
 *                         and empty when `pageNumber` addresses a page past the end
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see BrowsedIndex
 * @see IndexBrowseCriteria
 */
public record IndexBrowseResult(
	long catalogVersion,
	int pageNumber,
	int pageSize,
	int totalRecordCount,
	@Nonnull BrowsedIndex[] indexes
) {

	public IndexBrowseResult {
		Objects.requireNonNull(indexes, "Indexes must not be null!");
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final IndexBrowseResult that = (IndexBrowseResult) o;
		return this.catalogVersion == that.catalogVersion &&
			this.pageNumber == that.pageNumber &&
			this.pageSize == that.pageSize &&
			this.totalRecordCount == that.totalRecordCount &&
			Arrays.equals(this.indexes, that.indexes);
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(this.catalogVersion);
		result = 31 * result + this.pageNumber;
		result = 31 * result + this.pageSize;
		result = 31 * result + this.totalRecordCount;
		return 31 * result + Arrays.hashCode(this.indexes);
	}

	@Nonnull
	@Override
	public String toString() {
		return "IndexBrowseResult{catalogVersion=" + this.catalogVersion +
			", pageNumber=" + this.pageNumber +
			", pageSize=" + this.pageSize +
			", totalRecordCount=" + this.totalRecordCount +
			", indexes=" + Arrays.toString(this.indexes) + '}';
	}

}
