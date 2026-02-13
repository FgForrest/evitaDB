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

package io.evitadb.api.requestResponse.schema.mutation;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Interface marking entity schema mutations that can detect and resolve conflicts with other mutations in the
 * mutation pipeline, enabling intelligent mutation deduplication and merging.
 *
 * **Purpose and Design Intent**
 *
 * This interface was created to minimize the number of schema mutations applied to large databases. Schema mutations
 * (especially those affecting indexes) are expensive operations — creating an index only to immediately drop it due
 * to conflicting mutations wastes resources. By implementing this interface, mutations can examine the existing
 * pipeline and eliminate redundant or contradictory operations before execution.
 *
 * **Motivation: Avoiding Expensive Index Rebuilds**
 *
 * Consider a client that calls:
 * 1. `withAttribute("price").filterable()` — creates a filterable index for "price"
 * 2. `withAttribute("price").notFilterable()` — would drop the index
 * 3. `withAttribute("price").filterable()` — would recreate the index again
 *
 * Without combination logic, evitaDB would laboriously build the index, tear it down, and rebuild it from scratch.
 * With `CombinableLocalEntitySchemaMutation`, the builder detects the conflicting mutations and produces only the
 * minimal set of changes (in this case, just the final `filterable()` state).
 *
 * **Usage Context**
 *
 * This interface is primarily used by {@link EntitySchemaEditor.EntitySchemaBuilder}, which accumulates mutations as
 * the client calls builder methods. Before producing the final
 * {@link io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation}, the builder invokes
 * `combineWith()` on each new mutation against all existing mutations in the pipeline, allowing the new mutation to
 * replace, merge with, or coexist alongside existing mutations.
 *
 * **Common Combination Patterns**
 *
 * - **Replacement**: A new mutation replaces an existing one (e.g., `SetAttributeSchemaFilterableMutation` replaces
 * an earlier instance for the same attribute)
 * - **Cancellation**: A new mutation cancels an existing one (e.g., `RemoveReferenceSchemaMutation` cancels
 * `CreateReferenceSchemaMutation` for the same reference, leaving the schema unchanged)
 * - **No conflict**: Return `null` to indicate the mutations can coexist without changes
 *
 * **Thread-Safety**
 *
 * All implementations are expected to be immutable and thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see CombinableCatalogSchemaMutation
 */
public interface CombinableLocalEntitySchemaMutation extends LocalEntitySchemaMutation {

	/**
	 * Examines an existing mutation in the pipeline and optionally produces a combination result that describes how
	 * the pipeline should be modified to keep it minimal and free of redundant operations.
	 *
	 * This method is called by the schema builder when a new mutation is added. The new mutation (this instance) is
	 * compared against each existing mutation in the pipeline. If a conflict or redundancy is detected, this method
	 * returns a {@link MutationCombinationResult} describing which mutations to remove and which to add.
	 *
	 * @param currentCatalogSchema current catalog schema state, can be consulted for validation or context
	 * @param currentEntitySchema  current entity schema state, can be consulted for validation or context
	 * @param existingMutation     the existing mutation in the pipeline to compare against
	 * @return `null` if no changes are needed (mutations can coexist), or a `MutationCombinationResult` describing
	 * which mutations to discard and which to replace them with
	 * @see MutationCombinationResult
	 */
	@Nullable
	MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	);

}
