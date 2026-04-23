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

package io.evitadb.index.bool;

import lombok.AllArgsConstructor;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Represents a per-transaction diff layer snapshot for {@link TransactionalBoolean} in the
 * Software Transactional Memory (STM) system. Each open transaction that modifies the boolean
 * gets its own `BooleanChanges` instance, keeping the mutation isolated until the transaction
 * is committed and merged back into the shared state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@AllArgsConstructor
public class BooleanChanges {
	/**
	 * The transaction-local snapshot of the boolean value.
	 */
	private boolean theValue;

	/**
	 * Sets the local value to true.
	 */
	public void setToTrue() {
		this.theValue = true;
	}

	/**
	 * Sets the local value to false.
	 */
	public void setToFalse() {
		this.theValue = false;
	}

	/**
	 * Returns the current local value.
	 */
	public boolean isTrue() {
		return this.theValue;
	}

}
