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

package io.evitadb.store.spi;

import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents an off-heap data reference with file backup. It contains either a path to the WAL file on disk or
 * a reference to a byte buffer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public final class OffHeapWithFileBackupReference implements Closeable {
	/**
	 * Path to the WAL file on disk (if the data did not fit into the off-heap buffer or there was no empty buffer
	 * available at the time).
	 */
	@Nullable private final Path filePath;
	/**
	 * Reference to the off-heap buffer (if the data fit into the off-heap buffer).
	 */
	@Nullable private final ByteBuffer buffer;
	/**
	 * The length of the data (correctly set both for data in buffer and data in file).
	 */
	@Getter private final int contentLength;
	/**
	 * The onClose action to be executed when the reference is closed.
	 */
	@Nonnull private final Runnable onClose;

	private OffHeapWithFileBackupReference(@Nullable Path filePath, @Nullable ByteBuffer buffer, int contentLength, @Nonnull Runnable onClose) {
		this.filePath = filePath;
		this.buffer = buffer;
		this.contentLength = contentLength;
		this.onClose = onClose;
	}

	/**
	 * Creates an OffHeapWithFileBackupReference object with the provided file path and content length.
	 *
	 * @param filePath       The path to the WAL file on disk.
	 * @param contentLength  The length of the data.
	 * @return An OffHeapWithFileBackupReference object.
	 */
	@Nonnull
	public static OffHeapWithFileBackupReference withFilePath(@Nonnull Path filePath, int contentLength, @Nonnull Runnable onClose) {
		return new OffHeapWithFileBackupReference(Objects.requireNonNull(filePath), null, contentLength, onClose);
	}

	/**
	 * Creates an OffHeapWithFileBackupReference object with the provided ByteBuffer and buffer peak.
	 *
	 * @param buffer The ByteBuffer containing the data.
	 * @param bufferPeak The peak value of the buffer.
	 * @return An OffHeapWithFileBackupReference object.
	 */
	@Nonnull
	public static OffHeapWithFileBackupReference withByteBuffer(@Nonnull ByteBuffer buffer, int bufferPeak, @Nonnull Runnable onClose) {
		return new OffHeapWithFileBackupReference(null, Objects.requireNonNull(buffer), bufferPeak, onClose);
	}

	/**
	 * Retrieves the file path associated with the OffHeapWithFileBackupReference object.
	 *
	 * @return An Optional containing the file path, or an empty Optional if the file path is null.
	 */
	@Nonnull
	public Optional<Path> getFilePath() {
		return Optional.ofNullable(this.filePath);
	}

	/**
	 * Retrieves the ByteBuffer object associated with the OffHeapWithFileBackupReference object.
	 *
	 * @return An Optional containing the ByteBuffer object, or an empty Optional if the ByteBuffer is null.
	 */
	@Nonnull
	public Optional<ByteBuffer> getBuffer() {
		return Optional.ofNullable(this.buffer);
	}

	@Override
	public void close() {
		// finalize resources
		this.onClose.run();
	}
}
