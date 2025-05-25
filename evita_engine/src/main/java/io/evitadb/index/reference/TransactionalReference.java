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

import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.core.Transaction.getTransactionalMemoryLayerIfExists;

/**
 * This class envelopes simple atomic reference and makes it transactional. This means, that the reference can be updated
 * by multiple writers and also multiple readers can read its original reference without spotting the changes made
 * in transactional access. Each transaction is bound to the same thread and different threads doesn't see changes in
 * another threads.
 *
 * If no transaction is opened, changes are applied directly to the delegate array. In such case the class is not thread
 * safe for multiple writers!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
public class TransactionalReference<T> implements TransactionalLayerProducer<ReferenceChanges<T>, Optional<T>>, Serializable {
	@Serial private static final long serialVersionUID = 1524821425865368156L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	private final AtomicReference<T> value;

	public TransactionalReference(@Nullable T value) {
		this.value = new AtomicReference<>(value);
	}

	/**
	 * Sets the value to `value` in a transactional safe way (if transaction is available).
	 */
	public void set(@Nullable T value) {
		final ReferenceChanges<T> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.value.set(value);
		} else {
			layer.set(value);
		}
	}

	/**
	 * Sets the value to `value` in a transactional safe way (if transaction is available) but only if `currentValue`
	 * equals to currently held attribute. Returns the value after the method application.
	 *
	 * @return the witness value, which will be the same as the expected value if successful
	 */
	@Nullable
	public T compareAndExchange(@Nullable T currentValue, @Nullable T newValue) {
		final ReferenceChanges<T> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.value.compareAndExchange(currentValue, newValue);
		} else {
			return layer.compareAndExchange(currentValue, newValue);
		}
	}

	/**
	 * returns the current value in a transactional safe way (if transaction is available).
	 */
	@Nullable
	public T get() {
		final ReferenceChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.value.get();
		} else {
			return layer.get();
		}
	}

	/*
		TRANSACTIONAL OBJECT IMPLEMENTATION
	 */

	@Override
	public ReferenceChanges<T> createLayer() {
		return new ReferenceChanges<>(this.value.get());
	}

	@Nonnull
	@Override
	public Optional<T> createCopyWithMergedTransactionalMemory(@Nullable ReferenceChanges<T> layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		return layer == null ? Optional.ofNullable(this.value.get()) : Optional.ofNullable(layer.get());
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}

}
