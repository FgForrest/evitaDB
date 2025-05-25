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

package io.evitadb.core.query.algebra.price.filteredPriceRecords;

import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.function.Consumer;

/**
 * This implementation of {@link FilteredPriceRecords} doesn't keep information about the particular prices, but keeps
 * only references to appropriate {@link PriceListAndCurrencyPriceIndex indexes} that can be used to locate the prices.
 * When asked for prices of particular entity, this implementation retrieves the lowest prices by inner record id for
 * the entity directly from the price list index in the order they are present in the array.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
public class LazyEvaluatedEntityPriceRecords implements FilteredPriceRecords {
	@Serial private static final long serialVersionUID = -6907857133556088806L;

	/**
	 * Contains references to price list indexes that will provide the lowest index for entity in
	 * {@link #getPriceRecordsLookup()}.
	 */
	@Getter private final PriceListAndCurrencyPriceIndex[] priceIndexes;


	public LazyEvaluatedEntityPriceRecords(@Nonnull PriceListAndCurrencyPriceIndex... priceIndex) {
		this.priceIndexes = priceIndex;
	}

	/**
	 * Method returns {@link PriceRecordLookup} implementation that provides
	 * {@link PriceListAndCurrencyPriceIndex#getLowestPriceRecordsForEntity(int)} for each entity asked.
	 */
	@Nonnull
	@Override
	public PriceRecordIterator getPriceRecordsLookup() {
		return new PriceRecordIterator(this.priceIndexes);
	}

	/**
	 * Implementation of {@link PriceRecordLookup} that provides
	 * {@link PriceListAndCurrencyPriceIndex#getLowestPriceRecordsForEntity(int)} for each entity asked.
	 */
	@ThreadSafe
	@RequiredArgsConstructor
	public static class PriceRecordIterator implements PriceRecordLookup {
		private final PriceListAndCurrencyPriceIndex[] priceIndexes;

		@Override
		public boolean forEachPriceOfEntity(int entityPk, int lastExpectedEntity, @Nonnull Consumer<PriceRecordContract> priceConsumer) {
			for (PriceListAndCurrencyPriceIndex priceIndex : this.priceIndexes) {
				final PriceRecordContract[] lowestPriceRecordsForEntity = priceIndex.getLowestPriceRecordsForEntity(entityPk);
				if (!ArrayUtils.isEmpty(lowestPriceRecordsForEntity)) {
					for (PriceRecordContract thePrice : lowestPriceRecordsForEntity) {
						priceConsumer.accept(thePrice);
					}
					return true;
				}
			}
			return false;
		}

	}
}
