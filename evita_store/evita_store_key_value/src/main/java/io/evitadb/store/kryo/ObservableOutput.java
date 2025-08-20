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

package io.evitadb.store.kryo;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.exception.StorageException;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.utils.Assert;
import io.evitadb.utils.BitUtils;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.CRC32C;
import java.util.zip.Deflater;

/**
 * This observable output extends original Kryo {@link Output} allowing to automatically compute CRC32C checksums after
 * record has been written to the storage. It also collects the written record size automatically and writes it to the
 * specified position in the record. To avoid writing to already committed file page the record flushing is postponed
 * until entire record has been written and record length update is performed in not yet stored buffer in the memory.
 * This also means that entire record must fit in the `bufferSize` size that defined in the constructor of this class.
 *
 * Because we need sufficiently big `buffer` because it limits our record sizes we need to specify another `flushSize`
 * limit that will control what size of "finalized" content (i.e. records which writing has been finished) will be flushed
 * to the disk. This means that even if we have very large buffer (say 16MB) and we write lot of small records (say 2KB)
 * we well be able to flush these records each 8KB (i.e. `flushSize=8KB`).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class ObservableOutput<T extends OutputStream> extends Output {
	/**
	 * Reserved space in the buffer that will be occupied by mandatory information (CRC).
	 */
	public static final int TAIL_MANDATORY_SPACE = MemoryMeasuringConstants.LONG_SIZE;
	static final long NULL_CRC = 0xFFFFFFFFFFFFFFFFL;
	/**
	 * Default flush size in bytes.
	 */
	public static final int DEFAULT_FLUSH_SIZE = 16_384;
	/**
	 * CRC32C computation instance. Reused - instantiated only once.
	 */
	private CRC32C crc32C;
	/**
	 * Deflater instance. Reused - instantiated only once.
	 */
	private Deflater deflater;
	/**
	 * Buffer used for compressed data.
	 */
	private byte[] deflateBuffer;
	/**
	 * Accumulated count of bytes that have been saved by compress.
	 * This number is crucial for correct file location calculation when multiple records are written to the output.
	 *
	 * Note: must be long to prevent integer overflow that would otherwise yield invalid written-bytes accounting.
	 */
	private long savedBytesByCompressionSinceReset;
	/**
	 * Contains floating position in the buffer that marks bytes already written to the output stream (disk). I.e. every
	 * byte before this position were already written out.
	 */
	private int lastConsumedPosition;
	/**
	 * Record start position. Use {@link #markStart()} to init.
	 */
	@Getter private int startPosition = -1;
	/**
	 * Record payload (i.e. real record contents). Use {@link #markPayloadStart()} to init.
	 */
	@Getter private int payloadStartPosition = -1;
	/**
	 * Position of the record length integer in the buffer. This position is used for writing down the real length of
	 * the records when {@link #markEnd(byte)} is called.
	 */
	@Getter private int recordLengthPosition = -1;
	/**
	 * This piece of logic should handle the situation when the data doesn't fit to the buffer. Handler might allow to
	 * split the data into several records and overcome this problem with insufficient buffer.
	 */
	@Nullable private Consumer<ObservableOutput<T>> onBufferOverflow;
	/**
	 * Internal flag that is set to true only when {@link #onBufferOverflow} handler is being executed.
	 */
	private boolean overflowing;
	/**
	 * Internal flag that is set to true only when {@link #markEnd(byte)} method is running - which means that space
	 * for the tail no longer needs to be kept in reserve.
	 */
	private boolean writingTail;
	/**
	 * This variable contains "safe-point" - i.e. position in the buffer that denotes start of currently written value.
	 * Some data type require multiple bytes and their number is not know up-front. This leads to situations that
	 * buffer is exhausted in the middle of writing the value. There might be {@link #onBufferOverflow} handler that
	 * allows to deal with this situation, but we need to ensure that the value is written completely either in old
	 * or new buffer (it cannot be interrupted in the middle). This `atomicPosition` allows us to copy the so-far written
	 * part of the value and move it to the next buffer, completely wiping it from the current one.
	 */
	private int atomicPosition = -1;

	public ObservableOutput(@Nonnull T outputStream, @Nonnull byte[] buffer) {
		super(buffer);
		super.setOutputStream(outputStream);
		// we need to hide CRC mandatory space from the Kryo output so that it asks for `require` when it reaches
		// the end of the capacity - this way we will have reserved space for safely writing the CRC checksum
		this.capacity = buffer.length - TAIL_MANDATORY_SPACE;
	}

	/**
	 * Initializes {@link ObservableInput} with recommended settings for SSD drives.
	 *
	 * @implNote <a href="https://codecapsule.com/2014/02/12/coding-for-ssds-part-6-a-summary-what-every-programmer-should-know-about-solid-state-drives/">Source</a>
	 * @param bufferSize maximal size of the single record that can be stored
	 */
	public ObservableOutput(@Nonnull T outputStream, int bufferSize, long currentFileSize) {
		this(outputStream, DEFAULT_FLUSH_SIZE, bufferSize, currentFileSize);
	}

	public ObservableOutput(@Nonnull T outputStream, int flushSize, int bufferSize, long currentFileSize) {
		super(outputStream, bufferSize);
		if (bufferSize < flushSize) {
			throw new StorageException("Buffer size cannot be lower than flush limit with some reserve space!");
		}
		this.total = currentFileSize;
		// we need to hide CRC mandatory space from the Kryo output so that it asks for `require` when it reaches
		// the end of the capacity - this way we will have reserved space for safely writing the CRC checksum
		this.capacity = bufferSize - TAIL_MANDATORY_SPACE;
	}

	/**
	 * Enables CRC32C checksum computation for each record has been written to the file.
	 */
	@Nonnull
	public ObservableOutput<T> computeCRC32() {
		if (this.crc32C == null) {
			this.crc32C = new CRC32C();
		}
		return this;
	}

	/**
	 * Enables compress for each record payload.
	 */
	@Nonnull
	public ObservableOutput<T> compress() {
		if (this.deflater == null) {
			this.deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
			// the deflate buffer must be the same size as the buffer,
			// if the data exceed the buffer size, it means compressed data are larger than the original data
			// and the compress would not be used at all
			this.deflateBuffer = new byte[this.buffer.length];
		}
		return this;
	}

	/**
	 * Returns true if the compression is enabled.
	 * @return true if the compression is enabled
	 */
	public boolean isCompressionEnabled() {
		return this.deflater != null;
	}

	/**
	 * Initializes start position of the records - i.e. since this moment record size starts to be observed.
	 */
	public void markStart() {
		this.startPosition = super.position;
	}

	/**
	 * Initializes start position of the record payload - i.e. since this moment CRC32C checksum starts to be computed
	 * for each byte written from now on.
	 */
	public void markPayloadStart() {
		this.payloadStartPosition = super.position;
		if (this.crc32C != null) {
			this.crc32C.reset();
		}
	}

	/**
	 * Initializes the position where overall record length should be written to.
	 */
	public void markRecordLengthPosition() {
		this.recordLengthPosition = super.position;
	}

	/**
	 * Method finalizes the record writing.
	 * When CRC32C checksum should be computed, it's done at this moment.
	 * Record length is computed and is written to the specified position of the record as INT value.
	 * Method resets all record related flags and another record can be written afterwards.
	 * Record is marked as finished and can be safely written to the output stream (disk).
	 *
	 * @param controlByte the control byte to be written along with the record
	 */
	@Nonnull
	public FileLocation markEnd(byte controlByte) {
		return markEndInternal(controlByte, this.deflater);
	}

	/**
	 * Method finalizes the record writing.
	 * When CRC32C checksum should be computed, it's done at this moment.
	 * Record length is computed and is written to the specified position of the record as INT value.
	 * Method resets all record related flags and another record can be written afterwards.
	 * Record is marked as finished and can be safely written to the output stream (disk).
	 *
	 * This method deliberately avoids compression. The control byte is not checked for the compression flag, so that
	 * its possible to store already compressed content using this method.
	 *
	 * @param controlByte the control byte to be written along with the record
	 */
	@Nonnull
	public FileLocation markEndSuppressingCompression(byte controlByte) {
		return markEndInternal(controlByte, null);
	}

	/**
	 * Method finalizes the record writing.
	 * When CRC32C checksum should be computed, it's done at this moment.
	 * Record length is computed and is written to the specified position of the record as INT value.
	 * Method resets all record related flags and another record can be written afterwards.
	 * Record is marked as finished and can be safely written to the output stream (disk).
	 *
	 * @param controlByte the control byte to be written along with the record
	 * @param theDeflater the Deflater instance tobe used for compression, or null to disable compression
	 */
	@Nonnull
	public FileLocation markEndInternal(byte controlByte, @Nullable Deflater theDeflater) {
		try {
			Assert.isPremiseValid(this.payloadStartPosition != -1, "Payload start position must be initialized!");
			this.writingTail = true;
			byte alteredControlByte = controlByte;
			final int payloadLength = this.position - this.payloadStartPosition;

			// compress payload if requested
			final int savedBytesByCompression;
			if (theDeflater != null) {
				theDeflater.reset();
				theDeflater.setInput(this.buffer, this.payloadStartPosition, payloadLength);
				theDeflater.finish();
				int deflatedLength = theDeflater.deflate(this.deflateBuffer, 0, this.deflateBuffer.length);
				if (theDeflater.finished() && deflatedLength < payloadLength) {
					savedBytesByCompression = payloadLength - deflatedLength;
					alteredControlByte = BitUtils.setBit(alteredControlByte, StorageRecord.COMPRESSION_BIT, true);
				} else {
					savedBytesByCompression = 0;
				}
			} else {
				savedBytesByCompression = 0;
			}

			// compute CRC32 checksum if requested
			final long crc;
			if (this.crc32C == null) {
				crc = NULL_CRC;
			} else {
				alteredControlByte = BitUtils.setBit(alteredControlByte, StorageRecord.CRC32_BIT, true);
				crc = calculateChecksum(savedBytesByCompression, payloadLength, alteredControlByte);
			}

			// write CRC32 or blank
			super.writeLong(crc);
			// store current position
			final int savedPosition = this.position;
			final int length = Math.subtractExact(this.position - this.startPosition, savedBytesByCompression);
			Assert.isPremiseValid(
				length >= 0,
				"Record length must be non-negative! Got: " + length + ", because " +
					"savedBytesByCompression is: " + savedBytesByCompression + ", " +
					"payloadLength is: " + payloadLength + ", alteredControlByte is: " + alteredControlByte + "!"
			);

			// seek backwards to the place of record length and write it
			writeIntWithoutTouchingPosition(this.recordLengthPosition, length);
			writeByteWithoutTouchingPosition(this.recordLengthPosition + 4, alteredControlByte);
			// restore position to the EOF
			super.position = savedPosition;
			// compute file coordinates before resetting positions
			final long supposedRecordStartPosition = Math.subtractExact(
				Math.addExact(this.total, this.startPosition - this.lastConsumedPosition),
				this.savedBytesByCompressionSinceReset
			);
			Assert.isPremiseValid(
				supposedRecordStartPosition >= 0,
				"Record start position must be non-negative! Got: " + supposedRecordStartPosition + ", because " +
					"savedBytesByCompressionSinceReset is: " + this.savedBytesByCompressionSinceReset + ", " +
					"total is: " + this.total + ", startPosition is: " + this.startPosition + ", lastConsumedPosition is: " + this.lastConsumedPosition + "!"
			);

			// write the record to the output stream
			IOUtils.executeSafely(
				payloadLength, savedBytesByCompression,
				KryoException::new,
				this::finishRecord
			);

			// return location coordinates
			return new FileLocation(supposedRecordStartPosition, length);
		} finally {
			this.writingTail = false;
		}
	}

	/**
	 * Calculates a CRC32C checksum based on the given input parameters and updates
	 * the CRC32C instance with the relevant data.
	 *
	 * @param savedBytesByCompression the number of bytes saved by compress.
	 *        This determines whether deflateBuffer or buffer is used for the checksum calculation.
	 * @param payloadLength the length of the payload used in checksum computation.
	 * @param alteredControlByte a single byte that will also be included in the checksum.
	 * @return the computed CRC32C checksum as a long value.
	 */
	private long calculateChecksum(int savedBytesByCompression, int payloadLength, byte alteredControlByte) {
		if (savedBytesByCompression > 0) {
			this.crc32C.update(this.deflateBuffer, 0, payloadLength - savedBytesByCompression);
		} else {
			this.crc32C.update(this.buffer, this.payloadStartPosition, payloadLength);
		}
		this.crc32C.update(alteredControlByte);
		return this.crc32C.getValue();
	}

	/**
	 * Finalizes the record writing process by writing its components (header, payload, and tail)
	 * to the output stream while considering compress savings. Updates internal counters
	 * and resets all record-related flags to prepare for another record to be written.
	 *
	 * @param thePayloadLength           the length of the record payload to be written
	 * @param theSavedBytesByCompression the number of bytes saved by compress
	 * @throws IOException if an I/O error occurs during writing
	 */
	private void finishRecord(int thePayloadLength, int theSavedBytesByCompression) throws IOException {
		// write header to the output stream
		this.outputStream.write(this.buffer, this.lastConsumedPosition, this.payloadStartPosition - this.lastConsumedPosition);
		// write payload to the output stream
		if (theSavedBytesByCompression > 0) {
			this.outputStream.write(this.deflateBuffer, 0, thePayloadLength - theSavedBytesByCompression);
			this.savedBytesByCompressionSinceReset = Math.addExact(this.savedBytesByCompressionSinceReset, theSavedBytesByCompression);
		} else {
			this.outputStream.write(this.buffer, this.payloadStartPosition, thePayloadLength);
		}
		// write tail to the output stream
		this.outputStream.write(this.buffer, this.position - TAIL_MANDATORY_SPACE, TAIL_MANDATORY_SPACE);

		// update counters as if the uncompressed data was written
		final int consumedLength = this.position - this.lastConsumedPosition;
		this.total += consumedLength;
		this.lastConsumedPosition += consumedLength;
		// clear open record indexes
		this.startPosition = -1;
		this.payloadStartPosition = -1;
	}

	/**
	 * This method opens writing into output in mode that handles buffer overflow situations using passed handler.
	 */
	public <S> S doWithOnBufferOverflowHandler(@Nonnull Consumer<ObservableOutput<T>> onBufferOverflow, @Nonnull Supplier<S> lambda) {
		try {
			this.onBufferOverflow = onBufferOverflow;
			return lambda.get();
		} finally {
			this.onBufferOverflow = null;
		}
	}

	@Override
	public T getOutputStream() {
		//noinspection unchecked
		return (T) super.getOutputStream();
	}

	@Override
	public byte[] toBytes() {
		byte[] newBuffer = new byte[this.position()];
		System.arraycopy(this.buffer, this.lastConsumedPosition, newBuffer, 0, newBuffer.length);
		return newBuffer;
	}

	@Override
	public int position() {
		return this.position - this.lastConsumedPosition;
	}

	@Override
	public void setPosition(int position) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Do not use for checking how many bytes were written to the output stream.
	 * Instead use {@link #getWrittenBytesSinceReset()}.
	 *
	 * @return the total number of bytes written to the uncompressed byte array
	 */
	@Override
	public long total() {
		return Math.addExact(this.total, position());
	}

	/**
	 * Retrieves the number of bytes written since the last reset.
	 * This value is calculated based on the total written bytes, the current position,
	 * and adjustments from compression savings since the last reset.
	 *
	 * @return the number of bytes written since the last reset as a long value.
	 */
	public long getWrittenBytesSinceReset() {
		final long writtenBytes = Math.subtractExact(Math.addExact(this.total, position()), this.savedBytesByCompressionSinceReset);
		Assert.isPremiseValid(
			writtenBytes >= 0,
			"Written bytes must be non-negative! Got: " + writtenBytes + ", because " +
				"savedBytesByCompressionSinceReset is: " + this.savedBytesByCompressionSinceReset + ", " +
				"total is: " + this.total + ", position is: " + position() + "!"
		);
		return writtenBytes;
	}

	@Override
	public void reset() {
		super.reset();
		this.lastConsumedPosition = 0;
		this.startPosition = -1;
		this.payloadStartPosition = -1;
		this.recordLengthPosition = -1;
		this.savedBytesByCompressionSinceReset = 0;
	}

	/**
	 * This method overrides default behaviour and protects currently written record from being flushed or rewritten.
	 * This implementation flushes the already finalized contents of the buffer if there is not enough space and compacts
	 * the buffer in a way that moves unfinished data to the start of the buffer. When the buffer is not big enough after
	 * these operations {@link KryoException} is thrown.
	 *
	 * @throws KryoException - when there is no space left in the buffer, can only happen if current record size exceeds
	 *                       `bufferSize` stated in constructor of this object.
	 */
	@Override
	protected boolean require(int required) throws KryoException {
		final int reserve = this.writingTail ? TAIL_MANDATORY_SPACE : 0;
		if (this.capacity - this.position + reserve >= required) {
			// there is enough capacity available
			return false;
		}
		// write data if there is enough accumulated
		writeDataToOutputStream();
		// we've moved far in the buffer, and we can rewind
		if (this.lastConsumedPosition > 0) {
			final int activeBufferSize = this.startPosition == -1 ? 0 : this.position - this.lastConsumedPosition;
			if (activeBufferSize < this.lastConsumedPosition) {
				// if it's safe to copy within single buffer - do it
				System.arraycopy(this.buffer, this.lastConsumedPosition, this.buffer, 0, activeBufferSize);
			} else {
				// it's not safe - data would be overwritten - we need to allocate new byte buffer
				final byte[] newBuffer = new byte[this.capacity + TAIL_MANDATORY_SPACE];
				System.arraycopy(this.buffer, this.lastConsumedPosition, newBuffer, 0, activeBufferSize);
				this.buffer = newBuffer;
			}
			recomputePositionsToNewStart();
		}
		if (this.capacity - this.position + reserve < required) {
			if (this.onBufferOverflow == null || this.overflowing) {
				// there is not enough due to big active record
				//noinspection StringConcatenationMissingWhitespace
				throw new KryoException("Active record exceeds buffer size of " + this.capacity + "B!");
			} else {
				try {
					this.overflowing = true;
					// check whether there is not value partially written only
					if (this.atomicPosition == -1) {
						// let the handler resolve the situation and try again
						this.onBufferOverflow.accept(this);
					} else {
						// if the atomic position is set - we need to rewind to the start of currently written atomic value
						// copy the unfinished part and move it to the new buffer
						final byte[] partiallyWrittenValue = Arrays.copyOfRange(this.buffer, this.atomicPosition, this.position);
						this.position = atomicPosition;
						// let the handler resolve the situation and try again
						this.onBufferOverflow.accept(this);
						// now write the partially written bytes
						super.writeBytes(partiallyWrittenValue, 0, partiallyWrittenValue.length);
						// reset atomic position
						this.atomicPosition = 0;
					}
					require(required);
				} finally {
					this.overflowing = false;
				}
			}
		}
		return true;
	}

	/**
	 * Flushes finished contents of this buffer regardless of their size.
	 */
	@Override
	public void flush() throws KryoException {
		if (this.outputStream == null) {
			return;
		}
		writeDataToOutputStream();
		IOUtils.close(
			KryoException::new,
			this.outputStream::flush
		);
	}

	@Override
	public void writeBytes(byte[] bytes, int offset, int count) throws KryoException {
		// this method allows writing multiple bytes, and we need to be able to rewind when the buffer is exhausted
		try {
			//safely init atomic position
			this.atomicPosition = this.position;
			super.writeBytes(bytes, offset, count);
		} finally {
			//and reset it when the block is exited
			this.atomicPosition = -1;
		}
	}

	@Override
	public void writeString(String value) throws KryoException {
		// this method allows writing multiple bytes, and we need to be able to rewind when the buffer is exhausted
		try {
			//safely init atomic position
			this.atomicPosition = position;
			super.writeString(value);
		} finally {
			//and reset it when the block is exited
			this.atomicPosition = -1;
		}
	}

	@Override
	public void writeAscii(String value) throws KryoException {
		// this method allows writing multiple bytes, and we need to be able to rewind when the buffer is exhausted
		try {
			//safely init atomic position
			this.atomicPosition = this.position;
			super.writeAscii(value);
		} finally {
			//and reset it when the block is exited
			this.atomicPosition = -1;
		}
	}

	/**
	 * This method initializes positions after compacting the buffer (ie. writing current record from the buffer start).
	 */
	private void recomputePositionsToNewStart() {
		// we have to align new starts
		if (this.startPosition != -1) {
			this.startPosition = this.startPosition - this.lastConsumedPosition;
			this.payloadStartPosition = this.payloadStartPosition == -1 ? -1 : this.payloadStartPosition - this.lastConsumedPosition;
			this.recordLengthPosition = this.recordLengthPosition - this.lastConsumedPosition;
		}
		this.position = this.position - this.lastConsumedPosition;
		this.lastConsumedPosition = 0;
	}

	/**
	 * This method behaves like {@link #writeInt(int)} with the exception that it doesn't affect the position in the buffer
	 * and writes to arbitrary position passed in argument. You need to reserve the space for the integer upwards otherwise
	 * data may be overwritten and binary stream corrupted.
	 *
	 * Implementation is copy & pasted from the original implementation.
	 */
	private void writeIntWithoutTouchingPosition(int position, int integerToWrite) {
		this.buffer[position] = (byte) integerToWrite;
		this.buffer[position + 1] = (byte) (integerToWrite >> 8);
		this.buffer[position + 2] = (byte) (integerToWrite >> 16);
		this.buffer[position + 3] = (byte) (integerToWrite >> 24);
	}

	/**
	 * Executes the flush of the buffer from the `lastFlushedPosition` with length passed in argument.
	 */
	private void writeDataToOutputStream() {
		final int flushPosition = this.startPosition == -1 ? this.position : this.startPosition;
		final int flushLength = flushPosition - this.lastConsumedPosition;
		if (flushLength > 0) {
			IOUtils.executeSafely(
				flushLength,
				KryoException::new,
				theLength -> {
					this.outputStream.write(this.buffer, this.lastConsumedPosition, theLength);
					this.total += theLength;
					this.lastConsumedPosition += theLength;
				}
			);
		}
	}

	/**
	 * This method behaves like {@link #writeByte(byte)} with the exception that it doesn't affect the position in the buffer
	 * and writes to arbitrary position passed in argument. You need to reserve the space for the byte upwards otherwise
	 * data may be overwritten and binary stream corrupted.
	 *
	 * Implementation is copy & pasted from the original implementation.
	 */
	private void writeByteWithoutTouchingPosition(int position, byte byteToWrite) {
		this.buffer[position] = byteToWrite;
	}

}
