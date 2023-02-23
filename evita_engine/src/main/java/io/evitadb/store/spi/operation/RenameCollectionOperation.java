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

import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.DeferredStorageOperation;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Implementation renames entire entity collection data file.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class RenameCollectionOperation implements DeferredStorageOperation<CatalogPersistenceService> {
	/**
	 * Contains entity type that identifies the entity collection to rename.
	 */
	@Nonnull
	private final String entityType;
	/**
	 * Contains entity type that identifies the entity collection to rename to.
	 */
	@Nonnull
	private final String newEntityType;
	/**
	 * Contains the primary key of new entity type.
	 */
	private final int newEntityTypePrimaryKey;

	@Nonnull
	@Override
	public Class<CatalogPersistenceService> getRequiredPersistenceServiceType() {
		return CatalogPersistenceService.class;
	}

	@Override
	public void execute(@Nonnull String owner, long transactionId, @Nonnull CatalogPersistenceService persistenceService) {
		persistenceService.replaceCollectionWith(entityType, newEntityTypePrimaryKey, newEntityType, transactionId);
	}
}
