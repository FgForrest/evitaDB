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
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumSet;
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
 * @param waitForTransactionAcceptanceInMillis  The maximum time in milliseconds the system will wait for a writing
 *                                              transaction to be accepted, i.e., written to the shared transaction WAL.
 *                                              This time span covers both the conflict resolution phase and appending
 *                                              to the shared WAL file. When the operation times out, the entire
 *                                              transaction will be rolled back.
 * @param flushFrequencyInMillis                The frequency of flushing the transactional data to the disk when they
 *                                              are sequentially processed. If database process the (small) transaction
 *                                              very quickly, it may decide to process next transaction before flushing
 *                                              changes to the disk. If the client waits for `CommitBehavior.WAIT_FOR_CHANGES_VISIBLE`
 *                                              he may wait entire `flushFrequencyInMillis` milliseconds before he gets
 *                                              the response.
 * @param conflictRingBufferSize                Size of the array inside transaction conflict keys ring buffer.
 *                                              The larger the size, the more conflict keys the ring buffer can keep
 *                                              in volatile memory. Amount of necessary conflict keys is dependent on
 *                                              granularity of conflict keys, the number of concurrent transactions,
 *                                              and the age of the oldest writable session (e.g. transaction).
 * @param conflictPolicy                        Set of conflict policies that will be used to resolve conflicts with
 *                                              other parallel sessions during the transaction commit. By default,
 *                                              {@link ConflictPolicy#ENTITY} is enabled.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record TransactionOptions(
	@Nonnull Path transactionWorkDirectory,
	long transactionMemoryBufferLimitSizeBytes,
	int transactionMemoryRegionCount,
	long walFileSizeBytes,
	int walFileCountKept,
	long waitForTransactionAcceptanceInMillis,
	long flushFrequencyInMillis,
	int conflictRingBufferSize,
	@Nonnull EnumSet<ConflictPolicy> conflictPolicy
) {
	public static final Path DEFAULT_TX_DIRECTORY = Paths.get(System.getProperty("java.io.tmpdir"), "evita/transaction");
	public static final long DEFAULT_TRANSACTION_MEMORY_BUFFER_LIMIT_SIZE = 16_777_216;
	public static final int DEFAULT_TRANSACTION_MEMORY_REGION_COUNT = 256;
	public static final int DEFAULT_WAL_SIZE_BYTES = 16_777_216;
	public static final int DEFAULT_WAL_FILE_COUNT_KEPT = 8;
	public static final int DEFAULT_WAIT_FOR_TRANSACTION_ACCEPTANCE = 20_000;
	public static final int DEFAULT_FLUSH_FREQUENCY = 1_000;
	public static final int DEFAULT_CONFLICT_RING_BUFFER_SIZE = 65_536;
	public static final EnumSet<ConflictPolicy> DEFAULT_CONFLICT_POLICY = EnumSet.of(ConflictPolicy.ENTITY);

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
			100,
			100,
			256,
			DEFAULT_CONFLICT_POLICY
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
	public static TransactionOptions.Builder builder(@Nonnull TransactionOptions transactionOptions) {
		return new TransactionOptions.Builder(transactionOptions);
	}

	public TransactionOptions() {
		this(
			DEFAULT_TX_DIRECTORY,
			DEFAULT_TRANSACTION_MEMORY_BUFFER_LIMIT_SIZE,
			DEFAULT_TRANSACTION_MEMORY_REGION_COUNT,
			DEFAULT_WAL_SIZE_BYTES,
			DEFAULT_WAL_FILE_COUNT_KEPT,
			DEFAULT_WAIT_FOR_TRANSACTION_ACCEPTANCE,
			DEFAULT_FLUSH_FREQUENCY,
			DEFAULT_CONFLICT_RING_BUFFER_SIZE,
			DEFAULT_CONFLICT_POLICY
		);
	}

	public TransactionOptions(
		@Nullable Path transactionWorkDirectory,
		long transactionMemoryBufferLimitSizeBytes,
		int transactionMemoryRegionCount,
		long walFileSizeBytes,
		int walFileCountKept,
		long waitForTransactionAcceptanceInMillis,
		long flushFrequencyInMillis,
		int conflictRingBufferSize,
		@Nonnull EnumSet<ConflictPolicy> conflictPolicy
	) {
		this.transactionWorkDirectory = Optional.ofNullable(transactionWorkDirectory).orElse(DEFAULT_TX_DIRECTORY);
		this.transactionMemoryBufferLimitSizeBytes = transactionMemoryBufferLimitSizeBytes;
		this.transactionMemoryRegionCount = transactionMemoryRegionCount;
		this.walFileSizeBytes = walFileSizeBytes;
		this.walFileCountKept = walFileCountKept;
		this.waitForTransactionAcceptanceInMillis = waitForTransactionAcceptanceInMillis;
		this.flushFrequencyInMillis = flushFrequencyInMillis;
		this.conflictRingBufferSize = conflictRingBufferSize;
		// defensive copy to prevent mutation of the record state
		this.conflictPolicy = conflictPolicy.isEmpty()
			? EnumSet.noneOf(ConflictPolicy.class)
			: EnumSet.copyOf(conflictPolicy);
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
		private long waitForTransactionAcceptance = DEFAULT_WAIT_FOR_TRANSACTION_ACCEPTANCE;
		private long flushFrequency = DEFAULT_FLUSH_FREQUENCY;
		private int conflictRingBufferSize = DEFAULT_CONFLICT_RING_BUFFER_SIZE;
		private final EnumSet<ConflictPolicy> conflictPolicy = EnumSet.copyOf(DEFAULT_CONFLICT_POLICY);

		Builder() {
		}

		Builder(@Nonnull TransactionOptions transactionOptions) {
			this.transactionWorkDirectory = transactionOptions.transactionWorkDirectory;
			this.transactionMemoryBufferLimitSizeBytes = transactionOptions.transactionMemoryBufferLimitSizeBytes;
			this.transactionMemoryRegionCount = transactionOptions.transactionMemoryRegionCount;
			this.walFileSizeBytes = transactionOptions.walFileSizeBytes;
			this.walFileCountKept = transactionOptions.walFileCountKept;
			this.waitForTransactionAcceptance = transactionOptions.waitForTransactionAcceptanceInMillis;
			this.flushFrequency = transactionOptions.flushFrequencyInMillis;
			this.conflictRingBufferSize = transactionOptions.conflictRingBufferSize;
			this.conflictPolicy.clear();
			this.conflictPolicy.addAll(transactionOptions.conflictPolicy);
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
		public TransactionOptions.Builder conflictRingBufferSize(int conflictRingBufferSize) {
			this.conflictRingBufferSize = conflictRingBufferSize;
			return this;
		}

		@Nonnull
		public TransactionOptions.Builder conflictPolicy(@Nonnull ConflictPolicy... conflictPolicy) {
			this.conflictPolicy.clear();
			Collections.addAll(this.conflictPolicy, conflictPolicy);
			return this;
		}

		@Nonnull
		public TransactionOptions.Builder conflictPolicyLastWriterWins() {
			this.conflictPolicy.clear();
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
				this.waitForTransactionAcceptance,
				this.flushFrequency,
				this.conflictRingBufferSize,
				this.conflictPolicy
			);
		}

	}

}
