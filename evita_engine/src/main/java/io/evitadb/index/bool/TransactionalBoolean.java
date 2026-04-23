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

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;

import static io.evitadb.core.transaction.Transaction.getTransactionalMemoryLayerIfExists;

/**
 * This class envelopes simple primitive boolean and makes it transactional. This means, that the boolean can be updated
 * by multiple writers and also multiple readers can read its original value without spotting the changes made
 * in transactional access. Each transaction is bound to the same thread and different threads don't see changes in
 * other threads.
 *
 * If no transaction is opened, changes are applied directly to the delegate boolean. In such case the class is not thread
 * safe for multiple writers!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
public class TransactionalBoolean implements TransactionalLayerProducer<BooleanChanges, Boolean>, Serializable {
	@Serial private static final long serialVersionUID = 7796376128158582312L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	private boolean value;

	/**
	 * Creates a new instance with an initial value of `false`.
	 */
	public TransactionalBoolean() {
		this.value = false;
	}

	/**
	 * Creates a new instance with the given initial value.
	 *
	 * @param value the initial boolean value
	 */
	public TransactionalBoolean(boolean value) {
		this.value = value;
	}

	/**
	 * Creates a new transactional layer initialized with the current value of this boolean.
	 */
	@Nonnull
	@Override
	public BooleanChanges createLayer() {
		return new BooleanChanges(this.value);
	}

	/**
	 * Sets the value to TRUE in a transaction-safe way (if transaction is available).
	 */
	public void setToTrue() {
		final BooleanChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.value = true;
		} else {
			layer.setToTrue();
		}
	}

	/**
	 * Sets the value to FALSE in a transaction-safe way (if transaction is available).
	 */
	public void setToFalse() {
		final BooleanChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.value = false;
		} else {
			layer.setToFalse();
		}
	}

	/**
	 * Returns the current boolean value in a transaction-safe way (if transaction is available).
	 */
	public boolean isTrue() {
		final BooleanChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.value;
		} else {
			return layer.isTrue();
		}
	}

	/**
	 * Resets the value to false in a transaction-safe way (if transaction is available).
	 * Functionally equivalent to {@link #setToFalse()}, provided for semantic clarity at call
	 * sites where "reset" better conveys intent.
	 */
	public void reset() {
		this.setToFalse();
	}

	/*
		TransactionalLayerProducer implementation
	 */

	@Nonnull
	@Override
	public Boolean createCopyWithMergedTransactionalMemory(
		@Nullable BooleanChanges layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		return layer == null ? this.value : layer.isTrue();
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}
}
