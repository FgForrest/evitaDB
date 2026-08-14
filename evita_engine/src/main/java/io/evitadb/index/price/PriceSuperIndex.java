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
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges.ContainerChangesMemento;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceSuperIndex.PriceIndexChanges;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collection;
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
public class PriceSuperIndex
	extends AbstractPriceIndex<PriceListAndCurrencyPriceSuperIndex>
	implements TransactionalLayerProducer<PriceIndexChanges, PriceSuperIndex> {
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
	public PriceSuperIndex createCopyWithMergedTransactionalMemory(
		@Nullable PriceIndexChanges layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
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
	protected PriceListAndCurrencyPriceSuperIndex createNewPriceListAndCurrencyIndex(
		@Nonnull PriceIndexKey lookupKey,
		@Nonnull PriceSuperIndex superPriceIndex
	) {
		assertIsThisIndex(superPriceIndex);
		final PriceListAndCurrencyPriceSuperIndex newPriceListIndex = new PriceListAndCurrencyPriceSuperIndex(lookupKey);
		ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
			.ifPresent(it -> it.addCreatedItem(newPriceListIndex));
		return newPriceListIndex;
	}

	/**
	 * Verifies that the super price index the caller threaded down the write path is this very index.
	 *
	 * A super index owns the memory-expensive price records outright and so has no use for the parameter - but the check
	 * is free and it is the only place where the caller's claim ("this is the price index of the GLOBAL entity index that
	 * backs the index you are mutating") can be falsified cheaply. A mutation executor resolves the GLOBAL once and
	 * threads the same instance into both the GLOBAL's own price index and every reduced index it touches, so a caller
	 * that picked up the wrong catalog version's GLOBAL - the failure the removed super-index pointer used to be able to
	 * introduce silently - trips here on the very first price it writes.
	 *
	 * @param superPriceIndex the super price index handed over by the caller
	 */
	private void assertIsThisIndex(@Nonnull PriceSuperIndex superPriceIndex) {
		Assert.isPremiseValid(
			superPriceIndex == this,
			"Price write routed to a super index with a foreign GLOBAL price index handed in - " +
				"the caller resolved a different (or stale) GLOBAL entity index than the one it is mutating!"
		);
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
		@Nullable DateTimeRange validity, int priceWithoutTax, int priceWithTax,
		@Nonnull PriceSuperIndex superPriceIndex
	) {
		assertIsThisIndex(superPriceIndex);
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
		@Nullable DateTimeRange validity, int priceWithoutTax, int priceWithTax,
		@Nonnull PriceSuperIndex superPriceIndex
	) {
		assertIsThisIndex(superPriceIndex);
		priceListIndex.removePrice(entityPrimaryKey, internalPriceId, validity);
	}

	@Nonnull
	@Override
	protected Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> getPriceIndexes() {
		return this.priceIndexes;
	}

	/**
	 * Narrows the contract's wildcard element type to the concrete super index this implementation maintains. Every
	 * per-price-list index held by a super index is a {@link PriceListAndCurrencyPriceSuperIndex} - the class generic
	 * bound already guarantees it, and this override lets callers that need the super-index API (such as reclaiming
	 * the persisted leaf pages of a dropped index) rely on it statically instead of casting.
	 *
	 * @return the per-price-list super indexes maintained by this index
	 */
	@Nonnull
	@Override
	public Collection<PriceListAndCurrencyPriceSuperIndex> getPriceListAndCurrencyIndexes() {
		return this.priceIndexes.values();
	}

	/**
	 * Returns the heap this index occupies, in bytes — its map of per-combination super indexes and everything those
	 * own, including the price record bodies.
	 *
	 * The {@link PriceIndexKey} of each entry is charged **here**: the map is keyed by the very instance handed to the
	 * sub-index constructor, so the container owns it and the sub-index pays only for its reference slot.
	 *
	 * Walking every sub-index makes this `O(price records)` rather than `O(1)`, so it belongs to the index detail call
	 * and must never be called from a query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the inherited id, then the priceIndexes slot
		return layout.sizeOfObject(Long.BYTES + layout.referenceSize())
			+ this.priceIndexes.getHeapSizeInBytes(
				PRICE_INDEX_KEY_SIZER,
				PriceListAndCurrencyPriceSuperIndex::getHeapSizeInBytes
			);
	}

	/**
	 * Resolves the super index of a single price-list / currency combination, which a reduced
	 * {@link PriceListAndCurrencyPriceRefIndex} of the same combination needs in order to reach the memory-expensive
	 * {@link PriceRecord} and {@link io.evitadb.index.price.model.entityPrices.EntityPrices} instances it shares rather
	 * than owns.
	 *
	 * Unlike {@link #getPriceIndex(PriceIndexKey)} the combination is required to exist: a price is always added to the
	 * super index before the reduced index that references it, so an absent combination is a programming error rather
	 * than an expected miss. The read inside consults the transactional combo map, so a combination created earlier in
	 * the same transaction is visible.
	 *
	 * @param priceIndexKey the price-list / currency combination to resolve
	 * @return the super price index backing the combination (never `null`)
	 */
	@Nonnull
	public PriceListAndCurrencyPriceSuperIndex getPriceIndexOrThrow(@Nonnull PriceIndexKey priceIndexKey) {
		final PriceListAndCurrencyPriceSuperIndex superIndex = this.priceIndexes.get(priceIndexKey);
		Assert.isPremiseValid(
			superIndex != null,
			() -> "Super price index for `" + priceIndexKey + "` must exist in the GLOBAL entity index!"
		);
		return superIndex;
	}

	/**
	 * This class collects changes in {@link #priceIndexes} transactional map.
	 */
	public static class PriceIndexChanges implements Snapshotable<PriceIndexChanges.PriceIndexChangesMemento> {
		private final TransactionalContainerChanges<PriceListAndCurrencyPriceSuperIndex, PriceListAndCurrencyPriceSuperIndex> collectedPriceIndexChanges = new TransactionalContainerChanges<>();

		public void addCreatedItem(PriceListAndCurrencyPriceSuperIndex priceIndex) {
			this.collectedPriceIndexChanges.addCreatedItem(priceIndex);
		}

		public void addRemovedItem(PriceListAndCurrencyPriceSuperIndex priceIndex) {
			this.collectedPriceIndexChanges.addRemovedItem(priceIndex);
		}

		public void clean(TransactionalLayerMaintainer transactionalLayer) {
			this.collectedPriceIndexChanges.clean(transactionalLayer);
		}

		public void cleanAll(TransactionalLayerMaintainer transactionalLayer) {
			this.collectedPriceIndexChanges.cleanAll(transactionalLayer);
		}

		@Nonnull
		@Override
		public PriceIndexChangesMemento snapshot() {
			return new PriceIndexChangesMemento(this.collectedPriceIndexChanges.snapshot());
		}

		@Override
		public void restore(@Nonnull PriceIndexChangesMemento memento) {
			this.collectedPriceIndexChanges.restore(memento.collectedPriceIndexChanges());
		}

		/**
		 * Memento bundling the savepoint state of every {@link TransactionalContainerChanges} this aggregate tracks.
		 *
		 * @param collectedPriceIndexChanges snapshot of the price-index created/removed bookkeeping
		 */
		public record PriceIndexChangesMemento(
			@Nonnull ContainerChangesMemento<PriceListAndCurrencyPriceSuperIndex> collectedPriceIndexChanges
		) {
		}
	}

}
