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
 * The {@link CatalogStatisticsComponent#FRAGMENTATION} component of one entity collection - how much of its data store
 * is still live, and whether it already satisfies the compaction predicate.
 *
 * The configured thresholds that drive that predicate are catalog-wide and are reported once, in
 * {@link FragmentationStatistics}, rather than repeated for every collection.
 *
 * @param activeRecordShare     `liveBytes / (liveBytes + wasteBytes)` for this collection's data store
 * @param liveBytes             bytes of active records in it
 * @param wasteBytes            bytes compacting it would reclaim
 * @param compactionEligibleNow true when this data store already satisfies the compaction predicate
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CollectionFragmentation(
	double activeRecordShare,
	long liveBytes,
	long wasteBytes,
	boolean compactionEligibleNow
) {
}
