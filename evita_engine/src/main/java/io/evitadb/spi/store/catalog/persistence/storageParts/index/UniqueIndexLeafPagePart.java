/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * One persisted leaf page of a granular standalone (OWNER) {@link io.evitadb.index.attribute.UniqueIndex} bucket tree.
 * Under the tree-as-pages layout each leaf of the {@code TransactionalBucketBPlusTree} backing an
 * {@link io.evitadb.index.attribute.OwnerUniqueIndex} is stored as its own record, so a transaction (re)writes only the
 * leaf pages it actually changed instead of re-materializing the whole value-to-record map.
 *
 * A unique value maps to exactly one record id (uniqueness is enforced on insert), so unlike the FilterIndex leaf page
 * — which stores a {@code ValueToRecordBitmap} per bucket — this page stores the slimmest possible payload: two
 * positionally-aligned columns, the values (in ascending key order) and their single record ids.
 *
 * Identity is the pair `(streamId, pageSequence)`, packed into the storage-part primary key via
 * {@link AbstractLeafPagePart#computeUniquePartId}.
 * `streamId` is the {@link KeyCompressor} id of the sub-index's {@link LeafStreamKey} (one dictionary entry per
 * persisted sub-index, distinguished from the FilterIndex stream of the same attribute by the {@link AttributeKeyWithIndexType}'s
 * {@link AttributeIndexStoragePart.AttributeIndexType#UNIQUE} discriminator); `pageSequence` is the advance-only,
 * never-reused page sequence within that stream.
 *
 * Mirroring {@link FilterIndexLeafPagePart}: a write-path page carries the sub-index `(entityIndexPrimaryKey,
 * attributeKey)` identity and resolves (and caches) `streamId` store-side in {@link #computeUniquePartIdAndSet} (the
 * engine that emits the page has no compressor); a read-path page (rehydrated by the serializer) carries the
 * already-known `streamId` and PK and leaves the identity null.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class UniqueIndexLeafPagePart extends AbstractLeafPagePart {
	@Serial private static final long serialVersionUID = 6537142098345610273L;

	/**
	 * The leaf's values in ascending key order, positionally aligned with {@link #recordIds}.
	 */
	@Nonnull @Getter private final Serializable[] values;
	/**
	 * The single record id owning each value, positionally aligned with {@link #values}.
	 */
	@Nonnull @Getter private final int[] recordIds;

	/**
	 * Creates a WRITE-PATH leaf page carrying the sub-index identity; its `streamId` and primary key are resolved
	 * store-side on first {@link #computeUniquePartIdAndSet(KeyCompressor)}.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeKey          the attribute + index-type identity of the sub-index
	 * @param pageSequence          the page sequence within the stream
	 * @param values                the leaf's values in ascending key order
	 * @param recordIds             the single record id owning each value, aligned with `values`
	 */
	public UniqueIndexLeafPagePart(
		int entityIndexPrimaryKey,
		@Nonnull AttributeKeyWithIndexType attributeKey,
		int pageSequence,
		@Nonnull Serializable[] values,
		@Nonnull int[] recordIds
	) {
		super(entityIndexPrimaryKey, attributeKey, pageSequence);
		Assert.isPremiseValid(values.length == recordIds.length, "Values and record ids must be positionally aligned!");
		this.values = values;
		this.recordIds = recordIds;
	}

	/**
	 * Creates a READ-PATH leaf page with an already-resolved `streamId` and primary key (used when rehydrating from
	 * storage); the write-path identity is left null.
	 *
	 * @param streamId      the resolved stream id
	 * @param pageSequence  the page sequence within the stream
	 * @param values        the leaf's values in ascending key order
	 * @param recordIds     the single record id owning each value, aligned with `values`
	 * @param storagePartPK the precomputed primary key
	 */
	public UniqueIndexLeafPagePart(
		int streamId,
		int pageSequence,
		@Nonnull Serializable[] values,
		@Nonnull int[] recordIds,
		@Nonnull Long storagePartPK
	) {
		super(streamId, pageSequence, storagePartPK);
		Assert.isPremiseValid(values.length == recordIds.length, "Values and record ids must be positionally aligned!");
		this.values = values;
		this.recordIds = recordIds;
	}

	@Override
	protected int resolveStreamId(@Nonnull KeyCompressor keyCompressor) {
		return keyCompressor.getId(
			new LeafStreamKey(
				getEntityIndexPrimaryKeyOrThrowException(), getAttributeKeyOrThrowException()
			)
		);
	}
}
