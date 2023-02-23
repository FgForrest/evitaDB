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

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * Implementations of this interface declare that they have inner fields which are of type {@link TransactionalLayerCreator}
 * and provide access to them.
 *
 * Interface is used in {@link TransactionalMemory#suppressTransactionalMemoryLayerFor(Object, Consumer)} to suppress
 * access to the transactional memory of those objects temporarily.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface TransactionalCreatorMaintainer {

	/**
	 * Returns collection of all {@link TransactionalLayerCreator} that this object maintains.
	 */
	@Nonnull
	Collection<TransactionalLayerCreator<?>> getMaintainedTransactionalCreators();

}
