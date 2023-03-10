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

package io.evitadb.store.spi.operation;

import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.DeferredStorageOperation;
import io.evitadb.store.spi.PersistenceService;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Implementation stores the {@link #storagePart} to the storage file mem-table.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class PutStoragePartOperation implements DeferredStorageOperation<PersistenceService> {
	/**
	 * Represents the storage part, that should be inserted or updated.
	 */
	@Nonnull
	private final StoragePart storagePart;

	@Nonnull
	@Override
	public Class<PersistenceService> getRequiredPersistenceServiceType() {
		return PersistenceService.class;
	}

	@Override
	public void execute(@Nonnull String owner, long transactionId, @Nonnull PersistenceService persistenceService) {
		persistenceService.putStoragePart(transactionId, storagePart);
	}

}
