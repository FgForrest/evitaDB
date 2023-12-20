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

package io.evitadb.driver;

import io.evitadb.api.TransactionContract;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * {@inheritDoc TransactionContract}
 *
 * This implementation represents the reflection of the server side transaction on the client side.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class EvitaClientTransaction implements TransactionContract {
	/**
	 * Reference to the active session the transaction is related to and opened within.
	 */
	private final EvitaClientSession session;
	/**
	 * Contains unique id of the transaction (the overflow risk for long type is ignored).
	 */
	@Getter private final UUID transactionId;
	/**
	 * Contains the catalog version the transaction is opened within.
	 */
	@Getter private final long catalogVersion;
	/**
	 * Rollback only flag.
	 */
	@Getter private boolean rollbackOnly;
	/**
	 * Flag that marks this instance closed and unusable. Once closed it can never be opened again.
	 */
	@Getter private boolean closed;

	@Override
	public void setRollbackOnly() {
		rollbackOnly = true;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}

		closed = true;
		session.closeTransaction();
	}

}
