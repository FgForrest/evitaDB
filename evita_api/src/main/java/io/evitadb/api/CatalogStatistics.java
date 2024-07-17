/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Aggregates basic data about the catalog and entity types stored in it.
 *
 * @param catalogName name of the catalog
 * @param corrupted true if the catalog is corrupted (other data will be not available)
 * @param catalogState current state of the catalog, null for corrupted catalog
 * @param catalogVersion version of the catalog, -1 for corrupted catalog
 * @param totalRecords total number of records in the catalog, -1 for corrupted catalog
 * @param indexCount total number of indexes in the catalog, -1 for corrupted catalog
 * @param sizeOnDiskInBytes total size of the catalog on disk in bytes
 * @param entityCollectionStatistics statistics for each entity collection in the catalog, empty array for corrupted catalog
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record CatalogStatistics(
	@Nonnull String catalogName,
	boolean corrupted,
	@Nullable CatalogState catalogState,
	long catalogVersion,
	long totalRecords,
	long indexCount,
	long sizeOnDiskInBytes,
	@Nonnull EntityCollectionStatistics[] entityCollectionStatistics
) {

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		CatalogStatistics that = (CatalogStatistics) o;
		return indexCount == that.indexCount && corrupted == that.corrupted && totalRecords == that.totalRecords && catalogVersion == that.catalogVersion && sizeOnDiskInBytes == that.sizeOnDiskInBytes && catalogName.equals(that.catalogName) && catalogState == that.catalogState && Arrays.equals(entityCollectionStatistics, that.entityCollectionStatistics);
	}

	@Override
	public int hashCode() {
		int result = catalogName.hashCode();
		result = 31 * result + Boolean.hashCode(corrupted);
		result = 31 * result + Objects.hashCode(catalogState);
		result = 31 * result + Long.hashCode(catalogVersion);
		result = 31 * result + Long.hashCode(totalRecords);
		result = 31 * result + Long.hashCode(indexCount);
		result = 31 * result + Long.hashCode(sizeOnDiskInBytes);
		result = 31 * result + Arrays.hashCode(entityCollectionStatistics);
		return result;
	}

	@Override
	public String toString() {
		return "CatalogStatistics{" +
			"catalogName='" + catalogName + '\'' +
			", corrupted=" + corrupted +
			", catalogState=" + catalogState +
			", catalogVersion=" + catalogVersion +
			", totalRecords=" + totalRecords +
			", indexCount=" + indexCount +
			", sizeOnDiskInBytes=" + sizeOnDiskInBytes +
			", entityCollectionStatistics=" + Arrays.toString(entityCollectionStatistics) +
			'}';
	}

	/**
	 * Aggregates basic data about the entity collection.
	 *
	 * @param entityType name of the entity collection
	 * @param totalRecords total number of records in the entity collection
	 * @param indexCount total number of indexes in the entity collection
	 * @param sizeOnDiskInBytes total size of the entity collection on disk in bytes
	 */
	public record EntityCollectionStatistics(
		@Nonnull String entityType,
		int totalRecords,
		int indexCount,
		long sizeOnDiskInBytes
	) {

	}

}
