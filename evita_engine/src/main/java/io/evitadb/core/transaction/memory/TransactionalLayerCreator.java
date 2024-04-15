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

import javax.annotation.Nonnull;

import static io.evitadb.core.Transaction.getTransactionalLayerMaintainer;
import static java.util.Optional.ofNullable;

/**
 * Interface allowing classes to participate in a transaction memory handling process. Implementations should implement
 * all get / set state methods in following pattern:
 *
 * ``` java
 * GET:
 *      final Changes layer = TransactionalMemory.getTransactionalMemoryLayerIfExists(this);
 * 		if (layer == null) {
 * 			// execute original logic
 *      } else {
* 			// execute logic and propagate all changes captured in transactional layer (Changes object)
 *      }
 *
 * SET:
 *      final Changes layer = TransactionalMemory.getTransactionalMemoryLayer(this);
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
	 * Uniquely identifies instance of {@link TransactionalLayerCreator} among other instances of the same class.
	 * Each instance of the class must return unique id that doesn't change in time. Id connect origin object with
	 * its transactional states in memory.
	 *
	 * Using {@link TransactionalObjectVersion} is highly recommended.
	 */
	long getId();

	/**
	 * Creates and returns new instance of the transactional memory instance. There is only single instance of
	 * the transactional state in single transactional layer - transactional state is linked with origin object via
	 * {@link #getId()} value.
	 */
	T createLayer();

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
	 * @param transactionalLayer object that provides access to entire transactional memory so that it can be manipulated
	 */
	void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer);

}
