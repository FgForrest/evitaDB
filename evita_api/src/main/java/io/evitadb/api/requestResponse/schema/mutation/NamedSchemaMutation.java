/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import javax.annotation.Nonnull;

/**
 * Marker interface for schema mutations that target a named schema container.
 *
 * This interface enables the mutation framework to route schema changes to the correct container within a schema
 * hierarchy. A "container" is any named schema element that can be independently mutated, such as:
 *
 * - **Entity schemas** — identified by entity type name (e.g., `"Product"`, `"Category"`)
 * - **Attribute schemas** — identified by attribute name (e.g., `"code"`, `"name"`)
 * - **Associated data schemas** — identified by associated data name (e.g., `"description"`, `"images"`)
 * - **Reference schemas** — identified by reference name (e.g., `"category"`, `"brand"`)
 * - **Sortable attribute compounds** — identified by compound name (e.g., `"priority"`, `"categoryAndPrice"`)
 *
 * **Purpose and Usage**
 *
 * Implementations of this interface expose the target container name via {@link #containerName()}, which the mutation
 * processing pipeline uses to:
 *
 * - **Validate mutations** — ensure mutations target existing schema elements
 * - **Route mutations** — dispatch mutations to the correct schema component
 * - **Detect conflicts** — identify concurrent mutations targeting the same container
 * - **Generate error messages** — provide clear diagnostics when mutations fail
 *
 * **Implementation Pattern**
 *
 * Most schema mutation abstract base classes implement this interface and delegate `containerName()` to the
 * mutation's primary identifier field:
 *
 * - `AbstractAttributeSchemaMutation` → returns `name` (the attribute name)
 * - `AbstractAssociatedDataSchemaMutation` → returns `name` (the associated data name)
 * - `AbstractReferenceDataSchemaMutation` → returns `name` (the reference name)
 * - `AbstractSortableAttributeCompoundSchemaMutation` → returns `name` (the compound name)
 * - `CreateEntitySchemaMutation` / `ModifyEntitySchemaMutation` → returns entity type name
 *
 * **Example Usage**
 *
 * When processing a mutation pipeline, the engine can filter mutations by container:
 *
 * ```java
 * mutations.stream()
 * .filter(m -> m instanceof NamedSchemaMutation)
 * .map(m -> (NamedSchemaMutation) m)
 * .filter(m -> m.containerName().equals("Product"))
 * .forEach(this::applyMutation);
 * ```
 *
 * **Thread-Safety**
 *
 * All implementations are immutable and thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface NamedSchemaMutation extends SchemaMutation {

	/**
	 * Returns the name of the schema container targeted by this mutation. The container name corresponds to the
	 * natural identifier of the schema element being mutated:
	 *
	 * - For attribute mutations: the attribute name (e.g., `"code"`, `"name"`)
	 * - For associated data mutations: the associated data name (e.g., `"description"`)
	 * - For reference mutations: the reference name (e.g., `"category"`)
	 * - For sortable compound mutations: the compound name (e.g., `"priority"`)
	 * - For entity mutations: the entity type name (e.g., `"Product"`)
	 *
	 * @return the container name, never `null`
	 */
	@Nonnull
	String containerName();

}
