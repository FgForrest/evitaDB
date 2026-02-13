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

package io.evitadb.api;

import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;

import javax.annotation.Nonnull;

/**
 * Callback interface that enables customization of entity and catalog schemas generated from annotated Java classes
 * before they are applied to the evitaDB catalog. This interface is primarily used with the
 * {@link EvitaSessionContract#defineEntitySchemaFromModelClass(Class, SchemaPostProcessor)} method.
 *
 * **Purpose and Usage**
 *
 * When evitaDB generates entity schemas from Java classes annotated with `@Entity`, `@Attribute`, `@Reference`, and
 * related annotations via {@link io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer}, the resulting schema
 * is passed through this post-processor before being committed to the catalog. This allows programmatic schema
 * adjustments that go beyond what annotations can express.
 *
 * **Common Use Cases**
 *
 * - Adding indexes or constraints not expressible via annotations
 * - Conditionally modifying schema based on runtime configuration
 * - Applying global schema conventions or policies
 * - Generating derived attributes or associated data definitions
 * - Adjusting attribute properties (sortability, filterability, localization)
 *
 * **Execution Context**
 *
 * The `postProcess()` method is invoked by {@link io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer} after
 * analyzing all annotations on the model class and constructing initial schema builders, but before the schema is
 * sealed and applied to the catalog. Modifications made via the provided builders are included in the final schema.
 *
 * **Thread-Safety**
 *
 * Implementations do not need to be thread-safe — each invocation receives dedicated builder instances that are not
 * shared across threads.
 *
 * **Example Usage**
 *
 * ```
 * SealedEntitySchema schema = session.defineEntitySchemaFromModelClass(
 * Product.class,
 * (catalogBuilder, entityBuilder) -> {
 * // Add a global attribute to all entities
 * entityBuilder.withAttribute("createdAt", OffsetDateTime.class, thatIs -> thatIs.sortable());
 *
 * // Modify reference schema to include additional indexes
 * entityBuilder.withReferenceTo("categories", "Category", Cardinality.ZERO_OR_MORE, thatIs ->
 * thatIs.indexed()
 * );
 * }
 * );
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 * @see SchemaPostProcessorCapturingResult for capturing the final set of mutations
 * @see io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer
 * @see EvitaSessionContract#defineEntitySchemaFromModelClass(Class, SchemaPostProcessor)
 */
public interface SchemaPostProcessor {

	/**
	 * Invoked after the schema is generated from the annotated model class and before it is applied to the catalog.
	 * Implementations can modify both the catalog schema and entity schema using the provided builder instances.
	 *
	 * Mutations performed on these builders are collected and applied atomically to the catalog schema. If the entity
	 * schema does not yet exist, it will be created; if it exists, it will be updated with the changes.
	 *
	 * @param catalogSchemaBuilder mutable builder for modifying catalog-level schema (global attributes, naming
	 *                             conventions)
	 * @param entitySchemaBuilder  mutable builder for modifying entity-level schema (attributes, associated data,
	 *                             references, indexes)
	 */
	void postProcess(
		@Nonnull CatalogSchemaBuilder catalogSchemaBuilder, @Nonnull EntitySchemaBuilder entitySchemaBuilder);

}
