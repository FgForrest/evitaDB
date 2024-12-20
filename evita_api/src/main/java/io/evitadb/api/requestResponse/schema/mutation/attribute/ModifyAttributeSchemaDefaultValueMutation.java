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
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
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
import java.util.Objects;

/**
 * Mutation is responsible for setting value to a {@link AttributeSchemaContract#getDefaultValue()}
 * in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link AttributeSchemaContract} or
 * {@link GlobalAttributeSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class ModifyAttributeSchemaDefaultValueMutation
	implements EntityAttributeSchemaMutation, GlobalAttributeSchemaMutation, ReferenceAttributeSchemaMutation,
	CombinableLocalEntitySchemaMutation, CombinableCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -7126530716174758452L;
	@Nonnull @Getter private final String name;
	@Getter @Nullable private final Serializable defaultValue;

	public ModifyAttributeSchemaDefaultValueMutation(@Nonnull String name, @Nullable Serializable defaultValue) {
		this.name = name;
		this.defaultValue = defaultValue;
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
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
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
		final Serializable theDefaultValue = EvitaDataTypes.toTargetType(this.defaultValue, attributeSchema.getType());
		if (Objects.equals(attributeSchema.getDefaultValue(), theDefaultValue)) {
			return attributeSchema;
		} else if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
			//noinspection unchecked,rawtypes
			return (S) GlobalAttributeSchema._internalBuild(
				this.name,
				globalAttributeSchema.getNameVariants(),
				globalAttributeSchema.getDescription(),
				globalAttributeSchema.getDeprecationNotice(),
				globalAttributeSchema.getUniquenessTypeInScopes(),
				globalAttributeSchema.getGlobalUniquenessTypeInScopes(),
				globalAttributeSchema.getFilterableInScopes(),
				globalAttributeSchema.getSortableInScopes(),
				globalAttributeSchema.isLocalized(),
				globalAttributeSchema.isNullable(),
				globalAttributeSchema.isRepresentative(),
				(Class) globalAttributeSchema.getType(),
				theDefaultValue,
				globalAttributeSchema.getIndexedDecimalPlaces()
			);
		} else if (attributeSchema instanceof EntityAttributeSchemaContract entityAttributeSchema) {
			//noinspection unchecked,rawtypes
			return (S) EntityAttributeSchema._internalBuild(
				this.name,
				entityAttributeSchema.getNameVariants(),
				entityAttributeSchema.getDescription(),
				entityAttributeSchema.getDeprecationNotice(),
				entityAttributeSchema.getUniquenessTypeInScopes(),
				entityAttributeSchema.getFilterableInScopes(),
				entityAttributeSchema.getSortableInScopes(),
				entityAttributeSchema.isLocalized(),
				entityAttributeSchema.isNullable(),
				entityAttributeSchema.isRepresentative(),
				(Class) entityAttributeSchema.getType(),
				theDefaultValue,
				entityAttributeSchema.getIndexedDecimalPlaces()
			);
		} else  {
			//noinspection unchecked,rawtypes
			return (S) AttributeSchema._internalBuild(
				this.name,
				attributeSchema.getNameVariants(),
				attributeSchema.getDescription(),
				attributeSchema.getDeprecationNotice(),
				attributeSchema.getUniquenessTypeInScopes(),
				attributeSchema.getFilterableInScopes(),
				attributeSchema.getSortableInScopes(),
				attributeSchema.isLocalized(),
				attributeSchema.isNullable(),
				(Class) attributeSchema.getType(),
				theDefaultValue,
				attributeSchema.getIndexedDecimalPlaces()
			);
		}
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor) {
		Assert.isPremiseValid(catalogSchema != null, "Catalog schema is mandatory!");
		final GlobalAttributeSchemaContract existingAttributeSchema = catalogSchema.getAttribute(this.name)
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The attribute `" + this.name + "` is not defined in catalog `" + catalogSchema.getName() + "` schema!"
			));

		try {
			final GlobalAttributeSchemaContract updatedAttributeSchema = mutate(catalogSchema, existingAttributeSchema, GlobalAttributeSchemaContract.class);
			return replaceAttributeIfDifferent(
				catalogSchema, existingAttributeSchema, updatedAttributeSchema, entitySchemaAccessor, this
			);
		} catch (UnsupportedDataTypeException ex) {
			throw new InvalidSchemaMutationException(
				"The value `" + this.defaultValue + "` cannot be automatically converted to " +
					"attribute `" + this.name + "` type `" + existingAttributeSchema.getType() +
					"` in catalog `" + catalogSchema.getName() + "`!"
			);
		}
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final EntityAttributeSchema existingAttributeSchema = entitySchema.getAttribute(this.name)
			.map(EntityAttributeSchema.class::cast)
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The attribute `" + this.name + "` is not defined in entity `" + entitySchema.getName() + "` schema!"
			));
		try {
			final Serializable theDefaultValue = EvitaDataTypes.toTargetType(this.defaultValue, existingAttributeSchema.getType());
			@SuppressWarnings({"unchecked", "rawtypes"})
			final EntityAttributeSchema updatedAttributeSchema = EntityAttributeSchema._internalBuild(
				this.name,
				existingAttributeSchema.getNameVariants(),
				existingAttributeSchema.getDescription(),
				existingAttributeSchema.getDeprecationNotice(),
				existingAttributeSchema.getUniquenessTypeInScopes(),
				existingAttributeSchema.getFilterableInScopes(),
				existingAttributeSchema.getSortableInScopes(),
				existingAttributeSchema.isLocalized(),
				existingAttributeSchema.isNullable(),
				existingAttributeSchema.isRepresentative(),
				(Class) existingAttributeSchema.getType(),
				theDefaultValue,
				existingAttributeSchema.getIndexedDecimalPlaces()
			);
			return replaceAttributeIfDifferent(
				entitySchema, existingAttributeSchema, updatedAttributeSchema
			);
		} catch (UnsupportedDataTypeException ex) {
			throw new InvalidSchemaMutationException(
				"The value `" + this.defaultValue + "` cannot be automatically converted to " +
					"attribute `" + this.name + "` type `" + existingAttributeSchema.getType() +
					"` in entity `" + entitySchema.getName() + "` schema!"
			);
		}
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema, @Nonnull ConsistencyChecks consistencyChecks) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		final AttributeSchema existingAttributeSchema = (AttributeSchema) getReferenceAttributeSchemaOrThrow(
			entitySchema, referenceSchema, this.name
		);
		try {
			final Serializable theDefaultValue = EvitaDataTypes.toTargetType(this.defaultValue, existingAttributeSchema.getType());
			@SuppressWarnings({"unchecked", "rawtypes"})
			final AttributeSchema updatedAttributeSchema = AttributeSchema._internalBuild(
				this.name,
				existingAttributeSchema.getNameVariants(),
				existingAttributeSchema.getDescription(),
				existingAttributeSchema.getDeprecationNotice(),
				existingAttributeSchema.getUniquenessTypeInScopes(),
				existingAttributeSchema.getFilterableInScopes(),
				existingAttributeSchema.getSortableInScopes(),
				existingAttributeSchema.isLocalized(),
				existingAttributeSchema.isNullable(),
				(Class) existingAttributeSchema.getType(),
				theDefaultValue,
				existingAttributeSchema.getIndexedDecimalPlaces()
			);
			return replaceAttributeIfDifferent(
				referenceSchema, existingAttributeSchema, updatedAttributeSchema
			);
		} catch (UnsupportedDataTypeException ex) {
			throw new InvalidSchemaMutationException(
				"The value `" + this.defaultValue + "` cannot be automatically converted to " +
					"attribute `" + this.name + "` type `" + existingAttributeSchema.getType() +
					"` in entity `" + entitySchema.getName() + "` schema!"
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
		return "Modify attribute `" + name + "` schema: " +
			", defaultValue=" + defaultValue;
	}
}
