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

package io.evitadb.api.requestResponse.mutation;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.cdc.CaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serializable;
import java.util.stream.Stream;

/**
 * This interface denotes all mutation operations that can be cast on Evita data objects.
 *
 * Mutations must be designed as immutable and thread safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
public sealed interface Mutation extends Serializable permits EntityMutation, LocalMutation, SchemaMutation, TransactionMutation {

	/**
	 * Returns operation classification.
	 */
	@Nonnull
	Operation operation();

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
		@Nonnull CaptureContent content
	);

	/**
	 * Direction of the change catalog capture stream.
	 */
	enum StreamDirection {

		FORWARD, REVERSE;

	}

}
