/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.utils.Assert;
import io.evitadb.utils.BitUtils;
import io.evitadb.utils.Crc32CWrapper;
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
import java.util.zip.Deflater;

/**
 * Extends Kryo {@link Output} to compute frame checksums and write each frame's length before its contents.
 * A frame is assembled in the in-memory buffer because its length is known only when the frame is complete.
 * {@link #markEnd(byte)} writes each finished frame to the output stream immediately. Frames whose length is
 * already known can bypass the assembly buffer through {@link #writeDirect(byte[], int, int)}.
 *
 * A record larger than the buffer is continued in chained frames by {@link StorageRecord}, which uses
 * {@link StorageRecord#CONTINUATION_BIT} and the {@link #onBufferOverflow} handler. A single value written by
 * `writeBytes`, `writeString` or `writeAscii` is never split across frames and must fit into one frame.
 *
 * File outputs borrow an exact-size buffer from the JVM-wide pool on their first write and return it after the
 * completed frame is appended. Detached outputs retain only an empty array. Continuation frames keep the same
 * buffer for the whole record; compression scratch space is borrowed just while finishing each frame.
 * Flush, reset and close also return buffers when no unfinished frame needs them.
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
	 * Checksum computation instance. Reused - instantiated only once.
	 */
	private final Checksum checksum;
	/**
	 * Deflater instance. Reused - instantiated only once.
	 */
	private final Deflater deflater;
	/**
	 * Buffer used for compressed data.
	 */
	@Nullable private byte[] deflateBuffer;
	/**
	 * Empty array used while a pooled output has no frame to assemble.
	 */
	private static final byte[] EMPTY_BUFFER = new byte[0];
	/**
	 * Shared frame buffer pool, or null for outputs that own their buffers.
	 */
	@Nullable private final ObservableOutputBufferPool bufferPool;
	/**
	 * Exact configured size, including the reserved tail.
	 */
	private final int bufferSize;
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
	/**
	 * Internal flag set to true when {@link #markCumulativeChecksumStart()} is called
	 * and output should calculate cumulative checksum for all data written.
	 */
	private boolean cumulatingChecksum;
	/**
	 * Holds the running cumulative checksum value since {@link #markCumulativeChecksumStart()}.
	 * This is combined with each record's full checksum (header + payload + tail) in finishRecord().
	 */
	private long cumulativeChecksum;

	/**
	 * Creates an ObservableOutput with a pre-allocated buffer.
	 *
	 * @param outputStream the output stream to write serialized data to
	 * @param buffer       the pre-allocated byte buffer for buffering writes
	 * @param checksum     the checksum instance for computing data integrity checksums
	 * @param deflater     the deflater for optional data compression, or null if compression is disabled
	 */
	public ObservableOutput(
		@Nonnull T outputStream,
		@Nonnull byte[] buffer,
		@Nonnull Checksum checksum,
		@Nullable Deflater deflater
	) {
		super(buffer);
		super.setOutputStream(outputStream);
		this.bufferPool = null;
		this.bufferSize = buffer.length;
		this.checksum = checksum;
		this.deflater = deflater;
		this.deflateBuffer = this.deflater == null ? null : new byte[buffer.length];
		// we need to hide CRC mandatory space from the Kryo output so that it asks for `require` when it reaches
		// the end of the capacity - this way we will have reserved space for safely writing the CRC checksum
		this.capacity = buffer.length - TAIL_MANDATORY_SPACE;
	}

	/**
	 * Creates an ObservableOutput with the specified frame buffer size.
	 *
	 * @param outputStream    the output stream to write serialized data to
	 * @param bufferSize      maximal size of one frame, including its header and checksum
	 * @param currentFileSize the current size of the output file for tracking total bytes written
	 * @param checksum        the checksum instance for computing data integrity checksums
	 * @param deflater        the deflater for optional data compression, or null if compression is disabled
	 */
	public ObservableOutput(
		@Nonnull T outputStream,
		int bufferSize,
		long currentFileSize,
		@Nonnull Checksum checksum,
		@Nullable Deflater deflater
	) {
		this(outputStream, new byte[bufferSize], checksum, deflater);
		this.total = currentFileSize;
	}

	/**
	 * Creates a detached output that borrows exact-size buffers only while assembling a frame or pending raw bytes.
	 * Compression scratch space is borrowed only while finishing a compressed frame.
	 *
	 * @param outputStream the output stream to write serialized data to
	 * @param bufferSize maximal size of one frame, including its header and checksum
	 * @param currentFileSize current physical file size used as the initial append position
	 * @param checksum checksum instance used to verify serialized data
	 * @param deflater compressor, or null to disable compression
	 * @param bufferPool shared pool providing exclusive frame and compression buffers
	 * @throws io.evitadb.exception.GenericEvitaInternalError if the frame size cannot fit the mandatory tail
	 */
	public ObservableOutput(
		@Nonnull T outputStream,
		int bufferSize,
		long currentFileSize,
		@Nonnull Checksum checksum,
		@Nullable Deflater deflater,
		@Nonnull ObservableOutputBufferPool bufferPool
	) {
		super(EMPTY_BUFFER);
		Assert.isPremiseValid(bufferSize > TAIL_MANDATORY_SPACE, "Buffer must fit the mandatory tail!");
		super.setOutputStream(outputStream);
		this.total = currentFileSize;
		this.checksum = checksum;
		this.deflater = deflater;
		this.bufferPool = bufferPool;
		this.bufferSize = bufferSize;
		this.maxCapacity = bufferSize;
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
	 * Initializes start position of the record payload - i.e. since this moment checksum starts to be computed
	 * for each byte written from now on.
	 */
	public void markPayloadStart() {
		this.payloadStartPosition = super.position;
		this.checksum.reset();
	}

	/**
	 * Initializes the position where overall record length should be written to.
	 */
	public void markRecordLengthPosition() {
		this.recordLengthPosition = super.position;
	}

	/**
	 * Method finalizes the record writing.
	 * When checksum should be computed, it's done at this moment.
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
	 * When checksum should be computed, it's done at this moment.
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
	 * When checksum should be computed, it's done at this moment.
	 * Record length is computed and is written to the specified position of the record as INT value.
	 * Method resets all record related flags and another record can be written afterwards.
	 * Record is marked as finished and can be safely written to the output stream (disk).
	 *
	 * @param controlByte the control byte to be written along with the record
	 * @param theDeflater the Deflater instance to use for compression, or null to disable compression
	 * @return physical file location of the completed frame
	 * @throws KryoException if the frame cannot be completed or appended
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
				if (this.bufferPool != null) {
					this.deflateBuffer = this.bufferPool.borrow(this.bufferSize);
				}
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
			if (this.checksum == Checksum.NO_OP) {
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
			// the lazy Supplier overload builds the diagnostic message only on actual failure, so this per-record premise
			// check stays allocation-free on the hot serialization path (the eager `cond, "..." + vars` overload would
			// otherwise concatenate a byte[] every record). `alteredControlByte` is reassigned earlier, so it is copied
			// into an effectively-final local for the message.
			final byte controlForError = alteredControlByte;
			Assert.isPremiseValid(
				length >= 0,
				() -> "Record length must be non-negative! Got: " + length + ", because " +
					"savedBytesByCompression is: " + savedBytesByCompression + ", " +
					"payloadLength is: " + payloadLength + ", alteredControlByte is: " + controlForError + "!"
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
			// same hot-path reasoning as the length premise above: the lazy Supplier keeps the message off the happy path
			Assert.isPremiseValid(
				supposedRecordStartPosition >= 0,
				() -> "Record start position must be non-negative! Got: " + supposedRecordStartPosition + ", because " +
					"savedBytesByCompressionSinceReset is: " + this.savedBytesByCompressionSinceReset + ", " +
					"total is: " + this.total + ", startPosition is: " + this.startPosition + ", lastConsumedPosition is: " + this.lastConsumedPosition + "!"
			);

			// write the record to the output stream; the primitive executeSafely overload avoids autoboxing the two int
			// args, keeping the IOException -> KryoException wrapping centralized
			IOUtils.executeSafely(
				payloadLength, savedBytesByCompression,
				KryoException::new,
				this::finishRecord
			);

			detachBuffer();
			// return location coordinates
			return new FileLocation(supposedRecordStartPosition, length);
		} finally {
			this.writingTail = false;
			if (this.bufferPool != null && theDeflater != null) {
				// Deflater retains its input array even after consuming it; do not pin a returned frame buffer.
				theDeflater.setInput(EMPTY_BUFFER);
			}
			if (this.bufferPool != null && this.deflateBuffer != null) {
				this.bufferPool.release(this.deflateBuffer);
				this.deflateBuffer = null;
			}
		}
	}

	/**
	 * Appends already framed bytes directly, without imposing the serialization buffer's capacity.
	 * Callers that know the frame length can emit its header, payload chunks and tail in order. Pending
	 * buffered bytes are flushed first, and direct bytes participate in the same physical byte accounting
	 * and cumulative checksum as buffered frames. No frame state is opened by this operation.
	 *
	 * @param bytes  bytes to append verbatim
	 * @param offset start offset in the array
	 * @param length number of bytes to append
	 * @throws io.evitadb.exception.EvitaInternalError if a buffered frame is open
	 * @throws KryoException if pending bytes or the direct chunk cannot be appended
	 */
	public void writeDirect(@Nonnull byte[] bytes, int offset, int length) {
		Assert.isPremiseValid(this.startPosition == -1, "Cannot append direct bytes while writing a record!");
		writeDataToOutputStream();
		detachBuffer();
		IOUtils.executeSafely(
			length,
			KryoException::new,
			theLength -> {
				this.outputStream.write(bytes, offset, theLength);
				this.total = Math.addExact(this.total, theLength);
			}
		);
		if (this.cumulatingChecksum && this.checksum != Checksum.NO_OP) {
			this.checksum.reset();
			this.checksum.update(bytes, offset, length);
			this.cumulativeChecksum = Crc32CWrapper.combine(
				this.cumulativeChecksum, this.checksum.getValue(), length
			);
		}
	}

	/**
	 * Returns the current checksum value.
	 *
	 * @return the current checksum as a long value.
	 */
	public long getChecksum() {
		return this.checksum.getValue();
	}

	/**
	 * Marks the start of cumulative checksum calculation. From this point, all bytes
	 * written to the output stream will be included in the cumulative checksum.
	 *
	 * The cumulative checksum covers the entire record:
	 * - Header (record length 4B + control byte 1B + generationId 8B = 13B)
	 * - Payload (compressed if compression used, uncompressed otherwise)
	 * - Tail CRC (8B)
	 *
	 * @throws io.evitadb.exception.EvitaInternalError if called while a record is being written
	 */
	public void markCumulativeChecksumStart() {
		Assert.isPremiseValid(
			this.startPosition == -1,
			"Cannot mark cumulative checksum start while writing a record!"
		);
		this.cumulatingChecksum = true;
		this.cumulativeChecksum = 0L;
	}

	/**
	 * Marks the start of cumulative checksum calculation with an initial checksum value.
	 * This allows continuing checksum calculation from a previously computed value, useful
	 * when appending data to an existing file where the initial content's CRC32C is known.
	 *
	 * The cumulative checksum covers the entire record:
	 * - Header (record length 4B + control byte 1B + generationId 8B = 13B)
	 * - Payload (compressed if compression used, uncompressed otherwise)
	 * - Tail CRC (8B)
	 *
	 * @param initialChecksum the initial checksum value to start from (typically computed
	 *                        from existing file content before appending)
	 * @throws io.evitadb.exception.EvitaInternalError if called while a record is being written
	 */
	public void markCumulativeChecksumStart(long initialChecksum) {
		Assert.isPremiseValid(
			this.startPosition == -1,
			"Cannot mark cumulative checksum start while writing a record!"
		);
		this.cumulatingChecksum = true;
		this.cumulativeChecksum = initialChecksum;
	}

	/**
	 * Marks the end of cumulative checksum calculation and returns the computed checksum.
	 *
	 * @return the cumulative CRC32C checksum covering all written bytes
	 */
	public long markCumulativeChecksumEnd() {
		this.cumulatingChecksum = false;
		final long result = this.cumulativeChecksum;
		this.cumulativeChecksum = 0L;
		return result;
	}

	/**
	 * Returns the current cumulative checksum value without stopping the accumulation.
	 * This allows reading intermediate checksum values during a cumulative checksum session.
	 *
	 * @return the current cumulative CRC32C checksum value
	 * @throws io.evitadb.exception.EvitaInternalError if cumulative checksum calculation is not active
	 */
	public long getCumulativeChecksum() {
		Assert.isPremiseValid(
			this.cumulatingChecksum,
			"Cannot get cumulative checksum when cumulative checksum calculation is not active!"
		);
		return this.cumulativeChecksum;
	}

	/**
	 * Calculates a checksum based on the given input parameters and updates
	 * the instance with the relevant data.
	 *
	 * @param savedBytesByCompression the number of bytes saved by compress.
	 *        This determines whether deflateBuffer or buffer is used for the checksum calculation.
	 * @param payloadLength the length of the payload used in checksum computation.
	 * @param alteredControlByte a single byte that will also be included in the checksum.
	 * @return the computed checksum as a long value.
	 */
	private long calculateChecksum(int savedBytesByCompression, int payloadLength, byte alteredControlByte) {
		if (savedBytesByCompression > 0) {
			this.checksum.update(this.deflateBuffer, 0, payloadLength - savedBytesByCompression);
		} else {
			this.checksum.update(this.buffer, this.payloadStartPosition, payloadLength);
		}
		this.checksum.update(alteredControlByte);
		return this.checksum.getValue();
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
		// calculate lengths for cumulative checksum
		final int headerLength = this.payloadStartPosition - this.lastConsumedPosition;
		final int actualPayloadLength = theSavedBytesByCompression > 0
			? thePayloadLength - theSavedBytesByCompression
			: thePayloadLength;

		// write header to the output stream
		this.outputStream.write(this.buffer, this.lastConsumedPosition, headerLength);

		// write payload to the output stream
		if (theSavedBytesByCompression > 0) {
			this.outputStream.write(this.deflateBuffer, 0, actualPayloadLength);
			this.savedBytesByCompressionSinceReset = Math.addExact(
				this.savedBytesByCompressionSinceReset, theSavedBytesByCompression);
		} else {
			this.outputStream.write(this.buffer, this.payloadStartPosition, thePayloadLength);
		}

		// write tail to the output stream (contains the per-record CRC)
		this.outputStream.write(this.buffer, this.position - TAIL_MANDATORY_SPACE, TAIL_MANDATORY_SPACE);

		// update cumulative checksum if enabled - reuse existing checksum field
		if (this.cumulatingChecksum && this.checksum != Checksum.NO_OP) {
			// reset checksum for full record calculation (per-record CRC already written to buffer)
			this.checksum.reset();

			// update with header bytes (includes finalized control byte)
			this.checksum.update(this.buffer, this.lastConsumedPosition, headerLength);

			// update with payload bytes (compressed or uncompressed - what was actually written)
			if (theSavedBytesByCompression > 0) {
				this.checksum.update(this.deflateBuffer, 0, actualPayloadLength);
			} else {
				this.checksum.update(this.buffer, this.payloadStartPosition, thePayloadLength);
			}

			// update with tail bytes (the CRC value itself)
			this.checksum.update(this.buffer, this.position - TAIL_MANDATORY_SPACE, TAIL_MANDATORY_SPACE);

			// get this record's full checksum and combine with running cumulative
			final long fullRecordChecksum = this.checksum.getValue();
			final int fullRecordLength = headerLength + actualPayloadLength + TAIL_MANDATORY_SPACE;

			// combine using CRC32C mathematical combination
			this.cumulativeChecksum = Crc32CWrapper.combine(
				this.cumulativeChecksum, fullRecordChecksum, fullRecordLength
			);
		}

		// update counters as if the uncompressed data was written
		final int consumedLength = this.position - this.lastConsumedPosition;
		this.total += consumedLength;
		this.lastConsumedPosition += consumedLength;
		// clear open record indexes
		this.startPosition = -1;
		this.payloadStartPosition = -1;
	}

	/**
	 * Executes serialization with an optional overflow handler and abandons only the unfinished frame on failure.
	 * Previously appended frames and pending bytes before the unfinished frame retain their positions and checksum.
	 *
	 * @param onBufferOverflow handler that closes continuation frames, or null to reject a frame that does not fit
	 * @param lambda serialization logic executed with the handler installed
	 * @param <S> type of the serialization result
	 * @return the result returned by the serialization logic
	 * @throws RuntimeException if serialization fails; the unfinished frame is abandoned before rethrowing
	 * @throws Error if serialization fails with an error; the unfinished frame is abandoned before rethrowing
	 */
	public <S> S doWithOnBufferOverflowHandler(
		@Nullable Consumer<ObservableOutput<T>> onBufferOverflow,
		@Nonnull Supplier<S> lambda
	) {
		try {
			this.onBufferOverflow = onBufferOverflow;
			return lambda.get();
		} catch (RuntimeException | Error exception) {
			abandonFrame();
			throw exception;
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

	/**
	 * Discards all pending bytes and resets byte counts, frame markers and cumulative checksum state.
	 * Use only when starting a new stream or discarding the output; serialization failures abandon just their frame.
	 */
	@Override
	public void reset() {
		super.reset();
		this.lastConsumedPosition = 0;
		this.startPosition = -1;
		this.payloadStartPosition = -1;
		this.recordLengthPosition = -1;
		this.savedBytesByCompressionSinceReset = 0;
		detachBuffer();
		// reset cumulative checksum state
		this.cumulatingChecksum = false;
		this.cumulativeChecksum = 0L;
	}

	/**
	 * This method overrides default behaviour and protects currently written record from being flushed or rewritten.
	 * This implementation flushes the already finalized contents of the buffer if there is not enough space and compacts
	 * the buffer in a way that moves unfinished data to the start of the buffer. When the buffer is not big enough after
	 * these operations {@link KryoException} is thrown.
	 *
	 * @param required number of contiguous bytes required for the next write
	 * @return true if a buffer was attached or its contents moved to make room
	 * @throws KryoException - when there is no space left in the buffer, can only happen if current record size exceeds
	 *                       `bufferSize` stated in constructor of this object.
	 */
	@Override
	protected boolean require(int required) throws KryoException {
		boolean attached = false;
		if (this.bufferPool != null && this.buffer.length == 0) {
			Assert.isPremiseValid(this.position == 0 && this.lastConsumedPosition == 0, "Invalid detached positions!");
			this.buffer = this.bufferPool.borrow(this.bufferSize);
			this.capacity = this.bufferSize - TAIL_MANDATORY_SPACE;
			attached = true;
		}
		final int reserve = this.writingTail ? TAIL_MANDATORY_SPACE : 0;
		if (this.capacity - this.position + reserve >= required) {
			// there is enough capacity available
			return attached;
		}
		// write data if there is enough accumulated
		writeDataToOutputStream();
		// we've moved far in the buffer, and we can rewind
		if (this.lastConsumedPosition > 0) {
			final int activeBufferSize = this.startPosition == -1 ? 0 : this.position - this.lastConsumedPosition;
			// arraycopy preserves overlapping source bytes as if copied through a temporary array.
			System.arraycopy(this.buffer, this.lastConsumedPosition, this.buffer, 0, activeBufferSize);
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
						this.position = this.atomicPosition;
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
	 * Flushes finished contents of this buffer regardless of their size and returns an idle pooled buffer.
	 *
	 * @throws KryoException if completed bytes or the underlying stream cannot be flushed
	 */
	@Override
	public void flush() throws KryoException {
		if (this.outputStream == null) {
			return;
		}
		writeDataToOutputStream();
		detachBuffer();
		IOUtils.close(
			KryoException::new,
			this.outputStream::flush
		);
	}

	/**
	 * Flushes completed bytes and closes the underlying stream, abandoning any unfinished frame.
	 * A pooled buffer is returned once it contains no pending bytes.
	 *
	 * @throws KryoException if flushing completed bytes fails
	 */
	@Override
	public void close() throws KryoException {
		try {
			super.close();
		} finally {
			abandonFrame();
		}
	}

	/**
	 * Drops only the unfinished frame and clears its markers, then returns the buffer if no bytes remain pending.
	 * Completed bytes before the frame remain available for the next flush. Already appended continuation frames
	 * remain unreferenced bytes in the append-only stream, so append positions and cumulative accounting stay intact.
	 */
	private void abandonFrame() {
		if (this.startPosition != -1) {
			this.position = this.startPosition;
		}
		this.startPosition = -1;
		this.payloadStartPosition = -1;
		this.recordLengthPosition = -1;
		this.atomicPosition = -1;
		detachBuffer();
	}

	/**
	 * Returns an idle pooled buffer exactly once, replacing it with the shared empty array.
	 * An open frame, pending bytes or an executing continuation handler keeps the buffer attached so its positions
	 * remain valid until those bytes are consumed or abandoned.
	 */
	private void detachBuffer() {
		if (this.bufferPool != null && !this.overflowing && this.startPosition == -1 &&
			this.position == this.lastConsumedPosition && this.buffer.length != 0) {
			final byte[] released = this.buffer;
			this.buffer = EMPTY_BUFFER;
			this.capacity = 0;
			this.position = 0;
			this.lastConsumedPosition = 0;
			this.bufferPool.release(released);
		}
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
			this.atomicPosition = this.position;
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
			// Update cumulative checksum for raw bytes written outside record lifecycle
			if (this.cumulatingChecksum && this.startPosition == -1 && this.checksum != Checksum.NO_OP) {
				this.checksum.reset();
				this.checksum.update(this.buffer, this.lastConsumedPosition, flushLength);
				this.cumulativeChecksum = Crc32CWrapper.combine(
					this.cumulativeChecksum, this.checksum.getValue(), flushLength
				);
			}
			// the primitive executeSafely overload avoids autoboxing `flushLength`, keeping the
			// IOException -> KryoException wrapping centralized
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
