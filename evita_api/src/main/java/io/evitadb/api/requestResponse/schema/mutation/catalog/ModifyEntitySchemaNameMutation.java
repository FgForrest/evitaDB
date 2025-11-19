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

package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.NamedSchemaMutation;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;

/**
 * Mutation is responsible for renaming an existing {@link EntitySchemaContract}. The mutation is used internally
 * in {@link CatalogContract#replace(CatalogSchemaContract, CatalogContract)} method.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
@AllArgsConstructor
public class ModifyEntitySchemaNameMutation
	implements LocalCatalogSchemaMutation, LocalEntitySchemaMutation, CatalogSchemaMutation, NamedSchemaMutation {
	@Serial private static final long serialVersionUID = -5176104766478870248L;
	@Getter @Nonnull private final String name;
	@Getter @Nonnull private final String newName;
	@Getter private final boolean overwriteTarget;

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor) {
		if (entitySchemaAccessor instanceof MutationEntitySchemaAccessor mutationEntitySchemaAccessor) {
			mutationEntitySchemaAccessor
				.getEntitySchema(this.name)
				.map(it -> mutate(catalogSchema, it))
				.ifPresentOrElse(
					it -> mutationEntitySchemaAccessor.replaceEntitySchema(this.name, it),
					() -> {
						throw new GenericEvitaInternalError("Entity schema not found: " + this.name);
					}
				);
		}
		// do nothing - we alter only the entity schema
		return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.notNull(
			entitySchema,
			() -> new InvalidSchemaMutationException("Entity schema `" + this.name + "` doesn't exist!")
		);
		if (this.newName.equals(catalogSchema.getName())) {
			// nothing has changed - we can return existing schema
			return entitySchema;
		} else {
			return EntitySchema._internalBuild(
				entitySchema.version() + 1,
				this.newName,
				NamingConvention.generate(this.newName),
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

	@Nonnull
	@Override
	public String containerName() {
		return this.name;
	}

	@Override
	public String toString() {
		return "Modify entity `" + this.name + "` schema: " +
			"newName='" + this.newName + "', overwriteTarget=" + this.overwriteTarget;
	}
}
