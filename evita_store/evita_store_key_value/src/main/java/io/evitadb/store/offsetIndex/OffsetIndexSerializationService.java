/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.offsetIndex;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.OffsetIndex.FileOffsetIndexBuilder;
import io.evitadb.store.offsetIndex.exception.IncompleteSerializationException;
import io.evitadb.store.offsetIndex.model.NonFlushedValue;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.utils.Assert;
import lombok.Data;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
	public static final OffsetIndexSerializationService INSTANCE = new OffsetIndexSerializationService();
	/**
	 * Length of single mem table record is 12B: startingPosition (long), length (int)
	 */
	public static final int PREVIOUS_MEM_TABLE_FRAGMENT_POINTER_SIZE = 8 + 4;
	/**
	 * Length of single mem table record is 21B: primaryKey (long), class (byte), startingPosition (long), length (int)
	 */
	public static final int MEM_TABLE_RECORD_SIZE = 8 + 1 + 8 + 4;

	/**
	 * Serializes entire {@link OffsetIndex} to the file. Only active keys are stored to the data file.
	 */
	@Nonnull
	public FileLocation serialize(@Nonnull OffsetIndex fileOffsetIndex, @Nonnull ObservableOutput<?> output, long transactionId) {
		final Collection<NonFlushedValue> nonFlushedEntries = fileOffsetIndex.getNonFlushedEntries();
		final Iterator<NonFlushedValue> entries = nonFlushedEntries.iterator();

		// start with full buffer
		output.flush();
		// this holds file location pointer to the last stored OffsetIndex fragment and is used to allow single direction pointing
		final AtomicReference<FileLocation> lastStorageRecordLocation = new AtomicReference<>(fileOffsetIndex.getFileOffsetIndexLocation());
		final ExpectedCounts fileOffsetIndexRecordCount = computeExpectedRecordCount(fileOffsetIndex.getOptions(), nonFlushedEntries.size());
		for (int i = 0; i < fileOffsetIndexRecordCount.getFragments(); i++) {
			lastStorageRecordLocation.set(
				new StorageRecord<>(
					output, OffsetIndex.SINGLE_NODE_ID, transactionId, i + 1 == fileOffsetIndexRecordCount.getFragments(),
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
						while (entries.hasNext() && cnt++ < fileOffsetIndexRecordCount.getRecordsInFragment()) {
							final NonFlushedValue nonFlushedValue = entries.next();
							stream.writeLong(nonFlushedValue.primaryKey());
							stream.writeByte(nonFlushedValue.recordType());
							final FileLocation fileLocation = nonFlushedValue.fileLocation();
							stream.writeLong(fileLocation.startingPosition());
							stream.writeInt(fileLocation.recordLength());
						}
						return fileOffsetIndex;
					}
				).getFileLocation()
			);
		}


		Assert.isPremiseValid(
			!entries.hasNext(),
			() -> new IncompleteSerializationException(countEntries(entries))
		);

		// empty buffer
		output.flush();

		// return location of the last stored memory fragment file
		return lastStorageRecordLocation.get();
	}

	/**
	 * Deserializes {@link OffsetIndex} from the fragment identified by `fileLocation`.
	 */
	public void deserialize(@Nonnull ObservableInput<?> input, @Nonnull FileLocation fileLocation, @Nonnull FileOffsetIndexBuilder fileOffsetIndexBuilder) {
		// this set holds all record keys that were removed
		final Set<RecordKey> removedEntries = new HashSet<>(16_384);
		// this variable holds location of the previous mem table fragment
		final AtomicReference<FileLocation> fileOffsetIndexFragmentLocation = new AtomicReference<>(fileLocation);
		boolean head = true;
		Long transactionId = null;
		do {
			final StorageRecord<Object> readRecord = new StorageRecord<>(
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
							final boolean wasUpdated = fileOffsetIndexBuilder.contains(recordKey);
							if (!wasRemoved && !wasUpdated) {
								fileOffsetIndexBuilder.register(
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
					readRecord.isClosesTransaction(),
					"Head OffsetIndex must have transaction finalization flag set, but it has not!"
				);
				head = false;
			} else {
				Assert.isTrue(
					readRecord.getTransactionId() < transactionId || readRecord.getTransactionId() == 0L && transactionId == 0L,
					"Transaction ids must compose a monotonic row - but they don't:  `" + transactionId + "` vs `" + readRecord.getTransactionId() + "`!"
				);
			}
			transactionId = readRecord.getTransactionId();

			// repeat reading fragments until root fragment is found
		} while (fileOffsetIndexFragmentLocation.get() != null);
	}

	/**
	 * Computes number of records that are required to store OffsetIndex record pointers of specified count.
	 */
	ExpectedCounts computeExpectedRecordCount(@Nonnull StorageOptions storageOptions, int recordCount) {
		final int maxRecordCountPerStorageRecords = (storageOptions.outputBufferSize() - StorageRecord.getOverheadSize() - PREVIOUS_MEM_TABLE_FRAGMENT_POINTER_SIZE) / MEM_TABLE_RECORD_SIZE;
		return new ExpectedCounts(
			maxRecordCountPerStorageRecords == 0 ? 0 : (recordCount + maxRecordCountPerStorageRecords - 1) / maxRecordCountPerStorageRecords,
			maxRecordCountPerStorageRecords
		);
	}

	/**
	 * Count number of entries left in iterator.
	 */
	private static int countEntries(Iterator<NonFlushedValue> entries) {
		int cnt = 0;
		while (entries.hasNext()) {
			cnt++;
			entries.next();
		}
		return cnt;
	}

	@Data
	static class ExpectedCounts {
		private final int fragments;
		private final int recordsInFragment;

	}

}
