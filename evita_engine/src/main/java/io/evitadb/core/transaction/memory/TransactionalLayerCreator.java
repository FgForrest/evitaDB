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

/**
 * Interface allowing classes to participate in a transaction memory handling process. Implementations should implement
 * all get / set state methods in following pattern (these accessors are normally reached through the static
 * {@link io.evitadb.core.transaction.Transaction} facade rather than {@link TransactionalMemory} directly):
 *
 * ``` java
 * GET:
 *      final Changes layer = Transaction.getTransactionalMemoryLayerIfExists(this);
 * 		if (layer == null) {
 * 			// execute original logic
 *      } else {
 * 			// execute logic and propagate all changes captured in transactional layer (Changes object)
 *      }
 *
 * SET:
 *      final Changes layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
 * 		if (layer == null) {
 * 			// execute original logic
 *      } else {
 * 			// put changes to the transactional layer
 *      }
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public interface TransactionalLayerCreator<T> {

	/**
	 * Uniquely identifies this instance among **all** live {@link TransactionalLayerCreator} instances, not merely among
	 * instances of the same class. The id must not change in time - it connects the origin object with its transactional
	 * state in memory, and is the sole key of the diff-layer registry.
	 *
	 * Ids must be drawn from {@link TransactionalObjectVersion#SEQUENCE}, which is what guarantees the global
	 * uniqueness this contract requires. Returning a constant, or an id from any other source, lets one object be handed
	 * a diff layer belonging to another - {@link TransactionalLayerMaintainer} therefore verifies the invariant and
	 * fails loudly on a collision.
	 *
	 * The value {@link TransactionalObjectVersion#NO_LAYER_ID} is reserved to denote "no layer" and is never emitted by
	 * the sequence, so it must never be returned here.
	 */
	long getId();

	/**
	 * Creates and returns new instance of the transactional memory instance. There is only single instance of
	 * the transactional state in single transactional layer - transactional state is linked with origin object via
	 * {@link #getId()} value.
	 */
	T createLayer();

}
