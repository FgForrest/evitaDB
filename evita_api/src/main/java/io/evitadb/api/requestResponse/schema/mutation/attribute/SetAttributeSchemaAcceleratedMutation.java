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
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
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
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.EnumMap;
import java.util.Set;

/**
 * Mutation is responsible for setting the optional {@link AttributeFilterAccelerator accelerators} of an
 * {@link AttributeSchemaContract} in {@link EntitySchemaContract}, and of a
 * {@link GlobalAttributeSchemaContract} in {@link CatalogSchemaContract}.
 *
 * The mutation is a **full statement of the accelerator axis** - it names every scope that should carry an
 * accelerator once it is applied, and a scope it does not name ends up with none. That is what makes combining two
 * of them a plain last-one-wins, and it is why the builder resolves its per-scope deltas against the schema before
 * emitting one rather than shipping the delta itself.
 *
 * The axis is orthogonal to {@link SetAttributeSchemaFilterableMutation}: this mutation never changes *whether* the
 * attribute can be filtered by, only how fast one shape of filter is answered. It does require the index it
 * accelerates to exist, which is a filter index rather than filterability specifically - see
 * {@link AttributeSchemaContract#hasFilterIndexInScope(Scope)}.
 *
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class SetAttributeSchemaAcceleratedMutation
	extends AbstractAttributeSchemaMutation
	implements EntityAttributeSchemaMutation, GlobalAttributeSchemaMutation, ReferenceAttributeSchemaMutation,
	CombinableLocalEntitySchemaMutation, CombinableCatalogSchemaMutation {
	@Serial private static final long serialVersionUID = 5031892604771394877L;

	/**
	 * The accelerators the attribute's filter index should maintain, per scope. Never `null` after construction - an
	 * absent field on the wire (an older client, an older WAL record) deserializes as the empty array, which states
	 * "no acceleration anywhere" and is exactly what every schema written before this axis existed means.
	 */
	@Getter @Nonnull private final ScopedAttributeFilterAccelerators[] acceleratorsInScopes;

	/**
	 * Creates a mutation stating the accelerators the attribute should maintain in every scope.
	 *
	 * @param name                 name of the altered attribute
	 * @param acceleratorsInScopes one carrier per scope that should maintain at least one accelerator; a scope not
	 *                             named here ends up with none. May be `null`, which means "no accelerator anywhere"
	 */
	@SerializableCreator
	public SetAttributeSchemaAcceleratedMutation(
		@Nonnull String name,
		@Nullable ScopedAttributeFilterAccelerators... acceleratorsInScopes
	) {
		super(name);
		this.acceleratorsInScopes = acceleratorsInScopes == null ?
			ScopedAttributeFilterAccelerators.EMPTY : acceleratorsInScopes;
	}

	/**
	 * Whether this mutation asks for any acceleration at all - false when it withdraws the whole axis.
	 *
	 * @return true when at least one carrier names at least one accelerator
	 */
	public boolean isAccelerated() {
		for (final ScopedAttributeFilterAccelerators scopedAccelerators : this.acceleratorsInScopes) {
			if (scopedAccelerators.accelerators().length > 0) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation
	) {
		if (existingMutation instanceof SetAttributeSchemaAcceleratedMutation theExistingMutation &&
			this.name.equals(theExistingMutation.getName())
		) {
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
		if (existingMutation instanceof SetAttributeSchemaAcceleratedMutation theExistingMutation &&
			this.name.equals(theExistingMutation.getName())
		) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public <S extends AttributeSchemaContract> S mutate(
		@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema, @Nonnull Class<S> schemaType
	) {
		Assert.isPremiseValid(attributeSchema != null, "Attribute schema is mandatory!");
		final EnumMap<Scope, Set<AttributeFilterAccelerator>> accelerators =
			AttributeSchema.toAcceleratorsEnumMap(this.acceleratorsInScopes);
		verifyAcceleratorsApplicableToType(this.name, attributeSchema.getType(), accelerators);
		// deliberately NOT checked here: that every declared scope has a filter index. That is an invariant of the
		// *assembled* schema, and this mutation is applied incrementally - a builder combines a filterability change
		// with an accelerator change and may apply them in either order, so the schema this method sees is routinely
		// an intermediate state whose filterability has not caught up yet. Refusing here made declaration order
		// significant, which it must not be. The invariant is enforced where the state is final instead:
		// `AbstractAttributeSchemaBuilder#validate` for the builder path, and the create mutations' constructors,
		// which carry the whole attribute in one payload and therefore have no intermediate state at all.
		if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
			if (globalAttributeSchema.getAcceleratorsInScopes().equals(accelerators)) {
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
					accelerators,
					globalAttributeSchema.getSortableInScopes(),
					globalAttributeSchema.isLocalized(),
					globalAttributeSchema.isNullable(),
					globalAttributeSchema.isRepresentative(),
					(Class) globalAttributeSchema.getType(),
					globalAttributeSchema.getDefaultValue(),
					globalAttributeSchema.getIndexedDecimalPlaces(),
					globalAttributeSchema.getConflictResolutionOverride()
				);
			}
		} else if (attributeSchema instanceof EntityAttributeSchemaContract entityAttributeSchema) {
			if (entityAttributeSchema.getAcceleratorsInScopes().equals(accelerators)) {
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
					accelerators,
					entityAttributeSchema.getSortableInScopes(),
					entityAttributeSchema.isLocalized(),
					entityAttributeSchema.isNullable(),
					entityAttributeSchema.isRepresentative(),
					(Class) entityAttributeSchema.getType(),
					entityAttributeSchema.getDefaultValue(),
					entityAttributeSchema.getIndexedDecimalPlaces(),
					entityAttributeSchema.getConflictResolutionOverride()
				);
			}
		} else {
			if (attributeSchema.getAcceleratorsInScopes().equals(accelerators)) {
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
					accelerators,
					attributeSchema.getSortableInScopes(),
					attributeSchema.isLocalized(),
					attributeSchema.isNullable(),
					attributeSchema.isRepresentative(),
					(Class) attributeSchema.getType(),
					attributeSchema.getDefaultValue(),
					attributeSchema.getIndexedDecimalPlaces(),
					attributeSchema.getConflictResolutionOverride()
				);
			}
		}
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(
		@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor
	) {
		return mutateGlobalAttributeSchema(catalogSchema, entitySchemaAccessor, this);
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull ConsistencyChecks consistencyChecks
	) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		// deliberately outside the consistency-check guard: this is not a consistency question about the reference's
		// current state but a flat statement that the accelerator cannot be served here at all, so a SKIP caller must
		// not be able to write a declaration nothing will ever honour
		verifyAcceleratorNotOnReferenceAttribute(
			this.name, referenceSchema.getName(), entitySchema.getName(), this.acceleratorsInScopes
		);
		return ReferenceAttributeSchemaMutation.super.mutate(entitySchema, referenceSchema, consistencyChecks);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Set attribute `" + this.name + "` schema: " +
			"accelerators=" + (isAccelerated() ? "(" + join(this.acceleratorsInScopes) + ")" : "none");
	}

}
