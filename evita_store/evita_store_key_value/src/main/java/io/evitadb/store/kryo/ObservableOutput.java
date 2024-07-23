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

package io.evitadb.store.kryo;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.exception.StorageException;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.utils.Assert;
import io.evitadb.utils.BitUtils;
import lombok.Getter;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.CRC32C;

import static java.util.Optional.ofNullable;

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
	public static final int TAIL_MANDATORY_SPACE = 8;
	static final long NULL_CRC = 0xFFFFFFFFFFFFFFFFL;
	/**
	 * Flush size that determines whether non-flushed but finalized contents of the current buffer should be written
	 * to the output stream (disk).
	 */
	@Getter private final int flushSize;
	/**
	 * CRC32C computation instance. Reused - instantiated only once.
	 */
	private CRC32C crc32C;
	/**
	 * Contains floating position in the buffer that marks bytes already written to the output stream (disk). I.e. every
	 * byte before this position were already flushed out.
	 */
	private int lastFlushedPosition;
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
	private Consumer<ObservableOutput<T>> onBufferOverflow;
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
	 * Initializes {@link ObservableInput} with recommended settings for SSD drives.
	 *
	 * @implNote <a href="https://codecapsule.com/2014/02/12/coding-for-ssds-part-6-a-summary-what-every-programmer-should-know-about-solid-state-drives/">Source</a>
	 * @param bufferSize maximal size of the single record that can be stored
	 */
	public ObservableOutput(T outputStream, int bufferSize, long currentFileSize) {
		this(outputStream, 16_384, bufferSize, currentFileSize);
	}

	public ObservableOutput(T outputStream, int flushSize, int bufferSize, long currentFileSize) {
		super(outputStream, bufferSize);
		if (bufferSize < flushSize) {
			throw new StorageException("Buffer size cannot be lower than flush limit with some reserve space!");
		}
		this.flushSize = flushSize;
		this.total = currentFileSize;
		// we need to hide CRC mandatory space from the Kryo output so that it asks for `require` when it reaches
		// the end of the capacity - this way we will have reserved space for safely writing the CRC checksum
		this.capacity = bufferSize - TAIL_MANDATORY_SPACE;
	}

	/**
	 * Enables CRC32C checksum computation for each record has been written to the file.
	 */
	public ObservableOutput<T> computeCRC32() {
		if (this.crc32C == null) {
			this.crc32C = new CRC32C();
		}
		return this;
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
		if (crc32C != null) {
			crc32C.reset();
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
	 * When CRC32C checksum should be computed, it's done in this moment.
	 * Record length is computed and is written to the specified position of the record as INT value.
	 * Method resets all record related flags and another record can be written afterwards.
	 * Record is marked as finished and can be safely written to the output stream (disk) - but may not depending on
	 * the {@link #getFlushSize()}.
	 */
	public FileLocation markEnd(byte controlByte) {
		try {
			Assert.isPremiseValid(payloadStartPosition != -1, "Payload start position must be initialized!");
			this.writingTail = true;
			final Optional<CRC32C> crc32Ref = ofNullable(crc32C);
			final byte alteredControlByte = crc32Ref.isPresent() ?
				BitUtils.setBit(controlByte, StorageRecord.CRC32_BIT, true) : controlByte;
			// compute CRC32 checksum if requested
			final long crc = crc32Ref
				.map(it -> {
					it.update(buffer, payloadStartPosition, position - payloadStartPosition);
					it.update(alteredControlByte);
					return it.getValue();
				})
				.orElse(NULL_CRC);
			// write CRC32 or blank
			super.writeLong(crc);
			// store current position
			final int savedPosition = position;
			final int length = position - startPosition;
			// seek backwards to the place of record length and write it
			writeIntWithoutTouchingPosition(this.recordLengthPosition, length);
			writeByteWithoutTouchingPosition(this.recordLengthPosition + 4, alteredControlByte);
			// restore position to the EOF
			super.position = savedPosition;
			// compute file coordinates before resetting positions
			final FileLocation fileLocation = new FileLocation(total + (startPosition - lastFlushedPosition), length);
			// clear open record indexes
			this.startPosition = -1;
			this.payloadStartPosition = -1;

			flushIfNecessary();

			// return location coordinates
			return fileLocation;
		} finally {
			writingTail = false;
		}
	}

	/**
	 * This method opens writing into output in mode that handles buffer overflow situations using passed handler.
	 */
	public <S> S doWithOnBufferOverflowHandler(Consumer<ObservableOutput<T>> onBufferOverflow, Supplier<S> lambda) {
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
	public void setBuffer(byte[] buffer) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setBuffer(byte[] buffer, int maxBufferSize) {
		throw new UnsupportedOperationException();
	}

	@Override
	public byte[] toBytes() {
		byte[] newBuffer = new byte[this.position()];
		System.arraycopy(buffer, lastFlushedPosition, newBuffer, 0, newBuffer.length);
		return newBuffer;
	}

	@Override
	public int position() {
		return position - lastFlushedPosition;
	}

	@Override
	public void setPosition(int position) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long total() {
		return total + position();
	}

	@Override
	public void reset() {
		super.reset();
		this.lastFlushedPosition = 0;
		this.startPosition = -1;
		this.payloadStartPosition = -1;
		this.recordLengthPosition = -1;
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
		final int reserve = writingTail ? TAIL_MANDATORY_SPACE : 0;
		if (capacity - position + reserve >= required) {
			// there is enough capacity available
			return false;
		}
		// flush to disk if there enough accumulated data
		flush();
		// we've moved far in the buffer, and we can rewind
		if (lastFlushedPosition > 0) {
			final int activeBufferSize = this.startPosition == -1 ? 0 : this.position - this.lastFlushedPosition;
			if (activeBufferSize < this.lastFlushedPosition) {
				// if it's safe to copy within single buffer - do it
				System.arraycopy(this.buffer, this.lastFlushedPosition, this.buffer, 0, activeBufferSize);
			} else {
				// it's not safe - data would be overwritten - we need to allocate new byte buffer
				final byte[] newBuffer = new byte[capacity + TAIL_MANDATORY_SPACE];
				System.arraycopy(this.buffer, this.lastFlushedPosition, newBuffer, 0, activeBufferSize);
				this.buffer = newBuffer;
			}
			recomputePositionsToNewStart();
		}
		if (capacity - position + reserve < required) {
			if (onBufferOverflow == null || overflowing) {
				// there is not enough due to big active record
				throw new KryoException("Active record exceeds buffer size of " + capacity + "B!");
			} else {
				try {
					overflowing = true;
					// check whether there is not value partially written only
					if (atomicPosition == -1) {
						// let the handler resolve the situation and try again
						onBufferOverflow.accept(this);
					} else {
						// if the atomic position is set - we need to rewind to the start of currently written atomic value
						// copy the unfinished part and move it to the new buffer
						final byte[] partiallyWrittenValue = Arrays.copyOfRange(buffer, atomicPosition, position);
						position = atomicPosition;
						// let the handler resolve the situation and try again
						onBufferOverflow.accept(this);
						// now write the partially written bytes
						super.writeBytes(partiallyWrittenValue, 0, partiallyWrittenValue.length);
						// reset atomic position
						atomicPosition = 0;
					}
					require(required);
				} finally {
					overflowing = false;
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
		if (outputStream == null) {
			return;
		}
		final int flushPosition = startPosition == -1 ? position : startPosition;
		final int flushLength = flushPosition - lastFlushedPosition;
		doFlush(flushLength);
	}

	@Override
	public void writeBytes(byte[] bytes, int offset, int count) throws KryoException {
		// this method allows writing multiple bytes, and we need to be able to rewind when the buffer is exhausted
		try {
			//safely init atomic position
			atomicPosition = position;
			super.writeBytes(bytes, offset, count);
		} finally {
			//and reset it when the block is exited
			atomicPosition = -1;
		}
	}

	@Override
	public void writeString(String value) throws KryoException {
		// this method allows writing multiple bytes, and we need to be able to rewind when the buffer is exhausted
		try {
			//safely init atomic position
			atomicPosition = position;
			super.writeString(value);
		} finally {
			//and reset it when the block is exited
			atomicPosition = -1;
		}
	}

	@Override
	public void writeAscii(String value) throws KryoException {
		// this method allows writing multiple bytes, and we need to be able to rewind when the buffer is exhausted
		try {
			//safely init atomic position
			atomicPosition = position;
			super.writeAscii(value);
		} finally {
			//and reset it when the block is exited
			atomicPosition = -1;
		}
	}

	/**
	 * This method initializes positions after compacting the buffer (ie. writing current record from the buffer start).
	 */
	private void recomputePositionsToNewStart() {
		// we have to align new starts
		if (this.startPosition != -1) {
			this.startPosition = this.startPosition - this.lastFlushedPosition;
			this.payloadStartPosition = this.payloadStartPosition == -1 ? -1 : this.payloadStartPosition - this.lastFlushedPosition;
			this.recordLengthPosition = this.recordLengthPosition - this.lastFlushedPosition;
		}
		this.position = this.position - this.lastFlushedPosition;
		this.lastFlushedPosition = 0;
	}

	/**
	 * Flushes finished contents of this buffer but only if their size exceeds {@link #getFlushSize()}.
	 */
	private void flushIfNecessary() {
		final int flushPosition = startPosition == -1 ? position : startPosition;
		final int flushLength = flushPosition - lastFlushedPosition;
		if (flushLength >= flushSize) {
			doFlush(flushLength);
		}
	}

	/**
	 * Executes the flush of the buffer from the `lastFlushedPosition` with length passed in argument.
	 */
	private void doFlush(int flushLength) {
		if (flushLength > 0) {
			try {
				outputStream.write(buffer, lastFlushedPosition, flushLength);
				outputStream.flush();
			} catch (IOException ex) {
				throw new KryoException(ex);
			}
			this.total += flushLength;
			this.lastFlushedPosition += flushLength;
		}
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
	 * This method behaves like {@link #writeByte(byte)} with the exception that it doesn't affect the position in the buffer
	 * and writes to arbitrary position passed in argument. You need to reserve the space for the byte upwards otherwise
	 * data may be overwritten and binary stream corrupted.
	 *
	 * Implementation is copy & pasted from the original implementation.
	 */
	private void writeByteWithoutTouchingPosition(int position, byte byteToWrite) {
		buffer[position] = byteToWrite;
	}

}
