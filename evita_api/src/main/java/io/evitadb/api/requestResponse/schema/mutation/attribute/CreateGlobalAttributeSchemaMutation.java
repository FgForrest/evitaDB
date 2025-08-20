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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaWithDeprecationContract;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CreateMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.EvitaDataTypes;
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
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.dataType.Scope.NO_SCOPE;

/**
 * Mutation is responsible for setting up a new {@link GlobalAttributeSchemaContract} in the {@link CatalogSchemaContract}.
 * Mutation can be used for altering also the existing {@link GlobalAttributeSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with
 * {@link RemoveAttributeSchemaMutation} mutation (if such is found in mutation pipeline).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class CreateGlobalAttributeSchemaMutation
	implements GlobalAttributeSchemaMutation, CombinableCatalogSchemaMutation, CatalogSchemaMutation, CreateMutation {
	@Serial private static final long serialVersionUID = 496202593310308290L;

	@Getter @Nonnull private final String name;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nonnull private final ScopedAttributeUniquenessType[] uniqueInScopes;
	@Getter @Nonnull private final ScopedGlobalAttributeUniquenessType[] uniqueGloballyInScopes;
	@Getter private final Scope[] filterableInScopes;
	@Getter private final Scope[] sortableInScopes;
	@Getter private final boolean localized;
	@Getter private final boolean nullable;
	@Getter private final boolean representative;
	@Getter @Nonnull private final Class<? extends Serializable> type;
	@Getter @Nullable private final Serializable defaultValue;
	@Getter private final int indexedDecimalPlaces;

	public CreateGlobalAttributeSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull AttributeUniquenessType unique,
		@Nonnull GlobalAttributeUniquenessType uniqueGlobally,
		boolean filterable,
		boolean sortable,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<? extends Serializable> type,
		@Nullable Serializable defaultValue,
		int indexedDecimalPlaces
	) {
		this(
			name, description, deprecationNotice,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.DEFAULT_SCOPE, unique)
			},
			new ScopedGlobalAttributeUniquenessType[]{
				new ScopedGlobalAttributeUniquenessType(Scope.DEFAULT_SCOPE, uniqueGlobally)
			},
			filterable ? Scope.DEFAULT_SCOPES : NO_SCOPE,
			sortable ? Scope.DEFAULT_SCOPES : NO_SCOPE,
			localized, nullable, representative, type, defaultValue, indexedDecimalPlaces
		);
	}

	@SerializableCreator
	public CreateGlobalAttributeSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable ScopedAttributeUniquenessType[] uniqueInScopes,
		@Nullable ScopedGlobalAttributeUniquenessType[] uniqueGloballyInScopes,
		@Nullable Scope[] filterableInScopes,
		@Nullable Scope[] sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<? extends Serializable> type,
		@Nullable Serializable defaultValue,
		int indexedDecimalPlaces
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ATTRIBUTE, name);
		if (!EvitaDataTypes.isSupportedTypeOrItsArray(type)) {
			throw new InvalidSchemaMutationException("The type `" + type + "` is not allowed in attributes!");
		}
		this.name = name;
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.uniqueInScopes = uniqueInScopes == null ?
			new ScopedAttributeUniquenessType[] { new ScopedAttributeUniquenessType(Scope.DEFAULT_SCOPE, AttributeUniquenessType.NOT_UNIQUE) } : uniqueInScopes;
		this.uniqueGloballyInScopes = uniqueGloballyInScopes == null ?
			new ScopedGlobalAttributeUniquenessType[] { new ScopedGlobalAttributeUniquenessType(Scope.DEFAULT_SCOPE, GlobalAttributeUniquenessType.NOT_UNIQUE) } : uniqueGloballyInScopes;
		this.filterableInScopes = filterableInScopes == null ? NO_SCOPE : filterableInScopes;
		this.sortableInScopes = sortableInScopes == null ? NO_SCOPE : sortableInScopes;
		this.localized = localized;
		this.nullable = nullable;
		this.representative = representative;
		this.type = type;
		this.defaultValue = defaultValue;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
	}

	@Nonnull
	public AttributeUniquenessType getUnique() {
		return Arrays.stream(this.uniqueInScopes)
			.filter(it -> it.scope() == Scope.DEFAULT_SCOPE)
			.findFirst()
			.map(ScopedAttributeUniquenessType::uniquenessType)
			.orElse(AttributeUniquenessType.NOT_UNIQUE);
	}

	@Nonnull
	public GlobalAttributeUniquenessType getUniqueGlobally() {
		return Arrays.stream(this.uniqueGloballyInScopes)
			.filter(it -> it.scope() == Scope.DEFAULT_SCOPE)
			.findFirst()
			.map(ScopedGlobalAttributeUniquenessType::uniquenessType)
			.orElse(GlobalAttributeUniquenessType.NOT_UNIQUE);
	}

	public boolean isFilterable() {
		return !ArrayUtils.isEmptyOrItsValuesNull(this.filterableInScopes);
	}

	public boolean isSortable() {
		return !ArrayUtils.isEmptyOrItsValuesNull(this.sortableInScopes);
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation) {
		// when the attribute schema was removed before and added again, we may remove both operations
		// and leave only operations that reset the original settings do defaults
		if (existingMutation instanceof RemoveAttributeSchemaMutation removeAttributeSchema && Objects.equals(removeAttributeSchema.getName(), this.name)) {
			final GlobalAttributeSchemaContract createdVersion = mutate(currentCatalogSchema, null, GlobalAttributeSchemaContract.class);
			final GlobalAttributeSchemaContract existingVersion = currentCatalogSchema.getAttribute(this.name).orElseThrow();
			return new MutationCombinationResult<>(
				null,
				Stream.of(
						makeMutationIfDifferent(
							GlobalAttributeSchemaContract.class,
							createdVersion, existingVersion,
							NamedSchemaContract::getDescription,
							newValue -> new ModifyAttributeSchemaDescriptionMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							GlobalAttributeSchemaContract.class,
							createdVersion, existingVersion,
							NamedSchemaWithDeprecationContract::getDeprecationNotice,
							newValue -> new ModifyAttributeSchemaDeprecationNoticeMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							GlobalAttributeSchemaContract.class,
							createdVersion, existingVersion,
							GlobalAttributeSchemaContract::getType,
							newValue -> new ModifyAttributeSchemaTypeMutation(this.name, newValue, this.indexedDecimalPlaces)
						),
						makeMutationIfDifferent(
							GlobalAttributeSchemaContract.class,
							createdVersion, existingVersion,
							GlobalAttributeSchemaContract::getDefaultValue,
							newValue -> new ModifyAttributeSchemaDefaultValueMutation(this.name, this.defaultValue)
						),
						makeMutationIfDifferent(
							GlobalAttributeSchemaContract.class,
							createdVersion, existingVersion,
							schema -> Arrays.stream(Scope.values())
								.filter(schema::isFilterableInScope)
								.toArray(Scope[]::new),
							newValue -> new SetAttributeSchemaFilterableMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							GlobalAttributeSchemaContract.class,
							createdVersion, existingVersion,
							schema -> Arrays.stream(Scope.values())
								.map(scope -> new ScopedAttributeUniquenessType(scope, schema.getUniquenessType(scope)))
								// filter out default values
								.filter(it -> it.uniquenessType() != AttributeUniquenessType.NOT_UNIQUE)
								.toArray(ScopedAttributeUniquenessType[]::new),
							newValue -> new SetAttributeSchemaUniqueMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							GlobalAttributeSchemaContract.class,
							createdVersion, existingVersion,
							schema -> Arrays.stream(Scope.values())
								.map(scope -> new ScopedGlobalAttributeUniquenessType(scope, schema.getGlobalUniquenessType(scope)))
								// filter out default values
								.filter(it -> it.uniquenessType() != GlobalAttributeUniquenessType.NOT_UNIQUE)
								.toArray(ScopedGlobalAttributeUniquenessType[]::new),
							newValue -> new SetAttributeSchemaGloballyUniqueMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							GlobalAttributeSchemaContract.class,
							createdVersion, existingVersion,
							schema -> Arrays.stream(Scope.values())
								.filter(schema::isSortableInScope)
								.toArray(Scope[]::new),
							newValue -> new SetAttributeSchemaSortableMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							GlobalAttributeSchemaContract.class,
							createdVersion, existingVersion,
							GlobalAttributeSchemaContract::isLocalized,
							newValue -> new SetAttributeSchemaLocalizedMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							GlobalAttributeSchemaContract.class,
							createdVersion, existingVersion,
							GlobalAttributeSchemaContract::isNullable,
							newValue -> new SetAttributeSchemaNullableMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							GlobalAttributeSchemaContract.class,
							createdVersion, existingVersion,
							GlobalAttributeSchemaContract::isRepresentative,
							newValue -> new SetAttributeSchemaRepresentativeMutation(this.name, newValue)
						)
					)
					.filter(Objects::nonNull)
					.map(LocalCatalogSchemaMutation.class::cast)
					.toArray(LocalCatalogSchemaMutation[]::new)
			);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public <S extends AttributeSchemaContract> S mutate(@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema, @Nonnull Class<S> schemaType) {
		//noinspection unchecked,rawtypes
		return (S) GlobalAttributeSchema._internalBuild(
			this.name, this.description, this.deprecationNotice,
			this.uniqueInScopes, this.uniqueGloballyInScopes,
			this.filterableInScopes, this.sortableInScopes,
			this.localized, this.nullable, this.representative,
			(Class) this.type, this.defaultValue,
			this.indexedDecimalPlaces
		);
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor) {
		Assert.isPremiseValid(catalogSchema != null, "Catalog schema is mandatory!");
		final GlobalAttributeSchemaContract newAttributeSchema = mutate(catalogSchema, null, GlobalAttributeSchemaContract.class);
		final GlobalAttributeSchemaContract existingAttributeSchema = catalogSchema.getAttribute(this.name).orElse(null);
		if (existingAttributeSchema == null) {
			return new CatalogSchemaWithImpactOnEntitySchemas(
				CatalogSchema._internalBuild(
					catalogSchema.version() + 1,
					catalogSchema.getName(),
					catalogSchema.getNameVariants(),
					catalogSchema.getDescription(),
					catalogSchema.getCatalogEvolutionMode(),
					Stream.concat(
							catalogSchema.getAttributes().values().stream(),
							Stream.of(newAttributeSchema)
						)
						.collect(
							Collectors.toMap(
								GlobalAttributeSchemaContract::getName,
								Function.identity()
							)
						),
					entitySchemaAccessor
				)
			);
		} else if (existingAttributeSchema.equals(newAttributeSchema)) {
			// the mutation must have been applied previously - return the schema we don't need to alter
			return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
		} else {
			// ups, there is conflict in attribute settings
			throw new InvalidSchemaMutationException(
				"The attribute `" + this.name + "` already exists in entity `" + catalogSchema.getName() + "` schema and" +
					" has different definition. To alter existing attribute schema you need to use different mutations."
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
		return "Create global attribute schema: " +
			"name='" + this.name + '\'' +
			", description='" + this.description + '\'' +
			", deprecationNotice='" + this.deprecationNotice + '\'' +
			", unique=(" + (Arrays.stream(this.uniqueInScopes).map(it -> it.scope() + ": " + it.uniquenessType().name())) + ")" +
			", uniqueGlobally=(" + (Arrays.stream(this.uniqueGloballyInScopes).map(it -> it.scope() + ": " + it.uniquenessType().name())) + ")" +
			", filterable=" + (isFilterable() ? "(in scopes: " + Arrays.toString(this.filterableInScopes) + ")" : "no") +
			", sortable=" + (isSortable() ? "(in scopes: " + Arrays.toString(this.sortableInScopes) + ")" : "no") +
			", localized=" + this.localized +
			", nullable=" + this.nullable +
			", representative=" + this.representative +
			", type=" + this.type +
			", defaultValue=" + this.defaultValue +
			", indexedDecimalPlaces=" + this.indexedDecimalPlaces;
	}

}
