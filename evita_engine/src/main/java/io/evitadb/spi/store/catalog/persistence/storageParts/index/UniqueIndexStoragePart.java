/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.RecordWithCompressedId;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/**
 * Unique index container stores index for single {@link AttributeSchema} of the single
 * {@link EntitySchema}. This container object serves only as a storage carrier for
 * {@link io.evitadb.index.attribute.UniqueIndex} which is a live memory representation of the data stored in this
 * container.
 *
 * A unique attribute that is also filterable (or whose uniqueness scope matches its filter key) is folded into the
 * shared `value→ValueToRecord` tree owned by {@link io.evitadb.index.attribute.AttributeIndex}; in that case the
 * {@link io.evitadb.index.attribute.UniqueIndex} runs in VIEW mode and writes a **slim** part carrying no
 * value-to-record map and no record-id bitmap ({@link #dataPresent} is `false`). The slim part still exists so the
 * index manifest records that the attribute key is unique and the view (plus its uniqueness enforcement) is
 * reconstructed on reload from the shared tree. Standalone (OWNER) unique indexes write the full part with
 * {@link #dataPresent} set to `true`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@ToString(of = "attributeIndexKey")
public class UniqueIndexStoragePart implements AttributeIndexStoragePart, RecordWithCompressedId<AttributeIndexKey> {
	@Serial private static final long serialVersionUID = 8200588488685516906L;

	/**
	 * Unique id that identifies {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final Integer entityIndexPrimaryKey;
	/**
	 * Contains name and locale of the indexed attribute.
	 */
	@Getter private final AttributeIndexKey attributeIndexKey;
	/**
	 * Contains type of the attribute.
	 */
	@Getter private final Class<? extends Serializable> type;
	/**
	 * `true` for OWNER (standalone) parts that carry the full {@link #uniqueValueToRecordId} map and
	 * {@link #recordIds} bitmap; `false` for slim VIEW parts whose data lives in the shared filter tree.
	 */
	@Getter private final boolean dataPresent;
	/**
	 * Keeps the unique value to record id mappings. Fairly large HashMap is expected here. `null` for slim VIEW parts.
	 */
	@Getter @Nullable private final Map<Serializable, Integer> uniqueValueToRecordId;
	/**
	 * Keeps information about all record ids present in this index. `null` for slim VIEW parts.
	 */
	@Getter @Nullable private final Bitmap recordIds;
	/**
	 * Id used for lookups in data storage for this particular container.
	 */
	@Nullable @Getter @Setter private Long storagePartPK;

	/**
	 * Builds a full OWNER part carrying the value-to-record map and record-id bitmap.
	 */
	public UniqueIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> type,
		@Nonnull Map<Serializable, Integer> uniqueValueToRecordId,
		@Nonnull Bitmap recordIds
	) {
		this(entityIndexPrimaryKey, attributeIndexKey, type, uniqueValueToRecordId, recordIds, null);
	}

	/**
	 * Builds a full OWNER part carrying the value-to-record map and record-id bitmap, with a pre-computed storage
	 * part id (load path).
	 */
	public UniqueIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> type,
		@Nonnull Map<Serializable, Integer> uniqueValueToRecordId,
		@Nonnull Bitmap recordIds,
		@Nullable Long storagePartPK
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeIndexKey = attributeIndexKey;
		// the attribute type is a non-null invariant: the only format that ever stored a `null` type was the dropped
		// legacy unique serializer, so a part can no longer be built without a concrete type
		this.type = Objects.requireNonNull(type, "attributeType is marked non-null but is null");
		this.dataPresent = true;
		this.uniqueValueToRecordId = uniqueValueToRecordId;
		this.recordIds = recordIds;
		this.storagePartPK = storagePartPK;
	}

	/**
	 * Builds a slim VIEW part — no value-to-record map, no record-id bitmap. The data lives in the shared filter tree.
	 */
	public UniqueIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> type
	) {
		this(entityIndexPrimaryKey, attributeIndexKey, type, (Long) null);
	}

	/**
	 * Builds a slim VIEW part — no value-to-record map, no record-id bitmap — with a pre-computed storage part id
	 * (load path).
	 */
	public UniqueIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> type,
		@Nullable Long storagePartPK
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeIndexKey = attributeIndexKey;
		// the attribute type is a non-null invariant even for slim view parts: the only format that ever stored a
		// `null` type was the dropped legacy unique serializer
		this.type = Objects.requireNonNull(type, "attributeType is marked non-null but is null");
		this.dataPresent = false;
		this.uniqueValueToRecordId = null;
		this.recordIds = null;
		this.storagePartPK = storagePartPK;
	}

	@Nonnull
	@Override
	public AttributeIndexType getIndexType() {
		return AttributeIndexType.UNIQUE;
	}

	@Override
	public AttributeIndexKey getStoragePartSourceKey() {
		return this.attributeIndexKey;
	}

}
