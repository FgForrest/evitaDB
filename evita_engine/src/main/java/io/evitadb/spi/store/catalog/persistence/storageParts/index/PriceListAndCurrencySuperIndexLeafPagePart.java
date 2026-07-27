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

import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * One persisted leaf page of a granular price-record tree. Under the tree-as-pages layout each leaf of the
 * {@code TransactionalElementBPlusTree} backing a {@code PriceListAndCurrencyPriceSuperIndex} is stored as its own
 * record, so a transaction (re)writes only the leaf pages it actually changed instead of re-materializing the whole
 * price-record array — which on a large price axis is the dominant per-commit write.
 *
 * The page carries the leaf's price records (a {@link PriceRecordContract} array, the same element shape the monolithic
 * {@link PriceListAndCurrencySuperIndexStoragePart} uses) in ascending internal-price-id order; the routing spine that
 * orders the leaves is NOT persisted (it is reconstructed on load), and a leaf page stores no separators.
 *
 * The `(streamId, pageSequence)` identity, primary-key packing and two-phase stream-id resolution are inherited from
 * {@link AbstractLeafPagePart}. This page's `streamId` is the {@link KeyCompressor} id of the sub-index's
 * {@link PriceLeafStreamKey} (one dictionary entry per persisted super price index). The writable {@link KeyCompressor}
 * that allocates the `streamId` lives store-side and is only reached at PK-assignment time (the persistence service calls
 * {@link #computeUniquePartIdAndSet(KeyCompressor)} just before writing); the engine that emits this page has no
 * compressor, so a write-path page is built with its `(entityIndexPrimaryKey, priceIndexKey)` identity and resolves (and
 * caches) `streamId` store-side in {@link #resolveStreamId}. A read-path page (rehydrated by the serializer) instead
 * carries the already-known `streamId` and PK and leaves the identity null.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class PriceListAndCurrencySuperIndexLeafPagePart extends AbstractLeafPagePart {
	@Serial private static final long serialVersionUID = 6028471950384716253L;

	/**
	 * Primary key of the owning entity index — write-path identity used to resolve the stream id store-side; `null`
	 * on a rehydrated (read-path) page.
	 */
	@Nullable @Getter private final Integer entityIndexPrimaryKey;
	/**
	 * The price list and currency identity of the sub-index — write-path identity used to resolve the stream id
	 * store-side; `null` on a rehydrated (read-path) page.
	 */
	@Nullable @Getter private final PriceIndexKey priceIndexKey;
	/**
	 * The leaf's price records in ascending internal-price-id order.
	 */
	@Nonnull @Getter private final PriceRecordContract[] priceRecords;

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
	 * @param priceIndexKey         the price list and currency identity of the sub-index
	 * @param pageSequence          the page sequence within the stream
	 * @param priceRecords          the leaf's price records in ascending internal-price-id order
	 */
	public PriceListAndCurrencySuperIndexLeafPagePart(
		int entityIndexPrimaryKey,
		@Nonnull PriceIndexKey priceIndexKey,
		int pageSequence,
		@Nonnull PriceRecordContract[] priceRecords
	) {
		super(pageSequence);
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.priceIndexKey = priceIndexKey;
		this.priceRecords = priceRecords;
	}

	/**
	 * Creates a READ-PATH leaf page with an already-resolved `streamId` and primary key (used when rehydrating from
	 * storage); the write-path identity is left null.
	 *
	 * @param streamId      the resolved stream id
	 * @param pageSequence  the page sequence within the stream
	 * @param priceRecords  the leaf's price records in ascending internal-price-id order
	 * @param storagePartPK the precomputed primary key
	 */
	public PriceListAndCurrencySuperIndexLeafPagePart(
		int streamId, int pageSequence, @Nonnull PriceRecordContract[] priceRecords, @Nonnull Long storagePartPK
	) {
		super(streamId, pageSequence, storagePartPK);
		this.entityIndexPrimaryKey = null;
		this.priceIndexKey = null;
		this.priceRecords = priceRecords;
	}

	@Override
	protected int resolveStreamId(@Nonnull KeyCompressor keyCompressor) {
		// write path: resolve the stream id from the sub-index identity via the writable compressor
		Assert.isPremiseValid(
			this.entityIndexPrimaryKey != null && this.priceIndexKey != null,
			"A leaf page must carry its sub-index identity to resolve the stream id!"
		);
		return keyCompressor.getId(new PriceLeafStreamKey(this.entityIndexPrimaryKey, this.priceIndexKey));
	}
}
