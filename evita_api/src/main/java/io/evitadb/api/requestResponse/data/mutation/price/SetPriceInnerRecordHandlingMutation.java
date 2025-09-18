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
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.mutation.SchemaEvolvingLocalMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Prices;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.ContainerType;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * This mutation allows to set / remove {@link PriceInnerRecordHandling} behaviour of the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode
public class SetPriceInnerRecordHandlingMutation implements SchemaEvolvingLocalMutation<PricesContract, PriceInnerRecordHandling> {
	@Serial private static final long serialVersionUID = -2047915704875849615L;
	/**
	 * Inner price record handling that needs to be set to the entity.
	 */
	@Getter private final PriceInnerRecordHandling priceInnerRecordHandling;

	public SetPriceInnerRecordHandlingMutation(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		this.priceInnerRecordHandling = priceInnerRecordHandling;
	}

	@Nonnull
	@Override
	public PricesContract mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable PricesContract existingValue) {
		if (existingValue == null) {
			return new Prices(entitySchema, this.priceInnerRecordHandling);
		} else if (existingValue.getPriceInnerRecordHandling() != this.priceInnerRecordHandling) {
			return new Prices(
				entitySchema,
				existingValue.version() + 1,
				existingValue.getPrices(),
				this.priceInnerRecordHandling
			);
		} else {
			return existingValue;
		}
	}

	@Override
	public long getPriority() {
		// we need to run before the prices are upserted
		return UpsertPriceMutation.PRICE_UPSERT_PRIORITY + 1;
	}

	@Override
	public PriceInnerRecordHandling getComparableKey() {
		return this.priceInnerRecordHandling;
	}

	@Nonnull
	@Override
	public Serializable getSkipToken(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		return Price.class;
	}

	@Override
	public void verifyOrEvolveSchema(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaBuilder entitySchemaBuilder) throws InvalidMutationException {
		if (!entitySchemaBuilder.isWithPrice() && this.priceInnerRecordHandling != PriceInnerRecordHandling.NONE) {
			if (entitySchemaBuilder.allows(EvolutionMode.ADDING_PRICES)) {
				entitySchemaBuilder.withPrice();
			} else {
				throw new InvalidMutationException(
					"Entity " + entitySchemaBuilder.getName() + " doesn't support prices, " +
						"you need to change the schema definition for it first."
				);
			}
		}
	}

	@Nonnull
	@Override
	public ContainerType containerType() {
		return ContainerType.PRICE;
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "set price inner record handling to `" + this.priceInnerRecordHandling + "`";
	}

}
