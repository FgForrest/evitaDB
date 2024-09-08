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

package io.evitadb.spike.mock;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIndexProvidingFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import lombok.RequiredArgsConstructor;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * No extra information provided - see (selfexplanatory) method signatures.
 * I have the best intention to write more detailed documentation but if you see this, there was not enough time or will to do so.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class MockInnerRecordIdsFormula extends AbstractFormula implements PriceIndexProvidingFormula, FilteredPriceRecordAccessor {
	private static final long CLASS_ID = -8740309489269214775L;
	private final Bitmap innerRecordIds;
	private final FilteredPriceRecords allPriceRecords;

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
	public PriceListAndCurrencyPriceIndex getPriceIndex() {
		throw new UnsupportedOperationException();
	}

	@Nonnull
	@Override
	public Formula getDelegate() {
		throw new UnsupportedOperationException();
	}

	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecords() {
		return allPriceRecords;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return innerRecordIds;
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return CLASS_ID;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	public int getEstimatedCardinality() {
		return innerRecordIds.size();
	}

	@Override
	public int getSize() {
		return innerRecordIds.size();
	}
}
