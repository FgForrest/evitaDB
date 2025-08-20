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

package io.evitadb.index.mutation.index;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.IndexType;
import io.evitadb.index.mutation.index.dataAccess.ExistingPriceSupplier;
import io.evitadb.store.entity.model.entity.price.PriceWithInternalIds;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Consumer;

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
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndex entityIndex,
		@Nonnull PriceKey priceKey,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal priceWithTax,
		boolean indexed,
		@Nonnull ExistingPriceSupplier existingPriceSupplier,
		@Nonnull PriceInternalIdProvider internalIdSupplier,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final PriceWithInternalIds formerPrice = existingPriceSupplier.getPriceByKey(priceKey);
		final PriceInnerRecordHandling innerRecordHandling = existingPriceSupplier.getPriceInnerRecordHandling();
		priceUpsert(
			executor, referenceSchema, entityIndex, priceKey, innerRecordId, validity,
			priceWithoutTax, priceWithTax,
			indexed,
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
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndex entityIndex,
		@Nonnull PriceKey priceKey,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal priceWithTax,
		boolean indexed,
		@Nullable PriceWithInternalIds formerPrice,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull PriceInternalIdProvider internalIdSupplier,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.PRICE_INDEX);
		final EntitySchema entitySchema = executor.getEntitySchema();
		final int indexedPricePlaces = entitySchema.getIndexedPricePlaces();
		final Scope scope = entityIndex.getIndexKey().scope();
		if (entitySchema.isPriceIndexedInScope(scope)) {
			// remove former price first
			if (formerPrice != null && formerPrice.exists() && formerPrice.indexed()) {
				final int formerInternalPriceId = formerPrice.getInternalPriceId();
				final Integer formerInnerRecordId = formerPrice.innerRecordId();
				final DateTimeRange formerValidity = formerPrice.validity();
				final int formerPriceWithoutTax = NumberUtils.convertExternalNumberToInt(formerPrice.priceWithoutTax(), indexedPricePlaces);
				final int formerPriceWithTax = NumberUtils.convertExternalNumberToInt(formerPrice.priceWithTax(), indexedPricePlaces);
				entityIndex.priceRemove(
					referenceSchema,
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
							referenceSchema,
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
			if (indexed) {
				final int internalPriceId = internalIdSupplier.getInternalPriceId(priceKey, innerRecordId);
				final int priceWithoutTaxAsInt = NumberUtils.convertExternalNumberToInt(priceWithoutTax, indexedPricePlaces);
				final int priceWithTaxAsInt = NumberUtils.convertExternalNumberToInt(priceWithTax, indexedPricePlaces);
				final int priceId = entityIndex.addPrice(
					referenceSchema,
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
							referenceSchema,
							entityPrimaryKey,
							priceId,
							priceKey, innerRecordHandling, innerRecordId,
							validity,
							priceWithoutTaxAsInt,
							priceWithTaxAsInt
						)
					);
				}
			}
		}
	}

	/**
	 * Method handles updating price index in the situation when entity price is removed.
	 */
	static void priceRemove(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndex entityIndex,
		@Nonnull PriceKey priceKey,
		@Nonnull ExistingPriceSupplier existingPriceSupplier,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final PriceWithInternalIds formerPrice = existingPriceSupplier.getPriceByKey(priceKey);
		final PriceInnerRecordHandling innerRecordHandling = existingPriceSupplier.getPriceInnerRecordHandling();

		priceRemove(executor, referenceSchema, entityIndex, priceKey, formerPrice, innerRecordHandling, undoActionConsumer);
	}

	/**
	 * Method handles updating price index in the situation when entity price is removed.
	 */
	static void priceRemove(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndex entityIndex,
		@Nonnull PriceKey priceKey,
		@Nullable PriceWithInternalIds formerPrice,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.PRICE_INDEX);
		final EntitySchema entitySchema = executor.getEntitySchema();
		final int indexedPricePlaces = entitySchema.getIndexedPricePlaces();

		final Scope scope = entityIndex.getIndexKey().scope();
		if (entitySchema.isPriceIndexedInScope(scope)) {
			if (formerPrice != null) {
				if (formerPrice.exists() && formerPrice.indexed()) {
					final Integer internalPriceIdRef = formerPrice.getInternalPriceId();
					Assert.isPremiseValid(internalPriceIdRef != null, "Price " + priceKey + " doesn't have internal id!");
					final int internalPriceId = internalPriceIdRef;
					final Integer innerRecordId = formerPrice.innerRecordId();
					final DateTimeRange validity = formerPrice.validity();
					final int priceWithoutTax = NumberUtils.convertExternalNumberToInt(formerPrice.priceWithoutTax(), indexedPricePlaces);
					final int priceWithTax = NumberUtils.convertExternalNumberToInt(formerPrice.priceWithTax(), indexedPricePlaces);
					entityIndex.priceRemove(
						referenceSchema,
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
								referenceSchema,
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
	}

	/**
	 * Returns simple function that returns exactly the `price` passed in argument. The function checks whether passed
	 * bi function arguments match the `price` identification and if not exception is thrown.
	 */
	@Nonnull
	static PriceInternalIdProvider createPriceProvider(@Nonnull PriceWithInternalIds price) {
		return (priceKey, innerRecordId) -> {
			Assert.isPremiseValid(
				priceKey.equals(price.priceKey()) && Objects.equals(innerRecordId, price.innerRecordId()),
				"Unexpected price call!"
			);
			return price.internalPriceId();
		};
	}

	/**
	 * The PriceInternalIdProvider interface defines a method for retrieving the internal ID associated with a specific price.
	 */
	interface PriceInternalIdProvider {

		/**
		 * Retrieves the internal price ID associated with the provided {@link PriceKey} and optional inner record ID.
		 *
		 * @param priceKey the key identifying the price, which is a combination of price ID, price list, and currency.
		 * @param innerRecordId an optional identifier that may provide additional context within an otherwise identified price.
		 * @return the internal ID corresponding to the given price key and inner record ID.
		 */
		int getInternalPriceId(@Nonnull PriceKey priceKey, @Nullable Integer innerRecordId);

	}

}
