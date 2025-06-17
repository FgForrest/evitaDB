/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.mutation;


import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

/**
 * This interface represents mutations that are bound to a specific catalog within the evitaDB instance.
 *
 * CatalogBoundMutation is a sealed interface that permits three specific types of mutations:
 *
 * 1. **EntityMutation**: Top-level mutations that group all LocalMutations targeting the same entity,
 *    allowing for atomic updates to a single Entity in evitaDB.
 *
 * 2. **LocalMutation**: Mutations that operate on an EntityContract object.
 *
 * 3. **SchemaMutation**: Mutations that operate on an EntitySchemaContract object, allowing for
 *    schema modifications that happen transactionally.
 *
 * Unlike EngineMutation which operates on the entire evitaDB instance, CatalogBoundMutation is
 * scoped to a specific catalog, providing more targeted mutation capabilities.
 *
 * All implementations must provide a way to transform the mutation to a stream of change catalog
 * capture items for tracking and auditing purposes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public sealed interface CatalogBoundMutation extends Mutation permits EntityMutation, LocalMutation, SchemaMutation, TransactionMutation {

	/**
	 * Transforms mutation to the stream of change catalog capture item matching the input predicate.
	 *
	 * @param predicate the predicate to be used for filtering the {@link LocalMutation} mutation items if any
	 *                  are present
	 * @param content   the requested content of the capture
	 * @return the change catalog capture item
	 */
	@Nonnull
	Stream<ChangeCatalogCapture> toChangeCatalogCapture(
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content
	);

}
