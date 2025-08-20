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
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mutation is responsible for removing one or more currencies to a {@link EntitySchemaContract#getCurrencies()}
 * in {@link EntitySchemaContract}.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * or negative mutation {@link AllowCurrencyInEntitySchemaMutation} if those mutation are present in the mutation pipeline
 * multiple times.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class DisallowCurrencyInEntitySchemaMutation implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = 8499615143393252355L;
	@Getter private final Set<Currency> currencies;

	@SerializableCreator
	public DisallowCurrencyInEntitySchemaMutation(@Nonnull Currency... currencies) {
		this.currencies = new LinkedHashSet<>(Arrays.asList(currencies));
	}

	public DisallowCurrencyInEntitySchemaMutation(@Nonnull Set<Currency> currencies) {
		this.currencies = new LinkedHashSet<>(currencies);
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof DisallowCurrencyInEntitySchemaMutation disallowCurrencyInEntitySchema) {
			return new MutationCombinationResult<>(
				null,
				new DisallowCurrencyInEntitySchemaMutation(
					Stream.concat(
							disallowCurrencyInEntitySchema.getCurrencies().stream(),
							this.currencies.stream()
						)
						.distinct()
						.toArray(Currency[]::new)
				)
			);
		} else if (existingMutation instanceof AllowCurrencyInEntitySchemaMutation allowCurrencyInEntitySchema) {
			final Currency[] currenciesToAdd = Arrays.stream(allowCurrencyInEntitySchema.getCurrencies())
				.filter(added -> !this.currencies.contains(added))
				.toArray(Currency[]::new);
			final Set<Currency> currenciesToRemove = this.currencies.stream()
				.filter(it -> currentEntitySchema.getCurrencies().contains(it))
				.collect(Collectors.toSet());

			return new MutationCombinationResult<>(
				currenciesToAdd.length == 0 ? null : (currenciesToAdd.length == ((AllowCurrencyInEntitySchemaMutation) existingMutation).getCurrencies().length ? existingMutation : new AllowCurrencyInEntitySchemaMutation(currenciesToAdd)),
				currenciesToRemove.size() == this.currencies.size() ? this : (currenciesToRemove.isEmpty() ? null : new DisallowCurrencyInEntitySchemaMutation(currenciesToRemove))
			);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		if (entitySchema.getCurrencies().stream().noneMatch(this.currencies::contains)) {
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
				entitySchema.getCurrencies()
					.stream()
					.filter(it -> !this.currencies.contains(it))
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
		return "Disallow: currencies=" + this.currencies;
	}
}
