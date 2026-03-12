/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.function.Functions;
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
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Mutation is responsible for setting value to a {@link ReferenceSchemaContract#isFaceted()}
 * in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link ReferenceSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class SetReferenceSchemaFacetedMutation
	extends AbstractModifyReferenceDataSchemaMutation
	implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -5012839462710583947L;
	@Getter @Nullable private final Scope[] facetedInScopes;
	/**
	 * Per-scope expressions narrowing which entities participate in faceting.
	 * Null means inherited from the reflected reference (only valid for reflected references),
	 * or "don't change" for non-reflected references.
	 */
	@Getter @Nullable private final ScopedFacetedPartially[] facetedPartiallyInScopes;

	/**
	 * Creates mutation that controls the faceted flag of the reference schema using a simple
	 * boolean (applied to the default scope). Null means inherited from the reflected reference.
	 */
	public SetReferenceSchemaFacetedMutation(
		@Nonnull String name,
		@Nullable Boolean faceted
	) {
		this(name, faceted == null ? null : (faceted ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE), null);
	}

	/**
	 * Creates mutation that controls the faceted flag with detailed per-scope configuration.
	 * Null means inherited from the reflected reference.
	 */
	public SetReferenceSchemaFacetedMutation(
		@Nonnull String name,
		@Nullable Scope[] facetedInScopes
	) {
		this(name, facetedInScopes, null);
	}

	/**
	 * Creates mutation that controls both the faceted flag and the facetedPartially expressions
	 * with detailed per-scope configuration. Null for either field means "inherited" for reflected
	 * references, or "don't change" for non-reflected references.
	 */
	@SerializableCreator
	public SetReferenceSchemaFacetedMutation(
		@Nonnull String name,
		@Nullable Scope[] facetedInScopes,
		@Nullable ScopedFacetedPartially[] facetedPartiallyInScopes
	) {
		super(name);
		this.facetedInScopes = facetedInScopes;
		this.facetedPartiallyInScopes = facetedPartiallyInScopes;
	}

	/**
	 * Returns the faceted flag for the default scope, or null when the value is inherited.
	 */
	@Nullable
	public Boolean getFaceted() {
		if (this.facetedInScopes == null) {
			return null;
		} else {
			return !ArrayUtils.isEmptyOrItsValuesNull(this.facetedInScopes);
		}
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (
			existingMutation instanceof SetReferenceSchemaFacetedMutation theExistingMutation &&
				this.name.equals(theExistingMutation.getName())
		) {
			// later mutation fully replaces the existing one
			return new MutationCombinationResult<>(null, this);
		} else if (
			existingMutation instanceof CreateReferenceSchemaMutation createMutation
				&& this.name.equals(createMutation.getName())
				&& (this.facetedInScopes != null || this.facetedPartiallyInScopes != null)
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
					this.facetedInScopes != null
						? this.facetedInScopes
						: createMutation.getFacetedInScopes(),
					this.facetedPartiallyInScopes != null
						? this.facetedPartiallyInScopes
						: createMutation.getFacetedPartiallyInScopes()
				)
			);
		} else if (
			existingMutation instanceof CreateReflectedReferenceSchemaMutation createMutation
				&& this.name.equals(createMutation.getName())
				&& (this.facetedInScopes != null || this.facetedPartiallyInScopes != null)
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
					this.facetedInScopes != null
						? this.facetedInScopes
						: createMutation.getFacetedInScopes(),
					this.facetedPartiallyInScopes != null
						? this.facetedPartiallyInScopes
						: createMutation.getFacetedPartiallyInScopes(),
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
			// apply faceted change if present
			if (this.facetedInScopes != null) {
				final EnumSet<Scope> facetedScopes = ArrayUtils.toEnumSet(Scope.class, this.facetedInScopes);
				final boolean alreadyMatches =
					!reflectedReferenceSchema.isFacetedInherited() &&
						reflectedReferenceSchema.getFacetedInScopes().equals(facetedScopes);
				if (!alreadyMatches) {
					result = reflectedReferenceSchema.withFaceted(this.facetedInScopes);
				}
			} else if (this.facetedPartiallyInScopes == null) {
				// both null — for reflected references null means "inherited";
				// only skip if already inherited
				if (reflectedReferenceSchema.isFacetedInherited()) {
					return reflectedReferenceSchema;
				}
				result = reflectedReferenceSchema.withFaceted(null);
			} else {
				// facetedInScopes is null (inherited) but facetedPartiallyInScopes is set —
				// transition to inherited faceting if not already
				if (!reflectedReferenceSchema.isFacetedInherited()) {
					result = reflectedReferenceSchema.withFaceted(null);
				}
			}
			// apply facetedPartially change if present
			if (this.facetedPartiallyInScopes != null &&
				result instanceof ReflectedReferenceSchema reflectedResult) {
				final Map<Scope, Expression> newFacetedPartiallyMap =
					ReferenceSchema.toFacetedPartiallyMap(this.facetedPartiallyInScopes);
				if (!reflectedResult.getFacetedPartiallyInScopes().equals(newFacetedPartiallyMap)) {
					result = reflectedResult.withFacetedPartially(newFacetedPartiallyMap);
				}
			}
			return result;
		} else {
			// non-reflected reference: null means "don't change"
			final Set<Scope> facetedScopes = this.facetedInScopes != null
				? ArrayUtils.toEnumSet(Scope.class, this.facetedInScopes)
				: referenceSchema.getFacetedInScopes();
			// compute new facetedPartially map
			final Map<Scope, Expression> newPartially;
			if (this.facetedPartiallyInScopes != null) {
				newPartially = ReferenceSchema.toFacetedPartiallyMap(this.facetedPartiallyInScopes);
			} else if (this.facetedInScopes != null) {
				// faceted scopes changed — filter out facetedPartially for scopes no longer faceted
				final Map<Scope, Expression> existingPartially = referenceSchema.getFacetedPartiallyInScopes();
				if (existingPartially.isEmpty()) {
					newPartially = existingPartially;
				} else {
					newPartially = new EnumMap<>(Scope.class);
					for (final Map.Entry<Scope, Expression> entry : existingPartially.entrySet()) {
						if (facetedScopes.contains(entry.getKey())) {
							newPartially.put(entry.getKey(), entry.getValue());
						}
					}
				}
			} else {
				newPartially = referenceSchema.getFacetedPartiallyInScopes();
			}
			// check if anything actually changed
			if (facetedScopes.equals(referenceSchema.getFacetedInScopes()) &&
				newPartially.equals(referenceSchema.getFacetedPartiallyInScopes())) {
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
				facetedScopes,
				newPartially,
				referenceSchema.getAttributes(),
				referenceSchema.getSortableAttributeCompounds()
			);
		}
	}

	@Override
	public String toString() {
		final Boolean faceted = getFaceted();
		final String facetedDescription;
		if (faceted == null) {
			facetedDescription = "(inherited)";
		} else if (faceted) {
			facetedDescription = "(faceted in scopes: " + Arrays.toString(this.facetedInScopes) + ")";
		} else {
			facetedDescription = "(not faceted)";
		}
		final String partiallyDescription;
		if (this.facetedPartiallyInScopes == null) {
			partiallyDescription = "";
		} else if (this.facetedPartiallyInScopes.length == 0) {
			partiallyDescription = ", facetedPartially=(none)";
		} else {
			partiallyDescription = ", facetedPartially=(in scopes: " +
				Arrays.toString(this.facetedPartiallyInScopes) + ")";
		}
		return "Set entity reference `" + this.name + "` schema: " +
			"faceted=" + facetedDescription + partiallyDescription;
	}
}
