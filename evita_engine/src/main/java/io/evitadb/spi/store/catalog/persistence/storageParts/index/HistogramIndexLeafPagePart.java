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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramLeafStreamKey.StreamKind;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;

/**
 * One persisted leaf page of a granular {@link io.evitadb.index.HistogramIndex} bucket tree. Under the tree-as-pages
 * layout each leaf of the {@code TransactionalBucketBPlusTree} backing the histogram's embedded {@code OwnerFilterIndex}
 * {@code InvertedIndex} is stored as its own record, so a transaction (re)writes only the leaf pages it actually changed
 * instead of re-materializing the whole bucket array.
 *
 * The page carries the leaf's buckets as a {@link ValueToRecordBitmap} array — the same (value, record-set) element
 * shape the monolithic {@code HistogramIndexStoragePart} uses — in ascending value order; the routing spine that orders
 * the leaves is NOT persisted (it is reconstructed on load), and a leaf page stores no separators.
 *
 * The `(streamId, pageSequence)` identity, primary-key packing and two-phase stream-id resolution are inherited from
 * {@link AbstractLeafPagePart}. This page's `streamId` is the {@link KeyCompressor} id of the sub-index's
 * {@link HistogramLeafStreamKey} resolved with {@link StreamKind#BUCKET} — one dictionary entry per persisted histogram
 * bucket stream, distinguished by `(entityIndexPrimaryKey, histogramName, locale)`. A write-path page carries that
 * identity and resolves its `streamId` store-side in {@link #resolveStreamId}; a read-path page carries the already-known
 * `streamId` and leaves the identity null.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HistogramIndexLeafPagePart extends AbstractLeafPagePart {
	@Serial private static final long serialVersionUID = -3947105628374619205L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex} — write-path identity used to resolve the stream id
	 * store-side; `null` on a rehydrated (read-path) page.
	 */
	@Nullable @Getter private final Integer entityIndexPrimaryKey;
	/**
	 * Name of the histogram definition of the sub-index — write-path identity; `null` on a rehydrated (read-path) page.
	 */
	@Nullable @Getter private final String histogramName;
	/**
	 * Locale of the sub-index (or `null` for a non-localized histogram) — write-path identity, always `null` on a
	 * rehydrated (read-path) page (a `null` here is indistinguishable from "read path", but a read-path page never
	 * re-resolves its stream id, so it is never read).
	 */
	@Nullable @Getter private final Locale locale;
	/**
	 * The leaf's buckets in ascending value order — (value, record-set) pairs.
	 */
	@Nonnull @Getter private final ValueToRecordBitmap[] buckets;

	/**
	 * Computes the storage-part primary key for a leaf page from its resolved identifying pair. Retained for callers that
	 * address it through this concrete type; it delegates to {@link AbstractLeafPagePart#computeUniquePartId} via
	 * {@link NumberUtils#pack}.
	 *
	 * @param streamId     the resolved stream id
	 * @param pageSequence the page sequence within the stream
	 * @return the 64-bit storage-part primary key
	 */
	public static long computeUniquePartId(int streamId, int pageSequence) {
		return NumberUtils.pack(streamId, pageSequence);
	}

	/**
	 * Creates a WRITE-PATH leaf page carrying the sub-index identity; its `streamId` and primary key are resolved
	 * store-side on first {@link #computeUniquePartIdAndSet(KeyCompressor)}.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param histogramName         the histogram name of the sub-index
	 * @param locale                the locale of the sub-index, or `null`
	 * @param pageSequence          the page sequence within the stream
	 * @param buckets               the leaf's buckets in ascending value order
	 */
	public HistogramIndexLeafPagePart(
		int entityIndexPrimaryKey,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		int pageSequence,
		@Nonnull ValueToRecordBitmap[] buckets
	) {
		super(pageSequence);
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.histogramName = histogramName;
		this.locale = locale;
		this.buckets = buckets;
	}

	/**
	 * Creates a READ-PATH leaf page with an already-resolved `streamId` and primary key (used when rehydrating from
	 * storage); the write-path identity is left null.
	 *
	 * @param streamId      the resolved stream id
	 * @param pageSequence  the page sequence within the stream
	 * @param buckets       the leaf's buckets in ascending value order
	 * @param storagePartPK the precomputed primary key
	 */
	public HistogramIndexLeafPagePart(
		int streamId, int pageSequence, @Nonnull ValueToRecordBitmap[] buckets, @Nonnull Long storagePartPK
	) {
		super(streamId, pageSequence, storagePartPK);
		this.entityIndexPrimaryKey = null;
		this.histogramName = null;
		this.locale = null;
		this.buckets = buckets;
	}

	@Override
	protected int resolveStreamId(@Nonnull KeyCompressor keyCompressor) {
		// write path: resolve the BUCKET stream id from the sub-index identity via the writable compressor
		Assert.isPremiseValid(
			this.entityIndexPrimaryKey != null && this.histogramName != null,
			"A leaf page must carry its sub-index identity to resolve the stream id!"
		);
		return keyCompressor.getId(
			new HistogramLeafStreamKey(this.entityIndexPrimaryKey, this.histogramName, this.locale, StreamKind.BUCKET)
		);
	}
}
