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

import static io.evitadb.core.transaction.Transaction.getTransactionalLayerMaintainer;
import static java.util.Optional.ofNullable;

/**
 * Participant of the commit-time merge cascade: an object that can produce its committed form and that can propagate
 * diff-layer removal through the objects it holds.
 *
 * Both capabilities are needed by **every** participant, whereas owning a diff layer
 * ({@link TransactionalLayerCreator}) is needed only by some. Objects that maintain transactionally modifiable internal
 * fields but cannot be modified themselves - see {@link VoidTransactionMemoryProducer} - implement this interface alone:
 * they rebuild from their (possibly changed) children without ever holding a diff of their own.
 *
 * Keeping the two roles apart is what allows an identifier-keyed layer registry to be safe by construction: a type with
 * no {@link TransactionalLayerCreator#getId()} cannot be looked up in it at all, so it can never be handed a diff layer
 * belonging to a different object.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface TransactionalStateProducer<COPY> {

	/**
	 * Merges changes / differences captured in the transactional layer to a separate (new) cloned object instance.
	 * Merge is ought to be performed deep-wise. Ie. if the current object contains references to other
	 * {@link TransactionalStateProducer} instances it's required to obtain their committed form through
	 * {@link TransactionalLayerMaintainer#getStateCopyWithCommittedChanges(TransactionalStateProducer)} when collecting
	 * changes to the returned instance.
	 *
	 * Implementations that own a diff layer must not implement this method - {@link TransactionalLayerProducer} provides
	 * it, resolving the layer through the maintainer and implementing
	 * {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)}
	 * instead. That arrangement keeps layer resolution and disposal in a single place.
	 *
	 * @param transactionalLayer object that provides access to entire transactional memory so that it can be manipulated
	 */
	@Nonnull
	COPY createCopyWithMergedTransactionalMemory(@Nonnull TransactionalLayerMaintainer transactionalLayer);

	/**
	 * Method implementation must remove entire diff memory from the current transaction. If object maintains inner
	 * objects, their memory must be removed as well.
	 */
	default void removeLayer() {
		ofNullable(getTransactionalLayerMaintainer())
			.ifPresent(this::removeLayer);
	}

	/**
	 * Method implementation must remove entire diff memory from the current transaction. If object maintains inner
	 * objects, their memory must be removed as well.
	 *
	 * Removal has to propagate through the whole object graph, which is why it lives here rather than on
	 * {@link TransactionalLayerCreator} - an object that owns no layer of its own may still hold children that do.
	 * Layer owners additionally drop their own layer through
	 * {@link TransactionalLayerMaintainer#removeTransactionalMemoryLayerIfExists(TransactionalLayerCreator)}.
	 *
	 * @param transactionalLayer object that provides access to entire transactional memory so that it can be manipulated
	 */
	void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer);

}
