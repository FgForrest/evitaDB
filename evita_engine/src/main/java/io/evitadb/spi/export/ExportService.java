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

package io.evitadb.spi.export;

import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.EvitaIOException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.export.model.ExportFileHandle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Closeable;
import java.io.InputStream;
import java.nio.file.OpenOption;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Contract for exporting engine-produced files in a storage-agnostic way.
 *
 * Implementations encapsulate the physical persistence of exported files (e.g. local filesystem,
 * network share, object store) so that the engine's business logic can create, publish and serve
 * files without being coupled to a particular storage. The typical lifecycle is:
 *
 * - create a new file via {@link #storeFile(String, String, String, String)} which returns an
 *   {@link io.evitadb.spi.export.model.ExportFileHandle} with an {@code OutputStream}
 * - write bytes to the stream and close the handle to finalize and publish the file
 * - obtain the {@link io.evitadb.api.file.FileForFetch} descriptor and expose it to clients
 * - list or retrieve files later via {@link #listFilesToFetch(int, int, Set)} and
 *   {@link #getFile(UUID)}; contents can be read with {@link #fetchFile(UUID)}
 *
 * The service extends {@link java.io.Closeable}; callers should close it to release resources when
 * no longer needed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
public interface ExportService extends Closeable {

	/**
	 * Returns a paginated list of files currently available for fetching.
	 *
	 * The result can be filtered by file origin. Pass an empty set to return files of all origins.
	 *
	 * @param page	 requested page (1-based or implementation defined)
	 * @param pageSize	 requested page size
	 * @param origin	 set of allowed origins; empty set means no filtering
	 * @return paginated list of descriptors for files ready to be fetched
	 */
	@Nonnull
	PaginatedList<FileForFetch> listFilesToFetch(int page, int pageSize, @Nonnull Set<String> origin);

	/**
	 * Returns the file descriptor for the specified {@code fileId} or an empty value when it does not
	 * exist.
	 *
	 * @param fileId	 unique identifier of the file
	 * @return optional descriptor of the file available for fetch
	 */
	@Nonnull
	Optional<FileForFetch> getFile(@Nonnull UUID fileId);

	/**
	 * Creates a new export file and returns a handle for writing its contents.
	 *
	 * The returned {@link ExportFileHandle} provides an {@code OutputStream} to write bytes. Closing
	 * the handle finalizes and publishes the file; a corresponding {@link FileForFetch} will then be
	 * available to readers.
	 *
	 * Notes:
	 * - {@code fileName} may be sanitized or made unique by the implementation
	 * - {@code description} is optional and can be used in listings and UIs
	 * - {@code contentType} should be a valid MIME type (e.g. {@code application/zip})
	 * - {@code origin} can be used to group or filter files (see
	 *   {@link #listFilesToFetch(int, int, Set)})
	 *
	 * @param fileName	 preferred file name
	 * @param description	 optional human-readable description
	 * @param contentType	 MIME type of the content
	 * @param origin	 optional origin tag of the file
	 * @return handle for writing and publishing the file
	 */
	@Nonnull
	ExportFileHandle storeFile(
		@Nonnull String fileName,
		@Nullable String description,
		@Nonnull String contentType,
		@Nullable String origin
	);

	/**
	 * Opens the exported file for reading and returns an {@code InputStream} with its contents.
	 *
	 * The caller is responsible for closing the stream. The operation does not remove the file from
	 * the storage.
	 *
	 * @param fileId	 file identifier
	 * @return non-null stream to read the file contents
	 * @throws FileForFetchNotFoundException if the file does not exist
	 */
	@Nonnull
	InputStream fetchFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException;

	/**
	 * Deletes the file identified by {@code fileId}.
	 *
	 * @param fileId	 file identifier
	 * @throws FileForFetchNotFoundException if the file is not found
	 * @throws UnexpectedIOException	 if the underlying storage deletion fails
	 */
	void deleteFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException;

	/**
	 * Triggers periodic maintenance of exported files using the implementation's configured policy.
	 *
	 * Implementations typically compute a threshold date based on
	 * `exportFileHistoryExpirationSeconds` and call
	 * {@link #purgeFiles(OffsetDateTime)} to delete expired files and enforce storage limits.
	 * The return value is suitable for scheduling frameworks that expect a delay; returning `0`
	 * schedules the task as usual.
	 *
	 * @return a scheduling hint; implementations commonly return `0`
	 */
	long purgeFiles();

	/**
	 * Purges exported files according to retention and storage size limits.
	 *
	 * Implementations should:
	 * - delete files older than {@code thresholdDate}
	 * - remove orphaned physical files without metadata that are older than the threshold
	 * - reduce total occupied size to respect `exportDirectorySizeLimitBytes`
	 *
	 * The operation is best-effort and may keep files needed by concurrent readers.
	 *
	 * @param thresholdDate	 files strictly older than this date can be removed
	 */
	void purgeFiles(@Nonnull OffsetDateTime thresholdDate);

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
	void writeFileMetadata(
		@Nonnull FileForFetch fileForFetch,
		@Nonnull OpenOption... options
	) throws EvitaIOException;

}
