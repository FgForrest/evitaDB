/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import io.evitadb.api.TransactionContract.CommitBehavior;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Configuration options related to transaction.
 *
 * @param transactionWorkDirectory              Directory on local disk where Evita creates temporary folders and files
 *                                              for transactional transaction. By default, temporary directory is used
 *                                              - but it is a good idea to set your own directory to avoid problems
 *                                              with disk space.
 * @param transactionMemoryBufferLimitSizeBytes Number of bytes that are allocated on off-heap memory for transaction
 *                                              memory buffer. This buffer is used to store temporary (isolated)
 *                                              transactional data before they are committed to the database.
 *                                              If the buffer is full, the transaction data are immediately written
 *                                              to the disk and the transaction processing gets slower.
 * @param transactionMemoryRegionCount          Number of slices of the `transactionMemoryBufferLimitSizeBytes` buffer.
 *                                              The more slices the smaller they get and the higher the probability
 *                                              that the buffer will be full and will have to be copied to the disk.
 * @param walFileSizeBytes                      Size of the Write-Ahead Log (WAL) file in bytes before it is rotated.
 * @param walFileCountKept                      Number of WAL files to keep.
 * @param flushFrequencyInMillis                The frequency of flushing the transactional data to the disk when they
 *                                              are sequentially processed. If database process the (small) transaction
 *                                              very quickly, it may decide to process next transaction before flushing
 *                                              changes to the disk. If the client waits for {@link CommitBehavior#WAIT_FOR_CHANGES_VISIBLE}
 *                                              he may wait entire {@link #flushFrequencyInMillis} milliseconds before he gets
 *                                              the response.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record TransactionOptions(
	@Nonnull Path transactionWorkDirectory,
	long transactionMemoryBufferLimitSizeBytes,
	int transactionMemoryRegionCount,
	long walFileSizeBytes,
	int walFileCountKept,
	long flushFrequencyInMillis
) {
	public static final Path DEFAULT_TX_DIRECTORY = Paths.get(System.getProperty("java.io.tmpdir"), "evita/transaction");
	public static final long DEFAULT_TRANSACTION_MEMORY_BUFFER_LIMIT_SIZE = 16_777_216;
	public static final int DEFAULT_TRANSACTION_MEMORY_REGION_COUNT = 256;
	public static final int DEFAULT_WAL_SIZE_BYTES = 16_777_216;
	public static final int DEFAULT_WAL_FILE_COUNT_KEPT = 8;
	public static final int DEFAULT_FLUSH_FREQUENCY = 1_000;

	/**
	 * Builder method is planned to be used only in tests.
	 */
	public static TransactionOptions temporary() {
		return new TransactionOptions(
			DEFAULT_TX_DIRECTORY,
			1_048_576,
			32,
			8_388_608,
			1,
			100
		);
	}

	/**
	 * Builder for the transaction options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static TransactionOptions.Builder builder() {
		return new TransactionOptions.Builder();
	}

	/**
	 * Builder for the transaction options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static TransactionOptions.Builder builder(@Nonnull TransactionOptions TransactionOptions) {
		return new TransactionOptions.Builder(TransactionOptions);
	}

	public TransactionOptions() {
		this(
			DEFAULT_TX_DIRECTORY,
			DEFAULT_TRANSACTION_MEMORY_BUFFER_LIMIT_SIZE,
			DEFAULT_TRANSACTION_MEMORY_REGION_COUNT,
			DEFAULT_WAL_SIZE_BYTES,
			DEFAULT_WAL_FILE_COUNT_KEPT,
			DEFAULT_FLUSH_FREQUENCY
		);
	}

	public TransactionOptions(
		@Nullable Path transactionWorkDirectory,
		long transactionMemoryBufferLimitSizeBytes,
		int transactionMemoryRegionCount,
		long walFileSizeBytes,
		int walFileCountKept,
		long flushFrequencyInMillis
	) {
		this.transactionWorkDirectory = Optional.ofNullable(transactionWorkDirectory).orElse(DEFAULT_TX_DIRECTORY);
		this.transactionMemoryBufferLimitSizeBytes = transactionMemoryBufferLimitSizeBytes;
		this.transactionMemoryRegionCount = transactionMemoryRegionCount;
		this.walFileSizeBytes = walFileSizeBytes;
		this.walFileCountKept = walFileCountKept;
		this.flushFrequencyInMillis = flushFrequencyInMillis;
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private Path transactionWorkDirectory = DEFAULT_TX_DIRECTORY;
		private long transactionMemoryBufferLimitSizeBytes = DEFAULT_TRANSACTION_MEMORY_BUFFER_LIMIT_SIZE;
		private int transactionMemoryRegionCount = DEFAULT_TRANSACTION_MEMORY_REGION_COUNT;
		private long walFileSizeBytes = DEFAULT_WAL_SIZE_BYTES;
		private int walFileCountKept = DEFAULT_WAL_FILE_COUNT_KEPT;
		private long flushFrequency = DEFAULT_FLUSH_FREQUENCY;

		Builder() {
		}

		Builder(@Nonnull TransactionOptions TransactionOptions) {
			this.transactionWorkDirectory = TransactionOptions.transactionWorkDirectory;
			this.transactionMemoryBufferLimitSizeBytes = TransactionOptions.transactionMemoryBufferLimitSizeBytes;
			this.transactionMemoryRegionCount = TransactionOptions.transactionMemoryRegionCount;
			this.walFileSizeBytes = TransactionOptions.walFileSizeBytes;
			this.walFileCountKept = TransactionOptions.walFileCountKept;
			this.flushFrequency = TransactionOptions.flushFrequencyInMillis;
		}

		@Nonnull
		public TransactionOptions.Builder transactionWorkDirectory(@Nonnull Path transactionWorkDirectory) {
			this.transactionWorkDirectory = transactionWorkDirectory;
			return this;
		}

		@Nonnull
		public TransactionOptions.Builder transactionMemoryBufferLimitSizeBytes(long transactionMemoryBufferLimitSizeBytes) {
			this.transactionMemoryBufferLimitSizeBytes = transactionMemoryBufferLimitSizeBytes;
			return this;
		}

		@Nonnull
		public TransactionOptions.Builder transactionMemoryRegionCount(int transactionMemoryRegionCount) {
			this.transactionMemoryRegionCount = transactionMemoryRegionCount;
			return this;
		}

		@Nonnull
		public TransactionOptions.Builder walFileSizeBytes(long walFileSizeBytes) {
			this.walFileSizeBytes = walFileSizeBytes;
			return this;
		}

		@Nonnull
		public TransactionOptions.Builder walFileCountKept(int walFileCountKept) {
			this.walFileCountKept = walFileCountKept;
			return this;
		}

		@Deprecated(since = "2025.3", forRemoval = true)
		@Nonnull
		public TransactionOptions.Builder flushFrequency(long flushFrequency) {
			return flushFrequencyInMillis(flushFrequency);
		}

		@Nonnull
		public TransactionOptions.Builder flushFrequencyInMillis(long flushFrequency) {
			this.flushFrequency = flushFrequency;
			return this;
		}

		@Nonnull
		public TransactionOptions build() {
			return new TransactionOptions(
				this.transactionWorkDirectory,
				this.transactionMemoryBufferLimitSizeBytes,
				this.transactionMemoryRegionCount,
				this.walFileSizeBytes,
				this.walFileCountKept,
				this.flushFrequency
			);
		}

	}

}
