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

package io.evitadb.api.proxy;

import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Provides access to the entity builder for capturing mutations on a proxy instance.
 *
 * This interface extends {@link WithEntityContract} and adds builder-related methods that enable client code
 * to modify entity data through proxy methods (setters, adders, removers) and then persist those changes
 * via {@link EvitaSessionContract#upsertEntity} or {@link EvitaSessionContract#upsertEntityDeeply}.
 *
 * **Mutation Capture Mechanism:**
 *
 * When a proxy instance is created, it starts in read-only mode. When the first mutation method is called
 * (e.g., `product.setName("new name")`), the proxy internally calls `entity().openForWrite()` to create
 * an {@link EntityBuilder} that captures all subsequent mutations. These mutations are then applied when
 * the entity is upserted.
 *
 * **Usage Context:**
 *
 * This interface is automatically implemented by proxies created in "editable" mode (when the underlying
 * entity supports mutation). Client proxy contracts can implement this interface to access the builder
 * directly for advanced scenarios (e.g., bulk operations, conditional mutations).
 *
 * **Builder Lifecycle:**
 *
 * - **Initial state**: No builder exists (`entityBuilderIfPresent()` returns empty)
 * - **After first mutation**: Builder is created and captures all mutations
 * - **After upsert**: Builder mutations are applied, but the builder remains in the proxy for potential
 *   further modifications
 *
 * **Example Usage:**
 *
 * ```java
 * public interface Product extends WithEntityBuilder {
 *     void setName(String name);
 *
 *     default void resetAllAttributes() {
 *         entityBuilder().removeAllAttributes();
 *     }
 * }
 * ```
 *
 * @see WithEntityContract
 * @see SealedEntityProxy
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface WithEntityBuilder extends WithEntityContract {

	/**
	 * Returns the entity builder instance that captures mutations performed on this proxy.
	 *
	 * If no mutations have been performed yet, this method creates a new builder by calling
	 * `entity().openForWrite()`. All subsequent mutation operations on the proxy will be delegated to this
	 * builder.
	 *
	 * **Side Effect:**
	 *
	 * Calling this method will create the builder if it doesn't exist, which marks the proxy as "modified"
	 * even if no actual changes are made. Use {@link #entityBuilderIfPresent()} if you want to check for
	 * modifications without triggering builder creation.
	 *
	 * @return existing builder if mutations were already performed, or newly created builder otherwise
	 */
	@Nonnull
	EntityBuilder entityBuilder();

	/**
	 * Returns the entity builder if it has been created (i.e., if any mutation methods were called on the proxy).
	 *
	 * This method allows checking whether the proxy has been modified without triggering builder creation.
	 * It's useful for conditional logic that should only execute when changes have been made.
	 *
	 * **Use Cases:**
	 * - Checking if proxy was modified before deciding whether to upsert
	 * - Avoiding unnecessary builder creation in read-only scenarios
	 * - Implementing conditional persistence logic
	 *
	 * @return the entity builder if mutations were performed, empty otherwise
	 */
	@Nonnull
	Optional<? extends EntityBuilder> entityBuilderIfPresent();

}
