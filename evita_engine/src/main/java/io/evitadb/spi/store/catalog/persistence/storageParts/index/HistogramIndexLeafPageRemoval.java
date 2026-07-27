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

import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramLeafStreamKey.StreamKind;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;

/**
 * A flush-time instruction to REMOVE a single granular {@link io.evitadb.index.HistogramIndex} leaf page that a leaf
 * merge (or a `PAGED -> SINGLE` collapse, or a whole-histogram / per-locale drop) dropped this commit. Removing it is
 * necessary, not optional: the append-only OffsetIndex never reclaims a record that is neither superseded (page
 * sequences are advance-only and never re-keyed) nor explicitly removed, so an unreferenced leaf page would otherwise be
 * copied forward by every compaction forever.
 *
 * A single removal covers EITHER axis: the {@link StreamKind} selects both the stream id resolved store-side and the
 * removed container class ({@link HistogramIndexLeafPagePart} for {@link StreamKind#BUCKET},
 * {@link HistogramRangeIndexLeafPagePart} for {@link StreamKind#RANGE}). Like the leaf pages on the write path, this
 * carries the sub-index `(entityIndexPrimaryKey, histogramName, locale)` identity rather than a pre-resolved `streamId`:
 * the writable {@link KeyCompressor} lives store-side, so the target primary key `pack(streamId, pageSequence)` is
 * resolved store-side in {@link #computeUniquePartIdAndSet}. The part carries no payload and is never written, so it
 * needs no Kryo serializer.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HistogramIndexLeafPageRemoval implements DeferredRemovalStoragePart {
	@Serial private static final long serialVersionUID = 1928374650192837465L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex} — identity used to resolve the freed page's
	 * `streamId` store-side.
	 */
	private final int entityIndexPrimaryKey;
	/**
	 * The histogram name of the sub-index — used to resolve the freed page's `streamId` store-side.
	 */
	@Nonnull private final String histogramName;
	/**
	 * The locale of the sub-index, or `null` for a non-localized histogram.
	 */
	@Nullable private final Locale locale;
	/**
	 * Which of the histogram's two page streams the freed page belongs to.
	 */
	@Nonnull private final StreamKind streamKind;
	/**
	 * The advance-only page sequence of the freed leaf within its stream.
	 */
	private final int pageSequence;
	/**
	 * The resolved storage-part primary key `pack(streamId, pageSequence)`; `null` until
	 * {@link #computeUniquePartIdAndSet} resolves it store-side.
	 */
	@Nullable private Long storagePartPK;

	/**
	 * Creates a removal instruction for the freed leaf page identified by its sub-index identity, stream kind and page
	 * sequence.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param histogramName         the histogram name of the sub-index
	 * @param locale                the locale of the sub-index, or `null`
	 * @param streamKind            which page stream (bucket or range) the freed page belongs to
	 * @param pageSequence          the page sequence of the freed leaf
	 */
	public HistogramIndexLeafPageRemoval(
		int entityIndexPrimaryKey,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull StreamKind streamKind,
		int pageSequence
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.histogramName = histogramName;
		this.locale = locale;
		this.streamKind = streamKind;
		this.pageSequence = pageSequence;
		this.storagePartPK = null;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return this.storagePartPK;
	}

	/**
	 * @return the locale of the sub-index whose leaf page is being removed, or `null` for a non-localized histogram
	 */
	@Nullable
	public Locale getLocale() {
		return this.locale;
	}

	/**
	 * @return which of the histogram's two page streams (bucket / range) the removed leaf page belongs to
	 */
	@Nonnull
	public StreamKind getStreamKind() {
		return this.streamKind;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		// the freed page's stream was registered when the sub-index first went PAGED, so the id resolves against the
		// existing dictionary; pack it with the page sequence into the same key the leaf page was written under
		final int streamId = keyCompressor.getId(
			new HistogramLeafStreamKey(this.entityIndexPrimaryKey, this.histogramName, this.locale, this.streamKind)
		);
		final long computedUniquePartId = HistogramIndexLeafPagePart.computeUniquePartId(streamId, this.pageSequence);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

	@Nonnull
	@Override
	public Class<? extends StoragePart> removedContainerType() {
		// the record-type byte differs per axis, so the removal must name the right container class
		return this.streamKind == StreamKind.BUCKET
			? HistogramIndexLeafPagePart.class
			: HistogramRangeIndexLeafPagePart.class;
	}
}
