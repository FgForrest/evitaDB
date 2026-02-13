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
 * Provides read-only access to the scope information of an entity.
 *
 * This interface can be implemented by client-defined proxy contracts when they need to check the entity's
 * scope. The {@link Scope} enum defines which data set the entity belongs to, typically distinguishing between
 * live/active entities and archived/historical entities.
 *
 * **Scope Semantics:**
 *
 * evitaDB supports multiple scopes for entities, allowing the same entity to exist in different data sets
 * simultaneously. Common use cases:
 * - **LIVE**: Active entities visible to end users
 * - **ARCHIVED**: Historical entities no longer active but retained for audit/compliance
 * - Custom scopes: Application-specific data partitions
 *
 * Scopes are typically used in queries via `scope(Scope.LIVE)` constraint to filter which data set to search.
 * An entity can be present in multiple scopes with potentially different attribute values per scope.
 *
 * **Use Cases:**
 *
 * - **Conditional rendering**: Hide archived entities in production UI but show in admin interfaces
 * - **Audit trails**: Display entity history by fetching both LIVE and ARCHIVED scopes
 * - **State-aware operations**: Implement different business logic based on entity scope
 * - **Compliance**: Track when entities transition from live to archived state
 *
 * **Implementation Note:**
 *
 * When a client proxy contract implements this interface, the proxy infrastructure automatically provides
 * the implementation by delegating to the underlying entity's scope information.
 *
 * **Example Usage:**
 *
 * ```java
 * public interface Product extends WithScope {
 *     String getName();
 *
 *     default boolean isActive() {
 *         return getScope() == Scope.LIVE;
 *     }
 *
 *     default boolean isArchived() {
 *         return getScope() == Scope.ARCHIVED;
 *     }
 * }
 * ```
 *
 * @see WithScopeEditor
 * @see io.evitadb.dataType.Scope
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface WithScope {

	/**
	 * Returns the scope of this entity instance.
	 *
	 * The scope indicates which data set this entity belongs to within evitaDB. An entity can exist in multiple
	 * scopes simultaneously (e.g., both LIVE and ARCHIVED), but each proxy instance represents the entity in
	 * a specific scope as determined by the query that fetched it.
	 *
	 * **Query Context:**
	 *
	 * The returned scope matches the scope specified in the query via `scope(Scope.LIVE)` or similar constraint.
	 * If no scope constraint was specified in the query, the default scope (typically LIVE) is returned.
	 *
	 * @return the scope of this entity instance (never null)
	 */
	@Nonnull
	Scope getScope();

}
