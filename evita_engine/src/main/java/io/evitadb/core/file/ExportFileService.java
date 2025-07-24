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

package io.evitadb.core.file;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.exception.InvalidFileNameException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.FolderLock;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * Export file service manages files that represents backups or some kind of recordings created by the database engine.
 * All is stored in single directory and for each data file there is metadata file with the same name and .metadata
 * extension that contains information about the file. This could be quite slow for large number of
 * files, but it's easy to start with. If performance becomes an issue, we can switch to more efficient approach
 * later - probably some kind of infrastructural database. Because we want to support clustered solutions we would have
 * to move to S3 storage or similar anyway.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ExportFileService implements Closeable {
	/**
	 * Folder lock to prevent concurrent access to the export directory.
	 */
	private final FolderLock folderLock;
	/**
	 * Storage options.
	 */
	private final StorageOptions storageOptions;
	/**
	 * Cached list of files to fetch.
	 */
	private final CopyOnWriteArrayList<FileForFetch> files;
	/**
	 * List of reserved temporary files that won't be purged automatically.
	 */
	private final CopyOnWriteArrayList<Path> reservedFiles = new CopyOnWriteArrayList<>();

	/**
	 * Parses metadata file and creates {@link FileForFetch} instance.
	 *
	 * @param metadataFile Path to the metadata file.
	 * @return {@link FileForFetch} instance or empty if the file is not valid.
	 */
	@Nonnull
	private static Optional<FileForFetch> toFileForFetch(@Nonnull Path metadataFile) {
		try {
			final List<String> metadataLines = Files.readAllLines(metadataFile, StandardCharsets.UTF_8);
			return of(FileForFetch.fromLines(metadataLines));
		} catch (Exception e) {
			return empty();
		}
	}

	/**
	 * Extracts a UUID from the provided file path.
	 *
	 * @param it the file path from which the UUID will be extracted
	 * @return an Optional containing the UUID if extraction is successful, otherwise an empty Optional
	 */
	@Nonnull
	private static Optional<UUID> getUuidFromPath(@Nonnull Path it) {
		try {
			return of(UUID.fromString(FileUtils.getFileNameWithoutExtension(it.toFile().getName())));
		} catch (Exception ex) {
			return empty();
		}
	}

	public ExportFileService(
		@Nonnull StorageOptions storageOptions,
		@Nonnull Scheduler scheduler
	) {
		this.storageOptions = storageOptions;
		// init files for fetch
		if (this.storageOptions.exportDirectory().toFile().exists()) {
			try (final Stream<Path> fileStream = Files.list(this.storageOptions.exportDirectory())) {
				this.files = fileStream
					.filter(it -> it.toFile().getName().endsWith(FileForFetch.METADATA_EXTENSION))
					.map(ExportFileService::toFileForFetch)
					.flatMap(Optional::stream)
					.sorted(Comparator.comparing(FileForFetch::created).reversed())
					.collect(Collectors.toCollection(CopyOnWriteArrayList::new));
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to read the contents of the folder: " + e.getMessage(),
					"Failed to read the contents of the folder.",
					e
				);
			}
		} else {
			Assert.isPremiseValid(
				this.storageOptions.exportDirectory().toFile().mkdirs(),
				() -> new UnexpectedIOException(
					"Failed to create directory: " + this.storageOptions.exportDirectory(),
					"Failed to create directory."
				)
			);
			this.files = new CopyOnWriteArrayList<>();
		}
		// init folder lock
		this.folderLock = new FolderLock(this.storageOptions.exportDirectory());
		this.reservedFiles.add(this.folderLock.lockFilePath());
		// schedule automatic purging task
		new DelayedAsyncTask(
			null,
			"Export file service purging task",
			scheduler,
			this::purgeFiles,
			5, TimeUnit.MINUTES
		).schedule();
	}

	/**
	 * Retrieves the path to the export directory where files are stored.
	 *
	 * @return the path to the export directory, ensuring it is not null
	 */
	@Nonnull
	public Path getExportDirectory() {
		return this.storageOptions.exportDirectory();
	}

	/**
	 * Returns paginated list of files to fetch. Optionally filtered by origin.
	 *
	 * @param page     requested page
	 * @param pageSize requested page size
	 * @param origin   optional origin filter
	 * @return paginated list of files to fetch
	 */
	@Nonnull
	public PaginatedList<FileForFetch> listFilesToFetch(int page, int pageSize, @Nonnull Set<String> origin) {
		final List<FileForFetch> filteredFiles = origin.isEmpty() ?
			this.files :
			this.files.stream()
				.filter(it -> it.origin() != null && Arrays.stream(it.origin()).anyMatch(origin::contains))
				.toList();
		final List<FileForFetch> filePage = filteredFiles
			.stream()
			.skip(PaginatedList.getFirstItemNumberForPage(page, pageSize))
			.limit(pageSize)
			.toList();
		return new PaginatedList<>(
			page, pageSize,
			filteredFiles.size(),
			filePage
		);
	}

	/**
	 * Returns file with the specified fileId that is available for download or empty if the file is not found.
	 *
	 * @param fileId fileId of the file
	 * @return file to fetch
	 */
	@Nonnull
	public Optional<FileForFetch> getFile(@Nonnull UUID fileId) {
		return this.files.stream()
			.filter(it -> it.fileId().equals(fileId))
			.findFirst();
	}

	/**
	 * Method creates new file for fetch and stores it in the export directory.
	 *
	 * @param fileName    name of the file
	 * @param description optional description of the file
	 * @param contentType MIME type of the file
	 * @param origin      optional origin of the file
	 * @return input stream to write the file
	 */
	@Nonnull
	public ExportFileHandle storeFile(
		@Nonnull String fileName,
		@Nullable String description,
		@Nonnull String contentType,
		@Nullable String origin
	) {
		final UUID fileId = UUIDUtil.randomUUID();
		final String finalFileName = fileId + FileUtils.getFileExtension(fileName).map(it -> "." + it).orElse("");
		final Path finalFilePath = this.storageOptions.exportDirectory().resolve(finalFileName);
		try {
			if (!this.storageOptions.exportDirectory().toFile().exists()) {
				Assert.isPremiseValid(
					this.storageOptions.exportDirectory().toFile().mkdirs(),
					() -> new UnexpectedIOException(
						"Failed to create directory: " + this.storageOptions.exportDirectory(),
						"Failed to create directory."
					)
				);
			}
			Assert.isPremiseValid(
				finalFilePath.toFile().createNewFile(),
				() -> new UnexpectedIOException(
					"Failed to create file: " + finalFilePath,
					"Failed to create file."
				)
			);
			final CompletableFuture<FileForFetch> fileForFetchCompletableFuture = new CompletableFuture<>();
			return new ExportFileHandle(
				fileForFetchCompletableFuture,
				finalFilePath,
				new WriteMetadataOnCloseOutputStream(
					finalFilePath,
					fileForFetchCompletableFuture,
					() -> {
						try {
							final FileForFetch fileForFetch = new FileForFetch(
								fileId,
								fileName,
								description,
								contentType,
								Files.size(finalFilePath),
								OffsetDateTime.now(),
								origin == null ? null : Arrays.stream(origin.split(","))
									.map(String::trim)
									.toArray(String[]::new)
							);
							writeFileMetadata(fileForFetch, StandardOpenOption.CREATE_NEW);
							this.files.add(0, fileForFetch);
							return fileForFetch;
						} catch (IOException e) {
							throw new UnexpectedIOException(
								"Failed to write metadata file: " + e.getMessage(),
								"Failed to write metadata file."
							);
						}
					}
				)
			);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to store file: " + finalFilePath,
				"Failed to store file.",
				e
			);
		}
	}

	/**
	 * Copies contents of the file to the output stream or throws exception if the file is not found.
	 *
	 * @param fileId file ID
	 * @return the inputstream to read contents from
	 * @throws FileForFetchNotFoundException if the file is not found
	 */
	@Nonnull
	public InputStream fetchFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
		try {
			final FileForFetch file = getFile(fileId)
				.orElseThrow(() -> new FileForFetchNotFoundException(fileId));
			return Files.newInputStream(file.path(this.storageOptions.exportDirectory()), StandardOpenOption.READ);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to open the designated file: " + e.getMessage(),
				"Failed to open the designated file.",
				e
			);
		}
	}

	/**
	 * Deletes the file by its ID or throws exception if the file is not found.
	 *
	 * @param fileId file ID
	 * @throws FileForFetchNotFoundException if the file is not found
	 * @throws UnexpectedIOException         if the file cannot be deleted
	 */
	public void deleteFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
		try {
			final FileForFetch file = getFile(fileId)
				.orElseThrow(() -> new FileForFetchNotFoundException(fileId));
			if (this.files.remove(file)) {
				Files.deleteIfExists(file.metadataPath(this.storageOptions.exportDirectory()));
				Files.deleteIfExists(file.path(this.storageOptions.exportDirectory()));
			}
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to delete file: " + e.getMessage(),
				"Failed to delete the file.",
				e
			);
		}
	}

	/**
	 * Creates a temporary file with the given file name in the export storage directory.
	 *
	 * @param fileName the name of the file to be created
	 * @return the Path of the created temporary file
	 * @throws RuntimeException if an I/O error occurs when creating the file
	 */
	@Nonnull
	public Path createTempFile(@Nonnull String fileName) {
		try {
			final Path filePath = this.storageOptions.exportDirectory().resolve(fileName);
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
	 * Creates temporary file using {@link #createTempFile(String)} and adds it to reserved files that won't be purged
	 * automatically - unless explicitly removed by the owner.
	 *
	 * @param fileName the name of the file to be created
	 * @return the Path of the created temporary file
	 */
	@Nonnull
	public Path createManagedTempFile(@Nonnull String fileName) {
		final Path reservedFile = createTempFile(fileName);
		this.reservedFiles.add(reservedFile.normalize());
		return reservedFile;
	}

	/**
	 * Removes the specified temporary file from the managed files list and deletes it from the file system if it exists.
	 *
	 * @param file the path to the temporary file to be purged; must not be null
	 */
	public void purgeManagedTempFile(@Nonnull Path file) {
		this.reservedFiles.remove(file.normalize());
		FileUtils.deleteFileIfExists(file);
	}

	/**
	 * Returns path to an existing temporary file with the given file name in the export storage directory.
	 *
	 * @param fileName the name of the file to be created
	 * @return the Path of the created temporary file
	 * @throws RuntimeException if an I/O error occurs when creating the file
	 */
	@Nonnull
	public Path getTempFile(@Nonnull String fileName) {
		final Path tempFile = this.storageOptions.exportDirectory().resolve(fileName);
		Assert.isTrue(
			tempFile.toFile().exists(),
			() -> new InvalidFileNameException(
				"Temporary file does not exist: " + tempFile,
				"Temporary file does not exist."
			)
		);
		return tempFile;
	}

	/**
	 * Retrieves the path of a given file within the specified export directory.
	 *
	 * @param file The file object for which the path is to be retrieved. This parameter must not be null.
	 * @return The path of the specified file located in the export directory.
	 */
	@Nonnull
	public Path getPathOf(@Nonnull FileForFetch file) {
		return file.path(this.storageOptions.exportDirectory());
	}

	/**
	 * Removes all reserved files and purges unmanaged temp files from the directory on closing.
	 */
	@Override
	public void close() {
		for (Path reservedFile : this.reservedFiles) {
			FileUtils.deleteFileIfExists(reservedFile);
		}
		purgeFiles();
		IOUtils.close(
			() -> new UnexpectedIOException(
				"Failed to close the folder lock: " + this.folderLock,
				"Failed to close the folder lock."
			),
			this.folderLock::close
		);
	}

	/**
	 * Deletes files that are older than the specified expiration threshold.
	 *
	 * This method first determines the threshold date by subtracting the configured
	 * expiration period from the current date and time. It then calls another
	 * method to perform the actual deletion of files older than this threshold.
	 *
	 * @return always zero so that the task is planned as usual
	 */
	private long purgeFiles() {
		// first go through the file list in memory and delete files that are older than the threshold
		final OffsetDateTime thresholdDate = OffsetDateTime.now().minusSeconds(this.storageOptions.exportFileHistoryExpirationSeconds());
		purgeFiles(thresholdDate);

		return 0L;
	}

	/**
	 * Purges old files from the storage based on the age and storage size limit.
	 *
	 * This method performs the following operations:
	 * - Deletes files in memory that are older than the threshold defined by `exportFileHistoryExpirationSeconds`.
	 * - Deletes files from the storage directory that do not have corresponding metadata and are older than the threshold.
	 * - Ensures that the total size of files in the storage directory does not exceed the size limit defined by `exportDirectorySizeLimitBytes`.
	 */
	void purgeFiles(@Nonnull OffsetDateTime thresholdDate) {
		final Set<UUID> knownFiles = new HashSet<>(this.files.size());
		final List<FileForFetch> filesToDelete = this.files.stream()
			.peek(it -> knownFiles.add(it.fileId()))
			.filter(it -> it.created().isBefore(thresholdDate))
			.toList();
		filesToDelete.forEach(
			it -> {
				log.info("Purging file, because it has been created before {}: {}", thresholdDate, it);
				deleteFile(it.fileId());
			}
		);

		// then go through the directory files, that does not have metadata file and delete all that were created before the threshold
		try (final Stream<Path> fileStream = Files.list(this.storageOptions.exportDirectory())) {
			fileStream
				.map(Path::normalize)
				.filter(it -> !getUuidFromPath(it).map(knownFiles::contains).orElse(false))
				.filter(it -> !this.reservedFiles.contains(it))
				.filter(it -> FileUtils.getFileLastModifiedTime(it).map(lastModifiedDate -> lastModifiedDate.isBefore(thresholdDate)).orElse(true))
				.forEach(it -> {
					log.info("Purging temporary file, because it has been last modified before {}: {}", thresholdDate, it);
					FileUtils.deleteFileIfExists(it);
				});
		} catch (IOException e) {
			log.error("Failed to list files in the directory: {}", this.storageOptions.exportDirectory(), e);
		}

		// then check the size of the directory and delete oldest files until the directory size is below the limit
		final long directorySize = FileUtils.getDirectorySize(this.storageOptions.exportDirectory());
		// delete the oldest files until the directory size is below the limit
		if (directorySize > this.storageOptions.exportDirectorySizeLimitBytes()) {
			final List<FileForFetch> filesByCreationDate = this.files.stream()
				.sorted(Comparator.comparing(FileForFetch::created))
				.toList();
			long savedSize = 0L;
			for (FileForFetch it : filesByCreationDate) {
				log.info("Purging the oldest file, because the export directory grew too big: {}", it);
				final long metadataFileSize = it.metadataPath(this.storageOptions.exportDirectory()).toFile().length();
				deleteFile(it.fileId());
				savedSize += it.totalSizeInBytes() + metadataFileSize;
				// finish removing files if the directory size is below the limit
				if (directorySize - savedSize <= this.storageOptions.exportDirectorySizeLimitBytes()) {
					break;
				}
			}
		}
	}

	/**
	 * Writes metadata file for the file with the specified fileId.
	 *
	 * @param fileForFetch file to write metadata for
	 * @throws IOException if the metadata file cannot be written
	 */
	private void writeFileMetadata(@Nonnull FileForFetch fileForFetch, @Nonnull OpenOption... options) throws IOException {
		Files.write(
			this.storageOptions.exportDirectory().resolve(fileForFetch.fileId() + FileForFetch.METADATA_EXTENSION),
			fileForFetch.toLines(),
			StandardCharsets.UTF_8,
			options
		);
	}

	/**
	 * Record that contains output stream the export file can be written to and future that will be completed when the
	 * file is written.
	 *
	 * @param fileForFetchFuture Future that will be completed when the file is written.
	 * @param filePath		     Path to the file.
	 * @param outputStream       Output stream the file can be written to.
	 */
	public record ExportFileHandle(
		@Nonnull CompletableFuture<FileForFetch> fileForFetchFuture,
		@Nonnull Path filePath,
		@Nonnull OutputStream outputStream
	) implements AutoCloseable {

		/**
		 * Returns the size of the target file.
		 * @return the size of the target file
		 */
		public long size() {
			return this.filePath.toFile().length();
		}

		@Override
		public void close() throws IOException {
			this.outputStream.close();
		}
	}

	/**
	 * Thin wrapper around {@link FileOutputStream} that writes metadata file on close.
	 */
	private final static class WriteMetadataOnCloseOutputStream extends FileOutputStream implements Closeable {
		private final CompletableFuture<FileForFetch> fileForFetchFuture;
		private final Supplier<FileForFetch> onClose;
		private boolean closed;

		public WriteMetadataOnCloseOutputStream(
			@Nonnull Path finalFilePath,
			@Nonnull CompletableFuture<FileForFetch> fileForFetchFuture,
			@Nonnull Supplier<FileForFetch> onClose
		) throws FileNotFoundException {
			super(finalFilePath.toFile());
			this.fileForFetchFuture = fileForFetchFuture;
			this.onClose = onClose;
		}

		@Override
		public void close() throws IOException {
			// avoid closing the stream multiple times
			if (!this.closed) {
				this.closed = true;
				try {
					super.close();
				} finally {
					this.fileForFetchFuture.complete(this.onClose.get());
				}
			}
		}

	}

}
