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

package io.evitadb.store.kryo;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.exception.CorruptedRecordException;
import io.evitadb.store.offsetIndex.exception.KryoSerializationException;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.stream.AbstractRandomAccessInputStream;
import io.evitadb.store.offsetIndex.stream.RandomAccessFileInputStream;
import io.evitadb.utils.BitUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.InputStream;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.CRC32C;

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
	 * Record start position. Use {@link #markStart()} to init.
	 */
	@Getter private int startPosition = -1;
	/**
	 * Record payload (i.e. real record contents). Use {@link #markPayloadStart(int)} to init.
	 */
	@Getter private int payloadStartPosition = -1;
	/**
	 * Contains number of bytes that were read since {@link #markStart()} at the time {@link #markPayloadStart(int)} was
	 * executed.
	 */
	private int payloadPrefixLength = 0;
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
	private Consumer<ObservableInput<T>> onBufferOverflow;
	/**
	 * Internal flag that is set to true only when {@link #onBufferOverflow} handler is being executed.
	 */
	private boolean overflowing;
	/**
	 * Internal flag that is set to true only when {@link #markPayloadStart(int)} method was called and method
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
	 *
	 * @implNote <a href="https://codecapsule.com/2014/02/12/coding-for-ssds-part-6-a-summary-what-every-programmer-should-know-about-solid-state-drives/">Source</a>
	 */
	public ObservableInput(T inputStream) {
		this(inputStream, 16_384);
	}

	public ObservableInput(T inputStream, int bufferSize) {
		super(inputStream, bufferSize);
	}

	@Override
	public T getInputStream() {
		//noinspection unchecked
		return (T) super.getInputStream();
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
		int skipCount = Math.min(limit - position, count);
		while (true) {
			require(skipCount);
			position += skipCount;
			count -= skipCount;
			if (count == 0) break;
			skipCount = Math.min(count, capacity);
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
		final int limit = this.actualLimit == -1 ? this.limit : this.actualLimit;
		final int reserve = readingTail ? 0 : TAIL_MANDATORY_SPACE;
		final int totalReadLength = computeTotalReadLength();
		if (expectedLength != -1 && totalReadLength + reserve + required > expectedLength) {
			if (overflowing || onBufferOverflow == null) {
				throw new CorruptedRecordException(
					"Unexpected record length - data probably corrupted " +
						"(record should be long " + expectedLength + "B, but was read " + (totalReadLength + reserve) + "B and another " + required + "B was requested for reading).",
					expectedLength, (long)totalReadLength + (long)reserve + required
				);
			} else {
				try {
					overflowing = true;
					onBufferOverflow.accept(this);
					require(required);
				} finally {
					overflowing = false;
				}
			}
		}
		/* END OF EXTENSION */

		int remaining = limit - position;
		if (remaining >= required) return remaining;
		if (required > capacity)
			throw new KryoException("Buffer too small: capacity: " + capacity + ", required: " + required);

		int count;
		// Try to fill the buffer.
		if (remaining > 0) {
			count = fill(buffer, limit, capacity - limit);
			if (count == -1) throw new KryoException("Buffer underflow.");
			remaining += count;
			if (remaining >= required) {
				/* EXTENSION */
				this.limit += count;
				this.limit = constraintLimitWithRecordLength();
				/* END OF EXTENSION */
				return remaining;
			}
		}

		/* EXTENSION */
		final int attemptedToRead = capacity - remaining;
		updateLostBuffer(remaining, attemptedToRead);
		/* END OF EXTENSION */

		// Was not enough, compact and try again.
		System.arraycopy(buffer, position, buffer, 0, remaining);
		total += position;
		position = 0;

		while (true) {
			count = fill(buffer, remaining, attemptedToRead);
			if (count == -1) {
				if (remaining >= required) break;
				throw new KryoException("Buffer underflow.");
			}
			remaining += count;
			if (remaining >= required) break; // Enough has been read.
		}

		/* EXTENSION */
		this.limit = remaining;
		this.limit = constraintLimitWithRecordLength();
		/* END OF EXTENSION */
		return limit;
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
		if (position + optional > Math.min(limit, capacity)) {
			final int reserve = readingTail ? 0 : TAIL_MANDATORY_SPACE;
			final int totalReadLength = computeTotalReadLength();
			if (expectedLength != -1 && totalReadLength + reserve + optional > expectedLength) {
				return limit - position;
			}
		}
		/* END OF EXTENSION */
		int remaining = limit - position;
		if (remaining >= optional) return optional;
		optional = Math.min(optional, capacity);

		int count;

		// Try to fill the buffer.
		count = fill(buffer, limit, capacity - limit);
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
		final int attemptedToRead = capacity - remaining;
		updateLostBuffer(remaining, attemptedToRead);
		/* END OF EXTENSION */

		// Was not enough, compact and try again.
		System.arraycopy(buffer, position, buffer, 0, remaining);
		total += position;
		position = 0;

		while (true) {
			count = fill(buffer, remaining, attemptedToRead);
			if (count == -1) break;
			remaining += count;
			if (remaining >= optional) break; // Enough has been read.
		}

		/* EXTENSION */
		this.limit = remaining;
		this.limit = constraintLimitWithRecordLength();
		return limit == 0 ? -1 : Math.min(limit, optional);
		/* END OF EXTENSION */
	}

	/**
	 * Method computes total record length read from {@link #markStart()} up to current {@link #position}.
	 */
	private int computeTotalReadLength() {
		final int readLength = computeReadLengthUpTo(this.position);
		return readLength + this.accumulatedLength;
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
		int copyCount = Math.min(limit - position, count);
		while (true) {
			require(copyCount);
			System.arraycopy(buffer, position, bytes, offset, copyCount);
			position += copyCount;
			count -= copyCount;
			if (count == 0) break;
			offset += copyCount;
			copyCount = optional(count);
			if (copyCount == -1) {
				// End of data.
				if (startingCount == count) return -1;
				break;
			}
			if (position == limit) break;
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
		int copyCount = Math.min(limit - position, count);
		while (true) {
			require(copyCount);
			System.arraycopy(buffer, position, bytes, offset, copyCount);
			position += copyCount;
			count -= copyCount;
			if (count == 0) break;
			offset += copyCount;
			copyCount = Math.min(count, capacity);
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
	 * Initializes start position of the record payload - i.e. since this moment CRC32C checksum starts to be computed
	 * for each byte read from now on.
	 */
	public void markPayloadStart(int expectedLength) {
		this.expectedLength = expectedLength;
		this.payloadStartPosition = this.position;
		this.payloadPrefixLength = computeReadLengthUpTo(this.payloadStartPosition);
		this.readingPayload = true;
		this.limit = Math.min(this.buffer.length, constraintLimitWithRecordLength() + this.payloadPrefixLength);
		if (crc32C != null) {
			crc32C.reset();
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

			if (payloadStartPosition != -1) {
				// compute the final part of the payload that hasn't been added to accumulated length and CRC32 checksum
				final int payloadLength = super.position - this.payloadStartPosition;
				final int totalLength = super.position - this.startPosition;
				if (crc32C == null || !BitUtils.isBitSet(controlByte, StorageRecord.CRC32_BIT)) {
					// skip CRC32 checksum - it will not be verified
					super.skip(8);
				} else {
					// update CRC32 checksum with the final payload part
					crc32C.update(super.buffer, this.payloadStartPosition, payloadLength);
					crc32C.update(BitUtils.setBit(controlByte, StorageRecord.CRC32_BIT, true));
					// verify checksum
					final long computedChecksum = crc32C.getValue();
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
	 * Initializes start position of the records - i.e. since this moment record size starts to be observed.
	 */
	public long markStart() {
		this.startPosition = super.position;
		this.expectedLength = -1;
		this.accumulatedLength = 0;
		return total();
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
		this.capacity = Math.min(this.buffer.length, location.recordLength());
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
		if (!readingTail && this.expectedLength > 0 && this.expectedLength < this.accumulatedLength + this.limit - this.lastOffset) {
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
		if (count > 0) {
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
					if (crc32C != null) {
						final int consumedLength = this.lastCount - (this.payloadStartPosition - this.lastOffset);
						crc32C.update(buffer, this.payloadStartPosition, consumedLength);
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
