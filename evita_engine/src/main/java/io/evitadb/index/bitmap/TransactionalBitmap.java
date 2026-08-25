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
	 * Initial capacity of the delta buffer a bulk delegate-branch write fills while a warm-up savepoint is open (see
	 * {@link #appendChangedId(int[], int, int, int)}). It is capped by the bulk argument's own size, so a two-element
	 * bulk write allocates two slots rather than this many.
	 */
	private static final int INITIAL_CHANGED_ID_CAPACITY = 16;

	/**
	 * The bitmap the non-transactional (delegate) branch writes into.
	 *
	 * The field is `final` because warm-up savepoint capture is PER OPERATION: a delegate-branch write flips bits on
	 * THIS instance and journals the inverse of exactly the bits it flipped, so a rollback replays those inverses
	 * against the same instance rather than swapping a captured pre-image reference back in (see
	 * {@link #journalAdditions(WarmUpSavepoint, int[], int)}). Nothing reassigns the slot after construction, which
	 * keeps the JMM's final-field freeze: an instance published to another thread WITHOUT a happens-before edge can
	 * never observe it as `null`.
	 */
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

	/**
	 * Every delegate branch of this class journals, into the warm-up savepoint bracketing the current root entity
	 * mutation when one is open, an inverse restoring exactly the membership it is about to change: a single-bit write
	 * pushes one inverse for that bit, a bulk write pushes one inverse reverting the ids whose membership it actually
	 * changed. A write that changes nothing journals nothing.
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
				journalAdditionIfOpen(recordId);
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
			addAllToDelegate(recordId);
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
							addAllToDelegate(recordId);
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
			addAllToDelegate(recordIds);
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
							// the prefix already walked is present in the delegate, so re-offering the WHOLE argument
							// to the shared helper reaches the same state while keeping a single journalling shape
							addAllToDelegate(recordIds);
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
				journalRemovalIfOpen(recordId);
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
			removeAllFromDelegate(recordId);
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
							removeAllFromDelegate(recordId);
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
			removeAllFromDelegate(recordIds);
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
							// the prefix already walked is absent from the delegate, so re-offering the WHOLE argument
							// to the shared helper reaches the same state while keeping a single journalling shape
							removeAllFromDelegate(recordIds);
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
	 * Journals, into the warm-up savepoint bracketing the current root entity mutation when one is open, the inverse of
	 * a single-bit ADDITION about to be applied to the delegate bitmap, so that a failed mutation rewinds this bitmap
	 * to exactly the members it held before the mutation began (see {@link WarmUpSavepoint}).
	 *
	 * The caller must already have established that `recordId` is ABSENT — every call site sits behind the
	 * {@link #contains(int)} short-circuit that makes an `add` of a present id a no-op — so the bit's captured previous
	 * state is "absent" and the inverse that restores it is a plain removal. A write that changes nothing therefore
	 * journals nothing.
	 *
	 * **Why per operation rather than one whole-bitmap capture.** The bitmap used to capture a copy-on-write
	 * {@link PersistentRoaringBitmap#clone()} on its first write-touch, which looked `O(1)`-ish because the clone is
	 * pointer work proportional to containers. The cost was merely deferred: cloning freezes every container of BOTH
	 * sides, so the very next write to a shared container has to copy it out (up to a `long[1024]` bitmap container),
	 * and bulk ingest re-clones and re-defrosts per entity. Profiling the 972k-article reference corpus put
	 * `BitmapContainer.clone`'s `long[]` copies at 13.2 % of all allocation with the mechanism switched on. Capturing
	 * per operation is `O(changed)` and touches nothing the write was not touching anyway.
	 *
	 * **The inverse reads {@link #roaringBitmap} at replay time** rather than closing over the instance the write went
	 * to. Today the field is final, so the two are the same bitmap; the shape is kept deliberately anyway, because a
	 * future change that reintroduced a reference swap would make a captured-instance inverse restore members into a
	 * bitmap nobody reads, silently. It re-invalidates {@link #memoizedCardinality} rather than restoring a captured
	 * value — the sentinel costs one recomputation on the next {@link #size()}, whereas a restored value would have to
	 * be trusted to have been valid (see the accepted-residues section of the savepoint documentation).
	 *
	 * Must be called BEFORE the mutation. Outside a savepoint it costs one {@link ThreadLocal} read returning `null`.
	 *
	 * @param recordId the id being added, known to be absent from the delegate bitmap
	 */
	private void journalAdditionIfOpen(int recordId) {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null) {
			savepoint.push(() -> {
				this.roaringBitmap.remove(recordId);
				this.memoizedCardinality = -1;
			});
		}
	}

	/**
	 * Journals the inverse of a single-bit REMOVAL about to be applied to the delegate bitmap — the mirror of
	 * {@link #journalAdditionIfOpen(int)}, whose JavaDoc carries the reasoning for the granularity.
	 *
	 * The caller must already have established that `recordId` is PRESENT, so the bit's captured previous state is
	 * "present" and the inverse that restores it is a plain addition.
	 *
	 * Must be called BEFORE the mutation. Outside a savepoint it costs one {@link ThreadLocal} read returning `null`.
	 *
	 * @param recordId the id being removed, known to be present in the delegate bitmap
	 */
	private void journalRemovalIfOpen(int recordId) {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null) {
			savepoint.push(() -> {
				this.roaringBitmap.add(recordId);
				this.memoizedCardinality = -1;
			});
		}
	}

	/**
	 * Adds every id of `recordIds` to the delegate bitmap, journalling into an open warm-up savepoint the ids whose
	 * membership the write actually CHANGED (its delta).
	 *
	 * With no savepoint open — the production default, and every bulk load with the atomicity flag off — this is
	 * byte-for-byte the write the method always performed, a single bulk `add` that reaches roaring's whole-container
	 * fast paths. That path must never pay for a mechanism that is switched off, so the per-id walk the delta needs
	 * lives strictly inside the savepoint-open branch, which pays it in exchange for not cloning (and subsequently
	 * defrosting) the whole accumulated bitmap.
	 *
	 * Duplicate ids in the argument need no special handling: the second occurrence finds the bit already present and
	 * does not enter the delta. An empty delta pushes nothing.
	 *
	 * @param recordIds the ids to add
	 */
	private void addAllToDelegate(@Nonnull int[] recordIds) {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint == null) {
			this.roaringBitmap.add(recordIds);
			this.memoizedCardinality = -1;
		} else {
			int[] addedIds = null;
			int addedCount = 0;
			try {
				for (final int recordId : recordIds) {
					if (!this.roaringBitmap.contains(recordId)) {
						// the delta slot is reserved BEFORE the bit is flipped, so no failure can leave a flip
						// outside the delta
						addedIds = appendChangedId(addedIds, addedCount, recordId, recordIds.length);
						addedCount++;
						this.roaringBitmap.add(recordId);
					}
				}
			} finally {
				// pushed from a FINALLY, so a walk that dies part-way still journals the flips it managed to make
				if (addedIds != null) {
					journalAdditions(savepoint, addedIds, addedCount);
				}
				this.memoizedCardinality = -1;
			}
		}
	}

	/**
	 * Adds every id of `recordIds` to the delegate bitmap, journalling into an open warm-up savepoint the ids whose
	 * membership the write actually CHANGED — the {@link Bitmap} counterpart of
	 * {@link #addAllToDelegate(int[])}, whose JavaDoc carries the reasoning.
	 *
	 * The savepoint-open branch iterates the argument rather than materializing it through {@link Bitmap#getArray()},
	 * so it does not even pay the whole-argument array the no-savepoint fast path allocates.
	 *
	 * @param recordIds the ids to add
	 */
	private void addAllToDelegate(@Nonnull Bitmap recordIds) {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint == null) {
			this.roaringBitmap.add(recordIds.getArray());
			this.memoizedCardinality = -1;
		} else {
			final int maxAddedCount = recordIds.size();
			int[] addedIds = null;
			int addedCount = 0;
			try {
				final OfInt it = recordIds.iterator();
				while (it.hasNext()) {
					final int recordId = it.nextInt();
					if (!this.roaringBitmap.contains(recordId)) {
						// the delta slot is reserved BEFORE the bit is flipped, so no failure can leave a flip
						// outside the delta
						addedIds = appendChangedId(addedIds, addedCount, recordId, maxAddedCount);
						addedCount++;
						this.roaringBitmap.add(recordId);
					}
				}
			} finally {
				// pushed from a FINALLY, so a walk that dies part-way still journals the flips it managed to make
				if (addedIds != null) {
					journalAdditions(savepoint, addedIds, addedCount);
				}
				this.memoizedCardinality = -1;
			}
		}
	}

	/**
	 * Removes every id of `recordIds` from the delegate bitmap, journalling into an open warm-up savepoint the ids
	 * whose membership the write actually CHANGED — the removal mirror of {@link #addAllToDelegate(int[])}.
	 *
	 * @param recordIds the ids to remove
	 */
	private void removeAllFromDelegate(@Nonnull int[] recordIds) {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint == null) {
			for (final int recordId : recordIds) {
				this.roaringBitmap.remove(recordId);
			}
			this.memoizedCardinality = -1;
		} else {
			int[] removedIds = null;
			int removedCount = 0;
			try {
				for (final int recordId : recordIds) {
					if (this.roaringBitmap.contains(recordId)) {
						// the delta slot is reserved BEFORE the bit is flipped, so no failure can leave a flip
						// outside the delta
						removedIds = appendChangedId(removedIds, removedCount, recordId, recordIds.length);
						removedCount++;
						this.roaringBitmap.remove(recordId);
					}
				}
			} finally {
				// pushed from a FINALLY, so a walk that dies part-way still journals the flips it managed to make
				if (removedIds != null) {
					journalRemovals(savepoint, removedIds, removedCount);
				}
				this.memoizedCardinality = -1;
			}
		}
	}

	/**
	 * Removes every id of `recordIds` from the delegate bitmap, journalling into an open warm-up savepoint the ids
	 * whose membership the write actually CHANGED — the {@link Bitmap} counterpart of
	 * {@link #removeAllFromDelegate(int[])}.
	 *
	 * The no-savepoint branch keeps the whole-bitmap `andNot` fast path for a roaring-backed argument untouched; the
	 * savepoint-open branch walks the argument id by id, because `andNot` reports nothing about WHICH members it took
	 * away and the delta is exactly what the inverse has to put back.
	 *
	 * @param recordIds the ids to remove
	 */
	private void removeAllFromDelegate(@Nonnull Bitmap recordIds) {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint == null) {
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
			final int maxRemovedCount = recordIds.size();
			int[] removedIds = null;
			int removedCount = 0;
			try {
				final OfInt it = recordIds.iterator();
				while (it.hasNext()) {
					final int recordId = it.nextInt();
					if (this.roaringBitmap.contains(recordId)) {
						// the delta slot is reserved BEFORE the bit is flipped, so no failure can leave a flip
						// outside the delta
						removedIds = appendChangedId(removedIds, removedCount, recordId, maxRemovedCount);
						removedCount++;
						this.roaringBitmap.remove(recordId);
					}
				}
			} finally {
				// pushed from a FINALLY, so a walk that dies part-way still journals the flips it managed to make
				if (removedIds != null) {
					journalRemovals(savepoint, removedIds, removedCount);
				}
				this.memoizedCardinality = -1;
			}
		}
	}

	/**
	 * Pushes ONE inverse reverting the additions a bulk write made — the first `addedCount` entries of `addedIds`, all
	 * of which were absent before that write and must be absent again after a rollback.
	 *
	 * A single journal entry per bulk operation, rather than one per changed id, is what keeps the journal bounded by
	 * operations instead of by members; the restore is still absolute per id, which is what the journal's strict
	 * reverse replay needs to collapse a bit written several times inside one savepoint back to its pre-savepoint
	 * membership.
	 *
	 * Unlike every other recording call in this class this one necessarily runs AFTER its forward write, because a
	 * delta is not knowable before the write that produces it. Two properties of the CALLER are what make that safe
	 * rather than merely convenient, and both are load-bearing rather than defensive style:
	 *
	 * - **The push happens in a `finally`, not after a completed walk.** A bulk walk that dies part-way — a throwing
	 *   iterator on the {@link Bitmap} overload, an allocation failure inside a container — has still flipped bits, and
	 *   they are rewindable only if the delta accumulated up to that point reaches the journal. Pushing only on the
	 *   normal exit would let the rollback report success while the bitmap silently kept those flips, which is exactly
	 *   the silent-partial-rollback failure this whole mechanism exists to remove.
	 * - **Each delta slot is reserved BEFORE its bit is flipped**, so no completed flip can end up outside the delta.
	 *   The reverse residue — an entry whose flip then never happened, because the write right after it threw — is
	 *   harmless: the inverse is an ABSOLUTE restore of the membership that id held before the walk, so re-asserting
	 *   it is a no-op.
	 *
	 * Together they make the guarantee total: after any outcome of the walk, every membership it changed is in the
	 * journal, and nothing the journal names can be restored wrongly.
	 *
	 * @param savepoint  the open savepoint to record into
	 * @param addedIds   buffer holding the ids the write added, in its first `addedCount` slots
	 * @param addedCount number of recorded ids, always greater than zero
	 */
	private void journalAdditions(@Nonnull WarmUpSavepoint savepoint, @Nonnull int[] addedIds, int addedCount) {
		savepoint.push(() -> {
			for (int i = 0; i < addedCount; i++) {
				this.roaringBitmap.remove(addedIds[i]);
			}
			this.memoizedCardinality = -1;
		});
	}

	/**
	 * Pushes ONE inverse reverting the removals a bulk write made — the first `removedCount` entries of `removedIds`,
	 * all of which were present before that write and must be present again after a rollback. The removal mirror of
	 * {@link #journalAdditions(WarmUpSavepoint, int[], int)}, whose JavaDoc carries the reasoning.
	 *
	 * @param savepoint    the open savepoint to record into
	 * @param removedIds   buffer holding the ids the write removed, in its first `removedCount` slots
	 * @param removedCount number of recorded ids, always greater than zero
	 */
	private void journalRemovals(@Nonnull WarmUpSavepoint savepoint, @Nonnull int[] removedIds, int removedCount) {
		savepoint.push(() -> {
			for (int i = 0; i < removedCount; i++) {
				this.roaringBitmap.add(removedIds[i]);
			}
			this.memoizedCardinality = -1;
		});
	}

	/**
	 * Appends `recordId` to a bulk write's delta buffer at index `changedCount`, allocating or growing the buffer as
	 * needed, and returns the buffer to store back into the caller's local.
	 *
	 * The buffer is grown by doubling and hard-capped at `maxChangedCount` — the size of the bulk argument, which no
	 * delta can exceed because each changed id is a distinct element of it. Sizing straight to that cap would make a
	 * bulk write that adds one new id to a million-member argument allocate four megabytes; starting small and doubling
	 * keeps the buffer proportional to the delta while still allocating exactly once for the common case of a delta
	 * that fits the initial capacity. `CompositeIntArray` — the codebase's general growing int buffer — was the
	 * alternative and loses here: its backing `ArrayList` plus a fixed 50-int chunk costs more than the whole buffer
	 * for the small deltas this path sees.
	 *
	 * @param changedIds      the buffer built so far, or `null` when nothing has been appended yet
	 * @param changedCount    number of ids already in the buffer, i.e. the index to write at
	 * @param recordId        the id to append
	 * @param maxChangedCount upper bound on the delta size, i.e. the size of the bulk argument
	 * @return the buffer to keep using, which is a NEW array whenever it had to be allocated or grown
	 */
	@Nonnull
	private static int[] appendChangedId(
		@Nullable int[] changedIds, int changedCount, int recordId, int maxChangedCount
	) {
		final int[] target;
		if (changedIds == null) {
			target = new int[Math.min(INITIAL_CHANGED_ID_CAPACITY, maxChangedCount)];
		} else if (changedCount == changedIds.length) {
			// the cap can never truncate below `changedCount + 1`: the buffer being full at a count strictly below the
			// cap means its length is below the cap too, so doubling-then-capping always yields a longer array
			target = Arrays.copyOf(changedIds, Math.min(changedIds.length << 1, maxChangedCount));
		} else {
			target = changedIds;
		}
		target[changedCount] = recordId;
		return target;
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
