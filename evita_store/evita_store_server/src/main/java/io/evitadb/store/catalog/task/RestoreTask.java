/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.core.executor.ClientRunnableTask;
import io.evitadb.core.executor.Interruptible;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceServiceFactory.CatalogFolderAllocator;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceServiceFactory.FileIdCarrier;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.task.RestoreTask.RestoreSettings;
import io.evitadb.store.catalog.task.stream.CountingInputStream;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * This task is used to restore a catalog from a ZIP file.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class RestoreTask extends ClientRunnableTask<RestoreSettings> {
	/**
	 * Storage configuration, supplying the root directory the folder token is resolved against.
	 */
	private final StorageOptions storageOptions;
	/**
	 * Allocates the folder the catalog is restored into. Bound by the engine rather than derived from the catalog
	 * name — see `CatalogFolderId` and issue #649. Consulted from {@link #doRestore()} rather than from the
	 * constructor, because allocating creates a directory and claims the catalog name, and this task is created
	 * long before it runs when the archive arrives over a chunked upload.
	 */
	private final CatalogFolderAllocator catalogFolderAllocator;

	/**
	 * Returns the name the archived file must be written under, carrying the restored catalog's name.
	 *
	 * The source prefix is deliberately not a parameter. An archive taken from a renamed catalog carries a prefix
	 * that no longer matches its own top-level directory, so deriving the file name by stripping that directory's
	 * length — as this did — mis-slices the name. The rewrite instead decomposes the incoming name by its suffix
	 * and trailing index alone, which is independent of whatever it starts with. See issue #649.
	 *
	 * @param entryName   relative path in the ZIP file, including its top-level directory
	 * @param catalogName the name of the catalog being restored into
	 * @return the file name carrying the restored catalog's name
	 */
	@Nonnull
	private static String getFileNameWithCatalogRename(
		@Nonnull String entryName,
		@Nonnull String catalogName
	) {
		final int directorySeparator = entryName.indexOf('/');
		final String fileName = directorySeparator < 0 ?
			entryName : entryName.substring(directorySeparator + 1);
		return CatalogFileNaming.canonicalizeTo(fileName, catalogName);
	}

	public RestoreTask(
		@Nonnull String catalogName,
		@Nonnull CatalogFolderAllocator catalogFolderAllocator,
		@Nonnull UUID fileId,
		@Nonnull Path pathToFile,
		long totalSizeInBytes,
		boolean deleteAfterRestore,
		@Nonnull StorageOptions storageOptions
	) {
		super(
			catalogName,
			RestoreTask.class.getSimpleName(),
			"Restore catalog `" + catalogName + "`",
			new RestoreSettings(
				fileId,
				pathToFile,
				totalSizeInBytes,
				deleteAfterRestore
			),
			task -> ((RestoreTask) task).doRestore(),
			TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED
		);
		this.storageOptions = storageOptions;
		this.catalogFolderAllocator = catalogFolderAllocator;
	}

	/**
	 * Restores the catalog from the input stream.
	 */
	private void doRestore() {
		// unzip contents of the stream
		final TaskStatus<RestoreSettings, Void> status = getStatus();
		final String catalogName = Objects.requireNonNull(status.catalogName());

		final Path inputFile = status.settings().pathToFile();
		log.info("Restoring catalog `{}` from file `{}`.", catalogName, inputFile);

		try (
			final CountingInputStream cis = new CountingInputStream(
				new BufferedInputStream(
					status.settings().deleteAfterRestore() ?
						Files.newInputStream(inputFile, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE) :
						Files.newInputStream(inputFile, StandardOpenOption.READ)
				)
			);
			final ZipInputStream zipInputStream = new ZipInputStream(cis)
		) {
			// the folder is claimed here rather than when this task was built - inside the try, so that a refusal
			// still closes the stream and takes the abandoned upload's archive down with it
			final CatalogFolderId catalogFolderId = this.catalogFolderAllocator.allocate();
			final Path storagePath = this.storageOptions.storageDirectory().resolve(catalogFolderId.id());
			DefaultCatalogPersistenceService.verifyDirectory(storagePath, true);

			ZipEntry entry = Objects.requireNonNull(zipInputStream.getNextEntry());
			Assert.isPremiseValid(entry.isDirectory(), "First entry in the zip file must be a directory!");
			// allocate buffer for reading
			final ByteBuffer buffer = ByteBuffer.allocate(16_384);
			while ((entry = zipInputStream.getNextEntry()) != null) {
				// get the name of the file in the zip and create the file in the storage
				final String fileName = getFileNameWithCatalogRename(entry.getName(), catalogName);
				final Path entryPath = storagePath.resolve(fileName).normalize();
				Assert.isTrue(entryPath.startsWith(storagePath), "Bad ZIP entry!");
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

			log.info("Catalog `{}` restored from file `{}`.", catalogName, inputFile);
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
		updateProgress((int) (((float) cis.getCount() / (float) getStatus().settings().totalSizeInBytes()) * 100));
	}

	/**
	 * Settings for this instance of restore task.
	 *
	 * @param fileId             The ID of the file to be restored.
	 * @param pathToFile         path to the file to be restored
	 * @param totalSizeInBytes   total size of the file in bytes
	 * @param deleteAfterRestore whether to delete the ZIP file after restore
	 */
	public record RestoreSettings(
		@Nonnull UUID fileId,
		@Nonnull Path pathToFile,
		long totalSizeInBytes,
		boolean deleteAfterRestore
	) implements Serializable, FileIdCarrier {

		@Nonnull
		@Override
		public String toString() {
			return "FileName: `" + this.pathToFile + '`' +
				", totalSizeInBytes: " + StringUtils.formatByteSize(this.totalSizeInBytes);
		}
	}

}
