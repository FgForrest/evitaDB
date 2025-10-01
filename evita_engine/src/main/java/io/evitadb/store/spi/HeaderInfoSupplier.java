/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.spi;

import io.evitadb.core.EntityCollection;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.store.spi.model.EntityCollectionHeader;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.OptionalInt;

/**
 * This interface provides methods to retrieve information about the header of an EntityCollection and allows to call
 * back the information in the {@link EntityCollection} from the persistence layer when creating {@link EntityCollectionHeader}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface HeaderInfoSupplier {

	/**
	 * Returns the value of the last assigned primary key in {@link EntityCollection}.
	 *
	 * @return the value of the last assigned primary key
	 */
	int getLastAssignedPrimaryKey();

	/**
	 * Returns the value of the last assigned index key in {@link EntityCollection}.
	 *
	 * @return the value of the last assigned index key
	 */
	int getLastAssignedIndexKey();

	/**
	 * Returns the value of the last assigned internal price id in {@link EntityCollection}.
	 *
	 * @return the value of the last assigned internal price id
	 */
	int getLastAssignedInternalPriceId();

	/**
	 * Retrieves the value of the global index key. The global index key is a key that is assigned to the {@link GlobalEntityIndex}
	 * within the {@link EntityCollection}. The index is created when the first entity is added to the collection.
	 *
	 * @return an optional integer representing the value of the global index key,
	 * or {@link OptionalInt#empty()} if the global index was not yet created (collection is empty)
	 */
	@Nonnull
	OptionalInt getGlobalIndexPrimaryKey();

	/**
	 * Retrieves the list of index keys in the {@link EntityCollection} excluding the global index key.
	 *
	 * @return a list of primary keys representing the index keys in the collection
	 */
	@Nonnull
	List<Integer> getIndexPrimaryKeys();

}
