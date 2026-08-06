/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import lombok.Getter;
import io.evitadb.roaringbitmap.PeekableIntIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.PrimitiveIterator.OfInt;

import static io.evitadb.core.transaction.Transaction.getTransactionalMemoryLayerForWriteIfExists;
import static io.evitadb.core.transaction.Transaction.getTransactionalMemoryLayerIfExists;

/**
 * This class envelops simple primitive int bitmap and makes it transactional. This means, that the bitmap can be
 * updated by multiple writers and also multiple readers can read from its original array without spotting the changes
 * made in transactional access. Each transaction is bound to the same thread and different threads don't see changes
 * in other threads.
 *
 * If no transaction is opened, changes are applied directly to the delegate bitmap. In such case the class is not
 * thread safe for multiple writers!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
public class TransactionalBitmap
	implements RoaringBitmapBackedBitmap,
	TransactionalLayerProducer<BitmapChanges, Bitmap>,
	Serializable {
	@Serial private static final long serialVersionUID = -6212206620911046989L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	private final PersistentRoaringBitmap roaringBitmap;
	private volatile int memoizedCardinality;

	/**
	 * Creates a new empty transactional bitmap.
	 */
	public TransactionalBitmap() {
		this.roaringBitmap = new PersistentRoaringBitmap();
		this.memoizedCardinality = 0;
	}

	/**
	 * Creates a transactional bitmap pre-populated with the given record ids.
	 *
	 * @param recordIds initial record ids to add
	 */
	public TransactionalBitmap(@Nonnull int... recordIds) {
		this.roaringBitmap = new PersistentRoaringBitmap();
		this.roaringBitmap.add(recordIds);
		this.memoizedCardinality = this.roaringBitmap.getCardinality();
	}

	/**
	 * Creates a transactional bitmap copied from the given bitmap.
	 *
	 * @param bitmap source bitmap to copy
	 */
	public TransactionalBitmap(@Nonnull Bitmap bitmap) {
		final PersistentRoaringBitmap theRoaringBitmap;
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
	public RoaringBitmapBackedBitmap createCopyWithMergedTransactionalMemory(
		@Nullable BitmapChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
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
	public PersistentRoaringBitmap getRoaringBitmap() {
		final BitmapChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.roaringBitmap;
		} else {
			return layer.getMergedBitmap();
		}
	}

	@Override
	public boolean add(int recordId) {
		// avoid creating a transactional layer for a no-op (record already present)
		if (this.contains(recordId)) {
			return false;
		} else {
			final BitmapChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
			if (layer == null) {
				this.roaringBitmap.add(recordId);
				this.memoizedCardinality = -1;
				return true;
			} else {
				return layer.addRecordId(recordId);
			}
		}
	}

	@Override
	public void addAll(int... recordId) {
		if (!Transaction.isTransactionAvailable()) {
			this.roaringBitmap.add(recordId);
			this.memoizedCardinality = -1;
		} else {
			BitmapChanges layer = getTransactionalMemoryLayerForWriteIfExists(this);
			if (layer != null) {
				for (int recId : recordId) {
					layer.addRecordId(recId);
				}
			} else {
				// defer layer creation until first actual change
				for (int recId : recordId) {
					if (!this.roaringBitmap.contains(recId)) {
						layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
						if (layer == null) {
							this.roaringBitmap.add(recordId);
							this.memoizedCardinality = -1;
						} else {
							for (int r : recordId) {
								layer.addRecordId(r);
							}
						}
						return;
					}
				}
			}
		}
	}

	@Override
	public void addAll(@Nonnull Bitmap recordIds) {
		if (!Transaction.isTransactionAvailable()) {
			this.roaringBitmap.add(recordIds.getArray());
			this.memoizedCardinality = -1;
		} else {
			BitmapChanges layer = getTransactionalMemoryLayerForWriteIfExists(this);
			final OfInt it = recordIds.iterator();
			if (layer != null) {
				while (it.hasNext()) {
					layer.addRecordId(it.nextInt());
				}
			} else {
				// defer layer creation until first actual change
				while (it.hasNext()) {
					final int recordId = it.nextInt();
					if (!this.roaringBitmap.contains(recordId)) {
						layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
						if (layer == null) {
							this.roaringBitmap.add(recordId);
							while (it.hasNext()) {
								this.roaringBitmap.add(it.nextInt());
							}
							this.memoizedCardinality = -1;
						} else {
							layer.addRecordId(recordId);
							while (it.hasNext()) {
								layer.addRecordId(it.nextInt());
							}
						}
						return;
					}
				}
			}
		}
	}

	@Override
	public boolean remove(int recordId) {
		// no layer yet — avoid creating one for a no-op (record already absent)
		if (!this.contains(recordId)) {
			return false;
		} else {
			final BitmapChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
			if (layer == null) {
				this.roaringBitmap.remove(recordId);
				this.memoizedCardinality = -1;
				return true;
			} else {
				return layer.removeRecordId(recordId);
			}
		}
	}

	@Override
	public void removeAll(int... recordId) {
		if (!Transaction.isTransactionAvailable()) {
			for (int recId : recordId) {
				this.roaringBitmap.remove(recId);
			}
			this.memoizedCardinality = -1;
		} else {
			BitmapChanges layer = getTransactionalMemoryLayerForWriteIfExists(this);
			if (layer != null) {
				for (int recId : recordId) {
					layer.removeRecordId(recId);
				}
			} else {
				// defer layer creation until first actual change
				for (int recId : recordId) {
					if (this.roaringBitmap.contains(recId)) {
						layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
						if (layer == null) {
							for (int r : recordId) {
								this.roaringBitmap.remove(r);
							}
							this.memoizedCardinality = -1;
						} else {
							for (int r : recordId) {
								layer.removeRecordId(r);
							}
						}
						return;
					}
				}
			}
		}
	}

	@Override
	public void removeAll(@Nonnull Bitmap recordIds) {
		if (!Transaction.isTransactionAvailable()) {
			if (recordIds instanceof RoaringBitmapBackedBitmap) {
				this.roaringBitmap.andNot(((RoaringBitmapBackedBitmap) recordIds).getRoaringBitmap());
			} else {
				final OfInt it = recordIds.iterator();
				while (it.hasNext()) {
					this.roaringBitmap.remove(it.nextInt());
				}
			}
			this.memoizedCardinality = -1;
		} else {
			BitmapChanges layer = getTransactionalMemoryLayerForWriteIfExists(this);
			final OfInt it = recordIds.iterator();
			if (layer != null) {
				while (it.hasNext()) {
					layer.removeRecordId(it.nextInt());
				}
			} else {
				// defer layer creation until first actual change
				while (it.hasNext()) {
					final int recordId = it.nextInt();
					if (this.roaringBitmap.contains(recordId)) {
						layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
						if (layer == null) {
							this.roaringBitmap.remove(recordId);
							while (it.hasNext()) {
								this.roaringBitmap.remove(it.nextInt());
							}
							this.memoizedCardinality = -1;
						} else {
							layer.removeRecordId(recordId);
							while (it.hasNext()) {
								layer.removeRecordId(it.nextInt());
							}
						}
						return;
					}
				}
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
		final PersistentRoaringBitmap theBitmap = getTheCurrentBitmap();
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
		final PersistentRoaringBitmap theBitmap = getTheCurrentBitmap();
		return theBitmap.first();
	}

	@Override
	public int getLast() {
		final PersistentRoaringBitmap theBitmap = getTheCurrentBitmap();
		return theBitmap.last();
	}

	@Override
	public int[] getArray() {
		return RoaringBitmapBackedBitmap.toSignedArray(getTheCurrentBitmap());
	}

	/**
	 * This wrapper's own object — the `roaringBitmap` reference, the `id` and the memoized cardinality —
	 * plus the **committed** roaring bitmap.
	 *
	 * Reads the field directly rather than going through {@link #getRoaringBitmap()}, and that is the whole
	 * substance of this method. Inside a transaction the accessor returns a merged bitmap computed from this
	 * bitmap and the open {@link BitmapChanges} layer; that merge is owned by the transaction, lives as long
	 * as the transaction does, and is charged to nobody here. Reporting it would make an index's footprint
	 * jump for the duration of a write and then fall back, which describes the writer rather than the index.
	 */
	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		return layout.sizeOfObject(layout.referenceSize() + Long.BYTES + Integer.BYTES)
			+ this.roaringBitmap.getHeapSizeInBytes(ROARING_HEAP_LAYOUT);
	}

	@Nonnull
	@Override
	public OfInt iterator() {
		final PersistentRoaringBitmap theBitmap = getTheCurrentBitmap();
		return new RoaringBitmapBackedBitmap.RoaringIntIteratorAdapter(theBitmap.getIntIterator());
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

	/**
	 * Returns the greatest record id at or below `fromValue` in signed order, or
	 * {@link RoaringBitmapBackedBitmap#NO_PREVIOUS_VALUE} when none exists. Answered from the transactional diff layer
	 * when one exists (mirroring {@link #isEmpty()} / {@link #size()}) rather than through the merged bitmap, so a
	 * caller on the write path does not pay a whole-bucket merge per query.
	 *
	 * @param fromValue inclusive upper bound in signed order
	 * @return the greatest signed value at or below `fromValue`, or {@link RoaringBitmapBackedBitmap#NO_PREVIOUS_VALUE}
	 */
	public long signedPreviousValue(int fromValue) {
		final BitmapChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return RoaringBitmapBackedBitmap.signedPreviousValue(this.roaringBitmap, fromValue);
		} else {
			return layer.signedPreviousValue(fromValue);
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
		return this.roaringBitmap.hashCode();
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final TransactionalBitmap that = (TransactionalBitmap) o;
		return this.roaringBitmap.equals(that.roaringBitmap);
	}

	@Override
	public String toString() {
		final PersistentRoaringBitmap theBitmap = getTheCurrentBitmap();
		return Arrays.toString(theBitmap.toArray());
	}

	@Nonnull
	private PersistentRoaringBitmap getTheCurrentBitmap() {
		final BitmapChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.roaringBitmap;
		} else {
			return layer.getMergedBitmap();
		}
	}
}
