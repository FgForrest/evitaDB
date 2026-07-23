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

/**
 * Interface allowing classes to participate in a transaction memory handling process. This specific form of the transactional
 * layer provider merges the changes to the separate copy of the original object.
 *
 * It is the common case: the object both **owns a diff layer** ({@link TransactionalLayerCreator}) and **produces its
 * committed form** ({@link TransactionalStateProducer}). Objects that only produce a copy, without owning a layer,
 * implement {@link VoidTransactionMemoryProducer} instead.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
public interface TransactionalLayerProducer<DIFF_PIECE, COPY>
	extends TransactionalLayerCreator<DIFF_PIECE>, TransactionalStateProducer<COPY> {

	/**
	 * Resolves this object's own diff layer and merges it, disposing of the layer afterwards.
	 *
	 * Implementations must not override this method - implement
	 * {@link #createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)} instead. Overriding it
	 * would bypass layer resolution and disposal; the layer would then stay `ALIVE` and
	 * {@link TransactionalLayerMaintainer#verifyLayerWasFullySwept()} would fail the commit rather than silently losing
	 * the changes.
	 */
	@Nonnull
	@Override
	default COPY createCopyWithMergedTransactionalMemory(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		return transactionalLayer.copyWithOwnLayer(this);
	}

	/**
	 * Merges changes / differences captured in a transactional layer to separate (new) cloned object instance.
	 * Merge is ought to be performed deep-wise. Ie. if current object contains references to other
	 * {@link TransactionalStateProducer} instances it's required to obtain their committed form through
	 * {@link TransactionalLayerMaintainer#getStateCopyWithCommittedChanges(TransactionalStateProducer)} when collecting
	 * changes to the return instance.
	 *
	 * @param layer              the transactional memory of this object that holds the difference protocol to apply to get updated version of the object
	 * @param transactionalLayer object that provides access to entire transactional memory so that it can be manipulated
	 */
	@Nonnull
	COPY createCopyWithMergedTransactionalMemory(
		@Nullable DIFF_PIECE layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	);

}
