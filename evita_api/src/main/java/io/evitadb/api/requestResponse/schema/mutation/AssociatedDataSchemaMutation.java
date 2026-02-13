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

import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Marker interface for all schema mutations that alter {@link AssociatedDataSchemaContract} definitions within
 * entity schemas.
 *
 * Associated data is arbitrary, complex data attached to entities — typically documents, descriptions, or structured
 * metadata that is not indexed or queried directly. This interface unifies operations that create, modify, or remove
 * associated data schemas in {@link EntitySchemaContract#getAssociatedData()}.
 *
 * **Mutation Scope**
 *
 * Implementations may modify entire schemas (e.g., creating or removing an associated data definition) or partially
 * mutate a single associated data schema (e.g., changing its type, localization, or nullability).
 *
 * **Key Implementations**
 *
 * Concrete mutations include:
 *
 * - `CreateAssociatedDataSchemaMutation` — creates a new associated data schema
 * - `ModifyAssociatedDataSchemaTypeMutation` — changes the data type of existing associated data
 * - `SetAssociatedDataSchemaLocalizedMutation` — marks associated data as localized or non-localized
 * - `RemoveAssociatedDataSchemaMutation` — deletes an associated data schema
 *
 * **Usage Pattern**
 *
 * Associated data mutations are always entity-scoped (they implement
 * {@link io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation}). Unlike attributes, associated
 * data does not appear in global catalog schemas or reference schemas.
 *
 * **Thread-Safety**
 *
 * All implementations are immutable and thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
@ThreadSafe
public interface AssociatedDataSchemaMutation extends SchemaMutation {

	/**
	 * Returns the name of the associated data schema targeted by this mutation.
	 *
	 * @return the associated data name, never `null`
	 */
	@Nonnull
	String getName();

	/**
	 * Applies the mutation operation on the associated data schema and returns the modified version. This method
	 * implements create, update, and remove operations using `null` as a sentinel value:
	 *
	 * - **Create**: `null` input → non-`null` output (new schema created)
	 * - **Modify**: non-`null` input → non-`null` output (existing schema modified)
	 * - **Remove**: non-`null` input → `null` output (schema deleted)
	 *
	 * Modification operations validate constraints such as type compatibility and localization requirements before
	 * applying changes.
	 *
	 * @param associatedDataSchema current version of the schema to mutate, may be `null` for create operations
	 * @return the mutated associated data schema, or `null` if the mutation removes the schema
	 */
	@Nullable
	AssociatedDataSchemaContract mutate(@Nullable AssociatedDataSchemaContract associatedDataSchema);

}
