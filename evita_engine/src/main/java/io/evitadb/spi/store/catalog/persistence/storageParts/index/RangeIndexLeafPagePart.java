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

import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey.StreamKind;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * One persisted leaf page of a granular FilterIndex range tree. Under the tree-as-pages layout each
 * leaf of the {@code TransactionalLongBPlusTree} backing a {@code RangeIndex} is stored as its own record, so a
 * transaction (re)writes only the leaf pages it actually changed instead of re-materializing the whole range-point array.
 *
 * The page carries the leaf's range points as a {@link TransactionalRangePoint} array — the same `(threshold, starts,
 * ends)` element shape the monolithic {@code FilterIndexStoragePart} inlines via its {@code RangeIndex} — in ascending
 * threshold order; the routing spine that orders the leaves is NOT persisted (it is reconstructed on load),
 * and a leaf page stores no separators. The border sentinels (`Long.MIN_VALUE` / `Long.MAX_VALUE`) live in the first /
 * last pages.
 *
 * Identity is the pair `(streamId, pageSequence)`, packed into the storage-part primary key via
 * {@link AbstractLeafPagePart#computeUniquePartId}.
 * `streamId` is the {@link KeyCompressor} id of the sub-index's {@link LeafStreamKey} resolved with
 * {@link StreamKind#RANGE} — distinct from the same FilterIndex's {@link StreamKind#BUCKET} value stream, so the two
 * streams' page sequences never collide; `pageSequence` is the advance-only, never-reused page sequence within that stream.
 *
 * Like {@link FilterIndexLeafPagePart}, a write-path page carries the `(entityIndexPrimaryKey, attributeKey)` identity
 * and resolves (and caches) `streamId` store-side in {@link #computeUniquePartIdAndSet} (the engine that emits it has no
 * writable compressor); a read-path page (rehydrated by the serializer) instead carries the already-known `streamId` and
 * PK and leaves the identity null.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class RangeIndexLeafPagePart extends AbstractAttributeLeafPagePart {
	@Serial private static final long serialVersionUID = 6612058937401852736L;

	/**
	 * The leaf's range points in ascending threshold order — (threshold, starts, ends) triples.
	 */
	@Nonnull @Getter private final TransactionalRangePoint[] points;

	/**
	 * Creates a WRITE-PATH leaf page carrying the sub-index identity; its `streamId` and primary key are resolved
	 * store-side on first {@link #computeUniquePartIdAndSet(KeyCompressor)} with {@link StreamKind#RANGE}.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeKey          the attribute + index-type identity of the sub-index
	 * @param pageSequence               the page sequence within the stream
	 * @param points                the leaf's range points in ascending threshold order
	 */
	public RangeIndexLeafPagePart(
		int entityIndexPrimaryKey,
		@Nonnull AttributeKeyWithIndexType attributeKey,
		int pageSequence,
		@Nonnull TransactionalRangePoint[] points
	) {
		super(entityIndexPrimaryKey, attributeKey, pageSequence);
		this.points = points;
	}

	/**
	 * Creates a READ-PATH leaf page with an already-resolved `streamId` and primary key (used when rehydrating from
	 * storage); the write-path identity is left null.
	 *
	 * @param streamId      the resolved stream id
	 * @param pageSequence       the page sequence within the stream
	 * @param points        the leaf's range points in ascending threshold order
	 * @param storagePartPK the precomputed primary key
	 */
	public RangeIndexLeafPagePart(
		int streamId, int pageSequence, @Nonnull TransactionalRangePoint[] points, @Nonnull Long storagePartPK
	) {
		super(streamId, pageSequence, storagePartPK);
		this.points = points;
	}

	@Override
	protected int resolveStreamId(@Nonnull KeyCompressor keyCompressor) {
		// RANGE-typed stream, distinct from this attribute's plain bucket stream
		return keyCompressor.getId(
			new LeafStreamKey(
				getEntityIndexPrimaryKeyOrThrowException(),
				getAttributeKeyOrThrowException(),
				StreamKind.RANGE
			)
		);
	}
}
