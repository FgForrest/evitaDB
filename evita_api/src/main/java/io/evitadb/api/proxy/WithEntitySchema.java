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

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;

/**
 * Provides access to the entity schema of the wrapped entity instance.
 *
 * This interface can be implemented by client-defined proxy contracts (interfaces, abstract classes, POJOs)
 * when they need to access the {@link EntitySchemaContract} of the underlying entity. The entity schema
 * contains metadata about the entity type, including:
 * - Defined attributes and their types/constraints
 * - Associated data schemas
 * - Reference schemas
 * - Price configuration
 * - Localization settings
 * - Evolution history
 *
 * **Use Cases:**
 *
 * Access to the entity schema is useful for:
 * - **Dynamic behavior**: Implementing generic methods that adapt based on schema configuration (e.g.,
 *   listing all attributes, checking if a specific attribute exists)
 * - **Validation**: Verifying that operations comply with schema constraints before attempting them
 * - **Introspection**: Building UI components or documentation from schema metadata
 * - **Schema evolution**: Programmatically checking schema version and capabilities
 *
 * **Implementation Note:**
 *
 * When a client proxy contract implements this interface, the proxy infrastructure automatically provides
 * the implementation. The method returns the schema of the underlying entity's collection.
 *
 * **Example Usage:**
 *
 * ```java
 * public interface Product extends WithEntitySchema {
 *     String getName();
 *
 *     default Set<String> getAvailableLocales() {
 *         return entitySchema().getLocales();
 *     }
 *
 *     default boolean isAttributeDefined(String attributeName) {
 *         return entitySchema().getAttribute(attributeName).isPresent();
 *     }
 * }
 * ```
 *
 * @see WithEntityContract
 * @see WithLocales
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface WithEntitySchema {

	/**
	 * Returns the entity schema of the underlying entity.
	 *
	 * The schema is immutable and represents the current definition of the entity type at the time the entity
	 * was retrieved. If the schema evolves after the entity is fetched, this method continues to return the
	 * snapshot from the time of retrieval (entities carry their schema version).
	 *
	 * @return the entity schema defining the structure and constraints of this entity type
	 */
	@Nonnull
	EntitySchemaContract entitySchema();

}
