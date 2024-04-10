/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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

import javax.annotation.concurrent.NotThreadSafe;

/**
 * This wrapper envelopes the object that {@link io.evitadb.index.array.TransactionalObject} uses to track the changes
 * made upon the immutable state. It also maintains information about it's state so that we can count all created
 * instances during {@link TransactionalLayerMaintainer#commit()} and verify all of them were processed and applied.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@NotThreadSafe
@RequiredArgsConstructor
class TransactionalLayerWrapper<T> {
	/**
	 * Contains the object that {@link io.evitadb.index.array.TransactionalObject} uses to track the changes made upon
	 * the immutable state.
	 */
	@Getter private final T item;
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
