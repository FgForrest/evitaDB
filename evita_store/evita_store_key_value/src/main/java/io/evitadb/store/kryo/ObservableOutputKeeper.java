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

package io.evitadb.store.kryo;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.IOUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * Caches open file streams by target path. Outputs borrow frame buffers from the JVM-wide
 * {@link ObservableOutputBufferPool} while writing, so idle cached outputs retain no serialization buffers.
 * The scheduled cut task closes unleased streams after {@link #CUT_OUTPUTS_AFTER_INACTIVITY_MS};
 * {@link #close(Path)} and {@link #close()} also close cached streams explicitly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
public class ObservableOutputKeeper implements AutoCloseable {
	/**
	 * Time without a lease after which a cached file stream is closed.
	 */
	private static final long CUT_OUTPUTS_AFTER_INACTIVITY_MS = 300_000L; // 5 minutes
	/**
	 * Timeout in seconds for acquiring locks on file handles during output stream operations.
	 * If a lock cannot be acquired within this timeout, an exception is thrown.
	 * Sourced from {@link StorageOptions#lockTimeoutSeconds()}, typically defaults to 5 seconds.
	 */
	private final int lockTimeoutSeconds;
	/**
	 * Timeout in seconds for waiting on processes to release file handles during cleanup operations.
	 * Used when disposing of cached outputs to ensure graceful resource release.
	 * Sourced from {@link StorageOptions#waitOnCloseSeconds()}, typically defaults to 5 seconds.
	 */
	private final int waitOnCloseSeconds;
	/**
	 * Contains reference to the asynchronous task executor that clears the cached file pointers after some
	 * time of inactivity.
	 */
	private final DelayedAsyncTask cutTask;
	/**
	 * Cache containing the cached outputs for target files.
	 * The cached outputs are stored as a ConcurrentHashMap with the target file path as the key and the corresponding
	 * ObservableOutput as the value. This cache is used to store and retrieve ObservableOutputs for target files to
	 * improve performance by avoiding repeated creation and closing of streams.
	 */
	private final ConcurrentHashMap<Path, OpenedOutputToFile> cachedOutputToFiles = CollectionUtils.createConcurrentHashMap(512);
	/**
	 * Method allowing to access {@link StorageOptions} internal settings so that we can pass only {@link ObservableOutputKeeper}
	 * in the {@link WriteOnlyFileHandle} class.
	 */
	public int getLockTimeoutSeconds() {
		return this.lockTimeoutSeconds;
	}

	/**
	 * This method is meant to be used in tests only to create default instance of {@link ObservableOutputKeeper}.
	 *
	 * @param scheduler the scheduler to use for delayed tasks
	 * @return a keeper with the default test lock and close timeouts
	 */
	@Nonnull
	public static ObservableOutputKeeper _internalBuild(@Nonnull Scheduler scheduler) {
		return new ObservableOutputKeeper(5, 5, scheduler);
	}

	/**
	 * Creates a cache of file streams with bounded lock and close waits.
	 *
	 * @param lockTimeoutSeconds maximum time in seconds to wait for a file handle lock
	 * @param waitOnCloseSeconds maximum time in seconds to wait for leased outputs during close
	 * @param scheduler scheduler that closes inactive, unleased file streams
	 */
	public ObservableOutputKeeper(
		int lockTimeoutSeconds,
		int waitOnCloseSeconds,
		@Nonnull Scheduler scheduler
	) {
		this.lockTimeoutSeconds = lockTimeoutSeconds;
		this.waitOnCloseSeconds = waitOnCloseSeconds;
		this.cutTask = new DelayedAsyncTask(
			null, "Idle file stream closer",
			scheduler,
			this::cutOutputCache,
			CUT_OUTPUTS_AFTER_INACTIVITY_MS, TimeUnit.MILLISECONDS
		);
	}

	/**
	 * Creates a catalog's cache of file streams with bounded lock and close waits.
	 *
	 * @param catalogName catalog associated with the scheduled cleanup task
	 * @param lockTimeoutSeconds maximum time in seconds to wait for a file handle lock
	 * @param waitOnCloseSeconds maximum time in seconds to wait for leased outputs during close
	 * @param scheduler scheduler that closes inactive, unleased file streams
	 */
	public ObservableOutputKeeper(
		@Nonnull String catalogName,
		int lockTimeoutSeconds,
		int waitOnCloseSeconds,
		@Nonnull Scheduler scheduler
	) {
		this.lockTimeoutSeconds = lockTimeoutSeconds;
		this.waitOnCloseSeconds = waitOnCloseSeconds;
		this.cutTask = new DelayedAsyncTask(
			catalogName, "Idle file stream closer",
			scheduler,
			this::cutOutputCache,
			CUT_OUTPUTS_AFTER_INACTIVITY_MS, TimeUnit.MILLISECONDS
		);
	}

	/**
	 * Executes passed lambda with leased {@link ObservableOutput} for the target file and releases it safely when
	 * the lambda finishes. An output whose failed write left its append position unknown is closed on release, so
	 * the next lease reopens the file at its physical length.
	 *
	 * @param targetFile file whose output is leased
	 * @param createFct factory opening a fresh output at the file's physical length
	 * @param lambda serialization operation performed with the exclusively leased output
	 * @param <T> operation result type
	 * @return result of the serialization operation
	 */
	public <T> T executeWithOutput(
		@Nonnull Path targetFile,
		@Nonnull Function<Path, ObservableOutput<FileOutputStream>> createFct,
		@Nonnull Function<ObservableOutput<FileOutputStream>, T> lambda
	) {
		final OpenedOutputToFile openedOutputToFile = getOpenedOutputToFile(targetFile, createFct);
		try {
			return lambda.apply(
				openedOutputToFile.output
			);
		} finally {
			releaseOutput(targetFile, openedOutputToFile);
		}
	}

	/**
	 * Executes passed lambda with leased {@link ObservableOutput} for the target file and releases it safely when
	 * the lambda finishes. An output whose failed write left its append position unknown is closed on release, so
	 * the next lease reopens the file at its physical length.
	 *
	 * @param targetFile file whose output is leased
	 * @param createFct factory opening a fresh output at the file's physical length
	 * @param lambda serialization operation performed with the exclusively leased output
	 */
	public void executeWithOutput(
		@Nonnull Path targetFile,
		@Nonnull Function<Path, ObservableOutput<FileOutputStream>> createFct,
		@Nonnull Consumer<ObservableOutput<FileOutputStream>> lambda
	) {
		final OpenedOutputToFile openedOutputToFile = getOpenedOutputToFile(targetFile, createFct);
		try {
			lambda.accept(
				openedOutputToFile.output
			);
		} finally {
			releaseOutput(targetFile, openedOutputToFile);
		}
	}

	/**
	 * Returns an exclusively leased cached or newly opened output. A stale entry closed during lookup is retried.
	 * The owner retains this lease until explicitly closing the path with {@link #close(Path)}.
	 *
	 * @param targetFile file whose output is leased
	 * @param createFct factory opening a fresh output at the file's physical length
	 * @return the exclusively leased output
	 */
	@Nonnull
	public ObservableOutput<FileOutputStream> getObservableOutputOrCreate(
		@Nonnull Path targetFile,
		@Nonnull Function<Path, ObservableOutput<FileOutputStream>> createFct
	) {
		return getOpenedOutputToFile(targetFile, createFct).output;
	}

	/**
	 * Method drops {@link ObservableOutput} for the target file (closes stream and releases reference).
	 */
	public void close(@Nonnull Path targetFile) {
		ofNullable(this.cachedOutputToFiles.remove(targetFile))
			.ifPresent(OpenedOutputToFile::close);
	}

	/**
	 * Stops scheduled cuts and closes cached outputs only after their leases end. Lease acquisition and closing
	 * share the entry monitor, so a writer's frame buffer cannot be returned to the pool by this thread.
	 *
	 * @throws RuntimeException if closing an idle output fails
	 */
	@Override
	public void close() {
		final long start = System.currentTimeMillis();
		try {
			IOUtils.closeQuietly(this.cutTask::close);
			int attempts = 0;
			do {
				for (Entry<Path, OpenedOutputToFile> entry : this.cachedOutputToFiles.entrySet()) {
					final OpenedOutputToFile outputToFile = entry.getValue();
					if (outputToFile.closeIfNotLeased()) {
						this.cachedOutputToFiles.remove(entry.getKey(), outputToFile);
					}
				}
				attempts++;
				Thread.onSpinWait();
				if (attempts % 10 == 0) {
					log.warn(
						"Waiting for {} cached outputs to be released before closing (waited {} seconds so far)",
						this.cachedOutputToFiles.size(),
						(System.currentTimeMillis() - start) / 1000
					);
				}
			} while (
				!this.cachedOutputToFiles.isEmpty() &&
					System.currentTimeMillis() - start < this.waitOnCloseSeconds * 1000L
			);
		} catch (RuntimeException ex) {
			log.error("Failed to close all cached outputs in {} seconds, {} outputs left",
				this.waitOnCloseSeconds,
				this.cachedOutputToFiles.size(),
				ex
			);
			throw ex;
		} finally {
			if (!this.cachedOutputToFiles.isEmpty()) {
				log.error(
					"Failed to close all cached outputs in {} seconds, {} outputs left",
					this.waitOnCloseSeconds,
					this.cachedOutputToFiles.size()
				);
				this.cachedOutputToFiles.clear();
			}
		}
	}

	/**
	 * Finds or creates and atomically leases an output. A cutter can close an entry between map lookup and lease;
	 * in that case remove only that stale entry and retry with a fresh stream.
	 *
	 * @param targetFile file whose output is required
	 * @param createFct factory reopening the file at its physical append position
	 * @return an open, exclusively leased entry
	 */
	@Nonnull
	private OpenedOutputToFile getOpenedOutputToFile(
		@Nonnull Path targetFile,
		@Nonnull Function<Path, ObservableOutput<FileOutputStream>> createFct
	) {
		this.cutTask.schedule();
		while (true) {
			final OpenedOutputToFile entry = this.cachedOutputToFiles.computeIfAbsent(
				targetFile, path -> new OpenedOutputToFile(createFct.apply(path))
			);
			if (entry.leaseOutput()) {
				return entry;
			}
			this.cachedOutputToFiles.remove(targetFile, entry);
		}
	}

	/**
	 * Ends the lease and drops an output that no longer knows where the file ends. A failed write may have left part
	 * of its bytes in the file, and appending at the stale position would misplace every later record. Hold the entry
	 * monitor through release, removal and closure so a writer cannot lease it between those steps.
	 *
	 * @param targetFile path the output is cached under
	 * @param openedOutputToFile the leased output
	 */
	private void releaseOutput(@Nonnull Path targetFile, @Nonnull OpenedOutputToFile openedOutputToFile) {
		synchronized (openedOutputToFile) {
			openedOutputToFile.releaseOutput();
			if (openedOutputToFile.output.isAppendPositionUnknown()) {
				// Remove before closing so even a disposal failure cannot leave the unusable entry cached.
				this.cachedOutputToFiles.remove(targetFile, openedOutputToFile);
				openedOutputToFile.close();
			}
		}
	}

	/**
	 * Check the cached output entries whether they should be cut because of long inactivity or left intact.
	 * Lease checks and closing are atomic with lease acquisition, preserving any writer's borrowed frame array.
	 *
	 * @return delay until the next cut, or -1 when no cached entry remains
	 */
	private long cutOutputCache() {
		final long threshold = System.currentTimeMillis() - CUT_OUTPUTS_AFTER_INACTIVITY_MS;
		long oldestNotCutEntryTouchTime = -1L;
		for (Entry<Path, OpenedOutputToFile> entry : this.cachedOutputToFiles.entrySet()) {
			final OpenedOutputToFile outputToFile = entry.getValue();
			if (outputToFile.getLastReadTime() < threshold && outputToFile.closeIfNotLeased()) {
				this.cachedOutputToFiles.remove(entry.getKey(), outputToFile);
			} else {
				final long lastReadTime = outputToFile.getLastReadTime();
				if (oldestNotCutEntryTouchTime == -1L || lastReadTime < oldestNotCutEntryTouchTime) {
					oldestNotCutEntryTouchTime = lastReadTime;
				}
			}
		}

		// re-plan the scheduled cut to the moment when the next entry should be cut down
		return oldestNotCutEntryTouchTime > -1L ? (oldestNotCutEntryTouchTime - threshold) + 1 : -1L;
	}

	/**
	 * Cached file stream with lease and closure state guarded by its monitor. The cutter reads the volatile
	 * last-touch time without acquiring that monitor, then uses the monitor to decide whether closure is safe.
	 */
	@RequiredArgsConstructor
	private static class OpenedOutputToFile implements Closeable {
		/**
		 * The observable output for the file with an open file stream.
		 */
		@Nonnull private final ObservableOutput<FileOutputStream> output;
		/**
		 * Last lease or release time, visible to the scheduled cutter without taking the entry monitor.
		 */
		@Getter private volatile long lastReadTime = System.currentTimeMillis();
		/**
		 * Whether a writer owns the output and its frame array; guarded by this entry's monitor.
		 */
		private boolean leased;
		/**
		 * Whether the output was disposed; guarded by this entry's monitor.
		 */
		private boolean closed;

		/**
		 * Acquires an exclusive lease while excluding both scheduled and owner-driven closure.
		 *
		 * @return true if leased, false if this stale entry was already closed
		 * @throws io.evitadb.exception.GenericEvitaInternalError if the output already has a lease
		 */
		public synchronized boolean leaseOutput() {
			if (this.closed) {
				return false;
			}
			Assert.isPremiseValid(!this.leased, "The output is already leased");
			this.leased = true;
			this.lastReadTime = System.currentTimeMillis();
			return true;
		}

		/**
		 * Ends the lease while the keeper holds this monitor through any retirement of an unusable output.
		 * Updating the touch time postpones the next inactivity cut for healthy outputs.
		 *
		 * @throws io.evitadb.exception.GenericEvitaInternalError if the entry is closed or has no lease
		 */
		public synchronized void releaseOutput() {
			Assert.isPremiseValid(!this.closed, "The output is already closed");
			Assert.isPremiseValid(this.leased, "The output is not leased");
			this.leased = false;
			this.lastReadTime = System.currentTimeMillis();
		}

		/**
		 * Closes only an idle entry, atomically with lease acquisition and release.
		 *
		 * @return true if closed or already closed, false while a writer owns the output
		 */
		public synchronized boolean closeIfNotLeased() {
			if (this.leased) {
				return false;
			}
			close();
			return true;
		}

		/**
		 * Explicitly closes the owner's output, including an outstanding lease. Repeated closure is harmless when
		 * the cutter and owner both retire the same entry.
		 *
		 * @throws com.esotericsoftware.kryo.KryoException if disposing of the output fails
		 */
		@Override
		public synchronized void close() {
			if (!this.closed) {
				this.closed = true;
				this.leased = false;
				this.output.close();
			}
		}
	}
}
