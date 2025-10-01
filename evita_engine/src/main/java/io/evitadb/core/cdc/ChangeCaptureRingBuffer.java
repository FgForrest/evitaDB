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

package io.evitadb.core.cdc;


import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToIntBiFunction;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * A ring buffer implementation for storing and retrieving {@link ChangeCatalogCapture} objects.
 * This buffer maintains a fixed-size circular array of catalog change captures, automatically
 * discarding the oldest entries when the buffer becomes full.
 *
 * The buffer tracks the effective start and end versions of the catalog changes it contains,
 * allowing clients to query for changes starting from a specific version and index position.
 * When the buffer is full and new items are added, the oldest items are removed, and the
 * effective start version and index are updated accordingly.
 *
 * This implementation uses a {@link ReentrantLock} to ensure thread safety for reading operations.
 * However, writing operations must always be performed from the same thread to maintain consistency.
 *
 * Class is thread safe for reading, but not thread safe for writing (writing must be always done from the same thread).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
class ChangeCaptureRingBuffer<T extends ChangeCapture> {
	/**
	 * Lock used to ensure thread safety for reading operations.
	 */
	private final ReentrantLock lock = new ReentrantLock();

	/**
	 * The circular array that stores the {@link ChangeCatalogCapture} objects.
	 */
	private final T[] workspace;

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
	 * The index of the oldest capture in the buffer.
	 * This is used to determine if a requested capture is within the buffer's scope.
	 */
	@Getter private int effectiveStartIndex;

	/**
	 * The catalog version of the oldest capture in the buffer.
	 * This is used to determine if a requested capture is within the buffer's scope.
	 */
	@Getter private long effectiveStartCatalogVersion;

	/**
	 * The latest catalog version that is visible in indexes.
	 * Captures with versions greater than this are not returned when copying to a target queue.
	 */
	@Setter @Getter private long effectiveLastCatalogVersion;

	/**
	 * Constructs a new ring buffer with the specified initial state and size.
	 *
	 * @param effectiveStartCatalogVersion the catalog version of the oldest capture that will be stored in the buffer
	 * @param effectiveStartIndex the index of the oldest capture that will be stored in the buffer
	 * @param effectiveLastCatalogVersion the latest catalog version that is visible in indexes
	 * @param bufferSize the size of the ring buffer (maximum number of captures that can be stored)
	 */
	public ChangeCaptureRingBuffer(
		final long effectiveStartCatalogVersion,
		final int effectiveStartIndex,
		long effectiveLastCatalogVersion,
		final int bufferSize,
		@Nonnull Class<T> type
	) {
		this.effectiveStartIndex = effectiveStartIndex;
		this.effectiveStartCatalogVersion = effectiveStartCatalogVersion;
		this.effectiveLastCatalogVersion = effectiveLastCatalogVersion;
		//noinspection unchecked
		this.workspace = (T[]) Array.newInstance(type, bufferSize);
	}

	/**
	 * Adds a new capture to the ring buffer. If the buffer is full, the oldest capture is removed
	 * to make space for the new one, and the effective start version and index are updated accordingly.
	 *
	 * This method is thread-safe as it acquires a lock before modifying the buffer.
	 *
	 * @param capture the capture to add to the buffer, must not be null
	 */
	public void offer(@Nonnull T capture) {
		this.lock.lock();
		try {
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
				final T startCapture = Objects.requireNonNull(this.workspace[newStartIndex]);
				this.effectiveStartCatalogVersion = startCapture.version();
				this.effectiveStartIndex = startCapture.index();
				this.startIndex = newStartIndex;
			}
			// add the new element
			this.workspace[this.endIndex - 1] = capture;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Copies captures from the ring buffer to the target queue, starting from the specified WAL pointer.
	 * Only captures with versions less than or equal to the effective last catalog version are copied.
	 *
	 * This method is thread-safe as it acquires a lock before reading from the buffer.
	 *
	 * @param walPointer the WAL pointer indicating where to start copying from, must not be null
	 * @param changeCatalogCaptures the target queue to copy captures to, must not be null
	 * @return last capture that was copied to the target queue
	 * @throws OutsideScopeException if the WAL pointer is outside the scope of captures currently in the buffer
	 */
	@Nonnull
	public Optional<T> copyTo(
		@Nonnull WalPointer walPointer,
		@Nonnull Queue<T> changeCatalogCaptures
	) throws OutsideScopeException {
		this.lock.lock();
		try {
			// throw exception if the walPointer is outside the covered scope
			if (walPointer.version() < this.effectiveStartCatalogVersion || (walPointer.version() == this.effectiveStartCatalogVersion && walPointer.index() < this.effectiveStartIndex)) {
				throw new OutsideScopeException();
			}
			if (this.startIndex >= this.endIndex && this.wrappedAround) {
				// The buffer has wrapped around, so we need to search in two segments
				final InsertionPosition headIndex = ArrayUtils.computeInsertPositionOfObjInOrderedArray(walPointer, this.workspace, this.startIndex, this.workspace.length, WalPointerCaptureComparator.INSTANCE);
				if (headIndex.alreadyPresent() || headIndex.position() < this.workspace.length) {
					// Found in the first segment, copy from there to the end, then from start to endIndex
					final Optional<T> tailResult = copyTo(headIndex.position(), this.workspace.length, changeCatalogCaptures);
					return copyTo(0, this.endIndex, changeCatalogCaptures).or(() -> tailResult);
				} else  {
					// Not found in the first segment, try the second segment
					final InsertionPosition tailIndex = ArrayUtils.computeInsertPositionOfObjInOrderedArray(walPointer, this.workspace, 0, this.endIndex, WalPointerCaptureComparator.INSTANCE);
					if (tailIndex.alreadyPresent() || tailIndex.position() < this.endIndex) {
						return copyTo(tailIndex.position(), this.endIndex, changeCatalogCaptures);
					} else {
						return empty();
					}
				}
			} else if (this.startIndex < this.endIndex) {
				// The buffer hasn't wrapped around, so we can search in a single segment
				final InsertionPosition index = ArrayUtils.computeInsertPositionOfObjInOrderedArray(walPointer, this.workspace, this.startIndex, this.endIndex, WalPointerCaptureComparator.INSTANCE);
				if (index.alreadyPresent() || index.position() < this.endIndex) {
					return copyTo(index.position(), this.endIndex, changeCatalogCaptures);
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
	 * Clears all entries in the ring buffer up to (but not including) the specified catalog version.
	 * Entries with versions less than the specified untilCatalogVersion are set to null, and the
	 * starting index of the buffer is updated accordingly. If the buffer is wrapped around, it clears
	 * entries in two segments: first clearing from the start index to the end of the array, then
	 * clearing from the beginning of the array to the end index.
	 *
	 * This method acquires a lock to ensure thread safety during the operation.
	 *
	 * @param untilCatalogVersion the catalog version up to which entries in the buffer should be cleared
	 */
	public void clearAllUntil(long untilCatalogVersion) {
		this.lock.lock();
		try {
			this.effectiveStartIndex = 0;
			boolean finished = false;
			if (this.startIndex >= this.endIndex && this.wrappedAround) {
				finished = clearSegmentUntil(this.startIndex, this.workspace.length, untilCatalogVersion);
				if (!finished) {
					// we've cleared the entire tail, now we need to clear the head
					this.startIndex = 0;
					finished = clearSegmentUntil(0, this.endIndex, untilCatalogVersion);
					this.wrappedAround = false;
				}
			} else if (this.startIndex < this.endIndex) {
				finished = clearSegmentUntil(this.startIndex, this.endIndex, untilCatalogVersion);
				this.wrappedAround = false;
			}
			if (finished) {
				this.effectiveStartCatalogVersion = Objects.requireNonNull(this.workspace[this.startIndex]).version();
			} else {
				this.effectiveStartCatalogVersion = untilCatalogVersion;
			}
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Clears all entries in the ring buffer with versions greater than the specified catalog version.
	 * Entries with versions greater than the specified catalogVersion are set to null, and the
	 * end index of the buffer is updated accordingly. If the buffer is wrapped around, it clears
	 * entries in two segments: first clearing from the start index to the end of the array, then
	 * clearing from the beginning of the array to the end index.
	 *
	 * In fact this method doesn't clear the buffer, just sets the start and end indexes properly.
	 *
	 * This method acquires a lock to ensure thread safety during the operation.
	 *
	 * @param catalogVersion the catalog version after which entries in the buffer should be cleared
	 */
	public void clearAllAfter(long catalogVersion) {
		this.lock.lock();
		try {
			if (this.startIndex >= this.endIndex && this.wrappedAround) {
				// Buffer has wrapped around, clear in two segments
				int lastValidEntry = findLastValidEntry(this.startIndex, this.workspace.length, catalogVersion);
				if (lastValidEntry == this.workspace.length - 1) {
					lastValidEntry = findLastValidEntry(0, this.endIndex, catalogVersion);
					if (lastValidEntry == -1) {
						this.endIndex = this.workspace.length;
						this.wrappedAround = false;
					} else {
						this.endIndex = lastValidEntry + 1;
					}
				} else {
					this.endIndex = lastValidEntry == -1 ? this.startIndex : lastValidEntry + 1;
					this.wrappedAround = false;
				}
			} else if (this.startIndex < this.endIndex) {
				// Buffer hasn't wrapped around, clear in a single segment
				final int lastValidEntry = findLastValidEntry(this.startIndex, this.endIndex, catalogVersion);
				this.endIndex = lastValidEntry == -1 ? this.startIndex : lastValidEntry + 1;
				this.wrappedAround = false;
			}
			// If buffer is empty, nothing to clear
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Finds the last valid entry in the specified segment of the ring buffer and returns its index.
	 *
	 * @param fromIndex the starting index of the segment to clear (inclusive)
	 * @param toIndex the ending index of the segment to clear (exclusive)
	 * @param catalogVersion the catalog version after which entries should be cleared
	 * @return last cleared index
	 */
	private int findLastValidEntry(
		int fromIndex,
		int toIndex,
		long catalogVersion
	) {
		int lastValidEntry = -1;
		for (int i = fromIndex; i < toIndex; i++) {
			if (this.workspace[i] != null && this.workspace[i].version() <= catalogVersion) {
				lastValidEntry = i;
			} else {
				return lastValidEntry;
			}
		}
		return lastValidEntry;
	}

	/**
	 * Clears a segment of the ring buffer up to (but not including) the specified catalog version.
	 * This method iterates through the specified range of the workspace array and clears entries
	 * with versions less than the specified untilCatalogVersion.
	 *
	 * @param fromIndex the starting index of the segment to clear (inclusive)
	 * @param toIndex the ending index of the segment to clear (exclusive)
	 * @param untilCatalogVersion the catalog version up to which entries should be cleared
	 * @return true if the clearing operation finished (i.e., reached the active catalog version),
	 */
	private boolean clearSegmentUntil(
		int fromIndex,
		int toIndex,
		long untilCatalogVersion
	) {
		for (int i = fromIndex; i < toIndex; i++) {
			if (this.workspace[i] != null && this.workspace[i].version() < untilCatalogVersion) {
				this.workspace[i] = null;
			} else if (this.workspace[i] != null) {
				// we reached the active catalog version, finish
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
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Copies captures from a specific range in the buffer to the target queue.
	 * Only captures with versions less than or equal to the effective last catalog version are copied.
	 * The copying stops if any of the following conditions are met:
	 * <ul>
	 *   <li>A capture with a version greater than the effective last catalog version is encountered</li>
	 *   <li>The target queue refuses to accept a capture (returns false from offer)</li>
	 *   <li>The end of the specified range is reached</li>
	 * </ul>
	 *
	 * @param from the starting index in the buffer (inclusive)
	 * @param to the ending index in the buffer (exclusive)
	 * @param target the target queue to copy captures to, must not be null
	 * @return last capture that was copied to the target queue
	 */
	@Nonnull
	private Optional<T> copyTo(int from, int to, @Nonnull Queue<T> target) {
		boolean continueCopy = true;
		int index = from;
		T currentCapture = null;
		T lastCapture = null;
		while (continueCopy) {
			lastCapture = currentCapture;
			currentCapture = this.workspace[index];
			// stop if we reach `to` index or if the capture relates to a catalog version not yet visible in indexes
			continueCopy = index++ < to &&
				currentCapture.version() <= this.effectiveLastCatalogVersion &&
				target.offer(currentCapture) &&
				index < this.workspace.length;
		}
		return ofNullable(lastCapture);
	}

	/**
	 * A comparator used for binary search operations to find the position of a {@link WalPointer}
	 * within an array of {@link ChangeCatalogCapture} objects.
	 * <p>
	 * The comparison is based first on the version and then on the index if versions are equal.
	 * This allows finding the exact position or the insertion point for a WAL pointer in the buffer.
	 */
	private static class WalPointerCaptureComparator implements ToIntBiFunction<ChangeCapture, WalPointer> {
		/**
		 * Singleton instance of the comparator to avoid creating multiple instances.
		 */
		public static final WalPointerCaptureComparator INSTANCE = new WalPointerCaptureComparator();

		/**
		 * Private constructor to enforce singleton pattern.
		 */
		private WalPointerCaptureComparator() {}

		/**
		 * Compares a {@link ChangeCatalogCapture} with a {@link WalPointer}.
		 *
		 * @param capture the capture to compare
		 * @param walPointer the WAL pointer to compare against
		 * @return a negative integer if the capture is less than the WAL pointer,
		 *         zero if they are equal, or a positive integer if the capture is greater
		 */
		@Override
		public int applyAsInt(ChangeCapture capture, WalPointer walPointer) {
			// nulls are always greater
			if (capture == null) {
				return 1;
			}
			// compare by version first, then by index
			if (capture.version() == walPointer.version()) {
				return Integer.compare(capture.index(), walPointer.index());
			} else if (capture.version() < walPointer.version()) {
				return -1;
			} else {
				return 1;
			}
		}
	}

	/**
	 * Exception thrown when a requested operation operates outside the scope of the ring buffer.
	 * <p>
	 * This exception is thrown by the {@link #copyTo(WalPointer, Queue)} method when the requested
	 * WAL pointer refers to a capture that is no longer in the buffer (it has been discarded due to
	 * buffer overflow). Clients should handle this exception by requesting captures from a different
	 * source, such as a write-ahead log.
	 */
	public static class OutsideScopeException extends Exception {
		/**
		 * Serial version UID for serialization.
		 */
		@Serial private static final long serialVersionUID = -7914519281407020017L;
	}

}
