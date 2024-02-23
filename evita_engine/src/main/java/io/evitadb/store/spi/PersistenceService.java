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

package io.evitadb.store.spi;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.core.buffer.DataStoreIndexChanges;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.store.model.StoragePart;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.util.function.Supplier;

/**
 * This interface defines shared methods for permited set of persistence services.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
sealed interface PersistenceService<IK extends IndexKey, I extends Index<IK>>
	extends Closeable
	permits CatalogPersistenceService, EntityCollectionPersistenceService {

	/**
	 * Returns true if underlying file was not yet created.
	 */
	boolean isNew();

	/**
	 * Returns underlying {@link StoragePartPersistenceService} which this instance uses for {@link StoragePart}
	 * persistence.
	 * @return underlying {@link StoragePartPersistenceService}
	 */
	@Nonnull
	StoragePartPersistenceService getStoragePartPersistenceService();

	/**
	 * Method initializes intermediate memory buffers keeper that are required when contents of the catalog are persisted.
	 * These buffers are not necessary when there are no updates to the catalog / collection, so it's wise to get rid
	 * of them if there is no actual need.
	 *
	 * The need is determined by the number of opened read write {@link EvitaSessionContract} to the catalog.
	 * If there is at least one opened read-write session we need to keep those outputs around. When there are only read
	 * sessions we don't need the outputs.
	 *
	 * The opening logic is responsible for calling {@link #release()} method that drops these buffers to the GC.
	 * TODO JNO - these methods will be moved to QueueWriter
	 *
	 * @see #release()
	 */
	void prepare();

	/**
	 * Method releases all intermediate (and large) write buffers and let the GC discard them.
	 * TODO JNO - these methods will be moved to QueueWriter
	 *
	 * @see #prepare()
	 */
	void release();

	/**
	 * Method combines {@link #prepare()} and {@link #release()} in a safe manner.
	 * If the write session is opened the prepare and release is not called.
	 * TODO JNO - these methods will be moved to QueueWriter
	 */
	<T> T executeWriteSafely(@Nonnull Supplier<T> lambda);

	/**
	 * Flushes all trapped memory data to the persistent storage.
	 * This method doesn't take transactional memory into an account but only flushes changes for trapped updates.
	 */
	void flushTrappedUpdates(long catalogVersion, @Nonnull DataStoreIndexChanges<IK, I> dataStoreIndexChanges);

	/**
	 * Returns true if the persistence service is closed.
	 * @return true if the persistence service is closed
	 */
	boolean isClosed();
}
