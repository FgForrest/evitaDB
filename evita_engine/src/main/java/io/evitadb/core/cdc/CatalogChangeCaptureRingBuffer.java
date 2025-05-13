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


import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.core.cdc.ChangeCatalogCapturePublisher.WalPointer;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToIntBiFunction;

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
class CatalogChangeCaptureRingBuffer {
	/**
	 * Lock used to ensure thread safety for reading operations.
	 */
	private final ReentrantLock lock = new ReentrantLock();

	/**
	 * The circular array that stores the {@link ChangeCatalogCapture} objects.
	 */
	private final ChangeCatalogCapture[] workspace;

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
	public CatalogChangeCaptureRingBuffer(
		final long effectiveStartCatalogVersion,
		final int effectiveStartIndex,
		long effectiveLastCatalogVersion,
		final int bufferSize
	) {
		this.effectiveStartIndex = effectiveStartIndex;
		this.effectiveStartCatalogVersion = effectiveStartCatalogVersion;
		this.effectiveLastCatalogVersion = effectiveLastCatalogVersion;
		this.workspace = new ChangeCatalogCapture[bufferSize];
	}

	/**
	 * Adds a new capture to the ring buffer. If the buffer is full, the oldest capture is removed
	 * to make space for the new one, and the effective start version and index are updated accordingly.
	 *
	 * This method is thread-safe as it acquires a lock before modifying the buffer.
	 *
	 * @param capture the capture to add to the buffer, must not be null
	 */
	public void offer(@Nonnull ChangeCatalogCapture capture) {
		this.lock.lock();
		try {
			this.endIndex++;
			// wrap around the ring buffer
			if (this.endIndex == this.workspace.length + 1) {
				this.endIndex = 1;
			}
			// if the ring buffer is full, remove the oldest element
			if (this.endIndex - 1 == this.startIndex) {
				// remove the oldest element
				final ChangeCatalogCapture removedCapture = Objects.requireNonNull(this.workspace[this.startIndex]);
				this.effectiveStartCatalogVersion = removedCapture.version();
				this.effectiveStartIndex = removedCapture.index();
				this.startIndex++;
			}
			// wrap around the ring buffer
			if (this.startIndex == this.workspace.length) {
				this.startIndex = 0;
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
	 * @throws OutsideScopeException if the WAL pointer is outside the scope of captures currently in the buffer
	 */
	public void copyTo(
		@Nonnull WalPointer walPointer,
		@Nonnull Queue<ChangeCatalogCapture> changeCatalogCaptures
	) throws OutsideScopeException {
		this.lock.lock();
		try {
			// throw exception if the walPointer is outside the covered scope
			if (walPointer.version() < this.effectiveStartCatalogVersion || (walPointer.version() == this.effectiveStartCatalogVersion && walPointer.index() < this.effectiveStartIndex)) {
				throw new OutsideScopeException();
			}
			if (this.startIndex >= this.endIndex) {
				// The buffer has wrapped around, so we need to search in two segments
				final int headIndex = ArrayUtils.binarySearch(this.workspace, walPointer, this.startIndex, this.workspace.length, WalPointerCaptureComparator.INSTANCE);
				if (headIndex >= 0) {
					// Found in the first segment, copy from there to the end, then from start to endIndex
					copyTo(headIndex, this.workspace.length, changeCatalogCaptures);
					copyTo(0, this.endIndex, changeCatalogCaptures);
				} else {
					// Not found in the first segment, try the second segment
					final int tailIndex = ArrayUtils.binarySearch(this.workspace, walPointer, 0, this.endIndex, WalPointerCaptureComparator.INSTANCE);
					if (tailIndex >= 0) {
						copyTo(tailIndex, this.endIndex, changeCatalogCaptures);
					}
				}
			} else {
				// The buffer hasn't wrapped around, so we can search in a single segment
				final int index = ArrayUtils.binarySearch(this.workspace, walPointer, this.startIndex, this.endIndex, WalPointerCaptureComparator.INSTANCE);
				if (index >= 0) {
					copyTo(index, this.endIndex, changeCatalogCaptures);
				}
			}
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
	 */
	private void copyTo(int from, int to, @Nonnull Queue<ChangeCatalogCapture> target) {
		boolean continueCopy = true;
		int index = from;
		while (continueCopy) {
			final ChangeCatalogCapture capture = this.workspace[index];
			// stop if we reach `to` index or if the capture relates to a catalog version not yet visible in indexes
			continueCopy = capture.version() <= this.effectiveLastCatalogVersion && target.offer(capture) && index++ < to;
		}
	}

	/**
	 * A comparator used for binary search operations to find the position of a {@link WalPointer}
	 * within an array of {@link ChangeCatalogCapture} objects.
	 * <p>
	 * The comparison is based first on the version and then on the index if versions are equal.
	 * This allows finding the exact position or the insertion point for a WAL pointer in the buffer.
	 */
	private static class WalPointerCaptureComparator implements ToIntBiFunction<ChangeCatalogCapture, WalPointer> {
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
		public int applyAsInt(ChangeCatalogCapture capture, WalPointer walPointer) {
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
