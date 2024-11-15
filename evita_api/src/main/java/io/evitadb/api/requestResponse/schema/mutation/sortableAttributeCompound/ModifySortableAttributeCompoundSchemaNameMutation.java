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
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
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

/**
 * Mutation is responsible for renaming an existing {@link SortableAttributeCompoundSchema} in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link SortableAttributeCompoundSchema} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class ModifySortableAttributeCompoundSchemaNameMutation
	implements CombinableLocalEntitySchemaMutation, ReferenceSortableAttributeCompoundSchemaMutation {
	@Serial private static final long serialVersionUID = -9180398601079510531L;
	@Nonnull @Getter private final String name;
	@Getter @Nonnull private final String newName;

	public ModifySortableAttributeCompoundSchemaNameMutation(@Nonnull String name, @Nonnull String newName) {
		this.name = name;
		this.newName = newName;
	}

	@Nullable
	@Override
	public SortableAttributeCompoundSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable SortableAttributeCompoundSchemaContract sortableAttributeCompoundSchema
	) {
		Assert.isPremiseValid(sortableAttributeCompoundSchema != null, "Sortable attribute compound schema is mandatory!");
		return SortableAttributeCompoundSchema._internalBuild(
			this.newName,
			sortableAttributeCompoundSchema.getNameVariants(),
			sortableAttributeCompoundSchema.getDescription(),
			sortableAttributeCompoundSchema.getDeprecationNotice(),
			sortableAttributeCompoundSchema.getIndexedInScopes(),
			sortableAttributeCompoundSchema.getAttributeElements()
		);
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof ModifySortableAttributeCompoundSchemaNameMutation theExistingMutation &&
			name.equals(theExistingMutation.getName())) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final SortableAttributeCompoundSchemaContract existingCompoundSchema = entitySchema.getSortableAttributeCompound(name)
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The sortable attribute compound `" + name + "` is not defined in entity `" + entitySchema.getName() + "` schema!"
			));

		final SortableAttributeCompoundSchemaContract updatedAttributeSchema = mutate(entitySchema, null, existingCompoundSchema);
		return replaceSortableAttributeCompoundIfDifferent(
			entitySchema, existingCompoundSchema, updatedAttributeSchema
		);
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema, @Nonnull ConsistencyChecks consistencyChecks) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		final SortableAttributeCompoundSchemaContract existingCompoundSchema = getReferenceSortableAttributeCompoundSchemaOrThrow(entitySchema, referenceSchema, name);
		final SortableAttributeCompoundSchemaContract updatedAttributeSchema = mutate(entitySchema, null, existingCompoundSchema);
		return replaceSortableAttributeCompoundIfDifferent(
			referenceSchema, existingCompoundSchema, updatedAttributeSchema
		);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Modify sortable attribute compound `" + name + "` schema: " +
			"newName='" + newName + '\'';
	}
}
