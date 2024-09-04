/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.dataType.ClassifierType;
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
public class CreateReflectedReferenceSchemaMutation implements ReferenceSchemaMutation, CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = 4075653645885678621L;
	@Getter @Nonnull private final String name;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nullable private final Cardinality cardinality;
	@Getter @Nonnull private final String referencedEntityType;
	@Getter @Nonnull private final String reflectedReferenceName;
	@Getter @Nullable private final Boolean faceted;
	@Getter @Nonnull private final AttributeInheritanceBehavior attributesInheritanceBehavior;
	@Getter @Nullable private final String[] attributeInheritanceFilter;

	public CreateReflectedReferenceSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Cardinality cardinality,
		@Nonnull String referencedEntityType,
		@Nonnull String reflectedReferenceName,
		@Nullable Boolean faceted,
		@Nonnull AttributeInheritanceBehavior attributesInheritanceBehavior,
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
		this.faceted = faceted;
		this.attributesInheritanceBehavior = attributesInheritanceBehavior;
		this.attributeInheritanceFilter = attributeInheritanceFilter;
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
		final Optional<ReferenceSchemaContract> currentReference = currentEntitySchema.getReference(name);
		if (
			existingMutation instanceof RemoveReferenceSchemaMutation removeReferenceMutation &&
				Objects.equals(removeReferenceMutation.getName(), name) && currentReference.isPresent()
		) {
			// we can convert mutation to updates only if the reference type matches
			if (currentReference.get() instanceof ReflectedReferenceSchemaContract existingVersion) {
				final ReflectedReferenceSchemaContract createdVersion = (ReflectedReferenceSchemaContract) mutate(currentEntitySchema, null);
				return new MutationCombinationResult<>(
					null,
					Stream.of(
							Stream.of(
								makeMutationIfDifferent(
									createdVersion, existingVersion,
									NamedSchemaContract::getDescription,
									newValue -> new ModifyReferenceSchemaDescriptionMutation(name, newValue)
								),
								makeMutationIfDifferent(
									createdVersion, existingVersion,
									NamedSchemaWithDeprecationContract::getDeprecationNotice,
									newValue -> new ModifyReferenceSchemaDeprecationNoticeMutation(name, newValue)
								),
								makeMutationIfDifferent(
									createdVersion, existingVersion,
									ReferenceSchemaContract::getCardinality,
									newValue -> new ModifyReferenceSchemaCardinalityMutation(name, newValue)
								),
								makeMutationIfDifferent(
									createdVersion, existingVersion,
									ReferenceSchemaContract::isIndexed,
									newValue -> new SetReferenceSchemaIndexedMutation(name, newValue)
								),
								makeMutationIfDifferent(
									createdVersion, existingVersion,
									ReferenceSchemaContract::isFaceted,
									newValue -> new SetReferenceSchemaFacetedMutation(name, newValue)
								)
							),
							existingVersion.getAttributes()
								.values()
								.stream()
								.map(attribute -> new ModifyReferenceAttributeSchemaMutation(name, new RemoveAttributeSchemaMutation(attribute.getName()))),
							existingVersion.getSortableAttributeCompounds()
								.values()
								.stream()
								.map(attribute -> new ModifyReferenceSortableAttributeCompoundSchemaMutation(name, new RemoveSortableAttributeCompoundSchemaMutation(attribute.getName())))
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
			name, description, deprecationNotice,
			referencedEntityType, reflectedReferenceName,
			cardinality,
			faceted,
			Collections.emptyMap(),
			Collections.emptyMap(),
			attributesInheritanceBehavior,
			attributeInheritanceFilter
		);
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final ReflectedReferenceSchema newReferenceSchema = (ReflectedReferenceSchema) this.mutate(entitySchema, null);
		final Optional<ReferenceSchemaContract> referencedReferenceSchema = catalogSchema.getEntitySchema(newReferenceSchema.getReferencedEntityType())
			.flatMap(it -> it.getReference(newReferenceSchema.getReflectedReferenceName()));
		final ReferenceSchemaContract referenceToInsert = referencedReferenceSchema
			.map(newReferenceSchema::withReferencedSchema)
			.orElse(newReferenceSchema);
		final Optional<ReferenceSchemaContract> existingReferenceSchema = entitySchema.getReference(name);
		if (existingReferenceSchema.isEmpty()) {
			return EntitySchema._internalBuild(
				entitySchema.version() + 1,
				entitySchema.getName(),
				entitySchema.getNameVariants(),
				entitySchema.getDescription(),
				entitySchema.getDeprecationNotice(),
				entitySchema.isWithGeneratedPrimaryKey(),
				entitySchema.isWithHierarchy(),
				entitySchema.isWithPrice(),
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
				"The reference `" + name + "` already exists in entity `" + entitySchema.getName() + "` schema and" +
					" has different definition. To alter existing reference schema you need to use different mutations."
			);
		}
	}

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

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Create entity reflected reference schema: " +
			"name='" + name + '\'' +
			", description='" + description + '\'' +
			", deprecationNotice='" + deprecationNotice + '\'' +
			", cardinality=" + cardinality +
			", entityType='" + referencedEntityType + '\'' +
			", reflectedReferenceName='" + reflectedReferenceName + '\'' +
			", faceted=" + faceted +
			", attributesInherited=" + attributesInheritanceBehavior +
			", attributesExcludedFromInheritance=" + Arrays.toString(attributeInheritanceFilter);
	}
}
