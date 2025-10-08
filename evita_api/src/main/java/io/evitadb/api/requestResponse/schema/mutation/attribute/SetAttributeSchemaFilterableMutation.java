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
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
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
import java.util.List;
import java.util.stream.Collectors;

import static io.evitadb.dataType.Scope.NO_SCOPE;

/**
 * Mutation is responsible for setting value to a {@link AttributeSchemaContract#isFilterable()}
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
public class SetAttributeSchemaFilterableMutation
	implements EntityAttributeSchemaMutation, GlobalAttributeSchemaMutation, ReferenceAttributeSchemaMutation,
	CombinableLocalEntitySchemaMutation, CombinableCatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -382658973541254821L;

	@Getter @Nonnull private final String name;
	@Getter private final Scope[] filterableInScopes;

	public SetAttributeSchemaFilterableMutation(@Nonnull String name, boolean filterable) {
		this(
			name,
			filterable ? Scope.DEFAULT_SCOPES : NO_SCOPE
		);
	}

	@SerializableCreator
	public SetAttributeSchemaFilterableMutation(
		@Nonnull String name,
		@Nullable Scope[] filterableInScopes
	) {
		this.name = name;
		this.filterableInScopes = filterableInScopes == null ? NO_SCOPE : filterableInScopes;
	}

	public boolean isFilterable() {
		return !ArrayUtils.isEmptyOrItsValuesNull(this.filterableInScopes);
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation) {
		if (existingMutation instanceof SetAttributeSchemaFilterableMutation theExistingMutation && this.name.equals(theExistingMutation.getName())) {
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
		if (existingMutation instanceof SetAttributeSchemaFilterableMutation theExistingMutation && this.name.equals(theExistingMutation.getName())) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public <S extends AttributeSchemaContract> S mutate(@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema, @Nonnull Class<S> schemaType) {
		Assert.isPremiseValid(attributeSchema != null, "Attribute schema is mandatory!");
		final EnumSet<Scope> filterable = ArrayUtils.toEnumSet(Scope.class, this.filterableInScopes);
		if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
			if (globalAttributeSchema.getFilterableInScopes().equals(filterable)) {
				return attributeSchema;
			} else {
				//noinspection unchecked,rawtypes
				return (S) GlobalAttributeSchema._internalBuild(
					this.name,
					globalAttributeSchema.getNameVariants(),
					globalAttributeSchema.getDescription(),
					globalAttributeSchema.getDeprecationNotice(),
					globalAttributeSchema.getUniquenessTypeInScopes(),
					globalAttributeSchema.getGlobalUniquenessTypeInScopes(),
					filterable,
					globalAttributeSchema.getSortableInScopes(),
					globalAttributeSchema.isLocalized(),
					globalAttributeSchema.isNullable(),
					globalAttributeSchema.isRepresentative(),
					(Class) globalAttributeSchema.getType(),
					globalAttributeSchema.getDefaultValue(),
					globalAttributeSchema.getIndexedDecimalPlaces()
				);
			}
		} else if (attributeSchema instanceof EntityAttributeSchemaContract entityAttributeSchema) {
			if (entityAttributeSchema.getFilterableInScopes().equals(filterable)) {
				return attributeSchema;
			} else {
				//noinspection unchecked,rawtypes
				return (S) EntityAttributeSchema._internalBuild(
					this.name,
					entityAttributeSchema.getNameVariants(),
					entityAttributeSchema.getDescription(),
					entityAttributeSchema.getDeprecationNotice(),
					entityAttributeSchema.getUniquenessTypeInScopes(),
					filterable,
					entityAttributeSchema.getSortableInScopes(),
					entityAttributeSchema.isLocalized(),
					entityAttributeSchema.isNullable(),
					entityAttributeSchema.isRepresentative(),
					(Class) entityAttributeSchema.getType(),
					entityAttributeSchema.getDefaultValue(),
					entityAttributeSchema.getIndexedDecimalPlaces()
				);
			}
		} else  {
			if (attributeSchema.getFilterableInScopes().equals(filterable)) {
				return attributeSchema;
			} else {
				//noinspection unchecked,rawtypes
				return (S) AttributeSchema._internalBuild(
					this.name,
					attributeSchema.getNameVariants(),
					attributeSchema.getDescription(),
					attributeSchema.getDeprecationNotice(),
					attributeSchema.getUniquenessTypeInScopes(),
					filterable,
					attributeSchema.getSortableInScopes(),
					attributeSchema.isLocalized(),
					attributeSchema.isNullable(),
					attributeSchema.isRepresentative(),
					(Class) attributeSchema.getType(),
					attributeSchema.getDefaultValue(),
					attributeSchema.getIndexedDecimalPlaces()
				);
			}
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

		final GlobalAttributeSchemaContract updatedAttributeSchema = mutate(catalogSchema, existingAttributeSchema, GlobalAttributeSchemaContract.class);
		return replaceAttributeIfDifferent(
			catalogSchema, existingAttributeSchema, updatedAttributeSchema, entitySchemaAccessor, this
		);
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final EntityAttributeSchemaContract existingAttributeSchema = entitySchema.getAttribute(this.name)
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The attribute `" + this.name + "` is not defined in entity `" + entitySchema.getName() + "` schema!"
			));

		final EntityAttributeSchemaContract updatedAttributeSchema = mutate(catalogSchema, existingAttributeSchema, EntityAttributeSchemaContract.class);
		return replaceAttributeIfDifferent(
			entitySchema, existingAttributeSchema, updatedAttributeSchema
		);
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema, @Nonnull ConsistencyChecks consistencyChecks) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		if (consistencyChecks != ReferenceSchemaMutator.ConsistencyChecks.SKIP) {
			final List<Scope> nonIndexedScopes = Arrays.stream(this.filterableInScopes)
				.filter(scope -> !referenceSchema.isIndexedInScope(scope))
				.toList();
			Assert.isTrue(
				nonIndexedScopes.isEmpty(),
				() -> new InvalidSchemaMutationException(
					"The reference `" + referenceSchema.getName() + "` is in entity `" + entitySchema.getName() +
						"` is not indexed in required scopes: " + nonIndexedScopes.stream().map(Enum::name).collect(Collectors.joining(", ")) + "! " +
						"Non-indexed references must not contain filterable attribute `" + this.name + "`!"
				)
			);
		}
		final AttributeSchemaContract existingAttributeSchema = getReferenceAttributeSchemaOrThrow(entitySchema, referenceSchema, this.name);
		final AttributeSchemaContract updatedAttributeSchema = mutate(null, existingAttributeSchema, AttributeSchemaContract.class);
		return replaceAttributeIfDifferent(
			referenceSchema, existingAttributeSchema, updatedAttributeSchema
		);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Set attribute `" + this.name + "` schema: " +
			", filterable=" + (isFilterable() ? "(in scopes: " + Arrays.toString(this.filterableInScopes) + ")" : "no");
	}
}
