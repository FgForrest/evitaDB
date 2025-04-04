/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.index.mutation.index.dataAccess;


import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.price.PriceWithInternalIds;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * PriceStoragePartSupplier is responsible for providing access to prices held in {@link PricesStoragePart} data
 * structure for purpose of indexing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class PriceStoragePartSupplier implements ExistingPriceSupplier {
	/**
	 * PricesStoragePart instance that holds the prices.
	 */
	private final PricesStoragePart priceStoragePart;

	@Nonnull
	@Override
	public Stream<PriceWithInternalIds> getExistingPrices() {
		return Arrays.stream(this.priceStoragePart.getPrices())
			.filter(Droppable::exists);
	}

	@Nonnull
	@Override
	public PriceInnerRecordHandling getPriceInnerRecordHandling() {
		return this.priceStoragePart.getPriceInnerRecordHandling();
	}

	@Nullable
	@Override
	public PriceWithInternalIds getPriceByKey(@Nonnull PriceKey priceKey) {
		final PriceWithInternalIds priceByKey = this.priceStoragePart.getPriceByKey(priceKey);
		return priceByKey != null && priceByKey.exists() ? priceByKey : null;
	}
}
