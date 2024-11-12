/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.utils.Assert;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Objects;

/**
 * Mutation is responsible for setting a {@link EntitySchemaContract#getDeprecationNotice()}
 * in {@link EntitySchemaContract}.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if it's present in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
@AllArgsConstructor
public class ModifyEntitySchemaDeprecationNoticeMutation implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -3441501850277257592L;
	@Getter @Nullable private final String deprecationNotice;

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof ModifyEntitySchemaDeprecationNoticeMutation) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		if (Objects.equals(entitySchema.getDeprecationNotice(), deprecationNotice)) {
			// entity schema is already removed - no need to do anything
			return entitySchema;
		} else if (entitySchema instanceof EntitySchema theEntitySchema) {
			return EntitySchema._internalBuild(
				theEntitySchema.version() + 1,
				theEntitySchema.getName(),
				theEntitySchema.getNameVariants(),
				theEntitySchema.getDescription(),
				deprecationNotice,
				theEntitySchema.isWithGeneratedPrimaryKey(),
				theEntitySchema.isWithHierarchy(),
				theEntitySchema.getHierarchyIndexedInScopes(),
				theEntitySchema.isWithPrice(),
				theEntitySchema.getPriceIndexedInScopes(),
				theEntitySchema.getIndexedPricePlaces(),
				theEntitySchema.getLocales(),
				theEntitySchema.getCurrencies(),
				theEntitySchema.getAttributes(),
				theEntitySchema.getAssociatedData(),
				theEntitySchema.getReferences(),
				theEntitySchema.getEvolutionMode(),
				theEntitySchema.getSortableAttributeCompounds()
			);
		} else {
			throw new InvalidSchemaMutationException(
				"Unsupported entity schema type: " + entitySchema.getClass().getName()
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
		return "Modify entity schema: " +
			"deprecationNotice='" + deprecationNotice + '\'';
	}
}
