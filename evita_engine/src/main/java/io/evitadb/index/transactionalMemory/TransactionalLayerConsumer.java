/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.index.transactionalMemory;

import io.evitadb.core.Transaction;

/**
 * Interface implementation contains logic, that applies cherry-picked changes from {@link TransactionalLayerMaintainer} upon
 * immutable state and by using {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer, Transaction)}
 * creates new instances respecting both initial state and the diff upon it.
 *
 * Consumers never alter original objects and always produce new ones. Consumers don't usually make deep copy of the original
 * object but rather only create new envelope which reuses untouched parts of the original object and replaces the parts
 * that were altered in the transactional diff.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public interface TransactionalLayerConsumer {

	/**
	 * Contains logic that calls {@link TransactionalLayerMaintainer#getStateCopyWithCommittedChanges(TransactionalLayerProducer, Transaction)}
	 * which internally calls {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer, Transaction)}
	 * and also unregisters the transactional piece with diff from the internal memory. At the end of the transaction
	 * there must be no diff left (all must be processed/consumed by TransactionalLayerConsumer implementations.
	 */
	void collectTransactionalChanges(TransactionalLayerMaintainer transactionalLayer);

}