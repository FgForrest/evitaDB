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

package io.evitadb.core.transaction.memory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Transactional memory piece that tracks inner transactional objects created and removed within a container during a
 * single transaction. At cleanup it discards the diff layers of those objects so that churn (objects both created and
 * removed in the same transaction) does not leak stale transactional memory.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public final class TransactionalContainerChanges<COPY, PRODUCER extends TransactionalStateProducer<COPY>>
	implements Snapshotable<TransactionalContainerChanges.ContainerChangesMemento<PRODUCER>> {
	/**
	 * Lazily allocated list of objects created in this transaction; `null` until the first {@link #addCreatedItem}.
	 */
	@Nullable private List<PRODUCER> createdItems;
	/**
	 * Lazily allocated list of objects removed in this transaction; `null` until the first {@link #addRemovedItem}.
	 */
	@Nullable private List<PRODUCER> removedItems;

	/**
	 * Registers new - created transactional object.
	 */
	public void addCreatedItem(PRODUCER item) {
		if (this.createdItems == null) {
			this.createdItems = new LinkedList<>();
		}
		this.createdItems.add(item);
	}

	/**
	 * Registers removal of existing transactional object.
	 */
	public void addRemovedItem(PRODUCER item) {
		if (this.removedItems == null) {
			this.removedItems = new LinkedList<>();
		}
		this.removedItems.add(item);
	}

	/**
	 * Collects all items that were both created and removed in this transaction and removes their transactional
	 * memory.
	 */
	public void clean(TransactionalLayerMaintainer transactionalLayer) {
		getCreatedAndRemovedItems().forEach(it -> it.removeLayer(transactionalLayer));
	}

	/**
	 * Collects all items that were either created or removed in this transaction and removes their transactional
	 * memory.
	 */
	public void cleanAll(TransactionalLayerMaintainer transactionalLayer) {
		final Consumer<PRODUCER> cleaningFct = item -> item.removeLayer(transactionalLayer);
		ofNullable(this.createdItems).ifPresent(it -> it.forEach(cleaningFct));
		ofNullable(this.removedItems).ifPresent(it -> it.forEach(cleaningFct));
	}

	/**
	 * Captures the current created/removed bookkeeping into a memento so that a savepoint can later revert any
	 * registrations made after this point. The producer instances are captured by reference only (the
	 * nested-layer-boundary invariant of {@link Snapshotable}) — their own diff state is reverted by their own
	 * {@link Snapshotable}; here only the *membership* of the two lists is snapshotted.
	 */
	@Nonnull
	@Override
	public ContainerChangesMemento<PRODUCER> snapshot() {
		return new ContainerChangesMemento<>(
			this.createdItems == null ? null : new ArrayList<>(this.createdItems),
			this.removedItems == null ? null : new ArrayList<>(this.removedItems)
		);
	}

	/**
	 * Resets the created/removed bookkeeping to exactly the state captured by the given memento, discarding any
	 * registrations made since. Copies *out of* the memento so the same memento may be restored repeatedly.
	 */
	@Override
	public void restore(@Nonnull ContainerChangesMemento<PRODUCER> memento) {
		final List<PRODUCER> snapshotCreated = memento.createdItems();
		this.createdItems = snapshotCreated == null ? null : new LinkedList<>(snapshotCreated);
		final List<PRODUCER> snapshotRemoved = memento.removedItems();
		this.removedItems = snapshotRemoved == null ? null : new LinkedList<>(snapshotRemoved);
	}

	/**
	 * Collects both created and removed items. Removes instances that were registered as both created and removed.
	 */
	private Stream<PRODUCER> getCreatedAndRemovedItems() {
		if (this.removedItems != null && this.createdItems != null) {
			return this.removedItems
				.stream()
				.filter(it -> {
					for (PRODUCER createdItem : this.createdItems) {
						if (createdItem == it) {
							return true;
						}
					}
					return false;
				});
		}
		return Stream.empty();
	}

	/**
	 * Immutable carrier of a {@link TransactionalContainerChanges} created/removed bookkeeping at the moment a
	 * savepoint was opened. The producer instances are held by reference only — restoring this memento reverts which
	 * producers are tracked as created/removed, not the producers' own internal diff state.
	 *
	 * @param createdItems snapshot copy of the created-items list, or `null` if none had been registered yet
	 * @param removedItems snapshot copy of the removed-items list, or `null` if none had been registered yet
	 * @param <PRODUCER>   the tracked transactional producer type
	 */
	public record ContainerChangesMemento<PRODUCER>(
		@Nullable List<PRODUCER> createdItems,
		@Nullable List<PRODUCER> removedItems
	) {
	}

}
