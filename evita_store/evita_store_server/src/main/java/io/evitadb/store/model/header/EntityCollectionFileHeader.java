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

package io.evitadb.store.model.header;

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.spi.store.catalog.header.model.EntityCollectionHeader;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.store.shared.model.PersistentStorageDescriptor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collections;
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
 *                                    {@link KeyCompressor} for file offset index deserialization.
 * @param lastKeyId                   Contains last assigned id in {@link PersistentStorageDescriptor#compressedKeys()}.
 *                                    Newly registered key will obtain ID = `lastKeyId` + 1.
 * @param lastModifiedMillis          Wall-clock time this header was written, in epoch milliseconds, or
 *                                    {@link #NOT_STAMPED} when it predates 2026.3 and carries no timestamp. The
 *                                    header is rewritten by every flush that changes the collection and by every
 *                                    compaction of it, so this answers "when did anything last change here" - a
 *                                    question the monotonic {@link #version()} cannot. It is deliberately not
 *                                    `File.lastModified()` of the data store file: that survives no restore (a
 *                                    restored catalog would report the restore as its last write) and compaction
 *                                    moves it without any logical change.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see PersistentStorageHeader
 */
public record EntityCollectionFileHeader(
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
	double activeRecordShare,
	long lastModifiedMillis
) implements PersistentStorageDescriptor, EntityCollectionHeader {
	@Serial private static final long serialVersionUID = 7284410593068317745L;

	/**
	 * Value of {@link #lastModifiedMillis()} meaning *no timestamp was recorded*. Headers written before 2026.3 carry
	 * no timestamp at all, and {@link io.evitadb.store.catalog.serializer.EntityCollectionHeaderSerializer_2026_2}
	 * reconstructs them with this value; a reader must therefore treat it as *unknown*, never as the epoch.
	 */
	public static final long NOT_STAMPED = 0L;

	/**
	 * Exposes `compressedKeys` as an unmodifiable view so the record's accessor cannot be used to mutate the
	 * underlying map. All known callers either build a private `HashMap` that is never retained after hand-off
	 * (deserializers) or pass a view over a backing map that is frozen after its owner's construction
	 * ({@code ReadOnlyKeyCompressor}), so an O(1) wrap is sufficient — a full copy would not add protection.
	 */
	public EntityCollectionFileHeader {
		compressedKeys = Collections.unmodifiableMap(compressedKeys);
	}

	public EntityCollectionFileHeader(@Nonnull String entityType, int entityTypePrimaryKey, int entityTypeFileIndex) {
		this(
			entityType, entityTypePrimaryKey,
			entityTypeFileIndex, 0, 0, 0, -1, 0.0,
			null, null, Collections.emptyList(),
			// a collection that has never been written has nothing to timestamp; the first flush stamps it
			NOT_STAMPED
		);
	}

	public EntityCollectionFileHeader(
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
		@Nonnull List<Integer> entityIndexIds,
		long lastModifiedMillis
	) {
		this(
			ofNullable(storageDescriptor).map(PersistentStorageDescriptor::version).orElse(1L),
			ofNullable(storageDescriptor).map(PersistentStorageDescriptor::fileLocation).orElse(FileLocation.EMPTY),
			ofNullable(storageDescriptor)
				.map(PersistentStorageDescriptor::compressedKeys)
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
			storageDescriptor == null ? 1 : storageDescriptor.peakCompressedKeyId(),
			activeRecordShare,
			lastModifiedMillis
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
	public int peakCompressedKeyId() {
		// `lastKeyId` is already serialized as part of this record and is always the peak id at the moment
		// this header was constructed — reuse it directly instead of rescanning `compressedKeys`
		return this.lastKeyId;
	}

	/**
	 * Compares the header's *identity and contents*, deliberately ignoring `activeRecordShare` and
	 * `lastModifiedMillis`. Both are measurements taken while the header was written rather than part of what the
	 * header addresses, and folding a wall clock into equality would make two headers describing identical data
	 * unequal for no reason a caller could act on. `activeRecordShare` has been excluded since the field was
	 * introduced; the timestamp follows it.
	 */
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof final EntityCollectionFileHeader that)) return false;

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
