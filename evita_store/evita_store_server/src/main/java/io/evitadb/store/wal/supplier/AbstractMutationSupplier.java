/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.store.wal.supplier;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.model.StorageRecord.StorageRecordWithChecksum;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.BufferUnderflowException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static io.evitadb.store.wal.AbstractMutationLog.CUMULATIVE_CRC32_SIZE;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Abstract sealed base class for both {@link MutationSupplier} (forward) and {@link ReverseMutationSupplier}
 * (reverse) that encapsulates common WAL reading logic, state management, and resource lifecycle.
 *
 * This class handles:
 *
 * - **WAL file format parsing** — expects the format: `[8-byte cumulative CRC32C] [4-byte content length]
 *   [transaction mutation data] [individual mutations...] [8-byte cumulative CRC32C]`, repeated per transaction.
 * - **Transaction location caching** — maintains a {@link ConcurrentHashMap} of {@link TransactionLocations}
 *   per WAL file index to enable fast seek to a specific catalog version without full file scans.
 * - **Cumulative checksum tracking** — initializes and maintains a running CRC32C checksum that is validated
 *   against stored values at transaction boundaries.
 * - **WAL file rotation** — transparently moves to the next or previous WAL file when the current one is
 *   exhausted.
 *
 * The constructor performs the initial seek to the requested catalog version by scanning forward from the
 * nearest cached transaction location, optionally rotating across WAL files.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
abstract sealed class AbstractMutationSupplier<T extends Mutation> implements Supplier<T>, Closeable
	permits MutationSupplier, ReverseMutationSupplier {
	/**
	 * This flag is used to prevent the observable input from reading the next transaction mutation if there is not
	 * enough data already written to fully fill the buffer of the observable input.
	 */
	protected final boolean avoidPartiallyFilledBuffer;
	/**
	 * The Kryo pool for serializing {@link TransactionMutation} (given by outside).
	 */
	protected final Pool<Kryo> catalogKryoPool;
	/**
	 * The Kryo object for serializing {@link TransactionMutation} obtained in constructor from the Kryo pool.
	 */
	protected final Kryo kryo;
	/**
	 * Path to the storage folder where the WAL file is stored.
	 */
	protected final Path storageFolder;
	/**
	 * The storage options from evita configuration.
	 */
	protected final StorageSettings storageSettings;
	/**
	 * The cache of already scanned WAL files. The locations might not be complete, but they will be always cover
	 * the start of the particular WAL file, but they may be later appended with new records that are not yet scanned
	 * or gradually added to the working WAL file. The index key in this map is the {@link #walFileIndex} of the WAL file.
	 */
	protected final ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache;
	/**
	 * The function that provides the name of the WAL file based on the index.
	 */
	protected final IntFunction<String> walFileNameProvider;
	/**
	 * Callback to be executed when the supplier is closed.
	 */
	private final Runnable onClose;
	/**
	 * The Write-Ahead Log (WAL) file reference
	 */
	protected File walFile;
	/**
	 * The index of the WAL file incremented each time the WAL file is rotated.
	 */
	protected int walFileIndex;
	/**
	 * The ObservableInput for reading {@link TransactionMutation} from the WAL file.
	 */
	@Nullable protected ObservableInput<RandomAccessFileInputStream> observableInput;
	/**
	 * The current {@link TransactionMutation} being read from the WAL file.
	 */
	@Nullable protected TransactionMutationWithLocation transactionMutation;
	/**
	 * Cumulative checksum that precedes the current transaction mutation.
	 */
	@Nullable protected Checksum cumulativeChecksum;
	/**
	 * The current position in the WAL file.
	 */
	protected long filePosition;
	/**
	 * The number of mutations read from the current transaction.
	 */
	protected int transactionMutationRead;
	/**
	 * The number of transactions read from the WAL file.
	 */
	@Getter protected int transactionsRead;

	/**
	 * Calculates the starting position of the next transaction in the WAL file based on the current file position
	 * and the record length of the given transaction mutation.
	 *
	 * @param currentPosition                 the current position in the WAL file
	 * @param transactionMutationWithLocation an instance of {@link TransactionMutationWithLocation} containing
	 *                                        the transaction information and its file location.
	 * @return the starting position of the next transaction in the WAL file as a long value.
	 */
	private static long calculateNextTransactionStartPosition(
		long currentPosition, @Nonnull TransactionMutationWithLocation transactionMutationWithLocation
	) {
		return currentPosition + transactionMutationWithLocation.getTransactionSpan().recordLength();
	}

	/**
	 * Creates a new AbstractMutationSupplier for reading transactions from a WAL file.
	 *
	 * @param version                        the starting version to read from
	 * @param walFileNameProvider            function to generate WAL file names from file index
	 * @param storageFolder                  the directory where WAL files are stored
	 * @param storageSettings                storage configuration including checksum and compression factories
	 * @param walFileIndex                   the index of the WAL file to read
	 * @param catalogKryoPool                pool of Kryo instances for deserialization
	 * @param transactionLocationsCache      cache of transaction locations within WAL files
	 * @param avoidPartiallyFilledBuffer     whether to avoid partially filled buffers during reads
	 * @param onClose                        optional callback to run when the supplier is closed
	 */
	public AbstractMutationSupplier(
		long version,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path storageFolder,
		@Nonnull StorageSettings storageSettings,
		int walFileIndex,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache,
		boolean avoidPartiallyFilledBuffer,
		@Nullable Runnable onClose
	) {
		this.walFile = storageFolder.resolve(walFileNameProvider.apply(walFileIndex)).toFile();
		this.walFileIndex = walFileIndex;
		this.walFileNameProvider = walFileNameProvider;
		this.storageFolder = storageFolder;
		this.storageSettings = storageSettings;
		this.transactionLocationsCache = transactionLocationsCache;
		this.avoidPartiallyFilledBuffer = avoidPartiallyFilledBuffer;
		this.onClose = onClose;
		// WAL file must exist and have at least 4 bytes (minimum for a content length prefix)
		if (!this.walFile.exists() || this.walFile.length() < 4) {
			this.catalogKryoPool = catalogKryoPool;
			this.kryo = null;
			this.observableInput = null;
			this.transactionMutation = null;
		} else {
			this.catalogKryoPool = catalogKryoPool;
			this.kryo = catalogKryoPool.obtain();
			try {
				this.observableInput = new ObservableInput<>(
					new RandomAccessFileInputStream(
						new RandomAccessFile(this.walFile, "r"),
						true
					),
					this.storageSettings.createChecksum(),
					this.storageSettings.createDecompressor().orElse(null)
				);
				// Outer do/while loop: retries with the next WAL file if the target version
				// is not found in the current one. Inner while loop: scans forward within a
				// single WAL file from the nearest cached position.
				Optional<TransactionMutationWithLocation> initialTransactionMutation;
				do {
					// try to start from the nearest cached position (fast path), otherwise
					// fall back to the beginning of the file (after the initial 8-byte checksum)
					this.filePosition = ofNullable(this.transactionLocationsCache.get(this.walFileIndex))
						.filter(it -> !it.wasCut())
						.map(it -> it.findNearestLocation(version))
						.orElse((long) CUMULATIVE_CRC32_SIZE);

					// seek back by CUMULATIVE_CRC32_SIZE to read the 8-byte checksum preceding the transaction
					this.observableInput.seekWithUnknownLength(this.filePosition - CUMULATIVE_CRC32_SIZE);
					final long initialChecksum = this.observableInput.simpleLongRead();
					this.cumulativeChecksum = storageSettings.createCumulativeChecksum(initialChecksum);
					// the checksum value itself is part of the WAL format and participates in
					// the cumulative computation — hence both reset (initialize) and update (register)
					this.cumulativeChecksum.update(initialChecksum);

					final long walFileLength = this.walFile.length();
					initialTransactionMutation = readAndRecordTransactionMutation(this.filePosition, walFileLength);
					// scan forward past transactions with versions older than the requested one
					while (initialTransactionMutation.map(it -> it.getVersion() < version).orElse(false)) {
						this.filePosition = calculateNextTransactionStartPosition(
							this.filePosition, initialTransactionMutation.get()
						);
						// read the cumulative checksum preceding the next transaction
						this.observableInput.seekWithUnknownLength(this.filePosition - CUMULATIVE_CRC32_SIZE);
						final long actualCumulativeChecksum = this.observableInput.simpleLongRead();
						this.cumulativeChecksum.reset(actualCumulativeChecksum);
						this.cumulativeChecksum.update(actualCumulativeChecksum);
						initialTransactionMutation = readAndRecordTransactionMutation(this.filePosition, walFileLength);
						// verify the file has enough room for the complete transaction
						if (
							initialTransactionMutation
								.map(it -> walFileLength < calculateNextTransactionStartPosition(this.filePosition, it))
								.orElse(true)
						) {
							initialTransactionMutation = empty();
							break;
						}
					}
				} while (
					initialTransactionMutation.isEmpty() &&
						// target version not found in this file — try the next WAL file
						moveToNextWalFile(1)
				);
				this.transactionMutation = initialTransactionMutation.orElse(null);
			} catch (BufferUnderflowException e) {
				// incomplete write or premature EOF — treat as no data available
				if (this.observableInput != null) {
					this.observableInput.close();
				}
				this.transactionMutation = null;
				this.observableInput = null;
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to read WAL file `" + this.walFile.getName() + "`!",
					"Failed to read WAL file!",
					e
				);
			}
		}
	}

	/**
	 * Returns the next mutation from the WAL file. Implemented by subclasses to provide forward
	 * or reverse iteration order.
	 *
	 * @return the next mutation, or {@code null} if all transactions have been exhausted
	 */
	@Nullable
	@Override
	public abstract T get();

	/**
	 * Releases all resources held by this supplier: returns the Kryo instance to the pool,
	 * closes the WAL file input stream, and invokes the optional close callback.
	 */
	@Override
	public void close() {
		if (this.kryo != null) {
			this.catalogKryoPool.free(this.kryo);
		}
		if (this.observableInput != null) {
			this.observableInput.close();
		}
		if (this.onClose != null) {
			this.onClose.run();
		}
	}

	/**
	 * Retrieves the observable input stream for reading mutation transactions.
	 * Ensures that the observable input is set before accessing it.
	 *
	 * @return an instance of {@link ObservableInput} wrapping a {@link RandomAccessFileInputStream}.
	 * @throws IllegalStateException if the observable input is null.
	 */
	@Nonnull
	protected ObservableInput<RandomAccessFileInputStream> getObservableInput() {
		Assert.isPremiseValid(
			this.observableInput != null,
			"Observable input must be set before reading a mutation."
		);
		return this.observableInput;
	}

	/**
	 * Closes the current WAL file and opens the adjacent one, resetting the file position and
	 * cumulative checksum. The direction is controlled by the {@code delta} parameter.
	 *
	 * @param delta positive to move forward (e.g. {@code +1}), negative to move backward (e.g. {@code -1})
	 * @return {@code true} if the adjacent WAL file exists and was successfully opened,
	 *         {@code false} otherwise
	 */
	protected boolean moveToNextWalFile(int delta) {
		if (this.observableInput != null) {
			this.observableInput.close();
		}

		final File nextWalFile = this.storageFolder.resolve(
			this.walFileNameProvider.apply(this.walFileIndex + delta)
		).toFile();

		if (nextWalFile.exists()) {
			try {
				this.walFile = nextWalFile;
				this.walFileIndex += delta;
				// position after the initial 8-byte cumulative checksum
				this.filePosition = CUMULATIVE_CRC32_SIZE;
				this.observableInput = new ObservableInput<>(
					new RandomAccessFileInputStream(
						new RandomAccessFile(nextWalFile, "r"),
						true
					),
					this.storageSettings.createChecksum(),
					this.storageSettings.createDecompressor().orElse(null)
				);

				// read the 8-byte cumulative checksum at the start of the WAL file
				final long initialChecksum = this.observableInput.readLong();
				if (this.cumulativeChecksum == null) {
					this.cumulativeChecksum = this.storageSettings.createCumulativeChecksum(initialChecksum);
				} else {
					this.cumulativeChecksum.reset(initialChecksum);
				}
				// the checksum value itself participates in the cumulative computation
				this.cumulativeChecksum.update(initialChecksum);

				return true;
			} catch (FileNotFoundException ignored) {
				// race condition: file disappeared between exists() check and open
				return false;
			}
		} else {
			return false;
		}
	}

	/**
	 * Reads a transaction mutation from the current WAL file position and records its location in the
	 * transaction locations cache. The method performs the following steps:
	 *
	 * 1. Reads the 4-byte content length prefix and feeds it into the cumulative checksum.
	 * 2. Validates that the file has enough room for the full record (4 + content + trailing CRC32C).
	 * 3. Deserializes the leading {@link TransactionMutation} with checksum computation.
	 * 4. Combines the mutation's checksum into the running cumulative checksum.
	 * 5. Validates the content length against actual bytes read plus the declared WAL payload size.
	 * 6. Registers the transaction location in the cache for future fast-path lookups.
	 *
	 * @param startPosition the byte position where this transaction begins in the WAL file
	 * @param fileSize      the current total size of the WAL file (for EOF detection)
	 * @return the transaction mutation with its file location, or empty if the file is truncated
	 */
	@Nonnull
	protected Optional<TransactionMutationWithLocation> readAndRecordTransactionMutation(
		long startPosition, long fileSize) {
		final ObservableInput<RandomAccessFileInputStream> theObservableInput = this.observableInput;
		Assert.isPremiseValid(
			this.observableInput != null,
			"Observable input is not initialized!"
		);
		Assert.isPremiseValid(
			this.cumulativeChecksum != null,
			"Cumulative checksum is not initialized!"
		);

		// not enough room for even the 4-byte content length prefix
		if (startPosition + 4 > fileSize) {
			return empty();
		}

		// record the stream position before reading so we can measure bytes consumed
		final long totalBefore = theObservableInput.total();
		// read the 4-byte content length: total size of the transaction's payload
		// (leading mutation + all individual mutations, excluding the length prefix and trailing checksum)
		final int contentLength = theObservableInput.simpleIntRead();
		this.cumulativeChecksum.update(contentLength);

		// full record = 4 (length prefix) + content + 8 (trailing cumulative CRC32C)
		final int fullRecordLength = 4 + contentLength + CUMULATIVE_CRC32_SIZE;

		if (startPosition + fullRecordLength > fileSize) {
			// file is truncated — not enough room for content + trailing checksum
			return empty();
		}

		// deserialize the leading TransactionMutation with checksum computation
		final StorageRecordWithChecksum<TransactionMutation> txMutationWithChecksum = StorageRecord.readWithChecksum(
			theObservableInput,
			(stream, length) -> (TransactionMutation) this.kryo.readClassAndObject(stream)
		);
		final TransactionMutation transactionMutation = Objects.requireNonNull(
			txMutationWithChecksum.record().payload()
		);
		// combine the leading mutation's checksum into the running cumulative checksum
		this.cumulativeChecksum.combine(
			txMutationWithChecksum.checksum(),
			txMutationWithChecksum.record().fileLocation().recordLength()
		);

		// validate content length: the 4-byte prefix + content should equal the bytes we actually
		// read for the leading mutation (leadTransactionMutationSize) plus the declared size of all
		// individual mutations (walSizeInBytes)
		final int leadTransactionMutationSize = Math.toIntExact(theObservableInput.total() - totalBefore);
		Assert.isPremiseValid(
			contentLength + 4 == leadTransactionMutationSize + transactionMutation.getWalSizeInBytes(),
			"Invalid WAL file on position `" + this.filePosition + "`!"
		);
		// register the transaction location in cache for future fast-path lookups
		this.transactionLocationsCache.computeIfAbsent(
			this.walFileIndex, it -> new TransactionLocations()
		).register(this.filePosition, transactionMutation);

		this.transactionsRead++;
		return of(
			new TransactionMutationWithLocation(
				transactionMutation,
				new FileLocation(this.filePosition, fullRecordLength),
				this.walFileIndex
			)
		);
	}

}
