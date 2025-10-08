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

package io.evitadb.dataType.array;

import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;
import java.util.PrimitiveIterator.OfLong;

/**
 * Composite array is a way around fixed size arrays. It allows to have arrays with elastic size composed of handful of
 * smaller arrays of specified CHUNK_SIZE, that are created as necessary. This class is similar to ArrayList but doesn't
 * reallocate entire array to the bigger one, just asks for another small chunk if the current array limit is exceeded.
 * This implementation is append only.
 *
 * When you know the array will hold ordered distinct longs it's much more efficient to use Roaring64Bitmap instead of
 * this data structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@Slf4j
public class CompositeLongArray {
	private static final int CHUNK_SIZE = 50;

	/**
	 * List of all chunks used in this instance.
	 */
	@Nonnull
	private final List<long[]> chunks = new LinkedList<>();
	/**
	 * Contains TRUE if array contains only non-duplicated monotonically increasing numbers.
	 */
	private boolean monotonic = true;
	/**
	 * Peek of the array (i.e. last written number) in current chunk.
	 * I.e. with two chunks of size 512 and 850 numbers written it is 337.
	 */
	private int chunkPeek = -1;
	/**
	 * Current chunk that is being written to.
	 */
	@Nonnull
	private long[] currentChunk;

	public CompositeLongArray() {
		this.currentChunk = new long[CHUNK_SIZE];
		this.chunks.add(this.currentChunk);
	}

	public CompositeLongArray(long... records) {
		this();
		addAll(records, 0, records.length);
	}

	/**
	 * Converts any long iterator to an array.
	 */
	public static long[] toArray(OfInt iterator) {
		return toCompositeArray(iterator).toArray();
	}

	/**
	 * Converts any long iterator to an elastic {@link CompositeLongArray}.
	 */
	public static CompositeLongArray toCompositeArray(OfInt iterator) {
		final CompositeLongArray result = new CompositeLongArray();
		while (iterator.hasNext()) {
			result.add(iterator.nextInt());
		}
		return result;
	}

	/**
	 * Returns true if the instance is empty and contains no numbers.
	 */
	public boolean isEmpty() {
		return this.chunkPeek == -1;
	}

	/**
	 * Returns last number written to the composite array.
	 */
	public long getLast() {
		return this.currentChunk[this.chunkPeek];
	}

	/**
	 * Return number on the specific index of the array.
	 */
	public long get(int index) {
		final int chunkIndex = index / CHUNK_SIZE;
		final int indexInChunk = index % CHUNK_SIZE;
		Assert.isTrue(chunkIndex < this.chunks.size(), "Chunk index " + chunkIndex + " exceeds chunks size (" + this.chunks.size() + ").");
		return this.chunks.get(chunkIndex)[indexInChunk];
	}

	/**
	 * Returns copy of the array starting at `start` position (inclusive) and ending at `end` position (exclusive).
	 * This method discards the {@link CompositeLongArray}.
	 */
	public long[] getRange(int start, int end) {
		int chunkIndex = start / CHUNK_SIZE;
		int startIndex = start % CHUNK_SIZE;
		int bytesToCopy = end - start;
		int resultPeek = 0;
		final long[] result = new long[bytesToCopy];
		do {
			boolean lastChunk = chunkIndex == this.chunks.size() - 1;
			if (lastChunk && bytesToCopy > this.chunkPeek + 1 - startIndex) {
				throw new ArrayIndexOutOfBoundsException(
					"Index: " + this.chunkPeek + ", Size: " + getSize()
				);
			}
			final int copiedSize = Math.min(bytesToCopy, CHUNK_SIZE - startIndex);
			System.arraycopy(this.chunks.get(chunkIndex), startIndex, result, resultPeek, copiedSize);
			bytesToCopy -= copiedSize;
			resultPeek += copiedSize;
			startIndex = 0;
			chunkIndex++;
		} while (bytesToCopy > 0);

		return result;
	}

	/**
	 * Returns true if the specified record is already part of the array.
	 */
	public boolean contains(long recordId) {
		for (long[] chunk : this.chunks) {
			if (this.monotonic) {
				// use fast binary search if array contains only monotonic record ids
				if (Arrays.binarySearch(chunk, recordId) >= 0) {
					return true;
				}
			} else {
				// else array must be full scanned
				for (long theNumber : chunk) {
					if (recordId == theNumber) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Returns index of the recordId in the array.
	 */
	public int indexOf(long recordId) {
		for (int i = 0; i < this.chunks.size(); i++) {
			final long[] chunk = this.chunks.get(i);
			int index;
			if (this.monotonic) {
				// use fast binary search if array contains only monotonic record ids
				index = Arrays.binarySearch(chunk, recordId);
			} else {
				// else array must be full scanned
				index = -1 * (CHUNK_SIZE + 1);
				for (int j = 0; j < chunk.length; j++) {
					final long theNumber = chunk[j];
					if (theNumber == recordId) {
						index = j;
						break;
					}
				}
			}
			if (index >= 0) {
				return i * CHUNK_SIZE + index;
			} else if (index * -1 > CHUNK_SIZE) {
				// continue searching - the index is out of this chunk
			} else if (index < -1) {
				return -1 * CHUNK_SIZE + index;
			}
		}
		return -1;
	}

	/**
	 * Returns the size of the array.
	 */
	public int getSize() {
		return ((this.chunks.size() - 1) * CHUNK_SIZE) + this.chunkPeek + 1;
	}

	/**
	 * Overwrites number at the specified index of the array.
	 */
	public void set(int recordId, int index) {
		Assert.isTrue(index < getSize(), "Index out of bounds!");
		this.chunks.get(index / CHUNK_SIZE)[index % CHUNK_SIZE] = recordId;
	}

	/**
	 * Appends another number at the end of the array.
	 */
	public void add(long number) {
		// keep eye on monotonic row
		if (this.monotonic && this.chunkPeek != -1 && number <= this.currentChunk[this.chunkPeek]) {
			this.monotonic = false;
		}

		// if last chunk was depleted obtain another one
		if (++this.chunkPeek == CHUNK_SIZE) {
			this.chunkPeek = 0;
			this.currentChunk = new long[CHUNK_SIZE];
			this.chunks.add(this.currentChunk);
		}

		this.currentChunk[this.chunkPeek] = number;
	}

	/**
	 * Appends a bunch of numbers starting at index `srcPosition` of `numbers` array and copying `length` numbers from it.
	 */
	public void addAll(@Nonnull long[] numbers, int srcPosition, int length) {

		if (numbers.length == 0) {
			return;
		}

		if (numbers.length < srcPosition + 1) {
			throw new ArrayIndexOutOfBoundsException();
		}

		// reset monotonic flag if added numbers violate monotonic row
		if (this.monotonic) {
			if (this.chunkPeek != -1 && this.currentChunk[this.chunkPeek] >= numbers[srcPosition]) {
				this.monotonic = false;
			} else {
				long lastNumber = numbers[srcPosition];
				for (int i = srcPosition + 1; i < length; i++) {
					if (lastNumber >= numbers[i]) {
						this.monotonic = false;
						break;
					}
					lastNumber = numbers[i];
				}
			}
		}

		int restLength = length;
		int currentSrcPos = srcPosition;

		// copy array contents when there is some numbers in it
		while (restLength > 0) {
			final int copyPosition;

			// if the current chunk is depleted borrow another one
			if (this.chunkPeek + 1 == CHUNK_SIZE) {
				this.chunkPeek = -1;
				this.currentChunk = new long[CHUNK_SIZE];
				this.chunks.add(this.currentChunk);
			}
			copyPosition = this.chunkPeek + 1;

			final int availableSizeInChunk = CHUNK_SIZE - copyPosition;
			final int copyLength = Math.min(availableSizeInChunk, restLength);

			System.arraycopy(numbers, currentSrcPos, this.currentChunk, copyPosition, copyLength);

			this.chunkPeek += copyLength;
			currentSrcPos += copyLength;
			restLength -= copyLength;
		}
	}

	/**
	 * Creates new long array that spans all chunks borrowed so far with all numbers in it.
	 */
	@Nonnull
	public long[] toArray() {
		final int size = getSize();
		final long[] result = new long[size];
		final Iterator<long[]> it = this.chunks.iterator();
		int copied = 0;
		while (copied < size) {
			final long[] chunk = it.next();
			final int restCount = size - copied;
			final int lengthToCopy = Math.min(restCount, chunk.length);
			System.arraycopy(chunk, 0, result, copied, lengthToCopy);
			copied += lengthToCopy;
		}
		return result;
	}

	/**
	 * Returns TRUE if array contains only non-duplicated monotonically increasing numbers.
	 */
	public boolean isMonotonic() {
		return this.monotonic;
	}

	/**
	 * Returns iterator over the composite array.
	 */
	@Nonnull
	public OfLong iterator() {
		return new CompositeLongArrayOfLong();
	}

	@Override
	public int hashCode() {
		final OfLong it = iterator();
		int hashCode = 0;
		while (it.hasNext()) {
			hashCode += 31 * it.next().hashCode();
		}
		return hashCode;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		CompositeLongArray that = (CompositeLongArray) o;
		final OfLong it = iterator();
		final OfLong it2 = that.iterator();
		while (it.hasNext() && it2.hasNext()) {
			if (!it.next().equals(it2.next())) {
				return false;
			}
		}
		return !it2.hasNext();
	}

	/**
	 * Iterator implementation.
	 */
	private class CompositeLongArrayOfLong implements OfLong {
		private final Iterator<long[]> chunkIterator;
		private final int size;
		private int chunkIndex;
		private int index;
		private long[] currentChunk;

		CompositeLongArrayOfLong() {
			this.index = -1;
			this.chunkIndex = CHUNK_SIZE;
			this.currentChunk = ArrayUtils.EMPTY_LONG_ARRAY;
			this.size = CompositeLongArray.this.getSize();
			this.chunkIterator = CompositeLongArray.this.chunks.iterator();
		}

		@Override
		public long nextLong() {
			if (this.index == this.size) {
				throw new NoSuchElementException("End of the array reached - max number of elements is " + getSize());
			}
			if (this.chunkIndex + 1 >= CHUNK_SIZE) {
				this.chunkIndex = -1;
				this.currentChunk = this.chunkIterator.next();
			}
			this.index++;
			return this.currentChunk[++this.chunkIndex];
		}

		@Override
		public boolean hasNext() {
			return this.size > this.index + 1;
		}
	}
}
