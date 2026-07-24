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
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
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
	/**
	 * Resolver that maps a {@link PriceIndexKey} to the memory-expensive
	 * {@link PriceListAndCurrencyPriceSuperIndex} that backs each reduced ref index. The owning entity collection wires
	 * it in through {@link #wireSuperIndexes(Function)}, and in production always supplies a {@link SuperIndexResolver}
	 * that closes over the collection's own {@link GlobalEntityIndex} — no {@link io.evitadb.core.catalog.Catalog} nor
	 * owning-collection back-reference is retained. It is used to wire newly created per-price-list ref indexes to the
	 * shared price record instances. The field is typed as a bare {@link Function} only so unit tests can wire a stub
	 * resolver directly; the concrete {@link SuperIndexResolver} keeps its captured GLOBAL inspectable for the
	 * carry-by-reference identity check.
	 */
	private Function<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> superIndexResolver;

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
	 * Wires the resolver that this index uses to obtain the {@link PriceListAndCurrencyPriceSuperIndex} for each
	 * price-list / currency combination, and immediately wires every already-present combination index to its super
	 * index. Called by the owning entity collection, which resolves super indexes from its own
	 * {@link GlobalEntityIndex} (same scope, same collection) instead of routing through the catalog.
	 *
	 * @param superIndexResolver maps a {@link PriceIndexKey} to the super price index backing that combination
	 */
	public void wireSuperIndexes(@Nonnull Function<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> superIndexResolver) {
		Assert.isPremiseValid(this.superIndexResolver == null, "Super index resolver was already wired to this index!");
		this.superIndexResolver = superIndexResolver;
		// wire every existing combination index to its super index
		this.priceIndexes.values().forEach(it -> it.wireSuperIndex(superIndexResolver.apply(it.getPriceIndexKey())));
	}

	/**
	 * Wires this index's price ref chain to the super price indexes held by the given {@link GlobalEntityIndex}, or —
	 * when the chain was already wired and this index was carried across a catalog version **by reference** — verifies
	 * that the captured GLOBAL is still this version's GLOBAL instead of re-wiring (the single-assign guard in
	 * {@link #wireSuperIndexes(Function)} forbids a second wire).
	 *
	 * A carried reduced index keeps the {@link SuperIndexResolver} it was originally wired with; because the
	 * clean-collection copy carries the GLOBAL entity index by reference in the very same step, that captured GLOBAL
	 * must be identity-equal to the one resolved for the new version. A mismatch means a stale super-index pointer
	 * survived a version bump — surface it here, at attach time, rather than letting a query read a retired version's
	 * super price index.
	 *
	 * @param globalIndex the GLOBAL entity index of this reduced index's scope, owning the backing super price indexes
	 */
	public void wireOrVerifySuperIndexes(@Nonnull GlobalEntityIndex globalIndex) {
		if (this.superIndexResolver == null) {
			// fresh (re-shelled on disk load, lazily created, or merged) index: wire its price ref chain for the first time
			wireSuperIndexes(new SuperIndexResolver(globalIndex));
		} else {
			// carried-by-reference index: prove its existing wiring still points at the current version's GLOBAL entity
			// index rather than silently skipping — GLOBAL identity is a strict superset of per-combo super identity
			// (any dirty combo yields a new combo instance, hence a changed combo map, hence a new GLOBAL)
			Assert.isPremiseValid(
				this.superIndexResolver instanceof SuperIndexResolver sir && sir.globalIndex() == globalIndex,
				"Carried price ref index is wired to a stale GLOBAL entity index (expected the current version's GLOBAL)!"
			);
		}
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

	/**
	 * Produces an unwired shallow copy of this **clean** price ref index for the commit-merge prune. Every per-price-list
	 * / currency combination index is re-shelled through {@link PriceListAndCurrencyPriceRefIndex#createCarryByReferenceCopy()}
	 * (adopting its record tree by reference), and the resulting index carries no super-index resolver
	 * ({@code superIndexResolver == null}) so the owning collection can wire it to the CURRENT catalog version's GLOBAL
	 * via {@link #wireOrVerifySuperIndexes(GlobalEntityIndex)}.
	 *
	 * Used when a clean reduced entity index is carried across a catalog version whose GLOBAL was rebuilt: the reduced
	 * index's memory-expensive sub-structures are shared by reference, but its price chain must be re-wired to the new
	 * GLOBAL's super, which the single-assign {@link PriceListAndCurrencyPriceRefIndex#wireSuperIndex} guard forbids doing
	 * in place on the shared combos — hence the thin re-shell here.
	 *
	 * @return a fresh, unwired price ref index whose combination indexes share this one's record trees by reference
	 */
	@Nonnull
	public PriceRefIndex createCarryByReferenceCopy() {
		final Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> copiedIndexes =
			new HashMap<>(this.priceIndexes.size());
		for (final Map.Entry<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> entry : this.priceIndexes.entrySet()) {
			copiedIndexes.put(entry.getKey(), entry.getValue().createCarryByReferenceCopy());
		}
		return new PriceRefIndex(this.scope, copiedIndexes);
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
		newPriceListIndex.wireSuperIndex(this.superIndexResolver.apply(lookupKey));
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
