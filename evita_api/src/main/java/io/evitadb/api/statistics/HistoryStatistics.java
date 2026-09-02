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
 * The {@link CatalogStatisticsComponent#HISTORY} component - how far back in time the catalog can be read, what that
 * costs on disk, and what is stopping superseded files from going away.
 *
 * **The active reader floor is the actionable number here.** It is the oldest catalog version still referenced by an
 * open reader or writer. Superseded files at a version above it cannot be deleted in either purge mode, so a floor
 * that stops advancing is the direct explanation for disk space that will not come back. evitaLab surfaces it as
 * *deletion floor*; the two names are deliberately mapped to each other rather than left to drift apart.
 *
 * **A floor of `0` means nothing is pinned, not that something is pinned at version zero.** The floor is only raised
 * when consumers of a version leave, so it stays `0` on a catalog no session has finished reading yet - which blocks
 * nothing, and is why `blockedByActiveReaderBytes` can be `0` while `awaitingDeletionBytes` is not.
 *
 * **Cost** - bounded file IO. Determining the time-travel window is a seek-read of the bootstrap file, which is why
 * it belongs here and not in {@link CatalogIdentity}.
 *
 * **Reading for a degraded catalog**
 *
 * Not delivered for an unusable catalog. Every other reading holds in both retention modes: the write-ahead log is
 * trimmed to a fixed number of files whether or not time travel is on, so `walFileCount` and `walBytes` are non-zero
 * either way - time travel widens the retained window rather than creating it - and superseded files exist in both
 * modes too. What genuinely differs is the *window*: see `oldestAvailableCatalogVersion`.
 *
 * @param timeTravelEnabled            true when WAL retention keeps history available for time travel
 * @param oldestAvailableCatalogVersion oldest catalog version whose data is still readable. With time travel disabled
 *                                     this equals `newestCatalogVersion`: obsolete data files are purged against the
 *                                     current header, so nothing older survives - and the bootstrap file, which is
 *                                     never trimmed, still *lists* those older versions even though they can no
 *                                     longer be read. `-1` only when the window could not be determined at all
 * @param oldestAvailableTimestamp     wall-clock time of `oldestAvailableCatalogVersion`; null when unknown
 * @param newestCatalogVersion         newest catalog version; `-1` when unknown
 * @param newestTimestamp              wall-clock time of `newestCatalogVersion`; null when unknown
 * @param walFileCount                 number of retained write-ahead log files
 * @param walBytes                     total bytes of the retained write-ahead log files
 * @param activeReaderFloor            oldest catalog version still referenced by an open reader or writer; `0` means
 *                                     none has been observed yet and therefore that nothing is pinned
 * @param awaitingDeletionFileCount    number of superseded data files not yet purged
 * @param awaitingDeletionBytes        total bytes of those files
 * @param blockedByActiveReaderBytes   part of them pinned at or above `activeReaderFloor`
 * @param purgeableBytes               part of them nothing blocks, waiting only on the purge mechanism
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record HistoryStatistics(
	boolean timeTravelEnabled,
	long oldestAvailableCatalogVersion,
	@Nullable OffsetDateTime oldestAvailableTimestamp,
	long newestCatalogVersion,
	@Nullable OffsetDateTime newestTimestamp,
	int walFileCount,
	long walBytes,
	long activeReaderFloor,
	int awaitingDeletionFileCount,
	long awaitingDeletionBytes,
	long blockedByActiveReaderBytes,
	long purgeableBytes
) {

	/**
	 * Returns the wall-clock time of the oldest readable catalog version.
	 *
	 * @return timestamp of the start of the time-travel window, empty when no history is retained
	 */
	@Nonnull
	public Optional<OffsetDateTime> oldestAvailableTimestampIfKnown() {
		return Optional.ofNullable(this.oldestAvailableTimestamp);
	}

	/**
	 * Returns the wall-clock time of the newest catalog version.
	 *
	 * @return timestamp of the end of the time-travel window, empty when it could not be determined
	 */
	@Nonnull
	public Optional<OffsetDateTime> newestTimestampIfKnown() {
		return Optional.ofNullable(this.newestTimestamp);
	}

}
