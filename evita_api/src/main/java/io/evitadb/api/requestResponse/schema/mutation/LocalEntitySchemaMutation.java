/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

/**
 * Marker interface that distinguishes local entity schema mutations from top-level entity schema mutations.
 *
 * **Purpose and Design Context**
 *
 * This interface identifies mutations that can be applied directly to an {@link EntitySchemaContract} without
 * needing to specify the entity type. These "local" mutations operate on a single entity schema's internal structure
 * (attributes, references, associated data, sortable compounds, evolution modes, currencies, locales, etc.) and are
 * typically composed together inside a top-level wrapper mutation.
 *
 * **Distinction from Top-Level Mutations**
 *
 * - **Local mutations** (implementing this interface): modify entity schema internals and do not carry the entity
 * type name — examples: `CreateAttributeSchemaMutation`, `ModifyReferenceSchemaCardinalityMutation`,
 * `AllowCurrencyInEntitySchemaMutation`.
 * - **Top-level mutations**: wrap local mutations and specify which entity type to target — example:
 * {@link io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation} contains an entity type
 * name and an array of `LocalEntitySchemaMutation` instances to apply.
 *
 * **Usage Pattern**
 *
 * When using the {@link io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder}, local
 * mutations are accumulated during builder method calls. Upon calling `toMutation()`, the builder packages all
 * accumulated local mutations into a single `ModifyEntitySchemaMutation` that carries the entity type name and
 * delegates mutation execution to each local mutation in sequence.
 *
 * **Key Implementors**
 *
 * - Attribute schema mutations: {@link io.evitadb.api.requestResponse.schema.mutation.attribute}
 * - Associated data schema mutations: {@link io.evitadb.api.requestResponse.schema.mutation.associatedData}
 * - Reference schema mutations: {@link io.evitadb.api.requestResponse.schema.mutation.reference} (via
 * {@link ReferenceSchemaMutation})
 * - Sortable compound schema mutations:
 * {@link io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound}
 * - Entity-level settings: `AllowCurrencyInEntitySchemaMutation`, `SetEntitySchemaWithHierarchyMutation`,
 * `ModifyEntitySchemaDescriptionMutation`, etc.
 *
 * **Thread-Safety**
 *
 * All implementations are expected to be immutable and thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface LocalEntitySchemaMutation extends EntitySchemaMutation {
}
