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

import org.roaringbitmap.PeekableIntIterator;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PrimitiveIterator.OfInt;
import java.util.stream.Collectors;

/**
 * IntegerBitmap implementation that is backed by {@link RoaringBitmap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class BaseBitmap implements RoaringBitmapBackedBitmap {
	@Serial private static final long serialVersionUID = -8471705193727315151L;
	private final RoaringBitmap roaringBitmap;
	private int memoizedCardinality;

	public BaseBitmap() {
		this.roaringBitmap = new RoaringBitmap();
		this.memoizedCardinality = 0;
	}

	public BaseBitmap(@Nullable int... recordIds) {
		final RoaringBitmap theRoaringBitmap = new RoaringBitmap();
		theRoaringBitmap.add(recordIds);
		this.roaringBitmap = theRoaringBitmap;
		this.memoizedCardinality = theRoaringBitmap.getCardinality();
	}

	public BaseBitmap(@Nonnull Bitmap bitmap) {
		final RoaringBitmap theRoaringBitmap;
		if (bitmap instanceof RoaringBitmapBackedBitmap) {
			theRoaringBitmap = ((RoaringBitmapBackedBitmap) bitmap).getRoaringBitmap().clone();
		} else {
			theRoaringBitmap = RoaringBitmapBackedBitmap.fromArray(bitmap.getArray());
		}
		this.roaringBitmap = theRoaringBitmap;
		this.memoizedCardinality = bitmap.size();
	}

	public BaseBitmap(@Nonnull RoaringBitmap bitmap) {
		this.roaringBitmap = bitmap;
		this.memoizedCardinality = bitmap.getCardinality();
	}

	@Nonnull
	@Override
	public RoaringBitmap getRoaringBitmap() {
		return this.roaringBitmap;
	}

	@Override
	public boolean add(int recordId) {
		final boolean added = this.roaringBitmap.checkedAdd(recordId);
		this.memoizedCardinality = added ? -1 : this.memoizedCardinality;
		return added;
	}

	@Override
	public void addAll(int... recordId) {
		this.roaringBitmap.add(recordId);
		this.memoizedCardinality = -1;
	}

	@Override
	public void addAll(@Nonnull Bitmap recordIds) {
		this.roaringBitmap.add(recordIds.getArray());
		this.memoizedCardinality = -1;
	}

	@Override
	public boolean remove(int recordId) {
		final boolean removed = this.roaringBitmap.checkedRemove(recordId);
		this.memoizedCardinality = removed ? -1 : this.memoizedCardinality;
		return removed;
	}

	@Override
	public void removeAll(int... recordId) {
		for (int recId : recordId) {
			this.roaringBitmap.remove(recId);
		}
		this.memoizedCardinality = -1;
	}

	@Override
	public void removeAll(@Nonnull Bitmap recordIds) {
		if (recordIds instanceof RoaringBitmapBackedBitmap) {
			this.roaringBitmap.andNot(((RoaringBitmapBackedBitmap) recordIds).getRoaringBitmap());
		} else {
			for (Integer recordId : recordIds) {
				this.roaringBitmap.remove(recordId);
			}
		}
		this.memoizedCardinality = -1;
	}

	@Override
	public boolean contains(int recordId) {
		return this.roaringBitmap.contains(recordId);
	}

	@Override
	public int indexOf(int recordId) {
		return RoaringBitmapBackedBitmap.indexOf(this.roaringBitmap, recordId);
	}

	@Override
	public int get(int index) {
		try {
			return this.roaringBitmap.select(index);
		} catch (IllegalArgumentException ex) {
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
		}
	}

	@Override
	public int[] getRange(int start, int end) {
		try {
			final int length = end - start;
			final int[] result = new int[length];
			if (result.length == 0) {
				return result;
			}
			result[0] = this.roaringBitmap.select(start);
			final PeekableIntIterator it = this.roaringBitmap.getIntIterator();
			it.advanceIfNeeded(result[0]);
			it.next();
			for (int i = 1; i < length; i++) {
				if (it.hasNext()) {
					result[i] = it.next();
				} else {
					throw new IndexOutOfBoundsException("Index: " + (start + i) + ", Size: " + size());
				}
			}
			return result;
		} catch (IllegalArgumentException ex) {
			throw new IndexOutOfBoundsException("Index: " + start + ", Size: " + size());
		}
	}

	@Override
	public int getFirst() {
		try {
			return this.roaringBitmap.first();
		} catch (NoSuchElementException ex) {
			throw new IndexOutOfBoundsException("IntegerBitmap is empty!");
		}
	}

	@Override
	public int getLast() {
		try {
			return this.roaringBitmap.last();
		} catch (NoSuchElementException ex) {
			throw new IndexOutOfBoundsException("IntegerBitmap is empty!");
		}
	}

	@Override
	public int[] getArray() {
		return this.roaringBitmap.toArray();
	}

	@Nonnull
	@Override
	public OfInt iterator() {
		return this.roaringBitmap.stream().iterator();
	}

	@Override
	public boolean isEmpty() {
		return this.roaringBitmap.isEmpty();
	}

	@Override
	public int size() {
		if (this.memoizedCardinality == -1) {
			this.memoizedCardinality = this.roaringBitmap.getCardinality();
		}
		return this.memoizedCardinality;
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.roaringBitmap);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		BaseBitmap that = (BaseBitmap) o;
		return this.roaringBitmap.equals(that.roaringBitmap);
	}

	@Override
	public String toString() {
		// we need to unify the output with ArrayBitmap and other implementations
		return "[" + this.roaringBitmap.stream().mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "]";
	}
}
