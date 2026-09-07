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

package io.evitadb.spike.mock;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Currency;

/**
 * Mock extension of {@link PriceListAndCurrencyPriceSuperIndex} that stores a pre-built array
 * of {@link PriceRecordContract} and a bitmap of their extracted price IDs. Overrides
 * {@link #getPriceRecords()} and {@link #getIndexedPriceIds()} to return the pre-built structures
 * directly, bypassing the real index's transactional storage layer.
 *
 * Used by {@link PriceIdsWithPriceRecordsRecordState} to back the {@link PriceIdContainerFormula}
 * with realistic price data for benchmark price-ID-to-entity-ID translation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class MockPriceListAndCurrencyPriceSuperIndex extends PriceListAndCurrencyPriceSuperIndex {
	@Serial private static final long serialVersionUID = -8175819375673200637L;
	private final PriceRecordContract[] entitiesPriceRecords;
	private final Bitmap priceIds;

	public MockPriceListAndCurrencyPriceSuperIndex(PriceRecordContract[] entitiesPriceRecords) {
		super(new PriceIndexKey("whatever", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE));
		this.entitiesPriceRecords = entitiesPriceRecords;
		final int[] extractedPriceIds = new int[entitiesPriceRecords.length];
		for (int i = 0; i < entitiesPriceRecords.length; i++) {
			extractedPriceIds[i] = entitiesPriceRecords[i].internalPriceId();
		}
		this.priceIds = new BaseBitmap(extractedPriceIds);
	}

	@Nonnull
	@Override
	public Bitmap getIndexedPriceEntityIds() {
		return super.getIndexedPriceEntityIds();
	}

	@Nonnull
	@Override
	public Bitmap getIndexedPriceIds() {
		return this.priceIds;
	}

	@Nonnull
	@Override
	public PriceRecordContract[] getPriceRecords() {
		return this.entitiesPriceRecords;
	}
}
