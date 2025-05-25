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

package io.evitadb.store.entity.model.entity;

import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price.PriceIdFirstPriceKeyComparator;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.Prices;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.store.entity.model.entity.price.PriceInternalIdContainer;
import io.evitadb.store.entity.model.entity.price.PriceWithInternalIds;
import io.evitadb.store.model.EntityStoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.ToIntBiFunction;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * This container class represents {@link Prices} of single {@link Entity}. Contains {@link PriceInnerRecordHandling}
 * information and all {@link PriceContract prices} connected to the entity.
 *
 * Although query allows to fetch prices valid only in certain moment / price list / currency, all prices are stored
 * in single storage container because the data are expected to be small.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@EqualsAndHashCode(exclude = {"dirty", "sizeInBytes"})
@ToString(of = {"entityPrimaryKey", "priceInnerRecordHandling"})
public class PricesStoragePart implements EntityStoragePart {
	@Serial private static final long serialVersionUID = 3489626529648601062L;
	private static final PriceWithInternalIds[] EMPTY_PRICES = new PriceWithInternalIds[0];

	/**
	 * Id used for lookups in persistent storage for this particular container.
	 */
	@Getter private final int entityPrimaryKey;
	/**
	 * See {@link Prices#version()}.
	 */
	private final int version;
	/**
	 * See {@link Prices#getPriceInnerRecordHandling()}.
	 */
	@Getter private PriceInnerRecordHandling priceInnerRecordHandling = PriceInnerRecordHandling.NONE;
	/**
	 * See {@link Prices#getPrices()}. Prices are sorted in ascending order according to {@link PriceKey} comparator.
	 */
	@Getter private PriceWithInternalIds[] prices = EMPTY_PRICES;
	/**
	 * Contains information about size of this container in bytes.
	 */
	private final int sizeInBytes;
	/**
	 * Contains true if anything changed in this container.
	 */
	@Getter private boolean dirty;

	public PricesStoragePart(int entityPrimaryKey) {
		this.entityPrimaryKey = entityPrimaryKey;
		this.version = 0;
		this.sizeInBytes = -1;
	}

	public PricesStoragePart(
		int entityPrimaryKey,
		int version,
		@Nonnull PriceInnerRecordHandling priceInnerRecordHandling,
		@Nonnull PriceWithInternalIds[] prices,
		int sizeInBytes
	) {
		Assert.isPremiseValid(
			priceInnerRecordHandling != PriceInnerRecordHandling.UNKNOWN,
			() -> "Cannot store price storage container with unknown price inner record handling."
		);
		this.entityPrimaryKey = entityPrimaryKey;
		this.version = version;
		this.priceInnerRecordHandling = priceInnerRecordHandling;
		this.prices = prices;
		this.sizeInBytes = sizeInBytes;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return (long) this.entityPrimaryKey;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return this.entityPrimaryKey;
	}

	@Override
	public boolean isEmpty() {
		return (this.prices.length == 0 || Arrays.stream(this.prices).noneMatch(Droppable::exists)) &&
			this.priceInnerRecordHandling == PriceInnerRecordHandling.NONE;
	}

	@Nonnull
	@Override
	public OptionalInt sizeInBytes() {
		return this.sizeInBytes == -1 ? OptionalInt.empty() : OptionalInt.of(this.sizeInBytes);
	}

	/**
	 * Returns inner data wrapped to {@link Prices} object that can be wired to {@link Entity}.
	 */
	@Nonnull
	public Prices getAsPrices(@Nonnull EntitySchemaContract entitySchema) {
		return new Prices(
			entitySchema, this.version, Arrays.stream(this.prices).collect(Collectors.toList()), this.priceInnerRecordHandling
		);
	}

	/**
	 * Sets {@link PriceInnerRecordHandling} strategy for this entity.
	 */
	public void setPriceInnerRecordHandling(PriceInnerRecordHandling priceInnerRecordHandling) {
		if (this.priceInnerRecordHandling != priceInnerRecordHandling) {
			this.priceInnerRecordHandling = priceInnerRecordHandling;
			this.dirty = true;
		}
	}

	/**
	 * Adds new or replaces existing price of the entity.
	 */
	public void replaceOrAddPrice(
		@Nonnull PriceKey priceKey,
		@Nonnull UnaryOperator<PriceContract> mutator,
		@Nonnull ToIntFunction<PriceKey> internalPriceIdResolver
	) {
		final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			priceKey, this.prices,
			(ToIntBiFunction<PriceWithInternalIds, PriceKey>)
				(examinedPrice, pk) -> PriceIdFirstPriceKeyComparator.INSTANCE.compare(examinedPrice.priceKey(), pk)
		);
		final int position = insertionPosition.position();
		if (insertionPosition.alreadyPresent()) {
			final PriceWithInternalIds existingContract = this.prices[position];
			final PriceContract updatedPriceContract = mutator.apply(existingContract);
			if (this.prices[position].differsFrom(updatedPriceContract)) {
				final int existingInternalPriceId = existingContract.getInternalPriceId();
				this.prices[position] = new PriceWithInternalIds(
					updatedPriceContract,
					// -1 value is used of old prices that were not indexed and thus do not have internal id
					// now all prices have internal id, so we can safely use -1 as a marker for non-existing price id
					existingInternalPriceId == -1 ?
						internalPriceIdResolver.applyAsInt(priceKey) : existingInternalPriceId
				);
				this.dirty = true;
			}
		} else {
			final PriceContract newPrice = mutator.apply(null);
			this.prices = ArrayUtils.insertRecordIntoArrayOnIndex(
				new PriceWithInternalIds(newPrice, internalPriceIdResolver.applyAsInt(priceKey)),
				this.prices,
				position
			);
			this.dirty = true;
		}
	}

	/**
	 * Returns a price by its key, NULL if not present.
	 */
	@Nullable
	public PriceWithInternalIds getPriceByKey(@Nonnull PriceKey priceKey) {
		final int index = ArrayUtils.binarySearch(
			this.prices, priceKey,
			(examinedPrice, pk) -> PriceIdFirstPriceKeyComparator.INSTANCE.compare(examinedPrice.priceKey(), pk)
		);
		return index >= 0 ? this.prices[index] : null;
	}

	/**
	 * Finds already assigned internal price identifier for combination of `priceKey`. If no id is found the returned
	 * {@link PriceInternalIdContainer} contains nulls in its field.
	 */
	@Nonnull
	public OptionalInt findExistingInternalIds(@Nonnull PriceKey priceKey) {
		for (PriceWithInternalIds price : this.prices) {
			if (Objects.equals(priceKey, price.priceKey())) {
				return OptionalInt.of(price.getInternalPriceId());
			}
		}
		return OptionalInt.empty();
	}

	/**
	 * Returns version of the entity for storing (incremented by one, if anything changed).
	 */
	public int getVersion() {
		return this.dirty ? this.version + 1 : this.version;
	}

}
