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
import io.evitadb.index.mutation.index.EntityIndexLocalMutationExecutor.Target;
import io.evitadb.index.mutation.index.dataAccess.ExistingPriceSupplier;
import io.evitadb.spi.store.catalog.shared.model.PriceWithInternalIds;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Static utility interface that co-locates all price-index mutation routines to keep
 * {@link EntityIndexLocalMutationExecutor} focused on orchestration rather than implementation details.
 *
 * All methods are package-level static helpers invoked by {@link EntityIndexLocalMutationExecutor} and
 * {@link ReferenceIndexMutator} during entity write operations. They are never called through the interface itself —
 * the interface exists purely as a namespace grouping mechanism.
 *
 * ## Scope-awareness
 *
 * Every mutating method first checks whether prices are indexed in the target index's {@link Scope} by calling
 * {@link EntitySchema#isPriceIndexedInScope(Scope)}. If prices are not indexed in that scope the method is a no-op,
 * so callers do not need to perform this guard themselves.
 *
 * ## Price values in indexes
 *
 * Price amounts (`priceWithoutTax`, `priceWithTax`) are stored internally as scaled integers — a
 * {@link BigDecimal} value is multiplied by `10^indexedPricePlaces` (see
 * {@link EntitySchema#getIndexedPricePlaces()}) and converted to `int` via
 * {@link NumberUtils#convertExternalNumberToInt}. This avoids floating-point comparisons during range filtering and
 * sorting.
 *
 * ## Internal price IDs
 *
 * Each distinct price stored in an entity's `PricesStoragePart` is assigned a unique, entity-wide monotonically
 * increasing `internalPriceId` from a catalog-level sequence. This internal ID is used within price indexes to
 * cross-reference prices across entity primary keys and to enable efficient price-for-sale resolution.
 *
 * ## Undo support
 *
 * Every mutating method accepts an optional `undoActionConsumer`. When present, the inverse of every index
 * modification is registered with it, enabling semi-rollback of partial mutations on error.
 *
 * ## Update-as-remove-then-insert
 *
 * Price upserts are implemented as atomic remove + insert rather than in-place updates, because price indexes are
 * sorted data structures (range trees, bitmaps) that cannot be patched in-place. The former entry is removed
 * using data read from `formerPrice` / `existingPriceSupplier`, and the new entry is inserted using the incoming
 * mutation values.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see EntityIndexLocalMutationExecutor
 * @see ReferenceIndexMutator
 */
public interface PriceIndexMutator {

	/**
	 * Inserts or updates a single price in the given `entityIndex`. The current state of the price is fetched
	 * lazily from `existingPriceSupplier`, which allows the call site to defer storage-part access until actually
	 * needed.
	 *
	 * This overload is used when processing a live `UpsertPriceMutation` for a specific price identified by
	 * `priceKey`. The existing price and the current `PriceInnerRecordHandling` strategy are both resolved from
	 * `existingPriceSupplier` and then forwarded to the lower-level overload.
	 *
	 * When the target scope does not index prices (`entitySchema.isPriceIndexedInScope(scope)` returns `false`),
	 * this method is a complete no-op.
	 *
	 * @param executor             the mutation executor that provides the entity schema, primary-key resolver, and
	 *                             access to the underlying storage parts
	 * @param referenceSchema      the reference schema when the target index is a reduced reference index, or
	 *                             `null` when the target is the global entity index
	 * @param entityIndex          the entity index to update; its scope determines whether price indexing is active
	 * @param priceKey             composite key (price ID + price list + currency) identifying the price to upsert
	 * @param innerRecordId        inner-record grouping ID (used by `LOWEST_PRICE` / `SUM` strategies), or `null`
	 *                             when the entity uses `NONE` handling
	 * @param validity             optional time-range during which the price is valid; `null` means always valid
	 * @param priceWithoutTax      price amount excluding tax, expressed as an exact `BigDecimal`
	 * @param priceWithTax         price amount including tax, expressed as an exact `BigDecimal`
	 * @param indexed              whether the price should participate in index-based filtering and sorting; when
	 *                             `false` the price record is removed from any existing index entry (treated as removal)
	 * @param existingPriceSupplier supplier that provides the previously indexed price and the inner-record-handling
	 *                             mode; consulted only when a former entry must be removed
	 * @param internalIdSupplier   callback that resolves or allocates the stable internal price ID for a given
	 *                             `PriceKey` + `innerRecordId` pair
	 * @param undoActionConsumer   optional collector for undo lambdas; when non-null every index modification is
	 *                             accompanied by its inverse registered with this consumer
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
	 * Inserts or updates a single price in the given `entityIndex` using explicitly supplied former-price data.
	 *
	 * This overload is the workhorse invoked both when processing individual `UpsertPriceMutation` events and when
	 * bulk-re-indexing all prices after a `SetPriceInnerRecordHandlingMutation` changes the entity-wide strategy.
	 * Callers that already hold the `PriceWithInternalIds` record (e.g. during bulk operations) use this form to
	 * avoid redundant storage-part lookups.
	 *
	 * **Update algorithm:**
	 *
	 * 1. If `formerPrice` is non-null, exists, and is currently indexed, the old index entry is removed from
	 *    `entityIndex` first. The former entry's scaled integer prices, validity, and internal price ID are read
	 *    directly from `formerPrice`. An undo action that re-adds the former entry is registered when
	 *    `undoActionConsumer` is provided.
	 * 2. If `indexed` is `true`, the new price entry is inserted. Price amounts are scaled to integers using
	 *    `indexedPricePlaces`. A new internal price ID is obtained from `internalIdSupplier`. An undo action that
	 *    removes the newly inserted entry is registered when `undoActionConsumer` is provided.
	 *
	 * When `indexed` is `false` and `formerPrice` was indexed, this effectively removes the price from the index
	 * without inserting a replacement (the non-indexed price is still persisted in entity storage, just not visible
	 * to price queries).
	 *
	 * When the target scope does not index prices (`entitySchema.isPriceIndexedInScope(scope)` returns `false`),
	 * the entire method body is skipped.
	 *
	 * @param executor             the mutation executor providing the entity schema, primary-key resolver, and
	 *                             access to the underlying storage parts
	 * @param referenceSchema      the reference schema when the target index is a reduced reference index, or
	 *                             `null` when the target is the global entity index
	 * @param entityIndex          the entity index to update; its scope determines whether price indexing is active
	 * @param priceKey             composite key (price ID + price list + currency) identifying the price to upsert
	 * @param innerRecordId        inner-record grouping ID (used by `LOWEST_PRICE` / `SUM` strategies), or `null`
	 *                             when the entity uses `NONE` handling
	 * @param validity             optional time-range during which the price is valid; `null` means always valid
	 * @param priceWithoutTax      price amount excluding tax, expressed as an exact `BigDecimal`
	 * @param priceWithTax         price amount including tax, expressed as an exact `BigDecimal`
	 * @param indexed              whether the price should participate in index-based filtering and sorting
	 * @param formerPrice          the price entry currently stored in the entity (may be `null` for brand-new prices,
	 *                             or a dropped record for previously deleted ones); used to remove the old index entry
	 * @param innerRecordHandling  the entity's current price-grouping strategy (`NONE`, `LOWEST_PRICE`, or `SUM`)
	 * @param internalIdSupplier   callback that resolves or allocates the stable internal price ID for the given
	 *                             `PriceKey` + `innerRecordId` pair
	 * @param undoActionConsumer   optional collector for undo lambdas; when non-null every index modification is
	 *                             accompanied by its inverse registered with this consumer
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
		final EntitySchema entitySchema = executor.getEntitySchema();
		final int indexedPricePlaces = entitySchema.getIndexedPricePlaces();
		final Scope scope = entityIndex.getIndexKey().scope();
		if (entitySchema.isPriceIndexedInScope(scope)) {
			// remove former price first
			if (formerPrice != null && formerPrice.exists() && formerPrice.indexed()) {
				final int epkForRemoval = executor.getPrimaryKeyToIndex(IndexType.PRICE_INDEX, Target.EXISTING);
				final int formerInternalPriceId = formerPrice.getInternalPriceId();
				final Integer formerInnerRecordId = formerPrice.innerRecordId();
				final DateTimeRange formerValidity = formerPrice.validity();
				final int formerPriceWithoutTax = NumberUtils.convertExternalNumberToInt(formerPrice.priceWithoutTax(), indexedPricePlaces);
				final int formerPriceWithTax = NumberUtils.convertExternalNumberToInt(formerPrice.priceWithTax(), indexedPricePlaces);
				entityIndex.priceRemove(
					referenceSchema,
					epkForRemoval,
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
							epkForRemoval,
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
				final int epkForUpsert = executor.getPrimaryKeyToIndex(IndexType.PRICE_INDEX, Target.NEW);
				final int internalPriceId = internalIdSupplier.getInternalPriceId(priceKey, innerRecordId);
				final int priceWithoutTaxAsInt = NumberUtils.convertExternalNumberToInt(priceWithoutTax, indexedPricePlaces);
				final int priceWithTaxAsInt = NumberUtils.convertExternalNumberToInt(priceWithTax, indexedPricePlaces);
				final int priceId = entityIndex.addPrice(
					referenceSchema,
					epkForUpsert,
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
							epkForUpsert,
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
	 * Removes a single price from the given `entityIndex`. The current state of the price is fetched lazily from
	 * `existingPriceSupplier`, allowing the call site to defer storage-part access.
	 *
	 * This overload is used when processing a live `RemovePriceMutation`. The existing price record and the
	 * `PriceInnerRecordHandling` strategy are resolved from `existingPriceSupplier` and forwarded to the
	 * lower-level overload.
	 *
	 * When the target scope does not index prices, this method is a no-op.
	 *
	 * @param executor             the mutation executor providing entity schema and storage-part access
	 * @param referenceSchema      the reference schema for a reduced index, or `null` for the global entity index
	 * @param entityIndex          the entity index from which the price entry should be removed
	 * @param priceKey             composite key identifying the price to remove
	 * @param existingPriceSupplier supplier of the currently persisted price and the inner-record-handling mode
	 * @param undoActionConsumer   optional collector for undo lambdas; when non-null the inverse re-add is registered
	 * @throws EvitaInvalidUsageException if the price identified by `priceKey` does not exist in storage
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
	 * Removes a single price from the given `entityIndex` using an explicitly supplied `formerPrice` record.
	 *
	 * This overload is used during bulk-re-indexing (e.g. when the entire entity is removed from an index or when
	 * all prices need to be purged before re-inserting them under a new `PriceInnerRecordHandling` strategy). Callers
	 * that already have the `PriceWithInternalIds` object available use this form to avoid extra storage-part lookups.
	 *
	 * **Removal rules:**
	 *
	 * - If `formerPrice` is `null` the price does not exist and an `EvitaInvalidUsageException` is thrown — the
	 *   caller has requested removal of a price that was never persisted.
	 * - If `formerPrice` exists but is not marked as indexed (`formerPrice.indexed() == false`) the price has no
	 *   entry in the index and removal is silently skipped.
	 * - If `formerPrice` exists and is indexed, the entry is removed from `entityIndex` and — when
	 *   `undoActionConsumer` is provided — the inverse `addPrice` call is registered as an undo action.
	 *
	 * When the target scope does not index prices, the entire method body is skipped.
	 *
	 * @param executor             the mutation executor providing the entity schema and primary-key resolver
	 * @param referenceSchema      the reference schema for a reduced index, or `null` for the global entity index
	 * @param entityIndex          the entity index from which the price entry should be removed
	 * @param priceKey             composite key identifying the price to remove
	 * @param formerPrice          the currently persisted price record; `null` indicates the price does not exist
	 * @param innerRecordHandling  the entity's current price-grouping strategy (`NONE`, `LOWEST_PRICE`, or `SUM`)
	 * @param undoActionConsumer   optional collector for undo lambdas; when non-null the inverse re-add is registered
	 * @throws EvitaInvalidUsageException if `formerPrice` is `null`, meaning the price does not exist and cannot
	 *                                    be removed
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
		final EntitySchema entitySchema = executor.getEntitySchema();
		final int indexedPricePlaces = entitySchema.getIndexedPricePlaces();

		final Scope scope = entityIndex.getIndexKey().scope();
		if (entitySchema.isPriceIndexedInScope(scope)) {
			if (formerPrice != null) {
				if (formerPrice.exists() && formerPrice.indexed()) {
					final int epkForRemoval = executor.getPrimaryKeyToIndex(IndexType.PRICE_INDEX, Target.EXISTING);
					final Integer internalPriceIdRef = formerPrice.getInternalPriceId();
					Assert.isPremiseValid(internalPriceIdRef != null, "Price " + priceKey + " doesn't have internal id!");
					final int internalPriceId = internalPriceIdRef;
					final Integer innerRecordId = formerPrice.innerRecordId();
					final DateTimeRange validity = formerPrice.validity();
					final int priceWithoutTax = NumberUtils.convertExternalNumberToInt(formerPrice.priceWithoutTax(), indexedPricePlaces);
					final int priceWithTax = NumberUtils.convertExternalNumberToInt(formerPrice.priceWithTax(), indexedPricePlaces);
					entityIndex.priceRemove(
						referenceSchema,
						epkForRemoval,
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
								epkForRemoval,
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
	 * Creates a type-safe {@link PriceInternalIdProvider} that is bound to a single, already-known
	 * {@link PriceWithInternalIds} record.
	 *
	 * The returned provider validates at call time that the requested `priceKey` and `innerRecordId` match the bound
	 * `price`; if they do not, a `GenericEvitaInternalError` is thrown via {@link Assert#isPremiseValid}. This guards
	 * against programming mistakes where a provider created for one price is accidentally passed to a call site that
	 * operates on a different price.
	 *
	 * This factory method is used during bulk re-indexing operations (scope changes, inner-record-handling changes,
	 * entity removal + re-insertion) where the internal ID of each price is already available from the persisted
	 * `PricesStoragePart`, avoiding an unnecessary sequence allocation.
	 *
	 * @param price the price record whose internal ID should be returned by the provider
	 * @return a {@link PriceInternalIdProvider} that returns `price.internalPriceId()` after identity validation
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
	 * Strategy interface for resolving or allocating the stable internal price ID associated with a specific price.
	 *
	 * Each price stored in an entity is assigned a unique, monotonically increasing `internalPriceId` drawn from a
	 * catalog-level sequence (see `SequenceType.PRICE`). This ID is used across price indexes to cross-reference
	 * price entries for a given entity primary key and to support efficient price-for-sale computation.
	 *
	 * Two implementations are used in practice:
	 *
	 * - The lambda returned by {@link PriceIndexMutator#createPriceProvider(PriceWithInternalIds)} — binds the ID
	 *   of a price already available from persistent storage; used during bulk re-indexing.
	 * - An inline lambda in `EntityIndexLocalMutationExecutor#updatePriceIndex` — looks up any previously assigned
	 *   ID in the container accessor and falls back to allocating a new one from the catalog sequence when the price
	 *   is encountered for the first time or was previously not indexed (internal ID stored as `-1`).
	 */
	interface PriceInternalIdProvider {

		/**
		 * Returns the internal price ID for the price identified by `priceKey` and `innerRecordId`.
		 *
		 * For brand-new prices the implementation is expected to allocate a fresh ID from the catalog sequence and
		 * register the assignment so that subsequent calls for the same price return the same value. For prices that
		 * already exist in persistent storage the previously recorded ID must be returned to ensure index consistency.
		 *
		 * @param priceKey      composite key (price ID + price list + currency) identifying the price
		 * @param innerRecordId inner-record grouping ID relevant when the entity uses `LOWEST_PRICE` or `SUM`
		 *                      handling; `null` for entities using the `NONE` strategy
		 * @return the stable, entity-wide unique internal price ID for the given price
		 */
		int getInternalPriceId(@Nonnull PriceKey priceKey, @Nullable Integer innerRecordId);

	}

}
