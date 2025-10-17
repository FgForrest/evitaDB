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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaWithDeprecationContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.NamedSchemaMutation;
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
import java.util.stream.Collectors;
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
@EqualsAndHashCode
public class CreateReflectedReferenceSchemaMutation
	implements ReferenceSchemaMutation, CombinableLocalEntitySchemaMutation, NamedSchemaMutation {
	@Serial private static final long serialVersionUID = -3833868605223655352L;
	@Getter @Nonnull private final String name;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nullable private final Cardinality cardinality;
	@Getter @Nonnull private final String referencedEntityType;
	@Getter @Nonnull private final String reflectedReferenceName;
	@Getter @Nullable private final ScopedReferenceIndexType[] indexedInScopes;
	@Getter @Nullable private final Scope[] facetedInScopes;
	@Getter @Nonnull private final AttributeInheritanceBehavior attributeInheritanceBehavior;
	@Getter @Nonnull private final String[] attributeInheritanceFilter;

	@Nullable
	private static <T> LocalEntitySchemaMutation makeMutationIfDifferent(
		@Nonnull ReflectedReferenceSchemaContract createdVersion,
		@Nonnull ReflectedReferenceSchemaContract existingVersion,
		@Nonnull Function<ReflectedReferenceSchemaContract, T> propertyRetriever,
		@Nonnull Function<T, LocalEntitySchemaMutation> mutationCreator
	) {
		final T newValue = propertyRetriever.apply(createdVersion);
		return Objects.equals(propertyRetriever.apply(existingVersion), newValue) ?
			null : mutationCreator.apply(newValue);
	}

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
			// by default reflected reference is not faceted unless explicitly set
			faceted == null ? null : faceted ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE,
			attributeInheritanceBehavior, attributeInheritanceFilter
		);
	}

	@SerializableCreator
	public CreateReflectedReferenceSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Cardinality cardinality,
		@Nonnull String referencedEntityType,
		@Nonnull String reflectedReferenceName,
		@Nullable ScopedReferenceIndexType[] indexedInScopes,
		@Nullable Scope[] facetedInScopes,
		@Nonnull AttributeInheritanceBehavior attributeInheritanceBehavior,
		@Nullable String[] attributeInheritanceFilter
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.REFERENCE, name);
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, referencedEntityType);
		this.name = name;
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.cardinality = cardinality;
		this.referencedEntityType = referencedEntityType;
		this.reflectedReferenceName = reflectedReferenceName;
		this.indexedInScopes = indexedInScopes;
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
		// and leave only operations that reset the original settings do defaults
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
	public ReflectedReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema, @Nonnull ConsistencyChecks consistencyChecks) {
		return ReflectedReferenceSchema._internalBuild(
			this.name, this.description, this.deprecationNotice,
			this.referencedEntityType, this.reflectedReferenceName,
			this.cardinality,
			this.indexedInScopes,
			this.facetedInScopes,
			Collections.emptyMap(),
			Collections.emptyMap(),
			this.attributeInheritanceBehavior,
			this.attributeInheritanceFilter
		);
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final ReflectedReferenceSchema newReferenceSchema = Objects.requireNonNull((ReflectedReferenceSchema) this.mutate(entitySchema, null));
		final Optional<ReferenceSchemaContract> referencedReferenceSchema = catalogSchema.getEntitySchema(newReferenceSchema.getReferencedEntityType())
			.flatMap(it -> it.getReference(newReferenceSchema.getReflectedReferenceName()));
		final ReferenceSchemaContract referenceToInsert = referencedReferenceSchema
			.map(newReferenceSchema::withReferencedSchema)
			.orElse(newReferenceSchema);
		final Optional<ReferenceSchemaContract> existingReferenceSchema = entitySchema.getReference(this.name);
		if (existingReferenceSchema.isEmpty()) {
			return EntitySchema._internalBuild(
				entitySchema.version() + 1,
				entitySchema.getName(),
				entitySchema.getNameVariants(),
				entitySchema.getDescription(),
				entitySchema.getDeprecationNotice(),
				entitySchema.isWithGeneratedPrimaryKey(),
				entitySchema.isWithHierarchy(),
				entitySchema.getHierarchyIndexedInScopes(),
				entitySchema.isWithPrice(),
				entitySchema.getPriceIndexedInScopes(),
				entitySchema.getIndexedPricePlaces(),
				entitySchema.getLocales(),
				entitySchema.getCurrencies(),
				entitySchema.getAttributes(),
				entitySchema.getAssociatedData(),
				Stream.concat(
						entitySchema.getReferences().values().stream(),
						Stream.of(referenceToInsert)
					)
					.collect(
						Collectors.toMap(
							ReferenceSchemaContract::getName,
							Function.identity()
						)
					),
				entitySchema.getEvolutionMode(),
				entitySchema.getSortableAttributeCompounds()
			);
		} else if (existingReferenceSchema.get().equals(newReferenceSchema)) {
			// the mutation must have been applied previously - return the schema we don't need to alter
			return entitySchema;
		} else {
			// ups, there is conflict in associated data settings
			throw new InvalidSchemaMutationException(
				"The reference `" + this.name + "` already exists in entity `" + entitySchema.getName() + "` schema and" +
					" has different definition. To alter existing reference schema you need to use different mutations."
			);
		}
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Nonnull
	@Override
	public String containerName() {
		return this.name;
	}

	@Override
	public String toString() {
		return "Create entity reflected reference schema: " +
			"name='" + this.name + '\'' +
			", description='" + this.description + '\'' +
			", deprecationNotice='" + this.deprecationNotice + '\'' +
			", cardinality=" + this.cardinality +
			", entityType='" + this.referencedEntityType + '\'' +
			", reflectedReferenceName='" + this.reflectedReferenceName + '\'' +
			", indexed=" + (this.indexedInScopes == null ? "(inherited)" : (ArrayUtils.isEmptyOrItsValuesNull(this.indexedInScopes) ? "(indexed in scopes: " + Arrays.toString(this.indexedInScopes) + ")" : "(not indexed)")) +
			", faceted=" + (this.facetedInScopes == null ? "(inherited)" : (ArrayUtils.isEmptyOrItsValuesNull(this.facetedInScopes) ? "(faceted in scopes: " + Arrays.toString(this.facetedInScopes) + ")" : "(not faceted)")) +
			", attributesInherited=" + this.attributeInheritanceBehavior +
			", attributesExcludedFromInheritance=" + Arrays.toString(this.attributeInheritanceFilter);
	}

}
