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

package io.evitadb.dataType.array;

import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.ToIntBiFunction;

/**
 * Composite array is a way around fixed size arrays. It allows to have arrays with elastic size composed of handful of
 * smaller arrays of specified CHUNK_SIZE, that created as necessary. This class is similar to ArrayList but doesn't
 * reallocate entire array to the bigger one, just asks for another small chunk if the current array limit is exceeded.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@Slf4j
public class CompositeObjectArray<T> implements Iterable<T> {
	private static final int CHUNK_SIZE = 50;

	/**
	 * List of all chunks used in this instance.
	 */
	@Nonnull
	private final List<Object[]> chunks = new LinkedList<>();
	/**
	 * Generic class of the object that is used to create new arrays.
	 */
	@Nonnull
	private final Class<T> objectType;
	/**
	 * Contains TRUE if array contains only non-duplicated monotonically increasing numbers.
	 */
	private boolean monotonic;
	/**
	 * Peek of the array (i.e. last written number) in current chunk.
	 * I.e. with two chunks of size 512 and 850 numbers written it is 337.
	 */
	private int chunkPeek = -1;
	/**
	 * Current chunk that is being written to.
	 */
	@Nonnull
	private Object[] currentChunk;

	public CompositeObjectArray(@Nonnull Class<T> objectType) {
		this.currentChunk = new Object[CHUNK_SIZE];
		this.chunks.add(this.currentChunk);
		this.objectType = objectType;
		this.monotonic = Comparable.class.isAssignableFrom(objectType);
	}

	public CompositeObjectArray(@Nonnull Class<T> objectType, @Nonnull T... records) {
		this(objectType);
		addAll(records, 0, records.length);
	}

	public CompositeObjectArray(@Nonnull Class<T> objectType, boolean trackMonotonicity) {
		this.currentChunk = new Object[CHUNK_SIZE];
		this.chunks.add(this.currentChunk);
		this.objectType = objectType;
		Assert.isTrue(!trackMonotonicity || Comparable.class.isAssignableFrom(objectType), "The type must be Comparable in order to track monotonicity!");
		this.monotonic = Comparable.class.isAssignableFrom(objectType) && trackMonotonicity;
	}

	public CompositeObjectArray(@Nonnull Class<T> objectType, boolean trackMonotonicity, @Nonnull  T... records) {
		this(objectType, trackMonotonicity);
		addAll(records, 0, records.length);
	}

	/**
	 * Converts any Object iterator to an array.
	 */
	public static <T extends Comparable<T>> T[] toArray(@Nonnull Class<T> objectType, @Nonnull Iterator<T> iterator) {
		return toCompositeArray(objectType, iterator).toArray();
	}

	/**
	 * Converts any Object iterator to an elastic {@link CompositeObjectArray}.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <T extends Comparable<T>> CompositeObjectArray<T> toCompositeArray(@Nonnull Class objectType, @Nonnull Iterator<T> iterator) {
		final CompositeObjectArray<T> result = new CompositeObjectArray<>(objectType);
		while (iterator.hasNext()) {
			result.add(iterator.next());
		}
		return result;
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
	@Nullable
	public T getLast() {
		if (isEmpty()) {
			return null;
		} else {
			//noinspection unchecked
			return (T) currentChunk[chunkPeek];
		}
	}

	/**
	 * Return number on the specific index of the array.
	 */
	@Nullable
	public T get(int index) {
		final int chunkIndex = index / CHUNK_SIZE;
		final int indexInChunk = index % CHUNK_SIZE;
		Assert.isTrue(chunkIndex < chunks.size(), "Chunk index " + chunkIndex + " exceeds chunks size (" + chunks.size() + ").");
		//noinspection unchecked
		return (T) chunks.get(chunkIndex)[indexInChunk];
	}

	/**
	 * Returns true if the specified record is already part of the array.
	 */
	public boolean contains(@Nonnull T recordId) {
		for (Object[] chunk : chunks) {
			if (monotonic) {
				// use fast binary search if array contains only monotonic record ids
				if (Arrays.binarySearch(chunk, recordId) >= 0) {
					return true;
				}
			} else {
				// else array must be full scanned
				for (final Object comparable : chunk) {
					@SuppressWarnings("unchecked") final T theNumber = (T) comparable;
					if (Objects.equals(recordId, theNumber)) {
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
	public int indexOf(@Nonnull T recordId) {
		for (int i = 0; i < chunks.size(); i++) {
			final Object[] chunk = chunks.get(i);
			int index;
			if (monotonic) {
				if (i == chunks.size() - 1) {
					// use fast binary search if array contains only monotonic record ids
					index = chunkPeek >= 0 ? Arrays.binarySearch(chunk, 0, chunkPeek + 1, recordId) : -1;
				} else {
					// use fast binary search if array contains only monotonic record ids
					index = Arrays.binarySearch(chunk, recordId);
				}
			} else {
				// else array must be full scanned
				index = -1 * (CHUNK_SIZE + 1);
				for (int j = 0; j < chunk.length; j++) {
					@SuppressWarnings("unchecked") final T theNumber = (T) chunk[j];
					if (Objects.equals(theNumber, recordId)) {
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
	 * Returns index of the recordId in the array using the provided comparator. This function can be used only if
	 * the array is monotonically increasing.
	 */
	public <U> int indexOf(@Nonnull U recordId, @Nonnull ToIntBiFunction<T, U> comparator) {
		Assert.isPremiseValid(monotonic, "The array must be monotonically increasing to use this method.");
		for (int i = 0; i < chunks.size(); i++) {
			final Object[] chunk = chunks.get(i);
			int index;
			if (i == chunks.size() - 1) {
				// use fast binary search if array contains only monotonic record ids
				// we can safely cast to Comparable because we know that the array is monotonic
				//noinspection unchecked
				index = chunkPeek >= 0 ?
					ArrayUtils.binarySearch(chunk, recordId, 0, chunkPeek + 1, (o, u) -> comparator.applyAsInt((T) o, u))
					: -1;
			} else {
				// use fast binary search if array contains only monotonic record ids
				// we can safely cast to Comparable because we know that the array is monotonic
				//noinspection unchecked
				index = ArrayUtils.binarySearch(chunk, recordId, (o, u) -> comparator.applyAsInt((T) o, u));
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
	public void set(@Nonnull T recordId, int index) {
		Assert.isTrue(index < getSize(), "Index " + index + " out of bounds (" + getSize() + ")!");
		chunks.get(index / CHUNK_SIZE)[index % CHUNK_SIZE] = recordId;
	}

	/**
	 * Appends another record at the end of the array.
	 */
	public void add(@Nonnull T record) {
		// keep eye on monotonic row
		//noinspection unchecked
		if (monotonic && chunkPeek != -1 && ((Comparable<T>) record).compareTo((T) currentChunk[chunkPeek]) <= 0) {
			monotonic = false;
		}

		// if last chunk was depleted obtain another one
		if (++chunkPeek == CHUNK_SIZE) {
			chunkPeek = 0;
			currentChunk = new Object[CHUNK_SIZE];
			chunks.add(currentChunk);
		}

		currentChunk[chunkPeek] = record;
	}

	/**
	 * Appends all objects from passed array.
	 */
	public void addAll(@Nonnull T[] objects) {
		addAll(objects, 0, objects.length);
	}

	/**
	 * Appends a bunch of objects starting at index `srcPosition` of `objects` array and copying `length` objects from it.
	 */
	public void addAll(@Nonnull T[] objects, int srcPosition, int length) {

		if (objects.length == 0) {
			return;
		}

		if (objects.length < srcPosition + 1) {
			throw new ArrayIndexOutOfBoundsException();
		}

		// reset monotonic flag if added objects violate monotonic row
		if (monotonic) {
			//noinspection unchecked
			if (chunkPeek != -1 && ((Comparable<T>) currentChunk[chunkPeek]).compareTo(objects[srcPosition]) >= 0) {
				monotonic = false;
			} else {
				T lastRecord = objects[srcPosition];
				for (int i = srcPosition + 1; i < length; i++) {
					//noinspection unchecked
					if (((Comparable<T>) lastRecord).compareTo(objects[i]) >= 0) {
						monotonic = false;
						break;
					}
					lastRecord = objects[i];
				}
			}
		}

		int restLength = length;
		int currentSrcPos = srcPosition;

		// copy array contents when there is some objects in it
		while (restLength > 0) {
			final int copyPosition;

			// if the current chunk is depleted borrow another one
			if (chunkPeek + 1 == CHUNK_SIZE) {
				chunkPeek = -1;
				currentChunk = new Object[CHUNK_SIZE];
				chunks.add(currentChunk);
			}
			copyPosition = chunkPeek + 1;

			final int availableSizeInChunk = CHUNK_SIZE - copyPosition;
			final int copyLength = Math.min(availableSizeInChunk, restLength);

			System.arraycopy(objects, currentSrcPos, currentChunk, copyPosition, copyLength);

			chunkPeek += copyLength;
			currentSrcPos += copyLength;
			restLength -= copyLength;
		}
	}

	/**
	 * Creates new object array that spans all chunks borrowed so far with all numbers in it.
	 */
	@Nonnull
	public T[] toArray() {
		final int size = getSize();
		@SuppressWarnings("unchecked") final T[] result = (T[]) Array.newInstance(objectType, size);
		final Iterator<Object[]> it = chunks.iterator();
		int copied = 0;
		while (copied < size) {
			final Object[] chunk = it.next();
			final int restCount = size - copied;
			final int lengthToCopy = Math.min(restCount, chunk.length);
			//noinspection SuspiciousSystemArraycopy
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
	@Override
	@Nonnull
	public Iterator<T> iterator() {
		return new CompositeArrayIterator<>();
	}

	/**
	 * Iterator implementation.
	 */
	private class CompositeArrayIterator<S> implements Iterator<S> {
		private final Iterator<Object[]> chunkIterator;
		private final int size;
		private int chunkIndex;
		private int index;
		private S[] currentChunk;

		CompositeArrayIterator() {
			this.index = -1;
			this.chunkIndex = CHUNK_SIZE;
			this.currentChunk = null;
			this.size = CompositeObjectArray.this.getSize();
			this.chunkIterator = CompositeObjectArray.this.chunks.iterator();
		}

		@Override
		public boolean hasNext() {
			return size > index + 1;
		}

		@Override
		public S next() {
			if (this.index == size) {
				throw new NoSuchElementException("End of the array reached - max number of elements is " + CompositeObjectArray.this.getSize());
			}
			if (this.chunkIndex + 1 >= CHUNK_SIZE) {
				this.chunkIndex = -1;
				//noinspection unchecked
				this.currentChunk = (S[]) chunkIterator.next();
			}
			this.index++;
			return this.currentChunk[++chunkIndex];
		}
	}
}
