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

import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * One persisted leaf page of a granular FilterIndex bucket tree. Under the tree-as-pages layout each
 * leaf of the {@code TransactionalBucketBPlusTree} backing an {@code InvertedIndex} is stored as its own record, so a
 * transaction (re)writes only the leaf pages it actually changed instead of re-materializing the whole bucket array.
 *
 * The page carries the leaf's buckets as a {@link ValueToRecord} array — each element either the multi-record
 * {@link ValueToRecordBitmap} or the compact single-record {@link ValueToRecordPrimitive}, mirroring the same split
 * the query-side read path already uses — in ascending value order; the routing spine that orders the leaves is NOT
 * persisted (it is reconstructed on load), and a leaf page stores no separators.
 *
 * Identity is the pair `(streamId, pageSequence)`, packed into the storage-part primary key via
 * {@link AbstractLeafPagePart#computeUniquePartId}.
 * `streamId` is the {@link KeyCompressor} id of the sub-index's {@link LeafStreamKey} (one dictionary entry per
 * persisted sub-index); `pageSequence` is the advance-only, never-reused page sequence within that stream.
 *
 * **Why the part carries the sub-index IDENTITY rather than a pre-resolved `streamId`.** The writable
 * {@link KeyCompressor} that allocates the `streamId` lives store-side and is only reached at PK-assignment time (the
 * persistence service calls {@link #computeUniquePartIdAndSet(KeyCompressor)} just before writing). The engine that
 * emits this page has no compressor, so it cannot know `streamId` at creation — exactly as the monolithic
 * {@link FilterIndexStoragePart} cannot know its own compressed id at creation. A write-path page is therefore built
 * with its `(entityIndexPrimaryKey, attributeKey)` identity and resolves (and caches) `streamId` store-side in
 * {@link #computeUniquePartIdAndSet}. A read-path page (rehydrated by the serializer) instead carries the already-known
 * `streamId` and PK and leaves the identity null.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FilterIndexLeafPagePart extends AbstractAttributeLeafPagePart {
	@Serial private static final long serialVersionUID = 8923174650293847561L;

	/**
	 * The leaf's buckets in ascending value order — (value, record-set) pairs.
	 */
	@Nonnull @Getter private final ValueToRecord[] buckets;

	/**
	 * Creates a WRITE-PATH leaf page carrying the sub-index identity; its `streamId` and primary key are resolved
	 * store-side on first {@link #computeUniquePartIdAndSet(KeyCompressor)}.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeKey          the attribute + index-type identity of the sub-index
	 * @param pageSequence               the page sequence within the stream
	 * @param buckets               the leaf's buckets in ascending value order
	 */
	public FilterIndexLeafPagePart(
		int entityIndexPrimaryKey,
		@Nonnull AttributeKeyWithIndexType attributeKey,
		int pageSequence,
		@Nonnull ValueToRecord[] buckets
	) {
		super(entityIndexPrimaryKey, attributeKey, pageSequence);
		this.buckets = buckets;
	}

	/**
	 * Creates a READ-PATH leaf page with an already-resolved `streamId` and primary key (used when rehydrating from
	 * storage); the write-path identity is left null.
	 *
	 * @param streamId      the resolved stream id
	 * @param pageSequence       the page sequence within the stream
	 * @param buckets       the leaf's buckets in ascending value order
	 * @param storagePartPK the precomputed primary key
	 */
	public FilterIndexLeafPagePart(
		int streamId, int pageSequence, @Nonnull ValueToRecord[] buckets, @Nonnull Long storagePartPK
	) {
		super(streamId, pageSequence, storagePartPK);
		this.buckets = buckets;
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
