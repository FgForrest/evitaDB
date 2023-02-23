/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.index.array;

import io.evitadb.index.iterator.BatchArrayIterator;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
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
		return chunkPeek == -1;
	}

	/**
	 * Returns last number written to the composite array.
	 */
	public int getLast() {
		return currentChunk[chunkPeek];
	}

	/**
	 * Return number on the specific index of the array.
	 */
	public int get(int index) {
		final int chunkIndex = index / CHUNK_SIZE;
		final int indexInChunk = index % CHUNK_SIZE;
		Assert.isTrue(chunkIndex < chunks.size(), "Chunk index " + chunkIndex + " exceeds chunks size (" + chunks.size() + ").");
		return chunks.get(chunkIndex)[indexInChunk];
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
			boolean lastChunk = chunkIndex == chunks.size() - 1;
			if (lastChunk && bytesToCopy > chunkPeek + 1 - startIndex) {
				throw new ArrayIndexOutOfBoundsException(
					"Index: " + chunkPeek + ", Size: " + getSize()
				);
			}
			final int copiedSize = Math.min(bytesToCopy, CHUNK_SIZE - startIndex);
			System.arraycopy(chunks.get(chunkIndex), startIndex, result, resultPeek, copiedSize);
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
		for (int[] chunk : chunks) {
			if (monotonic) {
				// use fast binary search if array contains only monotonic record ids
				if (Arrays.binarySearch(chunk, recordId) >= 0) {
					return true;
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
		for (int i = 0; i < chunks.size(); i++) {
			final int[] chunk = chunks.get(i);
			int index;
			if (monotonic) {
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
		return ((chunks.size() - 1) * CHUNK_SIZE) + chunkPeek + 1;
	}

	/**
	 * Overwrites number at the specified index of the array.
	 */
	public void set(int recordId, int index) {
		Assert.isTrue(index < getSize(), "Index out of bounds!");
		chunks.get(index / CHUNK_SIZE)[index % CHUNK_SIZE] = recordId;
	}

	/**
	 * Appends another number at the end of the array.
	 */
	public void add(int number) {
		// keep eye on monotonic row
		if (monotonic && chunkPeek != -1 && number <= currentChunk[chunkPeek]) {
			monotonic = false;
		}

		// if last chunk was depleted obtain another one
		if (++chunkPeek == CHUNK_SIZE) {
			chunkPeek = 0;
			currentChunk = new int[CHUNK_SIZE];
			chunks.add(currentChunk);
		}

		currentChunk[chunkPeek] = number;
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
		if (monotonic) {
			if (chunkPeek != -1 && currentChunk[chunkPeek] >= numbers[srcPosition]) {
				monotonic = false;
			} else {
				int lastNumber = numbers[srcPosition];
				for (int i = srcPosition + 1; i < length; i++) {
					if (lastNumber >= numbers[i]) {
						monotonic = false;
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
			if (chunkPeek + 1 == CHUNK_SIZE) {
				chunkPeek = -1;
				currentChunk = new int[CHUNK_SIZE];
				chunks.add(currentChunk);
			}
			copyPosition = chunkPeek + 1;

			final int availableSizeInChunk = CHUNK_SIZE - copyPosition;
			final int copyLength = Math.min(availableSizeInChunk, restLength);

			System.arraycopy(numbers, currentSrcPos, currentChunk, copyPosition, copyLength);

			chunkPeek += copyLength;
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
		final Iterator<int[]> it = chunks.iterator();
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
	 * Returns iterator over the composite array.
	 */
	@Nonnull
	public OfInt iterator() {
		return new CompositeIntArrayOfInt();
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
			chunkPeek * ARRAY_BASE_SIZE +
			chunkPeek * CHUNK_SIZE * INT_SIZE;
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
		private final int size;
		private int chunkIndex;
		private int index;
		private int[] currentChunk;

		CompositeIntArrayOfInt() {
			this.index = -1;
			this.chunkIndex = CHUNK_SIZE;
			this.currentChunk = null;
			this.size = CompositeIntArray.this.getSize();
			this.chunkIterator = CompositeIntArray.this.chunks.iterator();
		}

		@Override
		public int nextInt() {
			if (this.index == size) {
				throw new NoSuchElementException("End of the array reached - max number of elements is " + getSize());
			}
			if (this.chunkIndex + 1 >= CHUNK_SIZE) {
				this.chunkIndex = -1;
				this.currentChunk = chunkIterator.next();
			}
			this.index++;
			return this.currentChunk[++chunkIndex];
		}

		@Override
		public boolean hasNext() {
			return size > index + 1;
		}
	}

	/**
	 * Batch iterator implementation.
	 */
	private class CompositeBatchIterator implements BatchArrayIterator {
		private int peekArray = -1;

		@Override
		public boolean hasNext() {
			return CompositeIntArray.this.chunks.size() > peekArray + 1;
		}

		@Override
		public int[] nextBatch() {
			if (peekArray == CompositeIntArray.this.chunks.size()) {
				throw new NoSuchElementException("End of the array reached - max number of batches is " + CompositeIntArray.this.chunkPeek);
			}
			return CompositeIntArray.this.chunks.get(++peekArray);
		}

		@Override
		public void advanceIfNeeded(int target) {
			if (monotonic) {
				while (peekArray + 1 < CompositeIntArray.this.chunkPeek - 1 &&
					CompositeIntArray.this.chunks.get(peekArray + 1)[CHUNK_SIZE - 1] < target) {
					peekArray++;
				}
			}
		}

		@Override
		public int getPeek() {
			if (CompositeIntArray.this.chunks.size() == peekArray + 1) {
				return CompositeIntArray.this.chunkPeek + 1;
			} else {
				return CHUNK_SIZE;
			}
		}
	}
}