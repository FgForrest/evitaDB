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
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Currency;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

import static io.evitadb.utils.Assert.notNull;

/**
 * Price index contains data structures that allow processing price related filtering and sorting constraints such as
 * {@link io.evitadb.api.query.filter.PriceBetween}, {@link io.evitadb.api.query.filter.PriceValidIn},
 * {@link PriceNatural}.
 *
 * For each combination of {@link PriceContract#priceList()} and {@link PriceContract#currency()} it maintains
 * separate filtering index. Pre-sorted indexes are maintained for all prices regardless of their price list
 * relation because there is no guarantee that there will be currency or price list part of the query.
 *
 * This abstract class unifies base logic both for {@link PriceSuperIndex} that works with full data set and
 * {@link PriceRefIndex} that works with slimmed down data referencing the original ones in super index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
abstract class AbstractPriceIndex<T extends PriceListAndCurrencyPriceIndex> implements IndexDataStructure, Serializable, PriceIndexContract {
	@Serial private static final long serialVersionUID = 7715100845881804377L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * Prices one {@link PriceIndexKey} used as a key of the per-combination index map.
	 *
	 * The key object is the map's own — it is created per price-list / currency combination and handed to the
	 * sub-index constructor, which keeps only a reference slot to it. What the key *points at* is not: its
	 * `recordHandling` is an enum constant and its `currency` a {@link Currency} interned by the JVM, both owned for
	 * the lifetime of their class loader; the price list name comes from the entity schema's price definitions and is
	 * held by every key of that price list, so it is scaffolding rather than this map's content. All three therefore
	 * contribute their slot alone, exactly as an injected comparator does elsewhere.
	 */
	protected static final ToLongFunction<PriceIndexKey> PRICE_INDEX_KEY_SIZER = key -> {
		final VMLayout layout = VMLayout.current();
		// the recordHandling, currency and priceList slots, plus the memoized hash code
		return layout.sizeOfObject(3L * layout.referenceSize() + Integer.BYTES);
	};

	@Nonnull
	@Override
	public Collection<? extends PriceListAndCurrencyPriceIndex> getPriceListAndCurrencyIndexes() {
		return getPriceIndexes().values();
	}

	@Nonnull
	@Override
	public Stream<? extends PriceListAndCurrencyPriceIndex> getPriceIndexesStream(@Nonnull Currency currency, @Nonnull PriceInnerRecordHandling innerRecordHandling) {
		return getPriceIndexes()
			.values()
			.stream()
			.filter(it -> {
				final PriceIndexKey priceIndexKey = it.getPriceIndexKey();
				return innerRecordHandling.equals(priceIndexKey.getRecordHandling()) &&
					currency.equals(priceIndexKey.getCurrency());
			});
	}

	@Nonnull
	@Override
	public Stream<? extends PriceListAndCurrencyPriceIndex> getPriceIndexesStream(@Nonnull String priceListName, @Nonnull PriceInnerRecordHandling innerRecordHandling) {
		return getPriceIndexes()
			.values()
			.stream()
			.filter(it -> {
				final PriceIndexKey priceIndexKey = it.getPriceIndexKey();
				return innerRecordHandling.equals(priceIndexKey.getRecordHandling()) &&
					priceListName.equals(priceIndexKey.getPriceList());
			});
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
		int priceWithTax,
		@Nonnull PriceSuperIndex superPriceIndex
	) {
		final PriceIndexKey lookupKey = new PriceIndexKey(priceKey.priceList(), priceKey.currency(), innerRecordHandling);
		// the combination almost always exists already - probe first so the common path allocates no capturing lambda
		T priceListIndex = this.getPriceIndexes().get(lookupKey);
		if (priceListIndex == null) {
			priceListIndex = this.getPriceIndexes().computeIfAbsent(
				lookupKey, key -> createNewPriceListAndCurrencyIndex(key, superPriceIndex)
			);
		}
		return addPrice(
			referenceSchema, priceListIndex, entityPrimaryKey,
			internalPriceId, priceKey.priceId(), innerRecordId,
			validity, priceWithoutTax, priceWithTax, superPriceIndex
		);
	}

	@Override
	public void priceRemove(
		@Nullable ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		int internalPriceId, @Nonnull PriceKey priceKey,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax,
		@Nonnull PriceSuperIndex superPriceIndex
	) {
		final PriceIndexKey lookupKey = new PriceIndexKey(priceKey.priceList(), priceKey.currency(), innerRecordHandling);
		final T priceListIndex = this.getPriceIndexes().get(lookupKey);
		notNull(priceListIndex, "Price index for price list " + priceKey.priceList() + " and currency " + priceKey.currency() + " not found!");

		removePrice(
			referenceSchema, priceListIndex, entityPrimaryKey,
			internalPriceId, priceKey.priceId(), innerRecordId,
			validity, priceWithoutTax, priceWithTax, superPriceIndex
		);

		if (!priceListIndex.isTerminated() && priceListIndex.isEmpty()) {
			removeExistingIndex(lookupKey, priceListIndex);
		}
	}

	@Nullable
	@Override
	public T getPriceIndex(@Nonnull String priceList, @Nonnull Currency currency, @Nonnull PriceInnerRecordHandling innerRecordHandling) {
		return getPriceIndex(new PriceIndexKey(priceList, currency, innerRecordHandling));
	}

	@Nullable
	@Override
	public T getPriceIndex(@Nonnull PriceIndexKey priceListAndCurrencyKey) {
		return this.getPriceIndexes().get(priceListAndCurrencyKey);
	}

	@Override
	public boolean isPriceIndexEmpty() {
		return this.getPriceIndexes().isEmpty();
	}

	/**
	 * Method returns collection of all modified parts of this index that were modified and needs to be stored.
	 */
	public void getModifiedStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges trappedChanges) {
		for (T index : this.getPriceIndexes().values()) {
			// each per-list index appends its own parts: the ref index (and any non-paged index) emits a single
			// whole-index part, while the super price index emits granular PAGED leaf pages when its tree spans
			// multiple leaves
			index.appendStorageParts(entityIndexPrimaryKey, trappedChanges);
		}
	}

	@Override
	public void resetDirty() {
		for (PriceListAndCurrencyPriceIndex<?> priceIndex : getPriceIndexes().values()) {
			priceIndex.resetDirty();
		}
	}

	/*
		PROTECTED METHODS
	 */

	@Nonnull
	protected abstract T createNewPriceListAndCurrencyIndex(
		@Nonnull PriceIndexKey lookupKey,
		@Nonnull PriceSuperIndex superPriceIndex
	);

	protected void removeExistingIndex(@Nonnull PriceIndexKey lookupKey, @Nonnull T priceListIndex) {
		final T removedIndex = getPriceIndexes().remove(lookupKey);
		removedIndex.terminate();
	}

	protected abstract int addPrice(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull T priceListIndex,
		int entityPrimaryKey,
		int internalPriceId,
		int priceId,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax,
		@Nonnull PriceSuperIndex superPriceIndex
	);

	protected abstract void removePrice(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull T priceListIndex,
		int entityPrimaryKey,
		int internalPriceId,
		int priceId,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax,
		@Nonnull PriceSuperIndex superPriceIndex
	);

	@Nonnull
	protected abstract Map<PriceIndexKey, T> getPriceIndexes();

}
