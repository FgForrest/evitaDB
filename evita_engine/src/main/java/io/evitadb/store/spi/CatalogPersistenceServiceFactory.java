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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.spi;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.store.exception.InvalidStoragePathException;
import io.evitadb.store.spi.exception.DirectoryNotEmptyException;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.UUID;

/**
 * This interface and layer of abstraction was introduced because we want to split data storage / serialization and
 * the evitaDB implementation into separate modules, and we need to avoid circular dependency. The data storage
 * refers to the evitaDB business objects, but also evitaDB needs to call persistence logic via. defined interfaces.
 * Therefore, we used {@link java.util.ServiceLoader} pattern to dynamically locate proper but single implementation
 * of this interface and link these modules in runtime.
 *
 * The reasons for splitting evitaDB into main and storage module include:
 *
 * - we don't want to open interfaces of main domain objects such as {@link io.evitadb.api.requestResponse.data.structure.Entity} and
 *   others as public (constructors, fields, methods) in order the standard code don't use them directly - but still we
 *   want (de)serializers in this module to use exclusive internal ways of their instantiation
 *
 *   That's why certain fields / constructors are package protected and the serializer resides in the same package, but
 *   different module. If we wouldn't split the serializers into the different module, the original evitaDB would get
 *   polluted with both serialization and business logic that we want to avoid.
 *
 * - we wanted to also abstract from storage internals so that the business logic of evitaDB is not tightly coupled
 *   with the storage implementation
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CatalogPersistenceServiceFactory {

	/**
	 * Creates new instance of {@link CatalogPersistenceService}. For reasons this interface exists please see JavaDoc
	 * of the class.
	 */
	@Nonnull
	CatalogPersistenceService createNew(
		@Nonnull CatalogContract catalogInstance,
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ExportFileService exportFileService
	);

	/**
	 * Creates new instance of {@link CatalogPersistenceService}. For reasons this interface exists please see JavaDoc
	 * of the class.
	 */
	@Nonnull
	CatalogPersistenceService load(
		@Nonnull CatalogContract catalogInstance,
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ExportFileService exportFileService
	);

	/**
	 * Checks whether it's possible to create catalog of particular name in the storage directory.
	 *
	 * @param catalogName name of the catalog
	 * @param storageOptions storage options
	 * @param fileId			   The ID of the file to be restored.
	 * @param pathToFile path to the file to be restored
	 * @param totalBytesExpected total bytes expected to be read from the input stream
	 * @param deleteAfterRestore whether to delete the ZIP file after restore
	 * @return normalized name of the catalog to be created in storage directory
	 *
	 * @throws DirectoryNotEmptyException if the directory is not empty
	 * @throws InvalidStoragePathException if the storage path is invalid
	 */
	@Nonnull
	ServerTask<? extends FileIdCarrier, Void> restoreCatalogTo(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull UUID fileId,
		@Nonnull Path pathToFile,
		long totalBytesExpected,
		boolean deleteAfterRestore
	) throws DirectoryNotEmptyException, InvalidStoragePathException;

	/**
	 * This interface is implemented by a task settings that take care of catalog restoration.
	 */
	interface FileIdCarrier {

		/**
		 * Retrieves the unique identifier for the file associated with this task settings.
		 *
		 * @return A non-null UUID representing the unique identifier of the file.
		 */
		@Nonnull
		UUID fileId();

	}

}
