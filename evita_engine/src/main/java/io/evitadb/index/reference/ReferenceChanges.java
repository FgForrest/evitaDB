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

import io.evitadb.core.transaction.memory.Snapshotable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a per-transaction diff layer snapshot for {@link TransactionalReference} in the
 * Software Transactional Memory (STM) system. Each open transaction that modifies the reference
 * gets its own `ReferenceChanges` instance, keeping the mutation isolated until the transaction
 * is committed and merged back into the shared state.
 *
 * The whole mutable state of this layer is the single value currently parked in {@link #theValue}:
 * it starts equal to the baseline value copied in on layer creation, and `set` /
 * `compareAndExchange` replace what it points to. `T` is treated as an immutable,
 * atomically-swapped payload — writers swap the reference, they never mutate a `T` in place — so
 * capturing / re-storing the bare reference is sufficient to satisfy the {@link Snapshotable}
 * contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class ReferenceChanges<T> implements Snapshotable<ReferenceChanges.ReferenceMemento<T>> {
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

	/**
	 * Captures the single transaction-local value currently held by this layer into a memento. The
	 * held `T` is a reference-captured, atomically-swapped payload (never mutated in place), so
	 * capturing the bare reference is already independent of any later `set` / `compareAndExchange`
	 * call — those swap {@link #theValue} to a different object rather than mutating the captured
	 * one. No deep copy of `T` is performed (and must not be, to match the reference-swap design and
	 * the commit-side merge path).
	 *
	 * @return a non-null memento wrapping the (possibly `null`) value held at the snapshot moment
	 */
	@Nonnull
	@Override
	public ReferenceMemento<T> snapshot() {
		return new ReferenceMemento<>(this.theValue.get());
	}

	/**
	 * Resets this layer back to the value captured by the given memento, unconditionally overwriting
	 * whatever value is currently held and thereby undoing any number of intervening `set` /
	 * `compareAndExchange` calls in one step. The memento is immutable and may be restored
	 * repeatedly. A captured `null` value is faithfully restored.
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same layer
	 */
	@Override
	public void restore(@Nonnull ReferenceMemento<T> memento) {
		this.theValue.set(memento.value());
	}

	/**
	 * Immutable carrier of the single value captured from a {@link ReferenceChanges} layer. The
	 * wrapper is always non-null (so it satisfies the {@link Snapshotable} contract) while its
	 * {@link #value()} may legitimately be `null` — a `null` reference value is a valid layer state
	 * and must be distinguishable from absence.
	 *
	 * @param value the captured reference value (may be `null`); held by reference, never deep-copied
	 * @param <T> the reference payload type
	 */
	public record ReferenceMemento<T>(@Nullable T value) {
	}
}
