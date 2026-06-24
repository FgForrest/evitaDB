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
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey.StreamKind;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
 * Identity is the pair `(streamId, pageSequence)`, packed into the storage-part primary key via {@link NumberUtils#join}.
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
public class RangeIndexLeafPagePart implements StoragePart {
	@Serial private static final long serialVersionUID = 6612058937401852736L;

	/**
	 * Sentinel for a `streamId` not yet resolved (a write-path page before {@link #computeUniquePartIdAndSet}).
	 */
	public static final int UNRESOLVED_STREAM_ID = -1;

	/**
	 * Primary key of the owning entity index — write-path identity used to resolve {@link #streamId} store-side; `null`
	 * on a rehydrated (read-path) page.
	 */
	@Nullable @Getter private final Integer entityIndexPrimaryKey;
	/**
	 * The attribute + index-type identity of the sub-index — write-path identity used to resolve {@link #streamId}
	 * store-side; `null` on a rehydrated (read-path) page.
	 */
	@Nullable @Getter private final AttributeKeyWithIndexType attributeKey;
	/**
	 * The {@link KeyCompressor} id of the sub-index range stream this page belongs to. {@link #UNRESOLVED_STREAM_ID} on a
	 * write-path page until {@link #computeUniquePartIdAndSet} resolves it from the identity; already known on a
	 * rehydrated (read-path) page.
	 */
	@Getter private int streamId;
	/**
	 * The advance-only, never-reused page sequence of this leaf within its stream.
	 */
	@Getter private final int pageSequence;
	/**
	 * The leaf's range points in ascending threshold order — (threshold, starts, ends) triples.
	 */
	@Nonnull @Getter private final TransactionalRangePoint[] points;
	/**
	 * The storage-part primary key `join(streamId, pageSequence)`; `null` until assigned by
	 * {@link #computeUniquePartIdAndSet(KeyCompressor)} (write path) or supplied at rehydration (read path).
	 */
	@Nullable @Getter @Setter private Long storagePartPK;

	/**
	 * Computes the storage-part primary key for a leaf page from its resolved identifying pair.
	 *
	 * @param streamId the resolved stream id
	 * @param pageSequence  the page sequence within the stream
	 * @return the 64-bit storage-part primary key
	 */
	public static long computeUniquePartId(int streamId, int pageSequence) {
		return NumberUtils.join(streamId, pageSequence);
	}

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
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeKey = attributeKey;
		this.streamId = UNRESOLVED_STREAM_ID;
		this.pageSequence = pageSequence;
		this.points = points;
		this.storagePartPK = null;
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
		this.entityIndexPrimaryKey = null;
		this.attributeKey = null;
		this.streamId = streamId;
		this.pageSequence = pageSequence;
		this.points = points;
		this.storagePartPK = storagePartPK;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		if (this.streamId == UNRESOLVED_STREAM_ID) {
			// write path: resolve the RANGE stream id from the sub-index identity via the writable compressor (allocates a
			// dictionary entry on the first PAGED write of this range stream, returns the stable id thereafter)
			Assert.isPremiseValid(
				this.entityIndexPrimaryKey != null && this.attributeKey != null,
				"A leaf page must carry its sub-index identity to resolve the stream id!"
			);
			this.streamId = keyCompressor.getId(
				new LeafStreamKey(this.entityIndexPrimaryKey, this.attributeKey, StreamKind.RANGE)
			);
		}
		final long computedUniquePartId = computeUniquePartId(this.streamId, this.pageSequence);
		if (this.storagePartPK == null) {
			this.storagePartPK = computedUniquePartId;
		} else {
			Assert.isTrue(this.storagePartPK == computedUniquePartId, "Unique part ids must never differ!");
		}
		return computedUniquePartId;
	}
}
