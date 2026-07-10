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
import io.evitadb.spi.store.catalog.persistence.storageParts.RecordWithCompressedId;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
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
	 * `true` for OWNER (standalone) parts that carry the inline {@link #values} / {@link #recordIds} columns; `false`
	 * for slim VIEW parts whose data lives in the shared filter tree.
	 */
	@Getter private final boolean dataPresent;
	/**
	 * The `SINGLE`-shape value column: the indexed unique values in ascending key order, positionally aligned with
	 * {@link #recordIds}. `null` for slim VIEW parts and for `PAGED` OWNER parts (the entries live in
	 * {@link UniqueIndexLeafPagePart} leaf pages). The same column shape a leaf page carries — the small index is simply a
	 * single embedded leaf kept inline on the root rather than paged out to a separate part.
	 */
	@Getter @Nullable private final Serializable[] values;
	/**
	 * The `SINGLE`-shape payload column: the single record id owning each value, positionally aligned with
	 * {@link #values}. `null` for slim VIEW parts and for `PAGED` OWNER parts. The membership record-id set is exactly the
	 * (deduplicated) set of these ids, so it is NOT persisted separately — it is rebuilt from this column on load.
	 */
	@Getter @Nullable private final int[] recordIds;
	/**
	 * The `PAGED`/`SINGLE` discriminator for an OWNER part. When `true` the value-to-record bucket tree is persisted as
	 * individual {@link UniqueIndexLeafPagePart} leaf pages keyed by `pack(streamId, pageSequence)` and
	 * {@link #values} / {@link #recordIds} are `null`; when `false` (the small-index case) every entry
	 * lives inline in the {@link #values} / {@link #recordIds} columns. Always `false` for slim VIEW parts. The page stream id is
	 * deliberately NOT persisted here — it is the {@link LeafStreamKey}'s compressed id, recomputed at load from the
	 * sub-index identity via the catalog's read-only {@code KeyCompressor}.
	 */
	@Getter private final boolean paged;
	/**
	 * The high-water `pageSequence` of the stream (the maximum `pageSequence` ever allocated) for a `PAGED` OWNER part;
	 * `-1` otherwise. Persisted explicitly rather than derived as `max(pageSequence)` over live pages, so a freed max
	 * page cannot let a reused id be handed out while an older catalog version still references it.
	 */
	@Getter private final int highWaterPageSequence;
	/**
	 * The leaf pages of a `PAGED` OWNER part, listed in ascending key order — exactly the order in which the load path
	 * reads them back and reassembles the spine (the spine is NOT persisted; it is reconstructed at load). Empty
	 * otherwise.
	 */
	@Nonnull @Getter private final int[] leafPageSequences;
	/**
	 * Id used for lookups in data storage for this particular container.
	 */
	@Nullable @Getter @Setter private Long storagePartPK;

	/**
	 * Builds a full OWNER (`SINGLE`) part carrying the inline value/payload columns.
	 */
	public UniqueIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> type,
		@Nonnull Serializable[] values,
		@Nonnull int[] recordIds
	) {
		this(entityIndexPrimaryKey, attributeIndexKey, type, values, recordIds, null);
	}

	/**
	 * Builds a full OWNER (`SINGLE`) part carrying the inline value/payload columns, with a pre-computed storage part id
	 * (load path).
	 */
	public UniqueIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> type,
		@Nonnull Serializable[] values,
		@Nonnull int[] recordIds,
		@Nullable Long storagePartPK
	) {
		this(
			entityIndexPrimaryKey, attributeIndexKey, type, true,
			values, recordIds, false, -1, ArrayUtils.EMPTY_INT_ARRAY, storagePartPK
		);
	}

	/**
	 * Builds a `PAGED` OWNER part: the value-to-record entries live in {@link UniqueIndexLeafPagePart} leaf pages, so the
	 * root carries the explicit high-water `pageSequence` and the ordered leaf-page list (ascending key order) but NO
	 * inline map / bitmap and NO page-stream id (it is recomputed at load from the sub-index identity — see
	 * {@link #paged}).
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param attributeIndexKey     the attribute key
	 * @param type                  the indexed value type
	 * @param highWaterPageSequence the maximum `pageSequence` ever allocated for the stream
	 * @param leafPageSequences     the leaf pages in ascending key order
	 * @param storagePartPK         the already-assigned storage part PK, or `null`
	 * @return the paged owner unique index storage part
	 */
	@Nonnull
	public static UniqueIndexStoragePart paged(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> type,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences,
		@Nullable Long storagePartPK
	) {
		return new UniqueIndexStoragePart(
			entityIndexPrimaryKey, attributeIndexKey, type, true,
			null, null, true, highWaterPageSequence, leafPageSequences, storagePartPK
		);
	}

	/**
	 * Canonical constructor carrying every field — the OWNER/VIEW (`dataPresent`) discriminator, the optional inline
	 * value/payload columns, the `PAGED`/`SINGLE` page metadata and the already-assigned storage part PK.
	 */
	private UniqueIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> type,
		boolean dataPresent,
		@Nullable Serializable[] values,
		@Nullable int[] recordIds,
		boolean paged,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences,
		@Nullable Long storagePartPK
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeIndexKey = attributeIndexKey;
		// the attribute type is a non-null invariant: the only format that ever stored a `null` type was the dropped
		// legacy unique serializer, so a part can no longer be built without a concrete type
		this.type = Objects.requireNonNull(type, "attributeType is marked non-null but is null");
		this.dataPresent = dataPresent;
		this.values = values;
		this.recordIds = recordIds;
		this.paged = paged;
		this.highWaterPageSequence = highWaterPageSequence;
		this.leafPageSequences = leafPageSequences;
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
		this.values = null;
		this.recordIds = null;
		this.paged = false;
		this.highWaterPageSequence = -1;
		this.leafPageSequences = ArrayUtils.EMPTY_INT_ARRAY;
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
