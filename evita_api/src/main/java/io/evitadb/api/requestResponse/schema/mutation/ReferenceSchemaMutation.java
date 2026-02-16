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

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Marker interface for mutations that alter reference schemas within an entity schema.
 *
 * **Purpose and Design Context**
 *
 * This interface identifies mutations that modify the reference schemas stored in
 * {@link EntitySchemaContract#getReferences()}. References define relationships between entities (e.g., a "Product"
 * entity might have references to "Brand", "Category", "Tag" entities). Reference schemas specify the target entity
 * type, cardinality constraints, indexing settings, faceting configuration, and reference-specific attributes.
 *
 * **Why It Extends Both LocalEntitySchemaMutation and ReferenceSchemaMutator**
 *
 * This interface combines two distinct behavioral contracts:
 *
 * - **LocalEntitySchemaMutation**: Marks this as a mutation that can be applied to an entity schema without
 *   specifying the entity type (it operates "locally" within the entity schema). This allows reference mutations to
 *   be accumulated in the {@link io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder} and
 *   packaged into a {@link io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation}.
 * - **ReferenceSchemaMutator**: Provides the `mutate()` method that accepts an `EntitySchemaContract` and a
 *   `ReferenceSchemaContract` and returns the mutated reference schema. This interface distinguishes mutations that
 *   operate on the reference schema itself (not its attributes or sortable compounds) and supports consistency
 *   checks via {@link ReferenceSchemaMutator.ConsistencyChecks}.
 *
 * **Usage Patterns**
 *
 * Reference schema mutations are created through the {@link io.evitadb.api.requestResponse.schema.EntitySchemaEditor}
 * builder API, typically via methods like `withReferenceTo()`, `withReferenceName()`, `withCardinality()`,
 * `withIndexed()`, `withFaceted()`, etc. Common implementations include:
 *
 * - {@link io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation}: Creates a new
 *   reference schema
 * - {@link io.evitadb.api.requestResponse.schema.mutation.reference.RemoveReferenceSchemaMutation}: Removes an
 *   existing reference schema
 * - {@link io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaCardinalityMutation}:
 *   Changes cardinality constraints
 * - {@link io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation}: Controls
 *   indexing settings
 * - {@link io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaNameMutation}: Renames the
 *   reference
 *
 * **Thread-Safety**
 *
 * All implementations are immutable and thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
@ThreadSafe
public interface ReferenceSchemaMutation extends LocalEntitySchemaMutation, ReferenceSchemaMutator {

	/**
	 * Returns the name of the reference schema targeted by this mutation.
	 *
	 * @return reference name (never `null`)
	 */
	@Nonnull
	String getName();

}
