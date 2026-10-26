/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
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
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.List;

/**
 * Replaces the entire ordered normalization axis of an entity, reference or global attribute.
 * The setting applies to every scope. Combining statements for the same attribute keeps the later statement.
 * Type validity is checked on the assembled schema by {@link AttributeSchemaContract#validate()}.
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class SetAttributeSchemaNormalizedMutation
	extends AbstractAttributeSchemaMutation
	implements EntityAttributeSchemaMutation, GlobalAttributeSchemaMutation, ReferenceAttributeSchemaMutation,
	CombinableLocalEntitySchemaMutation, CombinableCatalogSchemaMutation {
	@Serial private static final long serialVersionUID = 8634212234101117588L;

	/** Ordered normalization descriptions; an empty array removes custom normalization. */
	@Nonnull private final AnalysisStep[] normalization;

	/**
	 * Creates a full normalization statement; `null` and an empty array both mean Unicode NFD only.
	 *
	 * @param name name of the attribute to alter
	 * @param normalization ordered steps to apply
	 */
	@SerializableCreator
	public SetAttributeSchemaNormalizedMutation(
		@Nonnull String name, @Nullable AnalysisStep... normalization
	) {
		super(name);
		this.normalization = normalization == null ? new AnalysisStep[0] : normalization.clone();
	}

	/**
	 * Returns the statement with a defensive copy of its ordered steps.
	 *
	 * @return copy of the ordered steps; empty when only Unicode NFD applies
	 */
	@Nonnull
	public AnalysisStep[] getNormalization() {
		return this.normalization.clone();
	}

	/** Combines statements for the same attribute by keeping this later statement. */
	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation
	) {
		if (existingMutation instanceof SetAttributeSchemaNormalizedMutation theExistingMutation &&
			this.name.equals(theExistingMutation.getName())
		) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	/** Combines statements for the same attribute by keeping this later statement. */
	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof SetAttributeSchemaNormalizedMutation theExistingMutation &&
			this.name.equals(theExistingMutation.getName())
		) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	/** Applies the normalization statement while retaining all other attribute settings. */
	@Nonnull
	@Override
	public <S extends AttributeSchemaContract> S mutate(
		@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema, @Nonnull Class<S> schemaType
	) {
		Assert.isPremiseValid(attributeSchema != null, "Attribute schema is mandatory!");
		final List<AnalysisStep> steps = Arrays.asList(this.normalization);
		if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
			if (globalAttributeSchema.getNormalization().equals(steps)) {
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
					steps
				);
			}
		} else if (attributeSchema instanceof EntityAttributeSchemaContract entityAttributeSchema) {
			if (entityAttributeSchema.getNormalization().equals(steps)) {
				return attributeSchema;
			} else {
				//noinspection unchecked,rawtypes
				return (S) EntityAttributeSchema._internalBuild(
					this.name,
					entityAttributeSchema.getNameVariants(),
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
					steps
				);
			}
		} else {
			if (attributeSchema.getNormalization().equals(steps)) {
				return attributeSchema;
			} else {
				//noinspection unchecked,rawtypes
				return (S) AttributeSchema._internalBuild(
					this.name,
					attributeSchema.getNameVariants(),
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
					steps
				);
			}
		}
	}

	/** Applies the global statement and propagates it to collections using the attribute. */
	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(
		@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor
	) {
		return mutateGlobalAttributeSchema(catalogSchema, entitySchemaAccessor, this);
	}

	/** Identifies this statement as an upsert operation. */
	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	/** Renders the complete normalization statement. */
	@Nonnull
	@Override
	public String toString() {
		return "Set attribute `" + this.name + "` schema: normalization=" + Arrays.toString(this.normalization);
	}

}
