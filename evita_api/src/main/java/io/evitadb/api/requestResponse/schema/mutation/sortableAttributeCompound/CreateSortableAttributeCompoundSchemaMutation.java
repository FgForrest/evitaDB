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

package io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaWithDeprecationContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mutation is responsible for setting up a new {@link SortableAttributeCompoundSchemaContract} in
 * the {@link EntitySchemaContract} or {@link ReferenceSchemaContract}.
 * Mutation can be used for altering also the existing {@link SortableAttributeCompoundSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with
 * {@link RemoveSortableAttributeCompoundSchemaMutation} mutation (if such is found in mutation pipeline).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class CreateSortableAttributeCompoundSchemaMutation
	implements CombinableLocalEntitySchemaMutation, ReferenceSortableAttributeCompoundSchemaMutation {

	@Serial private static final long serialVersionUID = 5667962046673510848L;
	@Getter @Nonnull private final String name;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nonnull private final AttributeElement[] attributeElements;

	@Nullable
	private static <T> LocalEntitySchemaMutation makeMutationIfDifferent(
		@Nonnull SortableAttributeCompoundSchemaContract createdVersion,
		@Nonnull SortableAttributeCompoundSchemaContract existingVersion,
		@Nonnull Function<SortableAttributeCompoundSchemaContract, T> propertyRetriever,
		@Nonnull Function<T, LocalEntitySchemaMutation> mutationCreator
	) {
		final T newValue = propertyRetriever.apply(createdVersion);
		return Objects.equals(propertyRetriever.apply(existingVersion), newValue) ?
			null : mutationCreator.apply(newValue);
	}

	public CreateSortableAttributeCompoundSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull AttributeElement... attributeElements
	) {

		this.name = name;
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.attributeElements = attributeElements;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		// when the attribute schema was removed before and added again, we may remove both operations
		// and leave only operations that reset the original settings do defaults
		if (existingMutation instanceof RemoveSortableAttributeCompoundSchemaMutation removeCompound &&
			Objects.equals(removeCompound.getName(), this.name) &&
			Arrays.equals(
				currentEntitySchema.getSortableAttributeCompound(removeCompound.getName())
					.map(SortableAttributeCompoundSchemaContract::getAttributeElements)
					.map(it -> it.toArray(AttributeElement[]::new))
					.orElseGet(() -> new AttributeElement[0]),
				this.attributeElements
			)
		) {
			final SortableAttributeCompoundSchemaContract createdVersion = mutate(currentEntitySchema, null, (SortableAttributeCompoundSchemaContract) null);
			final SortableAttributeCompoundSchemaContract existingVersion = currentEntitySchema.getSortableAttributeCompound(this.name).orElseThrow();
			return new MutationCombinationResult<>(
				null,
				Stream.of(
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							NamedSchemaContract::getDescription,
							newValue -> new ModifySortableAttributeCompoundSchemaDescriptionMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							NamedSchemaWithDeprecationContract::getDeprecationNotice,
							newValue -> new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(this.name, newValue)
						)
					)
					.filter(Objects::nonNull)
					.toArray(LocalEntitySchemaMutation[]::new)
			);
		} else {
			return null;
		}
	}

	@Nullable
	@Override
	public SortableAttributeCompoundSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable SortableAttributeCompoundSchemaContract sortableAttributeCompoundSchema
	) {
		return SortableAttributeCompoundSchema._internalBuild(
			this.name, this.description, this.deprecationNotice,
			Arrays.asList(this.attributeElements)
		);
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final SortableAttributeCompoundSchemaContract newCompoundSchema = mutate(entitySchema, null, (SortableAttributeCompoundSchemaContract) null);
		final SortableAttributeCompoundSchemaContract existingCompoundSchema = entitySchema.getSortableAttributeCompound(this.name).orElse(null);
		if (existingCompoundSchema == null) {
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
				entitySchema.getReferences(),
				entitySchema.getEvolutionMode(),
				Stream.concat(
					entitySchema.getSortableAttributeCompounds().values().stream(),
					Stream.of(newCompoundSchema)
				).collect(
					Collectors.toMap(
						SortableAttributeCompoundSchemaContract::getName,
						Function.identity()
					)
				)
			);
		} else if (existingCompoundSchema.equals(newCompoundSchema)) {
			// the mutation must have been applied previously - return the schema we don't need to alter
			return entitySchema;
		} else {
			// ups, there is conflict in attribute settings
			throw new InvalidSchemaMutationException(
				"The sortable attribute compound `" + this.name + "` already exists in entity `" + entitySchema.getName() +
					"` schema and it has different definition. To alter existing sortable attribute compound schema you" +
					" need to use different mutations."
			);
		}
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema, @Nonnull ConsistencyChecks consistencyChecks) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		final SortableAttributeCompoundSchemaContract newCompoundSchema = mutate(entitySchema, referenceSchema, (SortableAttributeCompoundSchemaContract) null);
		final Optional<SortableAttributeCompoundSchemaContract> existingCompoundSchema = getReferenceSortableAttributeCompoundSchema(referenceSchema, this.name);
		if (existingCompoundSchema.isEmpty()) {
			if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
				return reflectedReferenceSchema
					.withDeclaredSortableAttributeCompounds(
						Stream.concat(
								reflectedReferenceSchema.getDeclaredSortableAttributeCompounds().values().stream(),
								Stream.of(newCompoundSchema)
							)
							.collect(
								Collectors.toMap(
									SortableAttributeCompoundSchemaContract::getName,
									Function.identity()
								)
							)
					);
			} else if (referenceSchema instanceof ReferenceSchema theReferenceSchema) {
				return ReferenceSchema._internalBuild(
					theReferenceSchema.getName(),
					theReferenceSchema.getNameVariants(),
					theReferenceSchema.getDescription(),
					theReferenceSchema.getDeprecationNotice(),
					theReferenceSchema.getCardinality(),
					theReferenceSchema.getReferencedEntityType(),
					theReferenceSchema.isReferencedEntityTypeManaged() ?
						Collections.emptyMap() : theReferenceSchema.getEntityTypeNameVariants(s -> null),
					theReferenceSchema.isReferencedEntityTypeManaged(),
					theReferenceSchema.getReferencedGroupType(),
					theReferenceSchema.isReferencedGroupTypeManaged() ?
						Collections.emptyMap() : theReferenceSchema.getGroupTypeNameVariants(s -> null),
					theReferenceSchema.isReferencedGroupTypeManaged(),
					theReferenceSchema.getIndexedInScopes(),
					theReferenceSchema.getFacetedInScopes(),
					theReferenceSchema.getAttributes(),
					Stream.concat(
						theReferenceSchema.getSortableAttributeCompounds().values().stream(),
						Stream.of(newCompoundSchema)
					).collect(
						Collectors.toMap(
							SortableAttributeCompoundSchemaContract::getName,
							Function.identity()
						)
					)
				);
			} else {
				throw new InvalidSchemaMutationException(
					"Reference schema `" + referenceSchema.getName() + "` is not a valid reference schema!"
				);
			}
		} else if (existingCompoundSchema.get().equals(newCompoundSchema)) {
			// the mutation must have been applied previously - return the schema we don't need to alter
			return referenceSchema;
		} else {
			// ups, there is conflict in attribute settings
			throw new InvalidSchemaMutationException(
				"The sortable attribute compound `" + this.name + "` already exists in entity `" + entitySchema.getName() + "`" +
					" reference `" + referenceSchema.getName() + "` schema and" +
					" it has different definition. To alter existing sortable attribute compound schema you need to use" +
					" different mutations."
			);
		}
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Create sortable attribute compound schema: " +
			"name='" + this.name + '\'' +
			", description='" + this.description + '\'' +
			", deprecationNotice='" + this.deprecationNotice + '\'' +
			", attributeElements=" + Arrays.stream(this.attributeElements).map(AttributeElement::toString).collect(Collectors.joining(", "));
	}

}
