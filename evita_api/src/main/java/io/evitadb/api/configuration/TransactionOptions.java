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

import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
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
 * @param checkpointIntervalInMillis            How often the data files are made durable (fsync) and the bootstrap
 *                                              record pointing at them is written. Trunk incorporation always writes
 *                                              its bytes out, but between checkpoints they only reach the operating
 *                                              system page cache - the device flush is what this interval bounds.
 *                                              Set to `0` to checkpoint at the end of every trunk round. The
 *                                              interval is **ignored entirely when `syncWrites` is off** in
 *                                              `StorageOptions`: with no device flush being issued there is nothing
 *                                              to defer, so the two settings stay orthogonal instead of one
 *                                              silently re-enabling the other.
 *                                              This is deliberately a different cadence from
 *                                              `flushFrequencyInMillis`: that one bounds when changes become
 *                                              **visible**, this one bounds when they become **durable on the data
 *                                              files**. Durability of an acknowledged commit does not depend on it -
 *                                              the write-ahead log is the source of truth for that, and anything
 *                                              written after the last checkpoint is replayed from the WAL on restart.
 *                                              The interval therefore trades WAL retention and restart replay time
 *                                              for write throughput.
 * @param conflictRingBufferSize                Size of the array inside transaction conflict keys ring buffer.
 *                                              The larger the size, the more conflict keys the ring buffer can keep
 *                                              in volatile memory. Amount of necessary conflict keys is dependent on
 *                                              granularity of conflict keys, the number of concurrent transactions,
 *                                              and the age of the oldest writable session (e.g. transaction).
 * @param conflictPolicy                        Conflict resolution setting that will be used to resolve conflicts with
 *                                              other parallel sessions during the transaction commit. It combines the
 *                                              coarse {@link ConflictPolicy} scope with an optional set of
 *                                              {@link GranularConflictPolicy} refinements (see {@link ConflictResolution}).
 *                                              By default, conflicts are detected at {@link ConflictPolicy#ENTITY} level.
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
	long checkpointIntervalInMillis,
	int conflictRingBufferSize,
	@Nonnull ConflictResolution conflictPolicy
) {
	public static final Path DEFAULT_TX_DIRECTORY = Paths.get(System.getProperty("java.io.tmpdir"), "evita/transaction");
	public static final long DEFAULT_TRANSACTION_MEMORY_BUFFER_LIMIT_SIZE = 16_777_216;
	public static final int DEFAULT_TRANSACTION_MEMORY_REGION_COUNT = 256;
	public static final int DEFAULT_WAL_SIZE_BYTES = 16_777_216;
	public static final int DEFAULT_WAL_FILE_COUNT_KEPT = 8;
	public static final int DEFAULT_WAIT_FOR_TRANSACTION_ACCEPTANCE = 20_000;
	/**
	 * Relaxed from `1_000` when the budget started being honoured at all.
	 *
	 * The value was previously handed to trunk incorporation in nanoseconds while being compared in milliseconds,
	 * which inflated it to roughly 11.6 days - so in practice a round always drained the entire WAL backlog present
	 * when it opened, and this knob never bounded anything. Restoring the unit at `1_000` would have taken the engine
	 * from an effectively unbounded batching window straight to a one-second one, cutting rounds far more aggressively
	 * than any behaviour that has ever been exercised in production.
	 *
	 * Measured commit throughput is flat across `1_000`..`60_000` at 4 and 16 concurrent writers, because at those
	 * rates trunk incorporation keeps up and no backlog forms for the budget to cut. The value therefore only starts
	 * to matter in a sustained-backlog regime that the benchmark could not reproduce, and is chosen to hedge that
	 * regime: an order of magnitude more batching headroom than the nominal old value, while still bounding how long
	 * a client awaiting {@link io.evitadb.api.TransactionContract.CommitBehavior#WAIT_FOR_CHANGES_VISIBLE} can be kept
	 * behind other writers' work.
	 */
	public static final int DEFAULT_FLUSH_FREQUENCY = 10_000;
	/**
	 * Bounds how long the data files may sit in the operating system page cache before they are forced to the device
	 * and a bootstrap record is written to point at them.
	 *
	 * Before this interval existed, every trunk round paid the full device bill: `N_changed + 2` fsyncs, measured at
	 * roughly 15.5 ms on a LUKS+xfs SSD. That bill is **fixed per round**, so its per-transaction share is
	 * `15.5 / k`, where `k` is the number of transactions the round collapses. Under
	 * {@link io.evitadb.api.TransactionContract.CommitBehavior#WAIT_FOR_CHANGES_VISIBLE} a client blocks until its own
	 * change is visible and therefore cannot join a later batch, which caps `k` at the number of concurrent writers -
	 * the fsync share is consequently **largest when the system is least busy**. Measured share of the round: 57 % at
	 * 2 writers, 47 % at 8, 24 % at 16 and nothing at 64, where trunk incorporation's own merge work dominates.
	 *
	 * One second is the same order as the visibility cadence other engines expose, and two orders below their
	 * checkpoint cadences (WiredTiger 60 s, PostgreSQL 5 min), so it is a deliberately conservative starting point:
	 * it collects most of the win while keeping WAL retention and restart replay to about a second of writes.
	 */
	public static final int DEFAULT_CHECKPOINT_INTERVAL = 1_000;
	public static final int DEFAULT_CONFLICT_RING_BUFFER_SIZE = 65_536;
	public static final ConflictResolution DEFAULT_CONFLICT_RESOLUTION = new ConflictResolution(ConflictPolicy.ENTITY);

	/**
	 * Builder method is planned to be used only in tests.
	 *
	 * Checkpointing is pinned to every round here rather than to {@link #DEFAULT_CHECKPOINT_INTERVAL}. Deferred
	 * checkpointing is time-dependent by construction, and a test that commits and then inspects the catalog on disk
	 * would otherwise be asserting against a checkpoint that has not fired yet. The deferred path is covered by tests
	 * that configure the interval explicitly. This value is in the same spirit as the other deviations here (a 1 MB
	 * transaction buffer against 16 MB, one WAL file against eight): the method exists to make tests fast and
	 * deterministic, not to mirror production.
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
			0,
			256,
			DEFAULT_CONFLICT_RESOLUTION
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
			DEFAULT_CHECKPOINT_INTERVAL,
			DEFAULT_CONFLICT_RING_BUFFER_SIZE,
			DEFAULT_CONFLICT_RESOLUTION
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
		long checkpointIntervalInMillis,
		int conflictRingBufferSize,
		@Nullable ConflictResolution conflictPolicy
	) {
		this.transactionWorkDirectory = Optional.ofNullable(transactionWorkDirectory).orElse(DEFAULT_TX_DIRECTORY);
		this.transactionMemoryBufferLimitSizeBytes = transactionMemoryBufferLimitSizeBytes;
		this.transactionMemoryRegionCount = transactionMemoryRegionCount;
		this.walFileSizeBytes = walFileSizeBytes;
		this.walFileCountKept = walFileCountKept;
		this.waitForTransactionAcceptanceInMillis = waitForTransactionAcceptanceInMillis;
		this.flushFrequencyInMillis = flushFrequencyInMillis;
		this.checkpointIntervalInMillis = checkpointIntervalInMillis;
		this.conflictRingBufferSize = conflictRingBufferSize;
		// ConflictResolution is immutable, no defensive copy required; null falls back to the default
		this.conflictPolicy = Optional.ofNullable(conflictPolicy).orElse(DEFAULT_CONFLICT_RESOLUTION);
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
		private long checkpointInterval = DEFAULT_CHECKPOINT_INTERVAL;
		private int conflictRingBufferSize = DEFAULT_CONFLICT_RING_BUFFER_SIZE;
		private ConflictResolution conflictPolicy = DEFAULT_CONFLICT_RESOLUTION;

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
			this.checkpointInterval = transactionOptions.checkpointIntervalInMillis;
			this.conflictRingBufferSize = transactionOptions.conflictRingBufferSize;
			this.conflictPolicy = transactionOptions.conflictPolicy;
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

		@Deprecated(since = "2025.4", forRemoval = true)
		@Nonnull
		public TransactionOptions.Builder flushFrequency(long flushFrequency) {
			return flushFrequencyInMillis(flushFrequency);
		}

		@Nonnull
		public TransactionOptions.Builder flushFrequencyInMillis(long flushFrequency) {
			this.flushFrequency = flushFrequency;
			return this;
		}

		/**
		 * Sets how often the data files are forced to the device and a bootstrap record is written to point at them.
		 *
		 * @param checkpointInterval interval in milliseconds, `0` to checkpoint at the end of every trunk round
		 */
		@Nonnull
		public TransactionOptions.Builder checkpointIntervalInMillis(long checkpointInterval) {
			this.checkpointInterval = checkpointInterval;
			return this;
		}

		@Nonnull
		public TransactionOptions.Builder conflictRingBufferSize(int conflictRingBufferSize) {
			this.conflictRingBufferSize = conflictRingBufferSize;
			return this;
		}

		/**
		 * Sets the complete conflict resolution setting used during the transaction commit.
		 *
		 * @param conflictResolution the conflict resolution to use, never null
		 */
		@Nonnull
		public TransactionOptions.Builder conflictResolution(@Nonnull ConflictResolution conflictResolution) {
			this.conflictPolicy = conflictResolution;
			return this;
		}

		/**
		 * Sets the coarse conflict resolution scope with no sub-entity refinements.
		 *
		 * @param policy the coarse conflict scope, never null
		 */
		@Nonnull
		public TransactionOptions.Builder conflictResolution(@Nonnull ConflictPolicy policy) {
			this.conflictPolicy = new ConflictResolution(policy);
			return this;
		}

		/**
		 * Sets an entity-scoped conflict resolution with the provided sub-entity refinements. Passing no
		 * refinement is equivalent to a plain {@link ConflictPolicy#ENTITY} scope.
		 *
		 * @param policy      the coarse conflict scope, never null (must be {@link ConflictPolicy#ENTITY}
		 *                    when any refinement is supplied)
		 * @param granularity the sub-entity refinements to apply
		 */
		@Nonnull
		public TransactionOptions.Builder conflictResolution(@Nonnull ConflictPolicy policy, @Nonnull GranularConflictPolicy... granularity) {
			final EnumSet<GranularConflictPolicy> granularitySet = granularity.length == 0
				? EnumSet.noneOf(GranularConflictPolicy.class)
				: EnumSet.copyOf(Arrays.asList(granularity));
			this.conflictPolicy = new ConflictResolution(policy, granularitySet);
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
				this.checkpointInterval,
				this.conflictRingBufferSize,
				this.conflictPolicy
			);
		}

	}

}
