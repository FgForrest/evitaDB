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

package io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaWithDeprecationContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CreateMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.NamedSchemaMutation;
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
	implements CombinableLocalEntitySchemaMutation, ReferenceSortableAttributeCompoundSchemaMutation,
	CreateMutation, NamedSchemaMutation {

	@Serial private static final long serialVersionUID = 4126462217562106850L;
	@Getter @Nonnull private final String name;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nonnull private final Scope[] indexedInScopes;
	@Getter @Nonnull private final AttributeElement[] attributeElements;

	public CreateSortableAttributeCompoundSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Scope[] indexedInScopes,
		@Nonnull AttributeElement... attributeElements
	) {

		this.name = name;
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.indexedInScopes = indexedInScopes == null ? Scope.NO_SCOPE : indexedInScopes;
		this.attributeElements = attributeElements;
	}

	/**
	 * Checks if the current instance has indexed scopes.
	 *
	 * @return true if the indexedInScopes array is neither empty nor contains null values,
	 * otherwise returns false.
	 */
	public boolean isIndexed() {
		return !ArrayUtils.isEmptyOrItsValuesNull(this.indexedInScopes);
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
			final SortableAttributeCompoundSchemaContract createdVersion = Objects.requireNonNull(
				mutate(currentEntitySchema, null, (SortableAttributeCompoundSchemaContract) null)
			);
			final SortableAttributeCompoundSchemaContract existingVersion = currentEntitySchema.getSortableAttributeCompound(this.name).orElseThrow();
			return new MutationCombinationResult<>(
				null,
				Stream.of(
						makeMutationIfDifferent(
							SortableAttributeCompoundSchemaContract.class, createdVersion, existingVersion,
							NamedSchemaContract::getDescription,
							newValue -> new ModifySortableAttributeCompoundSchemaDescriptionMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							SortableAttributeCompoundSchemaContract.class, createdVersion, existingVersion,
							NamedSchemaWithDeprecationContract::getDeprecationNotice,
							newValue -> new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							SortableAttributeCompoundSchemaContract.class, createdVersion, existingVersion,
							sacs -> sacs.getIndexedInScopes().toArray(Scope[]::new),
							newValue -> new SetSortableAttributeCompoundSchemaIndexedMutation(this.name, newValue)
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
	public <T extends SortableAttributeCompoundSchemaContract> T mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable T existingSchema
	) {
		if (referenceSchema == null) {
			//noinspection unchecked
			return (T) EntitySortableAttributeCompoundSchema._internalBuild(
				this.name, this.description, this.deprecationNotice, this.indexedInScopes,
				Arrays.asList(this.attributeElements)
			);
		} else {
			//noinspection unchecked
			return (T) SortableAttributeCompoundSchema._internalBuild(
				this.name, this.description, this.deprecationNotice, this.indexedInScopes,
				Arrays.asList(this.attributeElements)
			);
		}
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final EntitySortableAttributeCompoundSchemaContract newCompoundSchema = mutate(entitySchema, null, (EntitySortableAttributeCompoundSchemaContract) null);
		final EntitySortableAttributeCompoundSchemaContract existingCompoundSchema = entitySchema.getSortableAttributeCompound(this.name).orElse(null);
		if (existingCompoundSchema == null) {
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
			} else {
				return ReferenceSchema._internalBuild(
					referenceSchema.getName(),
					referenceSchema.getNameVariants(),
					referenceSchema.getDescription(),
					referenceSchema.getDeprecationNotice(),
					referenceSchema.getCardinality(),
					referenceSchema.getReferencedEntityType(),
					referenceSchema.isReferencedEntityTypeManaged() ?
						Collections.emptyMap() : referenceSchema.getEntityTypeNameVariants(s -> null),
					referenceSchema.isReferencedEntityTypeManaged(),
					referenceSchema.getReferencedGroupType(),
					referenceSchema.isReferencedGroupTypeManaged() ?
						Collections.emptyMap() : referenceSchema.getGroupTypeNameVariants(s -> null),
					referenceSchema.isReferencedGroupTypeManaged(),
					referenceSchema.getReferenceIndexTypeInScopes(),
					referenceSchema.getFacetedInScopes(),
					referenceSchema.getAttributes(),
					Stream.concat(
						referenceSchema.getSortableAttributeCompounds().values().stream(),
						Stream.of(newCompoundSchema)
					).collect(
						Collectors.toMap(
							SortableAttributeCompoundSchemaContract::getName,
							Function.identity()
						)
					)
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

	@Nonnull
	@Override
	public String containerName() {
		return this.name;
	}

	@Override
	public String toString() {
		return "Create sortable attribute compound schema: " +
			"name='" + this.name + '\'' +
			", description='" + this.description + '\'' +
			", deprecationNotice='" + this.deprecationNotice + '\'' +
			", indexed=" + (isIndexed() ? "(in scopes: " + Arrays.toString(this.indexedInScopes) + ")" : "no") +
			", attributeElements=" + Arrays.stream(this.attributeElements).map(AttributeElement::toString).collect(Collectors.joining(", "));
	}

}
