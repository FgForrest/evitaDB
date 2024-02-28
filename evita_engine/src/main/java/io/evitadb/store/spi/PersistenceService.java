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

import io.evitadb.core.buffer.DataStoreIndexChanges;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.store.model.StoragePart;

import javax.annotation.Nonnull;
import java.io.Closeable;

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
