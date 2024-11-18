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
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.store.entity.model.entity.price.PriceWithInternalIds;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.stream.Stream;

/**
 * EntityPriceSupplier is responsible for providing access to prices held in {@link Entity} data structure for purpose
 * of changing the entity scope.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class EntityPriceSupplier implements ExistingPriceSupplier {
	/**
	 * Entity instance that holds the prices.
	 */
	private final Entity entity;
	/**
	 * Set of prices that were removed from the entity (and should be hidden).
	 */
	private Set<PriceKey> removedPrices;

	@Nonnull
	@Override
	public Stream<PriceWithInternalIds> getExistingPrices() {
		return this.entity.getPrices()
			.stream()
			.map(PriceWithInternalIds.class::cast)
			.filter(PriceWithInternalIds::exists)
			.filter(this::isNotRemoved);
	}

	@Nonnull
	@Override
	public PriceInnerRecordHandling getPriceInnerRecordHandling() {
		return this.entity.getPriceInnerRecordHandling();
	}

	@Nullable
	@Override
	public PriceWithInternalIds getPriceByKey(@Nonnull PriceKey priceKey) {
		return this.entity.getPrice(priceKey)
			.map(PriceWithInternalIds.class::cast)
			.filter(PriceWithInternalIds::exists)
			.filter(this::isNotRemoved)
			.orElse(null);
	}

	/**
	 * Registers a price for removal by adding the provided PriceKey to the set of removed prices.
	 * If the set of removed prices is not already initialized, it creates and initializes the set first.
	 *
	 * @param priceKey The PriceKey representing the unique combination of priceId, priceList, and currency to be registered for removal.
	 */
	public void registerRemoval(@Nonnull PriceKey priceKey) {
		if (this.removedPrices == null) {
			this.removedPrices = CollectionUtils.createHashSet(16);
		}
		this.removedPrices.add(priceKey);
	}

	/**
	 * Checks if the given price has not been removed. This method determines if a price is considered active by ensuring
	 * that it does not exist in the set of removed prices.
	 *
	 * @param price The price to be checked for removal status.
	 * @return {@code true} if the price has not been removed or if the set of removed prices is null, {@code false} otherwise.
	 */
	private boolean isNotRemoved(@Nonnull PriceWithInternalIds price) {
		return this.removedPrices == null || !this.removedPrices.contains(price.priceKey());
	}
}
