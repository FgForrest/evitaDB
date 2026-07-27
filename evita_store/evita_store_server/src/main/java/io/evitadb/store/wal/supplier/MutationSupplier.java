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
 * Reading stops when there are no more complete transactions available, or when the
 * {@link #requestedCatalogVersion} is reached (if {@code avoidPartiallyFilledBuffer} is enabled).
 *
 * **End-of-stream vs. failure.** When {@code avoidPartiallyFilledBuffer} is enabled, the caller is asserting
 * that {@link #requestedCatalogVersion} has already been durably written and may only lag in what the
 * reader's cached file-length view can currently observe. Until that version has actually been delivered,
 * any inability to advance — a truncated read, a not-yet-visible next record, or running out of WAL files —
 * is surfaced as a {@link WriteAheadLogCorruptedException} rather than
 * a silent {@code null}, so such a caller cannot mistake "not visible yet" for "nothing left to process".
 * Once the requested version has been delivered, or when no target version is enforced (a greedy read), the
 * same conditions are treated as a normal, graceful end of the stream.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public final class MutationSupplier<T extends Mutation> extends AbstractMutationSupplier<T> {
	/**
	 * The target catalog version up to which mutations should be read. Used in conjunction with
	 * {@link #avoidPartiallyFilledBuffer} to determine when to stop reading — if the flag is set,
	 * the supplier will not advance past this version even if more data is available in the WAL file.
	 */
	private final long requestedCatalogVersion;

	/**
	 * Creates a new forward mutation supplier that reads transactions sequentially starting from
	 * the given catalog version up to the requested catalog version.
	 *
	 * @param catalogVersion             the catalog version to start reading from
	 * @param requestedCatalogVersion    the target catalog version to read up to (used with
	 *                                   {@code avoidPartiallyFilledBuffer})
	 * @param walFileNameProvider         function to generate WAL file names from file index
	 * @param catalogStoragePath          the directory where WAL files are stored
	 * @param storageSettings             storage configuration including checksum and compression factories
	 * @param walFileIndex                the index of the WAL file to start reading from
	 * @param catalogKryoPool             pool of Kryo instances for deserialization
	 * @param transactionLocationsCache   cache of transaction locations within WAL files
	 * @param avoidPartiallyFilledBuffer  when {@code true}, stops reading when the requested catalog
	 *                                    version is reached to avoid incomplete buffer fills
	 * @param onClose                     optional callback to run when the supplier is closed
	 * @param walKind                     flavor of WAL being read — stamped on every corruption exception
	 */
	public MutationSupplier(
		long catalogVersion,
		long requestedCatalogVersion,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path catalogStoragePath,
		@Nonnull StorageSettings storageSettings,
		int walFileIndex,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache,
		boolean avoidPartiallyFilledBuffer,
		@Nullable Runnable onClose,
		@Nonnull WalKind walKind
	) {
		super(
			catalogVersion, walFileNameProvider, catalogStoragePath, storageSettings,
			walFileIndex, catalogKryoPool, transactionLocationsCache,
			avoidPartiallyFilledBuffer, onClose, walKind
		);
		this.requestedCatalogVersion = requestedCatalogVersion;
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
	 *         one, or if — in {@code avoidPartiallyFilledBuffer} mode — the stream cannot advance to the next
	 *         transaction before {@link #requestedCatalogVersion} has been delivered
	 */
	@Nullable
	@Override
	public T get() {
		if (this.transactionMutation == null) {
			return null;
		} else if (this.transactionMutationRead == 0) {
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
				final boolean mayEndGracefully = !this.avoidPartiallyFilledBuffer
					|| lastDeliveredVersion >= this.requestedCatalogVersion;
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
					final long currentFileLength = this.walFile.length();
					// check if there is enough room for another transaction (content + WAL tail marker)
					if (currentFileLength <= this.filePosition + AbstractMutationLog.WAL_TAIL_LENGTH) {
						if (!moveToNextWalFile(1)) {
							if (mayEndGracefully) {
								return null;
							}
							// the current WAL ends without room for another transaction before the caller's
							// requested version was reached, and there is no next file — the requested
							// transaction is missing or truncated, surface it instead of ending silently
							throw new WriteAheadLogCorruptedException(
								this.walKind,
								"Reached the end of " + this.walKind.fileLabel + " `" + this.walFile.getName() +
									"` at position " + this.filePosition + " before the requested catalog version " +
									this.requestedCatalogVersion + " was read (last delivered version " +
									lastDeliveredVersion + ").",
								this.walKind.corruptedLabel + ": requested version missing before end of file"
							);
						}
					}
					this.transactionMutation = readAndRecordTransactionMutation(
						this.filePosition, currentFileLength
					).orElse(null);

					// Guard against partially written transactions and an ObservableInput buffer
					// misalignment issue: when the remaining file data doesn't fully fill the read
					// buffer, concurrent writes can cause pointer misalignment. This check ensures
					// we only proceed when sufficient data is available.
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
						throw new WriteAheadLogCorruptedException(
							this.walKind,
							"Reached a truncated or unreadable transaction record in " + this.walKind.fileLabel +
								" `" + this.walFile.getName() + "` at position " + this.filePosition +
								" before the requested catalog version " + this.requestedCatalogVersion +
								" was read (last delivered version " + lastDeliveredVersion + ").",
							this.walKind.corruptedLabel + ": requested version not fully on disk before end of data"
						);
					}
					final long requiredEndPosition = this.transactionMutation.getTransactionSpan().endPosition();
					final boolean canProceed;
					if (this.avoidPartiallyFilledBuffer) {
						// the caller guarantees the requested transaction is written: proceed once its version is
						// within the requested range AND its CONTENT is on disk (the trailing checksum of a
						// just-written tail may still be lagging the reader's file-length view)
						canProceed = this.transactionMutation.getVersion() <= this.requestedCatalogVersion
							&& currentFileLength >= requiredEndPosition - AbstractMutationLog.CUMULATIVE_CRC32_SIZE;
					} else {
						// standard mode: proceed as long as the full transaction (incl. checksum) is written
						canProceed = currentFileLength >= requiredEndPosition;
					}
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
				} catch (Exception ex) {
					if (mayEndGracefully) {
						// everything the caller requested has been delivered (or we are reading greedily): a torn
						// or not-yet-durable tail is a legitimate graceful end-of-stream
						return null;
					}
					// a transaction the caller explicitly requested could not be read — surface it loudly instead
					// of reporting an exhausted stream that the caller would misread as "nothing left to process"
					throw new WriteAheadLogCorruptedException(
						this.walKind,
						"Failed to read " + this.walKind.fileLabel + " `" + this.walFile.getName() +
							"` while advancing towards requested catalog version " + this.requestedCatalogVersion +
							" (last delivered version " + lastDeliveredVersion + ") at position " + this.filePosition + ".",
						this.walKind.corruptedLabel + ": read failed before the requested version was reached",
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
