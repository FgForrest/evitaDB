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

import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceSuperIndex.PriceIndexChanges;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * Price index contains data structures that allow processing price related filtering and sorting constraints such as
 * {@link io.evitadb.api.query.filter.PriceBetween}, {@link io.evitadb.api.query.filter.PriceValidIn},
 * {@link PriceNatural}.
 *
 * For each combination of {@link PriceContract#priceList()} and {@link PriceContract#currency()} it maintains
 * separate filtering index. Pre-sorted indexes are maintained for all prices regardless of their price list
 * relation because there is no guarantee that there will be currency or price list part of the query.
 *
 * Super index maintains references to {@link PriceListAndCurrencyPriceSuperIndex}, the main logic is part of
 * the abstract class this implementation extends from. Price super index (or its inner indexes) contain full price
 * dataset and is self-sufficient (on the contrary to {@link PriceRefIndex}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PriceSuperIndex extends AbstractPriceIndex<PriceListAndCurrencyPriceSuperIndex> implements TransactionalLayerProducer<PriceIndexChanges, PriceSuperIndex> {
	@Serial private static final long serialVersionUID = 7596276815836027747L;
	/**
	 * Map of {@link PriceListAndCurrencyPriceSuperIndex indexes} that contains prices that relates to specific price-list
	 * and currency combination.
	 */
	protected final TransactionalMap<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> priceIndexes;

	public PriceSuperIndex() {
		this.priceIndexes = new TransactionalMap<>(new HashMap<>(), PriceListAndCurrencyPriceSuperIndex.class, Function.identity());
	}

	public PriceSuperIndex(@Nonnull Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> priceIndexes) {
		this.priceIndexes = new TransactionalMap<>(priceIndexes, PriceListAndCurrencyPriceSuperIndex.class, Function.identity());
	}

	/*
		Transactional memory implementation
	 */

	@Override
	public PriceIndexChanges createLayer() {
		return new PriceIndexChanges();
	}

	@Nonnull
	@Override
	public PriceSuperIndex createCopyWithMergedTransactionalMemory(@Nullable PriceIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final PriceSuperIndex priceIndex = new PriceSuperIndex(
			transactionalLayer.getStateCopyWithCommittedChanges(this.priceIndexes)
		);
		ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
		return priceIndex;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.priceIndexes.removeLayer(transactionalLayer);
		final PriceSuperIndex.PriceIndexChanges changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
	}

	/*
		PROTECTED METHODS
	 */

	@Nonnull
	@Override
	protected PriceListAndCurrencyPriceSuperIndex createNewPriceListAndCurrencyIndex(@Nonnull PriceIndexKey lookupKey) {
		final PriceListAndCurrencyPriceSuperIndex newPriceListIndex = new PriceListAndCurrencyPriceSuperIndex(lookupKey);
		ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
			.ifPresent(it -> it.addCreatedItem(newPriceListIndex));
		return newPriceListIndex;
	}

	@Override
	protected void removeExistingIndex(@Nonnull PriceIndexKey lookupKey, @Nonnull PriceListAndCurrencyPriceSuperIndex priceListIndex) {
		super.removeExistingIndex(lookupKey, priceListIndex);
		ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
			.ifPresent(it -> it.addRemovedItem(priceListIndex));
	}

	@Override
	protected int addPrice(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull PriceListAndCurrencyPriceSuperIndex priceListIndex, int entityPrimaryKey,
		int internalPriceId, int priceId, @Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity, int priceWithoutTax, int priceWithTax
	) {
		final PriceRecordContract priceRecord = innerRecordId == null ?
			new PriceRecord(internalPriceId, priceId, entityPrimaryKey, priceWithTax, priceWithoutTax) :
			new PriceRecordInnerRecordSpecific(
				internalPriceId, priceId, entityPrimaryKey, innerRecordId, priceWithTax, priceWithoutTax
			);
		priceListIndex.addPrice(priceRecord, validity);
		return priceRecord.internalPriceId();
	}

	@Override
	protected void removePrice(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull PriceListAndCurrencyPriceSuperIndex priceListIndex, int entityPrimaryKey,
		int internalPriceId, int priceId, @Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity, int priceWithoutTax, int priceWithTax
	) {
		priceListIndex.removePrice(entityPrimaryKey, internalPriceId, validity);
	}

	@Nonnull
	@Override
	protected Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> getPriceIndexes() {
		return priceIndexes;
	}

	/**
	 * This class collects changes in {@link #priceIndexes} transactional map.
	 */
	public static class PriceIndexChanges {
		private final TransactionalContainerChanges<Void, PriceListAndCurrencyPriceSuperIndex, PriceListAndCurrencyPriceSuperIndex> collectedPriceIndexChanges = new TransactionalContainerChanges<>();

		public void addCreatedItem(PriceListAndCurrencyPriceSuperIndex priceIndex) {
			collectedPriceIndexChanges.addCreatedItem(priceIndex);
		}

		public void addRemovedItem(PriceListAndCurrencyPriceSuperIndex priceIndex) {
			collectedPriceIndexChanges.addRemovedItem(priceIndex);
		}

		public void clean(TransactionalLayerMaintainer transactionalLayer) {
			collectedPriceIndexChanges.clean(transactionalLayer);
		}

		public void cleanAll(TransactionalLayerMaintainer transactionalLayer) {
			collectedPriceIndexChanges.cleanAll(transactionalLayer);
		}
	}

}
