/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.api.requestResponse.data.mutation.price;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.PriceContract;
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
	 * Relates to {@link PriceContract#getInnerRecordId()}.
	 */
	@Getter private final Integer innerRecordId;
	/**
	 * Relates to {@link PriceContract#getPriceWithoutTax()}.
	 */
	@Getter private final BigDecimal priceWithoutTax;
	/**
	 * Relates to {@link PriceContract#getTaxRate()}.
	 */
	@Getter private final BigDecimal taxRate;
	/**
	 * Relates to {@link PriceContract#getPriceWithTax()}.
	 */
	@Getter private final BigDecimal priceWithTax;
	/**
	 * Relates to {@link PriceContract#getValidity()}.
	 */
	@Getter private final DateTimeRange validity;
	/**
	 * Relates to {@link PriceContract#isSellable()}.
	 */
	@Getter private final boolean sellable;

	public UpsertPriceMutation(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean sellable
	) {
		super(new PriceKey(priceId, priceList, currency));
		this.innerRecordId = innerRecordId;
		this.priceWithoutTax = priceWithoutTax;
		this.taxRate = taxRate;
		this.priceWithTax = priceWithTax;
		this.validity = validity;
		this.sellable = sellable;
	}

	public UpsertPriceMutation(
		@Nonnull PriceKey priceKey,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean sellable
	) {
		super(priceKey);
		this.innerRecordId = innerRecordId;
		this.priceWithoutTax = priceWithoutTax;
		this.taxRate = taxRate;
		this.priceWithTax = priceWithTax;
		this.validity = validity;
		this.sellable = sellable;
	}

	public UpsertPriceMutation(
		@Nonnull PriceKey priceKey,
		@Nonnull PriceContract price
	) {
		super(priceKey);
		this.innerRecordId = price.getInnerRecordId();
		this.priceWithoutTax = price.getPriceWithoutTax();
		this.taxRate = price.getTaxRate();
		this.priceWithTax = price.getPriceWithTax();
		this.validity = price.getValidity();
		this.sellable = price.isSellable();
	}

	@Nonnull
	@Override
	public Serializable getSkipToken(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		return priceKey.getCurrency();
	}

	@Override
	public void verifyOrEvolveSchema(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaBuilder entitySchemaBuilder) throws InvalidMutationException {
		if (!entitySchemaBuilder.isWithPrice()) {
			if (entitySchemaBuilder.allows(EvolutionMode.ADDING_PRICES)) {
				if (entitySchemaBuilder.supportsCurrency(priceKey.getCurrency()) || entitySchemaBuilder.allows(EvolutionMode.ADDING_CURRENCIES)) {
					entitySchemaBuilder.withPriceInCurrency(priceKey.getCurrency());
				} else {
					throw new InvalidMutationException(
						"Entity `" + entitySchemaBuilder.getName() + "` doesn't support adding new price currency (`" + priceKey.getCurrency() + "`), " +
							"you need to change the schema definition for it first."
					);
				}
			} else {
				throw new InvalidMutationException(
					"Entity `" + entitySchemaBuilder.getName() + "` doesn't support prices, " +
						"you need to change the schema definition for it first."
				);
			}
		} else if (!entitySchemaBuilder.supportsCurrency(priceKey.getCurrency())) {
			if (entitySchemaBuilder.allows(EvolutionMode.ADDING_CURRENCIES)) {
				entitySchemaBuilder.withPriceInCurrency(priceKey.getCurrency());
			} else {
				throw new InvalidMutationException(
					"Entity `" + entitySchemaBuilder.getName() + "` doesn't support adding new price currency (`" + priceKey.getCurrency() + "`), " +
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
				priceKey,
				innerRecordId,
				priceWithoutTax,
				taxRate,
				priceWithTax,
				validity,
				sellable
			);
		} else if (
			!Objects.equals(existingValue.getInnerRecordId(), innerRecordId) ||
			!Objects.equals(existingValue.getPriceWithoutTax(), priceWithoutTax) ||
			!Objects.equals(existingValue.getTaxRate(), taxRate) ||
			!Objects.equals(existingValue.getPriceWithTax(), priceWithTax) ||
			!Objects.equals(existingValue.getValidity(), validity) ||
				existingValue.isSellable() != sellable
		) {
			return new Price(
				existingValue.getVersion() + 1,
				existingValue.getPriceKey(),
				innerRecordId,
				priceWithoutTax,
				taxRate,
				priceWithTax,
				validity,
				sellable
			);
		} else {
			return existingValue;
		}
	}

	@Override
	public long getPriority() {
		return PRICE_UPSERT_PRIORITY;
	}

	@Override
	public String toString() {
		return "upsert price `" + priceKey + "`";
	}

}
