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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.function.Functions;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Mutation is responsible for setting bucketed histogram configuration on a
 * {@link ReferenceSchemaContract} in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link ReferenceSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts
 * with the same mutation if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class SetReferenceSchemaBucketedMutation
	extends AbstractModifyReferenceDataSchemaMutation
	implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -7823946102836517850L;
	@Getter @Nullable private final ScopedHistogramIndexDefinition[] bucketedInScopes;
	/**
	 * Per-scope expressions narrowing which entities participate in bucketed histogram computation.
	 * Null means inherited from the reflected reference (only valid for reflected references),
	 * or "don't change" for non-reflected references.
	 */
	@Getter @Nullable private final ScopedBucketedPartially[] bucketedPartiallyInScopes;

	/**
	 * Creates mutation that controls the bucketed flag with detailed per-scope histogram
	 * configuration. Null means inherited from the reflected reference.
	 */
	public SetReferenceSchemaBucketedMutation(
		@Nonnull String name,
		@Nullable ScopedHistogramIndexDefinition[] bucketedInScopes
	) {
		this(name, bucketedInScopes, null);
	}

	/**
	 * Creates mutation that controls both the bucketed histogram configuration and the
	 * bucketedPartially expressions with detailed per-scope configuration. Null for either
	 * field means "inherited" for reflected references, or "don't change" for non-reflected
	 * references.
	 */
	@SerializableCreator
	public SetReferenceSchemaBucketedMutation(
		@Nonnull String name,
		@Nullable ScopedHistogramIndexDefinition[] bucketedInScopes,
		@Nullable ScopedBucketedPartially[] bucketedPartiallyInScopes
	) {
		super(name);
		this.bucketedInScopes = bucketedInScopes;
		this.bucketedPartiallyInScopes = bucketedPartiallyInScopes;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (
			existingMutation instanceof SetReferenceSchemaBucketedMutation theExistingMutation &&
				this.name.equals(theExistingMutation.getName())
		) {
			// later mutation fully replaces the existing one
			return new MutationCombinationResult<>(null, this);
		} else if (
			existingMutation instanceof CreateReferenceSchemaMutation createMutation
				&& this.name.equals(createMutation.getName())
				&& (this.bucketedInScopes != null || this.bucketedPartiallyInScopes != null)
		) {
			// Absorb into the Create mutation using pure replacement semantics.
			// The mutation always carries the complete state — the builder is responsible
			// for collecting all scopes and expressions before emitting the mutation.
			return new MutationCombinationResult<>(
				new CreateReferenceSchemaMutation(
					createMutation.getName(),
					createMutation.getDescription(),
					createMutation.getDeprecationNotice(),
					createMutation.getCardinality(),
					createMutation.getReferencedEntityType(),
					createMutation.isReferencedEntityTypeManaged(),
					createMutation.getReferencedGroupType(),
					createMutation.isReferencedGroupTypeManaged(),
					createMutation.getIndexedInScopes(),
					createMutation.getIndexedComponentsInScopes(),
					createMutation.getFacetedInScopes(),
					createMutation.getFacetedPartiallyInScopes(),
					this.bucketedInScopes != null
						? this.bucketedInScopes
						: createMutation.getBucketedInScopes(),
					this.bucketedPartiallyInScopes != null
						? this.bucketedPartiallyInScopes
						: createMutation.getBucketedPartiallyInScopes()
				)
			);
		} else if (
			existingMutation instanceof CreateReflectedReferenceSchemaMutation createMutation
				&& this.name.equals(createMutation.getName())
				&& (this.bucketedInScopes != null || this.bucketedPartiallyInScopes != null)
		) {
			// Absorb into the CreateReflected mutation using pure replacement semantics.
			return new MutationCombinationResult<>(
				new CreateReflectedReferenceSchemaMutation(
					createMutation.getName(),
					createMutation.getDescription(),
					createMutation.getDeprecationNotice(),
					createMutation.getCardinality(),
					createMutation.getReferencedEntityType(),
					createMutation.getReflectedReferenceName(),
					createMutation.getIndexedInScopes(),
					createMutation.getIndexedComponentsInScopes(),
					createMutation.getFacetedInScopes(),
					createMutation.getFacetedPartiallyInScopes(),
					this.bucketedInScopes != null
						? this.bucketedInScopes
						: createMutation.getBucketedInScopes(),
					this.bucketedPartiallyInScopes != null
						? this.bucketedPartiallyInScopes
						: createMutation.getBucketedPartiallyInScopes(),
					createMutation.getAttributeInheritanceBehavior(),
					createMutation.getAttributeInheritanceFilter()
				)
			);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull ConsistencyChecks consistencyChecks
	) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
			ReferenceSchemaContract result = reflectedReferenceSchema;
			// apply bucketed change if present
			if (this.bucketedInScopes != null) {
				final Map<Scope, HistogramIndexDefinition> bucketedMap =
					ReferenceSchema.toBucketedHistogramMap(this.bucketedInScopes);
				final boolean alreadyMatches =
					!reflectedReferenceSchema.isBucketedInherited() &&
						reflectedReferenceSchema.getHistogramIndexDefinitions().equals(bucketedMap);
				if (!alreadyMatches) {
					result = reflectedReferenceSchema.withBucketed(bucketedMap);
				}
			} else if (this.bucketedPartiallyInScopes == null) {
				// both null — for reflected references null means "inherited";
				// only skip if already inherited
				if (reflectedReferenceSchema.isBucketedInherited()) {
					return reflectedReferenceSchema;
				}
				result = reflectedReferenceSchema.withBucketed(null);
			} else {
				// bucketedInScopes is null (inherited) but bucketedPartiallyInScopes is set —
				// transition to inherited bucketing if not already
				if (!reflectedReferenceSchema.isBucketedInherited()) {
					result = reflectedReferenceSchema.withBucketed(null);
				}
			}
			// apply bucketedPartially change if present
			if (this.bucketedPartiallyInScopes != null &&
				result instanceof ReflectedReferenceSchema reflectedResult) {
				final Map<Scope, Expression> newBucketedPartiallyMap =
					ReferenceSchema.toBucketedPartiallyMap(this.bucketedPartiallyInScopes);
				if (!reflectedResult.getBucketedPartiallyInScopes().equals(newBucketedPartiallyMap)) {
					result = reflectedResult.withBucketedPartially(newBucketedPartiallyMap);
				}
			}
			return result;
		} else {
			// non-reflected reference: null means "don't change"
			final Map<Scope, HistogramIndexDefinition> bucketedScopes = this.bucketedInScopes != null
				? ReferenceSchema.toBucketedHistogramMap(this.bucketedInScopes)
				: referenceSchema.getHistogramIndexDefinitions();
			// compute new bucketedPartially map
			final Map<Scope, Expression> newPartially;
			if (this.bucketedPartiallyInScopes != null) {
				newPartially = ReferenceSchema.toBucketedPartiallyMap(this.bucketedPartiallyInScopes);
			} else if (this.bucketedInScopes != null) {
				// bucketed scopes changed — filter out bucketedPartially for scopes no longer bucketed
				final Map<Scope, Expression> existingPartially = referenceSchema.getBucketedPartiallyInScopes();
				if (existingPartially.isEmpty()) {
					newPartially = existingPartially;
				} else {
					newPartially = new EnumMap<>(Scope.class);
					for (final Map.Entry<Scope, Expression> entry : existingPartially.entrySet()) {
						if (bucketedScopes.containsKey(entry.getKey())) {
							newPartially.put(entry.getKey(), entry.getValue());
						}
					}
				}
			} else {
				newPartially = referenceSchema.getBucketedPartiallyInScopes();
			}
			// check if anything actually changed
			if (bucketedScopes.equals(referenceSchema.getHistogramIndexDefinitions()) &&
				newPartially.equals(referenceSchema.getBucketedPartiallyInScopes())) {
				return referenceSchema;
			}
			return ReferenceSchema._internalBuild(
				this.name,
				referenceSchema.getNameVariants(),
				referenceSchema.getDescription(),
				referenceSchema.getDeprecationNotice(),
				referenceSchema.getCardinality(),
				referenceSchema.getReferencedEntityType(),
				referenceSchema.isReferencedEntityTypeManaged()
					? Collections.emptyMap()
					: referenceSchema.getEntityTypeNameVariants(Functions.noOpFunction()),
				referenceSchema.isReferencedEntityTypeManaged(),
				referenceSchema.getReferencedGroupType(),
				referenceSchema.isReferencedGroupTypeManaged()
					? Collections.emptyMap()
					: referenceSchema.getGroupTypeNameVariants(Functions.noOpFunction()),
				referenceSchema.isReferencedGroupTypeManaged(),
				referenceSchema.getReferenceIndexTypeInScopes(),
				referenceSchema.getIndexedComponentsInScopes(),
				referenceSchema.getFacetedInScopes(),
				referenceSchema.getFacetedPartiallyInScopes(),
				bucketedScopes,
				newPartially,
				referenceSchema.getAttributes(),
				referenceSchema.getSortableAttributeCompounds()
			);
		}
	}

	@Override
	public String toString() {
		final String bucketedDescription;
		if (this.bucketedInScopes == null) {
			bucketedDescription = "(inherited)";
		} else if (this.bucketedInScopes.length == 0) {
			bucketedDescription = "(not bucketed)";
		} else {
			bucketedDescription = "(bucketed in scopes: " + Arrays.toString(this.bucketedInScopes) + ")";
		}
		final String partiallyDescription;
		if (this.bucketedPartiallyInScopes == null) {
			partiallyDescription = "";
		} else if (this.bucketedPartiallyInScopes.length == 0) {
			partiallyDescription = ", bucketedPartially=(none)";
		} else {
			partiallyDescription = ", bucketedPartially=(in scopes: " +
				Arrays.toString(this.bucketedPartiallyInScopes) + ")";
		}
		return "Set entity reference `" + this.name + "` schema: " +
			"bucketed=" + bucketedDescription + partiallyDescription;
	}
}
