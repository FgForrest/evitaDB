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

package io.evitadb.core.management;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.FolderLock;
import io.evitadb.utils.IOUtils;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * File management utility used by the engine to work with a single configurable work directory.
 *
 * This service encapsulates the low-level file-system operations and provides a small API for:
 *
 * - creating temporary files in the engine {@link StorageOptions#workDirectory() work directory}
 * - keeping track of selected temporary files that must be explicitly purged by the caller
 * - resolving paths for files represented by {@link io.evitadb.api.file.FileForFetch}
 * - guarding the work directory with a coarse-grained folder lock so multiple processes/instances
 *   do not operate on it concurrently
 *
 * The class implements {@link AutoCloseable}. On close it deletes all still-reserved files and
 * releases the folder lock. Always prefer try-with-resources when you acquire this service.
 *
 * Error handling
 *  - I/O problems are wrapped into {@link UnexpectedIOException}. Preconditions are verified via
 *    {@link Assert} and will also result in {@link UnexpectedIOException} when violated.
 *
 * Intended effect
 *  - By centralizing file operations here, higher-level engine components remain decoupled from
 *    concrete storage details and life-cycle concerns around temporary files and locking.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
public class FileManagementService implements Closeable {
	/**
	 * Folder lock to prevent concurrent access to the export directory.
	 */
	private final FolderLock folderLock;
	/**
	 * Storage options.
	 */
	private final StorageOptions storageOptions;
	/**
	 * List of reserved temporary files that won't be purged automatically.
	 */
	private final CopyOnWriteArrayList<Path> reservedFiles = new CopyOnWriteArrayList<>();

	/**
	 * Creates the service for the provided storage options.
	 *
	 * - Ensures the {@link StorageOptions#workDirectory() work directory} exists
	 * - Acquires a {@link FolderLock} for the directory to prevent concurrent access
	 *
	 * @param storageOptions non-null storage options that determine the work directory
	 * @throws UnexpectedIOException when the work directory cannot be created
	 */
	public FileManagementService(@Nonnull StorageOptions storageOptions) {
		this.storageOptions = storageOptions;
		final Path workDirectory = this.storageOptions.workDirectory();
		if (!workDirectory.toFile().exists()) {
			Assert.isPremiseValid(
				workDirectory.toFile().mkdirs(),
				() -> new UnexpectedIOException(
					"Failed to create export storage directory: " + workDirectory,
					"Failed to create export storage directory."
				)
			);
		}
		// init folder lock
		this.folderLock = new FolderLock(workDirectory);
	}

	/**
	 * Creates a temporary file with the given file name in the work directory.
	 *
	 * If a file with the same name already exists, it is deleted first and then recreated as an empty
	 * file.
	 *
	 * Note: the created file is not tracked as a managed (reserved) file. Use
	 * {@link #createManagedTempFile(String)} if you need the service to delete it on close.
	 *
	 * @param fileName non-null file name (relative to the work directory)
	 * @return non-null path to the newly created empty file
	 * @throws UnexpectedIOException when an I/O error occurs while creating the file
	 */
	@Nonnull
	public Path createTempFile(@Nonnull String fileName) {
		try {
			final Path filePath = this.storageOptions.workDirectory().resolve(fileName);
			Files.deleteIfExists(filePath);
			return Files.createFile(filePath);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to create temporary file: " + e.getMessage(),
				"Failed to create temporary the file.",
				e
			);
		}
	}

	/**
	 * Creates a temporary file using {@link #createTempFile(String)} and marks it as managed
	 * (reserved) by this service.
	 *
	 * Managed files are not implicitly deleted during the lifetime of this service. They are removed
	 * either when you call {@link #purgeManagedTempFile(Path)} explicitly or when the service is
	 * {@link #close() closed}. This makes it convenient to ensure proper cleanup via
	 * try-with-resources.
	 *
	 * @param fileName non-null file name (relative to the work directory)
	 * @return non-null path to the created temporary file
	 */
	@Nonnull
	public Path createManagedTempFile(@Nonnull String fileName) {
		final Path reservedFile = createTempFile(fileName);
		this.reservedFiles.add(reservedFile.normalize());
		return reservedFile;
	}

	/**
	 * Removes the specified path from the managed files list and deletes the file if it exists.
	 *
	 * It is safe to call this method multiple times for the same file. The file path is normalized
	 * before it is removed from the internal tracking structure.
	 *
	 * @param file non-null path of the temporary file to be purged
	 */
	public void purgeManagedTempFile(@Nonnull Path file) {
		this.reservedFiles.remove(file.normalize());
		FileUtils.deleteFileIfExists(file);
	}

	/**
	 * Resolves and returns the path to an existing temporary file with the given name in the work
	 * directory.
	 *
	 * A precondition check ensures the file exists.
	 *
	 * @param fileName non-null file name (relative to the work directory)
	 * @return non-null path to the existing file
	 * @throws UnexpectedIOException when the file does not exist
	 */
	@Nonnull
	public Path getTempFile(@Nonnull String fileName) {
		final Path tempFile = this.storageOptions.workDirectory().resolve(fileName);
		Assert.isPremiseValid(
			tempFile.toFile().exists(),
			() -> new UnexpectedIOException(
				"Temporary file does not exist: " + tempFile,
				"Temporary file does not exist."
			)
		);
		return tempFile;
	}

	/**
	 * Closes the service.
	 *
	 * - Deletes all still-reserved files created via {@link #createManagedTempFile(String)}
	 * - Releases the folder lock acquired for the work directory
	 */
	@Override
	public void close() {
		// delete reserved files
		for (Path reservedFile : this.reservedFiles) {
			FileUtils.deleteFileIfExists(reservedFile);
		}
		// unlock the folder
		IOUtils.closeQuietly(this.folderLock::close);
	}

}
