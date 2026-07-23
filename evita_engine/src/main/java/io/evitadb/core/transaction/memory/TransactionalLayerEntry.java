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

import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Single registered diff layer of the software transactional memory - who owns it, the diff itself and its lifecycle
 * state. One entry is allocated per layer *creation* (never per lookup), and its lifetime matches the layer's, which
 * is what makes it the right place to retain the owning {@link TransactionalLayerCreator}: the registry is keyed by
 * the creator's {@link TransactionalLayerCreator#getId() id} alone, so nothing else keeps the creator reachable.
 *
 * The creator reference is needed on two paths:
 *
 * - {@link TransactionalLayerMaintainer#verifyLayerWasFullySwept()} reports the live creator objects behind any layer
 *   left {@link TransactionalLayerState#ALIVE} at commit;
 * - the registry asserts that one id never maps to two distinct live creators.
 *
 * The reference is held rather than eagerly stringified on purpose - rendering diagnostics for every created layer
 * would put `toString()` on a hot path; it belongs on the error path only.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@NotThreadSafe
@RequiredArgsConstructor
class TransactionalLayerEntry<T> {
	/**
	 * Contains the object that created the diff layer and that the layer belongs to.
	 */
	@Nonnull @Getter private final TransactionalLayerCreator<T> creator;
	/**
	 * Contains the object that {@link io.evitadb.index.array.TransactionalObject} uses to track the changes made upon
	 * the immutable state.
	 */
	@Nonnull @Getter private final T item;
	/**
	 * Contains state of the transactional layer - used to track whether all states were applied during
	 * {@link TransactionalLayerMaintainer#commit()}.
	 */
	@Getter private TransactionalLayerState state = TransactionalLayerState.ALIVE;

	/**
	 * Sets the transactional layer as used - discarded. There is no way back.
	 */
	public void discard() {
		Assert.isPremiseValid(this.state == TransactionalLayerState.ALIVE, "Item has been already discarded!");
		this.state = TransactionalLayerState.DISCARDED;
	}

}
