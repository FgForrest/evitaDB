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

package io.evitadb.store.catalog;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.scheduling.Scheduler;
import io.evitadb.store.exception.InvalidStoragePathException;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.CatalogPersistenceServiceFactory;
import io.evitadb.store.spi.exception.DirectoryNotEmptyException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
		@Nonnull Scheduler scheduler
	) {
		return new DefaultCatalogPersistenceService(
			catalogName, storageOptions, transactionOptions, scheduler
		);
	}

	@Nonnull
	@Override
	public CatalogPersistenceService load(
		@Nonnull CatalogContract catalogInstance,
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler
	) {
		return new DefaultCatalogPersistenceService(
			catalogInstance, catalogName, storageOptions, transactionOptions, scheduler
		);
	}

	@Nonnull
	@Override
	public Path restoreCatalogTo(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull InputStream inputStream
	) throws DirectoryNotEmptyException, InvalidStoragePathException {
		// unzip contents of the stream
		try (final ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
			final Path storagePath = DefaultCatalogPersistenceService.pathForNewCatalog(catalogName, storageOptions.storageDirectoryOrDefault());
			DefaultCatalogPersistenceService.verifyDirectory(storagePath, true);

			ZipEntry entry = zipInputStream.getNextEntry();
			Assert.isPremiseValid(entry.isDirectory(), "First entry in the zip file must be a directory!");
			// last character is always a slash
			final String directoryName = entry.getName().substring(0, entry.getName().length() - 1);
			// allocate buffer for reading
			final ByteBuffer buffer = ByteBuffer.allocate(16_384);
			while ((entry = zipInputStream.getNextEntry()) != null) {
				// get the name of the file in the zip and create the file in the storage
				final String fileName = getFileNameWithCatalogRename(entry.getName(), directoryName, catalogName);
				final Path entryPath = storagePath.resolve(fileName);
				try (final FileChannel fileChannel = FileChannel.open(entryPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
					// read the file from the zip and write it to the storage
					int bytesRead;
					while ((bytesRead = zipInputStream.read(buffer.array())) != -1) {
						buffer.limit(bytesRead);
						while (buffer.hasRemaining()) {
							fileChannel.write(buffer);
						}
						buffer.clear();
					}
				}
			}
			// write file marking the catalog as restored
			Assert.isPremiseValid(
				storagePath.resolve(CatalogPersistenceService.RESTORE_FLAG).toFile().createNewFile(),
				() -> new UnexpectedIOException(
					"Unexpected exception occurred while restoring catalog " + catalogName + ": unable to create restore flag file!",
					"Unexpected exception occurred while restoring catalog - unable to create restore flag file!"
				)
			);
			return storagePath;
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Unexpected exception occurred while restoring catalog: " + e.getMessage(),
				"Unexpected exception occurred while restoring catalog!",
				e
			);
		}
	}

	/**
	 * Returns the file name with renaming the files that contain original catalog name.
	 *
	 * @param entryName relative path in the ZIP file
	 * @param originalCatalogName original catalog name
	 * @param catalogName the new catalog name
	 * @return the file name with renaming
	 */
	@Nonnull
	private static String getFileNameWithCatalogRename(
		@Nonnull String entryName,
		@Nonnull String originalCatalogName,
		@Nonnull String catalogName
	) {
		final String fileName = entryName.substring(originalCatalogName.length() + 1);
		if (entryName.endsWith(CatalogPersistenceService.BOOT_FILE_SUFFIX)) {
			return CatalogPersistenceService.getCatalogBootstrapFileName(catalogName);
		} else if (entryName.endsWith(CatalogPersistenceService.CATALOG_FILE_SUFFIX)) {
			final Matcher matcher = CatalogPersistenceService.getCatalogDataStoreFileNamePattern(originalCatalogName)
				.matcher(fileName);
			Assert.isPremiseValid(
				matcher.matches(),
				"Invalid file name for catalog date file!"
			);
			return CatalogPersistenceService.getCatalogDataStoreFileName(
				catalogName, Integer.parseInt(matcher.group(1))
			);
		} else if (entryName.endsWith(CatalogPersistenceService.WAL_FILE_SUFFIX)) {
			final int walIndex = CatalogPersistenceService.getIndexFromWalFileName(originalCatalogName, fileName);
			return CatalogPersistenceService.getWalFileName(catalogName, walIndex);
		} else {
			return fileName;
		}
	}
}
