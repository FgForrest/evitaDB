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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;

/**
 * Mutation is responsible for setting value to a {@link AttributeSchemaContract#getDefaultValue()}
 * in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link AttributeSchemaContract} or
 * {@link GlobalAttributeSchemaContract} alone.
 * Mutation implements {@link CombinableEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class ModifyAttributeSchemaDefaultValueMutation
	implements EntityAttributeSchemaMutation, GlobalAttributeSchemaMutation, ReferenceAttributeSchemaMutation,
	CombinableEntitySchemaMutation, CombinableCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -7126530716174758452L;
	@Nonnull @Getter private final String name;
	@Getter @Nullable private final Serializable defaultValue;

	public ModifyAttributeSchemaDefaultValueMutation(@Nonnull String name, @Nullable Serializable defaultValue) {
		this.name = name;
		this.defaultValue = defaultValue;
	}

	@Nonnull
	@Override
	public Operation getOperation() {
		return Operation.UPDATE;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation) {
		if (existingMutation instanceof ModifyAttributeSchemaDefaultValueMutation theExistingMutation && name.equals(theExistingMutation.getName())) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	@Nullable
	@Override
	public MutationCombinationResult<EntitySchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull EntitySchemaContract currentEntitySchema, @Nonnull EntitySchemaMutation existingMutation) {
		if (existingMutation instanceof ModifyAttributeSchemaDefaultValueMutation theExistingMutation && name.equals(theExistingMutation.getName())) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public <S extends AttributeSchemaContract> S mutate(@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema, @Nonnull Class<S> schemaType) {
		Assert.isPremiseValid(attributeSchema != null, "Attribute schema is mandatory!");
		if (attributeSchema instanceof GlobalAttributeSchema globalAttributeSchema) {
			//noinspection unchecked,rawtypes
			return (S) GlobalAttributeSchema._internalBuild(
				name,
				globalAttributeSchema.getNameVariants(),
				globalAttributeSchema.getDescription(),
				globalAttributeSchema.getDeprecationNotice(),
				globalAttributeSchema.getUniquenessType(),
				globalAttributeSchema.getGlobalUniquenessType(),
				globalAttributeSchema.isFilterable(),
				globalAttributeSchema.isSortable(),
				globalAttributeSchema.isLocalized(),
				globalAttributeSchema.isNullable(),
				globalAttributeSchema.isRepresentative(),
				(Class) globalAttributeSchema.getType(),
				EvitaDataTypes.toTargetType(defaultValue, globalAttributeSchema.getType()),
				globalAttributeSchema.getIndexedDecimalPlaces()
			);
		} else if (attributeSchema instanceof EntityAttributeSchema entityAttributeSchema) {
			//noinspection unchecked,rawtypes
			return (S) EntityAttributeSchema._internalBuild(
				name,
				entityAttributeSchema.getNameVariants(),
				entityAttributeSchema.getDescription(),
				entityAttributeSchema.getDeprecationNotice(),
				entityAttributeSchema.getUniquenessType(),
				entityAttributeSchema.isFilterable(),
				entityAttributeSchema.isSortable(),
				entityAttributeSchema.isLocalized(),
				entityAttributeSchema.isNullable(),
				entityAttributeSchema.isRepresentative(),
				(Class) entityAttributeSchema.getType(),
				EvitaDataTypes.toTargetType(defaultValue, entityAttributeSchema.getType()),
				entityAttributeSchema.getIndexedDecimalPlaces()
			);
		} else {
			//noinspection unchecked,rawtypes
			return (S) AttributeSchema._internalBuild(
				name,
				attributeSchema.getNameVariants(),
				attributeSchema.getDescription(),
				attributeSchema.getDeprecationNotice(),
				attributeSchema.getUniquenessType(),
				attributeSchema.isFilterable(),
				attributeSchema.isSortable(),
				attributeSchema.isLocalized(),
				attributeSchema.isNullable(),
				(Class) attributeSchema.getType(),
				EvitaDataTypes.toTargetType(defaultValue, attributeSchema.getType()),
				attributeSchema.getIndexedDecimalPlaces()
			);
		}
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor) {
		Assert.isPremiseValid(catalogSchema != null, "Catalog schema is mandatory!");
		final GlobalAttributeSchemaContract existingAttributeSchema = catalogSchema.getAttribute(name)
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The attribute `" + name + "` is not defined in catalog `" + catalogSchema.getName() + "` schema!"
			));

		try {
			final GlobalAttributeSchemaContract updatedAttributeSchema = mutate(catalogSchema, existingAttributeSchema, GlobalAttributeSchemaContract.class);
			return replaceAttributeIfDifferent(
				catalogSchema, existingAttributeSchema, updatedAttributeSchema, entitySchemaAccessor, this
			);
		} catch (UnsupportedDataTypeException ex) {
			throw new InvalidSchemaMutationException(
				"The value `" + defaultValue + "` cannot be automatically converted to " +
					"attribute `" + name + "` type `" + existingAttributeSchema.getType() +
					"` in catalog `" + catalogSchema.getName() + "`!"
			);
		}
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final EntityAttributeSchemaContract existingAttributeSchema = entitySchema.getAttribute(name)
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The attribute `" + name + "` is not defined in entity `" + entitySchema.getName() + "` schema!"
			));
		try {
			@SuppressWarnings({"unchecked", "rawtypes"})
			final EntityAttributeSchema updatedAttributeSchema = EntityAttributeSchema._internalBuild(
				name,
				existingAttributeSchema.getNameVariants(),
				existingAttributeSchema.getDescription(),
				existingAttributeSchema.getDeprecationNotice(),
				existingAttributeSchema.getUniquenessType(),
				existingAttributeSchema.isFilterable(),
				existingAttributeSchema.isSortable(),
				existingAttributeSchema.isLocalized(),
				existingAttributeSchema.isNullable(),
				existingAttributeSchema.isRepresentative(),
				(Class) existingAttributeSchema.getType(),
				EvitaDataTypes.toTargetType(defaultValue, existingAttributeSchema.getType()),
				existingAttributeSchema.getIndexedDecimalPlaces()
			);
			return replaceAttributeIfDifferent(
				entitySchema, existingAttributeSchema, updatedAttributeSchema
			);
		} catch (UnsupportedDataTypeException ex) {
			throw new InvalidSchemaMutationException(
				"The value `" + defaultValue + "` cannot be automatically converted to " +
					"attribute `" + name + "` type `" + existingAttributeSchema.getType() +
					"` in entity `" + entitySchema.getName() + "` schema!"
			);
		}
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		final AttributeSchemaContract existingAttributeSchema = referenceSchema.getAttribute(name)
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The attribute `" + name + "` is not defined in entity `" + entitySchema.getName() +
					"` schema for reference with name `" + referenceSchema.getName() + "`!"
			));

		try {
			@SuppressWarnings({"unchecked", "rawtypes"})
			final AttributeSchema updatedAttributeSchema = AttributeSchema._internalBuild(
				name,
				existingAttributeSchema.getNameVariants(),
				existingAttributeSchema.getDescription(),
				existingAttributeSchema.getDeprecationNotice(),
				existingAttributeSchema.getUniquenessType(),
				existingAttributeSchema.isFilterable(),
				existingAttributeSchema.isSortable(),
				existingAttributeSchema.isLocalized(),
				existingAttributeSchema.isNullable(),
				(Class) existingAttributeSchema.getType(),
				EvitaDataTypes.toTargetType(defaultValue, existingAttributeSchema.getType()),
				existingAttributeSchema.getIndexedDecimalPlaces()
			);
			return replaceAttributeIfDifferent(
				referenceSchema, existingAttributeSchema, updatedAttributeSchema
			);
		} catch (UnsupportedDataTypeException ex) {
			throw new InvalidSchemaMutationException(
				"The value `" + defaultValue + "` cannot be automatically converted to " +
					"attribute `" + name + "` type `" + existingAttributeSchema.getType() +
					"` in entity `" + entitySchema.getName() + "` schema!"
			);
		}
	}

	@Override
	public String toString() {
		return "Modify attribute `" + name + "` schema: " +
			", defaultValue=" + defaultValue;
	}
}
