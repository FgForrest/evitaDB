/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import java.util.UUID;

/**
 * Aggregates basic data about the catalog and entity types stored in it.
 *
 * @param catalogId unique identifier of the catalog
 * @param catalogName name of the catalog
 * @param unusable true if the catalog is corrupted (other data will be not available)
 * @param readOnly true if the catalog is read-only (no mutations are allowed)
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
	@Nullable UUID catalogId,
	@Nonnull String catalogName,
	boolean unusable,
	boolean readOnly,
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
		return this.indexCount == that.indexCount && this.unusable == that.unusable && this.totalRecords == that.totalRecords && this.catalogVersion == that.catalogVersion && this.sizeOnDiskInBytes == that.sizeOnDiskInBytes && this.catalogName.equals(that.catalogName) && this.catalogState == that.catalogState && Arrays.equals(this.entityCollectionStatistics, that.entityCollectionStatistics);
	}

	@Override
	public int hashCode() {
		int result = this.catalogName.hashCode();
		result = 31 * result + Boolean.hashCode(this.unusable);
		result = 31 * result + Objects.hashCode(this.catalogState);
		result = 31 * result + Long.hashCode(this.catalogVersion);
		result = 31 * result + Long.hashCode(this.totalRecords);
		result = 31 * result + Long.hashCode(this.indexCount);
		result = 31 * result + Long.hashCode(this.sizeOnDiskInBytes);
		result = 31 * result + Arrays.hashCode(this.entityCollectionStatistics);
		return result;
	}

	@Nonnull
	@Override
	public String toString() {
		return "CatalogStatistics{" +
			"catalogName='" + this.catalogName + '\'' +
			", corrupted=" + this.unusable +
			", catalogState=" + this.catalogState +
			", catalogVersion=" + this.catalogVersion +
			", totalRecords=" + this.totalRecords +
			", indexCount=" + this.indexCount +
			", sizeOnDiskInBytes=" + this.sizeOnDiskInBytes +
			", entityCollectionStatistics=" + Arrays.toString(this.entityCollectionStatistics) +
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
