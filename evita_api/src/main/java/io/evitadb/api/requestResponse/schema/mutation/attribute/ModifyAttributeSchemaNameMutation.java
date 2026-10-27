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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;

/**
 * Mutation is responsible for renaming an existing {@link AttributeSchemaContract} in {@link EntitySchemaContract}
 * or {@link GlobalAttributeSchemaContract} in {@link CatalogSchemaContract}.
 * Mutation can be used for altering also the existing {@link AttributeSchemaContract} or
 * {@link GlobalAttributeSchemaContract} alone.
 * The mutation never combines with another one: a renamed name stands for two attributes in one pipeline, and a
 * second rename of the same name addresses an attribute that no longer has it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class ModifyAttributeSchemaNameMutation
	extends AbstractAttributeSchemaMutation
	implements EntityAttributeSchemaMutation, GlobalAttributeSchemaMutation, ReferenceAttributeSchemaMutation,
	LocalEntitySchemaMutation, LocalCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = 7259840478218862547L;
	@Getter @Nonnull private final String newName;

	public ModifyAttributeSchemaNameMutation(@Nonnull String name, @Nonnull String newName) {
		super(name);
		this.newName = newName;
	}

	@Nonnull
	@Override
	public <S extends AttributeSchemaContract> S mutate(
		@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema, @Nonnull Class<S> schemaType
	) {
		Assert.isPremiseValid(attributeSchema != null, "Attribute schema is mandatory!");
		if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
			//noinspection unchecked,rawtypes
			return (S) GlobalAttributeSchema._internalBuild(
				this.newName,
				globalAttributeSchema.getDescription(),
				globalAttributeSchema.getDeprecationNotice(),
				globalAttributeSchema.getUniquenessTypeInScopes(),
				globalAttributeSchema.getGlobalUniquenessTypeInScopes(),
				globalAttributeSchema.getFilterableInScopes(),
				globalAttributeSchema.getAcceleratorsInScopes(),
				globalAttributeSchema.getSearchableInScopes(),
				globalAttributeSchema.getSortableInScopes(),
				globalAttributeSchema.isLocalized(),
				globalAttributeSchema.isNullable(),
				globalAttributeSchema.isRepresentative(),
				(Class) globalAttributeSchema.getType(),
				globalAttributeSchema.getDefaultValue(),
				globalAttributeSchema.getIndexedDecimalPlaces(),
				globalAttributeSchema.getConflictResolutionOverride(),
				globalAttributeSchema.getNormalization()
			);
		} else if (attributeSchema instanceof EntityAttributeSchemaContract entityAttributeSchema) {
			//noinspection unchecked,rawtypes
			return (S) EntityAttributeSchema._internalBuild(
				this.newName,
				entityAttributeSchema.getDescription(),
				entityAttributeSchema.getDeprecationNotice(),
				entityAttributeSchema.getUniquenessTypeInScopes(),
				entityAttributeSchema.getFilterableInScopes(),
				entityAttributeSchema.getAcceleratorsInScopes(),
				entityAttributeSchema.getSearchableInScopes(),
				entityAttributeSchema.getSortableInScopes(),
				entityAttributeSchema.isLocalized(),
				entityAttributeSchema.isNullable(),
				entityAttributeSchema.isRepresentative(),
				(Class) entityAttributeSchema.getType(),
				entityAttributeSchema.getDefaultValue(),
				entityAttributeSchema.getIndexedDecimalPlaces(),
				entityAttributeSchema.getConflictResolutionOverride(),
				entityAttributeSchema.getNormalization()
			);
		} else {
			//noinspection unchecked,rawtypes
			return (S) AttributeSchema._internalBuild(
				this.newName,
				attributeSchema.getDescription(),
				attributeSchema.getDeprecationNotice(),
				attributeSchema.getUniquenessTypeInScopes(),
				attributeSchema.getFilterableInScopes(),
				attributeSchema.getAcceleratorsInScopes(),
				attributeSchema.getSearchableInScopes(),
				attributeSchema.getSortableInScopes(),
				attributeSchema.isLocalized(),
				attributeSchema.isNullable(),
				attributeSchema.isRepresentative(),
				(Class) attributeSchema.getType(),
				attributeSchema.getDefaultValue(),
				attributeSchema.getIndexedDecimalPlaces(),
				attributeSchema.getConflictResolutionOverride(),
				attributeSchema.getNormalization()
			);
		}
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(
		@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor
	) {
		return mutateGlobalAttributeSchema(catalogSchema, entitySchemaAccessor, this);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Modify attribute `" + this.name + "` schema: " +
			"newName='" + this.newName + '\'';
	}
}
