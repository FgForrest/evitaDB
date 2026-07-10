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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;

/**
 * A flush-time instruction to REMOVE the {@link HistogramCardinalityStoragePart} sibling of a histogram that was
 * dropped this commit — either the whole histogram (removed from its owning entity index's histogram map) or a single
 * locale of a localized histogram. Because the sibling is its own record type (distinct from the histogram root) with a
 * store-side-resolved primary key, the append-only OffsetIndex needs an explicit removal to reclaim it; the root's own
 * removal does not cover it.
 *
 * The part carries the sub-index `(entityIndexPrimaryKey, histogramName, locale)` identity rather than a pre-resolved
 * primary key: the writable {@link KeyCompressor} lives store-side, so the target primary key is resolved store-side in
 * {@link #computeUniquePartIdAndSet}. It carries no payload and is never written, so it needs no Kryo serializer.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HistogramCardinalityStoragePartRemoval implements DeferredRemovalStoragePart {
	@Serial private static final long serialVersionUID = 6047382910564738201L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex} — identity used to resolve the dropped sibling's
	 * primary key store-side.
	 */
	private final int entityIndexPrimaryKey;
	/**
	 * The histogram name of the sub-index — used to resolve the dropped sibling's primary key store-side.
	 */
	@Nonnull private final String histogramName;
	/**
	 * The locale of the sub-index, or `null` for a non-localized histogram.
	 */
	@Nullable private final Locale locale;
	/**
	 * The resolved storage-part primary key; `null` until {@link #computeUniquePartIdAndSet} resolves it store-side.
	 */
	@Nullable private Long storagePartPK;

	/**
	 * Creates a removal instruction for the dropped cardinality sibling identified by its sub-index identity.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param histogramName         the histogram name of the sub-index
	 * @param locale                the locale of the sub-index, or `null`
	 */
	public HistogramCardinalityStoragePartRemoval(
		int entityIndexPrimaryKey,
		@Nonnull String histogramName,
		@Nullable Locale locale
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.histogramName = histogramName;
		this.locale = locale;
		this.storagePartPK = null;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return this.storagePartPK;
	}

	/**
	 * @return the locale of the sub-index whose cardinality sibling is being removed, or `null` for a non-localized
	 * histogram
	 */
	@Nullable
	public Locale getLocale() {
		return this.locale;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = AbstractHistogramStoragePart.computeUniquePartId(
			this.entityIndexPrimaryKey, this.histogramName, this.locale, keyCompressor
		);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

	@Nonnull
	@Override
	public Class<? extends StoragePart> removedContainerType() {
		return HistogramCardinalityStoragePart.class;
	}
}
