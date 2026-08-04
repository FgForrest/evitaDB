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
 * The {@link CatalogStatisticsComponent#FRAGMENTATION} component - how much of each data store is still live, and how
 * close it is to being compacted.
 *
 * The compaction trigger is deterministic, so the thresholds that drive it are reported alongside the measurements
 * and a client can draw all of them on one gauge:
 *
 * ```
 * compact = fileSize > fileSizeCompactionThresholdBytes
 *        && ( activeRecordShare < maxWasteActiveShare                                  // hard override
 *          || (activeRecordShare < minimalActiveRecordShare && minCompactionIntervalElapsed) )
 * ```
 *
 * The waste accumulation rate and the projected compaction time are not part of this component yet - they need
 * counters that do not exist on the write path today and arrive with the second half of this work.
 *
 * **Per collection**
 *
 * The measurements here are catalog-wide; the same measurements for one collection's data store, and which of them
 * already satisfies the predicate, are fetched separately - see {@link CollectionFragmentation}. The configured
 * thresholds are catalog-wide and are reported here only.
 *
 * **Reading for a degraded catalog**
 *
 * Not delivered for an unusable catalog: the active share is derived from in-memory data-store state.
 *
 * @param activeRecordShare                catalog-wide `liveBytes / (liveBytes + wasteBytes)`; `1.0` when nothing is
 *                                         stored yet
 * @param liveBytes                        bytes of active records across the catalog and all collection data stores
 * @param wasteBytes                       bytes compaction would reclaim
 * @param compactionEligibleNow            true when at least one data store already satisfies the predicate above
 * @param fileSizeCompactionThresholdBytes configured minimum file size below which compaction never triggers
 * @param minimalActiveRecordShare         configured share below which compaction triggers once the minimum interval
 *                                         has elapsed
 * @param maxWasteActiveShare              configured share below which compaction triggers regardless of the interval
 * @param minCompactionIntervalMilliseconds configured minimum spacing between two compactions of the same file
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record FragmentationStatistics(
	double activeRecordShare,
	long liveBytes,
	long wasteBytes,
	boolean compactionEligibleNow,
	long fileSizeCompactionThresholdBytes,
	double minimalActiveRecordShare,
	double maxWasteActiveShare,
	long minCompactionIntervalMilliseconds
) {
}
