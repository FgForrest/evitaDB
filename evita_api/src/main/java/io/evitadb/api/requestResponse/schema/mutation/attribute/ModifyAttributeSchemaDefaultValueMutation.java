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
import io.evitadb.dataType.exception.InconvertibleDataTypeException;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
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
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class ModifyAttributeSchemaDefaultValueMutation
	extends AbstractAttributeSchemaMutation
	implements EntityAttributeSchemaMutation, GlobalAttributeSchemaMutation, ReferenceAttributeSchemaMutation,
	CombinableLocalEntitySchemaMutation, CombinableCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -7126530716174758452L;
	@Getter @Nullable private final Serializable defaultValue;

	public ModifyAttributeSchemaDefaultValueMutation(@Nonnull String name, @Nullable Serializable defaultValue) {
		super(name);
		this.defaultValue = defaultValue;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation) {
		if (existingMutation instanceof ModifyAttributeSchemaDefaultValueMutation theExistingMutation && this.name.equals(theExistingMutation.getName())) {
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
		if (existingMutation instanceof ModifyAttributeSchemaDefaultValueMutation theExistingMutation && this.name.equals(theExistingMutation.getName())) {
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
		// compared as stored values: an equal array is still another instance, and two ranges are equal as soon as
		// their bounds encode to the same numbers although a reader can tell them apart
		if (!EvitaDataTypes.storedValueDiffers(attributeSchema.getDefaultValue(), theDefaultValue)) {
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
				globalAttributeSchema.getAcceleratorsInScopes(),
				globalAttributeSchema.getSortableInScopes(),
				globalAttributeSchema.isLocalized(),
				globalAttributeSchema.isNullable(),
				globalAttributeSchema.isRepresentative(),
				(Class) globalAttributeSchema.getType(),
				theDefaultValue,
				globalAttributeSchema.getIndexedDecimalPlaces(),
				globalAttributeSchema.getConflictResolutionOverride()
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
				entityAttributeSchema.getAcceleratorsInScopes(),
				entityAttributeSchema.getSortableInScopes(),
				entityAttributeSchema.isLocalized(),
				entityAttributeSchema.isNullable(),
				entityAttributeSchema.isRepresentative(),
				(Class) entityAttributeSchema.getType(),
				theDefaultValue,
				entityAttributeSchema.getIndexedDecimalPlaces(),
				entityAttributeSchema.getConflictResolutionOverride()
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
				attributeSchema.getAcceleratorsInScopes(),
				attributeSchema.getSortableInScopes(),
				attributeSchema.isLocalized(),
				attributeSchema.isNullable(),
				attributeSchema.isRepresentative(),
				(Class) attributeSchema.getType(),
				theDefaultValue,
				attributeSchema.getIndexedDecimalPlaces(),
				attributeSchema.getConflictResolutionOverride()
			);
		}
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor) {
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
		} catch (UnsupportedDataTypeException | InconvertibleDataTypeException ex) {
			throw new InvalidSchemaMutationException(
				"The value `" + StringUtils.toString(this.defaultValue) + "` cannot be automatically converted to " +
					"attribute `" + this.name + "` type `" + existingAttributeSchema.getType() +
					"` in catalog `" + catalogSchema.getName() + "`!",
				ex
			);
		}
	}

	/**
	 * Applies the new default value to the attribute of the entity schema - an attribute of its own, or a global
	 * attribute it uses, which stays global: the attribute-level mutation builds the updated attribute in the class
	 * of the existing one. A change of the default value of a global attribute cascades here from the catalog schema
	 * into every entity schema using it.
	 *
	 * @throws InvalidSchemaMutationException when the attribute does not exist or the value cannot be converted to
	 *                                        its type
	 */
	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final EntityAttributeSchemaContract existingAttributeSchema = entitySchema.getAttribute(this.name)
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The attribute `" + this.name + "` is not defined in entity `" + entitySchema.getName() + "` schema!"
			));
		try {
			final EntityAttributeSchemaContract updatedAttributeSchema = mutate(
				catalogSchema, existingAttributeSchema, EntityAttributeSchemaContract.class
			);
			return replaceAttributeIfDifferent(
				entitySchema, existingAttributeSchema, updatedAttributeSchema
			);
		} catch (UnsupportedDataTypeException | InconvertibleDataTypeException ex) {
			throw new InvalidSchemaMutationException(
				"The value `" + StringUtils.toString(this.defaultValue) + "` cannot be automatically converted to " +
					"attribute `" + this.name + "` type `" + existingAttributeSchema.getType() +
					"` in entity `" + entitySchema.getName() + "` schema!",
				ex
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
			// compared as stored values, as at the attribute level
			if (!EvitaDataTypes.storedValueDiffers(existingAttributeSchema.getDefaultValue(), theDefaultValue)) {
				return referenceSchema;
			}
			@SuppressWarnings({"unchecked", "rawtypes"})
			final AttributeSchema updatedAttributeSchema = AttributeSchema._internalBuild(
				this.name,
				existingAttributeSchema.getNameVariants(),
				existingAttributeSchema.getDescription(),
				existingAttributeSchema.getDeprecationNotice(),
				existingAttributeSchema.getUniquenessTypeInScopes(),
				existingAttributeSchema.getFilterableInScopes(),
				existingAttributeSchema.getAcceleratorsInScopes(),
				existingAttributeSchema.getSortableInScopes(),
				existingAttributeSchema.isLocalized(),
				existingAttributeSchema.isNullable(),
				existingAttributeSchema.isRepresentative(),
				(Class) existingAttributeSchema.getType(),
				theDefaultValue,
				existingAttributeSchema.getIndexedDecimalPlaces(),
				existingAttributeSchema.getConflictResolutionOverride()
			);
			return replaceAttributeIfDifferent(
				referenceSchema, existingAttributeSchema, updatedAttributeSchema
			);
		} catch (UnsupportedDataTypeException | InconvertibleDataTypeException ex) {
			throw new InvalidSchemaMutationException(
				"The value `" + StringUtils.toString(this.defaultValue) + "` cannot be automatically converted to " +
					"attribute `" + this.name + "` type `" + existingAttributeSchema.getType() +
					"` in entity `" + entitySchema.getName() + "` schema!",
				ex
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
		return "Modify attribute `" + this.name + "` schema: " +
			"defaultValue=" + this.defaultValue;
	}
}
