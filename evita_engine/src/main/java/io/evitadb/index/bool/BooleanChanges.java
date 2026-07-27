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

import io.evitadb.core.transaction.memory.Snapshotable;
import lombok.AllArgsConstructor;

import javax.annotation.Nonnull;
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
public class BooleanChanges implements Snapshotable<BooleanChanges.BooleanChangesMemento> {
	/**
	 * The transaction-local snapshot of the boolean value.
	 */
	private boolean theValue;

	/**
	 * Immutable, primitive-holding memento carrying the entire mutable state of this layer — the single
	 * transaction-local boolean value. A primitive `boolean` is copied by value, so the memento is inherently
	 * independent of any subsequent mutation of the layer and can be restored any number of times. A record holding
	 * the primitive directly is used (instead of boxing into {@link Boolean}) to honour the project's no-autoboxing
	 * performance rule.
	 *
	 * @param value the captured transaction-local boolean value
	 */
	public record BooleanChangesMemento(boolean value) {
	}

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

	/**
	 * Captures the single transaction-local boolean value into an immutable memento. Because the value is a JVM
	 * primitive copied by value, the returned memento is fully independent of any later mutation of this layer.
	 *
	 * @return a memento carrying the current transaction-local boolean value
	 */
	@Nonnull
	@Override
	public BooleanChangesMemento snapshot() {
		return new BooleanChangesMemento(this.theValue);
	}

	/**
	 * Resets the transaction-local boolean value to the one captured in the given memento. Since the value is an
	 * absolute overwrite (not an incremental delta), this single assignment undoes any number of
	 * {@link #setToTrue()} / {@link #setToFalse()} flips performed since the memento was taken, and the same memento
	 * may be restored repeatedly.
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same layer
	 */
	@Override
	public void restore(@Nonnull BooleanChangesMemento memento) {
		this.theValue = memento.value();
	}

}
