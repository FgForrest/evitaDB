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

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaWithDeprecationContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
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

/**
 * Mutation is responsible for setting up a new {@link ReflectedReferenceSchemaContract} in the {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link ReflectedReferenceSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with
 * {@link RemoveReferenceSchemaMutation} mutation (if such is found in mutation pipeline).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class CreateReflectedReferenceSchemaMutation
	extends AbstractReferenceDataSchemaMutation
	implements ReferenceSchemaMutation, CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = 8762548294001376251L;

	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nullable private final Cardinality cardinality;
	@Getter @Nonnull private final String referencedEntityType;
	@Getter @Nonnull private final String reflectedReferenceName;
	@Getter @Nullable private final ScopedReferenceIndexType[] indexedInScopes;
	@Getter @Nullable private final ScopedReferenceIndexedComponents[] indexedComponentsInScopes;
	@Getter @Nullable private final Scope[] facetedInScopes;
	@Getter @Nonnull private final AttributeInheritanceBehavior attributeInheritanceBehavior;
	@Getter @Nonnull private final String[] attributeInheritanceFilter;

	/**
	 * Compares a property between the created and existing reflected reference schema versions.
	 * Returns a mutation if the values differ, or null if they are equal.
	 */
	@Nullable
	private static <T> LocalEntitySchemaMutation makeMutationIfDifferent(
		@Nonnull ReflectedReferenceSchemaContract createdVersion,
		@Nonnull ReflectedReferenceSchemaContract existingVersion,
		@Nonnull Function<ReflectedReferenceSchemaContract, T> propertyRetriever,
		@Nonnull Function<T, LocalEntitySchemaMutation> mutationCreator
	) {
		final T newValue = propertyRetriever.apply(createdVersion);
		final T existingValue = propertyRetriever.apply(existingVersion);
		// Arrays require content-based comparison instead of reference equality
		final boolean equal;
		if (newValue instanceof Object[] newArray && existingValue instanceof Object[] existingArray) {
			equal = Arrays.equals(newArray, existingArray);
		} else {
			equal = Objects.equals(existingValue, newValue);
		}
		return equal ? null : mutationCreator.apply(newValue);
	}

	/**
	 * Creates mutation that sets up a new reflected reference schema using a simple boolean
	 * flag for faceted configuration.
	 */
	public CreateReflectedReferenceSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Cardinality cardinality,
		@Nonnull String referencedEntityType,
		@Nonnull String reflectedReferenceName,
		@Nullable Boolean faceted,
		@Nonnull AttributeInheritanceBehavior attributeInheritanceBehavior,
		@Nullable String[] attributeInheritanceFilter
	) {
		this(
			name, description, deprecationNotice, cardinality, referencedEntityType, reflectedReferenceName,
			// by default reflected reference is indexed in the same scope as the entity
			null,
			// by default indexed components are inherited
			null,
			// by default reflected reference is not faceted unless explicitly set
			faceted == null ? null : faceted ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE,
			attributeInheritanceBehavior, attributeInheritanceFilter
		);
	}

	/**
	 * Creates mutation that sets up a new reflected reference schema with detailed per-scope
	 * indexed/faceted configuration.
	 */
	@SerializableCreator
	public CreateReflectedReferenceSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Cardinality cardinality,
		@Nonnull String referencedEntityType,
		@Nonnull String reflectedReferenceName,
		@Nullable ScopedReferenceIndexType[] indexedInScopes,
		@Nullable ScopedReferenceIndexedComponents[] indexedComponentsInScopes,
		@Nullable Scope[] facetedInScopes,
		@Nonnull AttributeInheritanceBehavior attributeInheritanceBehavior,
		@Nullable String[] attributeInheritanceFilter
	) {
		super(name);
		ClassifierUtils.validateClassifierFormat(ClassifierType.REFERENCE, name);
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, referencedEntityType);
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.cardinality = cardinality;
		this.referencedEntityType = referencedEntityType;
		this.reflectedReferenceName = reflectedReferenceName;
		this.indexedInScopes = indexedInScopes;
		this.indexedComponentsInScopes = indexedComponentsInScopes;
		this.facetedInScopes = facetedInScopes;
		this.attributeInheritanceBehavior = attributeInheritanceBehavior;
		this.attributeInheritanceFilter = attributeInheritanceFilter == null ?
			ArrayUtils.EMPTY_STRING_ARRAY : attributeInheritanceFilter;
	}

	/**
	 * Checks if the reference is faceted, which is determined by whether there is at least one scope
	 * in the `facetedInScopes` array that matches the default scope.
	 *
	 * @return true if the reference is faceted (contains at least one default scope), false otherwise
	 */
	public boolean isFaceted() {
		return this.facetedInScopes != null &&
			Arrays.stream(this.facetedInScopes).anyMatch(scope -> scope == Scope.DEFAULT_SCOPE);
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
			if (currentReference.get() instanceof ReflectedReferenceSchemaContract existingVersion) {
				final ReflectedReferenceSchemaContract createdVersion = Objects.requireNonNull(
					(ReflectedReferenceSchemaContract) mutate(currentEntitySchema, null)
				);
				return new MutationCombinationResult<>(
					null,
					Stream.of(
							Stream.of(
								makeMutationIfDifferent(
									createdVersion, existingVersion,
									NamedSchemaContract::getDescription,
									newValue -> new ModifyReferenceSchemaDescriptionMutation(this.name, newValue)
								),
								makeMutationIfDifferent(
									createdVersion, existingVersion,
									NamedSchemaWithDeprecationContract::getDeprecationNotice,
									newValue -> new ModifyReferenceSchemaDeprecationNoticeMutation(this.name, newValue)
								),
								makeMutationIfDifferent(
									createdVersion, existingVersion,
									ReferenceSchemaContract::getCardinality,
									newValue -> new ModifyReferenceSchemaCardinalityMutation(this.name, newValue)
								),
								makeMutationIfDifferent(
									createdVersion, existingVersion,
									ref -> ref.isIndexedInherited() ? null : Arrays.stream(Scope.values()).filter(ref::isIndexedInScope).toArray(Scope[]::new),
									newValue -> new SetReferenceSchemaIndexedMutation(this.name, newValue)
								),
								makeMutationIfDifferent(
									createdVersion, existingVersion,
									ref -> ref.isIndexedComponentsInherited()
										? null
										: ref.getIndexedComponentsInScopes(),
									newValue -> new SetReferenceSchemaIndexedMutation(
										this.name,
										createdVersion.isIndexedInherited()
											? null
											: createdVersion.getReferenceIndexTypeInScopes()
												.entrySet()
												.stream()
												.map(it -> new ScopedReferenceIndexType(it.getKey(), it.getValue()))
												.toArray(ScopedReferenceIndexType[]::new),
										createdVersion.getIndexedComponentsInScopes()
											.entrySet()
											.stream()
											.map(
												it -> new ScopedReferenceIndexedComponents(
													it.getKey(),
													it.getValue().toArray(ReferenceIndexedComponents[]::new)
												)
											)
											.toArray(ScopedReferenceIndexedComponents[]::new)
									)
								),
								makeMutationIfDifferent(
									createdVersion, existingVersion,
									ref -> ref.isFacetedInherited() ? null : Arrays.stream(Scope.values()).filter(ref::isFacetedInScope).toArray(Scope[]::new),
									newValue -> new SetReferenceSchemaFacetedMutation(this.name, newValue)
								)
							),
							existingVersion.getAttributes()
								.values()
								.stream()
								.map(attribute -> new ModifyReferenceAttributeSchemaMutation(this.name, new RemoveAttributeSchemaMutation(attribute.getName()))),
							existingVersion.getSortableAttributeCompounds()
								.values()
								.stream()
								.map(attribute -> new ModifyReferenceSortableAttributeCompoundSchemaMutation(this.name, new RemoveSortableAttributeCompoundSchemaMutation(attribute.getName())))
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
	public ReflectedReferenceSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull ConsistencyChecks consistencyChecks
	) {
		return ReflectedReferenceSchema._internalBuild(
			this.name, this.description, this.deprecationNotice,
			this.referencedEntityType, this.reflectedReferenceName,
			this.cardinality,
			this.indexedInScopes,
			this.indexedComponentsInScopes,
			this.facetedInScopes,
			Collections.emptyMap(),
			Collections.emptyMap(),
			this.attributeInheritanceBehavior,
			this.attributeInheritanceFilter
		);
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema
	) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final ReflectedReferenceSchema newReferenceSchema = Objects.requireNonNull(
			(ReflectedReferenceSchema) this.mutate(entitySchema, null)
		);
		final Optional<ReferenceSchemaContract> referencedReferenceSchema = catalogSchema
			.getEntitySchema(newReferenceSchema.getReferencedEntityType())
			.flatMap(it -> it.getReference(newReferenceSchema.getReflectedReferenceName()));
		final ReferenceSchemaContract referenceToInsert = referencedReferenceSchema
			.map(newReferenceSchema::withReferencedSchema)
			.orElse(newReferenceSchema);
		return insertNewReference(entitySchema, this.name, referenceToInsert, newReferenceSchema);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		final String indexedDescription;
		if (this.indexedInScopes == null) {
			indexedDescription = "(inherited)";
		} else if (ArrayUtils.isEmptyOrItsValuesNull(this.indexedInScopes)) {
			indexedDescription = "(not indexed)";
		} else {
			indexedDescription = "(indexed in scopes: " + Arrays.toString(this.indexedInScopes) + ")";
		}
		final String componentsDescription;
		if (this.indexedComponentsInScopes == null) {
			componentsDescription = "";
		} else {
			componentsDescription = ", indexedComponents=" + Arrays.toString(this.indexedComponentsInScopes);
		}
		final String facetedDescription;
		if (this.facetedInScopes == null) {
			facetedDescription = "(inherited)";
		} else if (ArrayUtils.isEmptyOrItsValuesNull(this.facetedInScopes)) {
			facetedDescription = "(not faceted)";
		} else {
			facetedDescription = "(faceted in scopes: " + Arrays.toString(this.facetedInScopes) + ")";
		}
		return "Create entity reflected reference schema: " +
			"name='" + this.name + '\'' +
			", description='" + this.description + '\'' +
			", deprecationNotice='" + this.deprecationNotice + '\'' +
			", cardinality=" + this.cardinality +
			", entityType='" + this.referencedEntityType + '\'' +
			", reflectedReferenceName='" + this.reflectedReferenceName + '\'' +
			", indexed=" + indexedDescription +
			componentsDescription +
			", faceted=" + facetedDescription +
			", attributesInherited=" + this.attributeInheritanceBehavior +
			", attributesExcludedFromInheritance=" +
			Arrays.toString(this.attributeInheritanceFilter);
	}

}
