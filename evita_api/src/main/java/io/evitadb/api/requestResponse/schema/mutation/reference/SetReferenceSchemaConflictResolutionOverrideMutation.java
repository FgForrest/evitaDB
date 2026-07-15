/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Collections;

/**
 * Mutation is responsible for setting value to a {@link ReferenceSchemaContract#getConflictResolutionOverride()}
 * in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link ReferenceSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class SetReferenceSchemaConflictResolutionOverrideMutation
	extends AbstractModifyReferenceDataSchemaMutation
	implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -1637248490553201984L;
	@Getter @Nonnull private final ConflictResolutionOverride conflictResolutionOverride;

	/**
	 * Creates a mutation that will change the conflict resolution override of an existing reference schema.
	 *
	 * @param name                       name of the reference schema to modify
	 * @param conflictResolutionOverride new value of the conflict resolution override
	 */
	public SetReferenceSchemaConflictResolutionOverrideMutation(
		@Nonnull String name,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride
	) {
		super(name);
		this.conflictResolutionOverride = conflictResolutionOverride;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof SetReferenceSchemaConflictResolutionOverrideMutation theExistingMutation
			&& this.name.equals(theExistingMutation.getName())
		) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws InvalidSchemaMutationException when applied to a {@link ReflectedReferenceSchema}, which always
	 *         inherits its conflict resolution and can therefore never carry an explicit override
	 */
	@Nonnull
	@Override
	public ReferenceSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull ConsistencyChecks consistencyChecks
	) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		if (referenceSchema.getConflictResolutionOverride() == this.conflictResolutionOverride) {
			// the mutation must have been applied previously - return the schema we don't need to alter
			return referenceSchema;
		}
		if (referenceSchema instanceof ReflectedReferenceSchema) {
			// a reflected reference always inherits its conflict resolution - it can never carry an
			// explicit override, so any attempt to set a non-inherited value is a programming error
			throw new InvalidSchemaMutationException(
				"The conflict resolution override cannot be set on reflected reference `" + this.name +
					"` - it is always inherited from the target reference!"
			);
		}
		return ReferenceSchema._internalBuild(
			referenceSchema.getName(),
			referenceSchema.getNameVariants(),
			referenceSchema.getDescription(),
			referenceSchema.getDeprecationNotice(),
			referenceSchema.getCardinality(),
			referenceSchema.getReferencedEntityType(),
			referenceSchema.isReferencedEntityTypeManaged()
				? Collections.emptyMap()
				: referenceSchema.getEntityTypeNameVariants(s -> null),
			referenceSchema.isReferencedEntityTypeManaged(),
			referenceSchema.getReferencedGroupType(),
			referenceSchema.isReferencedGroupTypeManaged()
				? Collections.emptyMap()
				: referenceSchema.getGroupTypeNameVariants(s -> null),
			referenceSchema.isReferencedGroupTypeManaged(),
			referenceSchema.getReferenceIndexTypeInScopes(),
			referenceSchema.getIndexedComponentsInScopes(),
			referenceSchema.getFacetedInScopes(),
			referenceSchema.getFacetedPartiallyInScopes(),
			referenceSchema.getAllHistogramIndexDefinitions(),
			referenceSchema.getBucketedPartiallyInScopes(),
			referenceSchema.getAttributes(),
			referenceSchema.getSortableAttributeCompounds(),
			this.conflictResolutionOverride
		);
	}

	@Override
	public String toString() {
		return "Set entity reference `" + this.name + "` schema: " +
			"conflictResolutionOverride=" + this.conflictResolutionOverride;
	}
}
