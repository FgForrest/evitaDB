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
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mutation is responsible for setting up a new {@link SortableAttributeCompoundSchemaContract} in
 * the {@link EntitySchemaContract} or {@link ReferenceSchemaContract}.
 * Mutation can be used for altering also the existing {@link SortableAttributeCompoundSchemaContract} alone.
 * Mutation implements {@link CombinableEntitySchemaMutation} allowing to resolve conflicts with
 * {@link RemoveSortableAttributeCompoundSchemaMutation} mutation (if such is found in mutation pipeline).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class CreateSortableAttributeCompoundSchemaMutation
	implements CombinableEntitySchemaMutation, ReferenceSortableAttributeCompoundSchemaMutation {

	@Serial private static final long serialVersionUID = 5667962046673510848L;
	@Getter @Nonnull private final String name;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nonnull private final AttributeElement[] attributeElements;

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
	public MutationCombinationResult<EntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull EntitySchemaMutation existingMutation
	) {
		// when the attribute schema was removed before and added again, we may remove both operations
		// and leave only operations that reset the original settings do defaults
		if (existingMutation instanceof RemoveSortableAttributeCompoundSchemaMutation removeCompound &&
			Objects.equals(removeCompound.getName(), name) &&
			Arrays.equals(
				currentEntitySchema.getSortableAttributeCompound(removeCompound.getName())
					.map(SortableAttributeCompoundSchemaContract::getAttributeElements)
					.map(it -> it.toArray(AttributeElement[]::new))
					.orElseGet(() -> new AttributeElement[0]),
				attributeElements
			)
		) {
			final SortableAttributeCompoundSchemaContract createdVersion = mutate(currentEntitySchema, null, null);
			final SortableAttributeCompoundSchemaContract existingVersion = currentEntitySchema.getSortableAttributeCompound(name).orElseThrow();
			return new MutationCombinationResult<>(
				null,
				Stream.of(
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							NamedSchemaContract::getDescription,
							newValue -> new ModifySortableAttributeCompoundSchemaDescriptionMutation(name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							NamedSchemaWithDeprecationContract::getDeprecationNotice,
							newValue -> new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(name, newValue)
						)
					)
					.filter(Objects::nonNull)
					.toArray(EntitySchemaMutation[]::new)
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
			name, description, deprecationNotice,
			Arrays.asList(attributeElements)
		);
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final SortableAttributeCompoundSchemaContract newCompoundSchema = mutate(entitySchema, null, null);
		final SortableAttributeCompoundSchemaContract existingCompoundSchema = entitySchema.getSortableAttributeCompound(name).orElse(null);
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
				"The sortable attribute compound `" + name + "` already exists in entity `" + entitySchema.getName() +
					"` schema and it has different definition. To alter existing sortable attribute compound schema you" +
					" need to use different mutations."
			);
		}
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		final SortableAttributeCompoundSchemaContract newCompoundSchema = mutate(entitySchema, referenceSchema, null);
		final SortableAttributeCompoundSchemaContract existingCompoundSchema = referenceSchema.getSortableAttributeCompound(name).orElse(null);
		if (existingCompoundSchema == null) {
			return ReferenceSchema._internalBuild(
				referenceSchema.getName(),
				referenceSchema.getNameVariants(),
				referenceSchema.getDescription(),
				referenceSchema.getDeprecationNotice(),
				referenceSchema.getReferencedEntityType(),
				referenceSchema.isReferencedEntityTypeManaged() ? Collections.emptyMap() : referenceSchema.getEntityTypeNameVariants(s -> null),
				referenceSchema.isReferencedEntityTypeManaged(),
				referenceSchema.getCardinality(),
				referenceSchema.getReferencedGroupType(),
				referenceSchema.isReferencedGroupTypeManaged() ? Collections.emptyMap() : referenceSchema.getGroupTypeNameVariants(s -> null),
				referenceSchema.isReferencedGroupTypeManaged(),
				referenceSchema.isIndexed(),
				referenceSchema.isFaceted(),
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
		} else if (existingCompoundSchema.equals(newCompoundSchema)) {
			// the mutation must have been applied previously - return the schema we don't need to alter
			return referenceSchema;
		} else {
			// ups, there is conflict in attribute settings
			throw new InvalidSchemaMutationException(
				"The sortable attribute compound `" + name + "` already exists in entity `" + entitySchema.getName() + "`" +
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
			"name='" + name + '\'' +
			", description='" + description + '\'' +
			", deprecationNotice='" + deprecationNotice + '\'' +
			", attributeElements=" + Arrays.stream(attributeElements).map(AttributeElement::toString).collect(Collectors.joining(", "));
	}

	@Nullable
	private static <T> EntitySchemaMutation makeMutationIfDifferent(
		@Nonnull SortableAttributeCompoundSchemaContract createdVersion,
		@Nonnull SortableAttributeCompoundSchemaContract existingVersion,
		@Nonnull Function<SortableAttributeCompoundSchemaContract, T> propertyRetriever,
		@Nonnull Function<T, EntitySchemaMutation> mutationCreator
	) {
		final T newValue = propertyRetriever.apply(createdVersion);
		return Objects.equals(propertyRetriever.apply(existingVersion), newValue) ?
			null : mutationCreator.apply(newValue);
	}

}
