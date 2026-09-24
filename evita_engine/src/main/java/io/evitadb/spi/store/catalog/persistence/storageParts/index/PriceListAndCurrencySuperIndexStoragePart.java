/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;

/**
 * Price list and currency index stores information about entity prices that share same currency and price list
 * identification. In practice, there is single index for combination of price list (basic for example) and currency
 * (CZK for example). This container object serves only as a storage carrier for
 * {@link PriceListAndCurrencyPriceSuperIndex} which is a live memory representation of the data
 * stored in this container.
 *
 * The part has two shapes. A `SINGLE` part carries every price record inline (the common,
 * small-index case). A `PAGED` part instead carries only the page-stream metadata — the shape
 * discriminator, the high-water page sequence and the ordered list of live leaf-page sequences —
 * while the records themselves live in individual {@link PriceListAndCurrencySuperIndexLeafPagePart}
 * leaf pages.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class PriceListAndCurrencySuperIndexStoragePart extends PriceListAndCurrencyIndexStoragePart {
	// bumped from 2938472615049182736L when DateTimeRange moved to millisecond comparison granularity: the byte layout
	// is unchanged, but the validity RangeIndex now persists epoch-MILLISECOND thresholds where the previous shape
	// persisted epoch-seconds. The previous uid is the one release 2026.2 shipped and is read - and rescaled - by
	// PriceListAndCurrencySuperIndexStoragePartSerializer_2026_2.
	@Serial private static final long serialVersionUID = 2938472615049182737L;
	/**
	 * Empty leaf-page list shared by every `SINGLE`-shaped part (no paged leaves).
	 */
	private static final int[] NO_LEAF_PAGES = ArrayUtils.EMPTY_INT_ARRAY;
	/**
	 * Empty inline price-record array shared by every `PAGED`-shaped part (its records live in leaf pages).
	 */
	private static final PriceRecordContract[] EMPTY_PRICE_RECORDS = new PriceRecordContract[0];
	/**
	 * For a `SINGLE`-shaped part (the whole price-record tree fits one record — the common, small-index case) this holds
	 * every price record inline. For a `PAGED`-shaped part the records live in individual
	 * {@link PriceListAndCurrencySuperIndexLeafPagePart} leaf pages instead, and this array is empty.
	 */
	@Getter private final PriceRecordContract[] priceRecords;
	/**
	 * The `PAGED`/`SINGLE` discriminator. When `true` the price-record tree is persisted as individual
	 * {@link PriceListAndCurrencySuperIndexLeafPagePart} leaf pages keyed by `pack(streamId, pageSequence)` and
	 * {@link #priceRecords} is empty; when `false` every record lives inline in {@link #priceRecords}. The page stream id
	 * is deliberately NOT persisted here: it is the {@link PriceLeafStreamKey}'s compressed id, recomputed at load from
	 * the sub-index identity via the catalog's read-only `KeyCompressor`.
	 */
	@Getter private final boolean paged;
	/**
	 * The high-water `pageSequence` of the stream (the maximum `pageSequence` ever allocated) for a `PAGED`-shaped part;
	 * `-1` for a `SINGLE`-shaped part. Persisted explicitly rather than derived as `max(pageSequence)` over live pages, so
	 * a freed max page cannot let a reused id be handed out while an older catalog version still references it.
	 */
	@Getter private final int highWaterPageSequence;
	/**
	 * The leaf pages of a `PAGED`-shaped part, listed in ascending internal-price-id order — exactly the order in which
	 * the load path reads them back and reassembles the spine (the spine is NOT persisted; it is reconstructed at load).
	 * Empty for a `SINGLE`-shaped part.
	 */
	@Nonnull @Getter private final int[] leafPageSequences;

	/**
	 * Builds a `PAGED`-shaped part: the price records live in {@link PriceListAndCurrencySuperIndexLeafPagePart} leaf
	 * pages, so the root carries the explicit high-water `pageSequence`, the ordered leaf-page list (ascending key order)
	 * and the inline {@link RangeIndex} validity, but NO inline records and NO page-stream id (it is recomputed at load
	 * from the sub-index identity).
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param priceIndexKey         the price list and currency identity
	 * @param validityIndex         the inline validity range index
	 * @param highWaterPageSequence the maximum `pageSequence` ever allocated for the stream
	 * @param leafPageSequences     the leaf pages in ascending key order
	 * @return the paged super price index storage part
	 */
	@Nonnull
	public static PriceListAndCurrencySuperIndexStoragePart paged(
		int entityIndexPrimaryKey,
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull RangeIndex validityIndex,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences
	) {
		return new PriceListAndCurrencySuperIndexStoragePart(
			entityIndexPrimaryKey, priceIndexKey, validityIndex, EMPTY_PRICE_RECORDS,
			true, highWaterPageSequence, leafPageSequences
		);
	}

	/**
	 * Creates a `SINGLE`-shaped part with every price record inline; its storage part PK is assigned before persistence.
	 */
	public PriceListAndCurrencySuperIndexStoragePart(int entityIndexPrimaryKey, @Nonnull PriceIndexKey priceIndexKey, @Nonnull RangeIndex validityIndex, @Nonnull PriceRecordContract[] priceRecords) {
		this(entityIndexPrimaryKey, priceIndexKey, validityIndex, priceRecords, false, -1, NO_LEAF_PAGES);
	}

	/**
	 * Creates a `SINGLE`-shaped part with every price record inline carrying the already-assigned storage part PK.
	 */
	public PriceListAndCurrencySuperIndexStoragePart(int entityIndexPrimaryKey, @Nonnull PriceIndexKey priceIndexKey, @Nonnull RangeIndex validityIndex, @Nonnull PriceRecordContract[] priceRecords, @Nonnull Long uniquePartId) {
		this(entityIndexPrimaryKey, priceIndexKey, validityIndex, priceRecords, false, -1, NO_LEAF_PAGES, uniquePartId);
	}

	/**
	 * Canonical constructor (PK not yet assigned) carrying the inline records and the `PAGED` page-stream metadata
	 * (`paged == false` ⇔ `SINGLE` shape: every record inline, `-1` high-water, empty leaf list).
	 */
	public PriceListAndCurrencySuperIndexStoragePart(
		int entityIndexPrimaryKey,
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull RangeIndex validityIndex,
		@Nonnull PriceRecordContract[] priceRecords,
		boolean paged,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences
	) {
		super(entityIndexPrimaryKey, priceIndexKey, validityIndex);
		this.priceRecords = priceRecords;
		this.paged = paged;
		this.highWaterPageSequence = highWaterPageSequence;
		this.leafPageSequences = leafPageSequences;
	}

	/**
	 * Canonical constructor carrying the already-assigned storage part PK, the inline records and the `PAGED` page-stream
	 * metadata (`paged == false` ⇔ `SINGLE` shape).
	 */
	public PriceListAndCurrencySuperIndexStoragePart(
		int entityIndexPrimaryKey,
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull RangeIndex validityIndex,
		@Nonnull PriceRecordContract[] priceRecords,
		boolean paged,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences,
		@Nonnull Long uniquePartId
	) {
		super(entityIndexPrimaryKey, priceIndexKey, validityIndex, uniquePartId);
		this.priceRecords = priceRecords;
		this.paged = paged;
		this.highWaterPageSequence = highWaterPageSequence;
		this.leafPageSequences = leafPageSequences;
	}

}
