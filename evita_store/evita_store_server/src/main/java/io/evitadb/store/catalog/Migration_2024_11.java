/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.store.catalog;


import io.evitadb.exception.ObsoleteStorageProtocolException;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.PersistenceService;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Migration interface containing one-time migration logic for upgrading data storage structures from version prior to
 * 2024.11 to version 2025.1 and newer. The migration primarily handles renaming of entity collection files to include
 * primary keys in their names, improving the storage structure and organization.
 *
 * This migration is required when upgrading from storage protocol version 1 to version 2.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @deprecated introduced with ##41 and could be removed later when no version prior to 2024.11 is used
 */
@Deprecated(since = "2024.11", forRemoval = true)
public interface Migration_2024_11 {
	/**
	 * Upgrades the storage protocol from version 1 to version 2. In the version 2 the entity collection files were
	 * renamed and contains the primary key in their name.
	 *
	 * @param catalogHeader                 the catalog header
	 * @param catalogStoragePath            the path to the catalog storage directory
	 * @param postUpgradeAction             action to write current storage protocol to catalog header after the upgrade
	 * @deprecated introduced with ##41 and could be removed later when no version prior to 2024.11 is used
	 */
	@Deprecated(since = "2024.1", forRemoval = true)
	static void upgradeFromStorageProtocolVersion_1_to_2(
		@Nonnull CatalogHeader catalogHeader,
		@Nonnull Path catalogStoragePath,
		@Nonnull Runnable postUpgradeAction
	) {
		// upgrade from version 1 to version 2
		ConsoleWriter.writeLine(
			"Catalog `" + catalogHeader.catalogName() + "` uses deprecated entity collection data file names of storage protocol version 1.",
			ConsoleColor.BRIGHT_BLUE, ConsoleDecoration.BOLD
		);
		// in this version the files were renamed and contains the index in their filename
		try {
			for (CollectionFileReference entityTypeFileIndex : catalogHeader.getEntityTypeFileIndexes()) {
				Files.move(
					catalogStoragePath.resolve(StringUtils.toCamelCase(
						entityTypeFileIndex.entityType()) + '_' + entityTypeFileIndex.fileIndex() + CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX),
					catalogStoragePath.resolve(
						CatalogPersistenceService.getEntityCollectionDataStoreFileName(
							entityTypeFileIndex.entityType(),
							entityTypeFileIndex.entityTypePrimaryKey(),
							entityTypeFileIndex.fileIndex()
						)
					),
					StandardCopyOption.ATOMIC_MOVE
				);
			}

			postUpgradeAction.run();

			ConsoleWriter.writeLine(
				"Catalog `" + catalogHeader.catalogName() + "` successfully upgraded from storage protocol version 1 to version " + PersistenceService.STORAGE_PROTOCOL_VERSION + ".",
				ConsoleColor.BRIGHT_BLUE, ConsoleDecoration.BOLD
			);
		} catch (IOException e) {
			throw new ObsoleteStorageProtocolException(
				"Failed to upgrade storage protocol from the version: " + catalogHeader.storageProtocolVersion() + ", " +
					"to: " + PersistenceService.STORAGE_PROTOCOL_VERSION,
				"Failed to upgrade storage protocol."
			);
		}
	}
}
