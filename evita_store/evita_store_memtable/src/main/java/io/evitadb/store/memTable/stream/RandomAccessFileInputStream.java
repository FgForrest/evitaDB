/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.memTable.stream;


import io.evitadb.store.exception.StorageException;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Objects;

/**
 * Streams data from a {@link RandomAccessFile} starting at its current position.
 * This class was copied from Apache Commons to avoid linking whole library - thanks!
 * Only changes made in this class is wrapping checked exceptions into unchecked ones.
 *
 * @since 2.8.0
 */
public class RandomAccessFileInputStream extends InputStream {
	private final boolean closeOnClose;
	private final RandomAccessFile randomAccessFile;

	/**
	 * Constructs a new instance configured to leave the underlying file open when this stream is closed.
	 *
	 * @param file The file to stream.
	 */
	public RandomAccessFileInputStream(final RandomAccessFile file) {
		this(file, false);
	}

	/**
	 * Constructs a new instance.
	 *
	 * @param file The file to stream.
	 * @param closeOnClose Whether to close the underlying file when this stream is closed.
	 */
	public RandomAccessFileInputStream(final RandomAccessFile file, final boolean closeOnClose) {
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
			return randomAccessFile.length() - randomAccessFile.getFilePointer();
		} catch (IOException e) {
			throw new StorageException("Error occurred while accessing length.", e);
		}
	}

	@Override
	public void close() {
		try {
			super.close();
			if (closeOnClose) {
				randomAccessFile.close();
			}
		} catch (IOException e) {
			throw new StorageException("Error occurred while closing file.", e);
		}
	}

	/**
	 * Gets the underlying file.
	 *
	 * @return the underlying file.
	 */
	public RandomAccessFile getRandomAccessFile() {
		return randomAccessFile;
	}

	/**
	 * Returns whether to close the underlying file when this stream is closed.
	 *
	 * @return Whether to close the underlying file when this stream is closed.
	 */
	public boolean isCloseOnClose() {
		return closeOnClose;
	}

	@Override
	public int read() {
		try {
			return randomAccessFile.read();
		} catch (IOException e) {
			throw new StorageException("Error while reading the file", e);
		}
	}

	@Override
	public int read(@Nonnull final byte[] bytes) {
		try {
			return randomAccessFile.read(bytes);
		} catch (IOException e) {
			throw new StorageException("Error while reading the file", e);
		}
	}

	@Override
	public int read(@Nonnull final byte[] bytes, final int offset, final int length) {
		try {
			return randomAccessFile.read(bytes, offset, length);
		} catch (IOException e) {
			throw new StorageException("Error while reading the file", e);
		}
	}

	/**
	 * Delegates to the underlying file.
	 *
	 * @param position See {@link RandomAccessFile#seek(long)}.
	 * @see RandomAccessFile#seek(long)
	 */
	public void seek(final long position) {
		try {
			randomAccessFile.seek(position);
		} catch (IOException e) {
			throw new StorageException("Error while seeking the position file", e);
		}
	}

	@Override
	public long skip(final long skipCount) {
		if (skipCount <= 0) {
			return 0;
		}
		try {
			final long filePointer = randomAccessFile.getFilePointer();
			final long fileLength = randomAccessFile.length();
			if (filePointer >= fileLength) {
				return 0;
			}
			final long targetPos = filePointer + skipCount;
			final long newPos = targetPos > fileLength ? fileLength - 1 : targetPos;
			if (newPos > 0) {
				seek(newPos);
			}
			return randomAccessFile.getFilePointer() - filePointer;
		} catch (IOException e) {
			throw new StorageException("Error while skipping contents in the file", e);
		}
	}
}