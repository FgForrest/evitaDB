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

/**
 * How much of a data store one storage-part type occupies. Shared by the catalog-level
 * {@link StorageCompositionStatistics} and the collection-level {@link CollectionStorageComposition}, because the
 * histogram has the same shape whichever data store it was read from.
 *
 * **Measured on the flushed state.** Records written but not yet flushed to the data store file count towards
 * neither `count` nor `totalBytes`. That is the correct reading for a breakdown whose question is where the bytes on
 * disk went - but it does mean this `count` can trail the record counts of
 * {@link CatalogStatisticsComponent#RECORD_COUNTS}, which include in-flight data, while writes are pending. The two
 * are not expected to agree except immediately after a flush.
 *
 * @param storagePartType simple class name of the storage part, e.g. `EntityBodyStoragePart`,
 *                        `AttributesStoragePart`, `AssociatedDataStoragePart`
 * @param count           number of records of this type currently held
 * @param totalBytes      total bytes those records occupy
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record StoragePartUsage(
	@Nonnull String storagePartType,
	int count,
	long totalBytes
) {

	/**
	 * Mean size of a record of this storage-part type. Exact, because both operands are exact.
	 *
	 * @return `totalBytes / count`, or `0` when no record of this type is held
	 */
	public long averageBytes() {
		return this.count == 0 ? 0L : this.totalBytes / this.count;
	}

}
