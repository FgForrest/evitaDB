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
import java.util.EnumSet;

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
	@Serial private static final long serialVersionUID = 4479269384430732059L;
	@Getter @Nullable private final Scope[] facetedInScopes;

	/**
	 * Creates mutation that controls the faceted flag of the reference schema using a simple
	 * boolean (applied to the default scope). Null means inherited from the reflected reference.
	 */
	public SetReferenceSchemaFacetedMutation(
		@Nonnull String name,
		@Nullable Boolean faceted
	) {
		this(name, faceted == null ? null : (faceted ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE));
	}

	/**
	 * Creates mutation that controls the faceted flag with detailed per-scope configuration.
	 * Null means inherited from the reflected reference.
	 */
	@SerializableCreator
	public SetReferenceSchemaFacetedMutation(
		@Nonnull String name,
		@Nullable Scope[] facetedInScopes
	) {
		super(name);
		this.facetedInScopes = facetedInScopes;
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
		if (existingMutation instanceof SetReferenceSchemaFacetedMutation theExistingMutation
			&& this.name.equals(theExistingMutation.getName())) {
			return new MutationCombinationResult<>(null, this);
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
		final EnumSet<Scope> facetedScopes = ArrayUtils.toEnumSet(Scope.class, this.facetedInScopes);
		if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
			final boolean alreadyMatches =
				(reflectedReferenceSchema.isFacetedInherited() && this.facetedInScopes == null) ||
				(!reflectedReferenceSchema.isFacetedInherited() &&
					reflectedReferenceSchema.getFacetedInScopes().equals(facetedScopes));
			if (alreadyMatches) {
				return reflectedReferenceSchema;
			} else {
				return reflectedReferenceSchema.withFaceted(this.facetedInScopes);
			}
		} else {
			if (facetedScopes.containsAll(referenceSchema.getFacetedInScopes()) &&
				facetedScopes.size() == referenceSchema.getFacetedInScopes().size()) {
				return referenceSchema;
			} else {
				return ReferenceSchema._internalBuild(
					this.name,
					referenceSchema.getNameVariants(),
					referenceSchema.getDescription(),
					referenceSchema.getDeprecationNotice(),
					referenceSchema.getCardinality(),
					referenceSchema.getReferencedEntityType(),
					referenceSchema.isReferencedEntityTypeManaged() ? Collections.emptyMap() : referenceSchema.getEntityTypeNameVariants(s -> null),
					referenceSchema.isReferencedEntityTypeManaged(),
					referenceSchema.getReferencedGroupType(),
					referenceSchema.isReferencedGroupTypeManaged() ? Collections.emptyMap() : referenceSchema.getGroupTypeNameVariants(s -> null),
					referenceSchema.isReferencedGroupTypeManaged(),
					referenceSchema.getReferenceIndexTypeInScopes(),
					facetedScopes,
					referenceSchema.getAttributes(),
					referenceSchema.getSortableAttributeCompounds()
				);
			}
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
		return "Set entity reference `" + this.name + "` schema: " +
			"faceted=" + facetedDescription;
	}
}
