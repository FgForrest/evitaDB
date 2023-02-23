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
	 * Returns unique id of the transaction (the overflow risk for long type is ignored).
	 */
	long getId();

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

}
