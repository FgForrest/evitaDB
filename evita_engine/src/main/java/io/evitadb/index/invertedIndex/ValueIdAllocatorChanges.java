/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.invertedIndex;

import io.evitadb.core.transaction.memory.Snapshotable;
import lombok.AllArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Represents a per-transaction diff layer for {@link ValueIdAllocator} in the Software Transactional Memory (STM)
 * system. Each open transaction that mints a value id gets its own instance, so the ids handed out inside the
 * transaction stay isolated until it commits — and are given back wholesale when it aborts.
 *
 * Giving the ids back on abort is deliberate and is what makes the allocator a
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} rather than a bare counter. An aborted
 * transaction's ids were never published to any consumer or snapshot, so re-handing them out is invisible; burning
 * them instead would consume the id space at the rate of *attempted* writes rather than committed ones.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
@AllArgsConstructor
public class ValueIdAllocatorChanges implements Snapshotable<ValueIdAllocatorChanges.ValueIdAllocatorMemento> {
	/**
	 * The transaction-local high-water mark: the id {@link #allocate()} will hand out next.
	 */
	private int nextValueId;

	/**
	 * Immutable, primitive-holding memento carrying the entire mutable state of this layer — the single
	 * transaction-local high-water mark. A primitive `int` is copied by value, so the memento is inherently
	 * independent of any subsequent mutation of the layer and can be restored any number of times. A record holding
	 * the primitive directly is used (instead of boxing into {@link Integer}) to honour the project's no-autoboxing
	 * performance rule.
	 *
	 * @param nextValueId the captured transaction-local high-water mark
	 */
	public record ValueIdAllocatorMemento(int nextValueId) {
	}

	/**
	 * Hands out the next transaction-local value id and advances the high-water mark.
	 *
	 * @return the freshly minted value id, always greater than {@link ValueIdAllocator#UNASSIGNED_VALUE_ID}
	 */
	public int allocate() {
		// guard BEFORE handing the id out, so the counter can never wrap into the negative range where it would
		// collide with UNASSIGNED_VALUE_ID and start silently aliasing live values
		if (this.nextValueId == Integer.MAX_VALUE) {
			throw ValueIdAllocator.exhausted();
		}
		return this.nextValueId++;
	}

	/**
	 * Returns the transaction-local high-water mark without advancing it.
	 *
	 * @return the id the next {@link #allocate()} would hand out
	 */
	public int getNextValueId() {
		return this.nextValueId;
	}

	/**
	 * Captures the single transaction-local high-water mark into an immutable memento - see
	 * {@link ValueIdAllocatorMemento} for why that memento is independent of any later mutation of this layer.
	 *
	 * @return a memento carrying the current transaction-local high-water mark
	 */
	@Nonnull
	@Override
	public ValueIdAllocatorMemento snapshot() {
		return new ValueIdAllocatorMemento(this.nextValueId);
	}

	/**
	 * Rewinds the high-water mark to the one captured in the given memento, giving back every id minted since. This is
	 * safe precisely because the savepoint being rolled back also rewinds the leaf id columns that received those ids
	 * (see the leaf's own memento), so no live value is left pointing at a rewound id. Since the value is an absolute
	 * overwrite rather than an incremental delta, the same memento may be restored repeatedly.
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same layer
	 */
	@Override
	public void restore(@Nonnull ValueIdAllocatorMemento memento) {
		this.nextValueId = memento.nextValueId();
	}

}
