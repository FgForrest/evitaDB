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
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.stream.Stream;

/**
 * Root interface for all schema-related {@link Mutation} operations in evitaDB. This interface marks mutations that
 * modify schema structures including catalog schemas ({@link io.evitadb.api.requestResponse.schema.CatalogSchemaContract}),
 * entity schemas ({@link EntitySchemaContract}), and nested schemas for attributes, references, associated data, and
 * sortable attribute compounds.
 *
 * Each schema mutation increments the version of the affected schema by one. Schema mutations are executed
 * transactionally — either the mutation is applied completely or not at all. While the mutation itself only modifies
 * the schema model, the engine-side application may have side effects such as index creation, removal, or rebuilding.
 *
 * **Schema Mutation Hierarchy**
 *
 * - {@link CatalogSchemaMutation}: Mutations affecting catalog schemas
 * - {@link LocalCatalogSchemaMutation}: Local mutations applicable to an already-identified catalog schema
 * - {@link TopLevelCatalogSchemaMutation}: Mutations that must be executed at the evitaDB level (not local to
 * a single catalog)
 * - {@link EntitySchemaMutation}: Mutations affecting entity schemas
 * - {@link LocalEntitySchemaMutation}: Local mutations applicable to an already-identified entity schema
 *
 * **Thread-Safety and Concurrency**
 *
 * Schema mutations are immutable and thread-safe. Concurrent transactional updates to the same entity can execute
 * safely as long as schema modifications don't overlap. The version increment mechanism helps detect concurrent
 * modifications and ensures serializability.
 *
 * **Change Data Capture**
 *
 * Schema mutations implement the {@link #toChangeCatalogCapture(MutationPredicate, ChangeCaptureContent)} default
 * method to support change data capture (CDC). This method converts the mutation into a stream of
 * {@link ChangeCatalogCapture} records that represent the schema change event. The method respects the provided
 * predicate to determine whether to include the mutation in the capture and supports two content modes:
 *
 * - {@link ChangeCaptureContent#BODY}: Includes the full mutation object in the capture
 * - {@link ChangeCaptureContent#HEADER}: Includes only metadata (operation type, context) without the mutation body
 *
 * The default implementation checks the predicate and, if it matches, emits a single `schemaCapture` event with the
 * mutation's operation type and optional body content.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
public non-sealed interface SchemaMutation extends CatalogBoundMutation {

	/**
	 * Converts this schema mutation into a stream of {@link ChangeCatalogCapture} events for change data capture
	 * (CDC) purposes. The method evaluates the provided predicate to determine whether this mutation should be
	 * included in the capture.
	 *
	 * @param predicate the predicate used to filter mutations for capture; if `predicate.test(this)` returns false,
	 *                  an empty stream is returned
	 * @param content   the requested content mode: {@link ChangeCaptureContent#BODY} includes the full mutation
	 *                  object, {@link ChangeCaptureContent#HEADER} includes only metadata without the mutation body
	 * @return a stream containing a single {@link ChangeCatalogCapture} event if the predicate matches, or an empty
	 * stream otherwise
	 */
	@Override
	@Nonnull
	default Stream<ChangeCatalogCapture> toChangeCatalogCapture(
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content
	) {
		final MutationPredicateContext context = predicate.getContext();

		if (predicate.test(this)) {
			return Stream.of(
				ChangeCatalogCapture.schemaCapture(
					context,
					operation(),
					content == ChangeCaptureContent.BODY ? this : null
				)
			);
		} else {
			return Stream.empty();
		}
	}

}
