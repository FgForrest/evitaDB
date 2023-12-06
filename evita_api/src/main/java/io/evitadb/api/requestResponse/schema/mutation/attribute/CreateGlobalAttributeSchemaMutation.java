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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaWithDeprecationContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.EvitaDataTypes;
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
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mutation is responsible for setting up a new {@link GlobalAttributeSchemaContract} in the {@link CatalogSchemaContract}.
 * Mutation can be used for altering also the existing {@link GlobalAttributeSchemaContract} alone.
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
public class CreateGlobalAttributeSchemaMutation
	implements GlobalAttributeSchemaMutation, CombinableCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -7082514745878566818L;
	@Getter @Nonnull private final String name;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nonnull private final AttributeUniquenessType unique;
	@Getter @Nonnull private final GlobalAttributeUniquenessType uniqueGlobally;
	@Getter private final boolean filterable;
	@Getter private final boolean sortable;
	@Getter private final boolean localized;
	@Getter private final boolean nullable;
	@Getter private final boolean representative;
	@Getter @Nonnull private final Class<? extends Serializable> type;
	@Getter @Nullable private final Serializable defaultValue;
	@Getter private final int indexedDecimalPlaces;

	@Nullable
	private static <T> LocalCatalogSchemaMutation makeMutationIfDifferent(
		@Nonnull GlobalAttributeSchemaContract createdVersion,
		@Nonnull GlobalAttributeSchemaContract existingVersion,
		@Nonnull Function<GlobalAttributeSchemaContract, T> propertyRetriever,
		@Nonnull Function<T, LocalCatalogSchemaMutation> mutationCreator
	) {
		final T newValue = propertyRetriever.apply(createdVersion);
		return Objects.equals(propertyRetriever.apply(existingVersion), newValue) ?
			null : mutationCreator.apply(newValue);
	}

	public CreateGlobalAttributeSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable AttributeUniquenessType unique,
		@Nullable GlobalAttributeUniquenessType uniqueGlobally,
		boolean filterable,
		boolean sortable,
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
		this.unique = unique == null ? AttributeUniquenessType.NOT_UNIQUE : unique;
		this.uniqueGlobally = uniqueGlobally == null ? GlobalAttributeUniquenessType.NOT_UNIQUE : uniqueGlobally;
		this.filterable = filterable;
		this.sortable = sortable;
		this.localized = localized;
		this.nullable = nullable;
		this.representative = representative;
		this.type = type;
		this.defaultValue = defaultValue;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation) {
		// when the attribute schema was removed before and added again, we may remove both operations
		// and leave only operations that reset the original settings do defaults
		if (existingMutation instanceof RemoveAttributeSchemaMutation removeAttributeSchema && Objects.equals(removeAttributeSchema.getName(), name)) {
			final GlobalAttributeSchemaContract createdVersion = mutate(currentCatalogSchema, null, GlobalAttributeSchemaContract.class);
			final GlobalAttributeSchemaContract existingVersion = currentCatalogSchema.getAttribute(name).orElseThrow();
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
							GlobalAttributeSchemaContract::getType,
							newValue -> new ModifyAttributeSchemaTypeMutation(name, newValue, indexedDecimalPlaces)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							GlobalAttributeSchemaContract::getDefaultValue,
							newValue -> new ModifyAttributeSchemaDefaultValueMutation(name, defaultValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							GlobalAttributeSchemaContract::isFilterable,
							newValue -> new SetAttributeSchemaFilterableMutation(name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							GlobalAttributeSchemaContract::getUniquenessType,
							newValue -> new SetAttributeSchemaUniqueMutation(name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							GlobalAttributeSchemaContract::getGlobalUniquenessType,
							newValue -> new SetAttributeSchemaGloballyUniqueMutation(name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							GlobalAttributeSchemaContract::isSortable,
							newValue -> new SetAttributeSchemaSortableMutation(name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							GlobalAttributeSchemaContract::isLocalized,
							newValue -> new SetAttributeSchemaLocalizedMutation(name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							GlobalAttributeSchemaContract::isNullable,
							newValue -> new SetAttributeSchemaNullableMutation(name, newValue)
						),
						makeMutationIfDifferent(
							createdVersion, existingVersion,
							GlobalAttributeSchemaContract::isRepresentative,
							newValue -> new SetAttributeSchemaRepresentativeMutation(name, newValue)
						)
					)
					.filter(Objects::nonNull)
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
			name, description, deprecationNotice,
			unique, uniqueGlobally, filterable, sortable, localized, nullable, representative,
			(Class) type, defaultValue,
			indexedDecimalPlaces
		);
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor) {
		Assert.isPremiseValid(catalogSchema != null, "Catalog schema is mandatory!");
		final GlobalAttributeSchemaContract newAttributeSchema = mutate(catalogSchema, null, GlobalAttributeSchemaContract.class);
		final GlobalAttributeSchemaContract existingAttributeSchema = catalogSchema.getAttribute(name).orElse(null);
		if (existingAttributeSchema == null) {
			return new CatalogSchemaWithImpactOnEntitySchemas(
				CatalogSchema._internalBuild(
					catalogSchema.getVersion() + 1,
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
				"The attribute `" + name + "` already exists in entity `" + catalogSchema.getName() + "` schema and" +
					" has different definition. To alter existing attribute schema you need to use different mutations."
			);
		}
	}

	@Override
	public String toString() {
		return "Create global attribute schema: " +
			"name='" + name + '\'' +
			", description='" + description + '\'' +
			", deprecationNotice='" + deprecationNotice + '\'' +
			", unique=" + unique +
			", uniqueGlobally=" + uniqueGlobally +
			", filterable=" + filterable +
			", sortable=" + sortable +
			", localized=" + localized +
			", nullable=" + nullable +
			", representative=" + representative +
			", type=" + type +
			", defaultValue=" + defaultValue +
			", indexedDecimalPlaces=" + indexedDecimalPlaces;
	}
}
