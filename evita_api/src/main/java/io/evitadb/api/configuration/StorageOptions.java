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

package io.evitadb.api.configuration;

import io.evitadb.utils.UUIDUtil;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.util.Optional.ofNullable;

/**
 * Configuration options related to the key-value storage.
 *
 * @param storageDirectory                   Directory on local disk where Evita data files are stored.
 *                                           By default, temporary directory is used - but it is highly recommended setting your own
 *                                           directory if you don't want to lose the data.
 *                                           recommended setting your own directory with dedicated disk space.
 * @param workDirectory                      Directory on local disk where Evita creates temporary infrastructural files with short
 *                                           lifespan - at most the lifespan of a single evitaDB instance. By default, Java temp
 *                                           directory is used, but can be redirected if temp is too small.
 * @param lockTimeoutSeconds                 This timeout represents a time in seconds that is tolerated to wait for
 *                                           lock acquiring. Locks are used to get handle to open file. Set of open
 *                                           handles is limited to `maxOpenedReadHandles` for read operations and
 *                                           single write handle for write operations (only single thread is expected
 *                                           to append to a file).
 * @param waitOnCloseSeconds                 This timeout represents a time that will file offset index wait for processes to release their
 *                                           read handles to file. After this timeout files will be closed by force and processes may
 *                                           experience an exception.
 * @param outputBufferSize                   The output buffer size determines how large a buffer is kept in memory for output
 *                                           purposes. The size of the buffer limits the maximum size of an individual record in the
 *                                           key/value data store.
 * @param maxOpenedReadHandles               Maximum number of simultaneously opened `InputStream` to file offset
 *                                           index file.
 * @param syncWrites                         Determines whether the storage layer forces the operating system to flush
 *                                           the internal buffers to disk at regular "safe points" or not. The default
 *                                           is true, so that data is not lost in the event of a power failure. There
 *                                           are situations where disabling this feature can improve performance and
 *                                           the client can accept the risk of data loss (e.g. when running automated
 *                                           tests, etc.).
 * @param compress                           Specifies whether or not to compress the data. If set to true, all data
 *                                           will be compressed, but only those whose compressed size is less than
 *                                           the original size will be saved in compressed form. The default is false.
 * @param computeCRC32C                      Determines whether CRC32C checksums will be computed for written
 *                                           records and also whether the CRC32C checksum will be checked on record read.
 * @param minimalActiveRecordShare           Minimal share of active records in the file. If the share is lower, the file will
 *                                           be compacted.
 * @param fileSizeCompactionThresholdBytes   Minimal file size threshold for compaction. If the file size is lower,
 *                                           the file will not be compacted even if the share of active records is lower
 *                                           than the minimal share.
 * @param timeTravelEnabled                  When set to true, the data files are not removed immediately after compacting,
 *                                           but are kept on disk as long as there is history available in the WAL log.
 *                                           This allows a snapshot of the database to be taken at any point in
 *                                           the history covered by the WAL log. From the snapshot, the database can be
 *                                           restored to the exact point in time with all the data available at that time.
 * @param minCompactionIntervalMilliseconds  Minimal wall-clock time (in milliseconds) that must elapse since a data file's
 *                                           last compaction before it may be compacted again for being merely below
 *                                           `minimalActiveRecordShare`. Defaults to `60000` (1 minute) - compacting
 *                                           a data file more often than that makes no practical sense (the I/O cost
 *                                           of a full-file rewrite dwarfs any savings). A value of `0` disables the
 *                                           gate entirely, meaning compaction happens as soon as the file is worth
 *                                           compacting (pre-2026.2 behavior). Regardless of this interval, a file is
 *                                           always compacted immediately once its active record share drops below
 *                                           `maxWasteActiveShare`.
 * @param maxWasteActiveShare                Active record share below which compaction is forced immediately,
 *                                           regardless of `minCompactionIntervalMilliseconds`. Defaults to `0.1`
 *                                           (90% waste), so that the 1-minute default interval above actually binds
 *                                           instead of being an inert no-op. Must be lower than
 *                                           `minimalActiveRecordShare` for the interval to have any effect - if set
 *                                           equal to or higher, it is self-defeating (an "emergency" override
 *                                           stricter than the ordinary "worthwhile" threshold makes no sense), so
 *                                           the constructor clamps it to at most `minimalActiveRecordShare` and logs
 *                                           a warning when `minCompactionIntervalMilliseconds` is set (the interval
 *                                           would otherwise silently never bind).
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
public record StorageOptions(
	@Nonnull Path storageDirectory,
	@Nonnull Path workDirectory,
	int lockTimeoutSeconds,
	int waitOnCloseSeconds,
	int outputBufferSize,
	@Nullable Integer maxOpenedReadHandles,
	boolean syncWrites,
	boolean compress,
	boolean computeCRC32C,
	double minimalActiveRecordShare,
	long fileSizeCompactionThresholdBytes,
	boolean timeTravelEnabled,
	long minCompactionIntervalMilliseconds,
	double maxWasteActiveShare
) {

	public static final int DEFAULT_OUTPUT_BUFFER_SIZE = 2_097_152; // 2MB
	public static final Path DEFAULT_DATA_DIRECTORY = Paths.get("").resolve("data");
	public static final Path DEFAULT_WORK_DIRECTORY = Paths.get(System.getProperty("java.io.tmpdir")).resolve("evita");
	public static final int DEFAULT_LOCK_TIMEOUT_SECONDS = 5;
	public static final int DEFAULT_WAIT_ON_CLOSE_SECONDS = 5;
	public static final int DEFAULT_MAX_OPENED_READ_HANDLES = Runtime.getRuntime().availableProcessors() * 20;
	public static final boolean DEFAULT_SYNC_WRITES = true;
	public static final boolean DEFAULT_COMPRESS = false;
	public static final boolean DEFAULT_COMPUTE_CRC = true;
	public static final double DEFAULT_MINIMAL_ACTIVE_RECORD_SHARE = 0.5;
	public static final long DEFAULT_MINIMAL_FILE_SIZE_COMPACTION_THRESHOLD = 104_857_600L; // 100MB
	public static final boolean DEFAULT_TIME_TRAVEL_ENABLED = false;
	// 1 minute - compacting a data file more often than this makes no sense: the I/O cost of a full-file rewrite
	// dwarfs any savings, and doing it in a tight loop can starve the server of disk bandwidth.
	public static final long DEFAULT_MIN_COMPACTION_INTERVAL_MILLISECONDS = 60_000L;
	// 10% active / 90% waste - the emergency override that still binds by default so the interval above isn't inert;
	// matches the I/O sweet spot identified for compaction waste targets (compaction I/O negligible vs append volume).
	public static final double DEFAULT_MAX_WASTE_ACTIVE_SHARE = 0.1;

	/**
	 * Builder method is planned to be used only in tests.
	 */
	@Nonnull
	public static StorageOptions temporary() {
		return new StorageOptions(
			Path.of(System.getProperty("java.io.tmpdir"), "evita/data"),
			Path.of(System.getProperty("java.io.tmpdir"), "evita/work"),
			5, 5, DEFAULT_OUTPUT_BUFFER_SIZE,
			Runtime.getRuntime().availableProcessors(),
			false,
			false,
			true,
			DEFAULT_MINIMAL_ACTIVE_RECORD_SHARE,
			DEFAULT_MINIMAL_FILE_SIZE_COMPACTION_THRESHOLD,
			DEFAULT_TIME_TRAVEL_ENABLED
		);
	}

	/**
	 * Builder for the storage options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static StorageOptions.Builder builder() {
		return new StorageOptions.Builder();
	}

	/**
	 * Builder for the storage options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static StorageOptions.Builder builder(@Nonnull StorageOptions storageOptions) {
		return new StorageOptions.Builder(storageOptions);
	}

	public StorageOptions() {
		this(
			DEFAULT_DATA_DIRECTORY,
			randomize(DEFAULT_WORK_DIRECTORY),
			DEFAULT_LOCK_TIMEOUT_SECONDS,
			DEFAULT_WAIT_ON_CLOSE_SECONDS,
			DEFAULT_OUTPUT_BUFFER_SIZE,
			DEFAULT_MAX_OPENED_READ_HANDLES,
			DEFAULT_SYNC_WRITES,
			DEFAULT_COMPRESS,
			DEFAULT_COMPUTE_CRC,
			DEFAULT_MINIMAL_ACTIVE_RECORD_SHARE,
			DEFAULT_MINIMAL_FILE_SIZE_COMPACTION_THRESHOLD,
			DEFAULT_TIME_TRAVEL_ENABLED
		);
	}

	/**
	 * Appends a randomly generated UUID to the specified directory path.
	 * This is useful for creating unique subdirectories or file paths within the given directory.
	 *
	 * @param directory the base directory to which a random UUID will be appended. Must not be null.
	 * @return a new {@link Path} object representing the specified directory with a random UUID appended.
	 */
	@Nonnull
	private static Path randomize(@Nonnull Path directory) {
		return directory.resolve(UUIDUtil.randomUUID().toString());
	}

	/**
	 * Constructor with nullable parameters for optional fields.
	 *
	 * @param storageDirectory               the storage directory path
	 * @param workDirectory                  the work directory path
	 * @param lockTimeoutSeconds             timeout for lock acquisition
	 * @param waitOnCloseSeconds             timeout for waiting on close
	 * @param outputBufferSize               size of output buffer
	 * @param maxOpenedReadHandles           maximum number of read handles
	 * @param syncWrites                     whether to sync writes
	 * @param compress                       whether to compress data
	 * @param computeCRC32C                  whether to compute CRC32C checksums
	 * @param minimalActiveRecordShare       minimal share of active records
	 * @param fileSizeCompactionThresholdBytes file size threshold for compaction
	 * @param timeTravelEnabled              whether time travel is enabled
	 * @param minCompactionIntervalMilliseconds   minimal wall-clock time between two compactions of the same file
	 * @param maxWasteActiveShare            active record share below which compaction is forced immediately
	 */
	public StorageOptions(
		@Nullable Path storageDirectory,
		@Nullable Path workDirectory,
		int lockTimeoutSeconds,
		int waitOnCloseSeconds,
		int outputBufferSize,
		@Nullable Integer maxOpenedReadHandles,
		boolean syncWrites,
		boolean compress,
		boolean computeCRC32C,
		double minimalActiveRecordShare,
		long fileSizeCompactionThresholdBytes,
		boolean timeTravelEnabled,
		long minCompactionIntervalMilliseconds,
		double maxWasteActiveShare
	) {
		this.storageDirectory = ofNullable(storageDirectory).orElse(DEFAULT_DATA_DIRECTORY);
		this.workDirectory = ofNullable(workDirectory).orElseGet(() -> randomize(DEFAULT_WORK_DIRECTORY));
		this.lockTimeoutSeconds = lockTimeoutSeconds;
		this.waitOnCloseSeconds = waitOnCloseSeconds;
		this.outputBufferSize = outputBufferSize;
		this.maxOpenedReadHandles = ofNullable(maxOpenedReadHandles).orElse(DEFAULT_MAX_OPENED_READ_HANDLES);
		this.syncWrites = syncWrites;
		this.compress = compress;
		this.computeCRC32C = computeCRC32C;
		this.minimalActiveRecordShare = minimalActiveRecordShare;
		this.fileSizeCompactionThresholdBytes = fileSizeCompactionThresholdBytes;
		this.timeTravelEnabled = timeTravelEnabled;
		this.minCompactionIntervalMilliseconds = minCompactionIntervalMilliseconds;
		// maxWasteActiveShare above minimalActiveRecordShare is semantically degenerate (the override is meant to be
		// a *stricter* emergency threshold) and would otherwise make compaction fire MORE eagerly than
		// minimalActiveRecordShare alone once minCompactionIntervalMilliseconds is 0 - clamp it so every construction
		// path (builder, YAML, previous-arity delegating constructor) preserves the old `active < A` behavior by default
		if (maxWasteActiveShare > minimalActiveRecordShare && minCompactionIntervalMilliseconds > 0) {
			log.warn(
				"maxWasteActiveShare ({}) is higher than minimalActiveRecordShare ({}) while " +
					"minCompactionIntervalMilliseconds ({}) is set - clamping maxWasteActiveShare down to " +
					"minimalActiveRecordShare, which means minCompactionIntervalMilliseconds will never bind " +
					"(compaction will behave as if it were disabled). Set maxWasteActiveShare lower than " +
					"minimalActiveRecordShare for the interval to have any effect.",
				maxWasteActiveShare, minimalActiveRecordShare, minCompactionIntervalMilliseconds
			);
		}
		this.maxWasteActiveShare = Math.min(maxWasteActiveShare, minimalActiveRecordShare);
	}

	/**
	 * Previous-arity constructor kept for binary/source compatibility with callers compiled against the
	 * pre-{@code minCompactionIntervalMilliseconds}/{@code maxWasteActiveShare} signature. Delegates to the canonical
	 * constructor with defaults that reproduce the original compaction trigger exactly.
	 *
	 * @param storageDirectory               the storage directory path
	 * @param workDirectory                  the work directory path
	 * @param lockTimeoutSeconds             timeout for lock acquisition
	 * @param waitOnCloseSeconds             timeout for waiting on close
	 * @param outputBufferSize               size of output buffer
	 * @param maxOpenedReadHandles           maximum number of read handles
	 * @param syncWrites                     whether to sync writes
	 * @param compress                       whether to compress data
	 * @param computeCRC32C                  whether to compute CRC32C checksums
	 * @param minimalActiveRecordShare       minimal share of active records
	 * @param fileSizeCompactionThresholdBytes file size threshold for compaction
	 * @param timeTravelEnabled              whether time travel is enabled
	 */
	public StorageOptions(
		@Nullable Path storageDirectory,
		@Nullable Path workDirectory,
		int lockTimeoutSeconds,
		int waitOnCloseSeconds,
		int outputBufferSize,
		@Nullable Integer maxOpenedReadHandles,
		boolean syncWrites,
		boolean compress,
		boolean computeCRC32C,
		double minimalActiveRecordShare,
		long fileSizeCompactionThresholdBytes,
		boolean timeTravelEnabled
	) {
		this(
			storageDirectory, workDirectory, lockTimeoutSeconds, waitOnCloseSeconds, outputBufferSize,
			maxOpenedReadHandles, syncWrites, compress, computeCRC32C, minimalActiveRecordShare,
			fileSizeCompactionThresholdBytes, timeTravelEnabled,
			DEFAULT_MIN_COMPACTION_INTERVAL_MILLISECONDS, DEFAULT_MAX_WASTE_ACTIVE_SHARE
		);
	}

	/**
	 * Returns the maximum number of opened read handles if it is explicitly specified,
	 * or the default value if it is not set.
	 *
	 * @return the maximum number of opened read handles or the default value.
	 */
	public int maxOpenedReadHandlesOrDefault() {
		return this.maxOpenedReadHandles != null ? this.maxOpenedReadHandles : DEFAULT_MAX_OPENED_READ_HANDLES;
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	@Slf4j
	public static class Builder {

		private Path storageDirectory = DEFAULT_DATA_DIRECTORY;
		private Path workDirectory = randomize(DEFAULT_WORK_DIRECTORY);
		private int lockTimeoutSeconds = DEFAULT_LOCK_TIMEOUT_SECONDS;
		private int waitOnCloseSeconds = DEFAULT_WAIT_ON_CLOSE_SECONDS;
		private int outputBufferSize = DEFAULT_OUTPUT_BUFFER_SIZE;
		private int maxOpenedReadHandles = DEFAULT_MAX_OPENED_READ_HANDLES;
		private boolean syncWrites = DEFAULT_SYNC_WRITES;
		private boolean compression = DEFAULT_COMPRESS;
		private boolean computeCRC32C = DEFAULT_COMPUTE_CRC;
		private double minimalActiveRecordShare = DEFAULT_MINIMAL_ACTIVE_RECORD_SHARE;
		private long fileSizeCompactionThresholdBytes = DEFAULT_MINIMAL_FILE_SIZE_COMPACTION_THRESHOLD;
		private boolean timeTravelEnabled = DEFAULT_TIME_TRAVEL_ENABLED;
		private long minCompactionIntervalMilliseconds = DEFAULT_MIN_COMPACTION_INTERVAL_MILLISECONDS;
		private double maxWasteActiveShare = DEFAULT_MAX_WASTE_ACTIVE_SHARE;

		Builder() {
		}

		Builder(@Nonnull StorageOptions storageOptions) {
			this.storageDirectory = storageOptions.storageDirectory;
			this.workDirectory = storageOptions.workDirectory;
			this.lockTimeoutSeconds = storageOptions.lockTimeoutSeconds;
			this.waitOnCloseSeconds = storageOptions.waitOnCloseSeconds;
			this.outputBufferSize = storageOptions.outputBufferSize;
			this.maxOpenedReadHandles = ofNullable(storageOptions.maxOpenedReadHandles).orElse(DEFAULT_MAX_OPENED_READ_HANDLES);
			this.syncWrites = storageOptions.syncWrites;
			this.compression = storageOptions.compress;
			this.computeCRC32C = storageOptions.computeCRC32C;
			this.minimalActiveRecordShare = storageOptions.minimalActiveRecordShare;
			this.fileSizeCompactionThresholdBytes = storageOptions.fileSizeCompactionThresholdBytes;
			this.timeTravelEnabled = storageOptions.timeTravelEnabled;
			this.minCompactionIntervalMilliseconds = storageOptions.minCompactionIntervalMilliseconds;
			this.maxWasteActiveShare = storageOptions.maxWasteActiveShare;
		}

		@Nonnull
		public Builder storageDirectory(@Nonnull Path storageDirectory) {
			//noinspection ConstantValue
			this.storageDirectory = storageDirectory == null ? DEFAULT_DATA_DIRECTORY : storageDirectory;
			return this;
		}

		@Nonnull
		public Builder workDirectory(@Nonnull Path workDirectory) {
			//noinspection ConstantValue
			this.workDirectory = workDirectory == null ? randomize(DEFAULT_WORK_DIRECTORY) : workDirectory;
			return this;
		}

		@Nonnull
		public Builder lockTimeoutSeconds(int lockTimeoutSeconds) {
			this.lockTimeoutSeconds = lockTimeoutSeconds;
			return this;
		}

		@Nonnull
		public Builder waitOnCloseSeconds(int waitOnCloseSeconds) {
			this.waitOnCloseSeconds = waitOnCloseSeconds;
			return this;
		}

		@Nonnull
		public Builder outputBufferSize(int outputBufferSize) {
			this.outputBufferSize = outputBufferSize;
			return this;
		}

		@Nonnull
		public Builder maxOpenedReadHandles(int maxOpenedReadHandles) {
			this.maxOpenedReadHandles = maxOpenedReadHandles;
			return this;
		}

		@Nonnull
		public Builder syncWrites(boolean syncWrites) {
			this.syncWrites = syncWrites;
			return this;
		}

		@Nonnull
		public Builder compress(boolean compress) {
			this.compression = compress;
			return this;
		}

		@Nonnull
		public Builder computeCRC32(boolean computeCRC32) {
			this.computeCRC32C = computeCRC32;
			return this;
		}

		@Nonnull
		public Builder minimalActiveRecordShare(double minimalActiveRecordShare) {
			this.minimalActiveRecordShare = minimalActiveRecordShare;
			return this;
		}

		@Nonnull
		public Builder fileSizeCompactionThresholdBytes(long fileSizeCompactionThresholdBytes) {
			this.fileSizeCompactionThresholdBytes = fileSizeCompactionThresholdBytes;
			return this;
		}

		@Nonnull
		public Builder timeTravelEnabled(boolean timeTravelEnabled) {
			this.timeTravelEnabled = timeTravelEnabled;
			return this;
		}

		@Nonnull
		public Builder minCompactionIntervalMilliseconds(long minCompactionIntervalMilliseconds) {
			this.minCompactionIntervalMilliseconds = minCompactionIntervalMilliseconds;
			return this;
		}

		@Nonnull
		public Builder maxWasteActiveShare(double maxWasteActiveShare) {
			this.maxWasteActiveShare = maxWasteActiveShare;
			return this;
		}

		@Nonnull
		public StorageOptions build() {
			return new StorageOptions(
				this.storageDirectory,
				this.workDirectory,
				this.lockTimeoutSeconds,
				this.waitOnCloseSeconds,
				this.outputBufferSize,
				this.maxOpenedReadHandles,
				this.syncWrites,
				this.compression,
				this.computeCRC32C,
				this.minimalActiveRecordShare,
				this.fileSizeCompactionThresholdBytes,
				this.timeTravelEnabled,
				this.minCompactionIntervalMilliseconds,
				this.maxWasteActiveShare
			);
		}

	}

}
