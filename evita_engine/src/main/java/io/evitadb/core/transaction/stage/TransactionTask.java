/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.core.transaction.stage;

import io.evitadb.api.CommitProgressRecord;
import io.evitadb.core.metric.event.transaction.TransactionQueuedEvent;

import javax.annotation.Nonnull;
import java.util.UUID;

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
	 * Returns the version of the catalog that will be valid in the catalog after the transaction is
	 * committed.
	 *
	 * @return The catalog version assigned to the transaction task
	 */
	long catalogVersion();

	/**
	 * Returns the version of the catalog schema that will be valid in the catalog after the transaction is
	 * committed.
	 *
	 * @return The catalog schema version assigned to the transaction task
	 */
	int catalogSchemaVersion();

	/**
	 * Retrieves the unique ID of the transaction that carries the changes to the database.
	 *
	 * @return The transaction ID
	 */
	@Nonnull
	UUID transactionId();

	/**
	 * Retrieves the {@link CommitProgressRecord} associated with this transaction task. The this record contains
	 * future objects that represents the completion of the task execution and returns a new catalog version when
	 * completed.
	 *
	 * @return The future associated with the transaction task
	 */
	@Nonnull
	CommitProgressRecord commitProgress();

	/**
	 * Contains the event generated when the task was created.
	 * @return the event
	 */
	@Nonnull
	TransactionQueuedEvent transactionQueuedEvent();

}
