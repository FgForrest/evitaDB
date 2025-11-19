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

package io.evitadb.api.requestResponse.schema.mutation.associatedData;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaWithDeprecationContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.AssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CreateMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.NamedSchemaMutation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
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
import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mutation is responsible for setting up a new {@link AssociatedDataSchemaContract} in the {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link AssociatedDataSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with
 * {@link RemoveAssociatedDataSchemaMutation} mutation (if such is found in mutation pipeline).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class CreateAssociatedDataSchemaMutation
	implements AssociatedDataSchemaMutation, CombinableLocalEntitySchemaMutation, CreateMutation, NamedSchemaMutation {
	@Serial private static final long serialVersionUID = -7368528015832499968L;
	@Getter @Nonnull private final String name;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nonnull private final Class<? extends Serializable> type;
	@Getter private final boolean localized;
	@Getter private final boolean nullable;

	public CreateAssociatedDataSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull Class<? extends Serializable> type,
		boolean localized,
		boolean nullable
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ASSOCIATED_DATA, name);
		final Class<?> plainType = type.isArray() ? type.getComponentType() : type;
		if (!(EvitaDataTypes.isSupportedTypeOrItsArray(type) || ComplexDataObject.class.equals(plainType))
			|| Predecessor.class.equals(plainType) || ReferencedEntityPredecessor.class.equals(plainType)) {
			throw new InvalidSchemaMutationException("The type `" + type + "` is not allowed in associated data!");
		}
		this.name = name;
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.type = type;
		this.localized = localized;
		this.nullable = nullable;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		// when the associated schema was removed before and added again, we may remove both operations
		// and leave only operations that reset the original settings do defaults
		if (existingMutation instanceof RemoveAssociatedDataSchemaMutation removeAssociatedDataSchema && Objects.equals(removeAssociatedDataSchema.getName(), this.name)) {
			final AssociatedDataSchemaContract createdVersion = mutate(null);
			final AssociatedDataSchemaContract existingVersion = currentEntitySchema.getAssociatedData(this.name)
				.orElseThrow(() -> new GenericEvitaInternalError("Sanity check!"));

			return new MutationCombinationResult<>(
				null,
				Stream.of(
						makeMutationIfDifferent(
							AssociatedDataSchemaContract.class,
							createdVersion, existingVersion,
							NamedSchemaContract::getDescription,
							newValue -> new ModifyAssociatedDataSchemaDescriptionMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							AssociatedDataSchemaContract.class,
							createdVersion, existingVersion,
							NamedSchemaWithDeprecationContract::getDeprecationNotice,
							newValue -> new ModifyAssociatedDataSchemaDeprecationNoticeMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							AssociatedDataSchemaContract.class,
							createdVersion, existingVersion,
							AssociatedDataSchemaContract::getType,
							newValue -> new ModifyAssociatedDataSchemaTypeMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							AssociatedDataSchemaContract.class,
							createdVersion, existingVersion,
							AssociatedDataSchemaContract::isLocalized,
							newValue -> new SetAssociatedDataSchemaLocalizedMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							AssociatedDataSchemaContract.class,
							createdVersion, existingVersion,
							AssociatedDataSchemaContract::isNullable,
							newValue -> new SetAssociatedDataSchemaNullableMutation(this.name, newValue)
						)
					)
					.filter(Objects::nonNull)
					.toArray(LocalEntitySchemaMutation[]::new)
			);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public AssociatedDataSchemaContract mutate(@Nullable AssociatedDataSchemaContract associatedDataSchema) {
		return AssociatedDataSchema._internalBuild(
			this.name, this.description, this.deprecationNotice, this.type, this.localized, this.nullable
		);
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final AssociatedDataSchemaContract newAssociatedDataSchema = mutate(null);
		final Optional<AssociatedDataSchemaContract> existingAssociatedDataSchema = entitySchema.getAssociatedData(this.name);
		if (existingAssociatedDataSchema.isEmpty()) {
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
				Stream.concat(
						entitySchema.getAssociatedData().values().stream(),
						Stream.of(newAssociatedDataSchema)
					)
					.collect(
						Collectors.toMap(
							AssociatedDataSchemaContract::getName,
							Function.identity()
						)
					),
				entitySchema.getReferences(),
				entitySchema.getEvolutionMode(),
				entitySchema.getSortableAttributeCompounds()
			);
		} else if (existingAssociatedDataSchema.get().equals(newAssociatedDataSchema)) {
			// the mutation must have been applied previously - return the schema we don't need to alter
			return entitySchema;
		} else {
			// ups, there is conflict in associated data settings
			throw new InvalidSchemaMutationException(
				"The associated data `" + this.name + "` already exists in entity `" + entitySchema.getName() + "` schema and" +
					" has different definition. To alter existing associated data schema you need to use different mutations."
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
		return "Create associated data: " +
			"name='" + this.name + '\'' +
			", description='" + this.description + '\'' +
			", deprecationNotice='" + this.deprecationNotice + '\'' +
			", type=" + this.type +
			", localized=" + this.localized +
			", nullable=" + this.nullable;
	}
}
