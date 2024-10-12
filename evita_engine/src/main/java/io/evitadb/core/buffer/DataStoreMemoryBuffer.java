/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.buffer;

import io.evitadb.core.EntityCollection;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.store.model.StoragePart;

import javax.annotation.Nonnull;
import java.util.function.Function;

/**
 * DataStoreMemoryBuffer represents volatile temporal memory between the {@link EntityCollection} and persistent
 * storage that keeps frequently changed data in the {@link DataStoreMemoryBuffer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface DataStoreMemoryBuffer extends DataStoreReader {

	/**
	 * Collects information about dirty indexes that need to be persisted. If transaction is opened, the changes
	 * are written only in the transactional layer and persisted at moment when transaction is committed. When
	 * transaction is not opened the changes are not immediately written to the persistent storage but trapped in shared
	 * memory and will be written when buffer is flushed. This is usually the case when entity index is just being
	 * created for the first time and the transaction were not yet enabled on it.
	 */
	<IK extends IndexKey, I extends Index<IK>> I getOrCreateIndexForModification(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing);

	/**
	 * Removes {@link EntityIndex} from the change set. After removal (either successfully or unsuccessful)
	 * `removalPropagation` function is called to propagate deletion to the origin collection.
	 */
	<IK extends IndexKey, I extends Index<IK>> I removeIndex(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> removalPropagation);

	/**
	 * Removes container from the target storage. If transaction is open, it just marks the container as removed but
	 * doesn't really remove it.
	 */
	<T extends StoragePart> boolean removeByPrimaryKey(long catalogVersion, long primaryKey, @Nonnull Class<T> entityClass);

	/**
	 * Removes container from the target storage in the memory only. Changes are written at the moment when
	 * buffer is flushed and {@link #getTrappedChanges()} is called.
	 */
	<T extends StoragePart> boolean trapRemoveByPrimaryKey(long catalogVersion, long primaryKey, @Nonnull Class<T> entityClass);

	/**
	 * Inserts or updates container in the target storage. If transaction is opened, the changes are written only in
	 * the transactional layer and are not really written to the persistent storage. Changes are written at the moment
	 * when transaction is committed.
	 */
	<T extends StoragePart> void update(long catalogVersion, @Nonnull T value);

	/**
	 * Inserts or updates container in the target storage in the memory only. Changes are written at the moment when
	 * buffer is flushed and {@link #getTrappedChanges()} is called.
	 */
	<T extends StoragePart> void trapUpdate(long catalogVersion, @Nonnull T value);

	/**
	 * Method returns current buffer with trapped changes.
	 */
	DataStoreChanges getTrappedChanges();

}
