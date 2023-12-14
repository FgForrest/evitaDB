/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.api;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Transaction represents internal object that allows to track all information necessary to keep writes in isolation
 * and finally commits them to the shared data storage or throw them away in case of rollback.
 *
 * Transaction is created by creating instance of this class (i.e. constructor), transaction is closed by calling
 * {@link #close()} method and it either commits or rollbacks the changes (when {@link #setRollbackOnly()} is called.
 *
 * This concept is pretty much known from relational databases.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface TransactionContract extends AutoCloseable {

	/**
	 * Returns unique id of the transaction. This id serves only to identify the transaction among other transactions
	 * and doesn't relate to MVVC mechanism of the database.
	 */
	@Nonnull
	UUID getTransactionId();

	/**
	 * Marks this transaction for rollback only. Ie. when transaction terminates, all changes will be rolled back.
	 */
	void setRollbackOnly();

	/**
	 * Returns TRUE if {@link #setRollbackOnly()} was called anytime in this transaction.
	 */
	boolean isRollbackOnly();

	/**
	 * Closes the transaction and frees related resources.
	 * If {@link #setRollbackOnly()} the changes made in session are committed, otherwise they're rolled back.
	 */
	@Override
	void close();

	/**
	 * Returns TRUE if {@link #close()} was called anytime in this transaction.
	 */
	boolean isClosed();

	/**
	 * Enum representing the different behaviors a transaction can have when committing changes.
	 */
	enum CommitBehaviour {

		/**
		 * Changes performed in the transaction are passed to evitaDB server, checked for conflicts and if no conflict
		 * is found the transaction is marked as completed and commit is finished. This behaviour is fastest, but does
		 * not guarantee that the changes are persisted on disk and durable. If the server crashes before the changes
		 * are written to disk, the changes are lost.
		 */
		NO_WAIT,

		/**
		 * Changes performed in the transaction are passed to evitaDB server, checked for conflicts and if no conflict
		 * is found, they are written to Write Ahead Log (WAL) and transaction waits until the WAL is persisted on disk
		 * (fsynced). After that the transaction is marked as completed and commit is finished. This behaviour is
		 * slower than {@link #NO_WAIT} but guarantees that the changes are persisted on disk and durable. The server
		 * may decide to fsync changes from multiple transactions at once, so the transaction may wait longer than
		 * necessary. This behaviour still does not guarantee that the changes will be visible immediately after
		 * the commit - because they still need to be propagated to indexes in order new data can be found by queries.
		 *
		 * This behaviour is default.
		 */
		WAIT_FOR_LOG_PERSISTENCE,

		/**
		 * Changes performed in the transaction are passed to evitaDB server, checked for conflicts and if no conflict
		 * is found, they are written to Write Ahead Log (WAL). Then the WAL is processed and all changes are propagated
		 * to indexes. After that the transaction is marked as completed and commit is finished. This behaviour is
		 * slowest but guarantees that the changes are persisted on disk and durable and that they are visible
		 * immediately after the commit is marked as completed.
		 */
		WAIT_FOR_INDEX_PROPAGATION

	}

}
