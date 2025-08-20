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
import com.esotericsoftware.kryo.io.Input;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.exception.CorruptedRecordException;
import io.evitadb.store.offsetIndex.exception.KryoSerializationException;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.stream.AbstractRandomAccessInputStream;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.Assert;
import io.evitadb.utils.BitUtils;
import io.evitadb.utils.IOUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.CRC32C;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static io.evitadb.store.kryo.ObservableOutput.TAIL_MANDATORY_SPACE;
import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * This observable input extends original Kryo {@link Input} allowing to automatically verify CRC32C checksums after
 * record has been read from the storage. It also verifies whether the read record size match expected record size
 * stored along with the record.
 *
 * When {@link ObservableInput} is initialized with {@link RandomAccessFileInputStream} it can perform {@link #seek(FileLocation)}
 * operation that allows it to skim through random record locations of the file and read records one by one. Reading
 * buffer size is automatically adapted to expected record size SSD page size is effectively used (see
 * https://www.extremetech.com/extreme/210492-extremetech-explains-how-do-ssds-work).
 * Use {@link ObservableInput#ObservableInput(InputStream)} method to use recommended settings for SSD drives.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class ObservableInput<T extends InputStream> extends Input {
	/**
	 * CRC32C computation instance. Reused - instantiated only once.
	 */
	private CRC32C crc32C;
	/**
	 * Inflater instance. Reused - instantiated only once.
	 */
	private Inflater inflater;
	/**
	 * Input buffer for decompression.
	 */
	private byte[] decompressionBuffer;
	/**
	 * Bytes read from underlying input stream since last {@link #reset()}.
	 */
	private int bytesReadFromInputStreamSinceReset;
	/**
	 * Start of the sensible data in the {@link #decompressionBuffer}.
	 */
	private int decompressionBufferStart;
	/**
	 * Limit of the {@link #decompressionBuffer} that is filled with actual data.
	 */
	private int decompressionBufferPeek;
	/**
	 * Contains number of bytes that were present in {@link Inflater#getBytesRead()} when the decompression buffer
	 * was filled in for the last time.
	 */
	private int inflaterReadBytesOnLastDecompressionBufferFill;
	/**
	 * Flag indicating whether the record payload is compressed.
	 */
	private boolean compressed;
	/**
	 * Record start position. Use {@link #markStart()} to init.
	 */
	@Getter private int startPosition = -1;
	/**
	 * Record payload (i.e. real record contents). Use {@link #markPayloadStart(int, byte)} to init.
	 */
	@Getter private int payloadStartPosition = -1;
	/**
	 * Contains number of bytes that were read since {@link #markStart()} at the time {@link #markPayloadStart(int, byte)} was
	 * executed. This number represents part in active buffer, when the buffer is wrapped around part of the payload
	 * prefix is in {@link #accumulatedLength}.
	 */
	private int payloadPrefixLength = 0;
	/**
	 * Contains expected number of bytes for the record payload.
	 */
	private int expectedPayloadLength = 0;
	/**
	 * Contains number of bytes that were read from underlying input stream since {@link #markPayloadStart(int, byte)}
	 * was executed.
	 */
	private int payloadReadLength = 0;
	/**
	 * Contains number of bytes that were decompressed from the compressed payload.
	 */
	private int payloadDecompressedLength = 0;
	/**
	 * Counter accumulating already read bytes since the {@link #markStart()} when buffer is filled in and must be
	 * reused for different content.
	 */
	private int accumulatedLength;
	/**
	 * Contains expected length of the record and limits the count of bytes read from this input stream.
	 */
	private int expectedLength = -1;
	/**
	 * This piece of logic should handle the situation when the deserializes data that exceeds the record expected size.
	 * Handler might allow to fetch next chunk of the data and reset the input stream.
	 */
	@Nullable private Consumer<ObservableInput<T>> onBufferOverflow;
	/**
	 * Internal flag that is set to true only when {@link #onBufferOverflow} handler is being executed.
	 */
	private boolean overflowing;
	/**
	 * Internal flag that is set to true only when {@link #markPayloadStart(int, byte)} method was called and method
	 * {@link #markEnd(byte)}  was not yet called. When this flag is true we read payload.
	 */
	private boolean readingPayload;
	/**
	 * Internal flag that is set to true only when {@link #markEnd(byte)}  method is running - which means that space
	 * for the tail no longer needs to be kept in reserve.
	 */
	private boolean readingTail;
	/**
	 * Contains offset in the buffer that was filled by {@link #fill(byte[], int, int)} called the last time.
	 * Represents index of meaningful content of the buffer.
	 */
	private int lastOffset = -1;
	/**
	 * Contains length of the meaningful content in the buffer that was filled by {@link #fill(byte[], int, int)}
	 * starting with {@link #lastOffset}.
	 */
	private int lastCount = -1;
	/**
	 * Contains original Kryo {@link #limit} value when it's overwritten by our custom logic that respects the end
	 * of the record defined by {@link #expectedLength}. This query is not known to Kryo - the limit represents
	 * the end of fetched data in the {@link #buffer} for kryo, but in our case the data stream might continue with
	 * another record. We need to enforce the end of the record and apply {@link #onBufferOverflow} logic between
	 * the records and this is checked only in {@link #require(int)} method. The `require` method is due to performance
	 * reasons invoked by Kryo only when it knows, that it reached the limit of read data in the {@link #buffer}.
	 * To force it we need to mimic the premature end of data limit when we know we have expected end of the record
	 * within the current buffer. The actual limit can be removed once the end of the record is handled
	 * by {@link #onBufferOverflow} logic.
	 */
	private int actualLimit = -1;

	/**
	 * Initializes ObservableInput with recommended settings for SSD drives.
	 */
	public ObservableInput(@Nonnull T inputStream) {
		this(inputStream, 16_384);
	}

	public ObservableInput(@Nonnull T inputStream, int bufferSize) {
		super(inputStream, bufferSize);
	}

	public ObservableInput(@Nonnull T inputStream, @Nonnull byte[] buffer) {
		super(buffer);
		setInputStream(inputStream);
	}

	@Override
	public T getInputStream() {
		//noinspection unchecked
		return (T) super.getInputStream();
	}

	/**
	 * This method overrides original implementation and clears also all the internal counters.
	 */
	@Override
	public void reset() {
		super.reset();
		this.accumulatedLength = 0;
		this.lastOffset = -1;
		this.lastCount = -1;
		this.startPosition = -1;
		this.payloadStartPosition = -1;
		this.payloadPrefixLength = 0;
		this.expectedLength = -1;
		this.actualLimit = -1;
		this.readingPayload = false;
		this.overflowing = false;
		this.compressed = false;
		this.decompressionBufferStart = 0;
		this.decompressionBufferPeek = 0;
		this.bytesReadFromInputStreamSinceReset = 0;
		this.inflaterReadBytesOnLastDecompressionBufferFill = 0;
		this.payloadReadLength = 0;
		this.payloadDecompressedLength = 0;
		this.expectedPayloadLength = -1;
		this.capacity = this.buffer.length;
	}

	/**
	 * This method overrides original implementation. The single change is in {@link #require(int)} method call.
	 * In original implementation it's called after position has been moved, but we need to call it before it so that
	 * {@link #overflowing} logic can kick into an action.
	 */
	@Override
	public void skip(int count) throws KryoException {
		// this method doesn't call `require` at the start of the super implementation, and we need to trigger
		// onBufferOverflow logic immediately when there is no content available - that's why the check is here
		int skipCount = Math.min(this.limit - this.position, count);
		while (true) {
			require(skipCount);
			this.position += skipCount;
			count -= skipCount;
			if (count == 0) break;
			skipCount = Math.min(count, this.capacity);
		}
	}

	/**
	 * Decompresses the provided compressed byte array and returns the decompressed data.
	 *
	 * @param compressedBytes the input byte array containing compressed data
	 * @return a byte array containing the decompressed data
	 */
	public int decompress(byte[] compressedBytes, byte[] decompressedBytes) throws KryoException {
		Assert.isPremiseValid(
			!this.compressed,
			"Decompression buffer is already in use, can't decompress another data!"
		);
		this.inflater.reset();
		this.inflater.setInput(compressedBytes, 0, compressedBytes.length);

		try {
			int n;
			while ((n = this.inflater.inflate(decompressedBytes, 0, decompressedBytes.length)) == 0) {
				if (this.inflater.finished() || this.inflater.needsDictionary()) {
					throw new KryoException("Unexpected end of ZLIB input stream");
				}
				if (this.inflater.needsInput()) {
					throw new KryoException("Expected more data in ZLIB byte array.");
				}
			}

			if (this.inflater.finished()) {
				return n;
			} else {
				throw new KryoException("Expected more data in ZLIB byte array.");
			}
		} catch (DataFormatException e) {
			throw new KryoException("Unexpected end of ZLIB input stream", e);
		} finally {
			this.inflater.reset();
		}
	}

	/**
	 * This method overrides default implementation by uncompressing data from the inflater if the record is compressed.
	 * The read from the underlying stream is performed only when the inflater needs more data to uncompress.
	 *
	 * @param buffer to fill data in
	 * @param offset offset to fill the data to
	 * @param count  count of bytes to fill
	 * @return number of bytes filled in
	 * @throws KryoException when the underlying stream throws an exception
	 */
	@Override
	protected int fill(byte[] buffer, int offset, int count) throws KryoException {
		if (this.inputStream == null) {
			return -1;
		} else if (count == 0) {
			return 0;
		}
		if (this.compressed) {
			Assert.isPremiseValid(this.inflater != null, "Record is compressed and ObservableInput has inflate support disabled!");
			try {
				int n;
				while ((n = this.inflater.inflate(buffer, offset, count)) == 0) {
					if (this.inflater.finished() || this.inflater.needsDictionary()) {
						return -1;
					}
					if (this.inflater.needsInput()) {
						// update CRC32C checksum before the buffer gets overwritten
						if (this.crc32C != null) {
							this.crc32C.update(
								this.decompressionBuffer,
								this.decompressionBufferStart,
								this.decompressionBufferPeek - this.decompressionBufferStart
							);
						}
						// check how much data has been read from the decompression buffer so far
						final int currentlyReadBytes = Math.toIntExact(this.inflater.getBytesRead());
						final int leftToRead = this.expectedPayloadLength - currentlyReadBytes;
						if (leftToRead > 0) {
							// attempt to read next chunk of data from the underlying stream into the decompression buffer
							final int readLength = IOUtils.executeSafely(
								KryoException::new,
								() -> this.inputStream.read(
									this.decompressionBuffer, 0,
									this.decompressionBuffer.length
								)
							);
							if (readLength == -1) {
								throw new KryoException("Unexpected end of ZLIB input stream");
							} else {
								// data has been read successfully, now we need to update the inflater and counters
								this.inflaterReadBytesOnLastDecompressionBufferFill = currentlyReadBytes;
								this.decompressionBufferStart = 0;
								this.decompressionBufferPeek = readLength;
								this.payloadReadLength += readLength;
								this.bytesReadFromInputStreamSinceReset += readLength;
								this.inflater.setInput(this.decompressionBuffer, 0, readLength);
							}
						} else {
							// there are no data to read, we need to trigger overflow handler
							handleOverflow(
								this.accumulatedLength + this.payloadPrefixLength + this.payloadReadLength + TAIL_MANDATORY_SPACE,
								count
							);
							return n;
						}
					}
				}
				this.payloadDecompressedLength += n;
				return n;
			} catch (DataFormatException e) {
				final String message = e.getMessage();
				throw new KryoException(message != null ? message : "Invalid ZLIB data format");
			}
		} else {
			try {
				// read data from the underlying input stream
				final int readLength = this.inputStream.read(buffer, offset, count);
				this.bytesReadFromInputStreamSinceReset += readLength;
				return readLength;
			} catch (IOException ex) {
				throw new KryoException(ex);
			}
		}
	}

	/**
	 * This method overrides original implementation in that sense that it computes CRC32C checksum at the moment when
	 * current buffer contents are about to be lost because new data needs to be written to it (see method
	 * {@link #updateLostBuffer(int, int)}). It also collects the information about data length that have been already
	 * read for current record - so that we can verify this in the end.
	 *
	 * Contents of this method were copied & pasted from the original (we should promote a patch to original library here).
	 * The changes are:
	 * - handling {@link #overflowing} logic at the start of the method
	 * - calling method {@link #updateLostBuffer(int, int)}.
	 */
	@Override
	protected int require(int required) throws KryoException {
		/* EXTENSION */
		int limit = this.actualLimit == -1 || this.compressed || this.readingTail ? this.limit : this.actualLimit;
		final int reserve = this.readingTail ? 0 : TAIL_MANDATORY_SPACE;
		final int totalReadLengthWithReserve = computeTotalReadLength() + reserve;
		// if we read compressed payload
		if (this.compressed) {
			// and we've decompressed all expected bytes and there is not enough unprocessed bytes in current buffer
			if (limit - this.position < required && this.inflater.finished() && this.inflater.getBytesRead() == this.expectedPayloadLength) {
				// trigger overflow situation
				handleOverflow(totalReadLengthWithReserve, required);
				// update limit after overflow handler was executed
				limit = this.actualLimit == -1 || this.compressed || this.readingTail ? this.limit : this.actualLimit;
			}
		} else if (this.expectedLength != -1 && totalReadLengthWithReserve + required > this.expectedLength) {
			// else if we've read all the current buffer and more is required, trigger overflow situation
			handleOverflow(totalReadLengthWithReserve, required);
			// update limit after overflow handler was executed
			limit = this.actualLimit == -1 || this.compressed || this.readingTail ? this.limit : this.actualLimit;
		}
		/* END OF EXTENSION */

		int remaining = limit - this.position;
		if (remaining >= required) return remaining;
		if (required > this.capacity)
			throw new KryoException("Buffer too small: capacity: " + this.capacity + ", required: " + required);

		int count;
		// Try to fill the buffer.
		if (remaining > 0) {
			count = fill(this.buffer, limit, this.capacity - limit);
			if (count == -1) throw new KryoException("Buffer underflow.");
			remaining += count;
			if (remaining >= required) {
				/* EXTENSION */
				this.limit += count;
				if (!this.compressed) {
					this.limit = constraintLimitWithRecordLength();
				}
				/* END OF EXTENSION */
				return remaining;
			}
		}

		/* EXTENSION */
		updateLostBuffer(remaining, this.capacity - remaining);
		/* END OF EXTENSION */

		// Was not enough, compact and try again.
		System.arraycopy(this.buffer, this.position, this.buffer, 0, remaining);
		this.total += this.position;
		this.position = 0;

		while (true) {
			count = fill(this.buffer, remaining, this.capacity - remaining);
			if (count == -1) {
				if (remaining >= required) break;
				throw new KryoException("Buffer underflow.");
			}
			remaining += count;
			if (remaining >= required) break; // Enough has been read.
		}

		/* EXTENSION */
		this.limit = remaining;
		if (!this.compressed) {
			this.limit = constraintLimitWithRecordLength();
		}
		/* END OF EXTENSION */
		return remaining;
	}

	/**
	 * This method overrides original implementation in that sense that it computes CRC32C checksum at the moment when
	 * current buffer contents are about to be lost because new data needs to be written to it (see method
	 * {@link #updateLostBuffer(int, int)}). It also collects the information about data length that have been already
	 * read for current record - so that we can verify this in the end.
	 *
	 * Contents of this method were copied & pasted from the original (we should promote a patch to original library here).
	 * The single change is represented by calling method {@link #updateLostBuffer(int, int)}.
	 */
	@Override
	protected int optional(int optional) throws KryoException {
		/* EXTENSION */
		if (this.position + optional > Math.min(this.limit, this.capacity)) {
			final int reserve = this.readingTail ? 0 : TAIL_MANDATORY_SPACE;
			final int totalReadLength = computeTotalReadLength();
			if (this.expectedLength != -1 && totalReadLength + reserve + optional > this.expectedLength) {
				return this.limit - this.position;
			}
		}
		/* END OF EXTENSION */
		int remaining = this.limit - this.position;
		if (remaining >= optional) return optional;
		optional = Math.min(optional, this.capacity);

		int count;

		// Try to fill the buffer.
		count = fill(this.buffer, this.limit, this.capacity - this.limit);
		if (count == -1) return remaining == 0 ? -1 : Math.min(remaining, optional);
		remaining += count;
		if (remaining >= optional) {
			/* EXTENSION */
			this.limit += count;
			this.limit = constraintLimitWithRecordLength();
			/* END OF EXTENSION */
			return optional;
		}

		/* EXTENSION */
		final int attemptedToRead = this.capacity - remaining;
		updateLostBuffer(remaining, attemptedToRead);
		/* END OF EXTENSION */

		// Was not enough, compact and try again.
		System.arraycopy(this.buffer, this.position, this.buffer, 0, remaining);
		this.total += this.position;
		this.position = 0;

		while (true) {
			count = fill(this.buffer, remaining, this.capacity - remaining);
			if (count == -1) break;
			remaining += count;
			if (remaining >= optional) break; // Enough has been read.
		}

		/* EXTENSION */
		this.limit = remaining;
		if (!this.compressed) {
			this.limit = constraintLimitWithRecordLength();
		}
		return this.limit == 0 ? -1 : Math.min(remaining, optional);
		/* END OF EXTENSION */
	}

	/**
	 * This method overrides original implementation. The single change is in {@link #require(int)} method call.
	 * In original implementation it's called after position has been moved, but we need to call it before it so that
	 * {@link #overflowing} logic can kick into an action.
	 */
	@Override
	public int read(byte[] bytes, int offset, int count) throws KryoException {
		// this method doesn't call `require` at the start of the super implementation, and we need to trigger
		// onBufferOverflow logic immediately when there is no content available - that's why the check is here
		if (bytes == null) throw new KryoSerializationException("bytes cannot be null.");
		int startingCount = count;
		int copyCount = Math.min(this.limit - this.position, count);
		while (true) {
			require(copyCount);
			System.arraycopy(this.buffer, this.position, bytes, offset, copyCount);
			this.position += copyCount;
			count -= copyCount;
			if (count == 0) break;
			offset += copyCount;
			copyCount = optional(count);
			if (copyCount == -1) {
				// End of data.
				if (startingCount == count) return -1;
				break;
			}
			if (this.position == this.limit) break;
		}
		return startingCount - count;
	}

	/**
	 * This method overrides original implementation. The single change is in {@link #require(int)} method call.
	 * In original implementation it's called after position has been moved, but we need to call it before it so that
	 * {@link #overflowing} logic can kick into an action.
	 */
	@Override
	public void readBytes(byte[] bytes, int offset, int count) throws KryoException {
		// this method doesn't call `require` at the start of the super implementation, and we need to trigger
		// onBufferOverflow logic immediately when there is no content available - that's why the check is here
		if (bytes == null) throw new KryoSerializationException("bytes cannot be null.");
		int copyCount = Math.min(this.limit - this.position, count);
		while (true) {
			require(copyCount);
			System.arraycopy(this.buffer, this.position, bytes, offset, copyCount);
			this.position += copyCount;
			count -= copyCount;
			if (count == 0) break;
			offset += copyCount;
			copyCount = Math.min(count, this.capacity);
		}
	}

	/**
	 * This method overrides original implementation. The single change is in {@link #require(int)} method call.
	 * In original implementation it's called after position has been moved, but we need to call it before it so that
	 * {@link #overflowing} logic can kick into an action.
	 */
	@Override
	public String readString() {
		require(1);
		return super.readString();
	}

	/**
	 * This method overrides original implementation. The single change is in {@link #require(int)} method call.
	 * In original implementation it's called after position has been moved, but we need to call it before it so that
	 * {@link #overflowing} logic can kick into an action.
	 */
	@Override
	public StringBuilder readStringBuilder() {
		require(1);
		return super.readStringBuilder();
	}

	/**
	 * This method allows reading a single int from the buffer outside of {@link #markStart()} and {@link #markEnd(byte)}
	 * method calls. It's used when there is numeric information between consecutive {@link StorageRecord}. We can't
	 * simply call {@link #readInt()} on observable input because it would trigger {@link #require(int)} method that
	 * interoperates with internal counters expecting a {@link StorageRecord} lifecycle. This method will set and clears
	 * all the internal counters so that the single integer can be read "off the record".
	 *
	 * @return int read from the buffer
	 */
	public int simpleIntRead() {
		this.startPosition = super.position;
		this.expectedLength = 4;
		this.accumulatedLength = 0;
		this.payloadStartPosition = this.position;
		this.payloadPrefixLength = computeReadLengthUpTo(this.payloadStartPosition);
		this.actualLimit = this.limit > 0 ? this.limit : -1;
		this.limit = Math.min(this.buffer.length, constraintLimitWithRecordLength(0) + this.payloadPrefixLength);
		this.readingTail = true;
		try {
			return readInt();
		} finally {
			this.limit = this.actualLimit >= 0 ? this.actualLimit : this.limit;
			this.actualLimit = -1;
			this.startPosition = -1;
			this.expectedLength = -1;
			this.accumulatedLength = 0;
			this.payloadStartPosition = -1;
			this.payloadPrefixLength = 0;
			this.readingTail = false;
		}
	}

	/**
	 * This method allows reading a single long from the buffer outside of {@link #markStart()} and {@link #markEnd(byte)}
	 * method calls. It's used when there is numeric information between consecutive {@link StorageRecord}. We can't
	 * simply call {@link #readLong()} on observable input because it would trigger {@link #require(int)} method that
	 * interoperates with internal counters expecting a {@link StorageRecord} lifecycle. This method will set and clears
	 * all the internal counters so that the single long can be read "off the record".
	 *
	 * @return long read from the buffer
	 */
	public long simpleLongRead() {
		this.startPosition = super.position;
		this.expectedLength = 8;
		this.accumulatedLength = 0;
		this.payloadStartPosition = this.position;
		this.payloadPrefixLength = computeReadLengthUpTo(this.payloadStartPosition);
		this.actualLimit = this.limit > 0 ? this.limit : -1;
		this.limit = Math.min(this.buffer.length, constraintLimitWithRecordLength(0) + this.payloadPrefixLength);
		this.readingTail = true;
		try {
			return readLong();
		} finally {
			this.limit = this.actualLimit >= 0 ? this.actualLimit : this.limit;
			this.actualLimit = -1;
			this.startPosition = -1;
			this.expectedLength = -1;
			this.accumulatedLength = 0;
			this.payloadStartPosition = -1;
			this.payloadPrefixLength = 0;
			this.readingTail = false;
		}
	}

	/**
	 * Initializes start position of the record payload - i.e. since this moment CRC32C checksum starts to be computed
	 * for each byte read from now on.
	 */
	public void markPayloadStart(int expectedLength, byte controlByte) {
		this.expectedLength = expectedLength;
		this.payloadStartPosition = this.position;
		this.payloadPrefixLength = computeReadLengthUpTo(this.payloadStartPosition);
		this.expectedPayloadLength = this.expectedLength - (this.payloadPrefixLength + this.accumulatedLength + TAIL_MANDATORY_SPACE);
		this.payloadReadLength = 0;
		this.compressed = BitUtils.isBitSet(controlByte, StorageRecord.COMPRESSION_BIT);
		Assert.isPremiseValid(
			!this.compressed || this.inflater != null,
			() -> new CorruptedRecordException("Record is compressed and ObservableInput has compression support disabled!")
		);
		this.readingPayload = true;
		if (this.crc32C != null) {
			this.crc32C.reset();
		}
		if (this.inflater != null && this.compressed) {
			this.inflater.reset();
			// now we need to reset the limit in the buffer - since it may have been already filled with
			// compressed data and copy those data into decompression buffer, by this the pointer in underlying
			// stream will match the content in the decompression buffer
			this.payloadDecompressedLength = 0;
			// swap buffer and decompression buffer,
			// decompression buffer will contain raw data and buffer "unknown data" that would be rewritten by inflater
			// when the decompression finishes - buffers will be swapped back again
			final byte[] tmp = this.decompressionBuffer;
			this.decompressionBuffer = this.buffer;
			this.buffer = tmp;
			this.inflater.setInput(this.decompressionBuffer, this.position, this.limit - this.position);
			this.decompressionBufferStart = this.position;
			this.decompressionBufferPeek = this.actualLimit == -1 ? this.limit : this.actualLimit;
			this.inflaterReadBytesOnLastDecompressionBufferFill = 0;
			// this will enforce invoking `fill` method with first inflater call
			this.limit = this.position;
		} else {
			this.limit = Math.min(this.buffer.length, constraintLimitWithRecordLength() + this.payloadPrefixLength);
		}
	}

	/**
	 * Method finalizes the record reading.
	 * When CRC32C checksum should be verified, it's done at this moment.
	 * Expected and read record size is verified at all times.
	 * Method resets all record related flags and another record can be read afterwards.
	 */
	public void markEnd(byte controlByte) {
		try {
			this.readingPayload = false;
			this.readingTail = true;
			// enlarge limit to original value - we're already finishing record
			this.limit = this.actualLimit >= 0 ? this.actualLimit : this.limit;

			if (this.payloadStartPosition != -1) {
				// compute the final part of the payload that hasn't been added to accumulated length and CRC32 checksum
				final int payloadLength;
				final int totalLength;
				final int bytesReadSinceLastFill;
				final int bytesSavedByCompression;
				// was the record compressed?
				if (this.compressed) {
					// if the inflater is still not finished - try to exhaust it, to get all the data and update CRC32C accordingly
					if (!this.inflater.finished()) {
						Assert.isPremiseValid(
							fill(this.buffer, this.position, this.expectedPayloadLength - Math.toIntExact(this.inflater.getBytesRead())) == -1,
							() -> new CorruptedRecordException("Some meaningful data were extracted in the buffer, but they were not read!")
						);
					}
					// if payload was compressed, switch to standard - non-compressed mode,
					// we've read entire compressed payload
					this.compressed = false;
					this.expectedLength = this.accumulatedLength + this.payloadPrefixLength + this.payloadDecompressedLength + TAIL_MANDATORY_SPACE;
					bytesReadSinceLastFill = Math.toIntExact(this.inflater.getBytesRead()) - this.inflaterReadBytesOnLastDecompressionBufferFill;
					// swap buffers back
					final byte[] tmp = this.decompressionBuffer;
					this.decompressionBuffer = this.buffer;
					this.buffer = tmp;
					// update position in the buffer and limit
					this.position = this.decompressionBufferStart + bytesReadSinceLastFill;
					this.limit = this.decompressionBufferPeek;
					bytesSavedByCompression = this.payloadDecompressedLength - this.expectedPayloadLength;
					// calculate really read length
					payloadLength = this.payloadDecompressedLength;
					totalLength = this.payloadPrefixLength + this.payloadDecompressedLength;
				} else {
					payloadLength = this.position - this.payloadStartPosition;
					totalLength = this.position - this.startPosition;
					bytesReadSinceLastFill = 0;
					bytesSavedByCompression = 0;
				}
				// do we need to verify checksum?
				if (this.crc32C == null || !BitUtils.isBitSet(controlByte, StorageRecord.CRC32_BIT)) {
					// skip CRC32 checksum - it will not be verified
					super.skip(8);
				} else {
					if (bytesSavedByCompression > 0) {
						// update CRC32 checksum with the rest of the decompression buffer (it was swapped with the buffer)
						this.crc32C.update(this.buffer, this.decompressionBufferStart, bytesReadSinceLastFill);
					} else {
						// otherwise, update CRC32 checksum with the final payload part
						this.crc32C.update(this.buffer, this.payloadStartPosition, payloadLength);
					}
					// update CRC32C checksum with the control byte
					this.crc32C.update(controlByte);
					// verify checksum
					final long computedChecksum = this.crc32C.getValue();
					final long loadedChecksum = readLong();
					isPremiseValid(
						computedChecksum == loadedChecksum,
						() -> new CorruptedRecordException(
							"Invalid checksum - data probably corrupted " +
								"(record should have got CRC32C " + loadedChecksum + ", but was " + computedChecksum + ").",
							loadedChecksum, computedChecksum
						)
					);
				}
				// compute the length of the record, that has been read and compare with expectations
				this.accumulatedLength += totalLength + TAIL_MANDATORY_SPACE;
				isPremiseValid(
					this.expectedLength == this.accumulatedLength,
					() -> new CorruptedRecordException(
						"Invalid record size read - data probably corrupted " +
							"(record should have length " + this.expectedLength + ", but was " + this.accumulatedLength + ").",
						this.expectedLength, this.accumulatedLength
					)
				);
			}
		} finally {
			// reset counters
			this.startPosition = -1;
			this.actualLimit = -1;
			this.payloadStartPosition = -1;
			this.payloadPrefixLength = 0;
			this.compressed = false;
			this.decompressionBufferStart = 0;
			this.decompressionBufferPeek = 0;
			this.inflaterReadBytesOnLastDecompressionBufferFill = 0;
			this.expectedPayloadLength = 0;
			this.payloadReadLength = 0;
			this.payloadDecompressedLength = 0;
			this.expectedLength = -1;
			this.accumulatedLength = 0;
			this.readingTail = false;
		}
	}

	/**
	 * Enables CRC32C checksum verification after record has been read from the file.
	 */
	public ObservableInput<T> computeCRC32() {
		if (this.crc32C == null) {
			this.crc32C = new CRC32C();
		}
		return this;
	}

	/**
	 * Enables compress support for each record payload.
	 */
	@Nonnull
	public ObservableInput<T> compress() {
		if (this.inflater == null) {
			this.inflater = new Inflater(true);
			this.decompressionBuffer = new byte[this.buffer.length];
		}
		return this;
	}

	/**
	 * Returns true if the compression is enabled.
	 * @return true if the compression is enabled
	 */
	public boolean isCompressionEnabled() {
		return this.inflater != null;
	}

	/**
	 * Initializes start position of the records - i.e. since this moment record size starts to be observed.
	 */
	public long markStart() {
		this.startPosition = this.position;
		this.expectedLength = -1;
		this.accumulatedLength = 0;
		this.readingPayload = false;
		this.overflowing = false;
		this.compressed = false;
		return this.bytesReadFromInputStreamSinceReset - (this.limit - this.position);
	}

	/**
	 * This method opens reading from output in mode that handles buffer overflow situations using passed handler.
	 */
	public <S> S doWithOnBufferOverflowHandler(Consumer<ObservableInput<T>> onBufferOverflow, Supplier<S> lambda) {
		try {
			this.onBufferOverflow = onBufferOverflow;
			return lambda.get();
		} finally {
			this.onBufferOverflow = null;
		}
	}

	/**
	 * Method requires {@link AbstractRandomAccessInputStream} as an inner stream of this instance. If different stream
	 * is present ClassCastException is thrown.
	 *
	 * Method will position location in the file to the desired location, resets all internal flags and settings to
	 * the initial state and initializes capacity of the buffer to the record length. This speeds reading process
	 * because only necessary amount of data is read, even if ObservableInput is initialized with much bigger
	 * buffer.
	 *
	 * @throws ClassCastException when internal stream is not {@link RandomAccessFileInputStream}
	 */
	public void seekWithUnknownLength(long startingPosition) throws ClassCastException {
		seek(new FileLocation(startingPosition, this.buffer.length));
	}

	/**
	 * Method requires {@link AbstractRandomAccessInputStream} as an inner stream of this instance. If different stream
	 * is present ClassCastException is thrown.
	 *
	 * Method will position location in the file to the desired location, resets all internal flags and settings to
	 * the initial state and initializes capacity of the buffer to the record length. This speeds reading process
	 * because only necessary amount of data is read, even if ObservableInput is initialized with much bigger
	 * buffer.
	 *
	 * @throws ClassCastException when internal stream is not {@link RandomAccessFileInputStream}
	 */
	public void seek(@Nonnull FileLocation location) throws ClassCastException {
		((AbstractRandomAccessInputStream) this.inputStream).seek(location.startingPosition());
		this.limit = 0;
		this.actualLimit = -1;
		this.position = 0;
		this.total = 0;
		this.accumulatedLength = 0;
		this.lastOffset = -1;
		this.lastCount = -1;
		this.startPosition = -1;
		this.payloadStartPosition = -1;
		this.payloadPrefixLength = 0;
		this.readingPayload = false;
		this.overflowing = false;
		this.bytesReadFromInputStreamSinceReset = 0;
		this.capacity = Math.min(this.buffer.length, location.recordLength());
	}

	/**
	 * Retrieves the length of the input stream.
	 *
	 * @return the length of the input stream as a long value
	 */
	public long getLength() {
		return ((AbstractRandomAccessInputStream) this.inputStream).getLength();
	}

	/**
	 * Method requires {@link AbstractRandomAccessInputStream} as an inner stream of this instance. If different stream
	 * is present ClassCastException is thrown.
	 *
	 * Method will position location in the file to the desired location, resets all internal flags and settings to
	 * the initial state.
	 */
	public void resetToPosition(long location) {
		((AbstractRandomAccessInputStream) this.inputStream).seek(location);
		this.limit = 0;
		this.reset();
	}

	/**
	 * Handles the scenario where a buffer overflow occurs during record processing. In this case we try to fall back
	 * on overflow handler that can decide whether to continue reading or throw an exception.
	 *
	 * @param totalReadLengthWithReserve The total length of data already read, including reserved bytes.
	 * @param required                   The number of additional bytes required to continue processing the record.
	 * @throws CorruptedRecordException If the buffer is overflowing and no overflow handler is provided,
	 *                                  indicating that the record length is unexpected and data might be corrupted.
	 */
	@SuppressWarnings("StringConcatenationMissingWhitespace")
	private void handleOverflow(int totalReadLengthWithReserve, int required) {
		if (this.overflowing || this.onBufferOverflow == null) {
			throw new CorruptedRecordException(
				"Unexpected record length - data probably corrupted " +
					"(record should be long " + this.expectedLength + "B, but was read " + totalReadLengthWithReserve +
					"B and another " + required + "B was requested for reading).",
				this.expectedLength, (long) totalReadLengthWithReserve + required
			);
		} else {
			try {
				this.overflowing = true;
				this.onBufferOverflow.accept(this);
				require(required);
			} finally {
				this.overflowing = false;
			}
		}
	}

	/**
	 * Method computes total record length read from {@link #markStart()} up to current {@link #position}.
	 */
	private int computeTotalReadLength() {
		final int readLength = computeReadLengthUpTo(this.position);
		return readLength + this.accumulatedLength;
	}

	/**
	 * Variant of the method that counts with the CRC32C checksum at the end of the record.
	 *
	 * @see #constraintLimitWithRecordLength(int)
	 */
	private int constraintLimitWithRecordLength() {
		return constraintLimitWithRecordLength(TAIL_MANDATORY_SPACE);
	}

	/**
	 * Method will check whether the limit exceeds the {@link #expectedLength} of current record and if so, it caps
	 * the limit to the size, that comply with the expected length. The problem is that `limit` reflect the number of
	 * Bytes that was successfully read from the stream/file and there may be also Bytes that belong to different
	 * record.
	 *
	 * We need to query the `limit` to copy the end of the record because the limit is used in many Kryo methods
	 * to optimize calling {@link #require(int)} method to necessary minimum - i.e. only the situation when they reach
	 * the limit. But we need {@link #require(int)} method to be called also at the moment when we reach the end
	 * of the current record so that we can apply {@link #overflowing} logic and check checksum and read header
	 * of the next record.
	 */
	private int constraintLimitWithRecordLength(int mandatorySpaceLength) {
		if (!this.readingTail && this.expectedLength > 0 && this.expectedLength < this.accumulatedLength + this.limit - this.lastOffset) {
			// remember but cap the limit
			this.actualLimit = this.limit;
			return this.startPosition + this.expectedLength - mandatorySpaceLength - this.accumulatedLength;
		} else {
			// leave limit unchanged
			return this.limit;
		}
	}

	/**
	 * Compute the length of the already read content up to the passed position.
	 * Logic handles overflow over buffer boundary - the startPosition may be located at the end of the buffer and when
	 * content before payload start is read it triggers buffer refilling and thus making current `position` lesser than
	 * `startPosition`.
	 */
	private int computeReadLengthUpTo(int thePosition) {
		if (thePosition >= this.startPosition) {
			// easiest scenario - we've reading after the start position
			return thePosition - this.startPosition;
		} else {
			// we're already reading part of the record part and the buffer has rotated boundaries
			return thePosition - this.lastOffset;
		}
	}

	/**
	 * This method handles flag manipulation and accumulator updates. It also tracks of the data movements in the buffer
	 * and updates moving CRC32 hash computation when data in the buffer is about to be rewritten with the new content.
	 */
	private void updateLostBuffer(int offset, int count) {
		if (!this.compressed && count > 0) {
			// recompute accumulated length since the start of the record
			if (this.lastCount != -1) {
				if (!this.readingTail) {
					this.accumulatedLength += this.position - (this.startPosition - offset);
					// update payload start positions - we're going to rewrite the buffer so the sensible payload start changes
					this.startPosition = offset;
				}
				// we're reading payload - we have to recompute accumulated length
				if (this.readingPayload) {
					// and if crc32 checksum is enabled update id by the read contents
					// when the content is compressed the crc32 checksum is computed in `fill` method
					if (this.crc32C != null) {
						final int consumedLength = this.lastCount - (this.payloadStartPosition - this.lastOffset);
						this.crc32C.update(buffer, this.payloadStartPosition, consumedLength);
					}
					// update payload start positions - we're going to rewrite the buffer so the sensible payload start changes
					this.payloadStartPosition = offset;
				}
			}
			// always update last meaningful offset and count to track the part of the buffer that hasn't yet been
			// accounted to accumulated length and recorded to CRC32
			this.lastOffset = offset;
			this.lastCount = count;
		}
	}

}
