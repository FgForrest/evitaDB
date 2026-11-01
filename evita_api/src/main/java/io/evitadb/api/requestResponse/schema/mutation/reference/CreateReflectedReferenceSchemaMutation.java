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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
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
	@Serial private static final long serialVersionUID = 6927605828004667958L;

	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nullable private final Cardinality cardinality;
	@Getter @Nonnull private final String referencedEntityType;
	@Getter @Nonnull private final String reflectedReferenceName;
	@Getter @Nullable private final ScopedReferenceIndexType[] indexedInScopes;
	@Getter @Nullable private final ScopedReferenceIndexedComponents[] indexedComponentsInScopes;
	@Getter @Nullable private final Scope[] facetedInScopes;
	@Getter @Nullable private final ScopedFacetedPartially[] facetedPartiallyInScopes;
	@Getter @Nullable private final ScopedHistogramIndexDefinition[] bucketedInScopes;
	@Getter @Nullable private final ScopedBucketedPartially[] bucketedPartiallyInScopes;
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
	 * Returns the mutation that sets a property a reflected reference may inherit from its original, when the created
	 * and the existing version declare it differently. Inheriting the property and declaring a value are different
	 * definitions even when the value equals the inherited one. A value is read only from a version that declares it,
	 * because an inherited value is not available while the reflected reference is not bound to its original, and the
	 * mutation receives null to make the property inherited.
	 *
	 * @param createdInherits  whether the created version inherits the property
	 * @param createdValue     supplies the value the created version declares
	 * @param existingInherits whether the existing version inherits the property
	 * @param existingValue    supplies the value the existing version declares
	 * @param mutationCreator  creates the mutation setting the new value, null for inherited
	 * @return the mutation, or null when both versions declare the property the same way
	 */
	@Nullable
	private static <T> LocalEntitySchemaMutation makeMutationIfDeclaredDifferently(
		boolean createdInherits,
		@Nonnull Supplier<T> createdValue,
		boolean existingInherits,
		@Nonnull Supplier<T> existingValue,
		@Nonnull Function<T, LocalEntitySchemaMutation> mutationCreator
	) {
		if (createdInherits) {
			return existingInherits ? null : mutationCreator.apply(null);
		} else {
			final T newValue = createdValue.get();
			return !existingInherits && Objects.equals(newValue, existingValue.get())
				? null
				: mutationCreator.apply(newValue);
		}
	}

	/**
	 * Returns the attributes the reflected reference declares itself.
	 *
	 * @param reference the reflected reference
	 * @return the declared attributes by name
	 */
	@Nonnull
	private static Map<String, AttributeSchemaContract> declaredAttributesOf(@Nonnull ReflectedReferenceSchemaContract reference) {
		return reference instanceof ReflectedReferenceSchema reflectedReference
			? reflectedReference.getDeclaredAttributes()
			: reference.getAttributes();
	}

	/**
	 * Returns the sortable attribute compounds the reflected reference declares itself.
	 *
	 * @param reference the reflected reference
	 * @return the declared compounds by name
	 */
	@Nonnull
	private static Map<String, SortableAttributeCompoundSchemaContract> declaredSortableAttributeCompoundsOf(
		@Nonnull ReflectedReferenceSchemaContract reference
	) {
		return reference instanceof ReflectedReferenceSchema reflectedReference
			? reflectedReference.getDeclaredSortableAttributeCompounds()
			: reference.getSortableAttributeCompounds();
	}

	/**
	 * Creates a single {@link SetReferenceSchemaFacetedMutation} carrying both faceted scopes and
	 * facetedPartially expressions if either property differs between created and existing versions.
	 * Emitting a single mutation avoids the Set+Set combining problem where a second
	 * SetReferenceSchemaFacetedMutation would replace the first, losing the scopes data.
	 */
	@Nullable
	private LocalEntitySchemaMutation createCombinedFacetedMutation(
		@Nonnull ReflectedReferenceSchemaContract createdVersion,
		@Nonnull ReflectedReferenceSchemaContract existingVersion
	) {
		final Scope[] createdScopes = createdVersion.isFacetedInherited()
			? null
			: Arrays.stream(Scope.values())
				.filter(createdVersion::isFacetedInScope)
				.toArray(Scope[]::new);
		final Scope[] existingScopes = existingVersion.isFacetedInherited()
			? null
			: Arrays.stream(Scope.values())
				.filter(existingVersion::isFacetedInScope)
				.toArray(Scope[]::new);
		final boolean scopesEqual = Arrays.equals(createdScopes, existingScopes);
		final boolean partiallyEqual = createdVersion.getFacetedPartiallyInScopes()
			.equals(existingVersion.getFacetedPartiallyInScopes());
		if (scopesEqual && partiallyEqual) {
			return null;
		}
		return new SetReferenceSchemaFacetedMutation(
			this.name,
			createdScopes,
			createdVersion.getFacetedPartiallyInScopes().entrySet().stream()
				.map(e -> new ScopedFacetedPartially(e.getKey(), e.getValue()))
				.toArray(ScopedFacetedPartially[]::new)
		);
	}

	/**
	 * Returns the mutations that change the indexing of the existing reflected reference into the indexing of the
	 * re-created one, in the order they must apply; a null element stands for no change.
	 *
	 * No single mutation makes both the index types and declared components inherited, so a re-created reference that
	 * inherits both, in place of one that declares its components, takes two: the first makes the components inherited
	 * beside index types it declares none of, the second makes the index types inherited and leaves the components.
	 * Otherwise null components make them inherited beside declared index types, as the created reference has them;
	 * passing the components the created reference reports would declare them instead.
	 *
	 * @param createdVersion  the reference as the creation defines it
	 * @param existingVersion the reference as the entity holds it before the removal
	 * @return the mutations, with null elements for the properties that stay as they are
	 */
	@Nonnull
	private Stream<LocalEntitySchemaMutation> indexingMutationsOf(
		@Nonnull ReflectedReferenceSchemaContract createdVersion,
		@Nonnull ReflectedReferenceSchemaContract existingVersion
	) {
		if (createdVersion.isIndexedInherited() && createdVersion.isIndexedComponentsInherited()
			&& !existingVersion.isIndexedComponentsInherited()) {
			return Stream.of(
				new SetReferenceSchemaIndexedMutation(this.name, ScopedReferenceIndexType.EMPTY, null),
				new SetReferenceSchemaIndexedMutation(this.name, (Scope[]) null)
			);
		} else {
			return Stream.of(
				makeMutationIfDifferent(
					createdVersion, existingVersion,
					ref -> ref.isIndexedInherited()
						? null
						: Arrays.stream(Scope.values()).filter(ref::isIndexedInScope).toArray(Scope[]::new),
					newValue -> new SetReferenceSchemaIndexedMutation(this.name, newValue)
				),
				makeMutationIfDifferent(
					createdVersion, existingVersion,
					ref -> ref.isIndexedComponentsInherited() ? null : ref.getIndexedComponentsInScopes(),
					newValue -> new SetReferenceSchemaIndexedMutation(
						this.name,
						createdVersion.isIndexedInherited()
							? null
							: createdVersion.getReferenceIndexTypeInScopes()
								.entrySet()
								.stream()
								.map(it -> new ScopedReferenceIndexType(it.getKey(), it.getValue()))
								.toArray(ScopedReferenceIndexType[]::new),
						createdVersion.isIndexedComponentsInherited()
							? null
							: createdVersion.getIndexedComponentsInScopes()
								.entrySet()
								.stream()
								.map(
									it -> new ScopedReferenceIndexedComponents(
										it.getKey(), it.getValue().toArray(ReferenceIndexedComponents[]::new)
									)
								)
								.toArray(ScopedReferenceIndexedComponents[]::new)
					)
				)
			);
		}
	}

	/**
	 * Creates a single {@link SetReferenceSchemaBucketedMutation} carrying both bucketed histogram
	 * definitions and bucketedPartially expressions if either property differs between created and
	 * existing versions. Emitting a single mutation avoids the Set+Set combining problem where a second
	 * SetReferenceSchemaBucketedMutation would replace the first, losing the scopes data.
	 */
	@Nullable
	private LocalEntitySchemaMutation createCombinedBucketedMutation(
		@Nonnull ReflectedReferenceSchemaContract createdVersion,
		@Nonnull ReflectedReferenceSchemaContract existingVersion
	) {
		final ScopedHistogramIndexDefinition[] createdBucketed =
			createdVersion.getAllHistogramIndexDefinitions().entrySet().stream()
				.flatMap(scopeEntry -> scopeEntry.getValue().values().stream()
					.map(def -> new ScopedHistogramIndexDefinition(
						scopeEntry.getKey(), def.nameOfTheIndex(), def.valueExpression(), def.assignedWhen()
					))
				)
				.toArray(ScopedHistogramIndexDefinition[]::new);
		final ScopedHistogramIndexDefinition[] existingBucketed =
			existingVersion.getAllHistogramIndexDefinitions().entrySet().stream()
				.flatMap(scopeEntry -> scopeEntry.getValue().values().stream()
					.map(def -> new ScopedHistogramIndexDefinition(
						scopeEntry.getKey(), def.nameOfTheIndex(), def.valueExpression(), def.assignedWhen()
					))
				)
				.toArray(ScopedHistogramIndexDefinition[]::new);
		final boolean bucketedEqual = Arrays.equals(createdBucketed, existingBucketed);
		final boolean partiallyEqual = createdVersion.getBucketedPartiallyInScopes()
			.equals(existingVersion.getBucketedPartiallyInScopes());
		if (bucketedEqual && partiallyEqual) {
			return null;
		}
		return new SetReferenceSchemaBucketedMutation(
			this.name,
			createdBucketed,
			createdVersion.getBucketedPartiallyInScopes().entrySet().stream()
				.map(e -> new ScopedBucketedPartially(e.getKey(), e.getValue()))
				.toArray(ScopedBucketedPartially[]::new)
		);
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
			// by default facetedPartially is inherited
			null,
			// by default bucketed is empty
			ScopedHistogramIndexDefinition.EMPTY,
			// by default bucketedPartially is empty
			ScopedBucketedPartially.EMPTY,
			attributeInheritanceBehavior, attributeInheritanceFilter
		);
	}

	/**
	 * Creates mutation that sets up a new reflected reference schema with detailed per-scope
	 * indexed/faceted/bucketed configuration including per-scope facetedPartially and
	 * bucketedPartially expressions.
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
		@Nullable ScopedFacetedPartially[] facetedPartiallyInScopes,
		@Nullable ScopedHistogramIndexDefinition[] bucketedInScopes,
		@Nullable ScopedBucketedPartially[] bucketedPartiallyInScopes,
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
		this.facetedPartiallyInScopes = facetedPartiallyInScopes;
		this.bucketedInScopes = bucketedInScopes;
		this.bucketedPartiallyInScopes = bucketedPartiallyInScopes;
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
			if (
				currentReference.get() instanceof ReflectedReferenceSchemaContract existingVersion &&
					// no mutation can redirect a reflected reference to another original reference, so the removal
					// and the creation both stay when the re-created reference reflects a different one
					this.referencedEntityType.equals(existingVersion.getReferencedEntityType()) &&
					this.reflectedReferenceName.equals(existingVersion.getReflectedReferenceName())
			) {
				final ReflectedReferenceSchemaContract createdVersion = Objects.requireNonNull(
					(ReflectedReferenceSchemaContract) mutate(currentEntitySchema, null)
				);
				return new MutationCombinationResult<>(
					null,
					Stream.of(
							Stream.of(
								makeMutationIfDeclaredDifferently(
									createdVersion.isDescriptionInherited(), createdVersion::getDescription,
									existingVersion.isDescriptionInherited(), existingVersion::getDescription,
									newValue -> new ModifyReferenceSchemaDescriptionMutation(this.name, newValue)
								),
								makeMutationIfDeclaredDifferently(
									createdVersion.isDeprecatedInherited(), createdVersion::getDeprecationNotice,
									existingVersion.isDeprecatedInherited(), existingVersion::getDeprecationNotice,
									newValue -> new ModifyReferenceSchemaDeprecationNoticeMutation(this.name, newValue)
								),
								makeMutationIfDeclaredDifferently(
									createdVersion.isCardinalityInherited(), createdVersion::getCardinality,
									existingVersion.isCardinalityInherited(), existingVersion::getCardinality,
									newValue -> new ModifyReferenceSchemaCardinalityMutation(this.name, newValue)
								),
								createdVersion.getAttributesInheritanceBehavior() == existingVersion.getAttributesInheritanceBehavior() &&
									Arrays.equals(createdVersion.getAttributeInheritanceFilter(), existingVersion.getAttributeInheritanceFilter())
									? null
									: new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
										this.name,
										createdVersion.getAttributesInheritanceBehavior(),
										createdVersion.getAttributeInheritanceFilter()
									)
							),
							indexingMutationsOf(createdVersion, existingVersion),
							Stream.of(
								// emit a single mutation carrying both facetedInScopes and facetedPartially
								createCombinedFacetedMutation(createdVersion, existingVersion),
								// emit a single mutation carrying both bucketedInScopes and bucketedPartially
								createCombinedBucketedMutation(createdVersion, existingVersion)
							),
							// only the declared part: an inherited attribute cannot be removed, and the inherited ones are
							// not available while the reflected reference is not bound to its original
							declaredAttributesOf(existingVersion)
								.values()
								.stream()
								.map(attribute -> new ModifyReferenceAttributeSchemaMutation(this.name, new RemoveAttributeSchemaMutation(attribute.getName()))),
							declaredSortableAttributeCompoundsOf(existingVersion)
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
			this.facetedPartiallyInScopes,
			this.bucketedInScopes,
			this.bucketedPartiallyInScopes,
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
		// creating the reference is a schema change, so a scope it inherits from the reference it reflects and its
		// explicit components leave uncovered gets the default component rather than none at all
		final ReferenceSchemaContract referenceToInsert = referencedReferenceSchema
			.map(newReferenceSchema::withReferencedSchemaAfterSchemaChange)
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
		final String facetedPartiallyDescription;
		if (this.facetedPartiallyInScopes == null) {
			facetedPartiallyDescription = "(inherited)";
		} else if (this.facetedPartiallyInScopes.length == 0) {
			facetedPartiallyDescription = "(none)";
		} else {
			facetedPartiallyDescription = "(in scopes: " + Arrays.toString(this.facetedPartiallyInScopes) + ")";
		}
		final String bucketedDescription;
		if (this.bucketedInScopes == null || this.bucketedInScopes.length == 0) {
			bucketedDescription = "(not bucketed)";
		} else {
			bucketedDescription = "(bucketed in scopes: " + Arrays.toString(this.bucketedInScopes) + ")";
		}
		final String bucketedPartiallyDescription;
		if (this.bucketedPartiallyInScopes == null || this.bucketedPartiallyInScopes.length == 0) {
			bucketedPartiallyDescription = "(none)";
		} else {
			bucketedPartiallyDescription = "(in scopes: " + Arrays.toString(this.bucketedPartiallyInScopes) + ")";
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
			", facetedPartially=" + facetedPartiallyDescription +
			", bucketed=" + bucketedDescription +
			", bucketedPartially=" + bucketedPartiallyDescription +
			", attributesInherited=" + this.attributeInheritanceBehavior +
			", attributesExcludedFromInheritance=" +
			Arrays.toString(this.attributeInheritanceFilter);
	}

}
