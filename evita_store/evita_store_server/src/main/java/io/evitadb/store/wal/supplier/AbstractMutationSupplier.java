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
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.store.catalog.wal.VersionSource;
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException;
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException.WalKind;
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
import static io.evitadb.store.wal.AbstractMutationLog.TRANSACTION_PREFIX_SIZE;
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
	 * The version the caller asserts is already durably written, or {@code null} for a greedy read that makes no
	 * such assertion.
	 *
	 * It is a promise, not a filter. A caller that names a version is telling the supplier the transaction is on
	 * disk, and the supplier holds it to that: failing to reach the named version is reported as a
	 * {@link io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException} rather than as an exhausted
	 * stream (see {@code MutationSupplier#get()}), because a caller that already believes the version was written
	 * cannot tell a silent end-of-stream apart from "nothing left to process". A greedy read gets no such
	 * treatment - for it a torn tail is simply where the data ends. The version is additionally an upper bound:
	 * transactions beyond it are not delivered even when they are complete on disk.
	 *
	 * **It does not relax how much of a record must be present.** Every read, named or greedy, requires the
	 * record to be whole - length prefix, content and trailing cumulative CRC32C. It once excused the trailing
	 * checksum on the theory that a named version's checksum could still be lagging the reader's file-length
	 * view, but {@code AbstractMutationLog#doAppend} writes that checksum *before* the force that makes the
	 * transaction durable, and {@code appendDeferringSync} defers only the force, never the bytes. The window
	 * that excuse covered therefore belongs to a transaction that has not been reported to anyone yet, and no
	 * caller is in a position to name it.
	 */
	@Nullable protected final Long requestedVersion;
	/**
	 * Who chose the versions this read is bounded by - the engine itself, or a client over an external API.
	 *
	 * It decides nothing about *what* is read and everything about how a version that cannot be found is
	 * reported: as damage the operator must look at, or as an argument the caller got wrong. See
	 * {@link VersionSource} for why that choice has to be made here rather than translated further up.
	 */
	@Nonnull protected final VersionSource versionSource;
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
	 * Identifies which flavor of WAL is being read — stamped on every WAL corruption exception thrown
	 * from this supplier when it detects structural defects (currently the cumulative CRC32C mismatch
	 * at transaction boundaries).
	 */
	protected final WalKind walKind;
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
	 * Returns the file position the given transaction must reach on disk to be considered readable at the current
	 * position - the full record end, trailing cumulative checksum included, whether or not a
	 * {@link #requestedVersion} was named. A record whose tail has not landed is not there yet, and naming a
	 * version does not change that; see {@link #requestedVersion} for why it cannot.
	 *
	 * @param startPosition                   the byte position where the transaction begins in the WAL file
	 * @param transactionMutationWithLocation the transaction whose required end position is being computed
	 * @return the byte position that must be present in the file for the transaction to be delivered
	 */
	private long requiredEndPosition(
		long startPosition, @Nonnull TransactionMutationWithLocation transactionMutationWithLocation
	) {
		return calculateNextTransactionStartPosition(startPosition, transactionMutationWithLocation);
	}

	/**
	 * Builds the exception for "the version this read was bounded by is not in the log", picking the kind that
	 * matches who chose that version.
	 *
	 * Both arms describe the same on-disk situation; they differ in who is being told and what it costs. An
	 * {@link VersionSource#INTERNAL} bound is one the engine observed to be durable, so its absence is damage -
	 * {@link WriteAheadLogCorruptedException}, an {@link io.evitadb.exception.EvitaInternalError}, counted by
	 * `io_evitadb_errors_total` and worth waking someone for. A {@link VersionSource#CLIENT} bound was never
	 * promised by anyone, so its absence is a bad argument - {@link EvitaInvalidUsageException}, which the error
	 * counter ignores, because a client that keeps asking for a rotated-out version is not a fault an operator
	 * can clear.
	 *
	 * The choice is made here, at construction, and not by translating on the way out: constructing an
	 * `EvitaInternalError` is itself what moves the counter, so an exception built first and converted later has
	 * already done the damage this split exists to prevent.
	 *
	 * @param privateMessage the full diagnostic text, including positions and versions - never shown to a client
	 * @param publicSuffix   the client-safe explanation, appended to the flavor-specific prefix
	 * @return the exception to throw; never thrown by this method itself
	 */
	@Nonnull
	protected RuntimeException missingBoundedVersion(
		@Nonnull String privateMessage, @Nonnull String publicSuffix
	) {
		return this.versionSource == VersionSource.CLIENT ?
			new EvitaInvalidUsageException(privateMessage, publicSuffix) :
			new WriteAheadLogCorruptedException(
				this.walKind, privateMessage, this.walKind.corruptedLabel + ": " + publicSuffix
			);
	}

	/**
	 * Cause-carrying variant of {@link #missingBoundedVersion(String, String)}, for the case where the version
	 * could not be reached because a read beneath it failed rather than because the record was simply absent.
	 *
	 * @param privateMessage the full diagnostic text, including positions and versions - never shown to a client
	 * @param publicSuffix   the client-safe explanation, appended to the flavor-specific prefix
	 * @param cause          the failure that stopped the read from advancing
	 * @return the exception to throw; never thrown by this method itself
	 */
	@Nonnull
	protected RuntimeException missingBoundedVersion(
		@Nonnull String privateMessage, @Nonnull String publicSuffix, @Nonnull Throwable cause
	) {
		return this.versionSource == VersionSource.CLIENT ?
			new EvitaInvalidUsageException(privateMessage, publicSuffix, cause) :
			new WriteAheadLogCorruptedException(
				this.walKind, privateMessage, this.walKind.corruptedLabel + ": " + publicSuffix, cause
			);
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
	 * @param requestedVersion               the version the caller asserts is durably written, {@code null} for a
	 *                                       greedy read — see {@link #requestedVersion}
	 * @param versionSource                  who chose the versions this read is bounded by — decides whether a
	 *                                       version that cannot be found is damage or a bad argument
	 * @param onClose                        optional callback to run when the supplier is closed
	 * @param walKind                        flavor of WAL being read — stamped on every corruption exception
	 */
	public AbstractMutationSupplier(
		long version,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path storageFolder,
		@Nonnull StorageSettings storageSettings,
		int walFileIndex,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache,
		@Nullable Long requestedVersion,
		@Nonnull VersionSource versionSource,
		@Nullable Runnable onClose,
		@Nonnull WalKind walKind
	) {
		this.walFile = storageFolder.resolve(walFileNameProvider.apply(walFileIndex)).toFile();
		this.walFileIndex = walFileIndex;
		this.walFileNameProvider = walFileNameProvider;
		this.storageFolder = storageFolder;
		this.storageSettings = storageSettings;
		this.transactionLocationsCache = transactionLocationsCache;
		this.requestedVersion = requestedVersion;
		this.versionSource = versionSource;
		this.onClose = onClose;
		this.walKind = walKind;
		// WAL file must exist and have at least 4 bytes (minimum for a content length prefix)
		if (!this.walFile.exists() || this.walFile.length() < 4) {
			this.catalogKryoPool = catalogKryoPool;
			this.kryo = null;
			this.observableInput = null;
			this.transactionMutation = null;
			// This branch is how a named version most often goes missing, and the reason is not obvious:
			// `AbstractMutationLog#createSupplier` resolves the file index with `resolveWalFileIndex`, which
			// returns -1 once retention has trimmed away the file the start version lived in. `apply(-1)` is a
			// perfectly well-formed name for a file that cannot exist, so the supplier lands here rather than in
			// the scan below - and the scan is where the other not-found guard sits. Without this, the
			// aged-out-of-retention case, which is precisely the one VersionSource.CLIENT exists to report,
			// would still end in a silent empty stream. A greedy read keeps that silence: it named nothing, so
			// there is nothing it can be missing.
			if (requestedVersion != null && version <= requestedVersion) {
				// nothing was acquired on this path - kryo was never obtained and no input was opened - so
				// there is nothing to release before throwing
				throw missingBoundedVersion(
					"Catalog version " + requestedVersion + " cannot be read: " + this.walKind.fileLabel +
						" `" + this.walFile.getName() + "` holding the requested range is not present under `" +
						storageFolder + "` (reading forward from version " + version + "). The most likely " +
						"cause is that the range has been trimmed by WAL retention.",
					"requested version is no longer available in the log"
				);
			}
		} else {
			this.catalogKryoPool = catalogKryoPool;
			this.kryo = catalogKryoPool.obtain();
			try {
				this.observableInput = new ObservableInput<>(
					new RandomAccessFileInputStream(
						new RandomAccessFile(this.walFile, "r"),
						true
					),
					// cumulative-capable: readWithChecksum()/markCumulativeChecksumStart() below fold
					// per-record checksums via combine()/update(long), not just stream real bytes
					this.storageSettings.createCumulativeChecksum(0L),
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
						// verify the file has enough room for the whole transaction, trailing cumulative checksum
						// included — a record whose tail has not landed is not there yet, whoever is asking
						if (
							initialTransactionMutation
								.map(it -> walFileLength < requiredEndPosition(this.filePosition, it))
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
				// A greedy read that finds nothing has merely caught up with the writer - the ordinary state of
				// a log being tailed - so it ends the stream and says nothing. A read that NAMED a version makes
				// a different claim, and by this point every WAL file has been scanned without finding it.
				// Ending silently here is what lets a caller mistake "the version you named is gone" for
				// "nothing left to process": the trunk-incorporation stage spun on exactly that, and a
				// change-data-capture subscriber whose pointer has fallen out of retention still does.
				// `version > requestedVersion` is not a missing version, it is an empty interval: the caller has
				// asked for everything between a floor and a ceiling that are the wrong way round, which is what
				// a mutation-history query whose time frame starts after the last committed transaction resolves
				// to. There is nothing in that range by construction, so the answer is an empty stream - raising
				// would turn an ordinary "no results" page into an error.
				if (initialTransactionMutation.isEmpty() && requestedVersion != null && version <= requestedVersion) {
					// nothing escapes a constructor that throws, so close() will never run for this instance -
					// hand the Kryo back to the pool and release the file here or both leak. A Kryo left out of
					// the pool is not merely garbage: the pool hands out a fresh one, and a leak on a hot path
					// quietly multiplies the instances a writer and its readers are sharing
					this.observableInput.close();
					this.observableInput = null;
					catalogKryoPool.free(this.kryo);
					throw missingBoundedVersion(
						"Catalog version " + requestedVersion + " was not found in any " + this.walKind.fileLabel +
							" under `" + storageFolder + "` when reading forward from version " + version + ".",
						"requested version is not present in the log"
					);
				}
				this.transactionMutation = initialTransactionMutation.orElse(null);
			} catch (BufferUnderflowException e) {
				// incomplete write or premature EOF — treat as no data available
				if (this.observableInput != null) {
					this.observableInput.close();
				}
				this.transactionMutation = null;
				this.observableInput = null;
			} catch (IOException e) {
				// same reasoning as the not-found throw above, and it applied here long before that one existed:
				// this constructor is about to fail, so close() will never run and both the pooled Kryo and the
				// open file would be lost
				if (this.observableInput != null) {
					this.observableInput.close();
					this.observableInput = null;
				}
				catalogKryoPool.free(this.kryo);
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
	 * @return {@code true} if the adjacent WAL file exists, carries at least one record and was successfully
	 *         opened, {@code false} otherwise - including for a file still too short to hold its seed cumulative
	 *         checksum and a record behind it, which rotation leaves behind for a moment
	 */
	protected boolean moveToNextWalFile(int delta) {
		if (this.observableInput != null) {
			this.observableInput.close();
		}

		final File nextWalFile = this.storageFolder.resolve(
			this.walFileNameProvider.apply(this.walFileIndex + delta)
		).toFile();

		// rotation creates the next WAL file and writes its 8-byte seed cumulative checksum into it only
		// afterwards, so a reader that crosses the boundary in that window meets a file with nothing to read -
		// and a crash between the two leaves one at exactly that length for good. A file with no room for a
		// record behind its seed is therefore "not there yet" rather than a file to read: the seed read below is
		// unguarded and would surface a recoverable transient as a raw Kryo buffer underflow, on a supplier this
		// method has already half-rotated. The sibling reader AbstractMutationLog#getFirstVersionOf rejects the
		// same shape at the same threshold. Rejecting here, before anything below is reassigned, makes this
		// behave exactly like the non-existent-file path, whose `false` every caller already handles.
		if (nextWalFile.exists() && nextWalFile.length() > CUMULATIVE_CRC32_SIZE + TRANSACTION_PREFIX_SIZE) {
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
					// cumulative-capable: readWithChecksum()/markCumulativeChecksumStart() below fold
					// per-record checksums via combine()/update(long), not just stream real bytes
					this.storageSettings.createCumulativeChecksum(0L),
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
		long startPosition,
		long fileSize
	) {
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

		// full record = 4 (length prefix) + content + 8 (trailing cumulative CRC32C). A transaction counts as
		// present only once all three are on disk, and that holds whether or not the caller named a version:
		// `doAppend` writes the trailing checksum before the force that makes the transaction durable, so the
		// moment at which content is written and checksum is not belongs to an append still in progress -
		// nobody has been handed that version yet, so nobody can name it. Delivering a record on the strength
		// of its content alone would hand out bytes whose integrity cannot be checked.
		final int fullRecordLength = 4 + contentLength + CUMULATIVE_CRC32_SIZE;
		if (startPosition + fullRecordLength > fileSize) {
			// file is truncated — not enough room for the whole record
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
		// The framing prefix must agree with the record just read. It always can: the writer emits the 4-byte
		// prefix and the whole leading record from one ByteBuffer in a single write loop
		// (AbstractMutationLog#doAppend), and a file too short for the transaction was already rejected above -
		// so by this point every byte of both is on disk. A mismatch therefore means either genuine damage or a
		// reader that miscounted, and neither may be handled quietly: reporting an expected short read as a
		// fault is the failure this guard was once suspected of, but the cause turned out to be the reader
		// miscounting (see
		// documentation/adr/2026-09-13-off-record-reads-must-not-restore-an-invalidated-buffer-limit.md), which a
		// guard here would only have hidden.
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
