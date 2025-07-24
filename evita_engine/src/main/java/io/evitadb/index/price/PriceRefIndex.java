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

import io.evitadb.api.CatalogState;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Catalog;
import io.evitadb.core.CatalogRelatedDataStructure;
import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex.PriceListAndCurrencyPriceIndexTerminated;
import io.evitadb.index.price.PriceRefIndex.PriceIndexChanges;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.entityPrices.EntityPrices;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 * Ref index maintains references to {@link PriceListAndCurrencyPriceRefIndex}, the main logic is part of
 * the abstract class this implementation extends from. PriceRefIndex contains reduced set of data - we try to avoid
 * excessive memory consumption by maintaining reusing the existing {@link PriceRecord} and {@link EntityPrices}
 * objects in {@link PriceSuperIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PriceRefIndex extends AbstractPriceIndex<PriceListAndCurrencyPriceRefIndex> implements
	TransactionalLayerProducer<PriceIndexChanges, PriceRefIndex>,
	CatalogRelatedDataStructure<PriceRefIndex>
{
	@Serial private static final long serialVersionUID = 7596276815836027747L;
	/**
	 * Captures the scope of the index and reflects the {@link EntityIndexKey#scope()} of the main entity index this
	 * price index is part of.
	 */
	private final Scope scope;
	/**
	 * Map of {@link PriceListAndCurrencyPriceSuperIndex indexes} that contains prices that relates to specific price-list
	 * and currency combination.
	 */
	@Getter protected final TransactionalMap<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> priceIndexes;
	/**
	 * Lambda that manages initialization of new price list indexes. These indexes needs to locate their
	 * {@link PriceListAndCurrencyPriceSuperIndex} from the catalog data and use it for locating shared price records
	 * instances.
	 */
	private Consumer<PriceListAndCurrencyPriceRefIndex> initCallback;

	public PriceRefIndex(@Nonnull Scope scope) {
		this.scope = scope;
		this.priceIndexes = new TransactionalMap<>(new HashMap<>(), PriceListAndCurrencyPriceRefIndex.class, Function.identity());
	}

	public PriceRefIndex(
		@Nonnull Scope scope,
		@Nonnull Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> priceIndexes
	) {
		this.scope = scope;
		this.priceIndexes = new TransactionalMap<>(priceIndexes, PriceListAndCurrencyPriceRefIndex.class, Function.identity());
	}

	@Override
	public void attachToCatalog(@Nullable String entityType, @Nonnull Catalog catalog) {
		Assert.isPremiseValid(this.initCallback == null, "Catalog was already attached to this index!");
		this.initCallback = priceListAndCurrencyPriceRefIndex -> priceListAndCurrencyPriceRefIndex.attachToCatalog(entityType, catalog);
		// delegate call to price list indexes
		this.priceIndexes.values().forEach(it -> it.attachToCatalog(entityType, catalog));
	}

	@Nonnull
	@Override
	public PriceRefIndex createCopyForNewCatalogAttachment(@Nonnull CatalogState catalogState) {
		return new PriceRefIndex(
			this.scope,
			this.priceIndexes
				.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Map.Entry::getKey,
						it -> it.getValue().createCopyForNewCatalogAttachment(catalogState)
					)
				)
		);
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
	public PriceRefIndex createCopyWithMergedTransactionalMemory(@Nullable PriceIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final PriceRefIndex priceIndex = new PriceRefIndex(
			this.scope,
			transactionalLayer.getStateCopyWithCommittedChanges(this.priceIndexes)
		);
		ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
		return priceIndex;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.priceIndexes.removeLayer(transactionalLayer);
		final PriceIndexChanges changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
	}

	/*
		PROTECTED METHODS
	 */

	@Nonnull
	protected PriceListAndCurrencyPriceRefIndex createNewPriceListAndCurrencyIndex(@Nonnull PriceIndexKey lookupKey) {
		final PriceListAndCurrencyPriceRefIndex newPriceListIndex = new PriceListAndCurrencyPriceRefIndex(this.scope, lookupKey);
		this.initCallback.accept(newPriceListIndex);
		ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
			.ifPresent(it -> it.addCreatedItem(newPriceListIndex));
		return newPriceListIndex;
	}

	@Override
	protected void removeExistingIndex(@Nonnull PriceIndexKey lookupKey, @Nonnull PriceListAndCurrencyPriceRefIndex priceListIndex) {
		super.removeExistingIndex(lookupKey, priceListIndex);
		ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
			.ifPresent(it -> it.addRemovedItem(priceListIndex));
	}

	@Override
	protected int addPrice(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull PriceListAndCurrencyPriceRefIndex priceListIndex,
		int entityPrimaryKey,
		int internalPriceId,
		int priceId,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax
	) {
		final PriceRecordContract priceRecord = priceListIndex.addPrice(internalPriceId, validity);
		return priceRecord.internalPriceId();
	}

	@Override
	protected void removePrice(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull PriceListAndCurrencyPriceRefIndex priceListIndex, int entityPrimaryKey,
		int internalPriceId,
		int priceId,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax
	) {
		try {
			priceListIndex.removePrice(internalPriceId, validity);
		} catch (PriceListAndCurrencyPriceIndexTerminated ex) {
			// when super index was removed the referencing index must be removed as well
			removeExistingIndex(priceListIndex.getPriceIndexKey(), priceListIndex);
		}
	}

	/**
	 * This class collects changes in {@link #priceIndexes} transactional map.
	 */
	public static class PriceIndexChanges {
		private final TransactionalContainerChanges<Void, PriceListAndCurrencyPriceRefIndex, PriceListAndCurrencyPriceRefIndex> collectedPriceIndexChanges = new TransactionalContainerChanges<>();

		public void addCreatedItem(@Nonnull PriceListAndCurrencyPriceRefIndex priceIndex) {
			this.collectedPriceIndexChanges.addCreatedItem(priceIndex);
		}

		public void addRemovedItem(@Nonnull PriceListAndCurrencyPriceRefIndex priceIndex) {
			this.collectedPriceIndexChanges.addRemovedItem(priceIndex);
		}

		public void clean(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.collectedPriceIndexChanges.clean(transactionalLayer);
		}

		public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.collectedPriceIndexChanges.cleanAll(transactionalLayer);
		}
	}

}
