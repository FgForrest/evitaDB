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

import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * One persisted leaf page of a granular OWNER-mode {@link io.evitadb.index.attribute.SortIndex} value tree. Under the
 * tree-as-pages layout each leaf of the `InvertedIndex` (value → records bitmap) — which the owner index keeps as the
 * full source of truth for value ordering and cardinality — is stored as its own record, so a transaction (re)writes
 * only the leaf pages it actually changed instead of re-materializing the whole flat `sortedRecords` int array plus the
 * distinct value column on every commit.
 *
 * The page carries the leaf's buckets as a {@link ValueToRecordBitmap} array — the same (value, record-set) element shape
 * the {@code FilterIndex} leaf page uses — in ascending value order; the routing spine that orders the leaves is NOT
 * persisted (it is reconstructed on load), and a leaf page stores no separators. The flat positional `sortedRecords`
 * façade is NOT persisted either: it is reconstructed by concatenating the reloaded tree's buckets on load.
 *
 * Unlike the FILTER leaf page, the page also carries {@link #comparatorBaseLength} — the number of comparator-base
 * components of the owning sort index. A single-component (scalar) page stores one value per bucket; a compound page
 * stores a {@code comparatorBaseLength}-element {@code ComparableArray}. The bespoke serializer needs this length to
 * unwrap the compound value (the {@code ComparableArray} class is registered nowhere in Kryo), driving the same
 * component-by-component encoding the monolithic `SortIndexStoragePart` serializer uses.
 *
 * Identity is the pair `(streamId, pageSequence)`, packed into the storage-part primary key via
 * {@link AbstractLeafPagePart#computeUniquePartId}.
 * `streamId` is the {@link KeyCompressor} id of the sub-index's {@link LeafStreamKey} (one dictionary entry per persisted
 * sub-index, distinguished by a SORT-typed {@link AttributeKeyWithIndexType} so the SORT stream is disjoint from the
 * FILTER stream of the same attribute); `pageSequence` is the advance-only, never-reused page sequence within that stream.
 *
 * Mirroring {@link FilterIndexLeafPagePart}: a write-path page carries the sub-index `(entityIndexPrimaryKey,
 * attributeKey)` identity and resolves (and caches) `streamId` store-side in {@link #computeUniquePartIdAndSet} (the
 * engine that emits the page has no compressor); a read-path page (rehydrated by the serializer) carries the already-known
 * `streamId` and PK and leaves the identity null.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class SortIndexLeafPagePart extends AbstractAttributeLeafPagePart {
	@Serial private static final long serialVersionUID = 6918273405162738491L;

	/**
	 * The leaf's buckets in ascending value order — (value, record-set) pairs.
	 */
	@Nonnull @Getter private final ValueToRecordBitmap[] buckets;
	/**
	 * The number of comparator-base components of the owning sort index (1 == scalar, &gt;1 == compound). Drives the
	 * bespoke serializer's per-bucket value (un)wrapping; carried on the page because Kryo serializers are stateless.
	 */
	@Getter private final int comparatorBaseLength;

	/**
	 * Creates a WRITE-PATH leaf page carrying the sub-index identity; its `streamId` and primary key are resolved
	 * store-side on first {@link #computeUniquePartIdAndSet(KeyCompressor)}.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param attributeKey          the attribute + index-type identity of the sub-index
	 * @param pageSequence          the page sequence within the stream
	 * @param buckets               the leaf's buckets in ascending value order
	 * @param comparatorBaseLength  the number of comparator-base components (1 == scalar, &gt;1 == compound)
	 */
	public SortIndexLeafPagePart(
		int entityIndexPrimaryKey,
		@Nonnull AttributeKeyWithIndexType attributeKey,
		int pageSequence,
		@Nonnull ValueToRecordBitmap[] buckets,
		int comparatorBaseLength
	) {
		super(entityIndexPrimaryKey, attributeKey, pageSequence);
		this.buckets = buckets;
		this.comparatorBaseLength = comparatorBaseLength;
	}

	/**
	 * Creates a READ-PATH leaf page with an already-resolved `streamId` and primary key (used when rehydrating from
	 * storage); the write-path identity is left null.
	 *
	 * @param streamId             the resolved stream id
	 * @param pageSequence         the page sequence within the stream
	 * @param buckets              the leaf's buckets in ascending value order
	 * @param comparatorBaseLength the number of comparator-base components (1 == scalar, &gt;1 == compound)
	 * @param storagePartPK        the precomputed primary key
	 */
	public SortIndexLeafPagePart(
		int streamId,
		int pageSequence,
		@Nonnull ValueToRecordBitmap[] buckets,
		int comparatorBaseLength,
		@Nonnull Long storagePartPK
	) {
		super(streamId, pageSequence, storagePartPK);
		this.buckets = buckets;
		this.comparatorBaseLength = comparatorBaseLength;
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
