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

package io.evitadb.utils;


import io.evitadb.exception.FolderAlreadyUsedException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The FolderLock class is used to enforce exclusive access to a specific folder by creating and locking
 * a file in the folder. This is useful for ensuring that only one process can access or manipulate
 * the folder's contents at a time.
 *
 * This class implements the Closeable interface, meaning it should be closed after use to release
 * resources and clean up the lock file. When the lock is acquired, it creates a file named ".lock"
 * in the folder and locks it using a file lock. Attempting to create another FolderLock instance
 * on the same folder while it is already locked will throw a {@link FolderAlreadyUsedException}.
 *
 * Usage:
 * - To ensure exclusive access to a folder, instantiate FolderLock with the desired folder path.
 * - Remember to call the {@code close()} method to properly release the lock and delete the lock file
 * when access to the folder is no longer needed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
@NotThreadSafe
public class FolderLock implements Closeable {
	private static final String LOCK_FILE_NAME = ".lock";
	/**
	 * Helper map helping to identify the lock owner in case of FolderAlreadyUsedException (helpful for tests).
	 */
	private static final Map<String, Exception> ACQUIRED_LOCKS = new ConcurrentHashMap<>(32);
	/**
	 * Channel for the lock file.
	 */
	private final FileChannel lockFileChannel;
	/**
	 * Lock for the lock file.
	 */
	private final FileLock lockFileLock;
	/**
	 * Folder to lock.
	 */
	private final Path lockFilePath;

	public FolderLock(@Nonnull Path folder) {
		this.lockFilePath = folder.resolve(LOCK_FILE_NAME);

		// lock the export directory
		try {
			this.lockFileChannel = FileChannel.open(this.lockFilePath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			this.lockFileLock = this.lockFileChannel.tryLock();
			if (this.lockFileLock == null) {
				throw new FolderAlreadyUsedException(
					this.lockFilePath.toAbsolutePath(),
					ACQUIRED_LOCKS.get(this.lockFilePath.toAbsolutePath().toString())
				);
			} else {
				ACQUIRED_LOCKS.put(this.lockFilePath.toAbsolutePath().toString(), new Exception());
			}
		} catch (OverlappingFileLockException e) {
			throw new FolderAlreadyUsedException(
				this.lockFilePath.toAbsolutePath(),
				ACQUIRED_LOCKS.get(this.lockFilePath.toAbsolutePath().toString())
			);
		} catch (IOException e) {
			throw new GenericEvitaInternalError(
				"Failed to create lock in the folder " + folder + ".",
				"Failed to create lock in the folder.",
				e
			);
		}
	}

	@Override
	public void close() throws IOException {
		IOUtils.close(
			() -> new UnexpectedIOException(
				"Failed to release and close the lock file " + this.lockFilePath + ".",
				"Failed to release and close the lock file."
			),
			(IOUtils.IOExceptionThrowingRunnable) this.lockFileLock::release,
			(IOUtils.IOExceptionThrowingRunnable) this.lockFileChannel::close
		);
		FileUtils.deleteFileIfExists(this.lockFilePath);
		ACQUIRED_LOCKS.remove(this.lockFilePath.toAbsolutePath().toString());
	}

	/**
	 * Retrieves the path of the lock file associated with the folder.
	 *
	 * @return the {@link Path} object representing the lock file's location, ensuring it is non-null.
	 */
	@Nonnull
	public Path lockFilePath() {
		return this.lockFilePath;
	}

	@Override
	public String toString() {
		return this.lockFilePath.toString();
	}
}
