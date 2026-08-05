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

package io.evitadb.spi.store.catalog.persistence;

import javax.annotation.Nonnull;

/**
 * Everything {@link io.evitadb.api.statistics.CatalogStatisticsComponent#FRAGMENTATION} needs about a catalog, taken
 * as one measurement. Produced by {@link CatalogPersistenceService#measureFragmentation()}.
 *
 * **It exists so that the byte classification and the compaction predicate cannot disagree about the same file.**
 * Both are functions of the data store file lengths, and both used to read them independently - the footprint from a
 * directory listing, the forecast from a `stat` per store. That cost one extra syscall per data store on the polled
 * endpoint, and, worse, let the reported waste and the reported eligibility describe two different moments of a file
 * that is being appended to. One listing now feeds both, which is the same "one snapshot per request" rule the rest
 * of this API holds to.
 *
 * @param footprint                the byte classification of the catalog directory, including the catalog data
 *                                 store's own share of the live/waste split
 * @param catalogDataStoreForecast the compaction forecast of the catalog's own data store alone
 * @param totalForecast            the same folded across the catalog's own data store and every collection's - note
 *                                 that this is *not* the sum of the two visible halves, since the fold is a
 *                                 disjunction for eligibility and an earliest-wins for the projected time
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CatalogFragmentationSnapshot(
	@Nonnull CatalogStorageFootprint footprint,
	@Nonnull CompactionForecast catalogDataStoreForecast,
	@Nonnull CompactionForecast totalForecast
) {
}
