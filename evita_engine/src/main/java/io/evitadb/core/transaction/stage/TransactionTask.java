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

package io.evitadb.core.transaction.stage;

import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.core.metric.event.transaction.TransactionQueuedEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * This interface represents a transaction task, which is a unit of work performed within a transaction. It provides
 * methods to retrieve information about the task, such as the catalog name, catalog version, transaction ID,
 * commit behavior, and future completion.
 *
 * It should be implemented by simple data structures - most probably records that are propagated through
 * the transaction processing pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface TransactionTask {

	/**
	 * Retrieves the name of the catalog the transaction is bound to.
	 *
	 * @return The name of the catalog
	 */
	@Nonnull
	String catalogName();

	/**
	 * Returns the version of the catalog the transaction is bound to.
	 *
	 * @return The catalog version assigned to the transaction task
	 */
	long catalogVersion();

	/**
	 * Retrieves the unique ID of the transaction that carries the changes to the database.
	 *
	 * @return The transaction ID
	 */
	@Nonnull
	UUID transactionId();

	/**
	 * Retrieves the commit behavior of the transaction task. Commit behavior defines the moment the transaction is
	 * considered as committed from the client point of view.
	 *
	 * @return The commit behavior of the transaction task
	 */
	@Nonnull
	CommitBehavior commitBehaviour();

	/**
	 * Retrieves the future associated with this transaction task. The future represents the completion of the task
	 * execution and returns a new catalog version when completed. It's non-null ony if the client still waits for
	 * the transaction to reach the requested processing stage.
	 *
	 * @return The future associated with the transaction task
	 */
	@Nullable
	CompletableFuture<Long> future();

	/**
	 * Contains the event generated when the task was created.
	 * @return the event
	 */
	@Nonnull
	TransactionQueuedEvent transactionQueuedEvent();

}
