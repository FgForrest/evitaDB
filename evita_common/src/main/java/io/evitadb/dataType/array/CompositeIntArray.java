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

import io.evitadb.dataType.iterator.BatchArrayIterator;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;

import static io.evitadb.utils.MemoryMeasuringConstants.*;

/**
 * Composite array is a way around fixed size arrays. It allows to have arrays with elastic size composed of handful of
 * smaller arrays of specified CHUNK_SIZE, that are as necessary. This class is similar to ArrayList but doesn't
 * reallocate entire array to the bigger one, just asks for another small chunk if the current array limit is exceeded.
 * This implementation is append only.
 *
 * When you know the array will hold ordered distinct integers it's much more efficient to use
 * {@link org.roaringbitmap.RoaringBitmap} instead of this data structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@Slf4j
public class CompositeIntArray implements Serializable {
	@Serial private static final long serialVersionUID = -2841590944033782494L;
	private static final int CHUNK_SIZE = 50;

	/**
	 * List of all chunks used in this instance.
	 */
	@Nonnull
	private final List<int[]> chunks = new LinkedList<>();
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
	private int[] currentChunk;

	/**
	 * Converts any int iterator to an array.
	 */
	public static int[] toArray(OfInt iterator) {
		return toCompositeArray(iterator).toArray();
	}

	/**
	 * Converts any int iterator to an elastic {@link CompositeIntArray}.
	 */
	public static CompositeIntArray toCompositeArray(OfInt iterator) {
		final CompositeIntArray result = new CompositeIntArray();
		while (iterator.hasNext()) {
			result.add(iterator.nextInt());
		}
		return result;
	}

	public CompositeIntArray() {
		this.currentChunk = new int[CHUNK_SIZE];
		this.chunks.add(this.currentChunk);
	}

	public CompositeIntArray(int... records) {
		this();
		addAll(records, 0, records.length);
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
	public int getLast() {
		return this.currentChunk[this.chunkPeek];
	}

	/**
	 * Return number on the specific index of the array.
	 */
	public int get(int index) {
		final int chunkIndex = index / CHUNK_SIZE;
		final int indexInChunk = index % CHUNK_SIZE;
		Assert.isTrue(chunkIndex < this.chunks.size(), "Chunk index " + chunkIndex + " exceeds chunks size (" + this.chunks.size() + ").");
		return this.chunks.get(chunkIndex)[indexInChunk];
	}

	/**
	 * Returns copy of the array starting at `start` position (inclusive) and ending at `end` position (exclusive).
	 * This method discards the {@link CompositeIntArray}.
	 */
	public int[] getRange(int start, int end) {
		int chunkIndex = start / CHUNK_SIZE;
		int startIndex = start % CHUNK_SIZE;
		int bytesToCopy = end - start;
		int resultPeek = 0;
		final int[] result = new int[bytesToCopy];
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
	public boolean contains(int recordId) {
		for (int[] chunk : this.chunks) {
			if (this.monotonic) {
				// use fast binary search if array contains only monotonic record ids
				//noinspection ArrayEquality
				if (chunk == this.currentChunk) {
					if (Arrays.binarySearch(chunk, 0, this.chunkPeek + 1, recordId) >= 0) {
						return true;
					}
				} else {
					if (Arrays.binarySearch(chunk, recordId) >= 0) {
						return true;
					}
				}
			} else {
				// else array must be full scanned
				for (int theNumber : chunk) {
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
	public int indexOf(int recordId) {
		for (int i = 0; i < this.chunks.size(); i++) {
			final int[] chunk = this.chunks.get(i);
			int index;
			if (this.monotonic) {
				// use fast binary search if array contains only monotonic record ids
				index = Arrays.binarySearch(chunk, recordId);
			} else {
				// else array must be full scanned
				index = -1 * (CHUNK_SIZE + 1);
				for (int j = 0; j < chunk.length; j++) {
					final int theNumber = chunk[j];
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
	public void add(int number) {
		// keep eye on monotonic row
		if (this.monotonic && this.chunkPeek != -1 && number <= this.currentChunk[this.chunkPeek]) {
			this.monotonic = false;
		}

		// if last chunk was depleted obtain another one
		if (++this.chunkPeek == CHUNK_SIZE) {
			this.chunkPeek = 0;
			this.currentChunk = new int[CHUNK_SIZE];
			this.chunks.add(this.currentChunk);
		}

		this.currentChunk[this.chunkPeek] = number;
	}

	/**
	 * Appends a bunch of numbers starting at index `srcPosition` of `numbers` array and copying `length` numbers from it.
	 */
	public void addAll(@Nonnull int[] numbers, int srcPosition, int length) {

		if (numbers.length == 0 || length == 0) {
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
				int lastNumber = numbers[srcPosition];
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
				this.currentChunk = new int[CHUNK_SIZE];
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
	 * Creates new integer array that spans all chunks borrowed so far with all numbers in it.
	 */
	@Nonnull
	public int[] toArray() {
		final int size = getSize();
		final int[] result = new int[size];
		final Iterator<int[]> it = this.chunks.iterator();
		int copied = 0;
		while (copied < size) {
			final int[] chunk = it.next();
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
	 * Returns an iterator over the elements in this composite integer array.
	 *
	 * @return an iterator of type {@code OfInt} to traverse the elements of the composite integer array.
	 */
	@Nonnull
	public OfInt iterator() {
		return new CompositeIntArrayOfInt();
	}

	/**
	 * Returns an {@link OfInt} iterator starting from the given integer value.
	 *
	 * @param index the starting point from which the iterator will begin
	 * @return an {@link OfInt} iterator instance that starts from the specified value
	 */
	@Nonnull
	public OfInt iteratorFrom(int index) {
		return new CompositeIntArrayOfInt(index);
	}

	/**
	 * Returns an implementation of {@link BatchArrayIterator} to traverse through composite array in more optimal way.
	 */
	@Nonnull
	public BatchArrayIterator batchIterator() {
		return new CompositeBatchIterator();
	}

	/**
	 * Returns estimated memory size of this object in Bytes. Returned size si only rough estimate - not a precise number.
	 */
	public int getSizeInBytes() {
		return OBJECT_HEADER_SIZE + 2 * REFERENCE_SIZE + BYTE_SIZE + INT_SIZE +
			this.chunkPeek * ARRAY_BASE_SIZE +
			this.chunkPeek * CHUNK_SIZE * INT_SIZE;
	}

	@Override
	public int hashCode() {
		final OfInt it = iterator();
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
		CompositeIntArray that = (CompositeIntArray) o;
		final OfInt it = iterator();
		final OfInt it2 = that.iterator();
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
	private class CompositeIntArrayOfInt implements OfInt {
		private final Iterator<int[]> chunkIterator;
		private int chunkIndex;
		private int index;
		@Nullable private int[] currentChunk;

		CompositeIntArrayOfInt() {
			this(0);
		}

		CompositeIntArrayOfInt(int index) {
			this.index = index - 1;
			this.chunkIndex = Math.max(-1, index % CHUNK_SIZE - 1);
			this.chunkIterator = CompositeIntArray.this.chunks.listIterator(index / CHUNK_SIZE);
			this.currentChunk = null;
		}

		@Override
		public int nextInt() {
			final boolean endOfChunk = this.chunkIndex + 1 >= CHUNK_SIZE;
			if (endOfChunk || this.currentChunk == null) {
				if (endOfChunk) {
					this.chunkIndex = -1;
				}
				this.currentChunk = this.chunkIterator.hasNext() ? this.chunkIterator.next() : null;
			}
			if (this.currentChunk == null) {
				throw new NoSuchElementException("End of the array reached - max number of elements is " + getSize());
			}
			this.index++;
			return this.currentChunk[++this.chunkIndex];
		}

		@Override
		public boolean hasNext() {
			return CompositeIntArray.this.getSize() > this.index + 1;
		}
	}

	/**
	 * Batch iterator implementation.
	 */
	private class CompositeBatchIterator implements BatchArrayIterator {
		private int peekArray = -1;

		@Override
		public boolean hasNext() {
			return CompositeIntArray.this.chunks.size() > this.peekArray + 1;
		}

		@Override
		public int[] nextBatch() {
			if (this.peekArray == CompositeIntArray.this.chunks.size()) {
				throw new NoSuchElementException("End of the array reached - max number of batches is " + CompositeIntArray.this.chunkPeek);
			}
			return CompositeIntArray.this.chunks.get(++this.peekArray);
		}

		@Override
		public void advanceIfNeeded(int target) {
			if (CompositeIntArray.this.monotonic) {
				while (this.peekArray + 1 < CompositeIntArray.this.chunkPeek - 1 &&
					CompositeIntArray.this.chunks.get(this.peekArray + 1)[CHUNK_SIZE - 1] < target) {
					this.peekArray++;
				}
			}
		}

		@Override
		public int getPeek() {
			if (CompositeIntArray.this.chunks.size() == this.peekArray + 1) {
				return CompositeIntArray.this.chunkPeek + 1;
			} else {
				return CHUNK_SIZE;
			}
		}
	}
}
