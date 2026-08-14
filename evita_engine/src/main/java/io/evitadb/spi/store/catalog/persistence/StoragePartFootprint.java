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
import java.util.Comparator;

/**
 * How much of one data store the records of a single storage-part type occupy. One entry of the breakdown returned by
 * {@link CatalogPersistenceService#measureStoragePartComposition()} and
 * {@link EntityCollectionPersistenceService#measureStoragePartComposition()}.
 *
 * **Measured on the flushed state.** Records written but not yet flushed to the data store file are not counted -
 * neither in `count` nor in `totalBytes`. That is the honest reading for a breakdown of bytes on disk, and it is why
 * `count` here can lag the record counts reported by the statistics components that add in-flight data.
 *
 * **Bytes reconcile with the whole by construction**: the summed `totalBytes` of every entry is exactly the record
 * payload the data store holds, because the per-type figures are accumulated at the same statements as the data
 * store's own total. They are a strict *subset* of the `liveBytes` that `STORAGE_SIZE` reports, which additionally
 * covers the offset-index table the data store keeps to find those records - bookkeeping that belongs to no storage
 * part type and is therefore not attributed to one.
 *
 * @param storagePartType simple class name of the storage part, e.g. `EntityBodyStoragePart`, `AttributesStoragePart`
 * @param count           number of records of this type currently held
 * @param totalBytes      total bytes those records occupy
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record StoragePartFootprint(
	@Nonnull String storagePartType,
	int count,
	long totalBytes
) {

	/**
	 * The order every breakdown is returned in - largest consumer first, ties broken by type name.
	 *
	 * The underlying histogram is a hash map, so without an explicit order the same data would come back in
	 * a different sequence from one call to the next: a management table would reshuffle on every poll, and the
	 * array-based `equals` of the statistics records that carry this breakdown would report two identical
	 * compositions as different. Byte descending is also the order the breakdown is read in - the question it answers
	 * is which type is eating the disk.
	 */
	public static final Comparator<StoragePartFootprint> LARGEST_FIRST =
		Comparator.comparingLong(StoragePartFootprint::totalBytes).reversed()
			.thenComparing(StoragePartFootprint::storagePartType);

}
