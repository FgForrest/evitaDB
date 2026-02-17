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

package io.evitadb.api.proxy;

import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;

/**
 * Provides write access to modify the scope of an entity through a proxy instance.
 *
 * This interface extends {@link WithScope} and adds a setter method that enables client code to change which
 * data set(s) an entity belongs to. Scope modifications are tracked via the entity builder and applied when
 * the entity is persisted.
 *
 * **Scope Transitions:**
 *
 * Common scope transitions include:
 * - **Archival**: Moving an entity from LIVE to ARCHIVED when it's no longer active
 * - **Restoration**: Moving an entity from ARCHIVED back to LIVE when reactivated
 * - **Multi-scope**: Adding an entity to multiple scopes (e.g., both LIVE and ARCHIVED)
 *
 * The semantics of scope changes depend on your application's requirements and evitaDB's scope configuration.
 *
 * **Mutation Tracking:**
 *
 * Calling {@link #setScope(Scope)} triggers the creation of an {@link io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder}
 * (if not already present) and records a scope mutation. The change is applied when the entity is upserted via
 * {@link io.evitadb.api.EvitaSessionContract#upsertEntity} or {@link io.evitadb.api.EvitaSessionContract#upsertEntityDeeply}.
 *
 * **Use Cases:**
 *
 * - **Lifecycle management**: Implement entity lifecycle states (draft → published → archived)
 * - **Soft delete**: Move entities to ARCHIVED scope instead of hard deletion
 * - **Staged rollouts**: Use custom scopes for A/B testing or gradual feature rollouts
 * - **Multi-tenant isolation**: Use scopes to partition data by customer or environment
 *
 * **Implementation Note:**
 *
 * When a client proxy contract implements this interface, the proxy infrastructure automatically provides
 * the implementation. The setter delegates to the entity builder to record the mutation.
 *
 * **Example Usage:**
 *
 * ```java
 * public interface Product extends WithScopeEditor {
 *     String getName();
 *     void setName(String name);
 *
 *     default void archive() {
 *         setScope(Scope.ARCHIVED);
 *     }
 *
 *     default void publish() {
 *         setScope(Scope.LIVE);
 *     }
 * }
 *
 * // Usage:
 * Product product = session.getEntity(Product.class, 1, ...);
 * product.archive();
 * session.upsertEntity(product);  // scope change is persisted
 * ```
 *
 * @see WithScope
 * @see io.evitadb.dataType.Scope
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface WithScopeEditor extends WithScope {

	/**
	 * Sets the scope of the entity, recording a mutation that will be applied on upsert.
	 *
	 * This method changes which data set(s) the entity belongs to. The change is tracked via the entity
	 * builder and persisted when {@link io.evitadb.api.EvitaSessionContract#upsertEntity} or
	 * {@link io.evitadb.api.EvitaSessionContract#upsertEntityDeeply} is called.
	 *
	 * **Effect:**
	 * - Creates an entity builder if not already present (marks the proxy as modified)
	 * - Records a scope mutation in the builder
	 * - The scope change takes effect in the database only after upserting
	 *
	 * **Multi-Scope Behavior:**
	 *
	 * evitaDB supports entities existing in multiple scopes simultaneously. Calling this method with a new
	 * scope may either:
	 * - Replace the current scope (single-scope mode)
	 * - Add to existing scopes (multi-scope mode)
	 *
	 * The exact behavior depends on your entity schema configuration and scope handling strategy.
	 *
	 * @param scope the new scope for the entity (cannot be null)
	 */
	void setScope(@Nonnull Scope scope);

}
