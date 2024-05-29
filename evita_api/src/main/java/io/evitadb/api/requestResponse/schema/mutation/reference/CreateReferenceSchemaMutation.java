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
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaWithDeprecationContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mutation is responsible for setting up a new {@link ReferenceSchemaContract} in the {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link ReferenceSchemaContract} alone.
 * Mutation implements {@link CombinableEntitySchemaMutation} allowing to resolve conflicts with
 * {@link RemoveReferenceSchemaMutation} mutation (if such is found in mutation pipeline).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class CreateReferenceSchemaMutation implements ReferenceSchemaMutation, CombinableEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -1736213837309810284L;
	@Getter @Nonnull private final String name;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nonnull private final Cardinality cardinality;
	@Getter @Nonnull private final String referencedEntityType;
	@Getter private final boolean referencedEntityTypeManaged;
	@Getter @Nullable private final String referencedGroupType;
	@Getter private final boolean referencedGroupTypeManaged;
	@Getter private final boolean indexed;
	@Getter private final boolean faceted;

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
		ClassifierUtils.validateClassifierFormat(ClassifierType.REFERENCE, name);
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, referencedEntityType);
		this.name = name;
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.cardinality = cardinality == null ? Cardinality.ZERO_OR_MORE : cardinality;
		this.referencedEntityType = referencedEntityType;
		this.referencedEntityTypeManaged = referencedEntityTypeManaged;
		this.referencedGroupType = referencedGroupType;
		this.referencedGroupTypeManaged = referencedGroupTypeManaged;
		this.indexed = indexed;
		this.faceted = faceted;
	}

	@Nullable
	@Override
	public MutationCombinationResult<EntitySchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull EntitySchemaContract currentEntitySchema, @Nonnull EntitySchemaMutation existingMutation) {
		// when the reference schema was removed before and added again, we may remove both operations
		// and leave only operations that reset the original settings do defaults
		if (existingMutation instanceof RemoveReferenceSchemaMutation removeReferenceMutation && Objects.equals(removeReferenceMutation.getName(), name)) {
			final ReferenceSchemaContract createdVersion = mutate(currentEntitySchema, null);
			final ReferenceSchemaContract existingVersion = currentEntitySchema.getReference(name)
				.orElseThrow(() -> new GenericEvitaInternalError("Sanity check!"));

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
								ReferenceSchemaContract::getReferencedEntityType,
								newValue -> new ModifyReferenceSchemaRelatedEntityMutation(name, newValue, referencedEntityTypeManaged)
							),
							makeMutationIfDifferent(
								createdVersion, existingVersion,
								ReferenceSchemaContract::getReferencedGroupType,
								newValue -> new ModifyReferenceSchemaRelatedEntityGroupMutation(name, newValue, referencedGroupTypeManaged)
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
					.toArray(EntitySchemaMutation[]::new)
			);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema) {
		return ReferenceSchema._internalBuild(
			name, description, deprecationNotice,
			referencedEntityType, referencedEntityTypeManaged,
			cardinality,
			referencedGroupType, referencedGroupTypeManaged,
			indexed, faceted,
			Collections.emptyMap(),
			Collections.emptyMap()
		);
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final ReferenceSchemaContract newReferenceSchema = this.mutate(entitySchema, null);
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
						Stream.of(newReferenceSchema)
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
	private static <T> EntitySchemaMutation makeMutationIfDifferent(
		@Nonnull ReferenceSchemaContract createdVersion,
		@Nonnull ReferenceSchemaContract existingVersion,
		@Nonnull Function<ReferenceSchemaContract, T> propertyRetriever,
		@Nonnull Function<T, EntitySchemaMutation> mutationCreator
	) {
		final T newValue = propertyRetriever.apply(createdVersion);
		return Objects.equals(propertyRetriever.apply(existingVersion), newValue) ?
			null : mutationCreator.apply(newValue);
	}

	@Override
	public String toString() {
		return "Create entity reference schema: " +
			"name='" + name + '\'' +
			", description='" + description + '\'' +
			", deprecationNotice='" + deprecationNotice + '\'' +
			", cardinality=" + cardinality +
			", entityType='" + referencedEntityType + '\'' +
			", referencedEntityTypeManaged=" + referencedEntityTypeManaged +
			", groupType='" + referencedGroupType + '\'' +
			", referencedGroupTypeManaged=" + referencedGroupTypeManaged +
			", indexed=" + indexed +
			", faceted=" + faceted;
	}
}
