/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.index.price.PriceListAndCurrencyPriceRefIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.range.RangeIndex;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;

/**
 * Price list and currency index stores information about entity prices that share same currency and price list
 * identification. In practice, there is single index for combination of price list (basic for example) and currency
 * (CZK for example). This container object serves only as a storage carrier for
 * {@link PriceListAndCurrencyPriceRefIndex} which is a live memory representation of the data
 * stored in this container.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class PriceListAndCurrencyRefIndexStoragePart extends PriceListAndCurrencyIndexStoragePart {
	@Serial private static final long serialVersionUID = -1687563151524978160L;
	/**
	 * Contains data of all indexed prices ids in this container.
	 */
	@Getter private final int[] priceIds;

	public PriceListAndCurrencyRefIndexStoragePart(int entityIndexPrimaryKey, @Nonnull PriceIndexKey priceIndexKey, @Nonnull RangeIndex validityIndex, @Nonnull int[] priceIds) {
		super(entityIndexPrimaryKey, priceIndexKey, validityIndex);
		this.priceIds = priceIds;
	}

	public PriceListAndCurrencyRefIndexStoragePart(int entityIndexPrimaryKey, @Nonnull PriceIndexKey priceIndexKey, @Nonnull RangeIndex validityIndex, @Nonnull int[] priceIds, @Nonnull Long uniquePartId) {
		super(entityIndexPrimaryKey, priceIndexKey, validityIndex, uniquePartId);
		this.priceIds = priceIds;
	}

}
