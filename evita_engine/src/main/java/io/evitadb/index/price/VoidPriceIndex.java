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

package io.evitadb.index.price;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.price.model.PriceIndexKey;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.stream.Stream;

/**
 * Void implementation of the {@link PriceIndexContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class VoidPriceIndex implements PriceIndexContract {
	public static final PriceIndexContract INSTANCE = new VoidPriceIndex();

	@Nonnull
	@Override
	public Collection<? extends PriceListAndCurrencyPriceIndex> getPriceListAndCurrencyIndexes() {
		return Collections.emptyList();
	}

	@Nonnull
	@Override
	public Stream<? extends PriceListAndCurrencyPriceIndex> getPriceIndexesStream(@Nonnull Currency currency, @Nonnull PriceInnerRecordHandling innerRecordHandling) {
		return Stream.empty();
	}

	@Nonnull
	@Override
	public Stream<? extends PriceListAndCurrencyPriceIndex> getPriceIndexesStream(@Nonnull String priceListName, @Nonnull PriceInnerRecordHandling innerRecordHandling) {
		return Stream.empty();
	}

	@Override
	public int addPrice(
		@Nullable ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		int internalPriceId,
		@Nonnull PriceKey priceKey,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax
	) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void priceRemove(
		@Nullable ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		int internalPriceId,
		@Nonnull PriceKey priceKey,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax
	) {
		throw new UnsupportedOperationException();
	}

	@Nullable
	@Override
	public PriceListAndCurrencyPriceIndex getPriceIndex(@Nonnull String priceList, @Nonnull Currency currency, @Nonnull PriceInnerRecordHandling innerRecordHandling) {
		return null;
	}

	@Nullable
	@Override
	public PriceListAndCurrencyPriceIndex getPriceIndex(@Nonnull PriceIndexKey priceListAndCurrencyKey) {
		return null;
	}

	@Override
	public boolean isPriceIndexEmpty() {
		return true;
	}

}
