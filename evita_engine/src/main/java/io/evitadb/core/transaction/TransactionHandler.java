/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.transaction;

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainerFinalizer;

import javax.annotation.Nonnull;

/**
 * Represents a transaction handler that allows registering mutations in particular transaction. It also inherits
 * methods from {@link TransactionalLayerMaintainerFinalizer} that are used to commit or rollback the transaction
 * and apply the changes to the immutable state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface TransactionHandler extends TransactionalLayerMaintainerFinalizer {

	/**
	 * All mutation operations that occur within the transaction must be registered here in order they get recorded
	 * in the WAL and participate in conflict resolution logic.
	 *
	 * @param mutation mutation to be registered
	 */
	void registerMutation(@Nonnull Mutation mutation);

}
