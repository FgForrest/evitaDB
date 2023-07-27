/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaWithDeprecationContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
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
import java.io.Serializable;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mutation is responsible for setting up a new {@link AttributeSchemaContract} in the {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link AttributeSchemaContract} alone.
 * Mutation implements {@link CombinableEntitySchemaMutation} allowing to resolve conflicts with
 * {@link RemoveAttributeSchemaMutation} mutation (if such is found in mutation pipeline).
 *
 * TOBEDONE JNO - write tests
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class CreateAttributeSchemaMutation implements ReferenceAttributeSchemaMutation, CombinableEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -7082514745878566818L;
	@Getter @Nonnull private final String name;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter private final boolean unique;
	@Getter private final boolean filterable;
	@Getter private final boolean sortable;
	@Getter private final boolean localized;
	@Getter private final boolean nullable;
	@Getter @Nonnull private final Class<? extends Serializable> type;
	@Getter @Nullable private final Serializable defaultValue;
	@Getter private final int indexedDecimalPlaces;

	public CreateAttributeSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		boolean unique,
		boolean filterable,
		boolean sortable,
		boolean localized,
		boolean nullable,
		@Nonnull Class<? extends Serializable> type,
		@Nullable Serializable defaultValue,
		int indexedDecimalPlaces
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ATTRIBUTE, name);
		this.name = name;
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.unique = unique;
		this.filterable = filterable;
		this.sortable = sortable;
		this.localized = localized;
		this.nullable = nullable;
		this.type = type;
		this.defaultValue = defaultValue;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
	}

	@Nonnull
	@Override
	public Operation getOperation() {
		return Operation.CREATE;
	}

	@Nullable
	@Override
	public MutationCombinationResult<EntitySchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull EntitySchemaContract currentEntitySchema, @Nonnull EntitySchemaMutation existingMutation) {
		// when the attribute schema was removed before and added again, we may remove both operations
		// and leave only operations that reset the original settings do defaults
		if (existingMutation instanceof RemoveAttributeSchemaMutation removeAttributeSchema && Objects.equals(removeAttributeSchema.getName(), name)) {
			final AttributeSchemaContract createdVersion = mutate(currentCatalogSchema, (AttributeSchemaContract) null);
			final AttributeSchemaContract existingVersion = currentEntitySchema.getAttribute(name).orElseThrow();
			return new MutationCombinationResult<>(
				null,
				Stream.of(
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							NamedSchemaContract::getDescription,
							newValue -> new ModifyAttributeSchemaDescriptionMutation(name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							NamedSchemaWithDeprecationContract::getDeprecationNotice,
							newValue -> new ModifyAttributeSchemaDeprecationNoticeMutation(name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							AttributeSchemaContract::getType,
							newValue -> new ModifyAttributeSchemaTypeMutation(name, newValue, indexedDecimalPlaces)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							AttributeSchemaContract::getDefaultValue,
							newValue -> new ModifyAttributeSchemaDefaultValueMutation(name, defaultValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							AttributeSchemaContract::isFilterable,
							newValue -> new SetAttributeSchemaFilterableMutation(name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							AttributeSchemaContract::isUnique,
							newValue -> new SetAttributeSchemaUniqueMutation(name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							AttributeSchemaContract::isSortable,
							newValue -> new SetAttributeSchemaSortableMutation(name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							AttributeSchemaContract::isLocalized,
							newValue -> new SetAttributeSchemaLocalizedMutation(name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							AttributeSchemaContract::isNullable,
							newValue -> new SetAttributeSchemaNullableMutation(name, newValue)
						)
					)
					.filter(Objects::nonNull)
					.toArray(EntitySchemaMutation[]::new)
			);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public <S extends AttributeSchemaContract> S mutate(@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema) {
		//noinspection unchecked,rawtypes
		return (S) AttributeSchema._internalBuild(
			name, description, deprecationNotice,
			unique, filterable, sortable, localized, nullable,
			(Class) type, defaultValue,
			indexedDecimalPlaces
		);
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final AttributeSchemaContract newAttributeSchema = mutate(catalogSchema, (AttributeSchemaContract) null);
		final AttributeSchemaContract existingAttributeSchema = entitySchema.getAttribute(name).orElse(null);
		if (existingAttributeSchema == null) {
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
				Stream.concat(
						entitySchema.getAttributes().values().stream(),
						Stream.of(newAttributeSchema)
					)
					.collect(
						Collectors.toMap(
							AttributeSchemaContract::getName,
							Function.identity()
						)
					),
				entitySchema.getAssociatedData(),
				entitySchema.getReferences(),
				entitySchema.getEvolutionMode(),
				entitySchema.getSortableAttributeCompounds()
			);
		} else if (existingAttributeSchema.equals(newAttributeSchema)) {
			// the mutation must have been applied previously - return the schema we don't need to alter
			return entitySchema;
		} else {
			// ups, there is conflict in attribute settings
			throw new InvalidSchemaMutationException(
				"The attribute `" + name + "` already exists in entity `" + entitySchema.getName() + "` schema and" +
					" it has different definition. To alter existing attribute schema you need to use different mutations."
			);
		}
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchemaContract, @Nullable ReferenceSchemaContract referenceSchema) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		@SuppressWarnings({"unchecked", "rawtypes"}) final AttributeSchema newAttributeSchema = AttributeSchema._internalBuild(
			name, description, deprecationNotice,
			unique, filterable, sortable, localized, nullable,
			(Class) type, defaultValue,
			indexedDecimalPlaces
		);
		final AttributeSchemaContract existingAttributeSchema = referenceSchema.getAttribute(name).orElse(null);
		if (existingAttributeSchema == null) {
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
				Stream.concat(
						referenceSchema.getAttributes().values().stream(),
						Stream.of(newAttributeSchema)
					)
					.collect(
						Collectors.toMap(
							AttributeSchemaContract::getName,
							Function.identity()
						)
					),
				referenceSchema.getSortableAttributeCompounds()
			);
		} else if (existingAttributeSchema.equals(newAttributeSchema)) {
			// the mutation must have been applied previously - return the schema we don't need to alter
			return referenceSchema;
		} else {
			// ups, there is conflict in attribute settings
			throw new InvalidSchemaMutationException(
				"The attribute `" + name + "` already exists in entity `" + entitySchemaContract.getName() + "`" +
					" reference `" + referenceSchema.getName() + "` schema and" +
					" it has different definition. To alter existing attribute schema you need to use different mutations."
			);
		}
	}

	@Override
	public String toString() {
		return "Create attribute schema: " +
			"name='" + name + '\'' +
			", description='" + description + '\'' +
			", deprecationNotice='" + deprecationNotice + '\'' +
			", unique=" + unique +
			", filterable=" + filterable +
			", sortable=" + sortable +
			", localized=" + localized +
			", nullable=" + nullable +
			", type=" + type +
			", defaultValue=" + defaultValue +
			", indexedDecimalPlaces=" + indexedDecimalPlaces;
	}

	@Nullable
	private static <T> EntitySchemaMutation makeMutationIfDifferent(
		@Nonnull AttributeSchemaContract createdVersion,
		@Nonnull AttributeSchemaContract existingVersion,
		@Nonnull Function<AttributeSchemaContract, T> propertyRetriever,
		@Nonnull Function<T, EntitySchemaMutation> mutationCreator
	) {
		final T newValue = propertyRetriever.apply(createdVersion);
		return Objects.equals(propertyRetriever.apply(existingVersion), newValue) ?
			null : mutationCreator.apply(newValue);
	}
}
