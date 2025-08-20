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
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Currency;

/**
 * No extra information provided - see (selfexplanatory) method signatures.
 * I have the best intention to write more detailed documentation but if you see this, there was not enough time or will to do so.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class MockPriceListAndCurrencyPriceSuperIndex extends PriceListAndCurrencyPriceSuperIndex {
	@Serial private static final long serialVersionUID = -8175819375673200637L;
	private final PriceRecordContract[] entitiesPriceRecords;
	private final int[] priceIds;

	public MockPriceListAndCurrencyPriceSuperIndex(PriceRecordContract[] entitiesPriceRecords) {
		super(new PriceIndexKey("whatever", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE));
		this.entitiesPriceRecords = entitiesPriceRecords;
		this.priceIds = new int[entitiesPriceRecords.length];
		for (int i = 0; i < entitiesPriceRecords.length; i++) {
			this.priceIds[i] = entitiesPriceRecords[i].internalPriceId();

		}
	}

	@Nonnull
	@Override
	public Bitmap getIndexedPriceEntityIds() {
		return super.getIndexedPriceEntityIds();
	}

	@Nonnull
	@Override
	public int[] getIndexedPriceIds() {
		return this.priceIds;
	}

	@Nonnull
	@Override
	public PriceRecordContract[] getPriceRecords() {
		return this.entitiesPriceRecords;
	}
}
