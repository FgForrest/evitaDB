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

package io.evitadb.api.requestResponse.schema.mutation.entity;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mutation is responsible for removing one or more evolution modes to a {@link EntitySchemaContract#getEvolutionMode()}
 * in {@link EntitySchemaContract}.
 * Mutation implements {@link CombinableEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * or negative mutation {@link DisallowEvolutionModeInEntitySchemaMutation} if those mutation are present in the mutation pipeline
 * multiple times.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class DisallowEvolutionModeInEntitySchemaMutation implements CombinableEntitySchemaMutation {
	@Serial private static final long serialVersionUID = 1246857825698363659L;
	@Getter private final Set<EvolutionMode> evolutionModes;

	public DisallowEvolutionModeInEntitySchemaMutation(@Nonnull Set<EvolutionMode> evolutionModes) {
		this.evolutionModes = EnumSet.noneOf(EvolutionMode.class);
		this.evolutionModes.addAll(evolutionModes);
	}

	public DisallowEvolutionModeInEntitySchemaMutation(@Nonnull EvolutionMode... evolutionModes) {
		this.evolutionModes = EnumSet.noneOf(EvolutionMode.class);
		this.evolutionModes.addAll(Arrays.asList(evolutionModes));
	}

	@Nullable
	@Override
	public MutationCombinationResult<EntitySchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull EntitySchemaContract currentEntitySchema, @Nonnull EntitySchemaMutation existingMutation) {
		if (existingMutation instanceof DisallowEvolutionModeInEntitySchemaMutation disallowEvolutionModeInEntitySchema) {
			return new MutationCombinationResult<>(
				null,
				new DisallowEvolutionModeInEntitySchemaMutation(
					Stream.concat(
							disallowEvolutionModeInEntitySchema.getEvolutionModes().stream(),
							evolutionModes.stream()
						)
						.collect(Collectors.toSet())
				)
			);
		} else if (existingMutation instanceof AllowEvolutionModeInEntitySchemaMutation allowEvolutionModeInEntitySchema) {
			final EvolutionMode[] modesToAdd = Arrays.stream(allowEvolutionModeInEntitySchema.getEvolutionModes())
				.filter(added -> !evolutionModes.contains(added))
				.toArray(EvolutionMode[]::new);
			final Set<EvolutionMode> modesToRemove = evolutionModes.stream()
				.filter(it -> currentEntitySchema.getEvolutionMode().contains(it))
				.collect(Collectors.toSet());

			return new MutationCombinationResult<>(
				modesToAdd.length == 0 ? null : (modesToAdd.length == ((AllowEvolutionModeInEntitySchemaMutation) existingMutation).getEvolutionModes().length ? existingMutation : new AllowEvolutionModeInEntitySchemaMutation(modesToAdd)),
				modesToRemove.size() == evolutionModes.size() ? this : (modesToRemove.isEmpty() ? null : new DisallowEvolutionModeInEntitySchemaMutation(modesToRemove))
			);
		} else {
			return null;
		}
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		if (entitySchema.getEvolutionMode().stream().noneMatch(evolutionModes::contains)) {
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
				entitySchema.isWithPrice(),
				entitySchema.getIndexedPricePlaces(),
				entitySchema.getLocales(),
				entitySchema.getCurrencies(),
				entitySchema.getAttributes(),
				entitySchema.getAssociatedData(),
				entitySchema.getReferences(),
				entitySchema.getEvolutionMode()
					.stream()
					.filter(it -> !this.evolutionModes.contains(it))
					.collect(Collectors.toSet()),
				entitySchema.getSortableAttributeCompounds()
			);
		}
	}

	@Override
	public String toString() {
		return "Disallow: evolutionModes=" + evolutionModes;
	}
}
