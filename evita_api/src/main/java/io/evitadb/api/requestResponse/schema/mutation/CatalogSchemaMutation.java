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

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.mutation.StreamDirection;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Marks all implementations that alter the {@link CatalogSchemaContract}. This interface sits in the schema mutation
 * hierarchy between the root {@link SchemaMutation} interface and the more specialized subinterfaces:
 *
 * - {@link LocalCatalogSchemaMutation}: Mutations that can be applied locally to an already-identified catalog schema
 * instance. These mutations don't specify the target catalog name themselves and must be wrapped in a
 * {@link io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation} when sent to the server.
 * - {@link TopLevelCatalogSchemaMutation}: Mutations that must be executed at the evitaDB engine level, not locally
 * to a single catalog schema instance. Examples include
 * {@link io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation} and
 * {@link ModifyEntitySchemaMutation}.
 *
 * **Mutate Contract**
 *
 * The {@link #mutate(CatalogSchemaContract)} method applies the mutation operation and returns the modified catalog
 * schema. Different mutation types follow different patterns:
 *
 * - **Create operations**: Accept `null` input and produce a non-null result
 * - **Remove operations**: Accept non-null input and may produce `null` result
 * - **Modification operations**: Always accept and produce non-null values
 *
 * The return type {@link CatalogSchemaWithImpactOnEntitySchemas} allows catalog schema mutations to also specify
 * entity schema mutations that should be propagated to all entity schemas in the catalog. For example, a mutation
 * that adds a global attribute to the catalog schema may need to propagate attribute-related changes to entity
 * schemas via the `entitySchemaMutations` array.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
@ThreadSafe
public interface CatalogSchemaMutation extends SchemaMutation {

	/**
	 * Concatenates a parent CDC capture stream with child schema mutation captures, respecting the stream
	 * direction from the predicate context. In forward direction, child mutations follow the parent capture;
	 * in reverse direction, child mutations (iterated backwards) precede the parent capture. Each child
	 * mutation's capture is produced within {@link MutationPredicateContext#doNotAdvance} to prevent
	 * the context index from advancing for nested mutations.
	 *
	 * @param parentCapture  the CDC capture stream of the parent (wrapper) mutation
	 * @param childMutations the array of child schema mutations to stream
	 * @param predicate      the mutation predicate used for filtering and context tracking
	 * @param content        the requested CDC content mode (header or body)
	 * @return concatenated stream of parent and child captures in the appropriate direction
	 */
	@Nonnull
	static Stream<ChangeCatalogCapture> concatWithChildMutations(
		@Nonnull Stream<ChangeCatalogCapture> parentCapture,
		@Nonnull SchemaMutation[] childMutations,
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content
	) {
		final MutationPredicateContext context = predicate.getContext();
		if (context.getDirection() == StreamDirection.FORWARD) {
			return Stream.concat(
				parentCapture,
				Arrays.stream(childMutations)
					.filter(predicate)
					.flatMap(m -> context.doNotAdvance(
						() -> m.toChangeCatalogCapture(predicate, content)
					))
			);
		} else {
			final AtomicInteger index = new AtomicInteger(childMutations.length);
			return Stream.concat(
				Stream.generate(() -> null)
					.takeWhile(x -> index.get() > 0)
					.map(x -> childMutations[index.decrementAndGet()])
					.filter(predicate)
					.flatMap(x -> context.doNotAdvance(
						() -> x.toChangeCatalogCapture(predicate, content)
					)),
				parentCapture
			);
		}
	}

	/**
	 * Applies the mutation operation on the catalog schema in the input and returns the modified version along with
	 * any entity schema mutations that should be propagated to all entity schemas in the catalog.
	 *
	 * The create operation works with `null` input value and produces non-null result. The remove operation produces
	 * the opposite. Modification operations always accept and produce non-null values.
	 *
	 * @param catalogSchema current version of the schema as an input to mutate (may be null for create operations)
	 * @return a record containing the updated catalog schema and optional array of entity schema mutations to
	 * propagate, or null for remove operations that eliminate the catalog schema
	 */
	@Nullable
	CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema);

	/**
	 * Result object of the {@link #mutate(CatalogSchemaContract)} method allowing to return both the modified catalog
	 * schema and the list of entity schema mutations that were caused by the catalog schema mutation and needs to be
	 * propagated to the entity schemas.
	 *
	 * @param updatedCatalogSchema  modified catalog schema
	 * @param entitySchemaMutations list of entity schema mutations that were caused by the catalog schema mutation
	 */
	record CatalogSchemaWithImpactOnEntitySchemas(
		@Nonnull CatalogSchemaContract updatedCatalogSchema,
		@Nullable ModifyEntitySchemaMutation[] entitySchemaMutations
	) {
		public CatalogSchemaWithImpactOnEntitySchemas(@Nonnull CatalogSchemaContract updatedCatalogSchema) {
			this(updatedCatalogSchema, null);
		}
	}

}
