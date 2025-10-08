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

package io.evitadb.api.requestResponse.data.mutation.price;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.SchemaEvolvingLocalMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.DateTimeRange;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * This mutation allows to create / update {@link Price} of the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class UpsertPriceMutation extends PriceMutation implements SchemaEvolvingLocalMutation<PriceContract, PriceKey> {
	public static final long PRICE_UPSERT_PRIORITY = PRIORITY_UPSERT;
	@Serial private static final long serialVersionUID = 6899193328262302023L;
	/**
	 * Relates to {@link PriceContract#innerRecordId()}.
	 */
	@Getter private final Integer innerRecordId;
	/**
	 * Relates to {@link PriceContract#priceWithoutTax()}.
	 */
	@Getter private final BigDecimal priceWithoutTax;
	/**
	 * Relates to {@link PriceContract#taxRate()}.
	 */
	@Getter private final BigDecimal taxRate;
	/**
	 * Relates to {@link PriceContract#priceWithTax()}.
	 */
	@Getter private final BigDecimal priceWithTax;
	/**
	 * Relates to {@link PriceContract#validity()}.
	 */
	@Getter private final DateTimeRange validity;
	/**
	 * Relates to {@link PriceContract#indexed()}.
	 */
	@Getter private final boolean indexed;

	public UpsertPriceMutation(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean indexed
	) {
		super(new PriceKey(priceId, priceList, currency));
		this.innerRecordId = innerRecordId;
		this.priceWithoutTax = priceWithoutTax;
		this.taxRate = taxRate;
		this.priceWithTax = priceWithTax;
		this.validity = validity;
		this.indexed = indexed;
	}

	public UpsertPriceMutation(
		@Nonnull PriceKey priceKey,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean indexed
	) {
		super(priceKey);
		this.innerRecordId = innerRecordId;
		this.priceWithoutTax = priceWithoutTax;
		this.taxRate = taxRate;
		this.priceWithTax = priceWithTax;
		this.validity = validity;
		this.indexed = indexed;
	}

	public UpsertPriceMutation(
		@Nonnull PriceKey priceKey,
		@Nonnull PriceContract price
	) {
		super(priceKey);
		this.innerRecordId = price.innerRecordId();
		this.priceWithoutTax = price.priceWithoutTax();
		this.taxRate = price.taxRate();
		this.priceWithTax = price.priceWithTax();
		this.validity = price.validity();
		this.indexed = price.indexed();
	}

	private UpsertPriceMutation(
		@Nonnull PriceKey priceKey,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean indexed,
		long decisiveTimestamp
	) {
		super(priceKey, decisiveTimestamp);
		this.innerRecordId = innerRecordId;
		this.priceWithoutTax = priceWithoutTax;
		this.taxRate = taxRate;
		this.priceWithTax = priceWithTax;
		this.validity = validity;
		this.indexed = indexed;
	}

	@Nonnull
	@Override
	public Serializable getSkipToken(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		return this.priceKey.currency();
	}

	@Override
	public void verifyOrEvolveSchema(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaBuilder entitySchemaBuilder) throws InvalidMutationException {
		if (!entitySchemaBuilder.isWithPrice()) {
			if (entitySchemaBuilder.allows(EvolutionMode.ADDING_PRICES)) {
				if (entitySchemaBuilder.supportsCurrency(this.priceKey.currency()) || entitySchemaBuilder.allows(EvolutionMode.ADDING_CURRENCIES)) {
					entitySchemaBuilder.withPriceInCurrency(this.priceKey.currency());
				} else {
					throw new InvalidMutationException(
						"Entity `" + entitySchemaBuilder.getName() + "` doesn't support adding new price currency (`" + this.priceKey.currency() + "`), " +
							"you need to change the schema definition for it first."
					);
				}
			} else {
				throw new InvalidMutationException(
					"Entity `" + entitySchemaBuilder.getName() + "` doesn't support prices, " +
						"you need to change the schema definition for it first."
				);
			}
		} else if (!entitySchemaBuilder.supportsCurrency(this.priceKey.currency())) {
			if (entitySchemaBuilder.allows(EvolutionMode.ADDING_CURRENCIES)) {
				entitySchemaBuilder.withPriceInCurrency(this.priceKey.currency());
			} else {
				throw new InvalidMutationException(
					"Entity `" + entitySchemaBuilder.getName() + "` doesn't support adding new price currency (`" + this.priceKey.currency() + "`), " +
						"you need to change the schema definition for it first."
				);
			}
		}
	}

	@Nonnull
	@Override
	public PriceContract mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable PriceContract existingValue) {
		if (existingValue == null) {
			return new Price(
				this.priceKey,
				this.innerRecordId,
				this.priceWithoutTax,
				this.taxRate,
				this.priceWithTax,
				this.validity,
				this.indexed
			);
		} else if (
			!Objects.equals(existingValue.innerRecordId(), this.innerRecordId) ||
			!Objects.equals(existingValue.priceWithoutTax(), this.priceWithoutTax) ||
			!Objects.equals(existingValue.taxRate(), this.taxRate) ||
			!Objects.equals(existingValue.priceWithTax(), this.priceWithTax) ||
			!Objects.equals(existingValue.validity(), this.validity) ||
				existingValue.indexed() != this.indexed ||
				existingValue.dropped()
		) {
			return new Price(
				existingValue.version() + 1,
				existingValue.priceKey(),
				this.innerRecordId,
				this.priceWithoutTax,
				this.taxRate,
				this.priceWithTax,
				this.validity,
				this.indexed
			);
		} else {
			return existingValue;
		}
	}

	@Override
	public long getPriority() {
		return PRICE_UPSERT_PRIORITY;
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Nonnull
	@Override
	public LocalMutation<?, ?> withDecisiveTimestamp(long newDecisiveTimestamp) {
		return new UpsertPriceMutation(
			this.priceKey, this.innerRecordId,
			this.priceWithoutTax, this.taxRate, this.priceWithTax,
			this.validity, this.indexed, newDecisiveTimestamp
		);
	}

	@Override
	public String toString() {
		return "upsert price `" + this.priceKey + "`";
	}

}
