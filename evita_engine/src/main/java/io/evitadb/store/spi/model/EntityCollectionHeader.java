/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.spi.model;

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.EntityCollection;
import io.evitadb.index.EntityIndexType;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.model.PersistentStorageDescriptor;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * Catalog header contains crucial information to read data from a single data storage file. The catalog header needs
 * to be stored in catalog file and maps the data maintained by {@link EntityCollection} objects.
 *
 * @param entityType                  Type of the entity - {@link EntitySchema#getName()}.
 * @param entityTypePrimaryKey        Contains a unique identifier of the entity type that is assigned on entity
 *                                    collection creation and never changes.
 *                                    The primary key can be used interchangeably to
 *                                    {@link EntitySchema#getName() String entity type}.
 * @param entityTypeFileIndex         Contains index of an entity collection file where the collection contents are stored.
 * @param recordCount                 Contains information about the number of entities in the collection. Servers for
 *                                    informational purposes.
 * @param globalEntityIndexPrimaryKey Contains {@link io.evitadb.index.EntityIndex} id that belongs to the
 *                                    {@link EntityIndexType#GLOBAL} and is stored in file offset index.
 * @param usedEntityIndexPrimaryKeys  Contains list of unique {@link io.evitadb.index.EntityIndex} ids that are stored in
 *                                    file offset index.
 * @param lastPrimaryKey              Contains last primary key used by {@link EntityCollection} - but only in case that
 *                                    Evita assign new primary keys to the entities. New entity will obtain
 *                                    PK = `lastPrimaryKey` + 1.
 * @param lastEntityIndexPrimaryKey   Contains last primary key used by {@link io.evitadb.index.EntityIndex}. New entity
 *                                    indexes will obtain PK = `lastPrimaryKey` + 1.
 * @param storageDescriptor           Contains {@link PersistentStorageDescriptor} that is used to bootstrap
 *                                    {@link io.evitadb.store.service.KeyCompressor} for file offset index deserialization.
 * @param lastKeyId                   Contains last assigned id in {@link PersistentStorageDescriptor#compressedKeys()}.
 *                                    Newly registered key will obtain ID = `lastKeyId` + 1.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see PersistentStorageHeader
 */
public record EntityCollectionHeader(
	long version,
	@Nonnull FileLocation fileLocation,
	@Nonnull Map<Integer, Object> compressedKeys,
	@Nonnull String entityType,
	int entityTypePrimaryKey,
	int entityTypeFileIndex,
	int recordCount,
	int lastPrimaryKey,
	int lastEntityIndexPrimaryKey,
	int lastInternalPriceId,
	@Nullable PersistentStorageDescriptor storageDescriptor,
	@Nullable Integer globalEntityIndexPrimaryKey,
	@Nonnull List<Integer> usedEntityIndexPrimaryKeys,
	int lastKeyId,
	double activeRecordShare
) implements PersistentStorageDescriptor, StoragePart, Serializable {
	@Serial private static final long serialVersionUID = -2149051526452828365L;

	public EntityCollectionHeader(@Nonnull String entityType, int entityTypePrimaryKey, int entityTypeFileIndex) {
		this(
			entityType, entityTypePrimaryKey,
			entityTypeFileIndex, 0, 0, 0, -1, 0.0,
			null, null, Collections.emptyList()
		);
	}

	public EntityCollectionHeader(
		@Nonnull String entityType,
		int entityTypePrimaryKey,
		int entityTypeFileIndex,
		int recordCount,
		int lastPrimaryKey,
		int lastEntityIndexPrimaryKey,
		int lastInternalPriceId,
		double activeRecordShare,
		@Nullable PersistentStorageDescriptor storageDescriptor,
		@Nullable Integer globalIndexId,
		@Nonnull List<Integer> entityIndexIds
	) {
		this(
			ofNullable(storageDescriptor).map(PersistentStorageDescriptor::version).orElse(1L),
			ofNullable(storageDescriptor).map(PersistentStorageDescriptor::fileLocation).orElse(FileLocation.EMPTY),
			ofNullable(storageDescriptor)
				.map(PersistentStorageDescriptor::compressedKeys)
				.map(Collections::unmodifiableMap)
				.orElseGet(Collections::emptyMap),
			entityType,
			entityTypePrimaryKey,
			entityTypeFileIndex,
			recordCount,
			lastPrimaryKey,
			lastEntityIndexPrimaryKey,
			lastInternalPriceId,
			storageDescriptor,
			globalIndexId,
			entityIndexIds,
			storageDescriptor == null ?
				1 :
				storageDescriptor.compressedKeys()
				                 .keySet()
				                 .stream()
				                 .max(Comparator.comparingInt(o -> o))
				                 .orElse(1),
			activeRecordShare
		);
	}

	@Nonnull
	@Override
	public Long getStoragePartPK() {
		return (long) this.entityTypePrimaryKey;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return this.entityTypePrimaryKey;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof final EntityCollectionHeader that)) return false;

		return this.version == that.version &&
			this.lastKeyId == that.lastKeyId &&
			this.recordCount == that.recordCount &&
			this.lastPrimaryKey == that.lastPrimaryKey &&
			this.entityTypeFileIndex == that.entityTypeFileIndex &&
			this.lastInternalPriceId == that.lastInternalPriceId &&
			this.entityTypePrimaryKey == that.entityTypePrimaryKey &&
			this.lastEntityIndexPrimaryKey == that.lastEntityIndexPrimaryKey &&
			this.entityType.equals(that.entityType) &&
			this.fileLocation.equals(that.fileLocation) &&
			Objects.equals(this.globalEntityIndexPrimaryKey, that.globalEntityIndexPrimaryKey) &&
			this.usedEntityIndexPrimaryKeys.equals(that.usedEntityIndexPrimaryKeys) &&
			this.compressedKeys.equals(that.compressedKeys) &&
			Objects.equals(this.storageDescriptor, that.storageDescriptor);
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(this.version);
		result = 31 * result + this.fileLocation.hashCode();
		result = 31 * result + this.compressedKeys.hashCode();
		result = 31 * result + this.entityType.hashCode();
		result = 31 * result + this.entityTypePrimaryKey;
		result = 31 * result + this.entityTypeFileIndex;
		result = 31 * result + this.recordCount;
		result = 31 * result + this.lastPrimaryKey;
		result = 31 * result + this.lastEntityIndexPrimaryKey;
		result = 31 * result + this.lastInternalPriceId;
		result = 31 * result + Objects.hashCode(this.storageDescriptor);
		result = 31 * result + Objects.hashCode(this.globalEntityIndexPrimaryKey);
		result = 31 * result + this.usedEntityIndexPrimaryKeys.hashCode();
		result = 31 * result + this.lastKeyId;
		return result;
	}
}
