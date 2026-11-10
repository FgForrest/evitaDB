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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSearchMode;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Replaces reference text composition scopes and/or the shared ranking weight. Null components preserve their
 * current values, including on reflected references. This mutation changes metadata only and never rebuilds an index.
 *
 * Applied with {@link ConsistencyChecks#APPLY}, it refuses a declaration the reference cannot support: an unmanaged
 * target, a declared scope not indexed with the referenced entity, or a weight that is not finite and non-negative.
 * The index settings a reflected reference inherits from an original that is not bound yet are checked when the
 * session defining it closes.
 *
 * It folds into the creation of its reference unless a change of the indexed scopes or of the target follows that
 * creation, and it never combines with another set mutation, so a declaration never moves across a change of what it
 * requires.
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode(callSuper = true)
public class SetReferenceSchemaSearchableThroughMutation
	extends AbstractModifyReferenceDataSchemaMutation
	implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -5078953583783072254L;
	/** Complete scope map; null keeps the existing modes, an empty array removes them all. */
	@Getter @Nullable private final ScopedReferenceSearchMode[] searchableThroughInScopes;
	/** Shared weight; null keeps the existing weight. */
	@Getter @Nullable private final Float weight;

	/**
	 * Creates an independent update of the scope map and shared weight.
	 *
	 * @param name reference to update
	 * @param searchableThroughInScopes complete replacement map, or null to keep it
	 * @param weight shared ranking weight, or null to keep it
	 */
	@SerializableCreator
	public SetReferenceSchemaSearchableThroughMutation(
		@Nonnull String name,
		@Nullable ScopedReferenceSearchMode[] searchableThroughInScopes,
		@Nullable Float weight
	) {
		super(name);
		this.searchableThroughInScopes = searchableThroughInScopes == null
			? null : searchableThroughInScopes.clone();
		this.weight = weight == null ? null : AttributeSchemaContract.normalizeSearchWeight(weight);
	}

	/**
	 * Refuses a declaration whose effective schema cannot support text composition.
	 * Inherited index settings of an unbound reflected reference are validated once bound at session close.
	 *
	 * @param entitySchema owner of the reference
	 * @param referenceSchema assembled reference with the requested settings
	 */
	public static void verifyRequirements(
		@Nonnull EntitySchemaContract entitySchema, @Nonnull ReferenceSchema referenceSchema
	) {
		final List<String> errors = referenceSchema.validateSearchableThrough(entitySchema.getName()).toList();
		Assert.isTrue(errors.isEmpty(), () -> new InvalidSchemaMutationException(String.join("\n", errors)));
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull ConsistencyChecks consistencyChecks
	) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		final Map<Scope, ReferenceSearchMode> modes = this.searchableThroughInScopes == null
			? referenceSchema.getSearchableThroughInScopes()
			: ReferenceSchema.toSearchableThroughMap(this.searchableThroughInScopes);
		final float newWeight = this.weight == null ? referenceSchema.getSearchableThroughWeight() : this.weight;
		final ReferenceSchema result = ((ReferenceSchema) referenceSchema).withSearchableThrough(modes, newWeight);
		if (consistencyChecks == ConsistencyChecks.APPLY) {
			verifyRequirements(entitySchema, result);
		}
		return result.equals(referenceSchema) ? referenceSchema : result;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		// Keep separate sets ordered around prerequisite changes (index, components or target).
		if (existingMutation instanceof CreateReferenceSchemaMutation previous &&
			this.name.equals(previous.getName())) {
			return new MutationCombinationResult<>(new CreateReferenceSchemaMutation(
				previous.getName(),
				previous.getDescription(),
				previous.getDeprecationNotice(),
				previous.getCardinality(),
				previous.getReferencedEntityType(),
				previous.isReferencedEntityTypeManaged(),
				previous.getReferencedGroupType(),
				previous.isReferencedGroupTypeManaged(),
				previous.getIndexedInScopes(),
				previous.getIndexedComponentsInScopes(),
				previous.getFacetedInScopes(),
				previous.getFacetedPartiallyInScopes(),
				previous.getBucketedInScopes(),
				previous.getBucketedPartiallyInScopes(),
				previous.getConflictResolutionOverride(),
				this.searchableThroughInScopes == null ? previous.getSearchableThroughInScopes()
					: this.searchableThroughInScopes,
				this.weight == null ? previous.getSearchableThroughWeight() : this.weight
			));
		} else if (existingMutation instanceof CreateReflectedReferenceSchemaMutation previous &&
			this.name.equals(previous.getName())) {
			return new MutationCombinationResult<>(new CreateReflectedReferenceSchemaMutation(
				previous.getName(),
				previous.getDescription(),
				previous.getDeprecationNotice(),
				previous.getCardinality(),
				previous.getReferencedEntityType(),
				previous.getReflectedReferenceName(),
				previous.getIndexedInScopes(),
				previous.getIndexedComponentsInScopes(),
				previous.getFacetedInScopes(),
				previous.getFacetedPartiallyInScopes(),
				previous.getBucketedInScopes(),
				previous.getBucketedPartiallyInScopes(),
				previous.getAttributeInheritanceBehavior(),
				previous.getAttributeInheritanceFilter(),
				this.searchableThroughInScopes == null ? previous.getSearchableThroughInScopes()
					: this.searchableThroughInScopes,
				this.weight == null ? previous.getSearchableThroughWeight() : this.weight
			));
		}
		return null;
	}

	@Override
	public String toString() {
		return "Set entity reference `" + this.name + "` schema: searchableThrough=" +
			Arrays.toString(this.searchableThroughInScopes) + ", weight=" + this.weight;
	}
}
