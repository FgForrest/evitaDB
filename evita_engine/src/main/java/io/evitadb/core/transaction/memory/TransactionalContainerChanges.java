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

import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Transactional memory piece that collects created and removed inner transactional objects.
 */
public final class TransactionalContainerChanges<DIFF_PIECE, COPY, PRODUCER extends TransactionalLayerProducer<DIFF_PIECE, COPY>> {
	private List<PRODUCER> createdItems;
	private List<PRODUCER> removedItems;

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

}
