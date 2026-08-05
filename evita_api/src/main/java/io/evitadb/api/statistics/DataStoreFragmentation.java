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
 * How much of **one data store** is still live, how fast that is getting worse, and whether it already satisfies the
 * compaction predicate.
 *
 * One record serves every place a data store is described, because they are the same measurements of the same kind of
 * thing:
 *
 * - one entity collection's data store - {@link EntityCollectionStatistics#fragmentation()}
 * - the catalog's **own** data store, holding the catalog schema, the catalog and collection headers and the
 *   catalog-level indexes - {@link FragmentationStatistics#catalogDataStore()}
 *
 * The catalog-wide figures on {@link FragmentationStatistics} are the fold of all of them, and that fold is a function
 * of these same fields - eligibility disjoined, counters and rates summed, the projected time taken earliest-first.
 * That is why one type describes both a part and the whole: the persistence layer models it the same way, folding a
 * forecast into a forecast of its own type rather than into a separate aggregate shape.
 *
 * The configured thresholds the predicate is evaluated against are catalog-wide and are reported once, on
 * {@link FragmentationStatistics}, rather than repeated for every store.
 *
 * **`activeRecordShare` is not the share the compaction predicate is evaluated against.** It is derived from the two
 * byte figures reported with it, so a client can reproduce it; the trigger measures the store's live bytes against its
 * *file length*, which also carries the serialized offset-index table and anything the classification could not
 * attribute. The two are close but not equal, and the verdict is `compactionEligibleNow` - never this share compared
 * to a threshold. {@link FragmentationStatistics} says the same at more length, including how the two disagree in both
 * directions.
 *
 * **A `0` rate with an absent `estimatedCompactionAt` means "not on this trajectory", never "never".** A store nobody
 * writes to accumulates no waste, so no crossing can be projected, and the same holds immediately after a compaction.
 * Anything else would put a fabricated timestamp on a management screen, where it reads as a real answer.
 *
 * @param activeRecordShare     `liveBytes / (liveBytes + wasteBytes)` for this data store; `1.0` when nothing is
 *                              stored in it yet
 * @param liveBytes             bytes of active records in it
 * @param wasteBytes            bytes compacting it would reclaim
 * @param compactionEligibleNow true when this data store already satisfies the compaction predicate
 * @param wasteBytesGenerated   bytes stranded by rewrites and removals since this data store was opened or last
 *                              compacted - the engine's production counter, not the total waste the files hold
 * @param wasteAccumulationRateBytesPerSecond rate at which `wasteBytesGenerated` grows, smoothed over recent flushes
 *                              and decayed while nothing is being written; `0` when no waste is accruing
 * @param estimatedCompactionAt projected wall-clock time at which this data store crosses the predicate; null when no
 *                              crossing follows from the current rate, and null once `compactionEligibleNow` holds -
 *                              a store that is already due needs no forecast
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record DataStoreFragmentation(
	double activeRecordShare,
	long liveBytes,
	long wasteBytes,
	boolean compactionEligibleNow,
	long wasteBytesGenerated,
	double wasteAccumulationRateBytesPerSecond,
	@Nullable OffsetDateTime estimatedCompactionAt
) {

	/**
	 * Returns the projected time of the next compaction of this data store.
	 *
	 * @return when this data store is expected to satisfy the compaction predicate, empty when no crossing follows
	 * from the current write rate or when it already does
	 */
	@Nonnull
	public Optional<OffsetDateTime> estimatedCompactionAtIfKnown() {
		return Optional.ofNullable(this.estimatedCompactionAt);
	}

}
