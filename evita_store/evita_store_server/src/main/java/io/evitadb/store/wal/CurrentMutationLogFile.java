/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.store.wal;


import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.exception.CatalogWriteAheadLastTransactionMismatchException;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Record contains information about currently use mutation log file.
 */
class CurrentMutationLogFile implements Closeable {
	/**
	 * This field contains the version of the first transaction in the current {@link #walFilePath}.
	 */
	private final AtomicLong firstVersionOfCurrentWalFile = new AtomicLong(-1L);
	/**
	 * This field contains the version of the last fully written transaction in the WAL file.
	 * The value `-1` means there are no valid transactions in the WAL file.
	 */
	private final AtomicLong lastWrittenVersion = new AtomicLong();
	/**
	 * Version of the last transaction of the newest finalized (rotated away) WAL file preceding this one, or `-1`
	 * when this is the first file of the log or nothing about its predecessor is known.
	 *
	 * This file alone cannot say what the log's last written version is while it holds no transaction yet - and it
	 * holds none for a while after every rotation: briefly inside the append that rotated, and indefinitely when the
	 * process crashed between rotation creating this file (with only its cumulative checksum header) and the first
	 * append landing in it. The predecessor's last version is the answer for that whole stretch, so it travels with
	 * the file rather than living in a separate field that a reader could observe out of step with the file swap.
	 */
	private final long lastVersionOfPreviousWalFile;
	/**
	 * The index of the WAL file incremented each time the WAL file is rotated.
	 */
	@Getter private final int walFileIndex;
	/**
	 * The path to the WAL file.
	 */
	private final Path walFilePath;
	/**
	 * The file channel for writing to the WAL file.
	 */
	private final FileChannel walFileChannel;
	/**
	 * The output stream for writing {@link TransactionMutation} to the WAL file.
	 */
	private final ObservableOutput<ByteArrayOutputStream> output;
	/**
	 * Field contains current size of the WAL file the records are appended to in Bytes.
	 */
	private long currentWalFileSize;
	/**
	 * Cumulative CRC32C checksum computed over all bytes written to the WAL file from the beginning up to the current
	 * position.
	 */
	private long cumulativeChecksum;
	/**
	 * Field indicates whether the WAL file is closed.
	 */
	private boolean closed = false;

	public CurrentMutationLogFile(
		int walFileIndex,
		long firstCatalogVersion,
		long lastCatalogVersion,
		@Nonnull Path walFilePath,
		@Nonnull FileChannel walFileChannel,
		@Nonnull ObservableOutput<ByteArrayOutputStream> output,
		long size
	) {
		this(walFileIndex, firstCatalogVersion, lastCatalogVersion, -1L, walFilePath, walFileChannel, output, size, 0L);
	}

	/**
	 * Creates the record of the WAL file the log appends to.
	 *
	 * @param walFileIndex                 index of the WAL file
	 * @param firstCatalogVersion          version of the first transaction in the file, or `-1` when it holds none
	 * @param lastCatalogVersion           version of the last transaction in the file, or `-1` when it holds none
	 * @param lastVersionOfPreviousWalFile version of the last transaction of the newest finalized file before this
	 *                                     one, or `-1` when there is none - see {@link #lastVersionOfPreviousWalFile}
	 * @param walFilePath                  path of the WAL file
	 * @param walFileChannel               channel the transactions are appended through
	 * @param output                       output the transaction mutations are serialized into
	 * @param size                         current size of the file in bytes
	 * @param initialCumulativeChecksum    cumulative checksum the file currently stands at
	 */
	public CurrentMutationLogFile(
		int walFileIndex,
		long firstCatalogVersion,
		long lastCatalogVersion,
		long lastVersionOfPreviousWalFile,
		@Nonnull Path walFilePath,
		@Nonnull FileChannel walFileChannel,
		@Nonnull ObservableOutput<ByteArrayOutputStream> output,
		long size,
		long initialCumulativeChecksum
	) {
		this.walFileIndex = walFileIndex;
		this.firstVersionOfCurrentWalFile.set(firstCatalogVersion);
		this.lastWrittenVersion.set(lastCatalogVersion);
		this.lastVersionOfPreviousWalFile = lastVersionOfPreviousWalFile;
		this.walFilePath = walFilePath;
		this.walFileChannel = walFileChannel;
		this.output = output;
		this.currentWalFileSize = size;
		this.cumulativeChecksum = initialCumulativeChecksum;
	}

	/**
	 * Retrieves the file path of the current Write-Ahead Log (WAL) file.
	 * This method ensures that the WAL file is open before returning the file path.
	 *
	 * @return the {@link Path} representing the current WAL file's location
	 */
	@Nonnull
	public Path getWalFilePath() {
		assertOpen();
		return this.walFilePath;
	}

	/**
	 * Retrieves the file channel associated with the current Write-Ahead Log (WAL) file.
	 * This method ensures that the WAL file is open before returning the file channel.
	 *
	 * @return the FileChannel associated with the current WAL file
	 */
	@Nonnull
	public FileChannel getWalFileChannel() {
		assertOpen();
		return this.walFileChannel;
	}

	/**
	 * Retrieves the ObservableOutput associated with the current Write-Ahead Log (WAL) file.
	 *
	 * @return an ObservableOutput of ByteArrayOutputStream representing the output channel for the WAL file
	 */
	@Nonnull
	public ObservableOutput<ByteArrayOutputStream> getOutput() {
		assertOpen();
		return this.output;
	}

	/**
	 * Retrieves the current size of the Write-Ahead Log (WAL) file.
	 *
	 * @return the size of the current WAL file in bytes
	 */
	public long getCurrentWalFileSize() {
		return this.currentWalFileSize;
	}

	/**
	 * Retrieves the first catalog version of the current Write-Ahead Log (WAL) file.
	 *
	 * @return the first catalog version of the current WAL file
	 */
	public long getFirstVersionOfCurrentWalFile() {
		return this.firstVersionOfCurrentWalFile.get();
	}

	/**
	 * Retrieves the last written catalog version in the Write-Ahead Log (WAL) file.
	 *
	 * @return the most recent catalog version that was recorded
	 */
	public long getLastWrittenVersion() {
		return this.lastWrittenVersion.get();
	}

	/**
	 * Retrieves the version of the last transaction written to the whole log this file belongs to - the last version
	 * in this file, or, while this file holds no transaction yet, the last version of the finalized file before it.
	 *
	 * @return the last version written to the log, or `-1` when the log holds no transaction at all
	 */
	public long getLastWrittenVersionInLog() {
		final long lastVersionInThisFile = this.lastWrittenVersion.get();
		return lastVersionInThisFile == -1L ? this.lastVersionOfPreviousWalFile : lastVersionInThisFile;
	}

	/**
	 * Initializes the first catalog version of the current WAL file if it is not set yet and runs the given action.
	 *
	 * @param catalogVersion the catalog version to set
	 * @param andThen        the action to run after the catalog version is set
	 */
	public void initFirstVersionOfCurrentWalFileIfNecessary(long catalogVersion, @Nonnull Runnable andThen) {
		if (this.firstVersionOfCurrentWalFile.get() == -1) {
			this.firstVersionOfCurrentWalFile.set(catalogVersion);
			andThen.run();
		}
	}

	/**
	 * Updates the last written catalog version and the current WAL file size by the given written length and updated
	 * catalog version.
	 *
	 * @param catalogVersion the updated catalog version
	 * @param writtenLength  the length of the written record
	 * @param cumulativeChecksum the updated cumulative checksum after writing
	 */
	public void updateLastWrittenVersion(long catalogVersion, int writtenLength, long cumulativeChecksum) {
		checkNextVersionMatch(catalogVersion);
		this.lastWrittenVersion.set(catalogVersion);
		this.currentWalFileSize += writtenLength;
		this.cumulativeChecksum = cumulativeChecksum;
	}

	/**
	 * Checks if the next catalog version matches the expected order.
	 *
	 * The method validates that the provided catalog version is either the start of a new sequence
	 * (when the log holds no transaction at all) or the subsequent version of the last one written to the log.
	 * It throws a {@link GenericEvitaInternalError} if this condition is not met.
	 *
	 * The comparison is made against the whole log, not this file alone: a file that holds no transaction yet
	 * continues the finalized file before it, so its first transaction must follow that file's last one. Accepting any
	 * version there would let an append re-use a version the previous file already holds - which the log's own
	 * startup verification then rejects as a gap between files, leaving the log unopenable.
	 *
	 * @param version the catalog version to verify against the expected sequence
	 */
	public void checkNextVersionMatch(long version) {
		final long currentLastCatalogVersion = getLastWrittenVersionInLog();
		Assert.isPremiseValid(
			currentLastCatalogVersion == -1 || currentLastCatalogVersion + 1 == version,
			() -> new CatalogWriteAheadLastTransactionMismatchException(
				currentLastCatalogVersion,
				"Invalid catalog version `" + version + "`! Expected: `" + (currentLastCatalogVersion + 1) + "`, but got `" + version + "`!",
				"Invalid catalog version to write to the WAL file!"
			)
		);
	}

	/**
	 * Returns the current value of the cumulative CRC32C checksum.
	 * This represents the checksum of all bytes written to the WAL file from the beginning
	 * up to the current position.
	 *
	 * @return the current cumulative checksum value
	 */
	public long getCumulativeChecksum() {
		return this.cumulativeChecksum;
	}

	/**
	 * Closes the current WAL file.
	 *
	 * @throws IOException if an I/O error occurs
	 */
	public void close() throws IOException {
		this.closed = true;
		this.output.close();
		this.walFileChannel.close();
	}

	@Override
	public String toString() {
		return this.walFilePath.normalize().toString();
	}

	/**
	 * Asserts that the current WAL file is open.
	 */
	private void assertOpen() {
		Assert.isPremiseValid(
			!this.closed,
			"The current WAL file is already closed!"
		);
	}
}
