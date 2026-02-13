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

import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;

/**
 * Marker interface combining {@link CatalogSchemaMutation} and {@link TopLevelCatalogMutation} for
 * mutations that must execute at the evitaDB engine level while also modifying catalog schema.
 *
 * This interface exists to support the dual nature of certain catalog mutations that need both:
 * - Engine-level execution privileges (catalog lifecycle operations, cross-catalog access)
 * - Schema modification capabilities (implementing {@link CatalogSchemaMutation#mutate})
 *
 * **Why This Marker Exists:**
 *
 * Without this combined interface, implementations would need to implement both parent interfaces
 * separately, leading to ambiguity in type checking and polymorphic dispatch. By combining them
 * into a single marker, we enable:
 * - **Type-safe polymorphism:** Code can check `instanceof TopLevelCatalogSchemaMutation` to
 *   identify mutations that require both engine-level execution and schema modification
 * - **Clear architectural intent:** The marker explicitly signals that these mutations operate at
 *   the boundary between catalog lifecycle management and schema evolution
 * - **Simplified mutation routing:** The engine's mutation dispatcher can route these mutations
 *   to the appropriate execution pipeline based on a single type check
 *
 * **Practical Distinction:**
 *
 * The key difference from implementing both interfaces separately is semantic: this marker
 * indicates that the mutation fundamentally requires BOTH capabilities simultaneously (not just
 * optionally supporting them). For example:
 * - {@link io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation}
 *   wraps local mutations and must execute at engine level
 * - {@link io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation}
 *   creates a catalog and its initial schema in a single atomic engine-level operation
 * - {@link io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation}
 *   removes a catalog and updates schema metadata atomically
 *
 * In contrast, pure {@link TopLevelCatalogMutation} implementations (like
 * {@link io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation}) don't
 * modify schema, and pure {@link LocalCatalogSchemaMutation} implementations operate within an
 * existing catalog session without engine-level privileges.
 *
 * **Typical Implementations:**
 * - {@link io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.engine.RestoreCatalogSchemaMutation}
 *
 * @param <T> the type of result produced by the mutation's progress future
 * @see TopLevelCatalogMutation
 * @see CatalogSchemaMutation
 * @see LocalCatalogSchemaMutation
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface TopLevelCatalogSchemaMutation<T> extends CatalogSchemaMutation, TopLevelCatalogMutation<T> {

}
