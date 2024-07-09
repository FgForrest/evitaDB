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

package io.evitadb.store.catalog.task;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.core.async.ClientRunnableTask;
import io.evitadb.core.async.Interruptible;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.task.RestoreTask.RestoreSettings;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CountingInputStream;
import io.evitadb.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * This task is used to restore a catalog from a ZIP file.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class RestoreTask extends ClientRunnableTask<RestoreSettings> {
	private final StorageOptions storageOptions;

	/**
	 * Returns the file name with renaming the files that contain original catalog name.
	 *
	 * @param entryName           relative path in the ZIP file
	 * @param originalCatalogName original catalog name
	 * @param catalogName         the new catalog name
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

	public RestoreTask(
		@Nonnull String catalogName,
		@Nonnull InputStream inputStream,
		long totalSizeInBytes,
		@Nonnull StorageOptions storageOptions
	) {
		super(
			catalogName,
			"Restore catalog `" + catalogName + "`",
			new RestoreSettings(inputStream, storageOptions.exportDirectory(), totalSizeInBytes),
			task -> ((RestoreTask) task).doRestore()
		);
		this.storageOptions = storageOptions;
	}

	/**
	 * Restores the catalog from the input stream.
	 */
	private void doRestore() {
		// unzip contents of the stream
		final TaskStatus<RestoreSettings, Void> status = getStatus();
		final String catalogName = status.catalogName();

		log.info("Restoring catalog `{}` from file `{}`.", catalogName, status.settings().fileName());

		final Path inputFile = this.storageOptions.exportDirectory().resolve(status.settings().fileName());
		try (
			final CountingInputStream cis = new CountingInputStream(
				new BufferedInputStream(
					Files.newInputStream(inputFile, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE)
				)
			);
			final ZipInputStream zipInputStream = new ZipInputStream(cis)
		) {
			final Path storagePath = DefaultCatalogPersistenceService.pathForCatalog(catalogName, this.storageOptions.storageDirectoryOrDefault());
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
						readBlock(cis, fileChannel, buffer, bytesRead);
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

			log.info("Catalog `{}` restored from file `{}`.", catalogName, status.settings().fileName());
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Unexpected exception occurred while restoring catalog: " + e.getMessage(),
				"Unexpected exception occurred while restoring catalog!",
				e
			);
		}
	}

	/**
	 * Reads block of data from the input stream and writes it to the file channel.
	 */
	@Interruptible
	private void readBlock(
		@Nonnull CountingInputStream cis,
		@Nonnull FileChannel fileChannel,
		@Nonnull ByteBuffer buffer,
		int bytesRead
	) throws IOException {
		buffer.limit(bytesRead);
		while (buffer.hasRemaining()) {
			fileChannel.write(buffer);
		}
		buffer.clear();
		updateProgress((int) ((cis.getCount() * 100L) / getStatus().settings().totalSizeInBytes()));
	}

	/**
	 * Settings for this instance of restore task.
	 *
	 * @param fileName         name of the file to be restored
	 * @param totalSizeInBytes total size of the file in bytes
	 */
	public record RestoreSettings(
		@Nonnull String fileName,
		long totalSizeInBytes
	) implements Serializable {

		public RestoreSettings(@Nonnull InputStream inputStream, @Nonnull Path export, long totalSizeInBytes) {
			this(UUIDUtil.randomUUID() + ".zip", totalSizeInBytes);
			try {
				// todo jno avoid copying the file to the disk when it's already in place
				final long bytesCopied = Files.copy(
					inputStream, export.resolve(fileName()),
					StandardCopyOption.REPLACE_EXISTING
				);
				Assert.isPremiseValid(
					bytesCopied == totalSizeInBytes,
					"Unexpected number of bytes copied (" + bytesCopied + "B instead of " + totalSizeInBytes + "B)!"
				);
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Unexpected exception occurred while storing catalog file for restoration: " + e.getMessage(),
					"Unexpected exception occurred while storing catalog file for restoration!",
					e
				);
			}
		}

	}

}
