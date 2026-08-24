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
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
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
	/**
	 * The bitmap the non-transactional (delegate) branch writes into. NOT final only so that
	 * {@link #recordWarmUpSavepointTouch()} can swap a captured pre-image back in when a warm-up savepoint is rolled
	 * back; nothing else ever reassigns it after construction.
	 *
	 * Dropping `final` gives up the JMM's final-field freeze, so an instance handed to another thread WITHOUT a
	 * happens-before edge could in principle see this slot as `null`. That is not a regression in practice and is not
	 * defended against here: the reassignment only ever happens under a warm-up savepoint, and
	 * {@link io.evitadb.api.CatalogState#WARMING_UP} is contractually single-threaded, while an `ALIVE` catalog
	 * publishes its indexes across a version boundary that already carries the edge.
	 */
	private PersistentRoaringBitmap roaringBitmap;
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

	/**
	 * Every delegate branch of this class runs {@link #recordWarmUpSavepointTouch()} first, which captures a
	 * copy-on-write {@link PersistentRoaringBitmap#clone()} of the whole bitmap — `O(#containers)` of pointer work,
	 * not `O(size)` — and restores it by swapping the reference back and resetting the memoized cardinality.
	 *
	 * @return always `true` — see above
	 */
	@Override
	public boolean supportsWarmUpRollback() {
		return true;
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
				recordWarmUpSavepointTouch();
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
			recordWarmUpSavepointTouch();
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
							recordWarmUpSavepointTouch();
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
			recordWarmUpSavepointTouch();
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
							recordWarmUpSavepointTouch();
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
				recordWarmUpSavepointTouch();
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
			recordWarmUpSavepointTouch();
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
							recordWarmUpSavepointTouch();
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
			recordWarmUpSavepointTouch();
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
							recordWarmUpSavepointTouch();
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

	/**
	 * Captures the delegate bitmap for the warm-up savepoint bracketing the current root entity mutation, if one is
	 * open, so that a failed mutation rewinds this bitmap to exactly the members it held before the mutation began
	 * (see {@link WarmUpSavepoint}).
	 *
	 * The capture is made on the FIRST write-touch only, which is affordable here despite the bitmap being the large
	 * accumulated base structure: {@link PersistentRoaringBitmap#clone()} is copy-on-write on both of its levels, so it
	 * costs `O(#containers)` of pointer work rather than `O(#members)` of copying. Journaling membership per operation
	 * was the alternative and loses on the hottest path — {@link #addAll(Bitmap)} would have to allocate an inverse
	 * proportional to its argument on every call, whereas one clone covers every write in the savepoint.
	 *
	 * **Why the clone stays intact while the live bitmap keeps being mutated.** `clone()` raises the copy-on-write flag
	 * on every container of BOTH sides and marks BOTH `RoaringArray`s frozen. The next in-place write to the live
	 * bitmap therefore clones the container it targets before touching it (`copyIfShared`), and the next structural
	 * write defrosts the key/value arrays into a private copy — so no mutation applied after the capture can reach the
	 * captured pre-image. That is the same guarantee the transactional MVCC commit path relies on. The bitmap's own
	 * thread-safety caveat (a shared result must be safely published before another thread mutates it) does not apply:
	 * {@link io.evitadb.api.CatalogState#WARMING_UP} is contractually single-threaded.
	 *
	 * The restore swaps the captured reference back and re-invalidates {@link #memoizedCardinality} rather than
	 * restoring its pre-image. The sentinel is unconditionally safe — it costs one recomputation on the next
	 * {@link #size()} — while a restored value would silently outlive the swap if it were ever stale. Because the
	 * restore is a reference swap, a caller holding a bitmap handed out by {@link #getRoaringBitmap()} from before the
	 * rollback keeps the rewound-away instance; that is the same contract the array wrappers have, and warm-up readers
	 * fetch through the accessor per call.
	 *
	 * Must be called BEFORE the mutation. Outside a savepoint it costs one {@link ThreadLocal} read returning `null`.
	 */
	private void recordWarmUpSavepointTouch() {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null && savepoint.claimFirstTouch(this)) {
			final PersistentRoaringBitmap preImage = this.roaringBitmap.clone();
			savepoint.push(() -> {
				this.roaringBitmap = preImage;
				this.memoizedCardinality = -1;
			});
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
