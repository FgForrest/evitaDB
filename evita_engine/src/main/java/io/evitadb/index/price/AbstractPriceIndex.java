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
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Currency;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.utils.Assert.notNull;
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
 * This abstract class unifies base logic both for {@link PriceSuperIndex} that works with full data set and
 * {@link PriceRefIndex} that works with slimmed down data referencing the original ones in super index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
abstract class AbstractPriceIndex<T extends PriceListAndCurrencyPriceIndex> implements IndexDataStructure, Serializable, PriceIndexContract {
	@Serial private static final long serialVersionUID = 7715100845881804377L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

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
		int priceWithTax
	) {
		final T priceListIndex = this.getPriceIndexes().computeIfAbsent(
			new PriceIndexKey(priceKey.priceList(), priceKey.currency(), innerRecordHandling),
			this::createNewPriceListAndCurrencyIndex
		);
		return addPrice(
			referenceSchema, priceListIndex, entityPrimaryKey,
			internalPriceId, priceKey.priceId(), innerRecordId,
			validity, priceWithoutTax, priceWithTax
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
		int priceWithTax
	) {
		final PriceIndexKey lookupKey = new PriceIndexKey(priceKey.priceList(), priceKey.currency(), innerRecordHandling);
		final T priceListIndex = this.getPriceIndexes().get(lookupKey);
		notNull(priceListIndex, "Price index for price list " + priceKey.priceList() + " and currency " + priceKey.currency() + " not found!");

		removePrice(
			referenceSchema, priceListIndex, entityPrimaryKey,
			internalPriceId, priceKey.priceId(), innerRecordId,
			validity, priceWithoutTax, priceWithTax
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
			ofNullable(index.createStoragePart(entityIndexPrimaryKey))
				.ifPresent(trappedChanges::addChangeToStore);
		}
	}

	@Override
	public void resetDirty() {
		for (PriceListAndCurrencyPriceIndex<?,?> priceIndex : getPriceIndexes().values()) {
			priceIndex.resetDirty();
		}
	}

	/*
		PROTECTED METHODS
	 */

	@Nonnull
	protected abstract T createNewPriceListAndCurrencyIndex(@Nonnull PriceIndexKey lookupKey);

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
		int priceWithTax
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
		int priceWithTax
	);

	@Nonnull
	protected abstract Map<PriceIndexKey, T> getPriceIndexes();

}
