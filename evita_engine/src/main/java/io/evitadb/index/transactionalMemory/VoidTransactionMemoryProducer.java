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
 * This extension of {@link TransactionalLayerProducer} produces {@link Void} transactional memory diff piece. It should
 * be used in all all objects that maintain transactionally modifiable internal data fields but cannot be modified by
 * themselves. I.e. their transactional diff piece is Void (NULL), but they need to provide
 * {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer, Transaction)} implementation
 * so that the could create new instance consisting of new internal objects.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public interface VoidTransactionMemoryProducer<S> extends TransactionalLayerProducer<Void, S> {

	@Override
	default long getId() {
		return 1L;
	}

	@Override
	default Void createLayer() {
		throw new UnsupportedOperationException("This object doesn't handle changes directly!");
	}

}