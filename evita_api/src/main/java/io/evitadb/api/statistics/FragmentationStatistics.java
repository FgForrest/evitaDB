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
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The {@link CatalogStatisticsComponent#FRAGMENTATION} component - how much of each data store is still live, how fast
 * that is getting worse, and when the engine will act on it.
 *
 * The compaction trigger is deterministic, so the configured thresholds that drive it are reported alongside the
 * measurements:
 *
 * ```
 * compact = fileSize > fileSizeCompactionThresholdBytes
 *        && ( activeRecordShare < maxWasteActiveShare                                  // hard override
 *          || (activeRecordShare < minimalActiveRecordShare && minCompactionIntervalElapsed) )
 * ```
 *
 * **`activeRecordShare` is not the share that predicate is evaluated against, and the two must not be compared.**
 * The share reported here is a catalog-wide aggregate over the bytes reported next to it -
 * `liveBytes / (liveBytes + wasteBytes)` - so that a client can reproduce it from the record. The trigger instead
 * evaluates *each data store separately*, against its own file length, which also carries the serialized
 * offset-index table and anything the classification could not attribute. They disagree in both directions: one
 * small, very wasteful file makes `compactionEligibleNow` true while this aggregate still sits comfortably above
 * `minimalActiveRecordShare`, and the aggregate can fall below `minimalActiveRecordShare` while no file is large
 * enough to qualify at all.
 *
 * So: read the aggregate as *how wasteful is my data*, read the thresholds as *what the engine is configured to act
 * on*, and take whether it will act from `compactionEligibleNow` alone. Rendering the share and the thresholds on one
 * gauge and inferring the verdict from where the needle sits will contradict the flag next to it.
 *
 * **The current share answers "is this wasteful now"; the forecast answers the question a developer actually has.**
 * `wasteBytesGenerated` counts the bytes rewrites and removals have stranded *since each data store was opened or last
 * compacted* - it is the engine's own production counter, and it is deliberately not the same quantity as `wasteBytes`,
 * which is measured from the files and therefore also carries waste produced before this process started.
 * `wasteAccumulationRateBytesPerSecond` is that counter sampled at flush and smoothed, and `estimatedCompactionAt`
 * extrapolates the crossing from it.
 *
 * **A rate of `0` and an absent `estimatedCompactionAt` mean "not on this trajectory", not "never".** A catalog nobody
 * writes to accumulates no waste, so no crossing can be projected, and the same holds immediately after a compaction.
 * Anything else would put a fabricated timestamp on a management screen, which reads as a real answer.
 *
 * **`compactionEligibleNow` and `estimatedCompactionAt` are independent, and both can be set.** A data store that
 * already satisfies the predicate contributes no projection - it needs no forecast - so the boolean says *something is
 * due now* while the timestamp says *when the next store that is not yet due crosses*. At the collection level, where
 * there is only one data store, an eligible store therefore always reports an absent timestamp.
 *
 * **Which store is it?**
 *
 * Every measurement above is folded across the catalog's own data store *and* every collection's, so on its own it
 * cannot say *where* the fragmentation is. `catalogDataStore` reports the catalog's own store - schema, headers and
 * catalog-level indexes - separately, so a raised `compactionEligibleNow` can be attributed: if the nested flag is
 * set the catalog's own store is due, and if it is not the flag came from a collection. Within one response
 * `liveBytes` is `catalogDataStore.liveBytes()` plus the same field of every open collection, and likewise for
 * `wasteBytes`; across two responses that identity is not guaranteed, which is why the slice is reported rather than
 * left to be derived.
 *
 * **Per collection**
 *
 * The measurements here are catalog-wide; the same measurements for one collection's data store, and which of them
 * already satisfies the predicate, are fetched separately - see {@link DataStoreFragmentation}. The configured
 * thresholds are catalog-wide and are reported here only.
 *
 * **Reading for a degraded catalog**
 *
 * Not delivered for an unusable catalog: the active share is derived from in-memory data-store state.
 *
 * @param activeRecordShare                catalog-wide `liveBytes / (liveBytes + wasteBytes)`; `1.0` when nothing is
 *                                         stored yet. Not the per-file share the compaction predicate uses - see
 *                                         above before comparing it to any threshold below
 * @param liveBytes                        bytes of active records across the catalog and all collection data stores
 * @param wasteBytes                       bytes compaction would reclaim
 * @param compactionEligibleNow            true when at least one data store already satisfies the predicate above
 * @param wasteBytesGenerated              bytes stranded by rewrites and removals since each data store was opened
 *                                         or last compacted, summed across them
 * @param wasteAccumulationRateBytesPerSecond rate at which `wasteBytesGenerated` grows, smoothed over recent flushes
 *                                         and decayed while nothing is being written; `0` when no waste is accruing
 * @param estimatedCompactionAt            projected wall-clock time at which the first data store that is not already
 *                                         eligible crosses the predicate; null when no crossing follows from the
 *                                         current rate
 * @param catalogDataStore                 the same measurements for the catalog's own data store alone - the slice of
 *                                         every figure above that belongs to no collection
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
	long wasteBytesGenerated,
	double wasteAccumulationRateBytesPerSecond,
	@Nullable OffsetDateTime estimatedCompactionAt,
	@Nonnull DataStoreFragmentation catalogDataStore,
	long fileSizeCompactionThresholdBytes,
	double minimalActiveRecordShare,
	double maxWasteActiveShare,
	long minCompactionIntervalMilliseconds
) {

	/**
	 * Returns the projected time of the next compaction.
	 *
	 * @return when the first data store is expected to satisfy the compaction predicate, empty when no crossing
	 * follows from the current write rate or when one already does
	 */
	@Nonnull
	public Optional<OffsetDateTime> estimatedCompactionAtIfKnown() {
		return Optional.ofNullable(this.estimatedCompactionAt);
	}

}
