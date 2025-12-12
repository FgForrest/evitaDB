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

package io.evitadb.spi.export.model;

import io.evitadb.api.file.FileForFetch;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handle representing a single exported file produced by the engine.
 *
 * This interface decouples the engine's business logic from the underlying storage
 * implementation (e.g. local filesystem, network share, object store). The engine writes bytes to
 * the {@link #outputStream()} and, once finished, calls {@link #close()} to finalize the file. When
 * the file becomes available for download, {@link #fileForFetchFuture()} completes with a
 * {@link FileForFetch} descriptor that higher layers can use to expose the file to clients.
 *
 * Lifecycle in short:
 * - obtain a handle from the export service
 * - write data to the returned `OutputStream`
 * - call `close()` to flush and finalize the file
 * - await `fileForFetchFuture()` to publish/serve the file
 *
 * The handle is {@link AutoCloseable}; prefer try-with-resources to guarantee finalization.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ExportFileHandle extends AutoCloseable {

	/**
	 * Returns a stable identifier of the exported file.
	 *
	 * The identifier can be used for correlation in logs and APIs. Implementations should
	 * return the same identifier that will be present in the resulting {@link FileForFetch}.
	 *
	 * @return unique identifier of the file
	 */
	@Nonnull
	UUID fileId();

	/**
	 * Future that completes when the file is finalized and ready to be fetched.
	 *
	 * The future completes with a {@link FileForFetch} descriptor that can be used by higher
	 * layers to make the file available for clients (e.g. HTTP download). If finalization fails,
	 * the future completes exceptionally.
	 *
	 * @return a future providing the {@link FileForFetch} once the file is ready
	 */
	@Nonnull
	CompletableFuture<FileForFetch> fileForFetchFuture();

	/**
	 * Returns the current size of the target file in bytes.
	 *
	 * While the file is being written, the value may increase. After {@link #close()} completes,
	 * the value equals the final size of the exported file.
	 *
	 * @return number of bytes successfully persisted to the target file
	 */
	long size();

	/**
	 * Finalizes the exported file.
	 *
	 * This call flushes and closes the underlying stream and triggers publication of the file so
	 * that {@link #fileForFetchFuture()} can complete. Implementations should ensure idempotency:
	 * multiple invocations should be safe.
	 *
	 * @throws IOException if flushing or finalization of the target file fails
	 */
	@Override
	void close() throws IOException;

	/**
	 * Output stream for writing the contents of the exported file.
	 *
	 * The returned stream is managed by this handle and remains valid until {@link #close()} is
	 * invoked. To ensure proper finalization and publication of the file, prefer closing the handle
	 * rather than the stream directly.
	 *
	 * Thread-safety is implementation specific; unless stated otherwise, assume a single writer.
	 *
	 * @return non-null {@link OutputStream} to write export data to
	 */
	@Nonnull
	OutputStream outputStream();

}
