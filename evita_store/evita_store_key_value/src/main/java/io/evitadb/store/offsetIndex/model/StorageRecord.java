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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.offsetIndex.model;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.exception.CorruptedRecordException;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.evitadb.utils.BitUtils.isBitSet;
import static io.evitadb.utils.BitUtils.setBit;

/**
 * This class represents wrapper for any binary content, that needs to be stored / was already stored in file storage.
 * When this instance of this data wrapper exists it means data are present in the data store.
 *
 * @param <T>               Type of the payload that is stored in the data store.
 * @param transactionId     transaction id can be used to group multiple records into single transaction
 * @param closesTransaction set to TRUE when this record is the last record of the transaction
 * @param payload           this object represents the real payload of the record.
 * @param fileLocation      file location is a pointer to the location in the file that is occupied by binary content of
 *                          this record.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public record StorageRecord<T>(
	long transactionId,
	boolean closesTransaction,
	@Nullable T payload,
	@Nonnull FileLocation fileLocation
) {
	/**
	 * CRC not covered head 5B: length(int), control (byte)
	 */
	public static final int CRC_NOT_COVERED_HEAD = 4 + 1;
	/**
	 * Overhead is 22B: length(int), control (byte), transactionId (long), crc (long).
	 */
	public static final int OVERHEAD_SIZE = CRC_NOT_COVERED_HEAD + 8 + ObservableOutput.TAIL_MANDATORY_SPACE;
	/**
	 * First bit of control byte marks end of transaction record.
	 */
	public static final byte TRANSACTION_CLOSING_BIT = 1;
	/**
	 * Second bit of control byte marks that record spans with next record.
	 */
	public static final byte CONTINUATION_BIT = 2;
	/**
	 * Third bit of control byte marks that record has CRC32 calculated.
	 */
	public static final byte CRC32_BIT = 3;

	/**
	 * Returns count of bytes that are used by infrastructure informations of the record.
	 */
	public static int getOverheadSize() {
		return OVERHEAD_SIZE;
	}

	/**
	 * Constructor that is used for READING unknown record from the input stream. Constructor should be used for sequential
	 * reading of the data store contents when file offset index has been already read and we know locations of active
	 * records as well as their type.
	 *
	 * This constructor excepts that it is possible to resolve any file location to record type. If type cannot be resolved
	 * it is assumed record is "dead" and reading it's contents is skipped entirely.
	 */
	public static <T> StorageRecord<T> read(
		@Nonnull Kryo kryo,
		@Nonnull ObservableInput<?> input,
		@Nonnull Function<FileLocation, Class<T>> typeResolver
	) {
		final long startPosition = input.markStart();
		final int recordLength = input.readInt();
		final byte control = input.readByte();

		final FileLocation location = new FileLocation(startPosition, recordLength);
		final Class<T> payloadType = typeResolver.apply(location);

		if (payloadType == null) {
			// record is obsolete - it cannot be mapped to any know type
			input.skip(recordLength);
			final boolean closesTransaction = isBitSet(control, TRANSACTION_CLOSING_BIT);
			return new StorageRecord<>(
				-1L, closesTransaction, null, location
			);
		}

		final Supplier<T> payloadReader = () -> kryo.readObject(input, payloadType);
		return doReadStorageRecord(
			input,
			location,
			control,
			null,
			payloadReader
		);
	}

	/**
	 * Constructor that is used for READING known record from the input stream on known file location. Constructor should
	 * be used for random access reading of arbitrary records o reading lead record for the file offset index.
	 */
	public static <T> StorageRecord<T> read(
		@Nonnull ObservableInput<?> input,
		@Nonnull FileLocation location,
		@Nonnull BiFunction<ObservableInput<?>, Integer, T> reader
	) {
		input.seek(location);
		input.markStart();
		final int recordLength = input.readInt();
		byte control = input.readByte();

		final Supplier<T> payloadReader = () -> reader.apply(input, recordLength);
		//noinspection StringConcatenationMissingWhitespace
		return doReadStorageRecord(
			input,
			new FileLocation(location.startingPosition(), recordLength),
			control,
			fileLocation -> Assert.isPremiseValid(
				location.recordLength() == fileLocation.recordLength(),
				() -> new CorruptedRecordException(
					"Record length differs (" + fileLocation.recordLength() + "B, " +
						"expected " + location.recordLength() + "B) - it's probably corrupted!",
					location.recordLength(), fileLocation.recordLength()
				)
			),
			payloadReader
		);
	}

	/**
	 * Constructor that is used for READING known record from the input stream on known start position. Constructor
	 * should be used for sequential access reading of arbitrary records o reading lead record for the file offset index.
	 */
	public static <T> StorageRecord<T> read(
		@Nonnull ObservableInput<?> input,
		@Nonnull BiFunction<ObservableInput<?>, Integer, T> reader
	) {
		input.markStart();
		final int recordLength = input.readInt();
		byte control = input.readByte();

		return doReadStorageRecord(
			input,
			new FileLocation(input.position(), recordLength),
			control,
			null,
			() -> reader.apply(input, recordLength)
		);
	}

	/**
	 * Reads the {@link FileLocation} of a record from the specified {@link ObservableInput}.
	 *
	 * @param input          The input stream to read from.
	 * @param startPosition The starting position of the record in the file.
	 * @return The {@link FileLocation} of the record.
	 */
	@Nonnull
	public static FileLocation readFileLocation(
		@Nonnull ObservableInput<?> input,
		long startPosition
	) {
		input.seek(new FileLocation(startPosition, 4));
		input.markStart();
		final int recordLength = input.readInt();
		input.reset();
		return new FileLocation(startPosition, recordLength);
	}

	/**
	 * Reads a storage record from the input stream.
	 *
	 * @param input         The input stream to read from.
	 * @param location      The file location of the record.
	 * @param control       The control byte of the record.
	 * @param assertion     An optional assertion to be performed on the file location.
	 * @param payloadReader A supplier for reading the payload of the record.
	 * @param <T>           The type of the payload.
	 * @return The storage record read from the input stream.
	 */
	@Nonnull
	private static <T> StorageRecord<T> doReadStorageRecord(
		@Nonnull ObservableInput<?> input,
		@Nonnull FileLocation location,
		byte control,
		@Nullable Consumer<FileLocation> assertion,
		@Nonnull Supplier<T> payloadReader
	) {
		if (isBitSet(control, CONTINUATION_BIT)) {
			final ReadingContext context = new ReadingContext(
				location.startingPosition(), location.recordLength(), control
			);

			// record is active load and verify contents
			input.markPayloadStart(location.recordLength());
			final long transactionId = input.readLong();

			final T payload = input.doWithOnBufferOverflowHandler(
				new BufferOverflowReadHandler<>(context, transactionId),
				payloadReader
			);

			input.markEnd(context.getControlByte());

			final FileLocation fileLocation = new FileLocation(context.getStartPosition(), context.getRecordLength());

			if (assertion != null) {
				assertion.accept(fileLocation);
			}

			return new StorageRecord<>(
				transactionId, context.isClosesTransaction(), payload, fileLocation
			);
		} else {
			boolean closesTransaction = isBitSet(control, TRANSACTION_CLOSING_BIT);
			input.markPayloadStart(location.recordLength());
			final long transactionId = input.readLong();
			final T payload = payloadReader.get();
			input.markEnd(control);

			return new StorageRecord<>(
				transactionId, closesTransaction, payload,
				new FileLocation(location.startingPosition(), location.recordLength())
			);
		}
	}

	/**
	 * Writes the header of a storage record to the specified output.
	 *
	 * @param output The output stream to write the header to.
	 * @param transactionId The transaction id of the record.
	 */
	private static void writeHeader(@Nonnull ObservableOutput<?> output, long transactionId) {
		output.markStart();

		// we don't know the record length yet
		output.markRecordLengthPosition();
		// reserve int space for the record length and control byte
		output.writeInt(0);
		output.writeByte(0);

		output.markPayloadStart();
		// write transactional information
		output.writeLong(transactionId);
	}

	/**
	 * Writes a record to the specified output stream with the given transaction id and payload writer.
	 *
	 * @param output            The output stream to write the record to.
	 * @param transactionId     The transaction id of the record.
	 * @param closesTransaction A flag indicating whether the record closes the transaction.
	 * @param payloadWriter     The payload writer function that writes the payload to the output stream.
	 * @param <T>               The type of the payload.
	 * @param <OS>              The type of the output stream.
	 * @return A {@link PayloadWithFileLocation} object containing the payload and file location of the written record.
	 */
	@Nonnull
	private static <T, OS extends OutputStream> PayloadWithFileLocation<T> writeRecord(
		@Nonnull ObservableOutput<OS> output,
		long transactionId,
		boolean closesTransaction,
		@Nonnull Function<ObservableOutput<?>, T> payloadWriter
	) {
		final AtomicReference<FileLocationPointer> recordLocations = new AtomicReference<>(FileLocationPointer.INITIAL);
		return output.doWithOnBufferOverflowHandler(
			new OnBufferOverflowHandler<>(recordLocations, output, transactionId),
			() -> {
				// write storage record header
				writeHeader(output, transactionId);
				// finally, write payload
				final T thePayload = payloadWriter.apply(output);
				// compute crc32 and fill in record length
				final FileLocation theFileLocation = output.markEnd(setBit((byte) 0, TRANSACTION_CLOSING_BIT, closesTransaction));
				// return both
				return new PayloadWithFileLocation<>(thePayload, theFileLocation);
			}
		);
	}

	/**
	 * Writes a record to the specified output stream with the given transaction id and payload.
	 *
	 * @param output            The output stream to write the record to.
	 * @param transactionId     The transaction id of the record.
	 * @param closesTransaction A flag indicating whether the record closes the transaction.
	 * @param kryo              The Kryo instance used for serialization.
	 * @param payload           The payload object to be written.
	 * @param <T>               The type of the payload.
	 * @param <OS>              The type of the output stream.
	 * @return The file location object specifying the position and length of the written record in the file.
	 */
	@Nonnull
	private static <T, OS extends OutputStream> FileLocation writeRecord(
		@Nonnull ObservableOutput<OS> output,
		long transactionId,
		boolean closesTransaction,
		@Nonnull Kryo kryo,
		@Nonnull T payload
	) {
		final AtomicReference<FileLocationPointer> recordLocations = new AtomicReference<>(FileLocationPointer.INITIAL);
		return output.doWithOnBufferOverflowHandler(
			new OnBufferOverflowHandler<>(recordLocations, output, transactionId),
			() -> {
				// write storage record header
				writeHeader(output, transactionId);
				// finally, write payload
				kryo.writeObject(output, payload);
				// compute crc32 and fill in record length
				final byte controlByte = setBit((byte) 0, TRANSACTION_CLOSING_BIT, closesTransaction);
				final FileLocation completeRecordLocation = output.markEnd(controlByte);
				// return file length that possibly spans multiple records
				return recordLocations.get().computeOverallFileLocation(completeRecordLocation);
			}
		);
	}

	/**
	 * Constructor that is used for WRITING the record to the passed output stream.
	 */
	public <OS extends OutputStream> StorageRecord(
		@Nonnull Kryo kryo,
		@Nonnull ObservableOutput<OS> output,
		long transactionId,
		boolean closesTransaction,
		@Nonnull T payload
	) {
		this(
			transactionId,
			closesTransaction,
			payload,
			writeRecord(output, transactionId, closesTransaction, kryo, payload)
		);
	}

	/**
	 * Constructor that is used for WRITING the record to the passed output stream.
	 */
	public <OS extends OutputStream> StorageRecord(
		@Nonnull ObservableOutput<OS> output,
		long transactionId,
		boolean closesTransaction,
		@Nonnull Function<ObservableOutput<?>, T> payloadWriter
	) {
		this(
			transactionId,
			closesTransaction,
			writeRecord(output, transactionId, closesTransaction, payloadWriter)
		);
	}

	private StorageRecord(
		long transactionId,
		boolean closesTransaction,
		@Nonnull PayloadWithFileLocation<T> tPayloadWithFileLocation
	) {
		this(
			transactionId, closesTransaction, tPayloadWithFileLocation.payload(), tPayloadWithFileLocation.fileLocation()
		);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		StorageRecord<?> that = (StorageRecord<?>) o;
		return transactionId == that.transactionId &&
			closesTransaction == that.closesTransaction &&
			Objects.equals(payload, that.payload) &&
			Objects.equals(fileLocation, that.fileLocation);
	}

	/*
		PRIVATE METHODS
	 */

	@Override
	public int hashCode() {
		int result = (int) (transactionId ^ (transactionId >>> 32));
		result = 31 * result + (closesTransaction ? 1 : 0);
		result = 31 * result + (payload != null ? payload.hashCode() : 0);
		result = 31 * result + fileLocation.hashCode();
		return result;
	}

	/**
	 * Represents a payload with its corresponding file location information.
	 *
	 * @param payload      The payload.
	 * @param fileLocation The file location.
	 * @param <T>          The type of the payload.
	 */
	private record PayloadWithFileLocation<T>(
		@Nonnull T payload,
		@Nonnull FileLocation fileLocation
	) {
	}

	/**
	 * The ReadingContext class represents the context of reading a record from the input stream.
	 * It stores information such as the start position, record length, control byte, and flags indicating if
	 * the record closes a transaction or is a continuation record.
	 */
	@Getter
	private static class ReadingContext {
		/**
		 * Represents the start position of a record in the input stream.
		 * The start position indicates the byte offset from the beginning of the stream where the record starts.
		 */
		private final long startPosition;
		/**
		 * The recordLength variable represents the length of a record in the ReadingContext class.
		 * It stores the number of bytes that make up the record.
		 */
		private int recordLength;
		/**
		 * The controlByte variable represents the control byte of a record in the ReadingContext class.
		 * It stores information about the record, such as transaction closing, continuation, etc.
		 */
		private byte controlByte;
		/**
		 * The closesTransaction variable represents a flag indicating whether a record is the last record of a transaction.
		 **/
		private boolean closesTransaction;
		/**
		 * The continuationRecord flag indicates that the payload continues in the next record.
		 */
		private boolean continuationRecord;

		public ReadingContext(long startPosition, int recordLength, byte control) {
			this.startPosition = startPosition;
			this.recordLength = recordLength;
			this.controlByte = control;
			this.closesTransaction = isBitSet(control, TRANSACTION_CLOSING_BIT);
			this.continuationRecord = isBitSet(control, CONTINUATION_BIT);
		}

		public void updateWithNextRecord(int recordLength, byte control) {
			this.recordLength += recordLength;
			this.controlByte = control;
			this.closesTransaction = isBitSet(control, TRANSACTION_CLOSING_BIT);
			this.continuationRecord = isBitSet(control, CONTINUATION_BIT);
		}

	}

	/**
	 * This class represents a pointer to a specific location within a file. The record is used to chain the file
	 * locations of continuous records and compute overall file location (size) of the continuous records.
	 *
	 * @param previousPointer The previous pointer.
	 * @param fileLocation    The file location.
	 */
	private record FileLocationPointer(
		@Nullable FileLocationPointer previousPointer,
		@Nullable FileLocation fileLocation
	) {
		public static final FileLocationPointer INITIAL = new FileLocationPointer(null, null);

		/**
		 * Checks if the file location pointer is empty.
		 *
		 * @return {@code true} if the file location pointer is empty, {@code false} otherwise.
		 */
		public boolean isEmpty() {
			return this.fileLocation == null;
		}

		/**
		 * Computes the overall file location by traversing the file location pointers and accumulating the record lengths.
		 *
		 * @param lastRecordLocation The file location of the last record.
		 * @return The overall file location.
		 */
		@Nonnull
		public FileLocation computeOverallFileLocation(@Nonnull FileLocation lastRecordLocation) {
			if (isEmpty()) {
				return lastRecordLocation;
			} else {
				int lengthAcc = lastRecordLocation.recordLength();
				long startingPosition = lastRecordLocation.startingPosition();
				FileLocationPointer current = this;
				while (!current.isEmpty()) {
					final FileLocation currentFileLocation = current.fileLocation;
					lengthAcc += currentFileLocation.recordLength();
					startingPosition = currentFileLocation.startingPosition();
					current = current.previousPointer;
				}
				return new FileLocation(startingPosition, lengthAcc);
			}
		}
	}

	/**
	 * BufferOverflowReadHandler is used to handle situation when the payload didn't fit into the buffer and needs
	 * to be read and joined from multiple records.
	 *
	 * @param context       the reading context
	 * @param transactionId the transaction id of the record
	 * @param <IS>          the type of the InputStream
	 */
	private record BufferOverflowReadHandler<IS extends InputStream>(
		ReadingContext context,
		long transactionId
	) implements Consumer<ObservableInput<IS>> {

		@Override
		public void accept(ObservableInput<IS> observableInput) {
			observableInput.markEnd(context.getControlByte());

			observableInput.markStart();
			final int continuingRecordLength = observableInput.readInt();
			final byte continuingControl = observableInput.readByte();

			observableInput.markPayloadStart(continuingRecordLength);
			final long continuingTransactionId = observableInput.readLong();

			Assert.isPremiseValid(
				transactionId == continuingTransactionId,
				() -> new CorruptedRecordException(
					"Transaction id differs in continuous record (" +
						transactionId + " vs. " + continuingTransactionId +
						"). This is not expected!", transactionId, continuingTransactionId
				)
			);
			context.updateWithNextRecord(continuingRecordLength, continuingControl);
		}

	}

	/**
	 * OnBufferOverflowHandler is used to handle situation when the payload can't fit into the buffer and needs
	 * to be written into multiple records.
	 *
	 * @param <OO> the type of the ObservableOutput used in the record
	 */
	private record OnBufferOverflowHandler<OO extends ObservableOutput<?>>(
		AtomicReference<FileLocationPointer> recordLocations,
		OO output,
		long transactionId
	) implements Consumer<OO> {

		@Override
		public void accept(OO filledOutput) {
			final byte controlByte = setBit((byte) 0, CONTINUATION_BIT, true);
			final FileLocation incompleteRecordLocation = filledOutput.markEnd(controlByte);
			// register file location of the continuous record
			recordLocations.set(new FileLocationPointer(recordLocations.get(), incompleteRecordLocation));
			// write storage record header for another record
			writeHeader(output, transactionId);
		}
	}
}
