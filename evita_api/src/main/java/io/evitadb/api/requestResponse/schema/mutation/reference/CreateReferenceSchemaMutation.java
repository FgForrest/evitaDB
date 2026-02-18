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

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaWithDeprecationContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CreateMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.dataType.Scope.DEFAULT_SCOPE;
import static io.evitadb.dataType.Scope.NO_SCOPE;

/**
 * Mutation is responsible for setting up a new {@link ReferenceSchemaContract} in the {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link ReferenceSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with
 * {@link RemoveReferenceSchemaMutation} mutation (if such is found in mutation pipeline).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class CreateReferenceSchemaMutation
	extends AbstractReferenceDataSchemaMutation
	implements ReferenceSchemaMutation, CombinableLocalEntitySchemaMutation, CreateMutation {
	@Serial private static final long serialVersionUID = -5200773391501101688L;

	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nonnull private final Cardinality cardinality;
	@Getter @Nonnull private final String referencedEntityType;
	@Getter private final boolean referencedEntityTypeManaged;
	@Getter @Nullable private final String referencedGroupType;
	@Getter private final boolean referencedGroupTypeManaged;
	@Getter @Nonnull private final ScopedReferenceIndexType[] indexedInScopes;
	@Getter @Nonnull private final Scope[] facetedInScopes;

	/**
	 * Creates mutation that sets up a new reference schema with the given properties using simple boolean
	 * flags for indexed/faceted configuration (applied to the default scope only).
	 */
	public CreateReferenceSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Cardinality cardinality,
		@Nonnull String referencedEntityType,
		boolean referencedEntityTypeManaged,
		@Nullable String referencedGroupType,
		boolean referencedGroupTypeManaged,
		boolean indexed,
		boolean faceted
	) {
		this(
			name, description, deprecationNotice, cardinality,
			referencedEntityType, referencedEntityTypeManaged,
			referencedGroupType, referencedGroupTypeManaged,
			indexed
				? new ScopedReferenceIndexType[] {
					new ScopedReferenceIndexType(DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
				}
				: ScopedReferenceIndexType.EMPTY,
			faceted ? Scope.DEFAULT_SCOPES : NO_SCOPE
		);
	}

	/**
	 * Creates mutation that sets up a new reference schema with detailed per-scope indexed/faceted configuration.
	 */
	@SerializableCreator
	public CreateReferenceSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Cardinality cardinality,
		@Nonnull String referencedEntityType,
		boolean referencedEntityTypeManaged,
		@Nullable String referencedGroupType,
		boolean referencedGroupTypeManaged,
		@Nullable ScopedReferenceIndexType[] indexedInScopes,
		@Nullable Scope[] facetedInScopes
	) {
		super(name);
		ClassifierUtils.validateClassifierFormat(ClassifierType.REFERENCE, name);
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, referencedEntityType);
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.cardinality = cardinality == null ? Cardinality.ZERO_OR_MORE : cardinality;
		this.referencedEntityType = referencedEntityType;
		this.referencedEntityTypeManaged = referencedEntityTypeManaged;
		this.referencedGroupType = referencedGroupType;
		this.referencedGroupTypeManaged = referencedGroupTypeManaged;
		this.indexedInScopes = indexedInScopes == null ? ScopedReferenceIndexType.EMPTY : indexedInScopes;
		this.facetedInScopes = facetedInScopes == null ? NO_SCOPE : facetedInScopes;
	}

	/**
	 * Returns true if the reference is indexed in the default scope.
	 */
	public boolean isIndexed() {
		return Arrays.stream(this.indexedInScopes)
			.anyMatch(
				it -> it.scope() == DEFAULT_SCOPE && it.indexType() != ReferenceIndexType.NONE
			);
	}

	/**
	 * Returns true if the reference is faceted in the default scope.
	 */
	public boolean isFaceted() {
		return Arrays.stream(this.facetedInScopes)
			.anyMatch(scope -> scope == DEFAULT_SCOPE);
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		// when the reference schema was removed before and added again, we may remove both operations
		// and leave only operations that reset the original settings to defaults
		final Optional<ReferenceSchemaContract> currentReference = currentEntitySchema.getReference(this.name);
		if (
			existingMutation instanceof RemoveReferenceSchemaMutation removeReferenceMutation &&
				Objects.equals(removeReferenceMutation.getName(), this.name) && currentReference.isPresent()
		) {
			// we can convert mutation to updates only if the reference type matches
			final ReferenceSchemaContract existingVersion = currentReference.get();
			if (!(existingVersion instanceof ReflectedReferenceSchemaContract)) {
				final ReferenceSchemaContract createdVersion = Objects.requireNonNull(
					mutate(currentEntitySchema, null)
				);

				return new MutationCombinationResult<>(
					null,
					Stream.of(
							Stream.of(
								makeMutationIfDifferent(
									ReferenceSchemaContract.class,
									createdVersion, existingVersion,
									NamedSchemaContract::getDescription,
									newValue -> new ModifyReferenceSchemaDescriptionMutation(this.name, newValue)
								),
								makeMutationIfDifferent(
									ReferenceSchemaContract.class,
									createdVersion, existingVersion,
									NamedSchemaWithDeprecationContract::getDeprecationNotice,
									newValue -> new ModifyReferenceSchemaDeprecationNoticeMutation(this.name, newValue)
								),
								makeMutationIfDifferent(
									ReferenceSchemaContract.class,
									createdVersion, existingVersion,
									ReferenceSchemaContract::getCardinality,
									newValue -> new ModifyReferenceSchemaCardinalityMutation(this.name, newValue)
								),
								makeMutationIfDifferent(
									ReferenceSchemaContract.class,
									createdVersion, existingVersion,
									ReferenceSchemaContract::getReferencedEntityType,
									newValue -> new ModifyReferenceSchemaRelatedEntityMutation(
										this.name, newValue, this.referencedEntityTypeManaged
									)
								),
								makeMutationIfDifferent(
									ReferenceSchemaContract.class,
									createdVersion, existingVersion,
									ReferenceSchemaContract::getReferencedGroupType,
									newValue -> new ModifyReferenceSchemaRelatedEntityGroupMutation(
										this.name, newValue, this.referencedGroupTypeManaged
									)
								),
								makeMutationIfDifferent(
									ReferenceSchemaContract.class,
									createdVersion, existingVersion,
									ref -> ref.getReferenceIndexTypeInScopes()
										.entrySet()
										.stream()
										.map(it -> new ScopedReferenceIndexType(it.getKey(), it.getValue()))
										.toArray(ScopedReferenceIndexType[]::new),
									newValue -> new SetReferenceSchemaIndexedMutation(this.name, newValue)
								),
								makeMutationIfDifferent(
									ReferenceSchemaContract.class,
									createdVersion, existingVersion,
									ref -> Arrays.stream(Scope.values())
										.filter(ref::isFacetedInScope)
										.toArray(Scope[]::new),
									newValue -> new SetReferenceSchemaFacetedMutation(this.name, newValue)
								)
							),
							existingVersion.getAttributes()
								.values()
								.stream()
								.map(attribute -> new ModifyReferenceAttributeSchemaMutation(
									this.name,
									new RemoveAttributeSchemaMutation(
										attribute.getName()
									)
								)),
							existingVersion.getSortableAttributeCompounds()
								.values()
								.stream()
								.map(attribute -> new ModifyReferenceSortableAttributeCompoundSchemaMutation(
									this.name,
									new RemoveSortableAttributeCompoundSchemaMutation(
										attribute.getName()
									)
								))
						)
						.flatMap(Function.identity())
						.filter(Objects::nonNull)
						.toArray(LocalEntitySchemaMutation[]::new)
				);
			} else {
				return null;
			}
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
		return ReferenceSchema._internalBuild(
			this.name, this.description, this.deprecationNotice,
			this.referencedEntityType, this.referencedEntityTypeManaged,
			this.cardinality,
			this.referencedGroupType, this.referencedGroupTypeManaged,
			this.indexedInScopes, this.facetedInScopes,
			Collections.emptyMap(),
			Collections.emptyMap()
		);
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema
	) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final ReferenceSchemaContract newReferenceSchema = Objects.requireNonNull(
			this.mutate(entitySchema, null)
		);
		return insertNewReference(entitySchema, this.name, newReferenceSchema, newReferenceSchema);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Create entity reference schema: " +
			"name='" + this.name + '\'' +
			", description='" + this.description + '\'' +
			", deprecationNotice='" + this.deprecationNotice + '\'' +
			", cardinality=" + this.cardinality +
			", entityType='" + this.referencedEntityType + '\'' +
			", referencedEntityTypeManaged=" + this.referencedEntityTypeManaged +
			", groupType='" + this.referencedGroupType + '\'' +
			", referencedGroupTypeManaged=" + this.referencedGroupTypeManaged +
			", indexed=" + Arrays.toString(this.indexedInScopes) +
			", faceted=" + Arrays.toString(this.facetedInScopes);
	}

}
