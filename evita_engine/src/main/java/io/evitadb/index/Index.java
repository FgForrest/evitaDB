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

package io.evitadb.index;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.store.model.StoragePart;

import javax.annotation.Nonnull;

/**
 * Index provides access to the read optimized data structures related to the client data. This base contract defines
 * shared methods that allow locating the index in persistent data storage and track the {@link StoragePart} that needs
 * to be persisted in order to have entire index present in durable place.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface Index<T extends IndexKey> {

	/**
	 * Returns a key uniquely representing the index in a persistent data store.
	 * For each index key there must be exactly one index present in the data storage.
	 */
	@Nonnull
	T getIndexKey();

	/**
	 * Returns collection of {@link StoragePart} that represents various sub-indexes the index consists of. Collection
	 * contains only those sub-indexes/parts that have been modified since last index flush to the storage.
	 */
	void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges);

}
