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

import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.RecordWithCompressedId;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Map;

/**
 * This storage part implementation persists the root of a {@link ReferenceTypeCardinalityIndex} — the composed-key →
 * cardinality count map together with the `referencedEntityPrimaryKey → reduced-index-PK bitmap` companion map. The
 * cardinality count map is the second-largest churn wall at scale, so it is persisted GRANULARLY: a small index is written inline on
 * this root as positionally-aligned `(keys, payloads)` columns (the SINGLE shape); a large index spans more than one
 * bucket-tree leaf and is paged out as individual {@link ReferenceTypeCardinalityIndexLeafPagePart} records, with this
 * root holding only the high-water and the ordered live leaf-page sequence list (the PAGED shape). The companion
 * `referencedPrimaryKeysIndex` always rides inline on this root in both shapes (it is the smaller of the two members,
 * mirroring how {@link GlobalUniqueIndexStoragePart} keeps its locale map inline).
 *
 * This storage part is created only for persistence purposes; the live structure is the
 * {@link ReferenceTypeCardinalityIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ToString(of = {"entityIndexPrimaryKey", "referenceName", "paged"})
public class ReferenceTypeCardinalityIndexStoragePart implements StoragePart, RecordWithCompressedId<String> {
	// UID bumped for the 2026.2 granular-paging format change (added the PAGED/SINGLE discriminator); the released
	// 2025.x–2026.1 inline format is read by ReferenceTypeCardinalityIndexStoragePartSerializer_2026_1 at the old UID
	@Serial private static final long serialVersionUID = 4729183650284716093L;

	/**
	 * Unique id that identifies {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final int entityIndexPrimaryKey;
	/**
	 * Name of the reference this index is related to.
	 */
	@Nonnull @Getter private final String referenceName;
	/**
	 * Whether the cardinality map is paged out as leaf-page records (`true`) or carried inline on this root (`false`).
	 */
	@Getter private final boolean paged;
	/**
	 * SINGLE shape only: the composed signed `long` keys in ascending key order, positionally aligned with
	 * {@link #payloads}; `null` for a PAGED root.
	 */
	@Nullable @Getter private final long[] keys;
	/**
	 * SINGLE shape only: the cardinality counts (widened to `long`), positionally aligned with {@link #keys}; `null` for a
	 * PAGED root.
	 */
	@Nullable @Getter private final long[] payloads;
	/**
	 * PAGED shape only: the maximum page sequence ever allocated for the cardinality page stream; `0` for a SINGLE root.
	 */
	@Getter private final int highWaterPageSequence;
	/**
	 * PAGED shape only: every live leaf's page sequence in ascending key order (the PAGED root's leaf list); `null` for a
	 * SINGLE root.
	 */
	@Nullable @Getter private final int[] leafPageSequences;
	/**
	 * The `referencedEntityPrimaryKey → reduced-index-PK bitmap` companion map, carried inline on this root in BOTH the
	 * SINGLE and PAGED shapes.
	 */
	@Nonnull @Getter private final Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex;
	/**
	 * Id used for lookups in file offset index for this particular container.
	 */
	@Nullable @Getter @Setter private Long storagePartPK;

	/**
	 * Canonical all-fields constructor.
	 */
	private ReferenceTypeCardinalityIndexStoragePart(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		boolean paged,
		@Nullable long[] keys,
		@Nullable long[] payloads,
		int highWaterPageSequence,
		@Nullable int[] leafPageSequences,
		@Nonnull Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex,
		@Nullable Long storagePartPK
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.referenceName = referenceName;
		this.paged = paged;
		this.keys = keys;
		this.payloads = payloads;
		this.highWaterPageSequence = highWaterPageSequence;
		this.leafPageSequences = leafPageSequences;
		this.referencedPrimaryKeysIndex = referencedPrimaryKeysIndex;
		this.storagePartPK = storagePartPK;
	}

	/**
	 * Creates a write-path SINGLE root carrying the inline cardinality columns and the companion map.
	 *
	 * @param entityIndexPrimaryKey      primary key of the owning entity index
	 * @param referenceName              the reference name of the sub-index
	 * @param keys                       the composed signed `long` keys in ascending key order
	 * @param payloads                   the cardinality counts, aligned with `keys`
	 * @param referencedPrimaryKeysIndex the companion `referencedEntityPrimaryKey → reduced-index-PK bitmap` map
	 */
	public ReferenceTypeCardinalityIndexStoragePart(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		@Nonnull long[] keys,
		@Nonnull long[] payloads,
		@Nonnull Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex
	) {
		this(entityIndexPrimaryKey, referenceName, false, keys, payloads, 0, null, referencedPrimaryKeysIndex, null);
	}

	/**
	 * Creates a read-path SINGLE root with an already-known primary key (used when rehydrating from storage).
	 *
	 * @param entityIndexPrimaryKey      primary key of the owning entity index
	 * @param referenceName              the reference name of the sub-index
	 * @param keys                       the composed signed `long` keys in ascending key order
	 * @param payloads                   the cardinality counts, aligned with `keys`
	 * @param referencedPrimaryKeysIndex the companion `referencedEntityPrimaryKey → reduced-index-PK bitmap` map
	 * @param storagePartPK              the precomputed primary key
	 */
	public ReferenceTypeCardinalityIndexStoragePart(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		@Nonnull long[] keys,
		@Nonnull long[] payloads,
		@Nonnull Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex,
		long storagePartPK
	) {
		this(entityIndexPrimaryKey, referenceName, false, keys, payloads, 0, null, referencedPrimaryKeysIndex, storagePartPK);
	}

	/**
	 * Creates a write-path PAGED root carrying the page-stream metadata and the inline companion map (the cardinality
	 * columns live in separate {@link ReferenceTypeCardinalityIndexLeafPagePart} records).
	 *
	 * @param entityIndexPrimaryKey      primary key of the owning entity index
	 * @param referenceName              the reference name of the sub-index
	 * @param highWaterPageSequence      the maximum page sequence ever allocated for the stream
	 * @param leafPageSequences          every live leaf's page sequence in ascending key order
	 * @param referencedPrimaryKeysIndex the companion `referencedEntityPrimaryKey → reduced-index-PK bitmap` map
	 * @return the PAGED root storage part
	 */
	@Nonnull
	public static ReferenceTypeCardinalityIndexStoragePart paged(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences,
		@Nonnull Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex
	) {
		return new ReferenceTypeCardinalityIndexStoragePart(
			entityIndexPrimaryKey, referenceName, true, null, null,
			highWaterPageSequence, leafPageSequences, referencedPrimaryKeysIndex, null
		);
	}

	/**
	 * Creates a read-path PAGED root with an already-known primary key (used when rehydrating from storage).
	 *
	 * @param entityIndexPrimaryKey      primary key of the owning entity index
	 * @param referenceName              the reference name of the sub-index
	 * @param highWaterPageSequence      the maximum page sequence ever allocated for the stream
	 * @param leafPageSequences          every live leaf's page sequence in ascending key order
	 * @param referencedPrimaryKeysIndex the companion `referencedEntityPrimaryKey → reduced-index-PK bitmap` map
	 * @param storagePartPK              the precomputed primary key
	 * @return the PAGED root storage part
	 */
	@Nonnull
	public static ReferenceTypeCardinalityIndexStoragePart paged(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences,
		@Nonnull Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex,
		long storagePartPK
	) {
		return new ReferenceTypeCardinalityIndexStoragePart(
			entityIndexPrimaryKey, referenceName, true, null, null,
			highWaterPageSequence, leafPageSequences, referencedPrimaryKeysIndex, storagePartPK
		);
	}

	/**
	 * Method computes unique part id as long, that composes of integer primary key of the {@link io.evitadb.index.EntityIndex}
	 * index belong to and compressed reference name that is assigned as soon as index is first stored.
	 */
	public static long computeUniquePartId(int entityIndexPrimaryKey, @Nonnull String referenceName, @Nonnull KeyCompressor keyCompressor) {
		return NumberUtils.pack(entityIndexPrimaryKey, keyCompressor.getId(new ReferenceNameKey(referenceName)));
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = NumberUtils.pack(this.entityIndexPrimaryKey, keyCompressor.getId(new ReferenceNameKey(this.referenceName)));
		Assert.isPremiseValid(
			this.storagePartPK == null || this.storagePartPK == computedUniquePartId,
			() -> "The storage part id was already set to different value!"
		);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

	@Override
	public String getStoragePartSourceKey() {
		return this.referenceName;
	}

}
