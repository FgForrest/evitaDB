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
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
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
import java.util.Objects;

/**
 * Mutation is responsible for setting value to a {@link SortableAttributeCompoundSchema#isIndexedInScope(Scope)}
 * in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link SortableAttributeCompoundSchemaContract}.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class SetSortableAttributeCompoundSchemaIndexedMutation
	implements CombinableLocalEntitySchemaMutation, ReferenceSortableAttributeCompoundSchemaMutation,
	NamedSchemaMutation {
	@Serial private static final long serialVersionUID = 3555872852091050565L;

	@Nonnull @Getter private final String name;
	@Nonnull @Getter private final Scope[] indexedInScopes;

	public SetSortableAttributeCompoundSchemaIndexedMutation(
		@Nonnull String name,
		@Nullable Scope[] indexedInScopes
	) {
		this.name = name;
		this.indexedInScopes = indexedInScopes == null ? Scope.NO_SCOPE : indexedInScopes;
	}

	/**
	 * Checks if the current instance has indexed scopes.
	 *
	 * @return true if the indexedInScopes array is neither empty nor contains null values,
	 *         otherwise returns false.
	 */
	public boolean isIndexed() {
		return !ArrayUtils.isEmptyOrItsValuesNull(this.indexedInScopes);
	}

	@Nullable
	@Override
	public <T extends SortableAttributeCompoundSchemaContract> T mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable T existingSchema
	) {
		Assert.isPremiseValid(existingSchema != null, "Sortable attribute compound schema is mandatory!");
		if (referenceSchema == null) {
			//noinspection unchecked
			return (T) EntitySortableAttributeCompoundSchema._internalBuild(
				this.name,
				existingSchema.getNameVariants(),
				existingSchema.getDescription(),
				existingSchema.getDeprecationNotice(),
				this.indexedInScopes,
				existingSchema.getAttributeElements()
			);
		} else {
			//noinspection unchecked
			return (T) SortableAttributeCompoundSchema._internalBuild(
				this.name,
				existingSchema.getNameVariants(),
				existingSchema.getDescription(),
				existingSchema.getDeprecationNotice(),
				this.indexedInScopes,
				existingSchema.getAttributeElements()
			);
		}
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof SetSortableAttributeCompoundSchemaIndexedMutation theExistingMutation &&
			this.name.equals(theExistingMutation.getName())) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final EntitySortableAttributeCompoundSchemaContract existingCompoundSchema = entitySchema.getSortableAttributeCompound(this.name)
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The sortable attribute compound `" + this.name + "` is not defined in entity `" + entitySchema.getName() + "` schema!"
			));

		final EntitySortableAttributeCompoundSchemaContract updatedAttributeSchema = mutate(entitySchema, null, existingCompoundSchema);
		return replaceSortableAttributeCompoundIfDifferent(
			entitySchema, existingCompoundSchema, Objects.requireNonNull(updatedAttributeSchema)
		);
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema, @Nonnull ConsistencyChecks consistencyChecks) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		final SortableAttributeCompoundSchemaContract existingCompoundSchema = getReferenceSortableAttributeCompoundSchemaOrThrow(entitySchema, referenceSchema, this.name);
		final SortableAttributeCompoundSchemaContract updatedAttributeSchema = mutate(entitySchema, null, existingCompoundSchema);
		return replaceSortableAttributeCompoundIfDifferent(
			referenceSchema, existingCompoundSchema, Objects.requireNonNull(updatedAttributeSchema)
		);
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
		return "Set sortable attribute compound `" + this.name + "` schema: " +
			", indexed=" + (isIndexed() ? "(in scopes: " + Arrays.toString(this.indexedInScopes) + ")" : "no");
	}
}
