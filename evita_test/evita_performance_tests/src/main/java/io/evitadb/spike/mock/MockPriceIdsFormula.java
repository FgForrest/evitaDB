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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;

import javax.annotation.Nonnull;

/**
 * No extra information provided - see (selfexplanatory) method signatures.
 * I have the best intention to write more detailed documentation but if you see this, there was not enough time or will to do so.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class MockPriceIdsFormula extends PriceIdContainerFormula {
	private final Bitmap priceIds;
	private final PriceListAndCurrencyPriceIndex priceList;

	public MockPriceIdsFormula(PriceListAndCurrencyPriceIndex priceIndex, Bitmap priceIds, PriceRecordContract[] priceRecords) {
		super(priceIndex, new ConstantFormula(priceIds));
		this.priceIds = priceIds;
		this.priceList = new MockPriceListAndCurrencyPriceSuperIndex(priceRecords);
	}

	@Nonnull
	@Override
	public PriceListAndCurrencyPriceIndex getPriceIndex() {
		return this.priceList;
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getOperationCost() {
		throw new UnsupportedOperationException();
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return this.priceIds;
	}

}
