/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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


import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;

import javax.annotation.Nonnull;

/**
 * Marks mutations that must be executed at the entire evitaDB engine level rather than locally
 * within a single catalog session.
 *
 * This interface serves as a marker for engine-level mutations that target a specific catalog and
 * provides the catalog name via {@link #getCatalogName()}. Top-level mutations are executed by
 * the evitaDB engine itself (via {@link io.evitadb.api.EvitaContract}), not by individual catalog
 * sessions, enabling operations that require cross-catalog coordination, catalog lifecycle
 * management, or engine-level transactional guarantees.
 *
 * **Design Rationale:**
 *
 * The distinction between top-level and local mutations exists for several architectural reasons:
 * - **Catalog lifecycle:** Operations like creating, removing, or renaming catalogs must be
 *   executed at the engine level since no catalog session exists yet (or anymore)
 * - **Transactional scope:** Top-level mutations participate in engine-level transactions with
 *   different concurrency and isolation guarantees than catalog-local mutations
 * - **State management:** Engine-level mutations can modify the catalog registry and manage
 *   catalog state transitions (alive, warming up, shutdown)
 * - **Cross-catalog operations:** Some operations (like catalog duplication) require accessing
 *   multiple catalogs, which is only possible at the engine level
 *
 * **Execution Context:**
 *
 * Top-level mutations are submitted to {@link io.evitadb.api.EvitaContract#update(io.evitadb.api.requestResponse.mutation.Mutation...)}
 * and executed by the engine's mutation processing pipeline. They bypass catalog-local transaction
 * managers and schema builders, operating directly on the engine's catalog registry.
 *
 * **Typical Implementations:**
 * - {@link io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation}
 *
 * @param <T> the type of result produced by the mutation's progress future
 * @see TopLevelCatalogSchemaMutation
 * @see LocalCatalogSchemaMutation
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface TopLevelCatalogMutation<T> extends EngineMutation<T> {

	/**
	 * Returns the name of the catalog targeted by this mutation.
	 *
	 * This name is used by the engine to locate the target catalog in its registry and determine
	 * which catalog's locks and transactional context should be used for mutation execution.
	 *
	 * @return the catalog name
	 * @see CatalogSchemaContract#getName()
	 */
	@Nonnull
	String getCatalogName();

}
