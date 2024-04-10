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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.spi.model.reference;

import io.evitadb.store.model.FileLocation;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.model.CatalogVariableContentFileReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;

/**
 * A class representing a reference to a WAL (Write-Ahead Log) file.
 *
 * @param catalogName    The name of the catalog.
 * @param fileIndex      The index of the WAL file incremented each time the WAL file is rotated.
 * @param fileLocation   The location of the last processed transaction of the WAL file.
 */
public record WalFileReference(
	@Nonnull String catalogName,
	int fileIndex,
	@Nullable FileLocation fileLocation
) implements CatalogVariableContentFileReference {

	@Override
	@Nonnull
	public Path toFilePath(@Nonnull Path catalogFolder) {
		return catalogFolder.resolve(
			CatalogPersistenceService.getWalFileName(catalogName, fileIndex)
		);
	}

	@Override
	@Nonnull
	public WalFileReference incrementAndGet() {
		return new WalFileReference(catalogName, fileIndex + 1, null);
	}

}
