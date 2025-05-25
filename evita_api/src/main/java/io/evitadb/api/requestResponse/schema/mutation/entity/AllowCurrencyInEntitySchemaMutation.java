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

package io.evitadb.api.requestResponse.schema.mutation.entity;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.Currency;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mutation is responsible for adding one or more currencies to a {@link EntitySchemaContract#getCurrencies()}
 * in {@link EntitySchemaContract}.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * or negative mutation {@link DisallowCurrencyInEntitySchemaMutation} if those mutation are present in the mutation pipeline
 * multiple times.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class AllowCurrencyInEntitySchemaMutation implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = 5300752272825069167L;
	@Getter private final Currency[] currencies;

	public AllowCurrencyInEntitySchemaMutation(@Nonnull Currency... currencies) {
		this.currencies = currencies;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof AllowCurrencyInEntitySchemaMutation allowCurrencyInEntitySchema) {
			return new MutationCombinationResult<>(
				null,
				new AllowCurrencyInEntitySchemaMutation(
					Stream.concat(
							Arrays.stream(allowCurrencyInEntitySchema.getCurrencies()),
							Arrays.stream(this.currencies)
						)
						.distinct()
						.toArray(Currency[]::new)
				)
			);
		} else if (existingMutation instanceof DisallowCurrencyInEntitySchemaMutation disallowCurrencyInEntitySchema) {
			final Set<Currency> currenciesToRemove = disallowCurrencyInEntitySchema.getCurrencies()
				.stream()
				.filter(removed -> Arrays.stream(this.currencies).noneMatch(added -> added.equals(removed)))
				.collect(Collectors.toSet());
			final Currency[] currenciesToAdd = Arrays.stream(this.currencies)
				.filter(added -> !currentEntitySchema.getCurrencies().contains(added))
				.toArray(Currency[]::new);

			return new MutationCombinationResult<>(
				currenciesToRemove.isEmpty() ? null : (currenciesToRemove.size() == ((DisallowCurrencyInEntitySchemaMutation) existingMutation).getCurrencies().size() ? existingMutation : new DisallowCurrencyInEntitySchemaMutation(currenciesToRemove)),
				currenciesToAdd.length == this.currencies.length ? this : (currenciesToAdd.length == 0 ? null : new AllowCurrencyInEntitySchemaMutation(currenciesToAdd))
			);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		if (Arrays.stream(this.currencies).allMatch(entitySchema::supportsCurrency)) {
			// no need to change the schema
			return entitySchema;
		} else {
			return EntitySchema._internalBuild(
				entitySchema.version() + 1,
				entitySchema.getName(),
				entitySchema.getNameVariants(),
				entitySchema.getDescription(),
				entitySchema.getDeprecationNotice(),
				entitySchema.isWithGeneratedPrimaryKey(),
				entitySchema.isWithHierarchy(),
				entitySchema.getHierarchyIndexedInScopes(),
				entitySchema.isWithPrice(),
				entitySchema.getPriceIndexedInScopes(),
				entitySchema.getIndexedPricePlaces(),
				entitySchema.getLocales(),
				Stream.concat(
						entitySchema.getCurrencies().stream(),
						Arrays.stream(this.currencies)
					)
					.collect(Collectors.toSet()),
				entitySchema.getAttributes(),
				entitySchema.getAssociatedData(),
				entitySchema.getReferences(),
				entitySchema.getEvolutionMode(),
				entitySchema.getSortableAttributeCompounds()
			);
		}
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Allow: currencies=" + Arrays.toString(this.currencies);
	}
}
