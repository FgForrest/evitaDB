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
	 *
	 * jej i při čtení započítat do CRC výpočtu i když jakoby ani čtený není, protože je na začátku záznamu
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
	 *
	 * TODO JNO - implement
	 */
	public static final byte CRC32_BIT = 3;

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

		final Supplier<T> payloadReader = () -> reader.apply(input, recordLength);
		return doReadStorageRecord(
			input,
			new FileLocation(input.position(), recordLength),
			control,
			null,
			payloadReader
		);
	}

	/**
	 * TODO JNO - document me
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
	 * TODO JNO - document me
	 *
	 * @param output
	 * @param transactionId
	 * @param closesTransaction
	 * @param payloadWriter
	 * @return
	 * @param <T>
	 * @param <OS>
	 */
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
	 * TODO JNO - document me
	 * @param output
	 * @param transactionId
	 * @param closesTransaction
	 * @param kryo
	 * @param payload
	 * @return
	 * @param <T>
	 * @param <OS>
	 */
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
	public <OS extends OutputStream> StorageRecord(@Nonnull Kryo kryo, @Nonnull ObservableOutput<OS> output, long transactionId, boolean closesTransaction, @Nonnull T payload) {
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
	public <OS extends OutputStream> StorageRecord(@Nonnull ObservableOutput<OS> output, long transactionId, boolean closesTransaction, @Nonnull Function<ObservableOutput<?>, T> payloadWriter) {
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

	private record PayloadWithFileLocation<T>(T payload, FileLocation fileLocation) {
	}

	private static class ReadingContext {
		@Getter private final long startPosition;
		@Getter private int recordLength;
		@Getter private byte controlByte;
		@Getter private boolean closesTransaction;
		@Getter private boolean continuationRecord;

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
	 * TODO JNO - DOCUMENT ME
	 * @param previousPointer
	 * @param fileLocation
	 */
	private record FileLocationPointer(
		@Nullable FileLocationPointer previousPointer,
		@Nullable FileLocation fileLocation
	) {
		public static final FileLocationPointer INITIAL = new FileLocationPointer(null, null);

		public boolean isEmpty() {
			return this.fileLocation == null;
		}

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
	 * TODO JNO - document me
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
				() -> new CorruptedRecordException("Transaction id differs in continuous record (" + transactionId + " vs. " + continuingTransactionId + "). This is not expected!", transactionId, continuingTransactionId)
			);
			context.updateWithNextRecord(continuingRecordLength, continuingControl);
		}

	}

	/**
	 * TODO JNO - document me
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
