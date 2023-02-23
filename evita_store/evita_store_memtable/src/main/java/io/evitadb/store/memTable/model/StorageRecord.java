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

package io.evitadb.store.memTable.model;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.memTable.exception.CorruptedRecordException;
import io.evitadb.store.memTable.stream.RandomAccessFileInputStream;
import io.evitadb.store.model.FileLocation;
import io.evitadb.utils.Assert;
import lombok.Data;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * This class represents wrapper for any binary content, that needs to be stored / was already stored in file storage.
 * When this instance of this data wrapper exists it means data are present in the data store.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Data
public class StorageRecord<T> {
	/**
	 * CRC not covered head 5B: length(int), control (byte)
	 *
	 * TOBEDONE JNO - we need to cover also control byte by the CRC - what if the transaction bit is corrupted?!
	 */
	public static final int CRC_NOT_COVERED_HEAD = 4 + 1;
	/**
	 * Overhead is 22B: length(int), control (byte), nodeId (byte), transactionId (long), crc (long).
	 */
	public static final int OVERHEAD_SIZE = CRC_NOT_COVERED_HEAD + 1 + 8 + ObservableOutput.TAIL_MANDATORY_SPACE;
	/**
	 * First bit of control byte marks end of transaction record.
	 */
	public static final byte TRANSACTION_CLOSING_BIT = 1;
	/**
	 * Second bit of control byte marks that record spans with next record.
	 */
	public static final byte CONTINUATION_BIT = 2;

	/**
	 * Reserved space for the source node identification (if/when we go to distributed multi-master data store).
	 */
	private final byte nodeId;
	/**
	 * Transaction id (monotonically increased number).
	 */
	private final long transactionId;
	/**
	 * Set to TRUE when this record closes the transaction.
	 */
	private final boolean closesTransaction;
	/**
	 * This object represents the real payload of the record.
	 */
	private final T payload;
	/**
	 * File location is a pointer to the location in the file that is occupied by binary content of this record.
	 */
	private final FileLocation fileLocation;

	/**
	 * Returns count of bytes that are used by infrastructure informations of the record.
	 */
	public static int getOverheadSize() {
		return OVERHEAD_SIZE;
	}

	/**
	 * Sets bit at specified position to 1 in an arbitrary byte value. Only bit at specified index is changed, other
	 * bits stay the same.
	 */
	public static byte setBit(byte encoded, byte index, boolean bit) {
		byte bits = (byte) ((byte) 1 << index);
		if (bit) {
			return (byte) (encoded | bits);
		} else {
			return (byte) (encoded & (~bits));
		}
	}

	/**
	 * Reads bit at specified position and returns TRUE if bit is set to 1.
	 */
	public static boolean isBitSet(byte encoded, byte index) {
		return ((encoded & 0xff) & (1 << index)) != 0;
	}

	/**
	 * Constructor that is used for WRITING the record to the passed output stream.
	 */
	public StorageRecord(@Nonnull Kryo kryo, @Nonnull ObservableOutput<?> output, byte nodeId, long transactionId, boolean closesTransaction, @Nonnull T payload) {
		this.nodeId = nodeId;
		this.closesTransaction = closesTransaction;
		this.transactionId = transactionId;
		this.payload = payload;
		final AtomicReference<FileLocationPointer> recordLocations = new AtomicReference<>(FileLocationPointer.INITIAL);

		this.fileLocation = output.doWithOnBufferOverflowHandler(
			filledOutput -> {
				final FileLocation incompleteRecordLocation = filledOutput.markEnd(setBit((byte) 0, CONTINUATION_BIT, true));
				// register file location of the continuous record
				recordLocations.set(new FileLocationPointer(recordLocations.get(), incompleteRecordLocation));
				// write storage record header for another record
				writeHeader(output, nodeId, transactionId);
			},
			() -> {
				// write storage record header
				writeHeader(output, nodeId, transactionId);
				// finally, write payload
				kryo.writeObject(output, payload);
				// compute crc32 and fill in record length
				final FileLocation completeRecordLocation = output.markEnd(setBit((byte) 0, TRANSACTION_CLOSING_BIT, closesTransaction));
				// return file length that possibly spans multiple records
				return recordLocations.get().computeOverallFileLocation(completeRecordLocation);
			}
		);
	}

	/**
	 * Constructor that is used for WRITING the record to the passed output stream.
	 */
	public StorageRecord(@Nonnull ObservableOutput<?> output, byte nodeId, long transactionId, boolean closesTransaction, @Nonnull Function<ObservableOutput<?>, T> payloadWriter) {
		this.nodeId = nodeId;
		this.transactionId = transactionId;
		this.closesTransaction = closesTransaction;
		final AtomicReference<FileLocationPointer> recordLocations = new AtomicReference<>(FileLocationPointer.INITIAL);

		final PayloadWithFileLocation<T> result = output.doWithOnBufferOverflowHandler(
			filledOutput -> {
				final FileLocation incompleteRecordLocation = filledOutput.markEnd(setBit((byte) 0, CONTINUATION_BIT, true));
				// register file location of the continuous record
				recordLocations.set(new FileLocationPointer(recordLocations.get(), incompleteRecordLocation));
				// write storage record header
				writeHeader(output, nodeId, transactionId);
			},
			() -> {
				// write storage record header
				writeHeader(output, nodeId, transactionId);
				// finally, write payload
				final T thePayload = payloadWriter.apply(output);
				// compute crc32 and fill in record length
				final FileLocation theFileLocation = output.markEnd(setBit((byte) 0, TRANSACTION_CLOSING_BIT, closesTransaction));
				// return both
				return new PayloadWithFileLocation<>(thePayload, theFileLocation);
			}
		);

		this.fileLocation = result.fileLocation();
		this.payload = result.payload();
	}

	/**
	 * Constructor that is used for READING unknown record from the input stream. Constructor should be used for sequential
	 * reading of the data store contents when MEMTABLE has been already read and we know locations of active records as
	 * well as their type.
	 *
	 * This constructor excepts that it is possible to resolve any file location to record type. If type cannot be resolved
	 * it is assumed record is "dead" and reading it's contents is skipped entirely.
	 */
	public StorageRecord(@Nonnull Kryo kryo, @Nonnull ObservableInput<?> input, @Nonnull Function<FileLocation, Class<T>> typeResolver) {
		final long startPosition = input.markStart();
		final int recordLength = input.readInt();
		final byte control = input.readByte();
		final ReadingContext context = new ReadingContext(
			startPosition, recordLength, control
		);

		final FileLocation leadingRecordLocation = new FileLocation(startPosition, recordLength);
		final Class<T> payloadType = typeResolver.apply(new FileLocation(startPosition, recordLength));

		if (payloadType == null) {
			// record is obsolete - it cannot be mapped to any know type
			input.skip(recordLength);
			this.nodeId = -1;
			this.transactionId = -1;
			this.closesTransaction = context.isClosesTransaction();
			this.payload = null;
			this.fileLocation = leadingRecordLocation;
		} else if (isBitSet(control, CONTINUATION_BIT)) {
			// record is active load and verify contents
			input.markPayloadStart(recordLength);
			this.nodeId = input.readByte();
			this.transactionId = input.readLong();

			this.payload = input.doWithOnBufferOverflowHandler(
				observableInput -> {
					input.markEnd();

					input.markStart();
					final int continuingRecordLength = input.readInt();
					final byte continuingControl = input.readByte();

					input.markPayloadStart(continuingRecordLength);
					final byte continuingNodeId = input.readByte();
					final long continuingTransactionId = input.readLong();

					Assert.isPremiseValid(
						nodeId == continuingNodeId,
						() -> new CorruptedRecordException("Node id differs in continuous record. This is not expected!", nodeId, continuingNodeId)
					);
					Assert.isPremiseValid(
						transactionId == continuingTransactionId,
						() -> new CorruptedRecordException("Transaction id differs in continuous record. This is not expected!", transactionId, continuingTransactionId)
					);
					context.updateWithNextRecord(continuingRecordLength, continuingControl);
				},
				() -> kryo.readObject(input, payloadType)
			);

			this.closesTransaction = context.isClosesTransaction();
			this.fileLocation = new FileLocation(context.getStartPosition(), context.getRecordLength());
		} else {
			this.closesTransaction = isBitSet(control, TRANSACTION_CLOSING_BIT);
			// record is active load and verify contents
			input.markPayloadStart(recordLength);
			this.nodeId = input.readByte();
			this.transactionId = input.readLong();
			this.payload = kryo.readObject(input, payloadType);
			this.fileLocation = leadingRecordLocation;
		}

		input.markEnd();
	}

	/**
	 * Constructor that is used for READING known record from the input stream on known file location. Constructor should
	 * be used for random access reading of arbitrary records o reading lead record for the MEMTABLE.
	 */
	public StorageRecord(@Nonnull ObservableInput<RandomAccessFileInputStream> input, @Nonnull FileLocation location, @Nonnull BiFunction<ObservableInput<RandomAccessFileInputStream>, Integer, T> reader) {
		input.seek(location);
		input.markStart();
		final int recordLength = input.readInt();
		byte control = input.readByte();

		if (isBitSet(control, CONTINUATION_BIT)) {
			final ReadingContext context = new ReadingContext(
				location.startingPosition(), recordLength, control
			);

			input.markPayloadStart(recordLength);
			this.nodeId = input.readByte();
			this.transactionId = input.readLong();
			this.payload = input.doWithOnBufferOverflowHandler(
				observableInput -> {
					input.markEnd();

					input.markStart();
					final int continuingRecordLength = input.readInt();
					final byte continuingControl = input.readByte();

					input.markPayloadStart(continuingRecordLength);
					final byte continuingNodeId = input.readByte();
					final long continuingTransactionId = input.readLong();

					Assert.isPremiseValid(
						nodeId == continuingNodeId,
						() -> new CorruptedRecordException("Node id differs in continuous record (" + nodeId + " vs. " + continuingControl + "). This is not expected!", nodeId, continuingNodeId)
					);
					Assert.isPremiseValid(
						transactionId == continuingTransactionId,
						() -> new CorruptedRecordException("Transaction id differs in continuous record (" + transactionId + " vs. " + continuingTransactionId + "). This is not expected!", transactionId, continuingTransactionId)
					);
					context.updateWithNextRecord(continuingRecordLength, continuingControl);
				},
				() -> reader.apply(input, recordLength)
			);

			input.markEnd();

			this.fileLocation = new FileLocation(context.getStartPosition(), context.getRecordLength());
			this.closesTransaction = context.isClosesTransaction();

			Assert.isPremiseValid(
				location.equals(this.fileLocation),
				() -> new CorruptedRecordException(
					"Record length differs (" + this.fileLocation.recordLength() + "B, " +
						"expected " + location.recordLength() + "B) - it's probably corrupted!",
					location.recordLength(), this.fileLocation.recordLength()
				)
			);
		} else {
			this.closesTransaction = isBitSet(control, TRANSACTION_CLOSING_BIT);
			this.fileLocation = location;
			input.markPayloadStart(location.recordLength());
			this.nodeId = input.readByte();
			this.transactionId = input.readLong();
			this.payload = reader.apply(input, recordLength);
			input.markEnd();

		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		StorageRecord<?> that = (StorageRecord<?>) o;
		return nodeId == that.nodeId && transactionId == that.transactionId && closesTransaction == that.closesTransaction && Objects.equals(payload, that.payload) && Objects.equals(fileLocation, that.fileLocation);
	}

	@Override
	public int hashCode() {
		return Objects.hash(nodeId, transactionId, closesTransaction, payload, fileLocation);
	}

	/*
		PRIVATE METHODS
	 */

	private void writeHeader(@Nonnull ObservableOutput<?> output, byte nodeId, long transactionId) {
		output.markStart();

		// we don't know the record length yet
		output.markRecordLengthPosition();
		// reserve int space for the record length and control byte
		output.writeInt(0);
		output.writeByte(0);

		output.markPayloadStart();
		// write transactional information
		output.writeByte(nodeId);
		output.writeLong(transactionId);
	}

	private record PayloadWithFileLocation<T>(T payload, FileLocation fileLocation) {
	}

	@Data
	private static class ReadingContext {
		private final long startPosition;
		private int recordLength;
		private boolean closesTransaction;
		private boolean continuationRecord;

		public ReadingContext(long startPosition, int recordLength, byte control) {
			this.startPosition = startPosition;
			this.recordLength = recordLength;
			this.closesTransaction = isBitSet(control, TRANSACTION_CLOSING_BIT);
			this.continuationRecord = isBitSet(control, CONTINUATION_BIT);
		}

		public void updateWithNextRecord(int recordLength, byte control) {
			this.recordLength += recordLength;
			this.closesTransaction = isBitSet(control, TRANSACTION_CLOSING_BIT);
			this.continuationRecord = isBitSet(control, CONTINUATION_BIT);
		}

	}

	private static class FileLocationPointer {
		public static final FileLocationPointer INITIAL = new FileLocationPointer();
		@Getter private final FileLocationPointer previousPointer;
		@Getter private final FileLocation fileLocation;

		private FileLocationPointer() {
			this.previousPointer = null;
			this.fileLocation = null;
		}

		public FileLocationPointer(FileLocationPointer previousPointer, FileLocation fileLocation) {
			this.previousPointer = previousPointer;
			this.fileLocation = fileLocation;
		}

		public boolean isEmpty() {
			return this.fileLocation == null;
		}

		public boolean hasPrevious() {
			return previousPointer != null && !previousPointer.isEmpty();
		}

		public FileLocation computeOverallFileLocation(FileLocation lastRecordLocation) {
			if (isEmpty()) {
				return lastRecordLocation;
			} else {
				int lengthAcc = lastRecordLocation.recordLength();
				long startingPosition = lastRecordLocation.startingPosition();
				FileLocationPointer current = this;
				while (!current.isEmpty()) {
					final FileLocation currentFileLocation = current.getFileLocation();
					lengthAcc += currentFileLocation.recordLength();
					startingPosition = currentFileLocation.startingPosition();
					current = current.getPreviousPointer();
				}
				return new FileLocation(startingPosition, lengthAcc);
			}
		}
	}

}
