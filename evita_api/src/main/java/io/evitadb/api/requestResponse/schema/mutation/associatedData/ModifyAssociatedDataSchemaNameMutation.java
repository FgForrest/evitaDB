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
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
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
import java.util.Optional;

/**
 * Mutation is responsible for renaming an existing {@link AssociatedDataSchemaContract} in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link AssociatedDataSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class ModifyAssociatedDataSchemaNameMutation
	extends AbstractModifyAssociatedDataSchemaMutation implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -9148725260493440657L;
	@Getter @Nonnull private final String newName;

	/**
	 * Creates a mutation that will rename an existing associated data schema.
	 *
	 * @param name    current name of the associated data schema to rename
	 * @param newName new name for the associated data schema (must be a valid classifier format)
	 * @throws io.evitadb.exception.InvalidClassifierFormatException if the new name has invalid format
	 */
	public ModifyAssociatedDataSchemaNameMutation(
		@Nonnull String name,
		@Nonnull String newName
	) {
		super(name);
		ClassifierUtils.validateClassifierFormat(ClassifierType.ASSOCIATED_DATA, newName);
		this.newName = newName;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof ModifyAssociatedDataSchemaNameMutation theExistingMutation && this.name.equals(
			theExistingMutation.getName())) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public AssociatedDataSchemaContract mutate(@Nullable AssociatedDataSchemaContract associatedDataSchema) {
		Assert.isPremiseValid(associatedDataSchema != null, "Associated data schema is mandatory!");
		return AssociatedDataSchema._internalBuild(
			this.newName,
			associatedDataSchema.getDescription(),
			associatedDataSchema.getDeprecationNotice(),
			associatedDataSchema.getType(),
			associatedDataSchema.isLocalized(),
			associatedDataSchema.isNullable()
		);
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(
		@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final Optional<AssociatedDataSchemaContract> existingAssociatedDataSchema = entitySchema.getAssociatedData(
			this.name);
		if (existingAssociatedDataSchema.isEmpty()) {
			// ups, the associated data is missing
			throw new InvalidSchemaMutationException(
				"The associated data `" + this.name + "` is not defined in entity `" + entitySchema.getName() + "` schema!"
			);
		} else {
			final AssociatedDataSchemaContract theSchema = existingAssociatedDataSchema.get();
			final AssociatedDataSchemaContract updatedAssociatedDataSchema = mutate(theSchema);
			return replaceAssociatedDataIfDifferent(entitySchema, theSchema, updatedAssociatedDataSchema);
		}
	}

	@Override
	public String toString() {
		return "Modify associated data `" + this.name + "`: " +
			"newName='" + this.newName + '\'';
	}
}
