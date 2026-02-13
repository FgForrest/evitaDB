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

/**
 * Provides access to the version number of an entity for optimistic locking and change tracking.
 *
 * This interface can be implemented by client-defined proxy contracts when they need to access or work with
 * entity version information. evitaDB automatically increments the version number on every mutation, enabling:
 * - Optimistic locking to detect concurrent modifications
 * - Change tracking for audit and synchronization scenarios
 * - Cache invalidation based on version changes
 *
 * **Version Semantics:**
 *
 * - **Initial version**: Newly created entities start at version 1
 * - **Incremental**: Every successful upsert increments the version by 1
 * - **Monotonic**: Version numbers never decrease (strictly increasing)
 * - **Per-entity**: Each entity has its own independent version counter
 *
 * **Optimistic Locking:**
 *
 * The version field enables optimistic locking patterns where:
 * 1. Client fetches entity and reads version N
 * 2. Client modifies entity locally
 * 3. Client attempts to upsert with expected version N
 * 4. Database accepts upsert only if current version is still N, otherwise rejects
 *
 * This prevents lost updates when multiple clients modify the same entity concurrently.
 *
 * **Use Cases:**
 *
 * - **Conflict detection**: Detect when another client modified the entity since it was fetched
 * - **Change tracking**: Monitor when entities were last modified by comparing versions
 * - **Synchronization**: Use version as a watermark for incremental sync operations
 * - **Cache invalidation**: Invalidate cached entities when version changes
 * - **Audit trails**: Track modification frequency or rate of change
 *
 * **Implementation Note:**
 *
 * When a client proxy contract implements this interface, the proxy infrastructure automatically provides
 * the implementation by delegating to the underlying entity's version tracking.
 *
 * **Example Usage:**
 *
 * ```java
 * public interface Product extends WithVersion {
 *     String getName();
 *     void setName(String name);
 *
 *     default boolean hasBeenModified(int expectedVersion) {
 *         return version() != expectedVersion;
 *     }
 * }
 *
 * // Usage for optimistic locking:
 * Product product = session.getEntity(Product.class, 1, ...);
 * int originalVersion = product.version();
 * product.setName("Updated Name");
 * try {
 *     session.upsertEntity(product);  // fails if version changed meanwhile
 * } catch (ConcurrentModificationException e) {
 *     // handle conflict - refetch and retry
 * }
 * ```
 *
 * @see WithEntityContract
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface WithVersion {

	/**
	 * Returns the version number of the entity.
	 *
	 * The version is a positive integer that starts at 1 for newly created entities and increments by 1 on
	 * every successful mutation. The version is managed automatically by evitaDB and cannot be set directly
	 * by client code.
	 *
	 * **Post-Upsert Updates:**
	 *
	 * After upserting an entity, the version in the proxy instance is automatically updated to reflect the
	 * new version assigned by the database. This ensures the proxy always has the latest version number.
	 *
	 * **Edge Cases:**
	 *
	 * - For entities that haven't been persisted yet (no primary key), the version may be 0 or 1 depending
	 *   on whether the builder was created
	 * - Version is scoped per entity instance, not per entity type
	 *
	 * @return the version number (positive integer, typically >= 1 for persisted entities)
	 */
	int version();

}
