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
import io.evitadb.api.exception.TransactionException;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.exception.StorageException;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.InputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.function.Functions.noOpFunction;
import static io.evitadb.function.Functions.noOpRunnable;

/**
 * This implementation of {@link WriteOnlyFileHandle} tries to persist data only to the region of off-heap memory
 * managed by {@link OffHeapMemoryManager}. If there is no free region available or the written size exceeds the size
 * of the region, the {@link TransactionException} exception is thrown.
 * This class is not thread-safe and contains no locking.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class WriteOnlyOffHeapHandle implements WriteOnlyHandle {
	/**
	 * The variable `observableOutputKeeper` is an instance of the class `ObservableOutputKeeper`. It is used to keep
	 * references to `ObservableOutput` instances that internally maintain large buffers for serialization. The need for
	 * these buffers is determined by the number of open read-write sessions to a catalog. If there is at least one open
	 * read-write session, the `ObservableOutput` instances need to be kept. Otherwise, if there are only read sessions,
	 * the `ObservableOutput` instances can be disposed of.
	 */
	private final ObservableOutputKeeper observableOutputKeeper;
	/**
	 * CatalogOffHeapMemoryManager class is responsible for managing off-heap memory regions and providing
	 * free regions to acquire OutputStreams for writing data.
	 */
	private final OffHeapMemoryManager offHeapMemoryManager;
	/**
	 * OutputStream that is used to write data to the off-heap memory.
	 */
	@Nullable private ObservableOutput<OffHeapMemoryOutputStream> offHeapMemoryOutput;
	/**
	 * Reference to the {@link StorageOptions} object that contains configuration options for the storage system.
	 */
	private final StorageOptions storageOptions;
	/**
	 * Contains the information about the last end byte of fully written record.
	 */
	private int lastConsistentWrittenPosition = 0;

	public WriteOnlyOffHeapHandle(
		@Nonnull StorageOptions storageOptions,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull OffHeapMemoryManager offHeapMemoryManager
	) {
		this.storageOptions = storageOptions;
		this.offHeapMemoryManager = offHeapMemoryManager;
		this.observableOutputKeeper = observableOutputKeeper;
		this.offHeapMemoryOutput = null;
	}

	@Override
	public <T> T checkAndExecute(
		@Nonnull String operation,
		@Nonnull Runnable premise,
		@Nonnull Function<ObservableOutput<?>, T> logic
	) {
		return execute(premise, logic);
	}

	@Override
	public void checkAndExecuteAndSync(
		@Nonnull String operation,
		@Nonnull Runnable premise,
		@Nonnull Consumer<ObservableOutput<?>> logic
	) {
		execute(
			premise,
			(o) -> {
				logic.accept(o);
				return null;
			}
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
			premise,
			(o) -> postExecutionLogic.apply(o, logic.apply(o))
		);
	}

	@Override
	public long getLastWrittenPosition() {
		Assert.isPremiseValid(
			this.offHeapMemoryOutput != null,
			"WriteHandle is already closed!"
		);
		return this.offHeapMemoryOutput.getOutputStream().getPeakDataWrittenLength();
	}

	@Nonnull
	@Override
	public ReadOnlyHandle toReadOnlyHandle() {
		// sync data first
		execute(noOpRunnable(), noOpFunction());

		return new OffHeapReadOnlyHandle(this);
	}

	/**
	 * This method may not be called if the handle is terminated by conversion to {@link #toReadOnlyHandle()}. In such
	 * scenario the resources are cleared when the {@link OffHeapWithFileBackupReference} is closed. It's because
	 * the reference has longer lifespan than the {@link WriteOnlyOffHeapHandle} and still needs
	 * resources for reading purposes.
	 */
	@Override
	public void close() {
		releaseOffHeapMemory();
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
	public OffHeapWithFileBackupReference toReadOffHeapReference() {
		Assert.isPremiseValid(
			this.offHeapMemoryOutput != null,
			"WriteHandle is already closed!"
		);

		// sync to disk first
		execute(noOpRunnable(), noOpFunction());

		final ByteBuffer byteBuffer = this.offHeapMemoryOutput.getOutputStream().getByteBuffer();
		byteBuffer.limit(this.lastConsistentWrittenPosition);
		return OffHeapWithFileBackupReference.withByteBuffer(
			byteBuffer,
			this.lastConsistentWrittenPosition,
			this::releaseOffHeapMemory
		);
	}

	/**
	 * Allocates off-heap memory for writing data.
	 *
	 * This method ensures that off-heap memory is allocated only once by checking the premise
	 * that the `offHeapMemoryOutput` field is null. If the `offHeapMemoryOutput` has already been
	 * allocated, an exception is thrown, indicating that the allocation has already occurred.
	 * Otherwise, it initializes the `offHeapMemoryOutput` field by creating an observable output.
	 *
	 * @throws GenericEvitaInternalError if off-heap memory has already been allocated.
	 */
	public void allocateOffHeapMemory() {
		Assert.isPremiseValid(
			this.offHeapMemoryOutput == null,
			"WriteHandle is already allocated!"
		);
		this.offHeapMemoryOutput = createObservableOutput();
	}

	@Override
	public String toString() {
		return "write handle (off-heap memory)";
	}

	/**
	 * Releases the off-heap memory allocated for writing data.
	 *
	 * This method closes the off-heap memory output stream and sets it to null.
	 */
	public void releaseOffHeapMemory() {
		if (this.offHeapMemoryOutput != null) {
			this.offHeapMemoryOutput.close();
		}
		this.offHeapMemoryOutput = null;
	}

	/**
	 * Executes a given operation with the provided parameters.
	 *
	 * @param premise   The runnable code that must be executed before the main logic.
	 * @param logic     The function that contains the main logic of the operation.
	 *                  Takes an ObservableOutput object as input and returns the result of the operation.
	 * @param <T>       The type of the result returned by the logic function.
	 * @return The result of the execution of the logic function.
	 * @throws StorageException If the execution is interrupted or times out.
	 */
	private <T> T execute(
		@Nonnull final Runnable premise,
		@Nonnull final Function<ObservableOutput<?>, T> logic
	) {
		// first run premise
		premise.run();

		// if it passes execute the write logic
		final ObservableOutput<?> observableOutput = getObservableOutput();
		try {
			return executeLogic(logic, observableOutput);
		} catch (BufferOverflowException ex) {
			throw new TransactionException(
				"Failed to acquire off-heap memory region for writing data! " +
					"Please increase the size of the off-heap memory or decrease the size of the data being written.",
				ex
			);
		}
	}

	/**
	 * Executes the given logic function with the provided parameters.
	 *
	 * @param logic  The logic function to execute. Takes an ObservableOutput object as input and returns the result of the operation.
	 * @param output The observable output object to use as input for the logic function.
	 * @param <T>    The type of the result returned by the logic function.
	 * @return The result of the execution of the logic function.
	 */
	private <T> T executeLogic(
		@Nonnull Function<ObservableOutput<?>, T> logic,
		@Nonnull ObservableOutput<?> output
	) {
		final T result = logic.apply(output);
		// update the last consistent written position
		this.lastConsistentWrittenPosition = Math.toIntExact(output.getWrittenBytesSinceReset());
		return result;
	}

	/**
	 * Retrieves the observable output to write the data to.
	 *
	 * @return The observable output to write the data to.
	 */
	@Nonnull
	private ObservableOutput<?> getObservableOutput() {
		Assert.isPremiseValid(
			this.offHeapMemoryOutput != null,
			"WriteHandle is already closed!"
		);

		return this.offHeapMemoryOutput;
	}

	/**
	 * Creates the initial output for writing data. It tries to acquire an off-heap memory region first, and only if
	 * no free memory region is available, it creates a slower temporary file output.
	 *
	 * @return The observable output for writing data.
	 */
	@Nonnull
	private ObservableOutput<OffHeapMemoryOutputStream> createObservableOutput() {
		final Optional<OffHeapMemoryOutputStream> offHeapRegion = this.offHeapMemoryManager.acquireRegionOutputStream();
		if (offHeapRegion.isEmpty()) {
			throw new TransactionException(
				"Failed to acquire off-heap memory region for writing data! " +
					"Please increase the size of the off-heap memory or decrease the size of the data being written."
			);
		} else {
			final StorageOptions options = this.observableOutputKeeper.getOptions();
			this.offHeapMemoryOutput = new ObservableOutput<>(
				offHeapRegion.get(),
				options.outputBufferSize(),
				0
			);
			if (options.computeCRC32C()) {
				this.offHeapMemoryOutput.computeCRC32();
			}
			if (options.compress()) {
				this.offHeapMemoryOutput.compress();
			}
			return this.offHeapMemoryOutput;
		}
	}

	/**
	 * This implementation provides read-only access to data stored in off-heap memory or a file backup.
	 * It automatically adapts to the situation when the corresponding {@link #writeOnlyOffHeapHandle}
	 * switches from off-heap memory to a file backup.
	 */
	private static class OffHeapReadOnlyHandle implements ReadOnlyHandle {
		/**
		 * The write-only handle that this read-only handle is associated with.
		 */
		private final WriteOnlyOffHeapHandle writeOnlyOffHeapHandle;
		/**
		 * The delegate read-only handle that this read-only handle uses to access the data.
		 * It's not final because it may change when the write-only handle switches from off-heap memory to
		 * a file backup.
		 */
		private ReadOnlyHandle delegate;

		private OffHeapReadOnlyHandle(@Nonnull WriteOnlyOffHeapHandle writeOnlyOffHeapHandle) {
			this.writeOnlyOffHeapHandle = writeOnlyOffHeapHandle;
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
		 * already set and matches the current state of the {@link #writeOnlyOffHeapHandle}.
		 *
		 * @return the delegate ReadOnlyHandle
		 * @throws EvitaInternalError if no content has been written using the source write handle
		 */
		@Nonnull
		private ReadOnlyHandle getDelegate() {
			final StorageOptions storageOptions = this.writeOnlyOffHeapHandle.storageOptions;
			// initial state - we will be reading the data from the off-heap memory
			if (!(this.delegate instanceof ReadOnlyGenericHandle)) {
				Assert.isPremiseValid(
					this.writeOnlyOffHeapHandle.offHeapMemoryOutput != null,
					"WriteHandle is already closed!"
				);

				// check that it's really invoked only in the initial state
				Assert.isPremiseValid(this.delegate == null, "Delegate already set!");
				// create a new delegate to off-heap memory
				final ObservableInput<InputStream> observableInput = new ObservableInput<>(
					this.writeOnlyOffHeapHandle.offHeapMemoryOutput.getOutputStream().getInputStream()
				);
				if (storageOptions.computeCRC32C()) {
					observableInput.computeCRC32();
				}
				if (storageOptions.compress()) {
					observableInput.compress();
				}
				this.delegate = new ReadOnlyGenericHandle(observableInput, this.writeOnlyOffHeapHandle.lastConsistentWrittenPosition);
			} else {
				throw new GenericEvitaInternalError(
					"No content has been written using the source write handle!"
				);
			}
			return this.delegate;
		}

	}

}
