/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.store.offsetIndex;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.OffsetIndex.FileOffsetIndexStatistics;
import io.evitadb.store.offsetIndex.OffsetIndex.VolatileValueInformation;
import io.evitadb.store.offsetIndex.OffsetIndex.VolatileValues;
import io.evitadb.store.offsetIndex.exception.CorruptedRecordException;
import io.evitadb.store.offsetIndex.exception.IncompleteSerializationException;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.model.VersionedValue;
import io.evitadb.store.offsetIndex.stream.RandomAccessFileInputStream;
import io.evitadb.utils.Assert;
import io.evitadb.utils.BitUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.zip.CRC32C;

import static java.util.Optional.ofNullable;

/**
 * This service allows to (de)serialize {@link OffsetIndex} using {@link Kryo} from data file. Data file contains chain
 * of OffsetIndex fragments limited to {@link StorageOptions#outputBufferSize()} size. Each fragment except root one
 * points to previous fragment a OffsetIndex deserialization means all fragments needs to be read and deserialized.
 * OffsetIndex fragments contain:
 *
 * - insertedKeys + insertedLocations
 * - deletedLocations
 *
 * During deserialization deletedLocations take precedence over inserted locations and matching keys are skipped.
 *
 * Serialization allows serialization of entire {@link OffsetIndex} divided into requested parts due to size limitation
 * or allows serialization only of changes made to the {@link OffsetIndex} since it has been created (or deserialized).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
public class OffsetIndexSerializationService {
	/**
	 * Length of single mem table record is 12B: startingPosition (long), length (int)
	 */
	public static final int PREVIOUS_MEM_TABLE_FRAGMENT_POINTER_SIZE = 8 + 4;
	/**
	 * Length of single mem table record is 21B: primaryKey (long), class (byte), startingPosition (long), length (int)
	 */
	public static final int MEM_TABLE_RECORD_SIZE = 8 + 1 + 8 + 4;

	/**
	 * Estimates the size of the offset index file record based on the number of records.
	 *
	 * @param recordCount The number of records.
	 * @return The estimated size of the offset index file record.
	 */
	public static long countFileOffsetTableSize(
		int recordCount,
		@Nonnull StorageOptions storageOptions
	) {
		final long estimatedSize = (long) recordCount * (MEM_TABLE_RECORD_SIZE);
		return estimatedSize + (long) StorageRecord.getOverheadSize() * (PREVIOUS_MEM_TABLE_FRAGMENT_POINTER_SIZE + computeExpectedRecordCount(storageOptions, recordCount).fragments());
	}

	/**
	 * Verifies the integrity of the offset index file by calculating CRC32 checksum on each record content (if enabled)
	 * and performing control byte checks.
	 *
	 * @param offsetIndex The offset index to be verified.
	 * @param inputStream The input stream containing the offset index file.
	 * @param fileLength  The length of the offset index file.
	 * @return The statistics about the offset index file after verification.
	 */
	@Nonnull
	public static FileOffsetIndexStatistics verify(
		@Nonnull OffsetIndex offsetIndex,
		@Nonnull ObservableInput<?> inputStream,
		long fileLength
	) {
		final FileOffsetIndexStatistics result = new FileOffsetIndexStatistics(
			// use the latest possible version - we need actual count of records
			offsetIndex.count(Long.MAX_VALUE),
			offsetIndex.getTotalSizeBytes()
		);
		inputStream.resetToPosition(0);
		final CRC32C crc32C = offsetIndex.getStorageOptions().computeCRC32C() ? new CRC32C() : null;
		byte[] buffer = new byte[inputStream.getBuffer().length];
		int recCount = 0;
		long startPosition = 0;
		long prevTransactionId = 0;
		boolean firstTransaction = true;
		boolean transactionCleared = true;
		int accumulatedRecordLength = 0;
		do {
			recCount++;
			final int finalRecCount = recCount;

			try {
				inputStream.resetToPosition(startPosition);
				// computed record length without CRC32 checksum
				int recordLength = inputStream.readInt();
				final byte control = inputStream.readByte();
				final long catalogVersion = inputStream.readLong();
				final long finalStartPosition = startPosition;

				// verify that transactional id is monotonically increasing
				if (!firstTransaction && !(catalogVersion >= prevTransactionId)) {
					throw new CorruptedRecordException(
						"Transaction id record monotonic row is violated in record no. " + finalRecCount + " file position: [" + finalStartPosition + ", length " + recordLength + "B], previous transaction id is " + prevTransactionId + ", current is " + catalogVersion,
						prevTransactionId, catalogVersion
					);
				}
				// verify that transaction id stays the same within transaction block
				if (!transactionCleared && catalogVersion != prevTransactionId) {
					throw new CorruptedRecordException(
						"Transaction id was not cleared with control bit record id in record no. " + finalRecCount + " file position: [" + finalStartPosition + ", length " + recordLength + "B], previous transaction id is " + prevTransactionId + ", current is " + catalogVersion,
						prevTransactionId, catalogVersion
					);
				}

				ofNullable(crc32C).ifPresent(CRC32C::reset);
				// first 4 bytes of length are not part of the CRC check
				int processedRecordLength = StorageRecord.CRC_NOT_COVERED_HEAD;
				inputStream.resetToPosition(startPosition + processedRecordLength);
				// we have to avoid reading last 8 bytes of CRC check value
				while (processedRecordLength < recordLength - ObservableOutput.TAIL_MANDATORY_SPACE) {
					final int read = inputStream.read(buffer, 0, Math.min(recordLength - processedRecordLength - ObservableOutput.TAIL_MANDATORY_SPACE, buffer.length));
					ofNullable(crc32C).ifPresent(it -> it.update(buffer, 0, read));
					processedRecordLength += read;
				}
				// verify CRC32-C checksum
				if (crc32C != null) {
					crc32C.update(control);
					final long computedChecksum = crc32C.getValue();
					inputStream.resetToPosition(startPosition + recordLength - ObservableOutput.TAIL_MANDATORY_SPACE);
					final long storedChecksum = inputStream.readLong();
					processedRecordLength += ObservableOutput.TAIL_MANDATORY_SPACE;
					if (computedChecksum != storedChecksum) {
						throw new CorruptedRecordException(
							"Invalid checksum for record no. " + finalRecCount + " file position: [" + finalStartPosition + ", length " + recordLength + "B]", computedChecksum, storedChecksum
						);
					}
				}

				if (processedRecordLength != recordLength) {
					throw new CorruptedRecordException(
						"Record no. " + finalRecCount + " prematurely ended - file position: [" + finalStartPosition + ", length " + recordLength + "B]", processedRecordLength, recordLength
					);
				}

				startPosition += recordLength;
				prevTransactionId = catalogVersion;
				transactionCleared = BitUtils.isBitSet(control, StorageRecord.TRANSACTION_CLOSING_BIT);
				if (transactionCleared && catalogVersion > 0L) {
					firstTransaction = false;
				}
				accumulatedRecordLength += recordLength;
				if (!BitUtils.isBitSet(control, StorageRecord.CONTINUATION_BIT)) {
					result.registerRecord(accumulatedRecordLength);
					accumulatedRecordLength = 0;
				}
			} catch (KryoException ex) {
				throw new CorruptedRecordException(
					"Record no. " + finalRecCount + " cannot be read!", ex
				);
			}
		} while (fileLength > result.getTotalSize());

		return result;
	}

	/**
	 * Copies a snapshot of an offset index to an output stream. The output stream is not closed by this method.
	 * You are responsible for closing the output stream.
	 *
	 * @param offsetIndex    The original offset index to copy.
	 * @param inputStream    The input stream containing the offset index file.
	 * @param outputStream   The output stream to copy the snapshot to.
	 * @param catalogVersion The transaction ID of the snapshot.
	 * @return The length of the copied snapshot.
	 */
	@Nonnull
	public static FileLocationAndWrittenBytes copySnapshotTo(
		@Nonnull OffsetIndex offsetIndex,
		@Nonnull ObservableInput<RandomAccessFileInputStream> inputStream,
		@Nonnull OutputStream outputStream,
		long catalogVersion,
		@Nonnull Map<RecordKey, byte[]> valuesToOverride,
		@Nonnull VolatileValues volatileValues,
		@Nullable IntConsumer progressConsumer
	) {
		// we don't close neither input stream nor the output stream
		// input stream is still used in callee and the output stream is managed by the callee
		final ObservableOutput<OutputStream> output = new ObservableOutput<>(
			outputStream,
			offsetIndex.getStorageOptions().outputBufferSize(),
			offsetIndex.getStorageOptions().outputBufferSize(),
			0
		);
		if (offsetIndex.getStorageOptions().computeCRC32C()) {
			output.computeCRC32();
		}
		final Collection<Entry<RecordKey, FileLocation>> entries = offsetIndex.getEntries();
		final Collection<VersionedValue> nonFlushedValues = new ArrayList<>(entries.size());
		int counter = 0;
		final Iterator<Entry<RecordKey, FileLocation>> it = entries.iterator();
		while (it.hasNext()) {
			final Entry<RecordKey, FileLocation> entry = it.next();
			final Optional<VolatileValueInformation> volatileValueInfoRef = volatileValues.getVolatileValueInformation(
				catalogVersion, entry.getKey()
			);

			final FileLocation fileLocation;
			if (volatileValueInfoRef.isPresent()) {
				final VolatileValueInformation volatileValue = volatileValueInfoRef.get();
				if (volatileValue.removed() || volatileValue.addedInFuture()) {
					continue;
				} else {
					fileLocation = volatileValue.versionedValue().fileLocation();
				}
			} else {
				fileLocation = entry.getValue();
			}

			final StorageRecord<byte[]> theRecord = StorageRecord.read(
				inputStream,
				fileLocation,
				(stream, recordLength) -> stream.readBytes(recordLength - StorageRecord.OVERHEAD_SIZE)
			);

			final StorageRecord<byte[]> storageRecord = new StorageRecord<>(
				output, catalogVersion, !it.hasNext(),
				theOutput -> {
					final byte[] payload = valuesToOverride.getOrDefault(entry.getKey(), theRecord.payload());
					theOutput.write(payload);
					return payload;
				}
			);
			counter++;
			if (progressConsumer != null) {
				progressConsumer.accept(counter);
			}

			// finally, register non-flushed value
			final RecordKey key = entry.getKey();
			nonFlushedValues.add(
				new VersionedValue(
					key.primaryKey(), key.recordType(), storageRecord.fileLocation()
				)
			);
		}

		// now serialize the information about the values to the final record of the file and return its location
		return serialize(
			output,
			catalogVersion,
			nonFlushedValues,
			null,
			offsetIndex.getStorageOptions()
		);
	}

	/**
	 * Serializes entire {@link OffsetIndex} to the file. Only active keys are stored to the data file.
	 */
	@Nonnull
	public static FileLocationAndWrittenBytes serialize(
		@Nonnull ObservableOutput<?> output,
		long catalogVersion,
		@Nonnull Collection<VersionedValue> nonFlushedEntries,
		@Nullable FileLocation lastFileOffsetIndexLocation,
		@Nonnull StorageOptions storageOptions
	) {
		final int totalEntryCount = nonFlushedEntries.size();
		final Iterator<VersionedValue> entries = nonFlushedEntries.iterator();

		// start with full buffer
		output.flush();
		// this holds file location pointer to the last stored OffsetIndex fragment and is used to allow single direction pointing
		final AtomicReference<FileLocation> lastStorageRecordLocation = new AtomicReference<>(lastFileOffsetIndexLocation);
		final ExpectedCounts fileOffsetIndexRecordCount = computeExpectedRecordCount(storageOptions, nonFlushedEntries.size());
		for (int i = 0; i < fileOffsetIndexRecordCount.fragments; i++) {
			lastStorageRecordLocation.set(
				new StorageRecord<>(
					output,
					catalogVersion,
					i + 1 == fileOffsetIndexRecordCount.fragments(),
					stream -> {
						final FileLocation lsrl = lastStorageRecordLocation.get();
						if (lsrl == null) {
							// if no last record location is known - this is root mem table fragment
							stream.writeLong(-1);
							stream.writeInt(-1);
						} else {
							// if last record location is known - refer to it (chain the fragments)
							stream.writeLong(lsrl.startingPosition());
							stream.writeInt(lsrl.recordLength());
						}
						// iterate over entries (iterator is global and continues where last fragment finished)
						// we need to stop at the point when we know we would not be able to store any more records
						int cnt = 0;
						while (entries.hasNext() && cnt++ < fileOffsetIndexRecordCount.recordsInFragment()) {
							final VersionedValue nonFlushedValue = entries.next();
							stream.writeLong(nonFlushedValue.primaryKey());
							stream.writeByte(nonFlushedValue.recordType());
							final FileLocation fileLocation = nonFlushedValue.fileLocation();
							stream.writeLong(fileLocation.startingPosition());
							stream.writeInt(fileLocation.recordLength());
						}
						return null;
					}
				).fileLocation()
			);
		}


		Assert.isPremiseValid(
			!entries.hasNext(),
			() -> new IncompleteSerializationException(countEntries(entries))
		);

		// empty buffer
		output.flush();

		// return location of the last stored memory fragment file
		return new FileLocationAndWrittenBytes(
			lastStorageRecordLocation.get(),
			output.total()
		);
	}

	/**
	 * Deserializes {@link OffsetIndex} from the fragment identified by `fileLocation`.
	 */
	public static void deserialize(@Nonnull ObservableInput<?> input, @Nonnull FileLocation fileLocation, @Nonnull OffsetIndexBuilder OffsetIndexBuilder) {
		// this set holds all record keys that were removed
		final Set<RecordKey> removedEntries = new HashSet<>(16_384);
		// this variable holds location of the previous mem table fragment
		final AtomicReference<FileLocation> fileOffsetIndexFragmentLocation = new AtomicReference<>(fileLocation);
		boolean head = true;
		Long transactionId = null;
		do {
			final StorageRecord<Object> readRecord = StorageRecord.read(
				input,
				fileOffsetIndexFragmentLocation.get(),
				(stream, length) -> {
					// compute the length of the payload - this determines the moment when to stop reading
					final int effectiveLength = length - ObservableOutput.TAIL_MANDATORY_SPACE;
					// read previous memory fragment locations
					final long previousFileOffsetIndexFragmentPosition = stream.readLong();
					final int previousFileOffsetIndexFragmentLength = stream.readInt();
					if (previousFileOffsetIndexFragmentPosition == -1) {
						// if previous fragment position is -1, it means this fragment is root one
						fileOffsetIndexFragmentLocation.set(null);
					} else {
						fileOffsetIndexFragmentLocation.set(
							new FileLocation(previousFileOffsetIndexFragmentPosition, previousFileOffsetIndexFragmentLength)
						);
					}

					do {
						// read mem table records
						final long primaryKey = stream.readLong();
						final byte recordType = stream.readByte();
						final long startingPosition = stream.readLong();
						final int recordLength = stream.readInt();
						if (recordType < 0) {
							removedEntries.add(
								new RecordKey((byte) (recordType * -1), primaryKey)
							);
						} else {
							final RecordKey recordKey = new RecordKey(recordType, primaryKey);
							final boolean wasRemoved = removedEntries.contains(recordKey);
							final boolean wasUpdated = OffsetIndexBuilder.contains(recordKey);
							if (!wasRemoved && !wasUpdated) {
								OffsetIndexBuilder.register(
									recordKey,
									new FileLocation(startingPosition, recordLength)
								);
							}
						}

						// until we read as many bytes as effective fragment size
					} while (input.total() < effectiveLength);

					return null;
				});

			if (head) {
				Assert.isTrue(
					readRecord.closesTransaction(),
					"Head OffsetIndex must have a transaction finalization flag set, but it has not!"
				);
				head = false;
			} else {
				Assert.isTrue(
					readRecord.transactionId() <= transactionId,
					"Transaction ids must compose a monotonic row - but they don't: `" + transactionId + "` vs `" + readRecord.transactionId() + "`!"
				);
			}
			transactionId = readRecord.transactionId();

			// repeat reading fragments until root fragment is found
		} while (fileOffsetIndexFragmentLocation.get() != null);
	}

	/**
	 * Computes number of records that are required to store OffsetIndex record pointers of specified count.
	 */
	@Nonnull
	static ExpectedCounts computeExpectedRecordCount(@Nonnull StorageOptions storageOptions, int recordCount) {
		final int maxRecordCountPerStorageRecords = (storageOptions.outputBufferSize() - StorageRecord.getOverheadSize() - PREVIOUS_MEM_TABLE_FRAGMENT_POINTER_SIZE) / MEM_TABLE_RECORD_SIZE;
		return new ExpectedCounts(
			maxRecordCountPerStorageRecords == 0 ? 0 : (recordCount + maxRecordCountPerStorageRecords - 1) / maxRecordCountPerStorageRecords,
			maxRecordCountPerStorageRecords
		);
	}

	/**
	 * Count number of entries left in iterator.
	 */
	private static int countEntries(@Nonnull Iterator<VersionedValue> entries) {
		int cnt = 0;
		while (entries.hasNext()) {
			cnt++;
			entries.next();
		}
		return cnt;
	}

	/**
	 * Expected counts of records in the file.
	 *
	 * @param fragments         Number of fragments
	 * @param recordsInFragment Number of records in fragment
	 */
	record ExpectedCounts(
		int fragments,
		int recordsInFragment
	) {
	}

	/**
	 * File location and written bytes.
	 *
	 * @param fileLocation file location of the last memory fragment written
	 * @param writtenBytes number of total bytes written
	 */
	public record FileLocationAndWrittenBytes(
		@Nonnull FileLocation fileLocation,
		long writtenBytes
	) {
	}

}
