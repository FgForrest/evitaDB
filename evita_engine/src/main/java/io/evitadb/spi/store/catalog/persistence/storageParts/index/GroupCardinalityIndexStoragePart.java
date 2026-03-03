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
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.RecordWithCompressedId;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Map;

/**
 * This storage part implementation maintains cardinality information for a group entity index.
 * It stores how many times each entity primary key appears in the group (via different references)
 * and which referenced entity primary keys map to which entity primary keys.
 *
 * Unlike {@link ReferenceTypeCardinalityIndexStoragePart}, which stores cardinalities using composed
 * `Long` keys (via {@link NumberUtils#join(int, int)}), this storage part uses simple `Integer` keys
 * directly, since the group index does not need the complex key composition used by
 * {@link io.evitadb.index.ReferencedTypeEntityIndex}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
@AllArgsConstructor
@ToString(of = {"entityIndexPrimaryKey", "referenceName"})
public class GroupCardinalityIndexStoragePart implements StoragePart, RecordWithCompressedId<String> {
	@Serial private static final long serialVersionUID = -2847190532847265193L;

	/**
	 * Unique id that identifies {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final int entityIndexPrimaryKey;
	/**
	 * Name of the reference this index is related to.
	 */
	@Getter @Nonnull private final String referenceName;
	/**
	 * Map of entity primary keys to their cardinality counts. An entity can appear in a group
	 * via multiple references, so this tracks how many times each entity PK was added.
	 */
	@Getter @Nonnull private final Map<Integer, Integer> pkCardinalities;
	/**
	 * Map of referenced entity primary keys to bitmaps of entity primary keys that reference them
	 * within this group.
	 */
	@Getter @Nonnull private final Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex;
	/**
	 * Id used for lookups in file offset index for this particular container.
	 */
	@Getter @Setter private Long storagePartPK;

	/**
	 * Method computes unique part id as long, that composes of integer primary key of the
	 * {@link io.evitadb.index.EntityIndex} the index belongs to and compressed reference name
	 * that is assigned as soon as index is first stored.
	 */
	public static long computeUniquePartId(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		@Nonnull KeyCompressor keyCompressor
	) {
		return NumberUtils.join(
			entityIndexPrimaryKey,
			keyCompressor.getId(new ReferenceNameKey(referenceName))
		);
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = NumberUtils.join(
			this.entityIndexPrimaryKey,
			keyCompressor.getId(new ReferenceNameKey(this.referenceName))
		);
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
