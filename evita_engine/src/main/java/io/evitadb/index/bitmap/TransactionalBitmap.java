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

import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import lombok.Getter;
import org.roaringbitmap.PeekableIntIterator;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.PrimitiveIterator.OfInt;

import static io.evitadb.core.Transaction.getTransactionalMemoryLayerIfExists;

/**
 * This class envelopes simple primitive int bitmap and makes it transactional. This means, that the bitmap can be updated
 * by multiple writers and also multiple readers can read from its original array without spotting the changes made
 * in transactional access. Each transaction is bound to the same thread and different threads doesn't see changes in
 * another threads.
 *
 * If no transaction is opened, changes are applied directly to the delegate array. In such case the class is not thread
 * safe for multiple writers!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
public class TransactionalBitmap implements RoaringBitmapBackedBitmap, TransactionalLayerProducer<BitmapChanges, Bitmap>, Serializable {
	@Serial private static final long serialVersionUID = -6212206620911046989L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	private final RoaringBitmap roaringBitmap;
	private int memoizedCardinality;

	public TransactionalBitmap() {
		this.roaringBitmap = new RoaringBitmap();
		this.memoizedCardinality = 0;
	}

	public TransactionalBitmap(int... recordIds) {
		this.roaringBitmap = new RoaringBitmap();
		this.roaringBitmap.add(recordIds);
		this.memoizedCardinality = this.roaringBitmap.getCardinality();
	}

	public TransactionalBitmap(@Nonnull Bitmap bitmap) {
		final RoaringBitmap theRoaringBitmap;
		if (bitmap instanceof RoaringBitmapBackedBitmap) {
			theRoaringBitmap = ((RoaringBitmapBackedBitmap) bitmap).getRoaringBitmap().clone();
		} else {
			theRoaringBitmap = RoaringBitmapBackedBitmap.fromArray(bitmap.getArray());
		}
		this.roaringBitmap = theRoaringBitmap;
		this.memoizedCardinality = bitmap.size();
	}

	@Override
	public BitmapChanges createLayer() {
		return new BitmapChanges(this.roaringBitmap);
	}

	@Nonnull
	@Override
	public RoaringBitmapBackedBitmap createCopyWithMergedTransactionalMemory(@Nullable BitmapChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		if (layer == null) {
			return this;
		} else {
			return new BaseBitmap(layer.getMergedBitmap());
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}

	@Nonnull
	@Override
	public RoaringBitmap getRoaringBitmap() {
		final BitmapChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.roaringBitmap;
		} else {
			return layer.getMergedBitmap();
		}
	}

	@Override
	public boolean add(int recordId) {
		final BitmapChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			final boolean added = this.roaringBitmap.checkedAdd(recordId);
			this.memoizedCardinality = added ? -1 : this.memoizedCardinality;
			return added;
		} else {
			return layer.addRecordId(recordId);
		}
	}

	@Override
	public void addAll(int... recordId) {
		final BitmapChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.roaringBitmap.add(recordId);
			this.memoizedCardinality = -1;
		} else {
			for (int recId : recordId) {
				layer.addRecordId(recId);
			}
		}
	}

	@Override
	public void addAll(@Nonnull Bitmap recordIds) {
		final BitmapChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.roaringBitmap.add(recordIds.getArray());
			this.memoizedCardinality = -1;
		} else {
			for (Integer recordId : recordIds) {
				layer.addRecordId(recordId);
			}
		}
	}

	@Override
	public boolean remove(int recordId) {
		final BitmapChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			final boolean removed = this.roaringBitmap.checkedRemove(recordId);
			this.memoizedCardinality = removed ? -1 : this.memoizedCardinality;
			return removed;
		} else {
			return layer.removeRecordId(recordId);
		}
	}

	@Override
	public void removeAll(int... recordId) {
		final BitmapChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			for (int recId : recordId) {
				this.roaringBitmap.remove(recId);
			}
			this.memoizedCardinality = -1;
		} else {
			for (int recId : recordId) {
				layer.removeRecordId(recId);
			}
		}
	}

	@Override
	public void removeAll(@Nonnull Bitmap recordIds) {
		final BitmapChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			if (recordIds instanceof RoaringBitmapBackedBitmap) {
				this.roaringBitmap.andNot(((RoaringBitmapBackedBitmap) recordIds).getRoaringBitmap());
			} else {
				for (Integer recordId : recordIds) {
					this.roaringBitmap.remove(recordId);
				}
			}
			this.memoizedCardinality = -1;
		} else {
			for (Integer recordId : recordIds) {
				layer.removeRecordId(recordId);
			}
		}
	}

	@Override
	public boolean contains(int recordId) {
		final BitmapChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.roaringBitmap.contains(recordId);
		} else {
			return layer.contains(recordId);
		}
	}

	@Override
	public int indexOf(int recordId) {
		final BitmapChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return RoaringBitmapBackedBitmap.indexOf(this.roaringBitmap, recordId);
		} else {
			return RoaringBitmapBackedBitmap.indexOf(layer.getMergedBitmap(), recordId);
		}
	}

	@Override
	public int get(int index) {
		final BitmapChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.roaringBitmap.select(index);
		} else {
			return layer.getMergedBitmap().select(index);
		}
	}

	@Override
	public int[] getRange(int start, int end) {
		final RoaringBitmap theBitmap = getTheCurrentBitmap();
		try {
			final int length = end - start;
			final int[] result = new int[length];
			if (result.length == 0) {
				return result;
			}
			result[0] = theBitmap.select(start);
			final PeekableIntIterator it = theBitmap.getIntIterator();
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
		final RoaringBitmap theBitmap = getTheCurrentBitmap();
		return theBitmap.first();
	}

	@Override
	public int getLast() {
		final RoaringBitmap theBitmap = getTheCurrentBitmap();
		return theBitmap.last();
	}

	@Override
	public int[] getArray() {
		final RoaringBitmap theBitmap = getTheCurrentBitmap();
		return theBitmap.toArray();
	}

	@Nonnull
	@Override
	public OfInt iterator() {
		final RoaringBitmap theBitmap = getTheCurrentBitmap();
		return theBitmap.stream().iterator();
	}

	@Override
	public boolean isEmpty() {
		final BitmapChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.roaringBitmap.isEmpty();
		} else {
			return layer.isEmpty();
		}
	}

	@Override
	public int size() {
		final BitmapChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			if (this.memoizedCardinality == -1) {
				this.memoizedCardinality = this.roaringBitmap.getCardinality();
			}
			return this.memoizedCardinality;
		} else {
			return layer.getMergedLength();
		}
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.roaringBitmap);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		TransactionalBitmap that = (TransactionalBitmap) o;
		return this.roaringBitmap.equals(that.roaringBitmap);
	}

	@Override
	public String toString() {
		final RoaringBitmap theBitmap = getTheCurrentBitmap();
		return Arrays.toString(theBitmap.toArray());
	}

	private RoaringBitmap getTheCurrentBitmap() {
		final BitmapChanges layer = getTransactionalMemoryLayerIfExists(this);
		final RoaringBitmap theBitmap;
		if (layer == null) {
			theBitmap = this.roaringBitmap;
		} else {
			theBitmap = layer.getMergedBitmap();
		}
		return theBitmap;
	}
}
