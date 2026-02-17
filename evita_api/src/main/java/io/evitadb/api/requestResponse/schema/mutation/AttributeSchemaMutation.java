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

package io.evitadb.api.requestResponse.schema.mutation;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Marker interface for all schema mutations that alter {@link AttributeSchemaContract} definitions across the
 * evitaDB schema hierarchy.
 *
 * Attributes can appear in three distinct schema contexts, and this interface unifies mutation operations across all
 * of them:
 *
 * - **Global attributes** in {@link CatalogSchemaContract#getAttributes()} — shared across all entity types
 * - **Entity attributes** in {@link EntitySchemaContract#getAttributes()} — specific to one entity type
 * - **Reference attributes** in {@link ReferenceSchemaContract#getAttributes()} — associated with entity references
 *
 * **Mutation Scope**
 *
 * Implementations may modify entire schemas (e.g., creating or removing an attribute) or partially mutate
 * a single attribute (e.g., changing its description, type, or indexing configuration).
 *
 * **Key Implementations**
 *
 * Three specialized sub-interfaces provide context-specific behavior:
 *
 * - {@link io.evitadb.api.requestResponse.schema.mutation.attribute.GlobalAttributeSchemaMutation} — operates on
 * catalog-level global attributes
 * - {@link io.evitadb.api.requestResponse.schema.mutation.attribute.EntityAttributeSchemaMutation} — operates on
 * entity-level attributes
 * - {@link io.evitadb.api.requestResponse.schema.mutation.attribute.ReferenceAttributeSchemaMutation} — operates on
 * reference-level attributes
 *
 * Concrete mutations include `CreateAttributeSchemaMutation`, `ModifyAttributeSchemaTypeMutation`,
 * `SetAttributeSchemaFilterableMutation`, and `RemoveAttributeSchemaMutation`.
 *
 * **Thread-Safety**
 *
 * All implementations are immutable and thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
@ThreadSafe
public interface AttributeSchemaMutation extends SchemaMutation {

	/**
	 * Returns the name of the attribute schema targeted by this mutation.
	 *
	 * @return the attribute name, never `null`
	 */
	@Nonnull
	String getName();

	/**
	 * Applies the mutation operation on the attribute schema and returns the modified version. This method implements
	 * create, update, and remove operations using `null` as a sentinel value:
	 *
	 * - **Create**: `null` input → non-`null` output (new schema created)
	 * - **Modify**: non-`null` input → non-`null` output (existing schema modified)
	 * - **Remove**: non-`null` input → `null` output (schema deleted)
	 *
	 * The `catalogSchema` parameter provides access to shared global attributes, which may be referenced or inherited
	 * by entity or reference schemas. The `schemaType` parameter enables type-safe casting to the specific attribute
	 * schema subtype ({@link io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract},
	 * {@link io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract}, or
	 * {@link AttributeSchemaContract}).
	 *
	 * @param catalogSchema   owner catalog schema containing shared global attributes, may be `null` for
	 *                        entity-scoped or reference-scoped mutations
	 * @param attributeSchema current version of the attribute schema to mutate, may be `null` for create operations
	 * @param schemaType      expected runtime type of the attribute schema, used for type-safe casting
	 * @param <S>             attribute schema subtype (entity, global, or base attribute schema contract)
	 * @return the mutated attribute schema, or `null` if the mutation removes the schema
	 */
	@Nullable
	<S extends AttributeSchemaContract> S mutate(
		@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema, @Nonnull Class<S> schemaType);

}
