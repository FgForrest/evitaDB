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

package io.evitadb.index.reference;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a per-transaction diff layer snapshot for {@link TransactionalReference} in the
 * Software Transactional Memory (STM) system. Each open transaction that modifies the reference
 * gets its own `ReferenceChanges` instance, keeping the mutation isolated until the transaction
 * is committed and merged back into the shared state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class ReferenceChanges<T> {
	/**
	 * The transaction-local snapshot of the reference value.
	 */
	private final AtomicReference<T> theValue;

	/**
	 * Creates a new instance initialized with the given reference value.
	 *
	 * @param theValue the initial reference value for this transaction layer
	 */
	public ReferenceChanges(@Nullable T theValue) {
		this.theValue = new AtomicReference<>(theValue);
	}

	/**
	 * Replaces the current reference value with the given one.
	 */
	public void set(@Nullable T value) {
		this.theValue.set(value);
	}

	/**
	 * Returns the current reference value.
	 */
	@Nullable
	public T get() {
		return this.theValue.get();
	}

	/**
	 * Atomically sets the value to `newValue` if the current value equals `currentValue`.
	 * Returns the witness value (the value before the attempted exchange).
	 */
	@Nullable
	public T compareAndExchange(@Nullable T currentValue, @Nullable T newValue) {
		return this.theValue.compareAndExchange(currentValue, newValue);
	}
}
