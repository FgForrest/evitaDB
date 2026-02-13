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

package io.evitadb.api.requestResponse.schema.mutation;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Marker interface for all schema mutations that alter {@link SortableAttributeCompoundSchemaContract} definitions
 * within entity and reference schemas.
 *
 * Sortable attribute compounds are named compositions of multiple attributes that define precomputed, efficient sort
 * orders. They allow queries to sort entities by multiple fields (e.g., "sort by category, then price, then name")
 * without computing the order at query time. This interface unifies operations that create, modify, or remove
 * sortable attribute compound schemas.
 *
 * **Mutation Scope**
 *
 * Sortable attribute compounds can appear in two contexts:
 *
 * - **Entity-level compounds** in {@link EntitySchemaContract#getSortableAttributeCompounds()} — apply to entities
 * directly
 * - **Reference-level compounds** in {@link ReferenceSchemaContract#getSortableAttributeCompounds()} — apply to
 * referenced entities
 *
 * Implementations may modify entire schemas (e.g., creating or removing a compound) or partially mutate a single
 * compound (e.g., changing its name, description, or constituent attribute order).
 *
 * **Key Implementations**
 *
 * Concrete mutations include:
 *
 * - `CreateSortableAttributeCompoundSchemaMutation` — creates a new sortable compound
 * - `ModifySortableAttributeCompoundSchemaNameMutation` — renames an existing compound
 * - `SetSortableAttributeCompoundSchemaIndexedMutation` — controls indexing behavior
 * - `RemoveSortableAttributeCompoundSchemaMutation` — deletes a compound
 *
 * **Thread-Safety**
 *
 * All implementations are immutable and thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
@ThreadSafe
public interface SortableAttributeCompoundSchemaMutation extends SchemaMutation {

	/**
	 * Returns the name of the sortable attribute compound schema targeted by this mutation.
	 *
	 * @return the compound name, never `null`
	 */
	@Nonnull
	String getName();

	/**
	 * Applies the mutation operation on the sortable attribute compound schema and returns the modified version. This
	 * method implements create, update, and remove operations using `null` as a sentinel value:
	 *
	 * - **Create**: `null` input → non-`null` output (new schema created)
	 * - **Modify**: non-`null` input → non-`null` output (existing schema modified)
	 * - **Remove**: non-`null` input → `null` output (schema deleted)
	 *
	 * The `entitySchema` parameter provides validation context (e.g., verifying that constituent attributes exist).
	 * The `referenceSchema` parameter is non-`null` only when mutating a reference-level compound; it is `null` for
	 * entity-level compounds.
	 *
	 * @param entitySchema    owner entity schema used for validation and error messages, never `null`
	 * @param referenceSchema owner reference schema used for validation, or `null` if this is an entity-level compound
	 * @param existingSchema  current version of the schema to mutate, may be `null` for create operations
	 * @param <T>             sortable attribute compound schema subtype (entity or reference compound)
	 * @return the mutated sortable attribute compound schema, or `null` if the mutation removes the schema
	 */
	@Nullable
	<T extends SortableAttributeCompoundSchemaContract> T mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable T existingSchema
	);

	/**
	 * Replaces an existing sortable attribute compound schema with an updated one, but only if the schemas differ.
	 * This utility method optimizes schema versioning by avoiding unnecessary entity schema rebuilds when the
	 * mutation does not actually change the compound definition.
	 *
	 * If `existingSchema.equals(updatedSchema)` returns `true`, the original `entitySchema` is returned unchanged.
	 * Otherwise, a new entity schema is built with incremented version and the updated compound substituted.
	 *
	 * **Implementation Note**
	 *
	 * This method rebuilds the entity schema using {@link EntitySchema#_internalBuild}, copying all schema
	 * components except the sortable attribute compounds collection, which is reconstructed with the updated
	 * compound replacing the old one.
	 *
	 * @param entitySchema   the entity schema containing the compound, never `null`
	 * @param existingSchema the current version of the compound schema, never `null`
	 * @param updatedSchema  the new version of the compound schema, never `null`
	 * @return the original entity schema if no change occurred, or a new schema with the updated compound
	 */
	@Nonnull
	default EntitySchemaContract replaceSortableAttributeCompoundIfDifferent(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EntitySortableAttributeCompoundSchemaContract existingSchema,
		@Nonnull EntitySortableAttributeCompoundSchemaContract updatedSchema
	) {
		if (existingSchema.equals(updatedSchema)) {
			// we don't need to update entity schema - the associated data already contains the requested change
			return entitySchema;
		} else {
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
						entitySchema.getSortableAttributeCompounds()
							.values()
							.stream()
							.filter(it -> !updatedSchema.getName().equals(it.getName())),
						Stream.of(updatedSchema)
					)
					.collect(
						Collectors.toMap(
							EntitySortableAttributeCompoundSchemaContract::getName,
							Function.identity()
						)
					)
			);
		}
	}

}
