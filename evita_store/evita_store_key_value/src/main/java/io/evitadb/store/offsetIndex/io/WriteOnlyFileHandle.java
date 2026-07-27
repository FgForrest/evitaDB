/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.checksum.ChecksumFactory;
import io.evitadb.store.compression.CompressionFactory;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.exception.InvalidStoragePathException;
import io.evitadb.store.offsetIndex.exception.SyncFailedException;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.Deflater;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * Write handle protects access to the {@link ObservableOutput} by {@link ReentrantLock} allowing only single
 * client to use the resource in parallel. Waiting may time out after {@link #lockTimeoutSeconds}. Some methods allow
 * to execute premise check to verify whether the parent is still in operating mode, others ensure that the changes
 * are safely persisted on disk when the method finishes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
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
	private final int lockTimeoutSeconds;
	/**
	 * Size of the memory buffer used for write operations, in bytes.
	 * This buffer size limits the maximum size of individual records that can be written.
	 * Sourced from {@link StorageOptions#outputBufferSize()}, typically defaults to 2MB.
	 */
	private final int outputBufferSize;
	/**
	 * Controls whether OS buffer flush is forced at safe points to ensure data durability.
	 * When true, forces file system sync operations to persist data to physical storage.
	 * Sourced from {@link StorageOptions#syncWrites()}.
	 */
	private final boolean syncWrites;
	/**
	 * When set, the device flush at the end of each write is handed to this registry instead of being issued inline,
	 * and the file is made durable later by whoever owns the registry (see {@link PendingSyncRegistry}). The buffer
	 * flush is unaffected either way.
	 *
	 * Null restores the historical behaviour: sync inline whenever {@link #syncWrites} is set. Only the catalog and
	 * entity collection data files - the ones a trunk round writes and a checkpoint later forces - are given
	 * a registry; the bootstrap file, the engine files and the write-ahead log keep syncing inline.
	 */
	@Nullable private final PendingSyncRegistry pendingSyncRegistry;
	/**
	 * Factory for creating checksums for data integrity verification during write operations.
	 * Sourced from {@link StorageOptions#computeCRC32C()}.
	 */
	private final ChecksumFactory checksumFactory;
	/**
	 * Factory for creating compressor and decompressor instances for optional data compression.
	 * Compression is applied only when it reduces data size below the original.
	 * Sourced from {@link StorageOptions#compress()}.
	 */
	private final CompressionFactory compressionFactory;
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
	 * @throws io.evitadb.exception.EvitaIOException if there is an error creating the file or if it cannot be accessed.
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
			Assert.isTrue(
				directory.isDirectory(),
				() -> new InvalidStoragePathException("Storage path doesn't represent a directory: " + directory)
			);

			// create empty file if no file exists
			if (!targetFileRef.exists()) {
				final boolean fileCreated;
				try {
					fileCreated = targetFileRef.createNewFile();
				} catch (IOException e) {
					throw new UnexpectedIOException(
						"Cannot create file " + targetFileRef + "!",
						"Cannot create the file.",
						e
					);
				}
				isPremiseValid(
					fileCreated,
					() -> new UnexpectedIOException("File `" + filePath + "` doesn't exist and was not created!")
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
	 * @param theFilePath The path to the target file to which data will be written.
	 * @param outputBufferSize the size of the output buffer to use for writing data.
	 * @param checksum The checksum calculator touse for data integrity verification.
	 * @param deflater The deflater to use for compressing data, or {@code null} if no compression is desired.
	 *
	 * @return An {@code ObservableOutput} instance wrapping a {@code FileOutputStream} for the specified file.
	 * @throws UnexpectedIOException If the target file cannot be opened or accessed.
	 */
	@Nonnull
	static ObservableOutput<FileOutputStream> createObservableOutput(
		@Nonnull Path theFilePath,
		int outputBufferSize,
		@Nonnull Checksum checksum,
		@Nullable Deflater deflater
	) {
		try {
			final File theFile = theFilePath.toFile();
			final FileOutputStream targetOs = new FileOutputStream(theFile, true);
			return new ObservableOutput<>(
				targetOs,
				Math.min(ObservableOutput.DEFAULT_FLUSH_SIZE, outputBufferSize),
				outputBufferSize,
				theFile.length(),
				checksum,
				deflater
			);
		} catch (FileNotFoundException ex) {
			throw new UnexpectedIOException(
				"Target file " + theFilePath + " cannot be opened!",
				"Target file cannot be opened.",
				ex
			);
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

	/**
	 * Flushes the write buffer and then either forces the bytes to the device or notes the file as owing a force.
	 *
	 * Only the **device** flush is ever deferred. `os.flush()` runs on both paths, so offsets are assigned, the Kryo
	 * buffer is released and the freshly written records are readable through the page cache exactly as before -
	 * everything downstream of this call sees the same state either way. What changes is solely when the operating
	 * system is told to put those pages on the platter.
	 *
	 * @param os the output whose buffer is to be flushed
	 */
	private void doSyncOrDefer(@Nonnull ObservableOutput<FileOutputStream> os) {
		if (this.pendingSyncRegistry == null) {
			doSync(os, this.syncWrites);
		} else {
			doSync(os, false);
			this.pendingSyncRegistry.noteSyncPending(this);
		}
	}

	@Override
	public void forceDurable() {
		// deliberately opened fresh rather than reusing the output's descriptor: ObservableOutputKeeper owns
		// that one and may have released it since the write. fsync is a property of the file, not of the
		// descriptor, so a new channel flushes exactly the same dirty pages. The extra open/close is a couple
		// of microseconds against a device flush measured at ~5 ms on this class of storage.
		try (final FileChannel channel = FileChannel.open(this.targetFile, StandardOpenOption.WRITE)) {
			// force(true) - metadata included. On an appended file the length IS metadata, and a stale length
			// silently truncates the very records the bootstrap record is about to point at.
			channel.force(true);
		} catch (NoSuchFileException e) {
			// a REAL state, not an unhandled branch: compaction rewrites a data file under a new index and deletes
			// the old one, so a handle noted as owing a force may name a file that no longer exists by the time the
			// checkpoint runs. There is nothing left to make durable - no record written from here on can point into
			// a deleted file, and compaction fsyncs the replacement itself before the bootstrap record naming it is
			// written. Forcing a file that is gone is not possible and not needed.
			if (log.isDebugEnabled()) {
				log.debug("Skipping deferred sync of `{}` - the file no longer exists.", this.targetFile);
			}
		} catch (IOException e) {
			throw new SyncFailedException(e);
		}
	}

	public WriteOnlyFileHandle(
		@Nonnull Path targetFile,
		int outputBufferSize,
		boolean syncWrites,
		@Nonnull ChecksumFactory checksumCalculatorFactory,
		@Nonnull CompressionFactory compressionFactory,
		@Nonnull ObservableOutputKeeper observableOutputKeeper
	) {
		this(
			null, null, null, outputBufferSize, syncWrites,
			checksumCalculatorFactory, compressionFactory,
			targetFile, observableOutputKeeper
		);
	}

	public WriteOnlyFileHandle(
		@Nullable FileType fileType,
		@Nullable String logicalName,
		@Nonnull Path targetFile,
		int outputBufferSize,
		boolean syncWrites,
		@Nonnull ChecksumFactory checksumCalculatorFactory,
		@Nonnull CompressionFactory compressionFactory,
		@Nonnull ObservableOutputKeeper observableOutputKeeper
	) {
		this(
			null, fileType, logicalName, outputBufferSize, syncWrites,
			checksumCalculatorFactory, compressionFactory,
			targetFile, observableOutputKeeper
		);
	}

	public WriteOnlyFileHandle(
		@Nullable String catalogName,
		@Nullable FileType fileType,
		@Nullable String logicalName,
		int outputBufferSize,
		boolean syncWrites,
		@Nonnull ChecksumFactory checksumFactory,
		@Nonnull CompressionFactory compressionFactory,
		@Nonnull Path targetFile,
		@Nonnull ObservableOutputKeeper observableOutputKeeper
	) {
		this(
			catalogName, fileType, logicalName, outputBufferSize, syncWrites,
			checksumFactory, compressionFactory, targetFile, observableOutputKeeper, null
		);
	}

	/**
	 * Creates a handle whose device flush is deferred to a checkpoint instead of being issued at the end of every
	 * write.
	 *
	 * @param pendingSyncRegistry registry notified after each write instead of issuing `fsync`; null keeps the
	 *                            historical inline-sync behaviour
	 */
	public WriteOnlyFileHandle(
		@Nullable String catalogName,
		@Nullable FileType fileType,
		@Nullable String logicalName,
		int outputBufferSize,
		boolean syncWrites,
		@Nonnull ChecksumFactory checksumFactory,
		@Nonnull CompressionFactory compressionFactory,
		@Nonnull Path targetFile,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nullable PendingSyncRegistry pendingSyncRegistry
	) {
		// deferring a sync that would never have been issued would make the checkpoint force files for an operator
		// who explicitly turned durability off - the two settings are orthogonal and the caller resolves them
		isPremiseValid(
			pendingSyncRegistry == null || syncWrites,
			"Deferred sync must not be configured on a handle that does not sync at all!"
		);
		this.pendingSyncRegistry = pendingSyncRegistry;
		this.catalogName = catalogName;
		this.fileType = fileType;
		this.logicalName = logicalName;
		this.lockTimeoutSeconds = observableOutputKeeper.getLockTimeoutSeconds();
		this.outputBufferSize = outputBufferSize;
		this.syncWrites = syncWrites;
		this.checksumFactory = checksumFactory;
		this.compressionFactory = compressionFactory;
		this.targetFile = targetFile;
		isPremiseValid(getTargetFile(targetFile) != null, "Target file should be created or exception thrown!");
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
			throw new UnexpectedIOException(operation + " within timeout!");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GenericEvitaInternalError(operation + " due to interrupt!");
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
							doSyncOrDefer(observableOutput);
						}
					);
					return;
				} finally {
					this.handleLock.unlock();
				}
			}
			throw new UnexpectedIOException(operation + " within timeout!");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GenericEvitaInternalError(operation + " due to interrupt!");
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
							doSyncOrDefer(observableOutput);
							return postExecutionLogic.apply(observableOutput, result);
						}
					);
				} finally {
					this.handleLock.unlock();
				}
			}
			throw new UnexpectedIOException(operation + " within timeout!");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GenericEvitaInternalError(operation + " due to interrupt!");
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
			this.targetFile, this.checksumFactory, this.compressionFactory
		);
	}

	@Override
	public void close() {
		try {
			this.handleLock.lockInterruptibly();
			this.observableOutputKeeper.close(this.targetFile);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GenericEvitaInternalError("Failed to close file due to interrupt!");
		} finally {
			this.handleLock.unlock();
		}
	}

	@Override
	public String toString() {
		return "write handle: " + this.targetFile;
	}

	/**
	 * A factory function that creates an observable output stream for a file using the provided path and storage options.
	 */
	@Nonnull
	private ObservableOutput<FileOutputStream> createObservableOutput(@Nonnull Path theFilePath) {
		return createObservableOutput(
			theFilePath,
			this.outputBufferSize,
			this.checksumFactory.createChecksum(),
			this.compressionFactory.createCompressor().orElse(null)
		);
	}

}
