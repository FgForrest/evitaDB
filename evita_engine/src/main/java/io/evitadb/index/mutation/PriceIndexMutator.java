/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.mutation;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.IndexType;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.price.PriceInternalIdContainer;
import io.evitadb.store.entity.model.entity.price.PriceWithInternalIds;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static io.evitadb.utils.NumberUtils.convertToInt;

/**
 * This interface is used to co-locate price mutating routines which are rather procedural and long to avoid excessive
 * amount of code in {@link EntityIndexLocalMutationExecutor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface PriceIndexMutator {

	/**
	 * Method handles inserting or updating price in index according the changes in passed arguments. Update is executed
	 * as removal of previously stored value and inserting new price information to the indexes again.
	 */
	static void priceUpsert(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		@Nonnull String entityType,
		int entityPrimaryKey,
		@Nonnull PriceKey priceKey,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal priceWithTax,
		boolean sellable,
		@Nonnull BiFunction<PriceKey, Integer, PriceInternalIdContainer> internalIdSupplier,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final PricesStoragePart entityFormerPrices = executor.getContainerAccessor().getPriceStoragePart(entityType, entityPrimaryKey);
		final PriceWithInternalIds formerPrice = entityFormerPrices.getPriceByKey(priceKey);
		final PriceInnerRecordHandling innerRecordHandling = entityFormerPrices.getPriceInnerRecordHandling();
		priceUpsert(
			entityType, executor, entityIndex, priceKey, innerRecordId, validity,
			priceWithoutTax, priceWithTax,
			sellable,
			formerPrice, innerRecordHandling,
			internalIdSupplier,
			undoActionConsumer
		);
	}

	/**
	 * Method handles inserting or updating price in index according the changes in passed arguments. Update is executed
	 * as removal of previously stored value and inserting new price information to the indexes again.
	 */
	static void priceUpsert(
		@Nonnull String entityType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		@Nonnull PriceKey priceKey,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal priceWithTax,
		boolean sellable,
		@Nullable PriceWithInternalIds formerPrice,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull BiFunction<PriceKey, Integer, PriceInternalIdContainer> internalIdSupplier,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.PRICE_INDEX);
		final int indexedPricePlaces = executor.getEntitySchema().getIndexedPricePlaces();
		// remove former price first
		if (formerPrice != null && formerPrice.exists() && formerPrice.sellable()) {
			final Integer formerInternalPriceId = Objects.requireNonNull(formerPrice.getInternalPriceId());
			final Integer formerInnerRecordId = formerPrice.innerRecordId();
			final DateTimeRange formerValidity = formerPrice.validity();
			final int formerPriceWithoutTax = convertToInt(formerPrice.priceWithoutTax(), indexedPricePlaces);
			final int formerPriceWithTax = convertToInt(formerPrice.priceWithTax(), indexedPricePlaces);
			entityIndex.priceRemove(
				entityPrimaryKey,
				formerInternalPriceId,
				priceKey, innerRecordHandling, formerInnerRecordId,
				formerValidity,
				formerPriceWithoutTax,
				formerPriceWithTax
			);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> entityIndex.addPrice(
						entityPrimaryKey,
						formerInternalPriceId,
						priceKey, innerRecordHandling, formerInnerRecordId,
						formerValidity,
						formerPriceWithoutTax,
						formerPriceWithTax
					)
				);
			}
		}
		// now insert new price
		if (sellable) {
			final PriceInternalIdContainer internalPriceIds = internalIdSupplier.apply(priceKey, innerRecordId);
			final Integer internalPriceId = internalPriceIds.getInternalPriceId();
			final int priceWithoutTaxAsInt = convertToInt(priceWithoutTax, indexedPricePlaces);
			final int priceWithTaxAsInt = convertToInt(priceWithTax, indexedPricePlaces);
			final PriceInternalIdContainer priceId = entityIndex.addPrice(
				entityPrimaryKey,
				internalPriceId,
				priceKey, innerRecordHandling, innerRecordId,
				validity,
				priceWithoutTaxAsInt,
				priceWithTaxAsInt
			);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> entityIndex.priceRemove(
						entityPrimaryKey,
						internalPriceId,
						priceKey, innerRecordHandling, innerRecordId,
						validity,
						priceWithoutTaxAsInt,
						priceWithTaxAsInt
					)
				);
			}
			executor.getContainerAccessor().registerAssignedPriceId(entityType, entityPrimaryKey, priceKey, innerRecordId, priceId);
		}
	}

	/**
	 * Method handles updating price index in the situation when entity price is removed.
	 */
	static void priceRemove(
		@Nonnull String entityType,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		@Nonnull PriceKey priceKey,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.PRICE_INDEX);
		final PricesStoragePart entityFormerPrices = executor.getContainerAccessor().getPriceStoragePart(entityType, entityPrimaryKey);
		final PriceWithInternalIds formerPrice = entityFormerPrices.getPriceByKey(priceKey);
		final PriceInnerRecordHandling innerRecordHandling = entityFormerPrices.getPriceInnerRecordHandling();

		priceRemove(executor, entityIndex, priceKey, formerPrice, innerRecordHandling, undoActionConsumer);
	}

	/**
	 * Method handles updating price index in the situation when entity price is removed.
	 */
	static void priceRemove(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		@Nonnull PriceKey priceKey,
		@Nullable PriceWithInternalIds formerPrice,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.PRICE_INDEX);
		final int indexedPricePlaces = executor.getEntitySchema().getIndexedPricePlaces();

		if (formerPrice != null) {
			if (formerPrice.exists() && formerPrice.sellable()) {
				final int internalPriceId = formerPrice.getInternalPriceId();
				final Integer innerRecordId = formerPrice.innerRecordId();
				final DateTimeRange validity = formerPrice.validity();
				final int priceWithoutTax = convertToInt(formerPrice.priceWithoutTax(), indexedPricePlaces);
				final int priceWithTax = convertToInt(formerPrice.priceWithTax(), indexedPricePlaces);
				entityIndex.priceRemove(
					entityPrimaryKey,
					internalPriceId,
					priceKey,
					innerRecordHandling,
					innerRecordId,
					validity,
					priceWithoutTax,
					priceWithTax
				);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> entityIndex.addPrice(
							entityPrimaryKey,
							internalPriceId,
							priceKey,
							innerRecordHandling,
							innerRecordId,
							validity,
							priceWithoutTax,
							priceWithTax
						)
					);
				}
			}
		} else {
			throw new EvitaInvalidUsageException("Price " + priceKey + " doesn't exist and cannot be removed!");
		}
	}

	/**
	 * Returns simple function that returns exactly the `price` passed in argument. The function checks whether passed
	 * bi function arguments match the `price` identification and if not exception is thrown.
	 */
	@Nonnull
	static BiFunction<PriceKey, Integer, PriceInternalIdContainer> createPriceProvider(@Nonnull PriceWithInternalIds price) {
		return (priceKey, innerRecordId) -> {
			Assert.isPremiseValid(
				priceKey.equals(price.priceKey()) && Objects.equals(innerRecordId, price.innerRecordId()),
				"Unexpected price call!"
			);
			return price;
		};
	}
}
