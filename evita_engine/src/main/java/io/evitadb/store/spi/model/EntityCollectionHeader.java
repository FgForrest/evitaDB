/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.store.model.PersistentStorageDescriptor;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static java.util.Optional.ofNullable;

/**
 * Catalog header contains crucial information to read data from a single data storage file. The catalog header needs
 * to be stored in {@link CatalogBootstrap} and maps the data maintained by {@link EntityCollection} objects.
 *
 * @see PersistentStorageHeader
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
public class EntityCollectionHeader extends PersistentStorageHeader {
	@Serial private static final long serialVersionUID = 1079906797886901404L;

	/**
	 * Type of the entity - {@link EntitySchema#getName()}.
	 */
	@Getter private final String entityType;
	/**
	 * Contains a unique identifier of the entity type that is assigned on entity collection creation and never changes.
	 * The primary key can be used interchangeably to {@link EntitySchema#getName() String entity type}.
	 */
	@Getter private final int entityTypePrimaryKey;
	/**
	 * Contains information about the number of entities in the collection. Servers for informational purposes.
	 */
	@Getter private final int recordCount;
	/**
	 * Contains {@link io.evitadb.index.EntityIndex} id that belongs to the {@link EntityIndexType#GLOBAL} and is
	 * stored in MemTable.
	 */
	@Getter private final Integer globalEntityIndexId;
	/**
	 * Contains list of unique {@link io.evitadb.index.EntityIndex} ids that are stored in MemTable.
	 */
	@Getter private final List<Integer> usedEntityIndexIds;
	/**
	 * Contains last primary key used by {@link EntityCollection} - but only in case that Evita assignes
	 * new primary keys to the entities. New entity will obtain PK = `lastPrimaryKey` + 1.
	 */
	@Getter private final int lastPrimaryKey;
	/**
	 * Contains last primary key used by {@link io.evitadb.index.EntityIndex}. New entity indexes will obtain
	 * PK = `lastPrimaryKey` + 1.
	 */
	@Getter private final int lastEntityIndexPrimaryKey;
	/**
	 * Contains last assigned id in {@link #getCompressedKeys()}. Newly registered key will obtain ID = `lastKeyId` + 1.
	 */
	@Getter private final int lastKeyId;

	public EntityCollectionHeader(@Nonnull String entityType, int entityTypePrimaryKey) {
		this(
			entityType, entityTypePrimaryKey,
			0, 0, 0,
			null, null, Collections.emptyList()
		);
	}

	public EntityCollectionHeader(
		@Nonnull String entityType,
		int entityTypePrimaryKey,
		int recordCount,
		int lastPrimaryKey,
		int lastEntityIndexPrimaryKey,
		@Nullable PersistentStorageDescriptor storageDescriptor,
		@Nullable Integer globalIndexId,
		@Nonnull List<Integer> entityIndexIds
	) {
		super(
			ofNullable(storageDescriptor).map(PersistentStorageDescriptor::getVersion).orElse(1L),
			ofNullable(storageDescriptor).map(PersistentStorageDescriptor::getFileLocation).orElse(null),
			ofNullable(storageDescriptor)
				.map(PersistentStorageDescriptor::getCompressedKeys)
				.map(Collections::unmodifiableMap)
				.orElseGet(Collections::emptyMap)
		);
		this.entityType = entityType;
		this.entityTypePrimaryKey = entityTypePrimaryKey;
		this.recordCount = recordCount;
		this.globalEntityIndexId = globalIndexId;
		this.usedEntityIndexIds = entityIndexIds;
		this.lastPrimaryKey = lastPrimaryKey;
		this.lastEntityIndexPrimaryKey = lastEntityIndexPrimaryKey;
		this.lastKeyId = this.compressedKeys.keySet().stream().max(Comparator.comparingInt(o -> o)).orElse(1);
	}

	public EntityCollectionHeader(
		@Nonnull String entityType,
		int entityTypePrimaryKey,
		@Nullable EntityCollectionHeader originalHeader
	) {
		super(
			ofNullable(originalHeader).map(PersistentStorageDescriptor::getVersion).orElse(1L),
			ofNullable(originalHeader).map(PersistentStorageDescriptor::getFileLocation).orElse(null),
			ofNullable(originalHeader)
				.map(PersistentStorageDescriptor::getCompressedKeys)
				.map(Collections::unmodifiableMap)
				.orElseGet(Collections::emptyMap)
		);
		this.entityType = entityType;
		this.entityTypePrimaryKey = entityTypePrimaryKey;
		this.recordCount = ofNullable(originalHeader).map(EntityCollectionHeader::getRecordCount).orElse(0);
		this.globalEntityIndexId = ofNullable(originalHeader).map(it -> it.globalEntityIndexId).orElse(null);
		this.usedEntityIndexIds = ofNullable(originalHeader).map(EntityCollectionHeader::getUsedEntityIndexIds).orElse(Collections.emptyList());
		this.lastPrimaryKey = ofNullable(originalHeader).map(EntityCollectionHeader::getLastPrimaryKey).orElse(1);
		this.lastEntityIndexPrimaryKey = ofNullable(originalHeader).map(EntityCollectionHeader::getLastEntityIndexPrimaryKey).orElse(1);
		this.lastKeyId = ofNullable(originalHeader).map(it -> it.lastKeyId).orElse(1);
	}
}
