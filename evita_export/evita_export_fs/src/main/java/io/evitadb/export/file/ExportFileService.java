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
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.spi.export.model.ExportFileSizeLimit;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
public class ExportFileService implements ExportService {
	/**
	 * The timestamp `ISO_OFFSET_DATE_TIME` prints longest: expanded year, nanoseconds and an offset with seconds. A
	 * sidecar written at any moment fits the space reserved for this one.
	 */
	private static final OffsetDateTime LONGEST_TIMESTAMP = OffsetDateTime.of(
		999999999, 12, 31, 23, 59, 59, 999999999, ZoneOffset.ofHoursMinutesSeconds(17, 59, 59)
	);
	/**
	 * Folder lock to prevent concurrent access to the export directory.
	 */
	private final FolderLock folderLock;
	/**
	 * Filesystem-specific export options containing all settings.
	 */
	private final FileSystemExportOptions fsOptions;
	/**
	 * Cached list of files to fetch.
	 */
	private final CopyOnWriteArrayList<FileForFetch> files;
	/**
	 * Task that periodically purges old files from the storage.
	 */
	private final DelayedAsyncTask purgeTask;
	/**
	 * Provider for current time, allowing injection of controllable time for testing.
	 */
	private final Supplier<OffsetDateTime> timeProvider;

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
	 * @param scheduler scheduler for background tasks
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
	 * @param scheduler scheduler for background tasks
	 * @param timeProvider supplier for current time
	 */
	public ExportFileService(
		@Nonnull ExportOptions exportOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull Supplier<OffsetDateTime> timeProvider
	) {
		if (!(exportOptions instanceof FileSystemExportOptions)) {
			throw new IllegalArgumentException(
				"ExportFileService requires FileSystemExportOptions but got: " + exportOptions.getClass().getSimpleName()
			);
		}
		this.fsOptions = (FileSystemExportOptions) exportOptions;
		this.timeProvider = timeProvider;
		// init files for fetch
		if (this.fsOptions.getDirectory().toFile().exists()) {
			try (final Stream<Path> fileStream = Files.list(this.fsOptions.getDirectory())) {
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
				this.fsOptions.getDirectory().toFile().mkdirs(),
				() -> new UnexpectedIOException(
					"Failed to create directory: " + this.fsOptions.getDirectory(),
					"Failed to create directory."
				)
			);
			this.files = new CopyOnWriteArrayList<>();
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

	@Nonnull
	@Override
	public Optional<FileForFetch> getFile(@Nonnull UUID fileId) {
		return this.files.stream()
			.filter(it -> it.fileId().equals(fileId))
			.findFirst();
	}

	@Nonnull
	@Override
	public ExportFileHandleLocal storeFile(
		@Nonnull String fileName,
		@Nullable String description,
		@Nonnull String contentType,
		@Nullable String origin
	) {
		final UUID fileId = UUIDUtil.randomUUID();
		final String finalFileName = fileId + FileUtils.getFileExtension(fileName).map(it -> "." + it).orElse("");
		final Path finalFilePath = this.fsOptions.getDirectory().resolve(finalFileName);
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
				computeSizeLimit(fileId, fileName, description, contentType, origin),
				new WriteMetadataOnCloseOutputStream(
					finalFilePath,
					this.fsOptions.getDirectory().resolve(fileId + FileForFetch.METADATA_EXTENSION),
					fileForFetchCompletableFuture,
					() -> {
						try {
							final FileForFetch fileForFetch = createFileDescriptor(
								fileId,
								fileName,
								description,
								contentType,
								Files.size(finalFilePath),
								this.timeProvider.get(),
								origin
							);
							writeFileMetadata(fileForFetch, StandardOpenOption.CREATE_NEW);
							this.files.addFirst(fileForFetch);
							return fileForFetch;
						} catch (IOException e) {
							throw new UnexpectedIOException(
								"Failed to write metadata file: " + e.getMessage(),
								"Failed to write metadata file.",
								e
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
			return Files.newInputStream(file.path(this.fsOptions.getDirectory()), StandardOpenOption.READ);
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
			if (this.files.remove(file)) {
				Files.deleteIfExists(file.metadataPath(this.fsOptions.getDirectory()));
				Files.deleteIfExists(file.path(this.fsOptions.getDirectory()));
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
	public void close() {
		// stop purging task
		IOUtils.closeQuietly(this.purgeTask::close);
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

	@Override
	public void purgeFiles(@Nonnull OffsetDateTime thresholdDate) {
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
		try (final Stream<Path> fileStream = Files.list(this.fsOptions.getDirectory())) {
			fileStream
				.map(Path::normalize)
				.filter(it -> !getUuidFromPath(it).map(knownFiles::contains).orElse(false))
				.filter(it -> !this.folderLock.lockFilePath().equals(it))
				.filter(it -> FileUtils.getFileLastModifiedTime(it).map(lastModifiedDate -> lastModifiedDate.isBefore(thresholdDate)).orElse(true))
				.forEach(it -> {
					log.info("Purging temporary file, because it has been last modified before {}: {}", thresholdDate, it);
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
				final List<FileForFetch> filesByCreationDate = this.files.stream()
					.sorted(Comparator.comparing(FileForFetch::created))
					.toList();
				long savedSize = 0L;
				for (FileForFetch it : filesByCreationDate) {
					log.info("Purging the oldest file, because the export directory grew too big: {}", it);
					final long metadataFileSize = it.metadataPath(this.fsOptions.getDirectory())
						.toFile()
						.length();
					deleteFile(it.fileId());
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
	}

	@Override
	public long purgeFiles() {
		// first go through the file list in memory and delete files that are older than the threshold
		final OffsetDateTime thresholdDate = OffsetDateTime.now().minusSeconds(
			this.fsOptions.getHistoryExpirationSeconds());
		purgeFiles(thresholdDate);

		return 0L;
	}

	/** Builds the descriptor shared by the sidecar writer and its maximum encoded size calculation. */
	@Nonnull
	private static FileForFetch createFileDescriptor(
		@Nonnull UUID fileId, @Nonnull String fileName, @Nullable String description,
		@Nonnull String contentType, long size, @Nonnull OffsetDateTime created, @Nullable String origin
	) {
		return new FileForFetch(
			fileId, fileName, description, contentType, size, created,
			origin == null ? null : Arrays.stream(origin.split(",")).map(String::trim).toArray(String[]::new)
		);
	}

	/** Reserves the UTF-8 bytes of every sidecar line, including its terminating platform line separator. */
	@Nonnull
	private ExportFileSizeLimit computeSizeLimit(
		@Nonnull UUID fileId, @Nonnull String fileName, @Nullable String description,
		@Nonnull String contentType, @Nullable String origin
	) {
		final FileForFetch descriptor = createFileDescriptor(
			fileId, fileName, description, contentType, Long.MAX_VALUE, LONGEST_TIMESTAMP, origin
		);
		final int lineSeparatorBytes = System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
		long metadataBytes = 0;
		for (final String line : descriptor.toLines()) {
			metadataBytes += line.getBytes(StandardCharsets.UTF_8).length + lineSeparatorBytes;
		}
		final long limit = this.fsOptions.getSizeLimitBytes();
		return new ExportFileSizeLimit(Math.max(0, limit - metadataBytes), limit, "export.fileSystem.sizeLimitBytes");
	}

	/**
	 * Writes or updates the sidecar metadata for the provided file descriptor.
	 *
	 * Implementations may persist human-readable or machine-usable metadata (e.g. JSON) next to the
	 * binary content. Existing metadata can be overwritten based on {@code options}.
	 *
	 * @param fileForFetch	 descriptor of the file whose metadata should be persisted
	 * @param options	 optional {@link java.nio.file.StandardOpenOption} flags controlling write mode
	 * @throws EvitaIOException if the metadata cannot be written
	 */
	private void writeFileMetadata(
		@Nonnull FileForFetch fileForFetch,
		@Nonnull OpenOption... options
	) throws EvitaIOException {
		try {
			Files.write(
				this.fsOptions.getDirectory().resolve(fileForFetch.fileId() + FileForFetch.METADATA_EXTENSION),
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
	 * @param filePath		     Path to the file.
	 * @param sizeLimit          Payload allowance after reserving sidecar bytes, floored at zero.
	 * @param outputStream       Output stream the file can be written to.
	 */
	public record ExportFileHandleLocal(
		@Nonnull UUID fileId,
		@Nonnull CompletableFuture<FileForFetch> fileForFetchFuture,
		@Nonnull Path filePath,
		@Nonnull ExportFileSizeLimit sizeLimit,
		@Nonnull WriteMetadataOnCloseOutputStream outputStream
	) implements ExportFileHandle {

		@Override
		public boolean discard() {
			return this.outputStream.discard();
		}

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
		private final Path path;
		private final Path metadataPath;
		private boolean closed;

		/** Captures both export paths so a failed publication can remove all unpublished bytes. */
		public WriteMetadataOnCloseOutputStream(
			@Nonnull Path finalFilePath,
			@Nonnull Path metadataPath,
			@Nonnull CompletableFuture<FileForFetch> fileForFetchFuture,
			@Nonnull Supplier<FileForFetch> onClose
		) throws FileNotFoundException {
			super(finalFilePath.toFile());
			this.fileForFetchFuture = fileForFetchFuture;
			this.onClose = onClose;
			this.path = finalFilePath;
			this.metadataPath = metadataPath;
		}

		/**
		 * Closes the file without publishing metadata, removes its bytes and cancels its future.
		 *
		 * @return whether this call abandoned an open stream
		 */
		public boolean discard() {
			if (this.closed) {
				return false;
			}
			this.closed = true;
			try {
				super.close();
			} catch (Exception e) {
				log.error("Failed to close discarded export: {}", this.path, e);
			}
			try {
				Files.deleteIfExists(this.path);
			} catch (Exception e) {
				log.error("Failed to delete discarded export: {}", this.path, e);
			}
			this.fileForFetchFuture.cancel(false);
			return true;
		}

		@Override
		public void close() throws IOException {
			// avoid closing the stream multiple times
			if (!this.closed) {
				this.closed = true;
				try {
					super.close();
				} finally {
					try {
						this.fileForFetchFuture.complete(this.onClose.get());
					} catch (RuntimeException failure) {
						removeUnpublishedFiles();
						this.fileForFetchFuture.completeExceptionally(failure);
						throw failure;
					}
				}
			}
		}

		/** Removes the archive and its sidecar; a failure here is only logged, so it never replaces the reason. */
		private void removeUnpublishedFiles() {
			for (final Path unpublished : new Path[]{this.path, this.metadataPath}) {
				try {
					Files.deleteIfExists(unpublished);
				} catch (Exception cleanupFailure) {
					log.error("Failed to delete unpublished export file: {}", unpublished, cleanupFailure);
				}
			}
		}

	}

}
