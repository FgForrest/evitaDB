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
import io.evitadb.store.exception.StorageException;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle.doSync;

/**
 * Write handle protects access to the {@link ObservableOutput} by {@link ReentrantLock} allowing only single
 * client to use the resource in parallel. Waiting may time out after {@link #lockTimeoutSeconds}. Some methods allow
 * to execute premise check to verify whether the parent is still in operating mode, others ensure that the changes
 * are safely persisted on disk when the method finishes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class BootstrapWriteOnlyFileHandle implements WriteOnlyHandle {
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
	 * Single observable output is used for bootstrap records. Only single writer is allowed and observable output
	 * contains pretty small buffers.
	 */
	private final ObservableOutput<FileOutputStream> observableOutput;
	/**
	 * This variable represents a lock used for protecting access to a handle in the {@link BootstrapWriteOnlyFileHandle} class.
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

	public BootstrapWriteOnlyFileHandle(
		@Nonnull Path targetFile,
		@Nonnull StorageOptions storageOptions
	) {
		this(null, null, null, storageOptions, targetFile);
	}

	public BootstrapWriteOnlyFileHandle(
		@Nullable String catalogName,
		@Nullable FileType fileType,
		@Nullable String logicalName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull Path targetFile
	) {
		this.catalogName = catalogName;
		this.fileType = fileType;
		this.logicalName = logicalName;
		this.storageOptions = storageOptions;
		this.lockTimeoutSeconds = this.storageOptions.lockTimeoutSeconds();
		this.targetFile = targetFile;
		Assert.isPremiseValid(!this.storageOptions.compress(), "Compression is not supported for bootstrap file!");
		Assert.isPremiseValid(WriteOnlyFileHandle.getTargetFile(targetFile) != null, "Target file should be created or exception thrown!");
		this.observableOutput = WriteOnlyFileHandle.createObservableOutput(targetFile, this.storageOptions);
	}

	@Override
	public <T> T checkAndExecute(@Nonnull String operation, @Nonnull Runnable premise, @Nonnull Function<ObservableOutput<?>, T> logic) {
		try {
			if (this.handleLock.tryLock(this.lockTimeoutSeconds, TimeUnit.SECONDS)) {
				try {
					premise.run();
					return logic.apply(this.observableOutput);
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
					logic.accept(this.observableOutput);
					doSync(this.observableOutput, this.storageOptions.syncWrites());
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
					final S result = logic.apply(this.observableOutput);
					doSync(this.observableOutput, this.storageOptions.syncWrites());
					return postExecutionLogic.apply(this.observableOutput, result);
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
			this.observableOutput.close();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StorageException("Failed to close file due to interrupt!");
		} finally {
			this.handleLock.unlock();
		}
	}

	@Override
	public String toString() {
		return "bootstrap write handle: " + this.targetFile;
	}

}
