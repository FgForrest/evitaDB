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


import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.spi.exception.CatalogWriteAheadLastTransactionMismatchException;
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
	 * The value `0` means there are no valid transactions in the WAL file.
	 */
	private final AtomicLong lastWrittenVersion = new AtomicLong();
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
		this.walFileIndex = walFileIndex;
		this.firstVersionOfCurrentWalFile.set(firstCatalogVersion);
		this.lastWrittenVersion.set(lastCatalogVersion);
		this.walFilePath = walFilePath;
		this.walFileChannel = walFileChannel;
		this.output = output;
		this.currentWalFileSize = size;
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
	 */
	public void updateLastWrittenVersion(long catalogVersion, int writtenLength) {
		checkNextVersionMatch(catalogVersion);
		this.lastWrittenVersion.set(catalogVersion);
		this.currentWalFileSize += writtenLength;
	}

	/**
	 * Checks if the next catalog version matches the expected order.
	 *
	 * The method validates that the provided catalog version is either the start of a new sequence
	 * (when the current last catalog version is -1) or the subsequent version of the last written one.
	 * It throws a {@link GenericEvitaInternalError} if this condition is not met.
	 *
	 * @param version the catalog version to verify against the expected sequence
	 */
	public void checkNextVersionMatch(long version) {
		final long currentLastCatalogVersion = this.lastWrittenVersion.get();
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
