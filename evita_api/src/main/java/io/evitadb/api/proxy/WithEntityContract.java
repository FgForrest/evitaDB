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

import io.evitadb.api.requestResponse.data.EntityContract;

import javax.annotation.Nonnull;

/**
 * Provides direct access to the underlying sealed entity wrapped by a proxy instance.
 *
 * This interface can be implemented by client-defined proxy contracts (interfaces, abstract classes, POJOs)
 * when they need to access the raw {@link EntityContract} instance. This is useful for:
 * - Accessing entity data that doesn't have a corresponding proxy method
 * - Performing operations that require the low-level entity API
 * - Debugging and inspection of the underlying entity state
 * - Integration with code that works directly with {@link EntityContract}
 *
 * **Implementation Note:**
 *
 * When a client proxy contract implements this interface, the proxy infrastructure automatically provides
 * the implementation. The method delegates to the proxy state object which holds the wrapped entity.
 *
 * **Example Usage:**
 *
 * ```java
 * public interface Product extends WithEntityContract {
 *     String getName();
 *
 *     default boolean hasAttribute(String name) {
 *         return entity().getAttributeValue(name) != null;
 *     }
 * }
 * ```
 *
 * @see WithEntityBuilder
 * @see SealedEntityProxy
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface WithEntityContract {

	/**
	 * Returns the underlying sealed entity that is wrapped by this proxy instance.
	 *
	 * The returned entity is either a {@link io.evitadb.api.requestResponse.data.SealedEntity} for read-only
	 * proxies or an {@link io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder} wrapper for
	 * mutable proxies. The entity contains all the data that was fetched according to the query requirements.
	 *
	 * @return the underlying sealed entity (never a proxy, always the raw entity instance)
	 */
	@Nonnull
	EntityContract entity();

}
