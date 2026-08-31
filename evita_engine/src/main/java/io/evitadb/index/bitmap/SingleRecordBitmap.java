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

package io.evitadb.index.bitmap;

import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;

/**
 * Immutable {@link Bitmap} holding exactly one record id, stored as a bare `int`. It is the leanest possible bitmap
 * representation - a single object with one `int` field and no backing array, {@link io.evitadb.roaringbitmap.PersistentRoaringBitmap},
 * or {@link io.evitadb.dataType.array.CompositeIntArray}. It exists for the single-record hot path (notably the
 * {@link io.evitadb.index.invertedIndex.ValueToRecordPrimitive} bucket view) where allocating a heavier
 * {@link ArrayBitmap} or {@link BaseBitmap} for one id is pure waste.
 *
 * Like {@link EmptyBitmap} it is read-only: all mutation methods throw {@link UnsupportedOperationException}. Any change
 * is the caller's responsibility (it must build a different bitmap instead).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
public class SingleRecordBitmap implements Bitmap {
	@Serial private static final long serialVersionUID = 6927897965325863961L;
	private static final String ERROR_READ_ONLY = "Single-record bitmap is read only.";
	/**
	 * The lone record id held by this bitmap.
	 */
	private final int recordId;

	public SingleRecordBitmap(int recordId) {
		this.recordId = recordId;
	}

	/**
	 * Returns the lone record id this bitmap holds.
	 *
	 * Exists so a consumer folding many of these together can read the id without {@link #getArray()}, which
	 * allocates a one-element array per call - the very cost such a fold is usually trying to avoid.
	 *
	 * @return the record id
	 */
	public int getRecordId() {
		return this.recordId;
	}

	@Override
	public boolean isEmpty() {
		return false;
	}

	@Override
	public int size() {
		return 1;
	}

	@Override
	public boolean add(int recordId) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public void addAll(int... recordId) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public void addAll(@Nonnull Bitmap recordIds) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public boolean remove(int recordId) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public void removeAll(int... recordId) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public void removeAll(@Nonnull Bitmap recordIds) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public boolean contains(int recordId) {
		return recordId == this.recordId;
	}

	@Override
	public int indexOf(int recordId) {
		// mirrors Arrays.binarySearch: 0 when found, otherwise -(insertion point) - 1
		if (recordId == this.recordId) {
			return 0;
		}
		return recordId < this.recordId ? -1 : -2;
	}

	@Override
	public int get(int index) {
		if (index != 0) {
			throw new IndexOutOfBoundsException(Integer.toString(index));
		}
		return this.recordId;
	}

	@Override
	public int[] getRange(int start, int end) {
		if (start < 0 || end > 1 || start > end) {
			throw new IndexOutOfBoundsException("start: " + start + ", end: " + end);
		}
		return start == end ? new int[0] : new int[]{this.recordId};
	}

	@Override
	public int getFirst() {
		return this.recordId;
	}

	@Override
	public int getLast() {
		return this.recordId;
	}

	@Override
	public int[] getArray() {
		return new int[]{this.recordId};
	}

	/**
	 * Just this object: one `int` field and no backing structure at all. That is the whole reason this
	 * implementation exists — a roaring-backed bitmap holding the same single record costs an order of
	 * magnitude more, almost all of it fixed overhead.
	 */
	@Override
	public long getHeapSizeInBytes() {
		return VMLayout.current().sizeOfObject(Integer.BYTES);
	}

	@Nonnull
	@Override
	public OfInt iterator() {
		return new SingleIntIterator(this.recordId);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		// type-sensitive, consistent with the other Bitmap implementations (BaseBitmap / ArrayBitmap)
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		return this.recordId == ((SingleRecordBitmap) o).recordId;
	}

	@Override
	public int hashCode() {
		return Integer.hashCode(this.recordId);
	}

	@Override
	public String toString() {
		return "[" + this.recordId + "]";
	}

	/**
	 * Minimal single-value {@link OfInt} that yields the lone record id exactly once and avoids the one-element array a
	 * {@link io.evitadb.dataType.iterator.ConstantIntIterator} would allocate.
	 */
	private static final class SingleIntIterator implements OfInt {
		private final int value;
		private boolean consumed;

		SingleIntIterator(int value) {
			this.value = value;
		}

		@Override
		public int nextInt() {
			if (this.consumed) {
				throw new NoSuchElementException("Single-record bitmap iterator exhausted!");
			}
			this.consumed = true;
			return this.value;
		}

		@Override
		public boolean hasNext() {
			return !this.consumed;
		}
	}
}
