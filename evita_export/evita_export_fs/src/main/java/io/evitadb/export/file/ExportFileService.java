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

package io.evitadb.export.file;

import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.EvitaIOException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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
public class ExportFileService implements ExportService {
	/**
	 * Timeout for the file cache in milliseconds.
	 */
	private static final long FILES_CACHE_TTL_MS = 5 * 60 * 1000L;
	/**
	 * Timeout for acquiring the lock for listing files.
	 */
	private static final long LOCK_TIMEOUT_MS = 60_000L;

	/**
	 * Folder lock to prevent concurrent access to the export directory.
	 */
	private final FolderLock folderLock;
	/**
	 * Filesystem-specific export options containing all settings.
	 */
	private final FileSystemExportOptions fsOptions;
	/**
	 * Task that clears the file cache after inactivity.
	 */
	private final DelayedAsyncTask filesCacheClearTask;
	/**
	 * Queue for cache updates that happen while the cache is being loaded.
	 */
	private final BlockingQueue<Runnable> cacheUpdateQueue = new LinkedBlockingQueue<>();
	/**
	 * Lock for listing files.
	 */
	private final ReentrantLock listingLock = new ReentrantLock();
	/**
	 * Task that periodically purges old files from the storage.
	 */
	private final DelayedAsyncTask purgeTask;
	/**
	 * Provider for current time, allowing injection of controllable time for testing.
	 */
	private final Supplier<OffsetDateTime> timeProvider;
	/**
	 * Cached list of files to fetch.
	 */
	@Nullable private volatile CopyOnWriteArrayList<FileForFetch> files;

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

	/**
	 * Creates a new ExportFileService with default time provider using system clock.
	 *
	 * @param exportOptions export options configuration
	 * @param scheduler     scheduler for background tasks
	 */
	public ExportFileService(
		@Nonnull ExportOptions exportOptions,
		@Nonnull Scheduler scheduler
	) {
		this(exportOptions, scheduler, OffsetDateTime::now);
	}

	/**
	 * Creates a new ExportFileService with custom time provider.
	 * This constructor allows injection of a controllable time source for testing.
	 *
	 * @param exportOptions export options configuration
	 * @param scheduler     scheduler for background tasks
	 * @param timeProvider  supplier for current time
	 */
	public ExportFileService(
		@Nonnull ExportOptions exportOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull Supplier<OffsetDateTime> timeProvider
	) {
		if (!(exportOptions instanceof FileSystemExportOptions)) {
			throw new IllegalArgumentException(
				"ExportFileService requires FileSystemExportOptions but got: " + exportOptions.getClass()
					.getSimpleName()
			);
		}
		this.fsOptions = (FileSystemExportOptions) exportOptions;
		this.timeProvider = timeProvider;
		this.files = null;

		if (!this.fsOptions.getDirectory().toFile().exists()) {
			Assert.isPremiseValid(
				this.fsOptions.getDirectory().toFile().mkdirs(),
				() -> new UnexpectedIOException(
					"Failed to create directory: " + this.fsOptions.getDirectory(),
					"Failed to create directory."
				)
			);
		}
		// init folder lock
		this.folderLock = new FolderLock(this.fsOptions.getDirectory());
		// schedule automatic purging task
		this.purgeTask = new DelayedAsyncTask(
			null,
			"Export file service purging task",
			scheduler,
			this::purgeFiles,
			5, TimeUnit.MINUTES
		);
		this.purgeTask.schedule();

		this.filesCacheClearTask = new DelayedAsyncTask(
			null,
			"Export file service cache clearer",
			scheduler,
			() -> {
				this.files = null;
				return -1L;
			},
			FILES_CACHE_TTL_MS,
			TimeUnit.MILLISECONDS
		);
	}

	/**
	 * Retrieves the path to the export directory where files are stored.
	 *
	 * @return the path to the export directory, ensuring it is not null
	 */
	@Nonnull
	public Path getExportDirectory() {
		return this.fsOptions.getDirectory();
	}

	@Nonnull
	@Override
	public PaginatedList<FileForFetch> listFilesToFetch(
		int page,
		int pageSize,
		@Nonnull Set<String> catalog,
		@Nonnull Set<String> origin
	) {
		final List<FileForFetch> theFiles = getAndCacheFiles();
		Stream<FileForFetch> stream = theFiles.stream();
		if (!origin.isEmpty()) {
			stream = stream.filter(it -> it.origin() != null && Arrays.stream(it.origin()).anyMatch(origin::contains));
		}
		if (!catalog.isEmpty()) {
			stream = stream.filter(it -> it.catalogName() != null && catalog.contains(it.catalogName()));
		}

		final List<FileForFetch> filteredFiles = stream.toList();
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

	@Nonnull
	@Override
	public Optional<FileForFetch> getFile(@Nonnull UUID fileId) {
		try (final Stream<Path> fileStream = Files.walk(this.fsOptions.getDirectory(), 2)) {
			return fileStream
				.filter(Files::isRegularFile)
				.filter(it -> it.toFile().getName().equals(fileId + FileForFetch.METADATA_EXTENSION))
				.findFirst()
				.flatMap(ExportFileService::toFileForFetch);
		} catch (IOException e) {
			log.error("Failed to find file {}: {}", fileId, e.getMessage());
			return empty();
		}
	}

	@Nonnull
	@Override
	public ExportFileHandleLocal storeFile(
		@Nonnull String fileName,
		@Nullable String description,
		@Nonnull String contentType,
		@Nullable String catalog,
		@Nullable String origin
	) {
		final UUID fileId = UUIDUtil.randomUUID();
		final String finalFileName = fileId + FileUtils.getFileExtension(fileName).map(it -> "." + it).orElse("");
		final Path directory = resolveDirectory(catalog, true);
		final Path finalFilePath = directory.resolve(finalFileName);
		try {
			if (!this.fsOptions.getDirectory().toFile().exists()) {
				Assert.isPremiseValid(
					this.fsOptions.getDirectory().toFile().mkdirs(),
					() -> new UnexpectedIOException(
						"Failed to create directory: " + this.fsOptions.getDirectory(),
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
			return new ExportFileHandleLocal(
				fileId,
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
								this.timeProvider.get(),
								origin == null ? null : Arrays.stream(origin.split(","))
									.map(String::trim)
									.toArray(String[]::new),
								catalog
							);
							writeFileMetadata(fileForFetch, StandardOpenOption.CREATE_NEW);
							final Runnable updateCache = () -> {
								final CopyOnWriteArrayList<FileForFetch> theFiles = this.files;
								if (theFiles != null) {
									if (theFiles.stream().noneMatch(f -> f.fileId().equals(fileId))) {
										theFiles.add(0, fileForFetch);
									}
								}
							};
							if (this.files != null) {
								updateCache.run();
							} else {
								if (this.listingLock.tryLock()) {
									try {
										if (this.files != null) {
											updateCache.run();
										}
									} finally {
										this.listingLock.unlock();
									}
								} else {
									// Lock held by Lister
									if (this.files != null) {
										updateCache.run();
									} else {
										this.cacheUpdateQueue.add(updateCache);
										if (this.files != null) {
											updateCache.run();
										}
									}
								}
							}
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

	@Nonnull
	@Override
	public InputStream fetchFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
		try {
			final FileForFetch file = getFile(fileId)
				.orElseThrow(() -> new FileForFetchNotFoundException(fileId));
			final Path directory = resolveDirectory(file.catalogName(), false);
			return Files.newInputStream(file.path(directory), StandardOpenOption.READ);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to open the designated file: " + e.getMessage(),
				"Failed to open the designated file.",
				e
			);
		}
	}

	@Override
	public void deleteFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
		try {
			final FileForFetch file = getFile(fileId)
				.orElseThrow(() -> new FileForFetchNotFoundException(fileId));
			final Path directory = resolveDirectory(file.catalogName(), false);
			Files.deleteIfExists(file.metadataPath(directory));
			Files.deleteIfExists(file.path(directory));

			final CopyOnWriteArrayList<FileForFetch> theFiles = this.files;
			if (theFiles != null) {
				theFiles.remove(file);
			}
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to delete file: " + e.getMessage(),
				"Failed to delete the file.",
				e
			);
		}
	}

	@Override
	public long purgeFiles() {
		// first go through the file list in memory and delete files that are older than the threshold
		final OffsetDateTime thresholdDate = OffsetDateTime.now().minusSeconds(
			this.fsOptions.getHistoryExpirationSeconds());
		purgeFiles(thresholdDate);

		return 0L;
	}

	@Override
	public void purgeFiles(@Nonnull OffsetDateTime thresholdDate) {
		final List<FileForFetch> validFiles = scanFilesFromDisk();
		final Set<UUID> knownFileIds = new HashSet<>();

		final List<FileForFetch> filesToDelete = validFiles.stream()
			.peek(it -> knownFileIds.add(it.fileId()))
			.filter(it -> it.created().isBefore(thresholdDate))
			.toList();

		filesToDelete.forEach(
			it -> {
				log.info("Purging file, because it has been created before {}: {}", thresholdDate, it);
				deleteFileInternal(it);
			}
		);
		validFiles.removeAll(filesToDelete);

		// then go through the directory files, that does not have metadata file and delete all that were created before the threshold
		final Path directory = this.fsOptions.getDirectory();
		if (directory.toFile().exists()) {
			try (final Stream<Path> fileStream = Files.walk(this.fsOptions.getDirectory(), 2)) {
				fileStream
					.map(Path::normalize)
					.filter(Files::isRegularFile)
					.filter(it -> !it.toFile().getName().endsWith(FileForFetch.METADATA_EXTENSION))
					.filter(it -> !getUuidFromPath(it).map(knownFileIds::contains).orElse(false))
					.filter(it -> !this.folderLock.lockFilePath().equals(it))
					.filter(it -> FileUtils.getFileLastModifiedTime(it)
						.map(lastModifiedDate -> lastModifiedDate.isBefore(thresholdDate))
						.orElse(true))
					.forEach(it -> {
						log.info(
							"Purging temporary file, because it has been last modified before {}: {}", thresholdDate,
							it
						);
						FileUtils.deleteFileIfExists(it);
					});
			} catch (IOException e) {
				log.error("Failed to list files in the directory: {}", this.fsOptions.getDirectory(), e);
			}

			try {
				// then check the size of the directory and delete oldest files until the directory size is below the limit
				final long directorySize = FileUtils.getDirectorySize(this.fsOptions.getDirectory());
				// delete the oldest files until the directory size is below the limit
				if (directorySize > this.fsOptions.getSizeLimitBytes()) {
					final List<FileForFetch> filesByCreationDate = validFiles.stream()
						.sorted(Comparator.comparing(FileForFetch::created))
						.toList();
					long savedSize = 0L;
					for (FileForFetch it : filesByCreationDate) {
						log.info("Purging the oldest file, because the export directory grew too big: {}", it);
						final long metadataFileSize = it.metadataPath(resolveDirectory(it.catalogName(), false))
							.toFile()
							.length();
						deleteFileInternal(it);
						savedSize += it.totalSizeInBytes() + metadataFileSize;
						// finish removing files if the directory size is below the limit
						if (directorySize - savedSize <= this.fsOptions.getSizeLimitBytes()) {
							break;
						}
					}
				}
			} catch (UnexpectedIOException e) {
				log.error("Failed to calculate size of the directory: {}", this.fsOptions.getDirectory(), e);
			}

			// cleanup empty directories
			FileUtils.deleteEmptyDirectories(this.fsOptions.getDirectory());
		}
	}

	@Override
	public void close() {
		// stop purging task
		IOUtils.closeQuietly(this.purgeTask::close);
		IOUtils.closeQuietly(this.filesCacheClearTask::close);
		// purge old files
		purgeFiles();
		// release folder lock
		IOUtils.close(
			() -> new UnexpectedIOException(
				"Failed to close the folder lock: " + this.folderLock,
				"Failed to close the folder lock."
			),
			this.folderLock::close
		);
	}

	/**
	 * Retrieves and caches the list of files available for fetch.
	 * If the cached file list is not yet initialized, this method will scan the disk for relevant files,
	 * cache the results, and handle any pending updates in the update queue.
	 * Appropriate locks are used to ensure thread safety during the initialization of the cache.
	 *
	 * The method also schedules the clearing of the file cache and executes queued update tasks
	 * if the files were loaded during this call.
	 *
	 * @return a list of {@link FileForFetch} instances representing the available files for fetch
	 * @throws GenericEvitaInternalError if the lock cannot be acquired within the specified timeout
	 *                                   or if the thread is interrupted while waiting for the lock
	 */
	@Nonnull
	private List<FileForFetch> getAndCacheFiles() {
		List<FileForFetch> theFiles = this.files;
		if (theFiles == null) {
			try {
				if (this.listingLock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
					final List<Runnable> updates;
					try {
						theFiles = this.files;
						if (theFiles == null) {
							final List<FileForFetch> loadedFiles = scanFilesFromDisk();
							this.files = new CopyOnWriteArrayList<>(loadedFiles);
							theFiles = loadedFiles;

							// Replay queued updates
							updates = new ArrayList<>(this.cacheUpdateQueue.size());
							this.cacheUpdateQueue.drainTo(updates);
						} else {
							updates = List.of();
						}
					} finally {
						this.listingLock.unlock();
					}

					this.filesCacheClearTask.schedule();
					updates.forEach(Runnable::run);
				} else {
					throw new GenericEvitaInternalError("Could not acquire lock to list files within timeout");
				}
			} catch (InterruptedException e) {
				throw new GenericEvitaInternalError("Interrupted while waiting for lock to list files", e);
			}
		}
		return theFiles;
	}

	/**
	 * Resolves the directory for the given catalog. If the catalog is provided, it returns the subdirectory
	 * with the catalog name. If the catalog is null, it returns the root export directory.
	 *
	 * @param catalogName Name of the catalog or null if the file is stored in the root directory.
	 * @param create      If true, the directory will be created if it does not exist.
	 * @return Path to the directory.
	 */
	@Nonnull
	private Path resolveDirectory(@Nullable String catalogName, boolean create) {
		final Path directory = catalogName == null
			? this.fsOptions.getDirectory()
			: this.fsOptions.getDirectory().resolve(catalogName);
		if (create && !directory.toFile().exists()) {
			Assert.isPremiseValid(
				directory.toFile().mkdirs(),
				() -> new UnexpectedIOException(
					"Failed to create directory: " + directory,
					"Failed to create directory."
				)
			);
		}
		return directory;
	}

	/**
	 * Scans the export directory for metadata files and creates a list of {@link FileForFetch} instances.
	 *
	 * @return List of {@link FileForFetch} instances.
	 */
	@Nonnull
	private List<FileForFetch> scanFilesFromDisk() {
		try (final Stream<Path> fileStream = Files.walk(this.fsOptions.getDirectory(), 2)) {
			return fileStream
				.filter(Files::isRegularFile)
				.filter(it -> it.toFile().getName().endsWith(FileForFetch.METADATA_EXTENSION))
				.map(ExportFileService::toFileForFetch)
				.flatMap(Optional::stream)
				.sorted(Comparator.comparing(FileForFetch::created).reversed())
				.collect(Collectors.toList());
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to read the contents of the folder: " + e.getMessage(),
				"Failed to read the contents of the folder.",
				e
			);
		}
	}

	private void deleteFileInternal(FileForFetch file) {
		try {
			final Path directory = resolveDirectory(file.catalogName(), false);
			Files.deleteIfExists(file.metadataPath(directory));
			Files.deleteIfExists(file.path(directory));

			final CopyOnWriteArrayList<FileForFetch> theFiles = this.files;
			if (theFiles != null) {
				theFiles.remove(file);
			}
		} catch (IOException e) {
			log.error("Failed to delete file: {}", file, e);
		}
	}

	/**
	 * Writes or updates the sidecar metadata for the provided file descriptor.
	 *
	 * Implementations may persist human-readable or machine-usable metadata (e.g. JSON) next to the
	 * binary content. Existing metadata can be overwritten based on {@code options}.
	 *
	 * @param fileForFetch descriptor of the file whose metadata should be persisted
	 * @param options      optional {@link java.nio.file.StandardOpenOption} flags controlling write mode
	 * @throws EvitaIOException if the metadata cannot be written
	 */
	private void writeFileMetadata(
		@Nonnull FileForFetch fileForFetch,
		@Nonnull OpenOption... options
	) throws EvitaIOException {
		try {
			final Path directory = resolveDirectory(fileForFetch.catalogName(), true);
			Files.write(
				directory.resolve(fileForFetch.fileId() + FileForFetch.METADATA_EXTENSION),
				fileForFetch.toLines(),
				StandardCharsets.UTF_8,
				options
			);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to write metadata to a file: " + e.getMessage(),
				"Failed to write metadata to a file.",
				e
			);
		}
	}

	/**
	 * Record that contains output stream the export file can be written to and future that will be completed when the
	 * file is written.
	 *
	 * @param fileId             ID of the file.
	 * @param fileForFetchFuture Future that will be completed when the file is written.
	 * @param filePath           Path to the file.
	 * @param outputStream       Output stream the file can be written to.
	 */
	public record ExportFileHandleLocal(
		@Nonnull UUID fileId,
		@Nonnull CompletableFuture<FileForFetch> fileForFetchFuture,
		@Nonnull Path filePath,
		@Nonnull OutputStream outputStream
	) implements ExportFileHandle {

		@Override
		public long size() {
			return this.filePath.toFile().length();
		}

		@Override
		public void close() throws IOException {
			this.outputStream.close();
		}

		@Nonnull
		@Override
		public String toString() {
			return this.filePath + " (fileId: " + this.fileId + ")";
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
