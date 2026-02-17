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
 * Provides aggregated statistical information about a catalog instance, including its current state, size metrics,
 * and per-collection statistics. This record is primarily used for monitoring, management dashboards, and system
 * health checks.
 *
 * **Data Availability**
 *
 * When `unusable` is true (catalog is corrupted), most fields will contain placeholder or null values:
 * - `catalogState` will be null
 * - `catalogVersion`, `totalRecords`, `indexCount` will be -1
 * - `entityCollectionStatistics` will be an empty array
 * - Only `catalogId`, `catalogName`, `readOnly`, and `sizeOnDiskInBytes` remain valid
 *
 * **Usage Context**
 *
 * This record is returned by:
 * - {@link CatalogContract#getStatistics()} to get statistics for a single catalog
 * - {@link EvitaManagementContract#getCatalogStatistics()} to get statistics for all catalogs in the instance
 *
 * **Thread-Safety**
 *
 * This record is immutable and thread-safe. Statistics represent a snapshot at the time of retrieval and may
 * become stale as the catalog is modified.
 *
 * @param catalogId                  unique identifier of the catalog, null only for corrupted catalogs where ID cannot be determined
 * @param catalogName                name of the catalog, always present even for corrupted catalogs
 * @param unusable                   true if the catalog is corrupted and cannot be loaded (state is {@link CatalogState#CORRUPTED})
 * @param readOnly                   true if the catalog is in read-only mode and cannot accept mutations
 * @param catalogState               current operational state of the catalog, null only when unusable is true
 * @param catalogVersion             current version number of the catalog, incremented with each mutation, -1 for corrupted catalogs
 * @param totalRecords               total number of entity records across all collections in the catalog, -1 for corrupted catalogs
 * @param indexCount                 total number of indexes (attribute, reference, hierarchy) across all collections, -1 for corrupted catalogs
 * @param sizeOnDiskInBytes          total disk space consumed by all catalog data files in bytes
 * @param entityCollectionStatistics per-collection statistics for each entity type in the catalog, empty array for
 *                                   corrupted catalogs
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
		return this.indexCount == that.indexCount && this.unusable == that.unusable && this.totalRecords == that.totalRecords && this.catalogVersion == that.catalogVersion && this.sizeOnDiskInBytes == that.sizeOnDiskInBytes && this.catalogName.equals(
			that.catalogName) && this.catalogState == that.catalogState && Arrays.equals(
			this.entityCollectionStatistics, that.entityCollectionStatistics);
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
	 * Provides statistical information for a single entity collection within a catalog. Each entity collection
	 * represents a distinct entity type (analogous to a table in relational databases or a document type in NoSQL).
	 *
	 * **Usage Context**
	 *
	 * These statistics are embedded within {@link CatalogStatistics#entityCollectionStatistics()} to provide
	 * per-collection breakdowns of catalog metrics.
	 *
	 * **Thread-Safety**
	 *
	 * This record is immutable and thread-safe. Values represent a snapshot at the time of retrieval.
	 *
	 * @param entityType        unique name of the entity collection, corresponds to {@link EntityCollectionContract#getEntityType()}
	 * @param totalRecords      total number of entity records stored in this collection
	 * @param indexCount        total number of indexes created for this collection (includes attribute indexes, reference indexes,
	 *                          hierarchy indexes, and other specialized indexes)
	 * @param sizeOnDiskInBytes total disk space consumed by this collection's data files in bytes
	 */
	public record EntityCollectionStatistics(
		@Nonnull String entityType,
		int totalRecords,
		int indexCount,
		long sizeOnDiskInBytes
	) {

	}

}
