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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.store.exception.InvalidStoragePathException;
import io.evitadb.store.exception.StorageException;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.exception.SyncFailedException;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.Assert.isTrue;

/**
 * Write handle protects access to the {@link ObservableOutput} by {@link ReentrantLock} allowing only single
 * client to use the resource in parallel. Waiting may time out after {@link #lockTimeoutSeconds}. Some methods allow
 * to execute premise check to verify whether the parent is still in operating mode, others ensure that the changes
 * are safely persisted on disk when the method finishes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class WriteOnlyFileHandle implements WriteOnlyHandle {
	/**
	 * Logical name of the file that backs the {@link OffsetIndex} - used for observability.
	 */
	protected final String logicalName;
	/**
	 * Name of the catalog the persistence service relates to - used for observability.
	 */
	private final String catalogName;
	/**
	 * Type of the file that backs the {@link OffsetIndex} - used for observability.
	 */
	private final FileType fileType;
	/**
	 * The maximum time (in seconds) that a thread may wait to acquire the lock on the file handle.
	 * If a thread cannot acquire the lock within this time, a StorageException is thrown.
	 */
	private final long lockTimeoutSeconds;
	/**
	 * Reference to the {@link StorageOptions} object that contains configuration options for the storage system.
	 */
	private final StorageOptions storageOptions;
	/**
	 * The path to the target file that this handle is associated with.
	 * This handle provides write-only access to the file at this path.
	 */
	@Getter private final Path targetFile;
	/**
	 * The variable `observableOutputKeeper` is an instance of the class `ObservableOutputKeeper`. It is used to keep
	 * references to `ObservableOutput` instances that internally maintain large buffers for serialization. The need for
	 * these buffers is determined by the number of open read-write sessions to a catalog. If there is at least one open
	 * read-write session, the `ObservableOutput` instances need to be kept. Otherwise, if there are only read sessions,
	 * the `ObservableOutput` instances can be disposed of.
	 *
	 * The `ObservableOutputKeeper` class provides methods to get or create an `ObservableOutput` for a specific target
	 * file, free an `ObservableOutput` for a target file, prepare the holder for `ObservableOutput`, check if
	 * the cached outputs are prepared, and free all cached `ObservableOutput` instances.
	 */
	private final ObservableOutputKeeper observableOutputKeeper;
	/**
	 * This variable represents a lock used for protecting access to a handle in the {@link WriteOnlyFileHandle} class.
	 * It is an instance of the {@link ReentrantLock} class, which is a reentrant mutual exclusion lock.
	 *
	 * The handleLock is used to synchronize access to the {@link ObservableOutput} object in the WriteOnlyHandle interface.
	 * The methods in the WriteOnlyHandle interface that require access to the {@link ObservableOutput} object are wrapped
	 * in a synchronized block with the handleLock as the monitor object. This ensures that only one thread can access
	 * the {@link ObservableOutput} object at a time, preventing concurrent modification and ensuring thread safety.
	 *
	 * @see WriteOnlyHandle
	 * @see StorageOptions
	 */
	private final ReentrantLock handleLock = new ReentrantLock();

	/**
	 * Retrieves the target file where data will be written.
	 *
	 * @param filePath The path to the target file.
	 * @return The target file object.
	 * @throws InvalidStoragePathException if the storage path parent doesn't represent a directory.
	 * @throws StorageException            if there is an error creating the file or if it cannot be accessed.
	 */
	@Nonnull
	static File getTargetFile(@Nonnull Path filePath) {
		final File targetFileRef = filePath.toFile();
		if (!targetFileRef.exists()) {
			final File directory = targetFileRef.getParentFile();
			// ensure directory exits
			if (!directory.exists()) {
				//noinspection ResultOfMethodCallIgnored
				directory.mkdirs();
			}
			isTrue(
				directory.isDirectory(),
				() -> new InvalidStoragePathException("Storage path doesn't represent a directory: " + directory)
			);

			// create empty file if no file exists
			if (!targetFileRef.exists()) {
				final boolean fileCreated;
				try {
					fileCreated = targetFileRef.createNewFile();
				} catch (IOException e) {
					throw new StorageException("Cannot create file " + targetFileRef + "!", e);
				}
				isPremiseValid(
					fileCreated,
					() -> new StorageException("File `" + filePath + "` doesn't exist and was not created!")
				);
			}
			return targetFileRef;
		} else {
			return targetFileRef;
		}
	}

	/**
	 * Creates an observable output stream for a file using the specified file path and storage options.
	 * The method ensures the file is opened for writing, optionally computes a CRC32 checksum,
	 * and applies compression if specified in the storage options.
	 *
	 * @param theFilePath    The path to the target file to which data will be written.
	 * @param storageOptions The storage options that define buffer size, checksum computation, and compression settings.
	 * @return An {@code ObservableOutput} instance wrapping a {@code FileOutputStream} for the specified file.
	 * @throws StorageException If the target file cannot be opened or accessed.
	 */
	@Nonnull
	static ObservableOutput<FileOutputStream> createObservableOutput(@Nonnull Path theFilePath, @Nonnull StorageOptions storageOptions) {
		try {
			final File theFile = theFilePath.toFile();
			final FileOutputStream targetOs = new FileOutputStream(theFile, true);
			final ObservableOutput<FileOutputStream> output = new ObservableOutput<>(
				targetOs,
				Math.min(ObservableOutput.DEFAULT_FLUSH_SIZE, storageOptions.outputBufferSize()),
				storageOptions.outputBufferSize(),
				theFile.length()
			);
			if (storageOptions.computeCRC32C()) {
				output.computeCRC32();
			}
			if (storageOptions.compress()) {
				output.compress();
			}
			return output;
		} catch (FileNotFoundException ex) {
			throw new StorageException("Target file " + theFilePath + " cannot be opened!", ex);
		}
	}

	/**
	 * Synchronizes the data stored in the provided observable output stream to the disk.
	 *
	 * @param os The observable output stream to synchronize.
	 * @throws SyncFailedException if the synchronization operation failed.
	 */
	static void doSync(@Nonnull ObservableOutput<FileOutputStream> os, boolean fsSync) {
		// execute fsync so that data are really stored to the disk
		try {
			os.flush();
			if (fsSync) {
				os.getOutputStream().getFD().sync();
			}
		} catch (IOException e) {
			throw new SyncFailedException(e);
		}
	}

	public WriteOnlyFileHandle(
		@Nonnull Path targetFile,
		@Nonnull StorageOptions storageOptions,
		@Nonnull ObservableOutputKeeper observableOutputKeeper
	) {
		this(null, null, null, storageOptions, targetFile, observableOutputKeeper);
	}

	public WriteOnlyFileHandle(
		@Nullable String catalogName,
		@Nullable FileType fileType,
		@Nullable String logicalName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull Path targetFile,
		@Nonnull ObservableOutputKeeper observableOutputKeeper
	) {
		this.catalogName = catalogName;
		this.fileType = fileType;
		this.logicalName = logicalName;
		this.lockTimeoutSeconds = observableOutputKeeper.getLockTimeoutSeconds();
		this.storageOptions = storageOptions;
		this.targetFile = targetFile;
		Assert.isPremiseValid(getTargetFile(targetFile) != null, "Target file should be created or exception thrown!");
		this.observableOutputKeeper = observableOutputKeeper;
	}

	@Override
	public <T> T checkAndExecute(@Nonnull String operation, @Nonnull Runnable premise, @Nonnull Function<ObservableOutput<?>, T> logic) {
		try {
			if (this.handleLock.tryLock(this.lockTimeoutSeconds, TimeUnit.SECONDS)) {
				try {
					premise.run();
					return this.observableOutputKeeper.executeWithOutput(
						this.targetFile,
						this::createObservableOutput,
						logic::apply
					);
				} finally {
					this.handleLock.unlock();
				}
			}
			throw new StorageException(operation + " within timeout!");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StorageException(operation + " due to interrupt!");
		}
	}

	@Override
	public void checkAndExecuteAndSync(@Nonnull String operation, @Nonnull Runnable premise, @Nonnull Consumer<ObservableOutput<?>> logic) {
		try {
			if (this.handleLock.tryLock(this.lockTimeoutSeconds, TimeUnit.SECONDS)) {
				try {
					premise.run();
					this.observableOutputKeeper.executeWithOutput(
						this.targetFile,
						this::createObservableOutput,
						observableOutput -> {
							logic.accept(observableOutput);
							doSync(observableOutput, this.storageOptions.syncWrites());
						}
					);
					return;
				} finally {
					this.handleLock.unlock();
				}
			}
			throw new StorageException(operation + " within timeout!");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StorageException(operation + " due to interrupt!");
		}
	}

	@Override
	public <S, T> T checkAndExecuteAndSync(@Nonnull String operation, @Nonnull Runnable premise, @Nonnull Function<ObservableOutput<?>, S> logic, @Nonnull BiFunction<ObservableOutput<?>, S, T> postExecutionLogic) {
		try {
			if (this.handleLock.tryLock(this.lockTimeoutSeconds, TimeUnit.SECONDS)) {
				try {
					premise.run();
					return this.observableOutputKeeper.executeWithOutput(
						this.targetFile,
						this::createObservableOutput,
						observableOutput -> {
							final S result = logic.apply(observableOutput);
							doSync(observableOutput, this.storageOptions.syncWrites());
							return postExecutionLogic.apply(observableOutput, result);
						}
					);
				} finally {
					this.handleLock.unlock();
				}
			}
			throw new StorageException(operation + " within timeout!");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StorageException(operation + " due to interrupt!");
		}
	}

	@Override
	public long getLastWrittenPosition() {
		return this.targetFile.toFile().length();
	}

	@Nonnull
	@Override
	public ReadOnlyHandle toReadOnlyHandle() {
		return new ReadOnlyFileHandle(
			this.catalogName, this.fileType, this.logicalName,
			this.targetFile, this.storageOptions
		);
	}

	@Override
	public void close() {
		try {
			this.handleLock.lockInterruptibly();
			this.observableOutputKeeper.close(this.targetFile);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StorageException("Failed to close file due to interrupt!");
		} finally {
			this.handleLock.unlock();
		}
	}

	@Override
	public String toString() {
		/* TODO JNO - remove size printing */
		return "write handle: " + this.targetFile + " (current size on disk: " + this.targetFile.toFile().length() + " bytes)";
	}

	/**
	 * A factory function that creates an observable output stream for a file using the provided path and storage options.
	 */
	@Nonnull
	private ObservableOutput<FileOutputStream> createObservableOutput(@Nonnull Path theFilePath) {
		return createObservableOutput(theFilePath, this.storageOptions);
	}

}
