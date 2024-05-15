/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.metric.event.transaction.IsolatedWalFileClosedEvent;
import io.evitadb.core.metric.event.transaction.IsolatedWalFileOpenedEvent;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.exception.StorageException;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.exception.SyncFailedException;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle.OUTPUT_FACTORY;
import static io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle.getTargetFile;

/**
 * This implementation of {@link WriteOnlyFileHandle} tries to persist data primarily to the region of off-heap memory
 * managed by {@link OffHeapMemoryManager}. If there is no free region available or the written size exceeds the size
 * of the region, the data are offloaded to the disk and write continues to the temporary file (which is slower).
 * This class is not thread-safe and contains no locking.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class WriteOnlyOffHeapWithFileBackupHandle implements WriteOnlyHandle {
	/**
	 * The path to the target file that this handle is associated with.
	 * This handle provides write-only access to the file at this path.
	 */
	private final Path targetFile;
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
	 * OffHeapMemoryManager class is responsible for managing off-heap memory regions and providing
	 * free regions to acquire OutputStreams for writing data.
	 */
	private final OffHeapMemoryManager offHeapMemoryManager;
	/**
	 * OutputStream that is used to write data to the off-heap memory.
	 */
	private ObservableOutput<OffHeapMemoryOutputStream> offHeapMemoryOutput;
	/**
	 * OutputStream that is used to write data to the file.
	 */
	private ObservableOutput<FileOutputStream> fileOutput;
	/**
	 * Contains the information about the last end byte of fully written record.
	 */
	private int lastConsistentWrittenPosition = 0;

	/**
	 * Synchronizes the data stored in the provided observable output stream to the disk.
	 *
	 * @param os The observable output stream to synchronize.
	 * @throws SyncFailedException if the synchronization operation failed.
	 */
	private static void doSync(@Nonnull ObservableOutput<?> os) {
		// execute fsync so that data are really stored to the disk
		try {
			os.flush();
			if (os.getOutputStream() instanceof FileOutputStream fileOutputStream) {
				fileOutputStream.getFD().sync();
			}
		} catch (IOException e) {
			throw new SyncFailedException(e);
		}
	}

	public WriteOnlyOffHeapWithFileBackupHandle(
		@Nonnull Path targetFile,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull OffHeapMemoryManager offHeapMemoryManager
	) {
		this.offHeapMemoryManager = offHeapMemoryManager;
		this.targetFile = targetFile;
		this.observableOutputKeeper = observableOutputKeeper;
	}

	@Override
	public <T> T checkAndExecute(
		@Nonnull String operation,
		@Nonnull Runnable premise,
		@Nonnull Function<ObservableOutput<?>, T> logic
	) {
		return execute(operation, premise, logic, false);
	}

	@Override
	public void checkAndExecuteAndSync(
		@Nonnull String operation,
		@Nonnull Runnable premise,
		@Nonnull Consumer<ObservableOutput<?>> logic
	) {
		execute(
			operation,
			premise,
			(o) -> {
				logic.accept(o);
				return null;
			},
			true
		);
	}

	@Override
	public <S, T> T checkAndExecuteAndSync(
		@Nonnull String operation,
		@Nonnull Runnable premise,
		@Nonnull Function<ObservableOutput<?>, S> logic,
		@Nonnull BiFunction<ObservableOutput<?>, S, T> postExecutionLogic
	) {
		return execute(
			operation,
			premise,
			(o) -> postExecutionLogic.apply(o, logic.apply(o)),
			true
		);
	}

	@Override
	public long getLastWrittenPosition() {
		if (offHeapMemoryOutput != null) {
			return offHeapMemoryOutput.getOutputStream().getPeakDataWrittenLength();
		} else if (fileOutput != null) {
			return getTargetFile(targetFile).length();
		} else {
			return 0;
		}
	}

	@Nonnull
	@Override
	public ReadOnlyHandle toReadOnlyHandle() {
		// sync to disk first
		execute("flush", () -> {}, (o) -> null, true);

		return new OffHeapWithFileBackupReadOnlyHandle(this);
	}

	/**
	 * This method may not be called if the handle is terminated by conversion to {@link #toReadOnlyHandle()}. In such
	 * scenario the resources are cleared when the {@link OffHeapWithFileBackupReference} is closed. It's because
	 * the reference has longer lifespan than the {@link WriteOnlyOffHeapWithFileBackupHandle} and still needs
	 * resources for reading purposes.
	 */
	@Override
	public void close() {
		if (offHeapMemoryOutput != null) {
			releaseOffHeapMemory();
		} else if (fileOutput != null) {
			releaseTemporaryFile();
			// emit the event
			new IsolatedWalFileClosedEvent(
				offHeapMemoryManager.getCatalogName()
			).commit();
		}
	}

	/**
	 * Returns an OffHeapWithFileBackupReference object that represents the data written by this WriteOnlyOffHeapWithFileBackupHandle.
	 * If there is data stored in the off-heap memory, it creates an OffHeapWithFileBackupReference object with
	 * a ByteBuffer and the length of the data. If there is no data stored in the off-heap memory but there is data
	 * stored in a file, it creates an OffHeapWithFileBackupReference object with a file path and the length of the
	 * data.
	 *
	 * @return An OffHeapWithFileBackupReference object representing the data.
	 */
	@Nonnull
	public OffHeapWithFileBackupReference toReadOffHeapWithFileBackupReference() {
		// sync to disk first
		execute("flush", () -> {
		}, (o) -> null, true);

		if (offHeapMemoryOutput != null) {
			final ByteBuffer byteBuffer = offHeapMemoryOutput.getOutputStream().getByteBuffer();
			byteBuffer.limit(lastConsistentWrittenPosition);
			return OffHeapWithFileBackupReference.withByteBuffer(
				byteBuffer,
				lastConsistentWrittenPosition,
				this::releaseOffHeapMemory
			);
		} else if (fileOutput != null) {
			return OffHeapWithFileBackupReference.withFilePath(
				targetFile,
				Math.toIntExact(targetFile.toFile().length()),
				this::releaseTemporaryFile
			);
		} else {
			throw new GenericEvitaInternalError(
				"No content has been written using this write handle!"
			);
		}
	}

	@Override
	public String toString() {
		return "write handle: " + targetFile;
	}

	/**
	 * Releases the temporary file used for writing data.
	 *
	 * This method closes the file output stream, frees the observable output for the target file, and deletes the temporary file.
	 */
	private void releaseTemporaryFile() {
		if (this.fileOutput != null) {
			this.fileOutput.close();
			this.observableOutputKeeper.close(this.targetFile);
			Assert.isPremiseValid(getTargetFile(this.targetFile).delete(), "Failed to delete temporary file `" + this.targetFile + "`!");
		}
		this.fileOutput = null;
	}

	/**
	 * Releases the off-heap memory allocated for writing data.
	 *
	 * This method closes the off-heap memory output stream and sets it to null.
	 */
	private void releaseOffHeapMemory() {
		if (this.offHeapMemoryOutput != null) {
			this.offHeapMemoryOutput.close();
		}
		this.offHeapMemoryOutput = null;
	}

	/**
	 * Executes a given operation with the provided parameters.
	 *
	 * @param operation The name of the operation.
	 * @param premise   The runnable code that must be executed before the main logic.
	 * @param logic     The function that contains the main logic of the operation.
	 *                  Takes an ObservableOutput object as input and returns the result of the operation.
	 * @param sync      True if synchronization should be performed, false otherwise.
	 * @param <T>       The type of the result returned by the logic function.
	 * @return The result of the execution of the logic function.
	 * @throws StorageException If the execution is interrupted or times out.
	 */
	private <T> T execute(
		@Nonnull String operation,
		@Nonnull final Runnable premise,
		@Nonnull final Function<ObservableOutput<?>, T> logic,
		final boolean sync
	) {
		// first run premise
		premise.run();

		// if it passes execute the write logic
		final ObservableOutput<?> observableOutput = getObservableOutput();
		final boolean memoryImplementation = observableOutput.getOutputStream() instanceof OffHeapMemoryOutputStream;
		try {
			return executeLogic(logic, sync || memoryImplementation, observableOutput);
		} catch (BufferOverflowException ex) {
			// when we reach the end of the memory region
			if (observableOutput.getOutputStream() instanceof OffHeapMemoryOutputStream offHeapMemoryOutputStream) {
				// emit the event
				new IsolatedWalFileOpenedEvent(
					offHeapMemoryManager.getCatalogName()
				).commit();
				// we need to offload current data to the disk
				offloadMemoryToDisk(operation, offHeapMemoryOutputStream);
				// and retry the write logic again
				return executeLogic(logic, sync, getObservableOutput());
			} else {
				throw ex;
			}
		}
	}

	/**
	 * Executes the given logic function with the provided parameters.
	 *
	 * @param logic  The logic function to execute. Takes an ObservableOutput object as input and returns the result of the operation.
	 * @param sync   True if synchronization should be performed, false otherwise.
	 * @param output The observable output object to use as input for the logic function.
	 * @param <T>    The type of the result returned by the logic function.
	 * @return The result of the execution of the logic function.
	 */
	private <T> T executeLogic(
		@Nonnull Function<ObservableOutput<?>, T> logic,
		boolean sync,
		@Nonnull ObservableOutput<?> output
	) {
		final T result = logic.apply(output);
		if (sync) {
			doSync(output);
		}
		// update the last consistent written position
		lastConsistentWrittenPosition = Math.toIntExact(output.total());
		return result;
	}

	/**
	 * Offloads the data from the provided off-heap memory output stream to the disk.
	 *
	 * @param operation                 The name of the operation.
	 * @param offHeapMemoryOutputStream The off-heap memory output stream containing the data to be offloaded.
	 * @throws StorageException If there is an error offloading the data to the disk.
	 */
	private void offloadMemoryToDisk(
		@Nonnull String operation,
		@Nonnull OffHeapMemoryOutputStream offHeapMemoryOutputStream
	) {
		try (
			offHeapMemoryOutputStream;
			final FileOutputStream fos = new FileOutputStream(getTargetFile(targetFile));
			final FileChannel fileChannel = fos.getChannel()
		) {
			if (lastConsistentWrittenPosition > 0L) {
				// copy all written data to the file and close the off-heap memory output stream
				offHeapMemoryOutputStream.dumpToChannel(fileChannel);
				// now we need to rewind the file to the last consistent written position
				fileChannel.truncate(lastConsistentWrittenPosition);
			}
		} catch (IOException e) {
			throw new StorageException("Failed to offload data to the disk when executing " + operation + "!", e);
		}

		// switch output streams
		this.offHeapMemoryOutput = null;
		this.fileOutput = observableOutputKeeper.getObservableOutputOrCreate(targetFile, OUTPUT_FACTORY);
	}

	/**
	 * Retrieves the observable output to write the data to.
	 *
	 * @return The observable output to write the data to.
	 */
	@Nonnull
	private ObservableOutput<?> getObservableOutput() {
		if (offHeapMemoryOutput != null) {
			return offHeapMemoryOutput;
		} else if (fileOutput != null) {
			return fileOutput;
		} else {
			return createInitialOutput();
		}
	}

	/**
	 * Creates the initial output for writing data. It tries to acquire an off-heap memory region first, and only if
	 * no free memory region is available, it creates a slower temporary file output.
	 *
	 * @return The observable output for writing data.
	 */
	@Nonnull
	private ObservableOutput<? extends OutputStream> createInitialOutput() {
		final Optional<OffHeapMemoryOutputStream> offHeapRegion = offHeapMemoryManager.acquireRegionOutputStream();
		if (offHeapRegion.isEmpty()) {
			Assert.isPremiseValid(getTargetFile(targetFile).exists(), "Target file does not exist!");
			fileOutput = observableOutputKeeper.getObservableOutputOrCreate(targetFile, OUTPUT_FACTORY);
			return fileOutput;
		} else {
			final StorageOptions options = observableOutputKeeper.getOptions();
			offHeapMemoryOutput = new ObservableOutput<>(
				offHeapRegion.get(),
				options.outputBufferSize(),
				0
			);
			if (options.computeCRC32C()) {
				offHeapMemoryOutput.computeCRC32();
			}
			return offHeapMemoryOutput;
		}
	}

	/**
	 * This implementation provides read-only access to data stored in off-heap memory or a file backup.
	 * It automatically adapts to the situation when the corresponding {@link #writeOnlyOffHeapWithFileBackupHandle}
	 * switches from off-heap memory to a file backup.
	 */
	private static class OffHeapWithFileBackupReadOnlyHandle implements ReadOnlyHandle {
		/**
		 * The write-only handle that this read-only handle is associated with.
		 */
		private final WriteOnlyOffHeapWithFileBackupHandle writeOnlyOffHeapWithFileBackupHandle;
		/**
		 * The delegate read-only handle that this read-only handle uses to access the data.
		 * It's not final because it may change when the write-only handle switches from off-heap memory to
		 * a file backup.
		 */
		private ReadOnlyHandle delegate;

		private OffHeapWithFileBackupReadOnlyHandle(@Nonnull WriteOnlyOffHeapWithFileBackupHandle writeOnlyOffHeapWithFileBackupHandle) {
			this.writeOnlyOffHeapWithFileBackupHandle = writeOnlyOffHeapWithFileBackupHandle;
			this.delegate = getDelegate();
		}

		@Override
		public <T> T execute(@Nonnull Function<ObservableInput<?>, T> logic) {
			return getDelegate().execute(logic);
		}

		@Override
		public void close() {
			getDelegate().close();
		}

		@Override
		public long getLastWrittenPosition() {
			return getDelegate().getLastWrittenPosition();
		}

		/**
		 * Retrieves the delegate ReadOnlyHandle used for accessing data. It uses cached {@link #delegate} if it's
		 * already set and matches the current state of the {@link #writeOnlyOffHeapWithFileBackupHandle}.
		 *
		 * @return the delegate ReadOnlyHandle
		 * @throws EvitaInternalError if no content has been written using the source write handle
		 */
		@Nonnull
		private ReadOnlyHandle getDelegate() {
			if (this.writeOnlyOffHeapWithFileBackupHandle.offHeapMemoryOutput != null) {
				// initial state - we may be reading the data from the off-heap memory
				if (!(this.delegate instanceof ReadOnlyGenericHandle)) {
					// check that it's really invoked only in the initial state
					Assert.isPremiseValid(this.delegate == null, "Delegate already set!");
					// create a new delegate to off-heap memory
					final ObservableInput<InputStream> observableInput = new ObservableInput<>(
						this.writeOnlyOffHeapWithFileBackupHandle.offHeapMemoryOutput.getOutputStream().getInputStream()
					);
					this.delegate = new ReadOnlyGenericHandle(observableInput, this.writeOnlyOffHeapWithFileBackupHandle.lastConsistentWrittenPosition);
				}
			} else if (this.writeOnlyOffHeapWithFileBackupHandle.fileOutput != null) {
				// check the delegate already reads from the file
				if (!(this.delegate instanceof ReadOnlyFileHandle)) {
					// we don't close the existing off-heap memory delegate here,
					// because it was already close when the implementation switched to file
					// and also because there may be multiple read-only handles created from the same write handle
					this.delegate = new ReadOnlyFileHandle(
						this.writeOnlyOffHeapWithFileBackupHandle.targetFile,
						this.writeOnlyOffHeapWithFileBackupHandle.observableOutputKeeper.getOptions().computeCRC32C()
					);
				}
			} else {
				throw new GenericEvitaInternalError(
					"No content has been written using source write handle!"
				);
			}
			return delegate;
		}

	}

}
