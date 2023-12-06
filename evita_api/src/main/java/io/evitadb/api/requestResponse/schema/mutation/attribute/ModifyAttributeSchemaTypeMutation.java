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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.exception.InvalidSchemaMutationException;
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
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;

import static java.util.Optional.ofNullable;

/**
 * Mutation is responsible for setting value to a {@link AttributeSchemaContract#getType()}
 * in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link AttributeSchemaContract} or
 * {@link GlobalAttributeSchemaContract} alone.
 * Mutation implements {@link CombinableEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * TOBEDONE JNO - write tests
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class ModifyAttributeSchemaTypeMutation
	implements EntityAttributeSchemaMutation, GlobalAttributeSchemaMutation, ReferenceAttributeSchemaMutation,
				CombinableEntitySchemaMutation, CombinableCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -4704241145075202389L;
	@Nonnull @Getter private final String name;
	@Nonnull @Getter private final Class<? extends Serializable> type;
	@Getter private final int indexedDecimalPlaces;

	public ModifyAttributeSchemaTypeMutation(
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> type,
		int indexedDecimalPlaces
	) {
		if (!EvitaDataTypes.isSupportedTypeOrItsArray(type)) {
			throw new InvalidSchemaMutationException("The type `" + type + "` is not allowed in attributes!");
		}
		this.name = name;
		this.type = type;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation) {
		if (existingMutation instanceof AttributeSchemaMutation theExistingMutation && name.equals(theExistingMutation.getName())) {
			if (existingMutation instanceof ModifyAttributeSchemaTypeMutation) {
				return new MutationCombinationResult<>(null, this);
			} else if (existingMutation instanceof SetAttributeSchemaFilterableMutation || existingMutation instanceof SetAttributeSchemaSortableMutation) {
				// swap operations
				return new MutationCombinationResult<>(this, existingMutation);
			} else {
				return null;
			}
		} else {
			return null;
		}
	}

	@Nullable
	@Override
	public MutationCombinationResult<EntitySchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull EntitySchemaContract currentEntitySchema, @Nonnull EntitySchemaMutation existingMutation) {
		if (existingMutation instanceof AttributeSchemaMutation theExistingMutation && name.equals(theExistingMutation.getName())) {
			if (existingMutation instanceof ModifyAttributeSchemaTypeMutation) {
				return new MutationCombinationResult<>(null, this);
			} else if (existingMutation instanceof SetAttributeSchemaFilterableMutation || existingMutation instanceof SetAttributeSchemaSortableMutation) {
				// swap operations
				return new MutationCombinationResult<>(this, existingMutation);
			} else {
				return null;
			}
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public <S extends AttributeSchemaContract> S mutate(@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema, @Nonnull Class<S> schemaType) {
		Assert.isPremiseValid(attributeSchema != null, "Attribute schema is mandatory!");
		@SuppressWarnings("rawtypes")
		final Class newType = EvitaDataTypes.toWrappedForm(type);
		if (attributeSchema instanceof GlobalAttributeSchema globalAttributeSchema) {
			//noinspection unchecked
			return (S) GlobalAttributeSchema._internalBuild(
				name,
				globalAttributeSchema.getNameVariants(),
				globalAttributeSchema.getDescription(),
				globalAttributeSchema.getDeprecationNotice(),
				globalAttributeSchema.isUnique(),
				globalAttributeSchema.isUniqueGlobally(),
				globalAttributeSchema.isFilterable(),
				globalAttributeSchema.isSortable(),
				globalAttributeSchema.isLocalized(),
				globalAttributeSchema.isNullable(),
				globalAttributeSchema.isRepresentative(),
				newType,
				ofNullable(globalAttributeSchema.getDefaultValue())
					.map(it -> EvitaDataTypes.toTargetType(it, newType))
					.orElse(null),
				indexedDecimalPlaces
			);
		} else if (attributeSchema instanceof EntityAttributeSchema entityAttributeSchema) {
			//noinspection unchecked
			return (S) EntityAttributeSchema._internalBuild(
				name,
				entityAttributeSchema.getNameVariants(),
				entityAttributeSchema.getDescription(),
				entityAttributeSchema.getDeprecationNotice(),
				entityAttributeSchema.isUnique(),
				entityAttributeSchema.isFilterable(),
				entityAttributeSchema.isSortable(),
				entityAttributeSchema.isLocalized(),
				entityAttributeSchema.isNullable(),
				entityAttributeSchema.isRepresentative(),
				newType,
				ofNullable(entityAttributeSchema.getDefaultValue())
					.map(it -> EvitaDataTypes.toTargetType(it, newType))
					.orElse(null),
				indexedDecimalPlaces
			);
		} else {
			//noinspection unchecked
			return (S) AttributeSchema._internalBuild(
				name,
				attributeSchema.getNameVariants(),
				attributeSchema.getDescription(),
				attributeSchema.getDeprecationNotice(),
				attributeSchema.isUnique(),
				attributeSchema.isFilterable(),
				attributeSchema.isSortable(),
				attributeSchema.isLocalized(),
				attributeSchema.isNullable(),
				newType,
				ofNullable(attributeSchema.getDefaultValue())
					.map(it -> EvitaDataTypes.toTargetType(it, newType))
					.orElse(null),
				indexedDecimalPlaces
			);
		}
	}

	@Nullable
	@Override
	public CatalogSchemaContract mutate(@Nullable CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor) {
		Assert.isPremiseValid(catalogSchema != null, "Catalog schema is mandatory!");
		final GlobalAttributeSchemaContract existingAttributeSchema = catalogSchema.getAttribute(name)
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The attribute `" + name + "` is not defined in catalog `" + catalogSchema.getName() + "` schema!"
			));


		final GlobalAttributeSchemaContract updatedAttributeSchema = mutate(catalogSchema, existingAttributeSchema, GlobalAttributeSchemaContract.class);
		return replaceAttributeIfDifferent(
			catalogSchema, existingAttributeSchema, updatedAttributeSchema, entitySchemaAccessor
		);
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final EntityAttributeSchemaContract existingAttributeSchema = entitySchema.getAttribute(name)
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The attribute `" + name + "` is not defined in entity `" + entitySchema.getName() + "` schema!"
			));

		final EntityAttributeSchemaContract updatedAttributeSchema = mutate(catalogSchema, existingAttributeSchema, EntityAttributeSchemaContract.class);
		return replaceAttributeIfDifferent(
			entitySchema, existingAttributeSchema, updatedAttributeSchema
		);
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

		final AttributeSchemaContract updatedAttributeSchema = mutate(null, existingAttributeSchema, AttributeSchemaContract.class);
		return replaceAttributeIfDifferent(
			referenceSchema, existingAttributeSchema, updatedAttributeSchema
		);
	}

	@Override
	public String toString() {
		return "Modify attribute `" + name + "` schema: " +
			"type=" + type +
			", indexedDecimalPlaces=" + indexedDecimalPlaces;
	}

}
