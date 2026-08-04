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

/**
 * The {@link CatalogStatisticsComponent#STORAGE_COMPOSITION} component - where a collection's bytes actually go,
 * broken down by storage-part type.
 *
 * **Measured in bytes, not records.** Counts can invert the answer this statistic exists to give: a collection holding
 * 500k small attribute parts and 2k large associated-data blobs reads as attribute-dominated by count while being
 * associated-data-dominated by bytes. Since the question is "where is my storage going", bytes are the correct unit.
 * Counts are reported alongside so the average per type is exact.
 *
 * **No per-type maximum is reported**, deliberately. It would inherit the same "never decreases" wart as
 * {@link CollectionHeaderInfo#maxRecordSizeBytes()}, and a number whose label does not match its meaning is worse than
 * an absent number.
 *
 * **Scope of this component** - the catalog's own data store only, which holds schemas and catalog-level indexes. The
 * histogram of one entity collection's data store is fetched separately - see {@link CollectionStorageComposition}.
 * There is no catalog-wide sum across collections: adding up records of different storage-part types from different
 * data stores produces a number with no operational meaning.
 *
 * **Cost** - an in-memory map read per data store. Cheapness is already proven: the same histogram backs a live
 * Prometheus gauge.
 *
 * **Reading for a degraded catalog**
 *
 * Not delivered for an unusable catalog; the histogram lives in the loaded data stores.
 *
 * @param catalogParts storage-part usage of the catalog's own data store (schemas, catalog indexes)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record StorageCompositionStatistics(
	@Nonnull StoragePartUsage[] catalogParts
) {

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		return Arrays.equals(this.catalogParts, ((StorageCompositionStatistics) o).catalogParts);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(this.catalogParts);
	}

	@Nonnull
	@Override
	public String toString() {
		return "StorageCompositionStatistics{catalogParts=" + Arrays.toString(this.catalogParts) + '}';
	}

}
