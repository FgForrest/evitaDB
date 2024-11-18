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


import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.store.entity.model.entity.price.PriceWithInternalIds;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.stream.Stream;

/**
 * Interface provides access to the prices of an entity for the purpose of their indexing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ExistingPriceSupplier {

	/**
	 * Retrieves the current price inner record handling strategy.
	 *
	 * @return the {@link PriceInnerRecordHandling} enum value representing the current strategy for handling prices
	 *         with the same inner entity id.
	 */
	@Nonnull
	PriceInnerRecordHandling getPriceInnerRecordHandling();

	/**
	 * Retrieves a stream of existing prices, each represented by a {@link PriceWithInternalIds} object.
	 *
	 * @return a non-null {@link Stream} of {@link PriceWithInternalIds} representing the existing prices
	 */
	@Nonnull
	Stream<PriceWithInternalIds> getExistingPrices();

	/**
	 * Retrieves the price associated with the given {@link PriceKey}.
	 *
	 * @param priceKey the key that uniquely identifies the price
	 * @return the {@link PriceWithInternalIds} associated with the given price key, or null if no such price exists
	 */
	@Nullable
	PriceWithInternalIds getPriceByKey(@Nonnull PriceKey priceKey);

}
