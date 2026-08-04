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
 * The {@link CatalogStatisticsComponent#VOLATILE_STATE} component - what is held in memory but not yet on disk, and
 * what is being kept alive purely for readers that started long ago.
 *
 * `oldestRecordKeptTimestamp` is the one to watch: the multi-version history retained for still-open sessions is the
 * part of the heap that grows silently. A timestamp that keeps receding means a session is holding a view of the data
 * that the engine cannot let go of.
 *
 * **Reading for a degraded catalog**
 *
 * Not delivered for an unusable catalog - there is no in-memory state to report.
 *
 * **Per collection**
 *
 * The same state for one collection's data store is fetched separately - see {@link CollectionVolatileState}.
 *
 * @param totalSizeIncludingVolatileDataBytes bytes the data stores occupy including data not yet flushed
 * @param nonFlushedRecordCount               records written but not yet flushed to disk
 * @param nonFlushedSizeBytes                 bytes those records occupy
 * @param oldestRecordKeptTimestamp           creation time of the oldest record kept alive for an open session; null
 *                                            when nothing is being retained
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record VolatileStateStatistics(
	long totalSizeIncludingVolatileDataBytes,
	int nonFlushedRecordCount,
	long nonFlushedSizeBytes,
	@Nullable OffsetDateTime oldestRecordKeptTimestamp
) {

	/**
	 * Returns the creation time of the oldest record retained for a still-open session.
	 *
	 * @return the timestamp, empty when nothing is being retained
	 */
	@Nonnull
	public Optional<OffsetDateTime> oldestRecordKeptTimestampIfAny() {
		return Optional.ofNullable(this.oldestRecordKeptTimestamp);
	}

}
