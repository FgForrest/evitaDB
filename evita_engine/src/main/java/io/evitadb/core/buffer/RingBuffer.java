/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.buffer;


import io.evitadb.core.metric.event.system.RingBufferStatisticsEvent;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * A generic ring buffer implementation for storing and retrieving ordered data elements.
 * This buffer maintains a fixed-size circular array, automatically discarding the oldest
 * entries when the buffer becomes full.
 *
 * The buffer tracks the effective start and end boundaries of the data it contains,
 * allowing clients to query for data starting from a specific watermark position.
 * When the buffer is full and new items are added, the oldest items are removed, and the
 * effective start boundary is updated accordingly.
 *
 * This implementation uses a {@link ReentrantLock} to ensure thread safety for reading operations.
 * However, writing operations must always be performed from the same thread to maintain consistency.
 *
 * Thread safety: Safe for concurrent reads, but writes must be serialized (single writer thread).
 *
 * @param <DATA> the type of data elements stored in the buffer
 * @param <BOUNDARY> the comparable type used to define the range boundaries of stored data
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
public abstract class RingBuffer<DATA, BOUNDARY extends Comparable<BOUNDARY> & Serializable> {
	/**
	 * Lock used to ensure thread safety for reading operations.
	 */
	private final ReentrantLock lock = new ReentrantLock();

	/**
	 * Name of the catalog this buffer is associated with (for metrics purposes).
	 */
	private final String catalogName;

	/**
	 * The circular array that stores the data elements.
	 */
	private final DATA[] workspace;

	/**
	 * The index of the oldest element in the buffer.
	 * When the buffer wraps around, this index may be greater than or equal to endIndex.
	 */
	private int startIndex = 0;

	/**
	 * The index where the next element will be inserted.
	 * This is always one position ahead of the last inserted element.
	 */
	private int endIndex = 0;

	/**
	 * Flag indicating whether the buffer has wrapped around.
	 */
	private boolean wrappedAround;

	/**
	 * The boundary value of the oldest data element in the buffer.
	 * This is used to determine if a requested position is within the buffer's scope.
	 */
	@Getter private BOUNDARY effectiveStart;

	/**
	 * The latest boundary value that defines the upper limit of data visibility.
	 * Data elements with boundaries greater than this are not returned when copying to a target queue.
	 */
	@Getter private BOUNDARY effectiveEnd;

	/**
	 * Function to extract the boundary value from a data element.
	 */
	@Nonnull private final Function<DATA, BOUNDARY> boundaryExtractor;

	/**
	 * Comparator to compare a boundary value with a watermark.
	 * Returns negative if boundary < watermark, 0 if equal, positive if boundary > watermark.
	 */
	@Nonnull private final ToIntBiFunction<BOUNDARY, BOUNDARY> boundaryWatermarkComparator;

	/**
	 * Comparator to compare a data element with a watermark.
	 * Returns negative if data < watermark, 0 if equal, positive if data > watermark.
	 */
	@Nonnull private final ToIntBiFunction<DATA, BOUNDARY> dataWatermarkComparator;

	/**
	 * Counter of items accepted into the buffer since creation.
	 */
	private long itemsAccepted;

	/**
	 * Counter of items copied via {@link #copyTo(Comparable, Queue)} since creation.
	 */
	private long itemsCopied;

	/**
	 * Counter of items scanned via {@link #forEachSince(Comparable, Consumer)} since creation.
	 */
	private long itemsScanned;

	/**
	 * Cached gauge: number of items currently present in the buffer (occupied slots).
	 */
	private int itemsPresentTotal;

	/**
	 * Cached gauge: number of items currently available to scan/copy with respect to {@link #effectiveEnd}.
	 */
	private int itemsAvailableTotal;

	/**
	 * Constructs a new ring buffer with the specified initial state and size.
	 *
	 * @param effectiveStart the boundary value of the oldest data element that will be stored in the buffer
	 * @param effectiveEnd the latest boundary value defining the upper limit of data visibility
	 * @param bufferSize the size of the ring buffer (maximum number of data elements that can be stored)
	 * @param type the class of the data elements to create a properly typed array
	 * @param boundaryExtractor function to extract boundary values from data elements
	 * @param boundaryWatermarkComparator comparator to compare boundary values with watermarks
	 * @param dataWatermarkComparator comparator to compare data elements with watermarks
	 */
	protected RingBuffer(
		@Nullable final String catalogName,
		final BOUNDARY effectiveStart,
		final BOUNDARY effectiveEnd,
		final int bufferSize,
		@Nonnull Class<DATA> type,
		@Nonnull Function<DATA, BOUNDARY> boundaryExtractor,
		@Nonnull ToIntBiFunction<BOUNDARY, BOUNDARY> boundaryWatermarkComparator,
		@Nonnull ToIntBiFunction<DATA, BOUNDARY> dataWatermarkComparator
	) {
		this.catalogName = catalogName;
		this.effectiveStart = effectiveStart;
		this.effectiveEnd = effectiveEnd;
		//noinspection unchecked
		this.workspace = (DATA[]) Array.newInstance(type, bufferSize);
		this.boundaryExtractor = boundaryExtractor;
		this.boundaryWatermarkComparator = boundaryWatermarkComparator;
		this.dataWatermarkComparator = dataWatermarkComparator;
		// initialize cached gauges
		recomputeGaugesLocked();
	}

	/**
	 * Sets the effective end boundary.
	 *
	 * @param effectiveEnd the boundary to set as the effective end; must not be null
	 */
	public void setEffectiveEnd(@Nonnull BOUNDARY effectiveEnd) {
		this.lock.lock();
		try {
			this.effectiveEnd = effectiveEnd;
			// changing watermark affects only availability gauge - present stays the same
			this.itemsAvailableTotal = computeItemsAvailableLocked();
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Adds a new data element to the ring buffer. If the buffer is full, the oldest element is removed
	 * to make space for the new one, and the effective start boundary is updated accordingly.
	 *
	 * This method is thread-safe as it acquires a lock before modifying the buffer.
	 *
	 * @param data the data element to add to the buffer, must not be null
	 */
	public void offer(@Nonnull DATA data) {
		this.lock.lock();
		try {
			// increment accepted items counter
			this.itemsAccepted++;
			this.endIndex++;
			// wrap around the ring buffer
			if (this.endIndex == this.workspace.length + 1) {
				this.endIndex = 1;
				this.wrappedAround = true;
			}
			// if the ring buffer is full, remove the oldest element
			if (this.endIndex - 1 == this.startIndex && this.wrappedAround) {
				// remove the oldest element
				final int newStartIndex = (this.startIndex + 1) % this.workspace.length;
				final DATA startData = Objects.requireNonNull(this.workspace[newStartIndex]);
				this.effectiveStart = this.boundaryExtractor.apply(startData);
				this.startIndex = newStartIndex;
				// update cached gauges
				recomputeGaugesLocked();
			} else {
				// update just items present (effective boundary hasn't changed)
				this.itemsPresentTotal = computeItemsPresentLocked();
			}
			// add the new element
			this.workspace[this.endIndex - 1] = data;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Copies data elements from the ring buffer to the target queue, starting from the specified watermark position.
	 * Only data elements with boundaries less than or equal to the effective end boundary are copied.
	 *
	 * This method is thread-safe as it acquires a lock before reading from the buffer.
	 *
	 * @param watermark the watermark indicating where to start copying from, must not be null
	 * @param data the target queue to copy data elements to, must not be null
	 * @return last data element that was copied to the target queue, or empty if nothing was copied
	 * @throws OutsideScopeException if the watermark is outside the scope of data currently in the buffer
	 */
	@Nonnull
	public Optional<DATA> copyTo(
		@Nonnull BOUNDARY watermark,
		@Nonnull Queue<DATA> data
	) throws OutsideScopeException {
		this.lock.lock();
		try {
			// throw exception if the watermark is outside the covered scope
			if (this.boundaryWatermarkComparator.applyAsInt(this.effectiveStart, watermark) > 0) {
				throw new OutsideScopeException(this.effectiveStart);
			}
			if (this.startIndex >= this.endIndex && this.wrappedAround) {
				// The buffer has wrapped around, so we need to search in two segments
				final InsertionPosition headIndex = ArrayUtils.computeInsertPositionOfObjInOrderedArray(watermark, this.workspace, this.startIndex, this.workspace.length, this.dataWatermarkComparator);
				if (headIndex.alreadyPresent() || headIndex.position() < this.workspace.length) {
					// Found in the first segment, copy from there to the end, then from start to endIndex
					final Optional<DATA> tailResult = copyTo(headIndex.position(), this.workspace.length, data);
					return copyTo(0, this.endIndex, data).or(() -> tailResult);
				} else  {
					// Not found in the first segment, try the second segment
					final InsertionPosition tailIndex = ArrayUtils.computeInsertPositionOfObjInOrderedArray(watermark, this.workspace, 0, this.endIndex, this.dataWatermarkComparator);
					if (tailIndex.alreadyPresent() || tailIndex.position() < this.endIndex) {
						return copyTo(tailIndex.position(), this.endIndex, data);
					} else {
						return empty();
					}
				}
			} else if (this.startIndex < this.endIndex) {
				// The buffer hasn't wrapped around, so we can search in a single segment
				final InsertionPosition index = ArrayUtils.computeInsertPositionOfObjInOrderedArray(watermark, this.workspace, this.startIndex, this.endIndex, this.dataWatermarkComparator);
				if (index.alreadyPresent() || index.position() < this.endIndex) {
					return copyTo(index.position(), this.endIndex, data);
				} else {
					return empty();
				}
			} else {
				return empty();
			}
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Iterates over all data elements in the buffer that were added since the specified watermark (inclusive)
	 * and applies the provided consumer to each of them.
	 *
	 * @param watermark the watermark since which data elements should be processed
	 * @param dataConsumer the consumer that will process each data element
	 * @throws OutsideScopeException if the watermark is outside the scope of data currently in the buffer
	 */
	protected void forEachSince(
		@Nonnull BOUNDARY watermark,
		@Nonnull Consumer<DATA> dataConsumer
	) throws OutsideScopeException {
		this.lock.lock();
		try {
			// throw exception if the watermark is outside the covered scope
			final OutsideScopeException exceptionToThrow;
			if (this.boundaryWatermarkComparator.applyAsInt(this.effectiveStart, watermark) > 0) {
				exceptionToThrow = new OutsideScopeException(this.effectiveStart);
			} else {
				exceptionToThrow = null;
			}
			// now apply the data consumer
			if (this.startIndex >= this.endIndex && this.wrappedAround) {
				// The buffer has wrapped around, so we need to search in two segments
				final InsertionPosition headIndex = ArrayUtils.computeInsertPositionOfObjInOrderedArray(watermark, this.workspace, this.startIndex, this.workspace.length, this.dataWatermarkComparator);
				if (headIndex.alreadyPresent() || headIndex.position() < this.workspace.length) {
					// Found in the first segment, iterate from there to the end, then from start to endIndex
					forEach(headIndex.position(), this.workspace.length, dataConsumer);
					forEach(0, this.endIndex, dataConsumer);
				} else  {
					// Not found in the first segment, try the second segment
					final InsertionPosition tailIndex = ArrayUtils.computeInsertPositionOfObjInOrderedArray(watermark, this.workspace, 0, this.endIndex, this.dataWatermarkComparator);
					if (tailIndex.alreadyPresent() || tailIndex.position() < this.endIndex) {
						forEach(tailIndex.position(), this.endIndex, dataConsumer);
					}
				}
			} else if (this.startIndex < this.endIndex) {
				// The buffer hasn't wrapped around, so we can search in a single segment
				final InsertionPosition index = ArrayUtils.computeInsertPositionOfObjInOrderedArray(watermark, this.workspace, this.startIndex, this.endIndex, this.dataWatermarkComparator);
				if (index.alreadyPresent() || index.position() < this.endIndex) {
					forEach(index.position(), this.endIndex, dataConsumer);
				}
			}
			// throw exception if needed
			if (exceptionToThrow != null) {
				throw exceptionToThrow;
			}
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Clears all entries in the ring buffer up to (but not including) the specified boundary value.
	 * Entries with boundary values less than the specified boundary are set to null, and the
	 * starting index of the buffer is updated accordingly. If the buffer is wrapped around, it clears
	 * entries in two segments: first clearing from the start index to the end of the array, then
	 * clearing from the beginning of the array to the end index.
	 *
	 * This method acquires a lock to ensure thread safety during the operation.
	 *
	 * @param boundary the boundary value up to which entries in the buffer should be cleared
	 */
	public void clearAllUntil(@Nonnull BOUNDARY boundary) {
		this.lock.lock();
		try {
			boolean finished = false;
			if (this.startIndex >= this.endIndex && this.wrappedAround) {
				finished = clearSegmentUntil(this.startIndex, this.workspace.length, boundary);
				if (!finished) {
					// we've cleared the entire tail, now we need to clear the head
					this.startIndex = 0;
					finished = clearSegmentUntil(0, this.endIndex, boundary);
					this.wrappedAround = false;
				}
			} else if (this.startIndex < this.endIndex) {
				finished = clearSegmentUntil(this.startIndex, this.endIndex, boundary);
				this.wrappedAround = false;
			}
			if (finished) {
				this.effectiveStart = this.boundaryExtractor.apply(Objects.requireNonNull(this.workspace[this.startIndex]));
			} else {
				this.effectiveStart = boundary;
			}
			// indices and boundaries changed -> recompute gauges
			recomputeGaugesLocked();
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Clears all entries in the ring buffer with boundary values greater than the specified boundary.
	 * Entries with boundary values greater than the specified boundary are logically removed by
	 * adjusting the end index of the buffer. If the buffer is wrapped around, it processes
	 * entries in two segments: first from the start index to the end of the array, then
	 * from the beginning of the array to the end index.
	 *
	 * Note: This method doesn't physically clear the buffer, it only adjusts the start and end indexes.
	 *
	 * This method acquires a lock to ensure thread safety during the operation.
	 *
	 * @param boundary the boundary value after which entries in the buffer should be cleared
	 */
	public void clearAllAfter(@Nonnull BOUNDARY boundary) {
		this.lock.lock();
		try {
			if (this.startIndex >= this.endIndex && this.wrappedAround) {
				// Buffer has wrapped around, clear in two segments
				int lastValidEntry = findLastValidEntry(this.startIndex, this.workspace.length, boundary);
				if (lastValidEntry < 0) {
					lastValidEntry = findLastValidEntry(0, this.endIndex, boundary);
					if (lastValidEntry < 0) {
						this.endIndex = this.startIndex;
						this.wrappedAround = false;
					} else {
						this.endIndex = lastValidEntry;
					}
				} else {
					this.endIndex = lastValidEntry;
					this.wrappedAround = false;
				}
			} else if (this.startIndex < this.endIndex) {
				// Buffer hasn't wrapped around, clear in a single segment
				final int lastValidEntry = findLastValidEntry(this.startIndex, this.endIndex, boundary);
				this.endIndex = lastValidEntry < 0 ? this.startIndex : lastValidEntry;
				this.wrappedAround = false;
			}
			// update cached gauges (even if nothing was cleared, indices may have been adjusted)
			recomputeGaugesLocked();
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Finds the last valid entry in the specified segment of the ring buffer and returns its index.
	 * An entry is considered valid if its boundary value is less than or equal to the specified boundary.
	 *
	 * @param fromIndex the starting index of the segment to search (inclusive)
	 * @param toIndex the ending index of the segment to search (exclusive)
	 * @param boundary the boundary value threshold; entries with greater boundaries are invalid
	 * @return the index of the last valid entry, or -1 if no valid entry exists
	 */
	private int findLastValidEntry(
		int fromIndex,
		int toIndex,
		@Nonnull BOUNDARY boundary
	) {
		return ArrayUtils.binarySearch(
			this.workspace, boundary,
			fromIndex, toIndex,
			(data, b) -> this.boundaryExtractor.apply(data).compareTo(b)
		);
	}

	/**
	 * Clears a segment of the ring buffer up to (but not including) the specified boundary value.
	 * This method iterates through the specified range of the workspace array and clears entries
	 * with boundary values less than the specified boundary.
	 *
	 * @param fromIndex the starting index of the segment to clear (inclusive)
	 * @param toIndex the ending index of the segment to clear (exclusive)
	 * @param boundary the boundary value up to which entries should be cleared
	 * @return true if the clearing operation finished (reached an entry at or beyond the boundary),
	 *         false if the entire segment was cleared
	 */
	private boolean clearSegmentUntil(
		int fromIndex,
		int toIndex,
		@Nonnull BOUNDARY boundary
	) {
		for (int i = fromIndex; i < toIndex; i++) {
			if (this.workspace[i] != null && this.boundaryExtractor.apply(this.workspace[i]).compareTo(boundary) < 0) {
				this.workspace[i] = null;
			} else if (this.workspace[i] != null) {
				// we reached an entry at or beyond the specified boundary, finish
				this.startIndex = i;
				return true;
			}
		}
		this.startIndex = toIndex % this.workspace.length;
		if (toIndex >= this.endIndex) {
			this.endIndex = this.startIndex;
		}
		return false;
	}

	/**
	 * Resets the ring buffer by clearing all elements. This method
	 * sets all positions in the workspace array to null, and
	 * resets the startIndex and endIndex to zero. The wrappedAround
	 * flag is also set to false, indicating the buffer is now empty
	 * and has not wrapped around since being cleared.
	 */
	public void clearAll() {
		this.lock.lock();
		try {
			// clear the workspace
			Arrays.fill(this.workspace, null);
			this.startIndex = 0;
			this.endIndex = 0;
			this.wrappedAround = false;
			// reset gauges without scanning
			this.itemsPresentTotal = 0;
			this.itemsAvailableTotal = 0;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Copies data elements from a specific range in the buffer to the target queue.
	 * Only data elements with boundaries less than or equal to the effective end boundary are copied.
	 * The copying stops if any of the following conditions are met:
	 * <ul>
	 *   <li>A data element with a boundary greater than the effective end boundary is encountered</li>
	 *   <li>The target queue refuses to accept a data element (returns false from offer)</li>
	 *   <li>The end of the specified range is reached</li>
	 *   <li>The end of the workspace array is reached</li>
	 * </ul>
	 *
	 * @param from the starting index in the buffer (inclusive)
	 * @param to the ending index in the buffer (exclusive)
	 * @param target the target queue to copy data elements to, must not be null
	 * @return last data element that was copied to the target queue, or empty if nothing was copied
	 */
	@Nonnull
	private Optional<DATA> copyTo(int from, int to, @Nonnull Queue<DATA> target) {
		boolean continueCopy = true;
		int index = from;
		DATA currentData = null;
		DATA lastData = null;
		int copiedNow = 0;
		while (continueCopy) {
			lastData = currentData;
			currentData = this.workspace[index];
			// stop if we reach `to` index or if the data element boundary exceeds the effective end boundary
			final boolean shouldCopyData = index++ < to &&
				currentData != null &&
				this.boundaryExtractor.apply(currentData).compareTo(this.effectiveEnd) <= 0;
			// offer data to the target queue
			if (shouldCopyData && target.offer(currentData)) {
				copiedNow++;
				continueCopy = index < this.workspace.length;
			} else {
				continueCopy = false;
			}
		}
		// update copied counter
		this.itemsCopied += copiedNow;
		return ofNullable(lastData);
	}

	/**
	 * Iterates over data elements from a specific range in the buffer and applies the consumer to each of them.
	 * Only data elements with boundaries less than or equal to the effective end boundary are processed.
	 * The iteration stops if any of the following conditions are met:
	 * <ul>
	 *   <li>A data element with a boundary greater than the effective end boundary is encountered</li>
	 *   <li>The end of the specified range is reached</li>
	 *   <li>The end of the workspace array is reached</li>
	 * </ul>
	 *
	 * @param from the starting index in the buffer (inclusive)
	 * @param to the ending index in the buffer (exclusive)
	 * @param dataConsumer the consumer that will process each data element, must not be null
	 */
	private void forEach(int from, int to, @Nonnull Consumer<DATA> dataConsumer) {
		boolean continueIteration = true;
		int index = from;
		DATA currentData;
		int scannedNow = 0;
		while (continueIteration) {
			currentData = this.workspace[index++];
			final boolean withinEffectiveEnd = this.boundaryExtractor.apply(currentData).compareTo(this.effectiveEnd) <= 0;
			if (currentData != null && withinEffectiveEnd) {
				dataConsumer.accept(currentData);
				scannedNow++;
			}
			// stop if we reach `to` index or if the data element boundary exceeds the effective end boundary
			continueIteration = index < to &&
				currentData != null &&
				withinEffectiveEnd &&
				index < this.workspace.length;
		}
		// update scanned counter
		this.itemsScanned += scannedNow;
	}


	/**
	 * Creates a statistics JFR event snapshot reflecting current metrics of this ring buffer and emits it.
	 */
	public void emitObservabilityEvents() {
		final int present = this.itemsPresentTotal;
		final int available = this.itemsAvailableTotal;
		new RingBufferStatisticsEvent(
			this.catalogName,
			this.getRingBufferType(),
			this.itemsAccepted,
			this.itemsCopied,
			this.itemsScanned,
			present,
			available
		).commit();
	}

	/**
	 * Returns the type of the ring buffer, typically represented as a string.
	 * This is an abstract method that must be implemented by subclasses to
	 * specify the specific type of the ring buffer being used.
	 *
	 * @return the type of the ring buffer as a non-null string
	 */
	@Nonnull
	protected abstract String getRingBufferType();

	/**
	 * Counts items within [from,to) that are not null and have boundary <= effectiveEnd.
	 */
	private int countAvailableInRange(int from, int to) {
		int count = 0;
		for (int i = from; i < to; i++) {
			final DATA data = this.workspace[i];
			if (data == null) {
				break;
			}
			if (this.boundaryExtractor.apply(data).compareTo(this.effectiveEnd) <= 0) {
				count++;
			} else {
				break;
			}
		}
		return count;
	}

	/**
	 * Recomputes cached gauges under the assumption the lock is already held.
	 */
	private void recomputeGaugesLocked() {
		this.itemsPresentTotal = computeItemsPresentLocked();
		this.itemsAvailableTotal = computeItemsAvailableLocked();
	}

	/**
	 * Computes the number of items present in a locked state, considering
	 * the indices and whether the buffer has wrapped around.
	 *
	 * @return the total number of items present. Returns 0 if none are present,
	 *         the difference between endIndex and startIndex if not wrapped around,
	 *         the length of the workspace if fully wrapped, or the sum of indices
	 *         considering the wrap-around logic.
	 */
	private int computeItemsPresentLocked() {
		if (!this.wrappedAround) {
			return Math.max(0, this.endIndex - this.startIndex);
		} else if (this.endIndex == this.startIndex) {
			return this.workspace.length;
		} else {
			return (this.workspace.length - this.startIndex) + this.endIndex;
		}
	}

	/**
	 * Computes the total number of items available in the locked section of the workspace.
	 * The calculation considers wrapping around scenarios based on start and end indexes.
	 *
	 * @return the total count of available items based on current indices and workspace state.
	 */
	private int computeItemsAvailableLocked() {
		int available = 0;
		if (this.startIndex >= this.endIndex && this.wrappedAround) {
			available += countAvailableInRange(this.startIndex, this.workspace.length);
			available += countAvailableInRange(0, this.endIndex);
		} else if (this.startIndex < this.endIndex) {
			available += countAvailableInRange(this.startIndex, this.endIndex);
		}
		return available;
	}

	/**
	 * Exception thrown when a requested operation operates outside the scope of the ring buffer.
	 *
	 * This exception is thrown by the {@link #copyTo(Comparable, Queue)} method when the requested
	 * watermark refers to data that is no longer in the buffer (it has been discarded due to
	 * buffer overflow). Clients should handle this exception by requesting data from a different
	 * source, such as persistent storage or a write-ahead log.
	 */
	public static class OutsideScopeException extends Exception {
		/**
		 * Serial version UID for serialization.
		 */
		@Serial private static final long serialVersionUID = -7914519281407020017L;
		/**
		 * The effective start boundary of the buffer when the exception was thrown.
		 */
		private final Serializable effectiveStart;

		public OutsideScopeException(@Nonnull Serializable effectiveStart) {
			this.effectiveStart = effectiveStart;
		}

		/**
		 * Retrieves the effective start boundary of the buffer when the exception was thrown.
		 *
		 * @param <BOUNDARY> the type of the boundary, which extends {@link Comparable}.
		 * @return the effective start boundary, cast to the specified type.
		 */
		@Nonnull
		public <BOUNDARY extends Comparable<BOUNDARY>> BOUNDARY getEffectiveStart() {
			//noinspection unchecked
			return (BOUNDARY) this.effectiveStart;
		}

	}

}
