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

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.core.transaction.memory.WarmUpTouchStamped;
import io.evitadb.utils.VMLayout;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;

import static io.evitadb.core.transaction.Transaction.getTransactionalMemoryLayerIfExists;

/**
 * This class envelops simple atomic reference and makes it transactional. This means, that the reference can be
 * updated by multiple writers and also multiple readers can read its original reference without spotting the changes
 * made in transactional access. Each transaction is bound to the same thread and different threads don't see
 * changes in other threads.
 *
 * If no transaction is opened, changes are applied directly to the delegate reference. In such case the class is
 * not thread safe for multiple writers!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
public class TransactionalReference<T>
	implements TransactionalLayerProducer<ReferenceChanges<T>, Optional<T>>, WarmUpTouchStamped,
	Serializable {
	@Serial private static final long serialVersionUID = 1524821425865368156L;
	/**
	 * This structure's first-touch mark for the warm-up savepoint mechanism: the stamp of the
	 * {@link WarmUpSavepoint} that most recently captured its pre-image. {@link WarmUpTouchStamped}
	 * carries the requirements the field has to meet, and why breaking one of them corrupts a
	 * rollback rather than merely slowing it down.
	 */
	@Getter @Setter private transient long warmUpTouchStamp;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	private final AtomicReference<T> value;

	/**
	 * Creates a new instance with the given initial value.
	 *
	 * @param value the initial reference value
	 */
	public TransactionalReference(@Nullable T value) {
		this.value = new AtomicReference<>(value);
	}

	/**
	 * Returns the heap this holder occupies, in bytes — its own object, the {@link AtomicReference} it wraps and
	 * whatever `valueSizer` decides the referenced value is worth.
	 *
	 * The value is the caller's to price, for the same reason a map's values are: this holder cannot tell whether it
	 * owns what it points at. {@link io.evitadb.index.facet.FacetReferenceIndex} owns the group index it holds here
	 * and prices it in full; a holder pointing at a structure another index maintains would return `0`.
	 *
	 * The committed value is read directly rather than through {@link #get()}: the per-transaction
	 * {@link ReferenceChanges} layer belongs to the transaction that created it and disappears on commit or rollback,
	 * exactly as the map decorators of this package treat theirs.
	 *
	 * @param valueSizer prices the referenced value, or returns `0` when this holder does not own it
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes(@Nonnull ToLongFunction<? super T> valueSizer) {
		final VMLayout layout = VMLayout.current();
		final T committedValue = this.value.get();
		// id + warmUpTouchStamp + the AtomicReference slot, then the AtomicReference's own object holding a single
		// reference
		return layout.sizeOfObject(2L * Long.BYTES + layout.referenceSize())
			+ layout.sizeOfObject(layout.referenceSize())
			+ (committedValue == null ? 0L : valueSizer.applyAsLong(committedValue));
	}

	/**
	 * Sets the value to `value` in a transaction-safe way (if transaction is available).
	 */
	public void set(@Nullable T value) {
		final ReferenceChanges<T> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			recordWarmUpSavepointTouch();
			this.value.set(value);
		} else {
			layer.set(value);
		}
	}

	/**
	 * Sets the value to `value` in a transaction-safe way (if transaction is available) but only if `currentValue`
	 * equals to currently held attribute. Returns the value after the method application.
	 *
	 * @return the witness value, which will be the same as the expected value if successful
	 */
	@Nullable
	public T compareAndExchange(@Nullable T currentValue, @Nullable T newValue) {
		final ReferenceChanges<T> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			// recorded unconditionally rather than only on a successful exchange: the capture is the pre-image of the
			// whole holder, so it is correct either way, and testing the outcome first would cost more than it saves
			recordWarmUpSavepointTouch();
			return this.value.compareAndExchange(currentValue, newValue);
		} else {
			return layer.compareAndExchange(currentValue, newValue);
		}
	}

	/**
	 * Captures the referenced value for the warm-up savepoint bracketing the current root entity mutation, if one is
	 * open, so that a failed mutation rewinds this holder to what it pointed at before the mutation began (see
	 * {@link WarmUpSavepoint}).
	 *
	 * The capture is made on the FIRST write-touch only: the holder's entire mutable state is the one reference it
	 * carries, so a single captured pre-image is an absolute restore of all of it, and re-capturing on a later write
	 * would only overwrite it with a mid-savepoint value.
	 *
	 * The value itself is captured BY REFERENCE, exactly as a {@link io.evitadb.core.transaction.memory.Snapshotable}
	 * memento captures a nested producer: when the referenced object is itself a transactional structure, its internal
	 * state is rewound by its own journaling, never from here.
	 *
	 * Must be called BEFORE the write. Outside a savepoint it costs one {@link ThreadLocal} read returning `null`.
	 */
	private void recordWarmUpSavepointTouch() {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null && savepoint.claimFirstTouch(this)) {
			final T preImage = this.value.get();
			savepoint.push(() -> this.value.set(preImage));
		}
	}

	/**
	 * Returns the current value in a transaction-safe way (if transaction is available).
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

	/**
	 * Same as {@link #get()}, but reads through an ALREADY-RESOLVED transaction instead of resolving the thread's
	 * transaction itself.
	 *
	 * {@link #get()} begins with a `ThreadLocal` read of the current transaction. An operation that touches several
	 * transactional references pays that read once per reference even though the answer is identical for all of them;
	 * this overload lets the caller resolve it once (via
	 * {@link Transaction#getCurrentTransactionIfAvailable()}) and thread it down. Behaviour is otherwise identical -
	 * `null` transaction means read the committed value, exactly as an absent layer does.
	 *
	 * **Caller obligation — pass only the calling thread's own transaction.** Unlike {@link #get()}, which resolves
	 * the current thread's transaction and therefore cannot be misused, this overload reads through whatever
	 * transaction it is handed. Handing it another thread's transaction reads that thread's *uncommitted* diff layer.
	 * Resolve it within the same operation, use it for the duration of that operation, and then drop it: never cache
	 * it in a field, store it on the object, or pass it across threads.
	 *
	 * Because the transactional-memory dispatch is hoisted out of this method, the read-dispatch obligation described
	 * by INV-2 in `documentation/developer/stm/rules-and-invariants.md` moves to the caller.
	 *
	 * @param transaction the caller-resolved current transaction, or `null` when outside a transaction
	 * @return the value visible to the given transaction
	 */
	@Nullable
	public T get(@Nullable Transaction transaction) {
		final ReferenceChanges<T> layer = transaction == null ?
			null : transaction.getTransactionalMemory().getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.value.get();
		} else {
			return layer.get();
		}
	}

	@Nonnull
	@Override
	public ReferenceChanges<T> createLayer() {
		return new ReferenceChanges<>(this.value.get());
	}

	/**
	 * The whole mutable state of this wrapper is one reference, so its pre-image is captured in full on the first
	 * write-touch of the delegate branch and restored by a single field assignment. The referent is captured by
	 * reference: one carrying mutable state of its own is rewound by that object's own journalling.
	 *
	 * @return always `true` — see above
	 */
	@Override
	public boolean supportsWarmUpRollback() {
		return true;
	}

	@Nonnull
	@Override
	public Optional<T> createCopyWithMergedTransactionalMemory(
		@Nullable ReferenceChanges<T> layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		return layer == null ? Optional.ofNullable(this.value.get()) : Optional.ofNullable(layer.get());
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}

}
