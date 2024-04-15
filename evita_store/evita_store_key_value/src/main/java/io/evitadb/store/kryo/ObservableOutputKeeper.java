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

package io.evitadb.store.kryo;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.scheduling.DelayedAsyncTask;
import io.evitadb.scheduling.Scheduler;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
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
		return options.lockTimeoutSeconds();
	}

	public ObservableOutputKeeper(@Nonnull StorageOptions options, @Nonnull Scheduler scheduler) {
		this.options = options;
		this.cutTask = new DelayedAsyncTask(
			scheduler, this::cutOutputCache,
			CUT_OUTPUTS_AFTER_INACTIVITY_MS, ChronoUnit.MILLIS
		);
	}

	/**
	 * Executes passed lambda with leased {@link ObservableOutput} for the target file and releases it safely when
	 * the lambda finishes.
	 */
	public <T> T executeWithOutput(
		@Nonnull Path targetFile,
		@Nonnull BiFunction<Path, StorageOptions, ObservableOutput<FileOutputStream>> createFct,
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
		@Nonnull BiFunction<Path, StorageOptions, ObservableOutput<FileOutputStream>> createFct,
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
		@Nonnull BiFunction<Path, StorageOptions, ObservableOutput<FileOutputStream>> createFct
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
		final Iterator<OpenedOutputToFile> iterator = cachedOutputToFiles.values().iterator();
		do {
			while (iterator.hasNext()) {
				final OpenedOutputToFile outputToFile = iterator.next();
				if (!outputToFile.isLeased()) {
					outputToFile.close();
					iterator.remove();
				}
			}
			Thread.onSpinWait();
		} while (
			!cachedOutputToFiles.isEmpty() &&
				System.currentTimeMillis() - start < options.waitOnCloseSeconds() * 1000L
		);

		if (!cachedOutputToFiles.isEmpty()) {
			log.error(
				"Failed to close all cached outputs in {} seconds, {} outputs left",
				options.waitOnCloseSeconds(),
				cachedOutputToFiles.size()
			);
		}
	}

	/**
	 * Returns existing or creates new {@link ObservableOutput} and cache it for the passed target file.
	 */
	@Nonnull
	private OpenedOutputToFile getOpenedOutputToFile(
		@Nonnull Path targetFile,
		@Nonnull BiFunction<Path, StorageOptions, ObservableOutput<FileOutputStream>> createFct
	) {
		this.cutTask.schedule();
		return cachedOutputToFiles
			.computeIfAbsent(targetFile, path -> new OpenedOutputToFile(createFct.apply(path, options)));
	}

	/**
	 * Check the cached output entries whether they should be cut because of long inactivity or left intact.
	 * This method also re-plans the next cache cut if the cache is not empty.
	 */
	private long cutOutputCache() {
		final long now = System.currentTimeMillis();
		long oldestNotCutEntryTouchTime = -1L;
		final Iterator<OpenedOutputToFile> iterator = cachedOutputToFiles.values().iterator();
		while (iterator.hasNext()) {
			final OpenedOutputToFile outputToFile = iterator.next();
			final boolean oldestRecord = oldestNotCutEntryTouchTime == -1L || outputToFile.getLastReadTime() < oldestNotCutEntryTouchTime;
			if (outputToFile.getLastReadTime() - CUT_OUTPUTS_AFTER_INACTIVITY_MS > now) {
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
		// re-plan the scheduled cut to the moment when the next entry should be cut down
		return oldestNotCutEntryTouchTime > -1L ? (now - oldestNotCutEntryTouchTime) + 1 : -1L;
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
		 * Returns the observable output for the file with opened stream and allocated array.
		 *
		 * @return the observable output for the file with opened stream and allocated array
		 */
		@Nonnull
		public ObservableOutput<FileOutputStream> leaseOutput() {
			Assert.isPremiseValid(!this.leased, "The output is already leased");
			this.leased = true;
			this.lastReadTime = System.currentTimeMillis();
			return output;
		}

		/**
		 * Notifies about the usage of a transaction location object by updating the last read time.
		 */
		public void releaseOutput() {
			Assert.isPremiseValid(this.leased, "The output is not leased");
			this.leased = false;
			this.lastReadTime = System.currentTimeMillis();
		}

		@Override
		public void close() {
			this.leased = false;
			this.output.close();
		}

	}


}
