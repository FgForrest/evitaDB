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
 * What one data store holds in memory rather than on disk. Produced by
 * {@link CatalogPersistenceService#measureVolatileData()} for the catalog's own data store and by
 * {@link EntityCollectionPersistenceService#measureVolatileData()} for one collection's.
 *
 * The three quantities answer different questions and are deliberately not derived from one another:
 * `totalSizeIncludingVolatileDataBytes` is what the store would occupy if everything in flight were flushed right now,
 * `nonFlushedRecordCount`/`nonFlushedSizeBytes` are the in-flight part of that alone, and
 * `oldestRecordKeptTimestamp` is about a different retention entirely - the multi-version history the store cannot
 * release while an old session is still reading it.
 *
 * **Every field is a snapshot of something a concurrent writer may already have moved on from.** That is inherent to
 * asking what is in flight, and it is why nothing downstream may assert agreement between these numbers and any
 * separately-taken measurement.
 *
 * @param totalSizeIncludingVolatileDataBytes bytes the store occupies including the records not yet flushed
 * @param nonFlushedRecordCount               records written but not yet flushed
 * @param nonFlushedSizeBytes                 bytes those records occupy
 * @param oldestRecordKeptTimestamp           promotion time of the oldest historical version still retained for an
 *                                            open session; `null` when only the current version is kept
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record VolatileDataFootprint(
	long totalSizeIncludingVolatileDataBytes,
	int nonFlushedRecordCount,
	long nonFlushedSizeBytes,
	@Nullable OffsetDateTime oldestRecordKeptTimestamp
) {

	/**
	 * The footprint of a data store holding nothing in flight and retaining no history - also the identity element of
	 * {@link #plus(VolatileDataFootprint)}.
	 */
	public static final VolatileDataFootprint EMPTY = new VolatileDataFootprint(0L, 0, 0L, null);

	/**
	 * Combines this footprint with another one, which is how a catalog-wide figure is assembled from the per-store
	 * ones. The sizes and counts add up; the retained-history timestamp does **not** - the catalog is holding history
	 * back as far as its *oldest* retaining store, so the earlier of the two timestamps wins and a store retaining
	 * nothing contributes nothing rather than resetting the answer.
	 *
	 * The combiner lives here rather than at the call site so that the timestamp rule cannot be written one way for
	 * one aggregation and another way for the next.
	 *
	 * @param other the footprint to add to this one
	 * @return the combined footprint
	 */
	@Nonnull
	public VolatileDataFootprint plus(@Nonnull VolatileDataFootprint other) {
		final OffsetDateTime oldest;
		if (this.oldestRecordKeptTimestamp == null) {
			oldest = other.oldestRecordKeptTimestamp;
		} else if (other.oldestRecordKeptTimestamp == null) {
			oldest = this.oldestRecordKeptTimestamp;
		} else {
			oldest = this.oldestRecordKeptTimestamp.isBefore(other.oldestRecordKeptTimestamp) ?
				this.oldestRecordKeptTimestamp : other.oldestRecordKeptTimestamp;
		}
		return new VolatileDataFootprint(
			this.totalSizeIncludingVolatileDataBytes + other.totalSizeIncludingVolatileDataBytes,
			this.nonFlushedRecordCount + other.nonFlushedRecordCount,
			this.nonFlushedSizeBytes + other.nonFlushedSizeBytes,
			oldest
		);
	}

}
