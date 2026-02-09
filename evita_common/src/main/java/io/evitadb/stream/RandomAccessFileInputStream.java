/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.stream;

import io.evitadb.exception.UnexpectedIOException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Objects;

/**
 * Streams data from a {@link RandomAccessFile} starting at its current position.
 * This class was copied from Apache Commons IO to avoid linking the whole library - thanks!
 * The only change made in this class is wrapping checked exceptions into unchecked ones.
 *
 * @since 2.8.0 (Apache Commons IO version)
 */
public class RandomAccessFileInputStream extends AbstractRandomAccessInputStream {
	/** Whether to close the underlying {@link RandomAccessFile} when this stream is closed. */
	private final boolean closeOnClose;
	/** The underlying random access file being streamed. */
	@Getter @Nonnull private final RandomAccessFile randomAccessFile;

	/**
	 * Constructs a new instance configured to leave the underlying file open when this stream is closed.
	 *
	 * @param file The file to stream.
	 */
	public RandomAccessFileInputStream(@Nonnull final RandomAccessFile file) {
		this(file, false);
	}

	/**
	 * Constructs a new instance.
	 *
	 * @param file The file to stream.
	 * @param closeOnClose Whether to close the underlying file when this stream is closed.
	 */
	public RandomAccessFileInputStream(@Nonnull final RandomAccessFile file, final boolean closeOnClose) {
		this.randomAccessFile = Objects.requireNonNull(file, "file");
		this.closeOnClose = closeOnClose;
	}

	/**
	 * Returns an estimate of the number of bytes that can be read (or skipped over) from this input stream.
	 *
	 * If there are more than {@link Integer#MAX_VALUE} bytes available, return {@link Integer#MAX_VALUE}.
	 *
	 * @return An estimate of the number of bytes that can be read.
	 */
	@Override
	public int available() {
		final long avail = availableLong();
		if (avail > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		return (int) avail;
	}

	/**
	 * Returns the number of bytes that can be read (or skipped over) from this input stream.
	 *
	 * @return The number of bytes that can be read.
	 */
	public long availableLong() {
		try {
			return Math.max(0L, this.randomAccessFile.length() - this.randomAccessFile.getFilePointer());
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Error occurred while accessing length: " + e.getMessage(),
				"Error occurred while accessing length.",
				e
			);
		}
	}

	/** {@inheritDoc} */
	@Override
	public void close() {
		try {
			super.close();
			if (this.closeOnClose) {
				this.randomAccessFile.close();
			}
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Error occurred while closing file: " + e.getMessage(),
				"Error occurred while closing file.",
				e
			);
		}
	}

	/** {@inheritDoc} */
	@Override
	public int read() {
		try {
			return this.randomAccessFile.read();
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Error while reading the file: " + e.getMessage(),
				"Error while reading the file.",
				e
			);
		}
	}

	/** {@inheritDoc} */
	@Override
	public int read(@Nonnull final byte[] bytes) {
		try {
			return this.randomAccessFile.read(bytes);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Error while reading the file: " + e.getMessage(),
				"Error while reading the file.",
				e
			);
		}
	}

	/** {@inheritDoc} */
	@Override
	public int read(@Nonnull final byte[] bytes, final int offset, final int length) {
		try {
			return this.randomAccessFile.read(bytes, offset, length);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Error while reading the file: " + e.getMessage(),
				"Error while reading the file.",
				e
			);
		}
	}

	/**
	 * Delegates to the underlying file.
	 *
	 * @param position See {@link RandomAccessFile#seek(long)}.
	 * @see RandomAccessFile#seek(long)
	 */
	@Override
	public void seek(final long position) {
		try {
			this.randomAccessFile.seek(position);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Error while seeking to position in file: " + e.getMessage(),
				"Error while seeking to position in file.",
				e
			);
		}
	}

	/** {@inheritDoc} */
	@Override
	public long getLength() {
		try {
			return this.randomAccessFile.length();
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Error while getting the length of the file: " + e.getMessage(),
				"Error while getting the length of the file.",
				e
			);
		}
	}

	/**
	 * Skips over and discards up to {@code skipCount} bytes from this stream, clamping at EOF.
	 * Returns 0 if {@code skipCount <= 0} or the stream is already at EOF.
	 */
	@Override
	public long skip(final long skipCount) {
		if (skipCount <= 0) {
			return 0;
		}
		try {
			final long filePointer = this.randomAccessFile.getFilePointer();
			final long fileLength = this.randomAccessFile.length();
			if (filePointer >= fileLength) {
				return 0;
			}
			final long targetPos = filePointer + skipCount;
			final long newPos = Math.min(targetPos, fileLength);
			seek(newPos);
			return this.randomAccessFile.getFilePointer() - filePointer;
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Error while skipping contents in the file: " + e.getMessage(),
				"Error while skipping contents in the file.",
				e
			);
		}
	}
}
