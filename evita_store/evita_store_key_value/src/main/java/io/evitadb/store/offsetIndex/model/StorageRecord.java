/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.offsetIndex.model;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.function.TriFunction;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.exception.CorruptedRecordException;
import io.evitadb.store.offsetIndex.exception.PrematureEndOfFileException;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

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
 * @param <T>              Type of the payload that is stored in the data store.
 * @param generationId     generation id can be used to group multiple records into single generation
 * @param closesGeneration set to TRUE when this record is the last record of the generation
 * @param payload          this object represents the real payload of the record.
 * @param fileLocation     file location is a pointer to the location in the file that is occupied by binary content of
 *                         this record.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
public record StorageRecord<T>(
	long generationId,
	boolean closesGeneration,
	@Nullable T payload,
	@Nonnull FileLocation fileLocation
) {
	/**
	 * CRC not covered head 5B: length(int), control (byte), generationId (long)
	 */
	public static final int CRC_NOT_COVERED_HEAD = 4 + 1 + 8;
	/**
	 * CRC not covered part 13B: length(int), control (byte), crc itself (long)
	 */
	public static final int CRC_NOT_COVERED_PART = CRC_NOT_COVERED_HEAD + ObservableOutput.TAIL_MANDATORY_SPACE;
	/**
	 * Overhead is 22B: length(int), control (byte), generationId (long), crc (long).
	 */
	public static final int OVERHEAD_SIZE = CRC_NOT_COVERED_HEAD + ObservableOutput.TAIL_MANDATORY_SPACE;
	/**
	 * First bit of control byte marks end of generation record span.
	 */
	public static final byte GENERATION_CLOSING_BIT = 1;
	/**
	 * Second bit of control byte marks that record spans with next record.
	 */
	public static final byte CONTINUATION_BIT = 2;
	/**
	 * Third bit of control byte marks that record has CRC32 calculated.
	 */
	public static final byte CRC32_BIT = 3;
	/**
	 * Fourth bit of control byte marks that record is compressed.
	 */
	public static final byte COMPRESSION_BIT = 4;

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
	@Nonnull
	public static <T> StorageRecord<T> read(
		@Nonnull Kryo kryo,
		@Nonnull ObservableInput<?> input,
		@Nonnull Function<FileLocation, Class<T>> typeResolver
	) {
		long startPosition = -1L;
		int recordLength = -1;
		try {
			startPosition = input.markStart();
			recordLength = input.readInt();
			final byte control = input.readByte();

			final FileLocation location = new FileLocation(startPosition, recordLength);
			final Class<T> payloadType = typeResolver.apply(location);

			if (payloadType == null) {
				// record is obsolete - it cannot be mapped to any know type
				input.skip(recordLength);
				final boolean closesGeneration = isBitSet(control, GENERATION_CLOSING_BIT);
				return new StorageRecord<>(
					-1L, closesGeneration, null, location
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
		} catch (RuntimeException ex) {
			// reset input stream to avoid partially initialized state
			input.reset();
			if (startPosition != -1L && recordLength != -1) {
				// if we know the location, we can verify it
				checkUnderlyingInputStreamLength(input, new FileLocation(startPosition, recordLength), ex);
			}
			throw ex;
		}
	}

	/**
	 * Constructor that is used for READING known record from the input stream on known file location. Constructor should
	 * be used for random access reading of arbitrary records o reading lead record for the file offset index.
	 */
	@Nonnull
	public static <T> StorageRecord<T> read(
		@Nonnull ObservableInput<?> input,
		@Nonnull FileLocation location,
		@Nonnull TriFunction<ObservableInput<?>, Integer, Byte, T> reader
	) {
		try {
			input.seek(location);
			input.markStart();
			final int recordLength = input.readInt();
			byte control = input.readByte();

			final Supplier<T> payloadReader = () -> reader.apply(input, recordLength, control);
			return doReadStorageRecord(
				input,
				new FileLocation(location.startingPosition(), recordLength),
				control,
				fileLocation -> verifyExpectedLength(location, fileLocation),
				payloadReader
			);
		} catch (RuntimeException ex) {
			// reset input stream to avoid partially initialized state
			input.reset();
			checkUnderlyingInputStreamLength(input, location, ex);
			throw ex;
		}
	}

	/**
	 * Checks if the length of the underlying input stream is sufficient for the specified file location range.
	 * Logs an error and suppresses a {@link PrematureEndOfFileException} to the provided exception if the file
	 * size is not sufficient. Logs any exceptions encountered while retrieving the input stream length.
	 *
	 * @param input the observable input stream to be checked, must not be null
	 * @param location the file location containing the start position, end position, and record length, must not be null
	 * @param ex the runtime exception to which suppressed exceptions may be added, must not be null
	 */
	private static void checkUnderlyingInputStreamLength(
		@Nonnull ObservableInput<?> input,
		@Nonnull FileLocation location,
		@Nonnull RuntimeException ex
	) {
		try {
			final long maxLength = input.getLength();
			if (location.endPosition() > maxLength) {
				log.error("Attempt to read record at position {} with length {}B, but the file size is only {}B. " +
					"This may indicate a corrupted file or an attempt to read beyond the end of the file.",
					location.startingPosition(),
					location.recordLength(),
					maxLength
				);
				ex.addSuppressed(
					new PrematureEndOfFileException(
						maxLength,
						location.startingPosition(),
						location.endPosition()
					)
				);
			}
		} catch (Exception nested) {
			// just log
			log.error("Error getting length of input stream: {}", nested.getMessage(), nested);
		}
	}

	/**
	 * Constructor that is used for READING known record from the input stream on known file location. Constructor should
	 * be used for random access reading of arbitrary records o reading lead record for the file offset index.
	 *
	 * @deprecated introduced with #650 and could be removed later when no version prior to 2025.2 is used
	 */
	@Deprecated(since = "2025.1", forRemoval = true)
	@Nonnull
	public static <T> StorageRecord<T> readOldFormat(
		@Nonnull ObservableInput<?> input,
		@Nonnull FileLocation location,
		@Nonnull TriFunction<ObservableInput<?>, Integer, Byte, T> reader
	) {
		input.seek(location);
		input.markStart();
		final int recordLength = input.readInt();
		byte control = input.readByte();

		final Supplier<T> payloadReader = () -> reader.apply(input, recordLength, control);
		return doReadOldStorageRecord(
			input,
			new FileLocation(location.startingPosition(), recordLength),
			control,
			fileLocation -> verifyExpectedLength(location, fileLocation),
			payloadReader
		);
	}

	/**
	 * Constructor that is used for READING known record from the input stream on known file location. Constructor should
	 * be used for random access reading of arbitrary records o reading lead record for the file offset index.
	 *
	 * This method overrides the control byte to indicate that the record should be read as uncompressed. The payload
	 * can be read only as a byte array and will contain compressed data of the original record
	 */
	@Nonnull
	public static RawRecord readRaw(@Nonnull ObservableInput<?> input) {
		long startingPosition = -1L;
		int recordLength = -1;
		try {
			startingPosition = input.markStart();
			recordLength = input.readInt();
			final byte originalControl = input.readByte();
			final long generationId = input.readLong();
			// if the data is compressed we need to override the control byte and read it uncompressed
			byte control = setBit(originalControl, COMPRESSION_BIT, false);
			input.markPayloadStart(recordLength, control);
			final byte[] payload = input.readBytes(recordLength - CRC_NOT_COVERED_PART);
			input.markEnd(originalControl);

			return new RawRecord(
				new FileLocation(startingPosition, recordLength),
				originalControl,
				generationId,
				payload
			);
		} catch (RuntimeException ex) {
			// reset input stream to avoid partially initialized state
			input.reset();
			if (startingPosition != -1L && recordLength != -1) {
				// if we know the location, we can verify it
				checkUnderlyingInputStreamLength(input, new FileLocation(startingPosition, recordLength), ex);
			}
			throw ex;
		}
	}

	/**
	 * Constructor that is used for READING known record from the input stream on known file location. Constructor should
	 * be used for random access reading of arbitrary records o reading lead record for the file offset index.
	 *
	 * This method overrides the control byte to indicate that the record should be read as uncompressed. The payload
	 * can be read only as a byte array and will contain compressed data of the original record
	 *
	 * @deprecated introduced with #650 and could be removed later when no version prior to 2025.2 is used
	 */
	@Deprecated(since = "2025.1", forRemoval = true)
	@Nonnull
	public static RawRecord readOldRaw(
		@Nonnull ObservableInput<?> input
	) {
		long startPosition = -1L;
		int recordLength = -1;
		try {
			startPosition = input.markStart();
			recordLength = input.readInt();
			final byte originalControl = input.readByte();

			// if the data is compressed we need to override the control byte and read it uncompressed
			byte control = setBit(originalControl, COMPRESSION_BIT, false);
			input.markPayloadStart(recordLength, control);
			final long generationId = input.readLong();
			final byte[] payload = input.readBytes(recordLength - CRC_NOT_COVERED_PART);
			input.markEnd(originalControl);

			return new RawRecord(
				new FileLocation(startPosition, recordLength),
				originalControl,
				generationId,
				payload
			);
		} catch (RuntimeException ex) {
			// reset input stream to avoid partially initialized state
			input.reset();
			if (startPosition != -1L && recordLength != -1) {
				// if we know the location, we can verify it
				checkUnderlyingInputStreamLength(input, new FileLocation(startPosition, recordLength), ex);
			}
			throw ex;
		}
	}

	/**
	 * Constructor that is used for READING known record from the input stream on known file location. Constructor should
	 * be used for random access reading of arbitrary records o reading lead record for the file offset index.
	 *
	 * This method overrides the control byte to indicate that the record should be read as uncompressed. The payload
	 * can be read only as a byte array and will contain compressed data of the original record
	 *
	 * This method is special in the sense that it verifies whether the record length doesn't exceed the file size and
	 * if so, it throw specific exception.
	 *
	 * @deprecated introduced with #650 and could be removed later when no version prior to 2025.2 is used
	 */
	@Deprecated(since = "2025.3", forRemoval = true)
	@Nonnull
	public static RawRecord readOldRaw(
		@Nonnull ObservableInput<?> input,
		long fileSize
	) throws PrematureEndOfFileException {
		long startPosition = -1L;
		int recordLength = -1;
		try {
			startPosition = input.markStart();
			recordLength = input.readInt();
			final long theStartPosition = startPosition;
			final int theRecordLength = recordLength;
			Assert.isPremiseValid(
				startPosition + recordLength <= fileSize,
				() -> new PrematureEndOfFileException(fileSize, theStartPosition, theRecordLength)
			);
			final byte originalControl = input.readByte();

			// if the data is compressed we need to override the control byte and read it uncompressed
			byte control = setBit(originalControl, COMPRESSION_BIT, false);
			input.markPayloadStart(recordLength, control);
			final long generationId = input.readLong();
			final byte[] payload = input.readBytes(recordLength - CRC_NOT_COVERED_PART);
			input.markEnd(originalControl);

			return new RawRecord(
				new FileLocation(startPosition, recordLength),
				originalControl,
				generationId,
				payload
			);
		} catch (RuntimeException ex) {
			// reset input stream to avoid partially initialized state
			input.reset();
			if (startPosition != -1L && recordLength != -1) {
				// if we know the location, we can verify it
				checkUnderlyingInputStreamLength(input, new FileLocation(startPosition, recordLength), ex);
			}
			throw ex;
		}
	}

	/**
	 * Constructor that is used for READING known record from the input stream on known start position. Constructor
	 * should be used for sequential access reading of arbitrary records o reading lead record for the file offset index.
	 */
	@Nonnull
	public static <T> StorageRecord<T> read(
		@Nonnull ObservableInput<?> input,
		@Nonnull BiFunction<ObservableInput<?>, Integer, T> reader
	) {
		long startPosition = -1L;
		int recordLength = -1;
		try {
			input.markStart();
			startPosition = input.position();
			recordLength = input.readInt();
			byte control = input.readByte();

			final int theRecordLength = recordLength;
			return doReadStorageRecord(
				input,
				new FileLocation(startPosition, recordLength),
				control,
				null,
				() -> reader.apply(input, theRecordLength)
			);
		} catch (RuntimeException ex) {
			// reset input stream to avoid partially initialized state
			input.reset();
			if (startPosition != -1L && recordLength != -1) {
				// if we know the location, we can verify it
				checkUnderlyingInputStreamLength(input, new FileLocation(startPosition, recordLength), ex);
			}
			throw ex;
		}
	}

	/**
	 * Reads the {@link FileLocation} of a record from the specified {@link ObservableInput}.
	 *
	 * @param input         The input stream to read from.
	 * @param startPosition The starting position of the record in the file.
	 * @return The {@link FileLocation} of the record.
	 */
	@Nonnull
	public static FileLocation readFileLocation(
		@Nonnull ObservableInput<?> input,
		long startPosition
	) {
		int recordLength = -1;
		try {
			input.seek(new FileLocation(startPosition, 4));
			input.markStart();
			recordLength = input.readInt();
			input.reset();
			return new FileLocation(startPosition, recordLength);
		} catch (RuntimeException ex) {
			// reset input stream to avoid partially initialized state
			input.reset();
			if (recordLength != -1) {
				// if we know the location, we can verify it
				checkUnderlyingInputStreamLength(input, new FileLocation(startPosition, recordLength), ex);
			}
			throw ex;
		}
	}

	/**
	 * Method allows to write raw data to the output stream. The method is used for writing data that were read using
	 * {@link #readRaw(ObservableInput)} method.
	 */
	@Nonnull
	public static FileLocation writeRaw(
		@Nonnull ObservableOutput<?> output,
		byte control,
		long generationId,
		@Nonnull byte[] rawData
	) {
		try {
			output.markStart();
			output.markRecordLengthPosition();
			output.writeInt(0);
			output.writeByte(0);
			output.writeLong(generationId);
			output.markPayloadStart();
			output.writeBytes(rawData);
			final FileLocation resultLocation = output.markEndSuppressingCompression(control);
			//noinspection StringConcatenationMissingWhitespace
			Assert.isPremiseValid(
				rawData.length + CRC_NOT_COVERED_PART == resultLocation.recordLength(),
				() -> new CorruptedRecordException(
					"Record length differs (" + resultLocation.recordLength() + "B, " +
						"expected " + (rawData.length + CRC_NOT_COVERED_PART) + "B) - it's probably corrupted!",
					resultLocation.recordLength(), rawData.length + CRC_NOT_COVERED_PART
				)
			);
			return resultLocation;
		} catch (Exception ex) {
			// reset output stream to avoid partially initialized state
			output.reset();
			throw ex;
		}
	}

	/**
	 * Verifies that the record length of the calculated file location matches the expected record length
	 * of the input file location. If the lengths differ, an exception is thrown indicating a corrupted record.
	 *
	 * @param inputLocation      The {@code FileLocation} representing the expected record location and length.
	 * @param calculatedLocation The {@code FileLocation} representing the calculated record location and length.
	 * @throws CorruptedRecordException If the record lengths do not match, indicating a potential corruption.
	 */
	private static void verifyExpectedLength(@Nonnull FileLocation inputLocation, @Nonnull FileLocation calculatedLocation) {
		//noinspection StringConcatenationMissingWhitespace
		Assert.isPremiseValid(
			inputLocation.recordLength() == calculatedLocation.recordLength(),
			() -> new CorruptedRecordException(
				"Record length differs (" + calculatedLocation.recordLength() + "B, " +
					"expected " + inputLocation.recordLength() + "B) - it's probably corrupted!",
				inputLocation.recordLength(), calculatedLocation.recordLength()
			)
		);
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

			final long generationId = input.readLong();
			// record is active load and verify contents
			input.markPayloadStart(location.recordLength(), control);

			final T payload = input.doWithOnBufferOverflowHandler(
				new BufferOverflowReadHandler<>(context, generationId),
				payloadReader
			);

			input.markEnd(context.getControlByte());

			final FileLocation fileLocation = new FileLocation(context.getStartPosition(), context.getRecordLength());

			if (assertion != null) {
				assertion.accept(fileLocation);
			}

			return new StorageRecord<>(
				generationId, context.isClosesGeneration(), payload, fileLocation
			);
		} else {
			boolean closesGeneration = isBitSet(control, GENERATION_CLOSING_BIT);
			final long generationId = input.readLong();
			input.markPayloadStart(location.recordLength(), control);
			final T payload = payloadReader.get();
			input.markEnd(control);

			return new StorageRecord<>(
				generationId, closesGeneration, payload,
				new FileLocation(location.startingPosition(), location.recordLength())
			);
		}
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
	 * @deprecated introduced with #650 and could be removed later when no version prior to 2025.2 is used
	 */
	@Deprecated(since = "2025.1", forRemoval = true)
	@Nonnull
	private static <T> StorageRecord<T> doReadOldStorageRecord(
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
			input.markPayloadStart(location.recordLength(), control);
			final long generationId = input.readLong();

			final T payload = input.doWithOnBufferOverflowHandler(
				new BufferOverflowReadHandler<>(context, generationId),
				payloadReader
			);

			input.markEnd(context.getControlByte());

			final FileLocation fileLocation = new FileLocation(context.getStartPosition(), context.getRecordLength());

			if (assertion != null) {
				assertion.accept(fileLocation);
			}

			return new StorageRecord<>(
				generationId, context.isClosesGeneration(), payload, fileLocation
			);
		} else {
			boolean closesGeneration = isBitSet(control, GENERATION_CLOSING_BIT);
			input.markPayloadStart(location.recordLength(), control);
			final long generationId = input.readLong();
			final T payload = payloadReader.get();
			input.markEnd(control);

			return new StorageRecord<>(
				generationId, closesGeneration, payload,
				new FileLocation(location.startingPosition(), location.recordLength())
			);
		}
	}

	/**
	 * Writes the header of a storage record to the specified output.
	 *
	 * @param output       The output stream to write the header to.
	 * @param generationId The generation id of the record.
	 */
	private static void writeHeader(@Nonnull ObservableOutput<?> output, long generationId) {
		output.markStart();

		// we don't know the record length yet
		output.markRecordLengthPosition();
		// reserve int space for the record length and control byte
		output.writeInt(0);
		output.writeByte(0);
		// write generation information
		output.writeLong(generationId);

		output.markPayloadStart();
	}

	/**
	 * Writes a record to the specified output stream with the given generation id and payload writer.
	 *
	 * @param output           The output stream to write the record to.
	 * @param generationId     The generation id of the record.
	 * @param closesGeneration A flag indicating whether the record closes the generation.
	 * @param payloadWriter    The payload writer function that writes the payload to the output stream.
	 * @param <T>              The type of the payload.
	 * @param <OS>             The type of the output stream.
	 * @return A {@link PayloadWithFileLocation} object containing the payload and file location of the written record.
	 */
	@Nonnull
	private static <T, OS extends OutputStream> PayloadWithFileLocation<T> writeRecord(
		@Nonnull ObservableOutput<OS> output,
		long generationId,
		boolean closesGeneration,
		@Nonnull Function<ObservableOutput<?>, T> payloadWriter
	) {
		final AtomicReference<FileLocationPointer> recordLocations = new AtomicReference<>(FileLocationPointer.INITIAL);
		return output.doWithOnBufferOverflowHandler(
			new OnBufferOverflowHandler<>(recordLocations, output, generationId),
			() -> {
				// write storage record header
				writeHeader(output, generationId);
				// finally, write payload
				final T thePayload = payloadWriter.apply(output);
				// compute crc32 and fill in record length
				final FileLocation theFileLocation = output.markEnd(setBit((byte) 0, GENERATION_CLOSING_BIT, closesGeneration));
				// return both
				return new PayloadWithFileLocation<>(thePayload, theFileLocation);
			}
		);
	}

	/**
	 * Writes a record to the specified output stream with the given generation id and payload.
	 *
	 * @param output           The output stream to write the record to.
	 * @param generationId     The generation id of the record.
	 * @param closesGeneration A flag indicating whether the record closes the generation.
	 * @param kryo             The Kryo instance used for serialization.
	 * @param payload          The payload object to be written.
	 * @param <T>              The type of the payload.
	 * @param <OS>             The type of the output stream.
	 * @return The file location object specifying the position and length of the written record in the file.
	 */
	@Nonnull
	private static <T, OS extends OutputStream> FileLocation writeRecord(
		@Nonnull ObservableOutput<OS> output,
		long generationId,
		boolean closesGeneration,
		@Nonnull Kryo kryo,
		@Nonnull T payload
	) {
		try {
			final AtomicReference<FileLocationPointer> recordLocations = new AtomicReference<>(FileLocationPointer.INITIAL);
			return output.doWithOnBufferOverflowHandler(
				new OnBufferOverflowHandler<>(recordLocations, output, generationId),
				() -> {
					// write storage record header
					writeHeader(output, generationId);
					// finally, write payload
					kryo.writeObject(output, payload);
					// compute crc32 and fill in record length
					final byte controlByte = setBit((byte) 0, GENERATION_CLOSING_BIT, closesGeneration);
					final FileLocation completeRecordLocation = output.markEnd(controlByte);
					// return file length that possibly spans multiple records
					return recordLocations.get().computeOverallFileLocation(completeRecordLocation);
				}
			);
		} catch (Exception ex) {
			// reset output stream to avoid partially initialized state
			output.reset();
			throw ex;
		}
	}

	/**
	 * Constructor that is used for WRITING the record to the passed output stream.
	 */
	public <OS extends OutputStream> StorageRecord(
		@Nonnull Kryo kryo,
		@Nonnull ObservableOutput<OS> output,
		long generationId,
		boolean closesGeneration,
		@Nonnull T payload
	) {
		this(
			generationId,
			closesGeneration,
			payload,
			writeRecord(output, generationId, closesGeneration, kryo, payload)
		);
	}

	/**
	 * Constructor that is used for WRITING the record to the passed output stream.
	 */
	public <OS extends OutputStream> StorageRecord(
		@Nonnull ObservableOutput<OS> output,
		long generationId,
		boolean closesGeneration,
		@Nonnull Function<ObservableOutput<?>, T> payloadWriter
	) {
		this(
			generationId,
			closesGeneration,
			writeRecord(output, generationId, closesGeneration, payloadWriter)
		);
	}

	private StorageRecord(
		long generationId,
		boolean closesGeneration,
		@Nonnull PayloadWithFileLocation<T> tPayloadWithFileLocation
	) {
		this(
			generationId, closesGeneration, tPayloadWithFileLocation.payload(), tPayloadWithFileLocation.fileLocation()
		);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		StorageRecord<?> that = (StorageRecord<?>) o;
		return this.generationId == that.generationId &&
			this.closesGeneration == that.closesGeneration &&
			Objects.equals(this.payload, that.payload) &&
			Objects.equals(this.fileLocation, that.fileLocation);
	}

	/*
		PRIVATE METHODS
	 */

	@Override
	public int hashCode() {
		int result = Long.hashCode(this.generationId);
		result = 31 * result + (this.closesGeneration ? 1 : 0);
		result = 31 * result + (this.payload != null ? this.payload.hashCode() : 0);
		result = 31 * result + this.fileLocation.hashCode();
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
	 * the record closes a generation or is a continuation record.
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
		 * It stores information about the record, such as generation closing, continuation, etc.
		 */
		private byte controlByte;
		/**
		 * The closesGeneration variable represents a flag indicating whether a record is the last record of a generation.
		 **/
		private boolean closesGeneration;
		/**
		 * The continuationRecord flag indicates that the payload continues in the next record.
		 */
		private boolean continuationRecord;

		public ReadingContext(long startPosition, int recordLength, byte control) {
			this.startPosition = startPosition;
			this.recordLength = recordLength;
			this.controlByte = control;
			this.closesGeneration = isBitSet(control, GENERATION_CLOSING_BIT);
			this.continuationRecord = isBitSet(control, CONTINUATION_BIT);
		}

		public void updateWithNextRecord(int recordLength, byte control) {
			this.recordLength += recordLength;
			this.controlByte = control;
			this.closesGeneration = isBitSet(control, GENERATION_CLOSING_BIT);
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
				while (current != null && !current.isEmpty()) {
					final FileLocation currentFileLocation = Objects.requireNonNull(current.fileLocation());
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
	 * @param context      the reading context
	 * @param generationId the generation id of the record
	 * @param <IS>         the type of the InputStream
	 */
	private record BufferOverflowReadHandler<IS extends InputStream>(
		ReadingContext context,
		long generationId
	) implements Consumer<ObservableInput<IS>> {

		@Override
		public void accept(ObservableInput<IS> observableInput) {
			observableInput.markEnd(this.context.getControlByte());

			observableInput.markStart();
			final int continuingRecordLength = observableInput.readInt();
			final byte continuingControl = observableInput.readByte();
			final long continuingGenerationId = observableInput.readLong();

			observableInput.markPayloadStart(continuingRecordLength, continuingControl);

			Assert.isPremiseValid(
				this.generationId == continuingGenerationId,
				() -> new CorruptedRecordException(
					"Generation id differs in continuous record (" +
						this.generationId + " vs. " + continuingGenerationId +
						"). This is not expected!", this.generationId, continuingGenerationId
				)
			);
			this.context.updateWithNextRecord(continuingRecordLength, continuingControl);
		}

	}

	/**
	 * OnBufferOverflowHandler is used to handle situation when the payload can't fit into the buffer and needs
	 * to be written into multiple records.
	 *
	 * @param <OO> the type of the ObservableOutput used in the record
	 */
	private record OnBufferOverflowHandler<OO extends ObservableOutput<?>>(
		@Nonnull AtomicReference<FileLocationPointer> recordLocations,
		@Nonnull OO output,
		long generationId
	) implements Consumer<OO> {

		@Override
		public void accept(OO filledOutput) {
			final byte controlByte = setBit((byte) 0, CONTINUATION_BIT, true);
			final FileLocation incompleteRecordLocation = filledOutput.markEnd(controlByte);
			// register file location of the continuous record
			this.recordLocations.set(new FileLocationPointer(this.recordLocations.get(), incompleteRecordLocation));
			// write storage record header for another record
			writeHeader(this.output, this.generationId);
		}
	}

	/**
	 * A record representing raw data read from or written to a storage.
	 * This is a lower-level structure typically utilized for operations that work directly
	 * with unprocessed data within file locations.
	 *
	 * Instances of this record encapsulate the following:
	 * 1. A {@link FileLocation} object indicating the location of the record in a file.
	 * 2. A control byte which provides metadata or flags about the record.
	 * 3. A byte array containing the raw data payload associated with the record.
	 *
	 * This class is most commonly utilized for handling operations that bypass higher-level
	 * abstractions in the data storage layer. It is used internally for seeking, reading, or
	 * writing raw storage segments and may also carry additional contextual information
	 * in its `control` byte about the record.
	 */
	public record RawRecord(
		@Nonnull FileLocation location,
		byte control,
		long generationId,
		byte[] rawData
	) {}

}
