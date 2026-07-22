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

/**
 * This extension of {@link TransactionalStateProducer} owns **no** transactional memory diff piece. It should be used in
 * all objects that maintain transactionally modifiable internal data fields but cannot be modified by themselves. I.e.
 * they hold no diff of their own, but they need to provide a `createCopyWithMergedTransactionalMemory` implementation so
 * that they can create a new instance consisting of new internal objects.
 *
 * Such objects deliberately are **not** {@link TransactionalLayerCreator}s: having no id, they cannot be looked up in
 * the diff-layer registry at all, which is what makes it impossible for one of them to be handed a layer belonging to a
 * different object. They also skip the registry lookup entirely during the merge cascade, where it would be guaranteed
 * to miss.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public interface VoidTransactionMemoryProducer<S> extends TransactionalStateProducer<S> {
}
