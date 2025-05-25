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
import io.evitadb.api.requestResponse.schema.EvolutionMode;
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
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mutation is responsible for adding one or more evolution modes to a {@link EntitySchemaContract#getEvolutionMode()}
 * in {@link EntitySchemaContract}.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * or negative mutation {@link AllowEvolutionModeInEntitySchemaMutation} if those mutation are present in the mutation pipeline
 * multiple times.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class AllowEvolutionModeInEntitySchemaMutation implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = 4888804452076108103L;
	@Getter private final EvolutionMode[] evolutionModes;

	public AllowEvolutionModeInEntitySchemaMutation(@Nonnull EvolutionMode... evolutionModes) {
		this.evolutionModes = evolutionModes;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof AllowEvolutionModeInEntitySchemaMutation allowEvolutionModeInEntitySchema) {
			return new MutationCombinationResult<>(
				null,
				new AllowEvolutionModeInEntitySchemaMutation(
					Stream.concat(
							Arrays.stream(allowEvolutionModeInEntitySchema.getEvolutionModes()),
							Arrays.stream(this.evolutionModes)
						)
						.distinct()
						.toArray(EvolutionMode[]::new)
				)
			);
		} else if (existingMutation instanceof DisallowEvolutionModeInEntitySchemaMutation disallowEvolutionModeInEntitySchema) {
			final EvolutionMode[] modesToRemove = disallowEvolutionModeInEntitySchema.getEvolutionModes()
				.stream()
				.filter(removed -> Arrays.stream(this.evolutionModes).noneMatch(added -> added.equals(removed)))
				.toArray(EvolutionMode[]::new);
			final EvolutionMode[] modesToAdd = Arrays.stream(this.evolutionModes)
				.filter(added -> !currentEntitySchema.getEvolutionMode().contains(added))
				.toArray(EvolutionMode[]::new);

			return new MutationCombinationResult<>(
				modesToRemove.length == 0 ? null : (modesToRemove.length == ((DisallowEvolutionModeInEntitySchemaMutation) existingMutation).getEvolutionModes().size() ? existingMutation : new DisallowEvolutionModeInEntitySchemaMutation(modesToRemove)),
				modesToAdd.length == this.evolutionModes.length ? this : (modesToAdd.length == 0 ? null : new AllowEvolutionModeInEntitySchemaMutation(modesToAdd))
			);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		if (entitySchema.getEvolutionMode().containsAll(List.of(this.evolutionModes))) {
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
				entitySchema.getCurrencies(),
				entitySchema.getAttributes(),
				entitySchema.getAssociatedData(),
				entitySchema.getReferences(),
				Stream.concat(
						entitySchema.getEvolutionMode().stream(),
						Arrays.stream(this.evolutionModes)
					)
					.collect(Collectors.toSet()),
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
		return "Allow: evolutionModes=" + Arrays.toString(this.evolutionModes);
	}
}
