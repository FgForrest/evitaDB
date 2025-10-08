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

package io.evitadb.store.spi.model.storageParts.index;

import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.store.model.RecordWithCompressedId;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;

/**
 * Price list and currency index stores information about entity prices that share same currency and price list
 * identification. In practice, there is single index for combination of price list (basic for example) and currency
 * (CZK for example). This container object serves only as a storage carrier for
 * {@link PriceListAndCurrencyPriceIndex} which is a live memory representation of the data
 * stored in this container.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@ToString(of = {"priceIndexKey", "entityIndexPrimaryKey"})
abstract class PriceListAndCurrencyIndexStoragePart implements StoragePart, RecordWithCompressedId<PriceIndexKey> {
	@Serial private static final long serialVersionUID = -314925869532587405L;

	/**
	 * Unique id that identifies {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final int entityIndexPrimaryKey;
	/**
	 * Contains price list name and currency that along with {@link #entityIndexPrimaryKey} uniquely identifies this
	 * storage part.
	 */
	@Getter private final PriceIndexKey priceIndexKey;
	/**
	 * Contains date time validity of all indexed prices in this container.
	 */
	@Getter private final RangeIndex validityIndex;
	/**
	 * Id used for lookups in persistent data storage for this particular container.
	 */
	@Getter @Setter private Long storagePartPK;

	protected PriceListAndCurrencyIndexStoragePart(int entityIndexPrimaryKey, @Nonnull PriceIndexKey priceIndexKey, @Nonnull RangeIndex validityIndex) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.priceIndexKey = priceIndexKey;
		this.validityIndex = validityIndex;
	}

	protected PriceListAndCurrencyIndexStoragePart(int entityIndexPrimaryKey, @Nonnull PriceIndexKey priceIndexKey, @Nonnull RangeIndex validityIndex, @Nonnull Long storagePartPK) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.priceIndexKey = priceIndexKey;
		this.validityIndex = validityIndex;
		this.storagePartPK = storagePartPK;
	}

	/**
	 * Method computes unique part id as long, that composes of integer primary key of the {@link io.evitadb.index.EntityIndex}
	 * attributes belong to and compressed attribute key integer that is assigned as soon as attribute is first stored.
	 */
	public static long computeUniquePartId(@Nonnull Integer entityIndexPrimaryKey, @Nonnull PriceIndexKey priceIndexKey, @Nonnull KeyCompressor keyCompressor) {
		return NumberUtils.join(entityIndexPrimaryKey, keyCompressor.getId(priceIndexKey));
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = computeUniquePartId(getEntityIndexPrimaryKey(), getPriceIndexKey(), keyCompressor);
		final Long theUniquePartId = getStoragePartPK();
		if (theUniquePartId == null) {
			setStoragePartPK(computedUniquePartId);
		} else {
			Assert.isTrue(theUniquePartId == computedUniquePartId, "Unique part ids must never differ!");
		}
		return computedUniquePartId;
	}

	@Override
	public PriceIndexKey getStoragePartSourceKey() {
		return this.priceIndexKey;
	}
}
