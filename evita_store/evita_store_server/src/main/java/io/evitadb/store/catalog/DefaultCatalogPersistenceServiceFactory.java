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

package io.evitadb.store.catalog;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.core.executor.ClientRunnableTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.store.catalog.task.RestoreTask;
import io.evitadb.store.exception.InvalidStoragePathException;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.CatalogPersistenceServiceFactory;
import io.evitadb.store.spi.exception.DirectoryNotEmptyException;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.UUID;

/**
 * This implementation is the single and only implementation of {@link CatalogPersistenceServiceFactory}. Instance is
 * created and located by {@link java.util.ServiceLoader} pattern.
 *
 * @see CatalogPersistenceServiceFactory
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class DefaultCatalogPersistenceServiceFactory implements CatalogPersistenceServiceFactory {

	@Nonnull
	@Override
	public CatalogPersistenceService createNew(
		@Nonnull CatalogContract catalogInstance,
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ExportFileService exportFileService
		) {
		return new DefaultCatalogPersistenceService(
			catalogName, storageOptions, transactionOptions, scheduler, exportFileService
		);
	}

	@Nonnull
	@Override
	public CatalogPersistenceService load(
		@Nonnull CatalogContract catalogInstance,
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ExportFileService exportFileService
	) {
		return new DefaultCatalogPersistenceService(
			catalogInstance, catalogName, storageOptions, transactionOptions, scheduler, exportFileService
		);
	}

	@Nonnull
	@Override
	public ClientRunnableTask<? extends FileIdCarrier> restoreCatalogTo(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull UUID fileId,
		@Nonnull Path pathToFile,
		long totalBytesExpected,
		boolean deleteAfterRestore
	) throws DirectoryNotEmptyException, InvalidStoragePathException {
		return new RestoreTask(
			catalogName,
			fileId,
			pathToFile,
			totalBytesExpected,
			deleteAfterRestore,
			storageOptions
		);
	}



}
