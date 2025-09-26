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

package io.evitadb.store.kryo;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.storage.ObservableOutputChangeEvent;
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
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * This instance keeps references to the {@link ObservableOutput} instances that internally keep large buffers in
 * {@link ObservableOutput#getBuffer()} to use them for serialization. These buffers are not necessary when there are
 * no updates to the catalog / collection, so it's wise to get rid of them if there is no actual need.
 *
 * The need is determined by the number of opened read write {@link EvitaSessionContract} to the catalog. If there
 * is at least one opened read-write session we need to keep those outputs around. When there are only read sessions we
 * don't need the outputs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@RequiredArgsConstructor
public class ObservableOutputKeeper implements AutoCloseable {
	/**
	 * The time after which the WAL cache is dropped after inactivity.
	 */
	private static final long CUT_OUTPUTS_AFTER_INACTIVITY_MS = 300_000L; // 5 minutes
	/**
	 * The name of the catalog that the outputs are associated with.
	 */
	@Getter private final String catalogName;
	/**
	 * The configuration options for the key-value storage.
	 */
	@Getter private final StorageOptions options;
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
	public long getLockTimeoutSeconds() {
		return this.options.lockTimeoutSeconds();
	}

	public ObservableOutputKeeper(@Nonnull StorageOptions options, @Nonnull Scheduler scheduler) {
		this.catalogName = null;
		this.options = options;
		this.cutTask = new DelayedAsyncTask(
			null, "Write buffer releaser",
			scheduler,
			this::cutOutputCache,
			CUT_OUTPUTS_AFTER_INACTIVITY_MS, TimeUnit.MILLISECONDS
		);
	}

	public ObservableOutputKeeper(@Nonnull String catalogName, @Nonnull StorageOptions options, @Nonnull Scheduler scheduler) {
		this.catalogName = catalogName;
		this.options = options;
		this.cutTask = new DelayedAsyncTask(
			catalogName, "Write buffer releaser",
			scheduler,
			this::cutOutputCache,
			CUT_OUTPUTS_AFTER_INACTIVITY_MS, TimeUnit.MILLISECONDS
		);
	}

	/**
	 * Executes passed lambda with leased {@link ObservableOutput} for the target file and releases it safely when
	 * the lambda finishes.
	 */
	public <T> T executeWithOutput(
		@Nonnull Path targetFile,
		@Nonnull Function<Path, ObservableOutput<FileOutputStream>> createFct,
		@Nonnull Function<ObservableOutput<FileOutputStream>, T> lambda
	) {
		final OpenedOutputToFile openedOutputToFile = getOpenedOutputToFile(targetFile, createFct);
		try {
			return lambda.apply(
				openedOutputToFile.leaseOutput()
			);
		} finally {
			openedOutputToFile.releaseOutput();
		}
	}

	/**
	 * Executes passed lambda with leased {@link ObservableOutput} for the target file and releases it safely when
	 * the lambda finishes.
	 */
	public void executeWithOutput(
		@Nonnull Path targetFile,
		@Nonnull Function<Path, ObservableOutput<FileOutputStream>> createFct,
		@Nonnull Consumer<ObservableOutput<FileOutputStream>> lambda
	) {
		final OpenedOutputToFile openedOutputToFile = getOpenedOutputToFile(targetFile, createFct);
		try {
			lambda.accept(
				openedOutputToFile.leaseOutput()
			);
		} finally {
			openedOutputToFile.releaseOutput();
		}
	}

	/**
	 * Returns existing or creates new {@link ObservableOutput} and cache it for the passed target file.
	 */
	@Nonnull
	public ObservableOutput<FileOutputStream> getObservableOutputOrCreate(
		@Nonnull Path targetFile,
		@Nonnull Function<Path, ObservableOutput<FileOutputStream>> createFct
	) {
		return getOpenedOutputToFile(targetFile, createFct).leaseOutput();
	}

	/**
	 * Method drops {@link ObservableOutput} for the target file (closes stream and releases reference).
	 */
	public void close(@Nonnull Path targetFile) {
		ofNullable(this.cachedOutputToFiles.remove(targetFile))
			.ifPresent(OpenedOutputToFile::close);
	}

	/**
	 * Method drops all cached {@link ObservableOutput} (closes streams and releases references).
	 */
	@Override
	public void close() {
		final long start = System.currentTimeMillis();
		try {
			IOUtils.closeQuietly(this.cutTask::close);
			int attempts = 0;
			do {
				final Iterator<OpenedOutputToFile> iterator = this.cachedOutputToFiles.values().iterator();
				while (iterator.hasNext()) {
					final OpenedOutputToFile outputToFile = iterator.next();
					if (!outputToFile.isLeased()) {
						outputToFile.close();
						iterator.remove();
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
					System.currentTimeMillis() - start < this.options.waitOnCloseSeconds() * 1000L
			);

			// emit event
			if (this.catalogName == null) {
				new ObservableOutputChangeEvent(
					this.cachedOutputToFiles.size(),
					(long) this.cachedOutputToFiles.size() * this.options.outputBufferSize()
				).commit();
			} else {
				new ObservableOutputChangeEvent(
					this.catalogName,
					this.cachedOutputToFiles.size(),
					(long) this.cachedOutputToFiles.size() * this.options.outputBufferSize()
				).commit();
			}
		} catch (RuntimeException ex) {
			log.error("Failed to close all cached outputs in {} seconds, {} outputs left",
				this.options.waitOnCloseSeconds(),
				this.cachedOutputToFiles.size(),
				ex
			);
			throw ex;
		} finally {
			if (!this.cachedOutputToFiles.isEmpty()) {
				log.error(
					"Failed to close all cached outputs in {} seconds, {} outputs left",
					this.options.waitOnCloseSeconds(),
					this.cachedOutputToFiles.size()
				);
				this.cachedOutputToFiles.clear();
			}
		}
	}

	/**
	 * Returns existing or creates new {@link ObservableOutput} and cache it for the passed target file.
	 */
	@Nonnull
	private OpenedOutputToFile getOpenedOutputToFile(
		@Nonnull Path targetFile,
		@Nonnull Function<Path, ObservableOutput<FileOutputStream>> createFct
	) {
		this.cutTask.schedule();
		return this.cachedOutputToFiles
			.computeIfAbsent(targetFile, path -> new OpenedOutputToFile(createFct.apply(path)));
	}

	/**
	 * Check the cached output entries whether they should be cut because of long inactivity or left intact.
	 * This method also re-plans the next cache cut if the cache is not empty.
	 */
	private long cutOutputCache() {
		final long threshold = System.currentTimeMillis() - CUT_OUTPUTS_AFTER_INACTIVITY_MS;
		long oldestNotCutEntryTouchTime = -1L;
		final Iterator<OpenedOutputToFile> iterator = this.cachedOutputToFiles.values().iterator();
		while (iterator.hasNext()) {
			final OpenedOutputToFile outputToFile = iterator.next();
			final boolean oldestRecord = oldestNotCutEntryTouchTime == -1L || outputToFile.getLastReadTime() < oldestNotCutEntryTouchTime;
			if (outputToFile.getLastReadTime() < threshold) {
				if (outputToFile.isLeased()) {
					oldestNotCutEntryTouchTime = outputToFile.getLastReadTime();
				} else {
					outputToFile.close();
					iterator.remove();
				}
			} else if (oldestRecord) {
				oldestNotCutEntryTouchTime = outputToFile.getLastReadTime();
			}
		}

		// emit event
		if (this.catalogName == null) {
			new ObservableOutputChangeEvent(
				this.cachedOutputToFiles.size(),
				(long) this.cachedOutputToFiles.size() * this.options.outputBufferSize()
			).commit();
		} else {
			new ObservableOutputChangeEvent(
				this.catalogName,
				this.cachedOutputToFiles.size(),
				(long) this.cachedOutputToFiles.size() * this.options.outputBufferSize()
			).commit();
		}

		// re-plan the scheduled cut to the moment when the next entry should be cut down
		return oldestNotCutEntryTouchTime > -1L ? (oldestNotCutEntryTouchTime - threshold) + 1 : -1L;
	}

	@RequiredArgsConstructor
	private static class OpenedOutputToFile implements Closeable {
		/**
		 * The observable output for the file with opened stream and allocated array.
		 */
		@Nonnull private final ObservableOutput<FileOutputStream> output;
		/**
		 * The time of the last read of the transaction locations.
		 */
		@Getter private long lastReadTime = System.currentTimeMillis();
		/**
		 * The flag indicating whether the output is leased. The output must not be leased more than once.
		 */
		@Getter private boolean leased;
		/**
		 * The flag indicating whether the output is closed. The output must not be closed more than once.
		 */
		@Getter private boolean closed;

		/**
		 * Returns the observable output for the file with opened stream and allocated array.
		 *
		 * @return the observable output for the file with opened stream and allocated array
		 */
		@Nonnull
		public ObservableOutput<FileOutputStream> leaseOutput() {
			Assert.isPremiseValid(!this.closed, "The output is already closed");
			Assert.isPremiseValid(!this.leased, "The output is already leased");
			this.leased = true;
			this.lastReadTime = System.currentTimeMillis();
			return this.output;
		}

		/**
		 * Notifies about the usage of a transaction location object by updating the last read time.
		 */
		public void releaseOutput() {
			Assert.isPremiseValid(!this.closed, "The output is already closed");
			Assert.isPremiseValid(this.leased, "The output is not leased");
			this.leased = false;
			this.lastReadTime = System.currentTimeMillis();
		}

		@Override
		public void close() {
			Assert.isPremiseValid(!this.closed, "The output is already closed");
			this.closed = true;
			this.leased = false;
			this.output.close();
		}

	}


}
