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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.store.catalog.wal.VersionSource;
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException;
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException.WalKind;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.model.StorageRecord.StorageRecordWithChecksum;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.wal.AbstractMutationLog;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Supplies {@link Mutation} objects from a Write-Ahead Log (WAL) file in **forward order** — from the oldest
 * transaction towards the most recent. This is the counterpart to {@link ReverseMutationSupplier}, which reads
 * backwards.
 *
 * The forward reading strategy works in three phases per transaction:
 *
 * 1. **Transaction header** — the first call returns the {@link TransactionMutationWithLocation} itself.
 * 2. **Sequential mutation delivery** — subsequent calls read individual mutations in the order they were
 *    written, optionally computing CRC32C checksums for each.
 * 3. **Checksum validation and advancement** — after all mutations in a transaction are consumed, the
 *    trailing cumulative CRC32C checksum is read and validated. The supplier then advances to the next
 *    transaction in the current WAL file, or rotates to the next WAL file if the current one is exhausted.
 *
 * Reading stops when there are no more complete transactions available, or when the caller's
 * {@link #requestedVersion} has been reached.
 *
 * **End-of-stream vs. failure.** A caller that names a {@link #requestedVersion} is asserting that the version
 * has already been durably written. Until it has actually been delivered, any inability to advance — a truncated
 * read, a next record that is not there, or running out of WAL files — is surfaced as an exception rather than a
 * silent {@code null}, so such a caller cannot mistake "not there" for "nothing left to process". Once the
 * requested version has been delivered, or when none was named (a greedy read), the same conditions are treated
 * as a normal, graceful end of the stream.
 *
 * **Which exception depends on who named the version**, not on what the log looks like: see
 * {@link #missingBoundedVersion(String, String)}. A bound the engine chose for itself is one it has already
 * observed to be durable, so its absence is a {@link WriteAheadLogCorruptedException}; a bound that arrived from
 * a client is an {@link io.evitadb.exception.EvitaInvalidUsageException}, because nobody promised it and no
 * operator can act on it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public final class MutationSupplier<T extends Mutation> extends AbstractMutationSupplier<T> {
	/**
	 * Creates a new forward mutation supplier that reads transactions sequentially starting from
	 * the given catalog version up to the requested catalog version.
	 *
	 * @param catalogVersion             the catalog version to start reading from
	 * @param requestedVersion           the target catalog version to read up to, which the caller asserts is
	 *                                   durably written; {@code null} for a greedy read
	 * @param walFileNameProvider         function to generate WAL file names from file index
	 * @param catalogStoragePath          the directory where WAL files are stored
	 * @param storageSettings             storage configuration including checksum and compression factories
	 * @param walFileIndex                the index of the WAL file to start reading from
	 * @param catalogKryoPool             pool of Kryo instances for deserialization
	 * @param transactionLocationsCache   cache of transaction locations within WAL files
	 * @param versionSource               who chose the versions this read is bounded by — decides whether a
	 *                                    version that cannot be found is damage or a bad argument
	 * @param onClose                     optional callback to run when the supplier is closed
	 * @param walKind                     flavor of WAL being read — stamped on every corruption exception
	 */
	public MutationSupplier(
		long catalogVersion,
		@Nullable Long requestedVersion,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path catalogStoragePath,
		@Nonnull StorageSettings storageSettings,
		int walFileIndex,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache,
		@Nonnull VersionSource versionSource,
		@Nullable Runnable onClose,
		@Nonnull WalKind walKind
	) {
		super(
			catalogVersion, walFileNameProvider, catalogStoragePath, storageSettings,
			walFileIndex, catalogKryoPool, transactionLocationsCache,
			requestedVersion, versionSource, onClose, walKind
		);
	}

	/**
	 * Returns the next mutation in forward order. The method operates in three phases per transaction:
	 *
	 * 1. **Phase 1** ({@code transactionMutationRead == 0}): returns the {@link TransactionMutationWithLocation}
	 *    itself as the first element of the transaction.
	 * 2. **Phase 2** ({@code transactionMutationRead <= mutationCount}): reads individual mutations
	 *    sequentially, computing and combining CRC32C checksums when enabled.
	 * 3. **Phase 3** ({@code transactionMutationRead > mutationCount}): reads and validates the trailing
	 *    cumulative CRC32C checksum, then advances to the next transaction or WAL file.
	 *
	 * @return the next mutation in forward order, or {@code null} if all transactions have been exhausted
	 *         or the file is incomplete
	 * @throws WriteAheadLogCorruptedException if the trailing cumulative checksum does not match the computed
	 *         one, or if the stream cannot advance to the next transaction before a caller-named
	 *         {@link #requestedVersion} has been delivered
	 */
	@Nullable
	@Override
	public T get() {
		if (this.transactionMutation == null) {
			return null;
		} else if (this.transactionMutationRead == 0) {
			// the version ceiling has to be tested here as well as in the advance below, because the transaction
			// the CONSTRUCTOR landed on never passes through that test - it is delivered straight out of Phase 1.
			// When the start version sits above the ceiling the interval is empty, and without this the reader
			// answers it with the start transaction and all of its mutations, which is the one thing a bound is
			// supposed to prevent
			if (this.requestedVersion != null && this.transactionMutation.getVersion() > this.requestedVersion) {
				return null;
			}
			// Phase 1: return the transaction mutation header
			this.transactionMutationRead++;
			//noinspection unchecked
			return (T) this.transactionMutation;
		} else {
			if (this.transactionMutationRead <= this.transactionMutation.getMutationCount()) {
				// Phase 2: read individual mutations sequentially
				this.transactionMutationRead++;
				//noinspection unchecked
				return (T) readMutation();
			} else {
				// Phase 3: all mutations read — validate the trailing cumulative checksum and advance to the
				// next transaction. A read failure here (a missing trailing checksum, or a genuinely
				// underflowing next transaction) is a legitimate end-of-stream ONLY when we have already
				// delivered the caller's requested version, or when reading greedily; before that point a
				// requested version could not be read and MUST surface. Swallowing it into a silent "no more
				// data" is what makes the trunk-incorporation stage misread a durably-written transaction as
				// "already processed", hang, and spin forever.
				final long lastDeliveredVersion = this.transactionMutation.getVersion();
				final Long theRequestedVersion = this.requestedVersion;
				final boolean mayEndGracefully = theRequestedVersion == null
					|| lastDeliveredVersion >= theRequestedVersion;
				try {
					final long readCumulativeChecksum = getObservableInput().simpleLongRead();
					final Checksum checksum = Objects.requireNonNull(this.cumulativeChecksum);
					Assert.isPremiseValid(
						checksum.equalsTo(readCumulativeChecksum),
						() -> new WriteAheadLogCorruptedException(
							this.walKind,
							this.walFile.toPath(),
							this.transactionMutation.getTransactionSpan().endPosition(),
							checksum.getValue(),
							readCumulativeChecksum
						)
					);
					this.transactionMutation.withCumulativeChecksum(readCumulativeChecksum);
					// feed the validated checksum back into cumulative — it is part of the WAL format
					// and participates in the cumulative computation for the next transaction
					checksum.update(readCumulativeChecksum);

					this.filePosition = this.transactionMutation.getTransactionSpan().endPosition();
					long currentFileLength = this.walFile.length();
					// check if there is enough room for another transaction (content + WAL tail marker)
					if (currentFileLength <= this.filePosition + AbstractMutationLog.WAL_TAIL_LENGTH) {
						if (!moveToNextWalFile(1)) {
							if (mayEndGracefully) {
								return null;
							}
							// the current WAL ends without room for another transaction before the caller's
							// requested version was reached, and there is no next file — the requested
							// transaction is missing or truncated, surface it instead of ending silently
							throw missingBoundedVersion(
								"Reached the end of " + this.walKind.fileLabel + " `" + this.walFile.getName() +
									"` at position " + this.filePosition + " before the requested catalog version " +
									theRequestedVersion + " was read (last delivered version " +
									lastDeliveredVersion + ").",
								"requested version missing before end of file"
							);
						}
						// moveToNextWalFile swapped `walFile` and reset `filePosition` to the start of the new
						// file, so the length captured above describes the PREVIOUS file - typically the full
						// rotation threshold. Left stale, it is handed to readAndRecordTransactionMutation and to
						// the canProceed test below as the new file's size, which disables both of their
						// end-of-file guards for the first record of every rotated file.
						currentFileLength = this.walFile.length();
					}
					this.transactionMutation = readAndRecordTransactionMutation(
						this.filePosition, currentFileLength
					).orElse(null);

					// Guard against a partially written or not-yet-durable next transaction:
					// readAndRecordTransactionMutation returns empty when the file doesn't yet hold the full
					// record it needs (see its own end-of-file guards); this treats that as a legitimate end
					// only once mayEndGracefully holds.
					if (this.transactionMutation == null) {
						if (mayEndGracefully) {
							// greedy/recovery read, or the caller's requested version has already been
							// delivered — a truncated or not-yet-durable next record is a legitimate end
							return null;
						}
						// the next transaction on the way to the caller's requested version is not (yet)
						// sufficiently on disk; ending the stream silently here would be indistinguishable
						// from "nothing left to process" to a caller that already believes this version was
						// durably written, causing it to finalize prematurely at a stale version instead of
						// retrying — surface it loudly, exactly like the sibling end-of-data branches above
						throw missingBoundedVersion(
							"Reached a truncated or unreadable transaction record in " + this.walKind.fileLabel +
								" `" + this.walFile.getName() + "` at position " + this.filePosition +
								" before the requested catalog version " + theRequestedVersion +
								" was read (last delivered version " + lastDeliveredVersion + ").",
							"requested version not fully on disk before end of data"
						);
					}
					final long requiredEndPosition = this.transactionMutation.getTransactionSpan().endPosition();
					// the whole transaction - trailing cumulative checksum included - must be written before it
					// is delivered. Naming a version does not lower that bar: the writer emits the checksum
					// before the force that makes the transaction durable, so a record that is content-complete
					// and checksum-short belongs to an append still in flight, which no caller can yet name. What
					// a named version does change is the upper end: transactions past it are not this caller's.
					final boolean canProceed = currentFileLength >= requiredEndPosition
						&& (theRequestedVersion == null
						|| this.transactionMutation.getVersion() <= theRequestedVersion);
					if (canProceed) {
						this.transactionMutationRead = 1;
						//noinspection unchecked
						return (T) this.transactionMutation;
					} else {
						return null;
					}
				} catch (WriteAheadLogCorruptedException ex) {
					// a cumulative-checksum mismatch is a hard corruption regardless of position — never swallow it
					throw ex;
				} catch (EvitaInvalidUsageException ex) {
					// this method's own "the version you named is not there" verdict for a client-supplied bound
					// comes back through here, and it is already the right answer — re-wrapping it below would
					// bury it inside a second exception. But it is NOT the only thing of this type that can
					// arrive: `kryo.readClassAndObject` raises EvitaInvalidUsageException subclasses of its own
					// (UnsupportedDataTypeException among them) from deeper in the read path, and for a reader
					// that named no version those are simply where the data stops making sense. So the graceful
					// arm still wins wherever it applies, and only a caller still owed a version sees this.
					if (mayEndGracefully) {
						return null;
					}
					throw ex;
				} catch (Exception ex) {
					if (mayEndGracefully) {
						// everything the caller requested has been delivered (or we are reading greedily): a torn
						// or not-yet-durable tail is a legitimate graceful end-of-stream
						return null;
					}
					// a transaction the caller explicitly requested could not be read — surface it loudly instead
					// of reporting an exhausted stream that the caller would misread as "nothing left to process"
					throw missingBoundedVersion(
						"Failed to read " + this.walKind.fileLabel + " `" + this.walFile.getName() +
							"` while advancing towards requested catalog version " + theRequestedVersion +
							" (last delivered version " + lastDeliveredVersion + ") at position " + this.filePosition + ".",
						"read failed before the requested version was reached",
						ex
					);
				}
			}
		}
	}

	/**
	 * Reads the next individual mutation from the WAL file input stream. When cumulative checksum
	 * tracking is enabled, the mutation is read with checksum computation and the result is combined
	 * into the running cumulative checksum. When checksums are disabled, a plain read is performed.
	 *
	 * @return the deserialized mutation (never {@code null})
	 */
	@Nonnull
	private Mutation readMutation() {
		final StorageRecord<Mutation> storageRecord;
		if (this.cumulativeChecksum == null) {
			// no checksum tracking — plain read
			storageRecord = StorageRecord.read(
				getObservableInput(), (stream, length) -> (Mutation) this.kryo.readClassAndObject(stream)
			);
		} else {
			// read with checksum and combine into running cumulative checksum
			final StorageRecordWithChecksum<Mutation> storageRecordWithChecksum = StorageRecord.readWithChecksum(
				getObservableInput(), (stream, length) -> (Mutation) this.kryo.readClassAndObject(stream)
			);
			storageRecord = storageRecordWithChecksum.record();
			this.cumulativeChecksum.combine(
				storageRecordWithChecksum.checksum(),
				storageRecord.fileLocation().recordLength()
			);
		}
		return Objects.requireNonNull(storageRecord.payload());
	}

}
