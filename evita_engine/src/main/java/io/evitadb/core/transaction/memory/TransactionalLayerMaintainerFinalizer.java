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
import javax.annotation.Nullable;
import java.io.Closeable;

/**
 * Interface implementation contains logic, that applies cherry-picked changes from {@link TransactionalLayerMaintainer} upon
 * immutable state and by using {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)}
 * creates new instances respecting both initial state and the diff upon it.
 *
 * Consumers never alter original objects and always produce new ones. Consumers don't usually make deep copy of the original
 * object but rather only create new envelope which reuses untouched parts of the original object and replaces the parts
 * that were altered in the transactional diff.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public interface TransactionalLayerMaintainerFinalizer {

	/**
	 * Contains logic that calls {@link TransactionalLayerMaintainer#getStateCopyWithCommittedChanges(TransactionalLayerProducer)}
	 * which internally calls {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)}
	 * and also unregisters the transactional piece with diff from the internal memory.
	 */
	void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer);

	/**
	 * Rolls back the changes made in a transactional layer and frees related {@link Closeable} resources.
	 *
	 * @param transactionalLayer the transactional layer to rollback changes from
	 * @param cause              the cause of the rollback
	 */
	void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause);

}
