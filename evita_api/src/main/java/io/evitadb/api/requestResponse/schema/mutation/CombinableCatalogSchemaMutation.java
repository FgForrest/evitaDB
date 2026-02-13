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
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Implementations of this interface signalize that they might conflict with other {@link CatalogSchemaMutation} in
 * the pipeline. Method {@link #combineWith(CatalogSchemaContract, CatalogSchemaMutation)} allows to examine each
 * of the pipeline mutation and react to it.
 *
 * The interface was created mainly for the needs of {@link CatalogSchemaEditor.CatalogSchemaBuilder} where it's in our
 * interest to create the least amount of schema mutations possible. Most of the schema mutation are usually quite
 * expensive on larger databases and if there is chance to avoid those, we should try to do that. Consider situation
 * when the client calls operations like `attribute` -> `not filterable`, `attribute` -> filterable by accident.
 * Should we drop laboriously built index on such attribute just to create it again from the scratch or should we
 * eliminate the opposite commands on the spot?! We chose the latter - the builder produces only the minimal set of
 * necessary changes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CombinableCatalogSchemaMutation extends LocalCatalogSchemaMutation {

	/**
	 * Examines an existing mutation in the pipeline and optionally produces a
	 * {@link MutationCombinationResult} describing changes needed to eliminate redundancy or resolve
	 * conflicts.
	 *
	 * This method is called by {@link io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper}
	 * for each new mutation being added to the builder pipeline. It compares the new mutation (this)
	 * against an existing mutation already in the pipeline, allowing mutations to:
	 * - Merge themselves with existing mutations (e.g., combine attribute modifications)
	 * - Cancel out conflicting mutations (e.g., make filterable + make not filterable = no-op)
	 * - Replace existing mutations with optimized variants
	 * - Signal that they should be discarded as redundant
	 *
	 * The combination logic can consult the current schema state to make intelligent decisions about
	 * whether mutations are truly conflicting or can coexist.
	 *
	 * @param currentCatalogSchema the current state of the schema that can be consulted for
	 *                             context-aware combination decisions
	 * @param existingMutation     the existing mutation in the pipeline to check for conflicts or
	 *                             combination opportunities
	 * @return NULL if the pipeline should not be changed, or a {@link MutationCombinationResult}
	 * describing how to modify the pipeline (replace, remove, or split mutations)
	 * @see MutationCombinationResult
	 */
	@Nullable
	MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull LocalCatalogSchemaMutation existingMutation
	);

}
