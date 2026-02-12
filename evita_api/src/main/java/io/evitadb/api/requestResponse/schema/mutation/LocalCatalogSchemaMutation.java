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

package io.evitadb.api.requestResponse.schema.mutation;

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.catalog.MutationEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Marks catalog schema mutations that can be applied locally to an already-identified catalog
 * schema instance without requiring explicit catalog name specification.
 *
 * Local catalog mutations operate on a known schema instance passed as a parameter, in contrast
 * to top-level mutations (like {@link io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation})
 * which must carry the catalog name and are executed at the evitaDB engine level. This design
 * enables efficient mutation batching and conflict resolution within schema builders.
 *
 * **Architecture Pattern:**
 *
 * Local mutations follow a two-tier execution model:
 * - **Builder level:** Multiple local mutations are accumulated and potentially
 * combined/optimized
 * - **Engine level:** Accumulated mutations are wrapped in {@link ModifyCatalogSchemaMutation}
 * with the catalog name and submitted for transactional execution
 *
 * **Entity Schema Access:**
 *
 * The {@link #mutate(CatalogSchemaContract, EntitySchemaProvider)} method variant accepts an
 * `entitySchemaAccessor` parameter, allowing mutations to query and update entity schemas within
 * the catalog during mutation application. This is essential for operations like creating or
 * modifying entity collections within the catalog.
 *
 * **Change Data Capture Integration:**
 *
 * Local catalog mutations override {@link #toChangeCatalogCapture} to advance the mutation index,
 * treating each local mutation as a separate unit for CDC tracking. This ensures fine-grained
 * change capture for each individual schema modification.
 *
 * **Typical Implementations:**
 * - {@link io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation}
 * - {@link io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 * @see ModifyCatalogSchemaMutation
 * @see CombinableCatalogSchemaMutation
 */
public interface LocalCatalogSchemaMutation extends CatalogSchemaMutation {

	/**
	 * Default implementation that delegates to the two-parameter
	 * {@link #mutate(CatalogSchemaContract, EntitySchemaProvider)} method using a singleton immutable
	 * entity schema accessor. Implementations should override the two-parameter variant.
	 *
	 * @param catalogSchema current version of the catalog schema
	 * @return modified catalog schema with potential impact on entity schemas
	 */
	@Nullable
	@Override
	default CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema) {
		return mutate(
			Objects.requireNonNull(catalogSchema),
			MutationEntitySchemaAccessor.INSTANCE
		);
	}

	/**
	 * Applies the mutation operation on the catalog schema and returns the modified version.
	 * Create operations work with NULL input and produce non-NULL result, remove operations produce
	 * the opposite. Modification operations always accept and produce non-NULL values.
	 *
	 * The `entitySchemaAccessor` parameter enables mutations to query existing entity schemas and
	 * track changes to entity schemas during mutation application. Implementations like
	 * {@link io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation}
	 * use this to register newly created entity schemas, while mutations that modify entity schemas
	 * use it to access current entity schema state for validation or transformation.
	 *
	 * @param catalogSchema        current version of the schema as an input to mutate
	 * @param entitySchemaAccessor entity schema provider allowing to access and update the list of
	 *                             entity schemas in the catalog during mutation application
	 * @return modified catalog schema with potential impact on entity schemas
	 */
	@Nullable
	CatalogSchemaWithImpactOnEntitySchemas mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaProvider entitySchemaAccessor
	);

	/**
	 * Overrides the default implementation to advance the {@link MutationPredicateContext} index,
	 * treating each {@link LocalCatalogSchemaMutation} as a separate mutation unit for change data
	 * capture tracking.
	 *
	 * This ensures that when multiple local catalog mutations are batched together in a
	 * {@link ModifyCatalogSchemaMutation}, each mutation is tracked individually with its own
	 * index position in the CDC stream. This granularity is essential for incremental replication
	 * and debugging mutation sequences.
	 *
	 * @param predicate the predicate to be used for filtering mutation items
	 * @param content   the requested content of the capture
	 * @return stream of {@link ChangeCatalogCapture} items representing this mutation
	 */
	@Override
	@Nonnull
	default Stream<ChangeCatalogCapture> toChangeCatalogCapture(
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content
	) {
		predicate.getContext().advance();
		return CatalogSchemaMutation.super.toChangeCatalogCapture(predicate, content);
	}

}
