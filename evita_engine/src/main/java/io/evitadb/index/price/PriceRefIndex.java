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
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex.PriceListAndCurrencyPriceIndexTerminated;
import io.evitadb.index.price.PriceRefIndex.PriceIndexChanges;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.entityPrices.EntityPrices;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

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
 * Ref index maintains references to {@link PriceListAndCurrencyPriceRefIndex}, the main logic is part of
 * the abstract class this implementation extends from. PriceRefIndex contains reduced set of data - we try to avoid
 * excessive memory consumption by maintaining reusing the existing {@link PriceRecord} and {@link EntityPrices}
 * objects in {@link PriceSuperIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PriceRefIndex extends AbstractPriceIndex<PriceListAndCurrencyPriceRefIndex> implements
	TransactionalLayerProducer<PriceIndexChanges, PriceRefIndex>
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

	/**
	 * Reconstructs the price-record trees of every combination index that was **deserialized from disk**, pointing them
	 * at the shared {@link io.evitadb.index.price.model.priceRecord.PriceRecord} instances held by the matching super
	 * index of the passed GLOBAL price index.
	 *
	 * This is the only remaining reason a reduced price index has to be told about the GLOBAL at attach time, and it is
	 * about load correctness, not about version wiring: a ref index persists just its price ids and validity, so a disk
	 * load leaves its record tree empty and unusable until it is repointed at the heap instances. Every other attach
	 * path arrives with the trees already populated and is a no-op here.
	 *
	 * @param superPriceIndex the price index of the owning collection's GLOBAL entity index of this index's scope
	 */
	public void restorePriceRecords(@Nonnull PriceSuperIndex superPriceIndex) {
		for (final PriceListAndCurrencyPriceRefIndex refIndex : this.priceIndexes.values()) {
			refIndex.restorePriceRecordsFrom(superPriceIndex.getPriceIndexOrThrow(refIndex.getPriceIndexKey()));
		}
	}

	/**
	 * Returns the heap this index occupies, in bytes — its map of per-combination reference indexes and the tree
	 * spines those own, but **no** price record bodies.
	 *
	 * Every record a reference index reaches belongs to the {@link PriceListAndCurrencyPriceSuperIndex} of the same
	 * combination, which charges it. That is what keeps this figure proportional to the number of prices a scope
	 * references rather than to the size of the price payload itself, however many reduced indexes point at it.
	 *
	 * {@link #scope} is an enum constant and contributes its slot alone.
	 *
	 * Walking every sub-index makes this `O(price ids)` rather than `O(1)`, so it belongs to `MEMORY_FOOTPRINT` and
	 * must never be called from a query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the inherited id, then the scope and priceIndexes slots
		return layout.sizeOfObject(Long.BYTES + 2L * layout.referenceSize())
			+ this.priceIndexes.getHeapSizeInBytes(
				PRICE_INDEX_KEY_SIZER,
				PriceListAndCurrencyPriceRefIndex::getHeapSizeInBytes
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
	protected PriceListAndCurrencyPriceRefIndex createNewPriceListAndCurrencyIndex(
		@Nonnull PriceIndexKey lookupKey,
		@Nonnull PriceSuperIndex superPriceIndex
	) {
		// a freshly created combination index starts with an empty (but present) record tree, so there is nothing to
		// restore from the super index - it is resolved per operation instead. The lookup is still performed, because
		// a reduced combination must never come into existence without the super index that backs it.
		superPriceIndex.getPriceIndexOrThrow(lookupKey);
		final PriceListAndCurrencyPriceRefIndex newPriceListIndex = new PriceListAndCurrencyPriceRefIndex(this.scope, lookupKey);
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
		int priceWithTax,
		@Nonnull PriceSuperIndex superPriceIndex
	) {
		final PriceRecordContract priceRecord = priceListIndex.addPrice(
			internalPriceId, validity, superPriceIndex.getPriceIndexOrThrow(priceListIndex.getPriceIndexKey())
		);
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
		int priceWithTax,
		@Nonnull PriceSuperIndex superPriceIndex
	) {
		final PriceIndexKey lookupKey = priceListIndex.getPriceIndexKey();
		// Removing the last price of a combination drops its super index from the combo map AND terminates it - and if
		// the same transaction then adds a price back (which is exactly what a price UPDATE does: remove former, add
		// new), the combination is recreated as a DIFFERENT super index instance. All three shapes are observable here
		// depending on where in that sequence this removal lands, and all three mean the same thing: the shared records
		// this reduced index only references are gone, so it cannot outlive them. It is dropped, and the add that
		// follows recreates it against the current super index.
		//   - gone from the map      -> the nullable combination lookup below
		//   - mapped but terminated  -> the catch below
		//   - already replaced       -> the record lookup below comes back empty
		// The first two shapes positively identify themselves - a combination that is gone or terminated backs nothing
		// at all. The third does not: it looks identical to a bookkeeping defect that left one dangling id behind in
		// an otherwise-live index, and it cannot be recognised by comparing super-index instances any more, because
		// the reduced index no longer keeps a pointer to compare against. It is therefore the only one that has to
		// prove itself before the index is discarded - see assertNothingLiveWouldBeLost.
		final PriceListAndCurrencyPriceSuperIndex superIndex = superPriceIndex.getPriceIndex(lookupKey);
		if (superIndex == null) {
			removeExistingIndex(lookupKey, priceListIndex);
			return;
		}
		try {
			if (superIndex.getPriceRecordIfPresent(internalPriceId) == null) {
				assertNothingLiveWouldBeLost(superIndex, priceListIndex, internalPriceId);
				removeExistingIndex(lookupKey, priceListIndex);
				return;
			}
			priceListIndex.removePrice(internalPriceId, validity, superIndex);
		} catch (PriceListAndCurrencyPriceIndexTerminated ex) {
			// when super index was removed the referencing index must be removed as well
			removeExistingIndex(lookupKey, priceListIndex);
		}
	}

	/**
	 * Verifies that discarding `priceListIndex` cannot lose a price that is still live in the GLOBAL index.
	 *
	 * This guards the one ambiguous shape in {@link #removePrice}: the combination is present and healthy in the GLOBAL
	 * index, yet the record being removed is not in it. The benign explanation is that the GLOBAL - which runs ahead of
	 * the reduced indexes on an indexed upsert - already moved this entity's prices out of the combination, leaving
	 * every record this reduced index references behind; discarding it is then the only correct outcome. The other
	 * explanation is a bookkeeping defect that left a single dangling id in an otherwise-live index, and discarding it
	 * there would silently unindex every other price it holds.
	 *
	 * The two are told apart by the records themselves rather than by index identity: under the benign explanation
	 * nothing this index references survives in the combination, so a single surviving record proves the drop is
	 * unwarranted. Only reachable from the ambiguous shape, which the price-tagged functional suite exercises once
	 * across a thousand tests, so the linear probe costs nothing in practice.
	 *
	 * @param superIndex     the live GLOBAL combination index the reduced index is checked against
	 * @param priceListIndex the reduced combination index that is about to be discarded
	 * @param internalPriceId the internal id of the price whose removal triggered the check
	 */
	private static void assertNothingLiveWouldBeLost(
		@Nonnull PriceListAndCurrencyPriceSuperIndex superIndex,
		@Nonnull PriceListAndCurrencyPriceRefIndex priceListIndex,
		int internalPriceId
	) {
		if (priceListIndex.isTerminated()) {
			// a terminated index holds nothing anyone can still reach
			return;
		}
		for (final int indexedPriceId : priceListIndex.getIndexedPriceIds()) {
			if (indexedPriceId != internalPriceId && superIndex.getPriceRecordIfPresent(indexedPriceId) != null) {
				throw new GenericEvitaInternalError(
					"Reduced price index " + priceListIndex.getPriceIndexKey() + " would be discarded because " +
						"internal price id " + internalPriceId + " is missing from the GLOBAL index, but internal " +
						"price id " + indexedPriceId + " it also indexes is still live there - dropping it now would " +
						"silently unindex a price that still exists!"
				);
			}
		}
	}

	/**
	 * This class collects changes in {@link #priceIndexes} transactional map.
	 */
	public static class PriceIndexChanges implements Snapshotable<PriceIndexChanges.PriceIndexChangesMemento> {
		private final TransactionalContainerChanges<PriceListAndCurrencyPriceRefIndex, PriceListAndCurrencyPriceRefIndex> collectedPriceIndexChanges = new TransactionalContainerChanges<>();

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
			@Nonnull ContainerChangesMemento<PriceListAndCurrencyPriceRefIndex> collectedPriceIndexChanges
		) {
		}
	}

}
