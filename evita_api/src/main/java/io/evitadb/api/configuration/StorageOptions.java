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

import lombok.Builder;
import lombok.ToString;

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
 * @param exportDirectory                    Directory on local disk where Evita files are exported - for example, backups,
 *                                           JFR recordings, query recordings etc.
 * @param lockTimeoutSeconds                 This timeout represents a time in seconds that is tolerated to wait for lock acquiring.
 *                                           Locks are used to get handle to open file. Set of open handles is limited to
 *                                           {@link #maxOpenedReadHandles} for read operations and single write handle for write
 *                                           operations (only single thread is expected to append to a file).
 * @param waitOnCloseSeconds                 This timeout represents a time that will file offset index wait for processes to release their
 *                                           read handles to file. After this timeout files will be closed by force and processes may
 *                                           experience an exception.
 * @param outputBufferSize                   The output buffer size determines how large a buffer is kept in memory for output
 *                                           purposes. The size of the buffer limits the maximum size of an individual record in the
 *                                           key/value data store.
 * @param maxOpenedReadHandles               Maximum number of simultaneously opened {@link java.io.InputStream} to file offset index file.
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
 * @param exportDirectorySizeLimitBytes      Maximum overall size of the export directory. When this threshold
 *                                           is exceeded the oldest files will automatically be removed until the
 *                                           size drops below the limit.
 * @param exportFileHistoryExpirationSeconds Maximal age of exported file in seconds. When age is exceeded the file
 *                                           will be automatically removed.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Builder
public record StorageOptions(
	@Nonnull Path storageDirectory,
	@Nonnull Path exportDirectory,
	long lockTimeoutSeconds,
	long waitOnCloseSeconds,
	int outputBufferSize,
	@Nullable Integer maxOpenedReadHandles,
	boolean syncWrites,
	boolean compress,
	boolean computeCRC32C,
	double minimalActiveRecordShare,
	long fileSizeCompactionThresholdBytes,
	boolean timeTravelEnabled,
	long exportDirectorySizeLimitBytes,
	long exportFileHistoryExpirationSeconds
) {

	public static final int DEFAULT_OUTPUT_BUFFER_SIZE = 2_097_152; // 2MB
	public static final Path DEFAULT_DATA_DIRECTORY = Paths.get("").resolve("data");
	public static final Path DEFAULT_EXPORT_DIRECTORY = Paths.get("").resolve("export");
	public static final int DEFAULT_LOCK_TIMEOUT_SECONDS = 5;
	public static final int DEFAULT_WAIT_ON_CLOSE_SECONDS = 5;
	public static final int DEFAULT_MAX_OPENED_READ_HANDLES = Runtime.getRuntime().availableProcessors() * 20;
	public static final boolean DEFAULT_SYNC_WRITES = true;
	public static final boolean DEFAULT_COMPRESS = false;
	public static final boolean DEFAULT_COMPUTE_CRC = true;
	public static final double DEFAULT_MINIMAL_ACTIVE_RECORD_SHARE = 0.5;
	public static final long DEFAULT_MINIMAL_FILE_SIZE_COMPACTION_THRESHOLD = 104_857_600L; // 100MB
	public static final boolean DEFAULT_TIME_TRAVEL_ENABLED = false;
	public static final long DEFAULT_EXPORT_DIRECTORY_SIZE_LIMIT_BYTES = 1_073_741_824L; // 1GB
	public static final long DEFAULT_EXPORT_FILE_HISTORY_EXPIRATION_SECONDS = 604_800L; // 7 days

	/**
	 * Builder method is planned to be used only in tests.
	 */
	@Nonnull
	public static StorageOptions temporary() {
		return new StorageOptions(
			Path.of(System.getProperty("java.io.tmpdir"), "evita/data"),
			Path.of(System.getProperty("java.io.tmpdir"), "evita/export"),
			5, 5, DEFAULT_OUTPUT_BUFFER_SIZE,
			Runtime.getRuntime().availableProcessors(),
			false,
			false,
			true,
			DEFAULT_MINIMAL_ACTIVE_RECORD_SHARE,
			DEFAULT_MINIMAL_FILE_SIZE_COMPACTION_THRESHOLD,
			DEFAULT_TIME_TRAVEL_ENABLED,
			DEFAULT_EXPORT_DIRECTORY_SIZE_LIMIT_BYTES,
			DEFAULT_EXPORT_FILE_HISTORY_EXPIRATION_SECONDS
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
			DEFAULT_EXPORT_DIRECTORY,
			DEFAULT_LOCK_TIMEOUT_SECONDS,
			DEFAULT_WAIT_ON_CLOSE_SECONDS,
			DEFAULT_OUTPUT_BUFFER_SIZE,
			DEFAULT_MAX_OPENED_READ_HANDLES,
			DEFAULT_SYNC_WRITES,
			DEFAULT_COMPRESS,
			DEFAULT_COMPUTE_CRC,
			DEFAULT_MINIMAL_ACTIVE_RECORD_SHARE,
			DEFAULT_MINIMAL_FILE_SIZE_COMPACTION_THRESHOLD,
			DEFAULT_TIME_TRAVEL_ENABLED,
			DEFAULT_EXPORT_DIRECTORY_SIZE_LIMIT_BYTES,
			DEFAULT_EXPORT_FILE_HISTORY_EXPIRATION_SECONDS
		);
	}

	public StorageOptions(
		@Nullable Path storageDirectory,
		@Nullable Path exportDirectory,
		long lockTimeoutSeconds,
		long waitOnCloseSeconds,
		int outputBufferSize,
		@Nullable Integer maxOpenedReadHandles,
		boolean syncWrites,
		boolean compress,
		boolean computeCRC32C,
		double minimalActiveRecordShare,
		long fileSizeCompactionThresholdBytes,
		boolean timeTravelEnabled,
		long exportDirectorySizeLimitBytes,
		long exportFileHistoryExpirationSeconds
	) {
		this.storageDirectory = ofNullable(storageDirectory).orElse(DEFAULT_DATA_DIRECTORY);
		this.exportDirectory = ofNullable(exportDirectory).orElse(DEFAULT_EXPORT_DIRECTORY);
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
		this.exportDirectorySizeLimitBytes = exportDirectorySizeLimitBytes;
		this.exportFileHistoryExpirationSeconds = exportFileHistoryExpirationSeconds;
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
	public static class Builder {
		private Path storageDirectory = DEFAULT_DATA_DIRECTORY;
		private Path exportDirectory = DEFAULT_EXPORT_DIRECTORY;
		private long lockTimeoutSeconds = DEFAULT_LOCK_TIMEOUT_SECONDS;
		private long waitOnCloseSeconds = DEFAULT_WAIT_ON_CLOSE_SECONDS;
		private int outputBufferSize = DEFAULT_OUTPUT_BUFFER_SIZE;
		private int maxOpenedReadHandles = DEFAULT_MAX_OPENED_READ_HANDLES;
		private boolean syncWrites = DEFAULT_SYNC_WRITES;
		private boolean compression = DEFAULT_COMPRESS;
		private boolean computeCRC32C = DEFAULT_COMPUTE_CRC;
		private double minimalActiveRecordShare = DEFAULT_MINIMAL_ACTIVE_RECORD_SHARE;
		private long fileSizeCompactionThresholdBytes = DEFAULT_MINIMAL_FILE_SIZE_COMPACTION_THRESHOLD;
		private boolean timeTravelEnabled = DEFAULT_TIME_TRAVEL_ENABLED;
		private long exportDirectorySizeLimitBytes = DEFAULT_EXPORT_DIRECTORY_SIZE_LIMIT_BYTES;
		private long exportFileHistoryExpirationSeconds = DEFAULT_EXPORT_FILE_HISTORY_EXPIRATION_SECONDS;

		Builder() {
		}

		Builder(@Nonnull StorageOptions storageOptions) {
			this.storageDirectory = storageOptions.storageDirectory;
			this.exportDirectory = storageOptions.exportDirectory;
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
			this.exportDirectorySizeLimitBytes = storageOptions.exportDirectorySizeLimitBytes;
			this.exportFileHistoryExpirationSeconds = storageOptions.exportFileHistoryExpirationSeconds;
		}

		@Nonnull
		public Builder storageDirectory(@Nonnull Path storageDirectory) {
			//noinspection ConstantValue
			this.storageDirectory = storageDirectory == null ? DEFAULT_DATA_DIRECTORY : storageDirectory;
			return this;
		}

		@Nonnull
		public Builder exportDirectory(@Nonnull Path exportDirectory) {
			//noinspection ConstantValue
			this.exportDirectory = exportDirectory == null ? DEFAULT_EXPORT_DIRECTORY : exportDirectory;
			return this;
		}

		@Nonnull
		public Builder lockTimeoutSeconds(long lockTimeoutSeconds) {
			this.lockTimeoutSeconds = lockTimeoutSeconds;
			return this;
		}

		@Nonnull
		public Builder waitOnCloseSeconds(long waitOnCloseSeconds) {
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
		public Builder exportDirectorySizeLimitBytes(long exportDirectorySizeLimitBytes) {
			this.exportDirectorySizeLimitBytes = exportDirectorySizeLimitBytes;
			return this;
		}

		@Nonnull
		public Builder exportFileHistoryExpirationSeconds(long exportFileHistoryExpirationSeconds) {
			this.exportFileHistoryExpirationSeconds = exportFileHistoryExpirationSeconds;
			return this;
		}

		@Nonnull
		public StorageOptions build() {
			return new StorageOptions(
				this.storageDirectory,
				this.exportDirectory,
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
				this.exportDirectorySizeLimitBytes,
				this.exportFileHistoryExpirationSeconds
			);
		}

	}

}
