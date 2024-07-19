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

package io.evitadb.core.file;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.UUIDUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
public class ExportFileService {
	/**
	 * Storage options.
	 */
	private final StorageOptions storageOptions;
	/**
	 * Lock for the files list.
	 */
	private final ReentrantLock lock = new ReentrantLock();
	/**
	 * Cached list of files to fetch.
	 */
	private List<FileForFetch> files;

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

	public ExportFileService(@Nonnull StorageOptions storageOptions) {
		this.storageOptions = storageOptions;
		this.initFilesForFetch();
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
	public PaginatedList<FileForFetch> listFilesToFetch(int page, int pageSize, @Nullable String origin) {
		final List<FileForFetch> filteredFiles = origin == null ?
			this.files :
			this.files.stream()
				.filter(it -> it.origin() != null && Arrays.asList(it.origin()).contains(origin))
				.toList();
		final List<FileForFetch> filePage = filteredFiles
			.stream()
			.skip(PaginatedList.getFirstItemNumberForPage(page, pageSize))
			.limit(pageSize)
			.toList();
		return new PaginatedList<>(
			page, pageSize,
			filePage.size(),
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
	 * Method creates new file for fetch but doesn't create it - only metadata is created.
	 *
	 * @param fileName    name of the file
	 * @param description optional description of the file
	 * @param contentType MIME type of the file
	 * @param origin      optional origin of the file
	 * @return file for fetch
	 */
	@Nonnull
	public FileForFetch createFile(
		@Nonnull String fileName,
		@Nullable String description,
		@Nonnull String contentType,
		@Nullable String origin
	) {
		final UUID fileId = UUIDUtil.randomUUID();
		final String finalFileName = fileId + FileUtils.getFileExtension(fileName).map(it -> "." + it).orElse("");
		final Path finalFilePath = storageOptions.exportDirectory().resolve(finalFileName);
		try {
			if (!storageOptions.exportDirectory().toFile().exists()) {
				Assert.isPremiseValid(
					storageOptions.exportDirectory().toFile().mkdirs(),
					() -> new UnexpectedIOException(
						"Failed to create directory: " + storageOptions.exportDirectory(),
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
			lock.lock();
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
				Files.write(
					storageOptions.exportDirectory().resolve(fileId + FileForFetch.METADATA_EXTENSION),
					fileForFetch.toLines(),
					StandardCharsets.UTF_8,
					StandardOpenOption.CREATE_NEW
				);
				this.files.add(0, fileForFetch);
				return fileForFetch;
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to write metadata file: " + e.getMessage(),
					"Failed to write metadata file."
				);
			} finally {
				lock.unlock();
			}
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to store file: " + finalFilePath,
				"Failed to store file.",
				e
			);
		}
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
		final Path finalFilePath = storageOptions.exportDirectory().resolve(finalFileName);
		try {
			if (!storageOptions.exportDirectory().toFile().exists()) {
				Assert.isPremiseValid(
					storageOptions.exportDirectory().toFile().mkdirs(),
					() -> new UnexpectedIOException(
						"Failed to create directory: " + storageOptions.exportDirectory(),
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
				new WriteMetadataOnCloseOutputStream(
					finalFilePath,
					fileForFetchCompletableFuture,
					() -> {
						lock.lock();
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
							Files.write(
								storageOptions.exportDirectory().resolve(fileId + FileForFetch.METADATA_EXTENSION),
								fileForFetch.toLines(),
								StandardCharsets.UTF_8,
								StandardOpenOption.CREATE_NEW
							);
							this.files.add(0, fileForFetch);
							return fileForFetch;
						} catch (IOException e) {
							throw new UnexpectedIOException(
								"Failed to write metadata file: " + e.getMessage(),
								"Failed to write metadata file."
							);
						} finally {
							lock.unlock();
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
			return Files.newInputStream(file.path(storageOptions.exportDirectory()), StandardOpenOption.READ);
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
		lock.lock();
		try {
			final FileForFetch file = getFile(fileId)
				.orElseThrow(() -> new FileForFetchNotFoundException(fileId));
			if (this.files.remove(file)) {
				Files.delete(file.metadataPath(storageOptions.exportDirectory()));
				Files.delete(file.path(storageOptions.exportDirectory()));
			}
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to delete file: " + e.getMessage(),
				"Failed to delete the file.",
				e
			);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Returns input stream to read the file contents.
	 *
	 * @param file file to read
	 * @return input stream to read the file contents
	 * @throws IOException if the file cannot be read
	 */
	@Nonnull
	public InputStream createInputStream(@Nonnull FileForFetch file) throws IOException {
		return new ExportFileInputStream(
			file,
			Files.newInputStream(file.path(storageOptions.exportDirectory()), StandardOpenOption.READ)
		);
	}

	/**
	 * Returns absolute path of the file in the export directory.
	 * @param file file to get the path for
	 * @return absolute path of the file in the export directory
	 */
	@Nonnull
	public Path getFilePath(@Nonnull FileForFetch file) {
		return file.path(storageOptions.exportDirectory());
	}

	/**
	 * Method refreshes the list of files to fetch.
	 */
	private void initFilesForFetch() {
		try {
			lock.lock();
			if (this.storageOptions.exportDirectory().toFile().exists()) {
				try (final Stream<Path> fileStream = Files.list(this.storageOptions.exportDirectory())) {
					this.files = fileStream
						.filter(it -> !it.toFile().getName().endsWith(FileForFetch.METADATA_EXTENSION))
						.map(ExportFileService::toFileForFetch)
						.flatMap(Optional::stream)
						.sorted(Comparator.comparing(FileForFetch::created).reversed())
						.collect(Collectors.toCollection(ArrayList::new));
				} catch (IOException e) {
					throw new UnexpectedIOException(
						"Failed to read the contents of the folder: " + e.getMessage(),
						"Failed to read the contents of the folder.",
						e
					);
				}
			} else {
				this.files = new ArrayList<>(16);
			}
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Record that contains output stream the export file can be written to and future that will be completed when the
	 * file is written.
	 *
	 * @param fileForFetchFuture Future that will be completed when the file is written.
	 * @param outputStream       Output stream the file can be written to.
	 */
	public record ExportFileHandle(
		@Nonnull CompletableFuture<FileForFetch> fileForFetchFuture,
		@Nonnull OutputStream outputStream
	) {

	}

	/**
	 * Thin wrapper around {@link FileOutputStream} that writes metadata file on close.
	 */
	private final static class WriteMetadataOnCloseOutputStream extends FileOutputStream implements Closeable {
		private final CompletableFuture<FileForFetch> fileForFetchFuture;
		private final Supplier<FileForFetch> onClose;

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
			try {
				super.close();
			} finally {
				this.fileForFetchFuture.complete(onClose.get());
			}
		}

	}

	/**
	 * Input stream that wraps the file input stream and provides the file ID. This input stream is used to distinguish
	 * this input stream from other input streams that read data for example from the network.
	 */
	@RequiredArgsConstructor
	public static class ExportFileInputStream extends InputStream {
		@Getter private final FileForFetch file;
		private final InputStream inputStream;

		@Override
		public int read() throws IOException {
			return inputStream.read();
		}

		@Override
		public int read(byte[] b) throws IOException {
			return inputStream.read(b);
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			return inputStream.read(b, off, len);
		}

		@Override
		public byte[] readAllBytes() throws IOException {
			return inputStream.readAllBytes();
		}

		@Override
		public byte[] readNBytes(int len) throws IOException {
			return inputStream.readNBytes(len);
		}

		@Override
		public int readNBytes(byte[] b, int off, int len) throws IOException {
			return inputStream.readNBytes(b, off, len);
		}

		@Override
		public long skip(long n) throws IOException {
			return inputStream.skip(n);
		}

		@Override
		public void skipNBytes(long n) throws IOException {
			inputStream.skipNBytes(n);
		}

		@Override
		public int available() throws IOException {
			return inputStream.available();
		}

		@Override
		public void close() throws IOException {
			inputStream.close();
		}

		@Override
		public void mark(int readlimit) {
			inputStream.mark(readlimit);
		}

		@Override
		public void reset() throws IOException {
			inputStream.reset();
		}

		@Override
		public boolean markSupported() {
			return inputStream.markSupported();
		}

		@Override
		public long transferTo(OutputStream out) throws IOException {
			return inputStream.transferTo(out);
		}
	}

}
