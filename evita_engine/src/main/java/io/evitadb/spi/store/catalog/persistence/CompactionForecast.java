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
import javax.annotation.Nullable;
import java.time.OffsetDateTime;

/**
 * When one data store - or a whole catalog's worth of them - is going to be compacted, and how fast it is heading
 * there. Produced by {@link CatalogPersistenceService#measureFragmentation()} - once for the catalog's own data
 * store and once folded across every store it holds open - and by
 * {@link EntityCollectionPersistenceService#measureCompactionForecast()} for one collection's.
 *
 * **The eligibility flag is evaluated by the persistence layer, never re-derived by its caller.** The compaction
 * predicate lives next to the code that actually fires it, reads the same configured thresholds and the same
 * last-compaction timestamps, and would otherwise be written twice and drift the first time the trigger is touched.
 * The engine only projects this record onto the statistics component that reports it.
 *
 * `estimatedCompactionAt` is an extrapolation from the rate, not a schedule: a store whose write load changes will
 * cross earlier or later than it says, and a store nobody writes to will not cross at all - which is reported as
 * `null` rather than as a distant date, because a rendered timestamp reads as a commitment.
 *
 * @param compactionEligibleNow               true when at least one of the data stores described already satisfies the
 *                                            compaction predicate
 * @param wasteBytesGenerated                 bytes stranded by rewrites and removals since each described data store
 *                                            was opened or last compacted, summed; not the total waste those files
 *                                            hold, which is measured separately
 * @param wasteAccumulationRateBytesPerSecond how fast that counter is growing, smoothed over recent flushes and
 *                                            decayed while nothing is being written
 * @param estimatedCompactionAt               earliest projected crossing among the stores that are not already
 *                                            eligible; `null` when none follows from the current rate
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CompactionForecast(
	boolean compactionEligibleNow,
	long wasteBytesGenerated,
	double wasteAccumulationRateBytesPerSecond,
	@Nullable OffsetDateTime estimatedCompactionAt
) {

	/**
	 * Combines this forecast with another one, which is how the catalog-wide answer is assembled from the per-store
	 * ones.
	 *
	 * The counters and the rates add up; the two remaining fields do not. Eligibility is a disjunction - the catalog
	 * has work to do as soon as *any* of its stores does - and the projected time is the earliest of the two, since
	 * the question is when the next compaction happens, not the last. A store with nothing foreseen contributes no
	 * date rather than erasing the other's.
	 *
	 * The combiner lives here rather than at the call site so that neither rule can be written one way for one
	 * aggregation and another way for the next.
	 *
	 * @param other the forecast to add to this one
	 * @return the combined forecast
	 */
	@Nonnull
	public CompactionForecast plus(@Nonnull CompactionForecast other) {
		final OffsetDateTime earliest;
		if (this.estimatedCompactionAt == null) {
			earliest = other.estimatedCompactionAt;
		} else if (other.estimatedCompactionAt == null) {
			earliest = this.estimatedCompactionAt;
		} else {
			earliest = this.estimatedCompactionAt.isBefore(other.estimatedCompactionAt) ?
				this.estimatedCompactionAt : other.estimatedCompactionAt;
		}
		return new CompactionForecast(
			this.compactionEligibleNow || other.compactionEligibleNow,
			this.wasteBytesGenerated + other.wasteBytesGenerated,
			this.wasteAccumulationRateBytesPerSecond + other.wasteAccumulationRateBytesPerSecond,
			earliest
		);
	}

}
