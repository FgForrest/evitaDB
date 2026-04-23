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
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.model.StorageRecord.StorageRecordWithChecksum;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.store.wal.AbstractMutationLog;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

import static io.evitadb.store.wal.AbstractMutationLog.CUMULATIVE_CRC32_SIZE;

/**
 * Supplies {@link Mutation} objects from a Write-Ahead Log (WAL) file in **reverse order** — from the most
 * recent transaction backwards. This is the counterpart to {@link MutationSupplier}, which reads forward.
 *
 * The reverse reading strategy works in three phases per transaction:
 *
 * 1. **Position mapping** — on first access, all mutation positions within the current transaction are
 *    pre-scanned forward (via {@link #getMappedPositions()}) so that individual mutations can be addressed
 *    by random access in any order.
 * 2. **Reverse iteration** — individual mutations are returned in reverse order using the pre-mapped
 *    {@link FileLocation} entries.
 * 3. **Checksum validation** — after all mutations in a transaction have been read, the cumulative CRC32C
 *    checksum is reconstructed from individual mutation checksums and validated against the stored value.
 *
 * Once a transaction is fully consumed, the supplier navigates to the previous transaction
 * (potentially crossing WAL file boundaries) and repeats the process.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public final class ReverseMutationSupplier<T extends Mutation> extends AbstractMutationSupplier<T> {
	/**
	 * Index tracking the current position within the transaction's mutations. Counts down from
	 * {@code mutationCount} (returning the {@link TransactionMutationWithLocation} itself) through
	 * individual mutations (indices {@code mutationCount-1} down to {@code 0}), and finally to {@code -1}
	 * which triggers checksum validation and transition to the previous transaction.
	 */
	private int mutationIndex;
	/**
	 * Cached pre-scanned file locations and their corresponding CRC32C checksums for the current
	 * transaction. Lazily initialized on first access via {@link #getMappedPositions()} and reset
	 * to {@code null} when transitioning to the previous transaction.
	 */
	@Nullable private FileLocationsWithChecksums mappedPositionsWithChecksums;

	/**
	 * Creates a new reverse mutation supplier that reads transactions backwards starting from
	 * the given catalog version.
	 *
	 * @param catalogVersion             the catalog version to start reading from (in reverse)
	 * @param walFileNameProvider         function to generate WAL file names from file index
	 * @param catalogStoragePath          the directory where WAL files are stored
	 * @param storageSettings             storage configuration including checksum and compression factories
	 * @param walFileIndex                the index of the WAL file to start reading from
	 * @param catalogKryoPool             pool of Kryo instances for deserialization
	 * @param transactionLocationsCache   cache of transaction locations within WAL files
	 * @param onClose                     optional callback to run when the supplier is closed
	 */
	public ReverseMutationSupplier(
		long catalogVersion,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path catalogStoragePath,
		@Nonnull StorageSettings storageSettings,
		int walFileIndex,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache,
		@Nullable Runnable onClose
	) {
		super(
			catalogVersion, walFileNameProvider, catalogStoragePath, storageSettings,
			walFileIndex, catalogKryoPool, transactionLocationsCache,
			false, onClose
		);
		this.mutationIndex = this.transactionMutation == null ?
			0: this.transactionMutation.getMutationCount();
	}

	/**
	 * Returns the next mutation in reverse order. The method operates in three phases per transaction:
	 *
	 * 1. **Phase 1** ({@code mutationIndex == mutationCount}): returns the {@link TransactionMutationWithLocation}
	 *    itself and captures the pre-transaction cumulative checksum into {@code txChecksums[0]}.
	 * 2. **Phase 2** ({@code mutationIndex >= 0}): reads individual mutations in reverse order using
	 *    pre-mapped file positions and stores each mutation's CRC32C checksum in {@code txChecksums[i+1]}.
	 * 3. **Phase 3** ({@code mutationIndex < 0}): reconstructs the cumulative checksum by combining all
	 *    individual checksums in forward order, validates it against the stored value, then transitions
	 *    to the previous transaction via recursive tail-call.
	 *
	 * @return the next mutation in reverse order, or {@code null} if all transactions have been exhausted
	 */
	@Nullable
	@Override
	public T get() {
		if (this.transactionMutation == null) {
			return null;
		} else {
			final FileLocationsWithChecksums mappedPositionsWithChecksums = getMappedPositions();
			final FileLocation[] mappedPositions = mappedPositionsWithChecksums.locations();
			// checksum array layout: [0] = cumulative checksum before tx, [1..N] = per-mutation checksums
			final long[] txChecksums = mappedPositionsWithChecksums.checksums();
			if (this.mutationIndex == this.transactionMutation.getMutationCount()) {
				// Phase 1: return the transaction mutation itself and capture pre-tx cumulative checksum
				this.mutationIndex--;
				txChecksums[0] = Objects.requireNonNull(this.cumulativeChecksum).getValue();
				//noinspection unchecked
				return (T) this.transactionMutation;
			} else if (this.mutationIndex >= 0) {
				// Phase 2: read individual mutations in reverse using pre-mapped positions
				Assert.isPremiseValid(
					this.observableInput != null,
					"Observable input stream is not available!"
				);
				final FileLocation currentLocation = mappedPositions[this.mutationIndex];
				final StorageRecordWithChecksum<Mutation> storageRecord = StorageRecord.readWithChecksum(
					this.observableInput,
					currentLocation,
					(stream, length, control) -> (Mutation) this.kryo.readClassAndObject(stream)
				);
				// store individual mutation checksum at offset +1 (slot 0 holds cumulative)
				txChecksums[this.mutationIndex + 1] = storageRecord.checksum();
				this.mutationIndex--;
				//noinspection unchecked
				return (T) storageRecord.record().payload();
			} else {
				// Phase 3: all mutations read — reconstruct and validate cumulative checksum
				final ObservableInput<RandomAccessFileInputStream> theInput = getObservableInput();
				// seek to the trailing cumulative checksum stored at end of transaction
				theInput.seekWithUnknownLength(
					this.transactionMutation.getTransactionSpan().endPosition() -
						CUMULATIVE_CRC32_SIZE
				);
				final long readCumulativeChecksum = theInput.simpleLongRead();
				// reconstruct the cumulative checksum by replaying individual checksums in forward order
				final Checksum theCumulativeChecksum = Objects.requireNonNull(this.cumulativeChecksum);
				theCumulativeChecksum.reset(txChecksums[0]);
				for (int i = 0; i < this.transactionMutation.getMutationCount(); i++) {
					theCumulativeChecksum.combine(
						txChecksums[i + 1],
						mappedPositions[i].recordLength()
					);
				}
				// validate reconstructed checksum against the stored value
				Assert.isPremiseValid(
					theCumulativeChecksum.equalsTo(readCumulativeChecksum),
					() -> new WriteAheadLogCorruptedException(
						this.walFile.toPath(),
						this.transactionMutation.getTransactionSpan().endPosition(),
						theCumulativeChecksum.getValue(),
						readCumulativeChecksum
					)
				);
				this.transactionMutation.withCumulativeChecksum(readCumulativeChecksum);
				// transition to the previous transaction
				this.transactionMutation = findPreviousTransactionMutation(this.transactionMutation);
				this.mappedPositionsWithChecksums = null;
				this.mutationIndex = this.transactionMutation == null ?
					0 : this.transactionMutation.getMutationCount();
				// recursive tail-call to start delivering mutations from the previous transaction
				return get();
			}
		}
	}

	/**
	 * Lazily builds and returns the complete map of mutation file locations within the current transaction.
	 * The mapping process scans forward through the transaction:
	 *
	 * 1. Reads the leading {@link TransactionMutationWithLocation}'s {@link FileLocation} (skipping the
	 *    4-byte content length prefix).
	 * 2. Sequentially reads each individual mutation's {@link FileLocation} by advancing past each record.
	 * 3. Validates that the total scanned length matches the expected transaction span (minus the trailing
	 *    cumulative checksum).
	 *
	 * The result is cached in {@link #mappedPositionsWithChecksums} and includes a pre-allocated checksum
	 * array with {@code mutationCount + 1} slots (slot 0 for the cumulative pre-tx checksum, slots 1..N
	 * for individual mutation checksums).
	 *
	 * @return the cached file locations paired with their checksum array
	 */
	@Nonnull
	private FileLocationsWithChecksums getMappedPositions() {
		if (this.mappedPositionsWithChecksums == null) {
			Assert.isPremiseValid(
				this.observableInput != null,
				"Observable input stream is not available!"
			);
			Assert.isPremiseValid(
				this.transactionMutation != null,
				"Transaction mutation is not available!"
			);
			// skip the 4-byte content length prefix to read the leading transaction mutation's location
			final FileLocation transactionMutationLocation = StorageRecord.readFileLocation(
				this.observableInput,
				this.transactionMutation.getTransactionSpan().startingPosition() + 4
			);
			// sequentially scan all individual mutation locations following the leading mutation
			final int mutationCount = this.transactionMutation.getMutationCount();
			final FileLocation[] newlyMappedPositions = new FileLocation[mutationCount];
			long startingPosition = transactionMutationLocation.startingPosition() +
				transactionMutationLocation.recordLength();
			for (int i = 0; i < mutationCount; i++) {
				final FileLocation mutationLocation = StorageRecord.readFileLocation(
					this.observableInput,
					startingPosition
				);
				newlyMappedPositions[i] = mutationLocation;
				startingPosition = mutationLocation.startingPosition() + mutationLocation.recordLength();
			}
			// validate that scanned positions cover exactly the transaction content (excluding trailing checksum)
			final long scannedLength = startingPosition -
				this.transactionMutation.getTransactionSpan().startingPosition();
			final int expectedLength = this.transactionMutation.getTransactionSpan().recordLength() -
				CUMULATIVE_CRC32_SIZE;
			Assert.isPremiseValid(
				scannedLength == expectedLength,
				() -> new WriteAheadLogCorruptedException(
					"Transaction mutation span is not fully mapped!",
					"Transaction mutation span is not fully mapped (" +
						scannedLength + " vs. " + expectedLength + ")!"
				)
			);
			this.mappedPositionsWithChecksums = new FileLocationsWithChecksums(
				newlyMappedPositions,
				// +1 for the cumulative pre-transaction checksum stored at index 0
				new long[newlyMappedPositions.length + 1]
			);
		}
		return this.mappedPositionsWithChecksums;
	}

	/**
	 * Locates and returns the transaction mutation immediately preceding the given one (by catalog version).
	 * The algorithm:
	 *
	 * 1. If the current transaction starts at the very beginning of the WAL file (position ==
	 *    {@link AbstractMutationLog#CUMULATIVE_CRC32_SIZE CUMULATIVE_CRC32_SIZE}), attempts to move to
	 *    the previous WAL file. Returns {@code null} if no previous file exists.
	 * 2. Looks up the nearest cached {@link TransactionLocations} entry for the target version.
	 * 3. Scans forward from that cached position, reading and recording each transaction until the target
	 *    version is found or the end of the file is reached.
	 *
	 * @param currentTxMutation the transaction mutation whose predecessor is being sought
	 * @return the previous transaction mutation, or {@code null} if the beginning of all WAL files is reached
	 */
	@Nullable
	private TransactionMutationWithLocation findPreviousTransactionMutation(
		@Nonnull TransactionMutationWithLocation currentTxMutation
	) {
		if (currentTxMutation.getTransactionSpan().startingPosition() == CUMULATIVE_CRC32_SIZE) {
			// current transaction is the first in this WAL file — try the previous file
			if (!moveToNextWalFile(-1)) {
				return null;
			}
		}
		final long previousCatalogVersion = currentTxMutation.getVersion() - 1;
		// find the nearest known position from cache to avoid scanning from the file start
		this.filePosition = this.transactionLocationsCache.compute(
			this.walFileIndex,
			(index, existing) ->
				existing == null || existing.wasCut() ? new TransactionLocations() : existing
		).findNearestLocation(previousCatalogVersion);

		Assert.isPremiseValid(
			this.observableInput != null,
			"Observable input stream is not available!"
		);

		// seek back by CUMULATIVE_CRC32_SIZE to read the 8-byte cumulative checksum preceding the transaction
		this.observableInput.seekWithUnknownLength(this.filePosition - CUMULATIVE_CRC32_SIZE);
		final long initialCumulativeChecksum = this.observableInput.simpleLongRead();

		// reset checksum to the stored value AND register it as input data (the checksum itself is
		// part of the WAL format and participates in the cumulative computation)
		final Checksum theCumulativeChecksum = Objects.requireNonNull(this.cumulativeChecksum);
		theCumulativeChecksum.reset(initialCumulativeChecksum);
		theCumulativeChecksum.update(initialCumulativeChecksum);

		// scan forward through transactions until we find the target version or reach EOF
		final long walFileLength = this.walFile.length();
		Optional<TransactionMutationWithLocation> examinedTxMutation =
			readAndRecordTransactionMutation(this.filePosition, walFileLength);
		while (
			examinedTxMutation
				.map(
					it -> it.getVersion() < previousCatalogVersion &&
						this.filePosition + it.getTransactionSpan().recordLength() +
							AbstractMutationLog.WAL_TAIL_LENGTH < walFileLength)
				.orElse(false)
		) {
			// advance file position past the current transaction
			final FileLocation transactionSpan = examinedTxMutation.get().getTransactionSpan();
			this.filePosition += transactionSpan.recordLength();
			// read the cumulative checksum preceding the next transaction
			this.observableInput.seekWithUnknownLength(this.filePosition - CUMULATIVE_CRC32_SIZE);
			final long readCumulativeChecksum = this.observableInput.simpleLongRead();
			theCumulativeChecksum.reset(readCumulativeChecksum);
			theCumulativeChecksum.update(readCumulativeChecksum);
			// read the next transaction's content length and leading mutation
			examinedTxMutation = readAndRecordTransactionMutation(this.filePosition, walFileLength);
			// check if the file has enough room for the full transaction plus tail
			if (walFileLength < this.filePosition + transactionSpan.recordLength() +
				AbstractMutationLog.WAL_TAIL_LENGTH) {
				break;
			}
		}
		return examinedTxMutation
			.filter(it -> it.getVersion() == previousCatalogVersion)
			.orElse(null);
	}

	/**
	 * Pairs the pre-scanned mutation {@link FileLocation} entries with a checksum array used during
	 * reverse reading. The checksums array has {@code locations.length + 1} slots: index 0 holds the
	 * cumulative checksum preceding the transaction, and indices 1..N hold individual mutation checksums.
	 *
	 * @param locations the file locations of individual mutations within a transaction
	 * @param checksums the checksum array populated during reverse mutation reading
	 */
	private record FileLocationsWithChecksums(
		@Nonnull FileLocation[] locations,
		@Nonnull long[] checksums
	) {}

}
