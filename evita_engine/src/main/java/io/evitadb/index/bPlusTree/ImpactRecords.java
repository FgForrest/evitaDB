/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.bPlusTree;

import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.IntByteHashMap;
import com.carrotsearch.hppc.LongArrayList;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.SortedArrayBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.roaringbitmap.PeekableIntIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Arrays;

/**
 * The per-record **impact** bytes of one bucket of a {@link TransactionalBucketBPlusTree} leaf, together with every
 * operation the leaf performs on them - the impact-column sibling of {@link OverflowRecords}, which owns the record
 * set the impacts are aligned with.
 *
 * ## The slot follows the record tier
 *
 * A slot of the leaf's impact column mirrors the shape of the bucket's record set:
 *
 * | record slot | impact slot | alignment |
 * |---|---|---|
 * | `null` (single record) | a cached {@link Byte} | one byte, one record |
 * | sorted `int[]` | `byte[]` of the same length | index-parallel |
 * | committed bitmap | `byte[][]`, one chunk per roaring container | chunk `c` ↔ container `c`, unsigned order |
 * | bitmap written in a transaction | {@link PendingImpacts} | aligned once, at the leaf commit-merge |
 *
 * A committed bitmap's chunks are in container order, and chunk `c` is parallel to the ids of container `c` in
 * roaring's (unsigned) iteration order. {@link PendingImpacts} holds the committed chunks plus the unaligned
 * `pk -> impact` delta the transaction wrote.
 *
 * The slot is an untyped `Object` for the same reason an overflow slot is (see {@link OverflowRecords}): a `byte[]`
 * cannot implement an interface, and a wrapper per bucket would cost a header and an indirection per bucket.
 *
 * ## Alignment is never maintained against a merged view
 *
 * Inside a transaction a {@link TransactionalBitmap} answers ranks from its merged view, which is re-merged on every
 * write ({@code BitmapChanges#getMergedBitmap}). So the bitmap-tier arm never asks for a rank there: it records the
 * `(pk, impact)` pairs the transaction wrote and aligns them once, at the leaf's commit-merge, by walking the
 * committed-before and committed-after bitmaps side by side ({@link #align}). Outside a transaction (WARM_UP) the
 * delegate bitmap is mutated in place and a rank costs one container search, so the chunks are kept aligned
 * eagerly - the position is computed BEFORE the record arm writes, which is why every mutator here is called first.
 *
 * ## Mutation contract
 *
 * Every mutator returns the slot to store back and never writes a `byte[]`, `byte[][]` or `Byte` in place, so the
 * impact column inherits the leaf's MVCC isolation and savepoint memento exactly as the overflow column does. The one
 * exception is {@link PendingImpacts}, which is transaction-private by construction (it exists only in a leaf layer's
 * column) and accumulates its delta in place.
 *
 * Writing in place is what makes {@link PendingImpacts} the one slot a leaf memento cannot simply share: a per-entity
 * savepoint opened after the transaction's first write to the bucket would otherwise see its rollback restore the
 * leaf around a delta that still carries the failed entity's pairs. The memento therefore goes through
 * {@link #snapshotColumn}, {@link #restoreColumn} and {@link #releaseColumn}, which mark the delta, rewind it to the
 * mark, and stop its undo log again - see {@link PendingImpacts}.
 *
 * For the same reason a delta is written through exactly one live column. A split copies a delta by reference into
 * the base column of a leaf born in the transaction, and a layer that later starts over that base would alias it;
 * {@link #ownPendingDeltas} gives such a layer copies of its own, so the base keeps the delta it held.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ImpactRecords {
	/**
	 * The impact an insert carries on a tree that stores none - an `int` sentinel outside the `0..255` range a byte
	 * impact is widened into, so the leaf's insert path can take one parameter for both kinds of tree.
	 */
	static final int NO_IMPACT = -1;
	/**
	 * The impact slot of a bucket whose every record was removed - the leaf deletes such a bucket, so the slot never
	 * survives; it only has to be a legal return value.
	 */
	private static final byte[] EMPTY_IMPACTS = ArrayUtils.EMPTY_BYTE_ARRAY;
	/**
	 * Low 16 bits of a record id select the position inside its roaring container; the high 16 select the container.
	 */
	private static final int CONTAINER_SHIFT = 16;
	/**
	 * Bit 62 of a position packed by {@link #locate}: set when the id is a member of the bitmap, and the offset is
	 * then its index in its chunk.
	 */
	private static final long PRESENT_FLAG = 1L << 62;
	/**
	 * Bit 61 of a position packed by {@link #locate}: set when the id's roaring container already holds at least one
	 * id, so its chunk exists.
	 */
	private static final long CONTAINER_FLAG = 1L << 61;
	/**
	 * The low 61 bits of a position packed by {@link #locate}, without the flags: the chunk ordinal shifted left by
	 * 32 and the offset in the chunk in the low 32 bits - so masked positions sort by ordinal, then offset.
	 */
	private static final long POSITION_MASK = (1L << 61) - 1;

	private ImpactRecords() {
		throw new UnsupportedOperationException("ImpactRecords is a static utility and must not be instantiated!");
	}

	/**
	 * Builds the impact slot of a single-record bucket. The cached {@link Byte} instances cover the whole range, so
	 * this never allocates.
	 *
	 * @param impact the record's impact, widened to `0..255`
	 * @return the slot to store
	 */
	@Nonnull
	static Byte single(int impact) {
		assertImpactInRange(impact);
		return Byte.valueOf((byte) impact);
	}

	/**
	 * Builds the impact slot of a bucket promoted out of the single-record tier by a second, distinct record - the
	 * twin of {@link OverflowRecords#promoteSingle(int, int)}, ordered the same way.
	 *
	 * @param impactSlot the single bucket's current slot (a {@link Byte})
	 * @param held       the id the bucket already holds
	 * @param added      the second, distinct id
	 * @param impact     the added record's impact
	 * @return the two impacts as an array slot, in the unsigned order of their ids
	 */
	@Nonnull
	static byte[] promoteSingle(@Nullable Object impactSlot, int held, int added, int impact) {
		assertImpactInRange(impact);
		final byte heldImpact = asSingle(impactSlot);
		final byte addedImpact = (byte) impact;
		return Integer.compareUnsigned(held, added) < 0
			? new byte[]{heldImpact, addedImpact} : new byte[]{addedImpact, heldImpact};
	}

	/**
	 * Records the impact of a record being added to a multi-record bucket. **Must be called BEFORE
	 * {@link OverflowRecords#add(Object, int)}** - the array arm derives the landing index from the current array,
	 * and the eager bitmap arm derives the position from the delegate the record arm is about to mutate.
	 *
	 * @param impactSlot the bucket's current impact slot
	 * @param records    the bucket's current record set (an `int[]` or a {@link TransactionalBitmap})
	 * @param pk         the record id being added
	 * @param impact     the record's impact
	 * @return the impact slot to store back - the same instance when nothing changed
	 */
	@Nonnull
	static Object add(@Nullable Object impactSlot, @Nonnull Object records, int pk, int impact) {
		assertImpactInRange(impact);
		final byte added = (byte) impact;
		if (records instanceof final int[] small) {
			final byte[] impacts = asArray(impactSlot, small.length);
			final int position = SortedArrayBitmap.unsignedBinarySearch(small, pk);
			if (position >= 0) {
				if (impacts[position] == added) {
					return impacts;
				}
				final byte[] replaced = impacts.clone();
				replaced[position] = added;
				return replaced;
			}
			final int insertAt = -position - 1;
			final byte[] grown = insertByte(impacts, insertAt, added);
			if (small.length + 1 > OverflowRecords.SMALL_BUCKET_THRESHOLD) {
				// the record arm promotes to a bitmap built from the final id set; the impacts are chunked by
				// container over exactly that set, so the spine is ordinal-parallel from the first moment
				return chunkByContainer(grown, ArrayUtils.insertIntIntoArrayOnIndex(pk, small, insertAt));
			}
			return grown;
		}
		final TransactionalBitmap bitmap = asBitmap(records);
		if (Transaction.isTransactionAvailable()) {
			// deferred: no rank against the merged view, the pair is aligned at the commit-merge
			final PendingImpacts pending = pendingOf(impactSlot, bitmap);
			pending.put(pk, added);
			return pending;
		}
		// WARM_UP: the delegate is about to be mutated in place, so the position is resolved against it now
		final byte[][] chunks = asChunks(impactSlot);
		final PersistentRoaringBitmap live = bitmap.getRoaringBitmap();
		final long position = locate(live, chunks, pk);
		final int ordinal = ordinalOf(position);
		final int offset = offsetOf(position);
		if (isPresent(position)) {
			final byte[] chunk = chunks[ordinal];
			if (chunk[offset] == added) {
				return chunks;
			}
			final byte[] replaced = chunk.clone();
			replaced[offset] = added;
			return withChunk(chunks, ordinal, replaced);
		}
		if (containerExists(position)) {
			return withChunk(chunks, ordinal, insertByte(chunks[ordinal], offset, added));
		}
		// the id opens a new container, which roaring inserts at exactly this ordinal
		return withInsertedChunk(chunks, ordinal, new byte[]{added});
	}

	/**
	 * Drops the impacts of records being removed from a multi-record bucket. **Must be called BEFORE
	 * {@link OverflowRecords#remove(Object, int...)}**, for the reason {@link #add} gives. Ids that are not members
	 * are ignored, as the record arm ignores them.
	 *
	 * @param impactSlot the bucket's current impact slot
	 * @param records    the bucket's current record set (an `int[]` or a {@link TransactionalBitmap})
	 * @param pks        the record ids being removed; must be non-empty
	 * @return the impact slot to store back - the same instance when nothing changed; the content is unspecified
	 * when the removal drains the bucket, which the leaf then deletes
	 */
	@Nonnull
	static Object remove(@Nullable Object impactSlot, @Nonnull Object records, @Nonnull int... pks) {
		if (records instanceof final int[] small) {
			final byte[] impacts = asArray(impactSlot, small.length);
			final boolean[] dropped = new boolean[small.length];
			int dropCount = 0;
			for (final int pk : pks) {
				final int position = SortedArrayBitmap.unsignedBinarySearch(small, pk);
				if (position >= 0 && !dropped[position]) {
					dropped[position] = true;
					dropCount++;
				}
			}
			if (dropCount == 0) {
				return impacts;
			}
			if (dropCount == small.length) {
				return EMPTY_IMPACTS;
			}
			final byte[] survivors = new byte[small.length - dropCount];
			int target = 0;
			for (int i = 0; i < small.length; i++) {
				if (!dropped[i]) {
					survivors[target++] = impacts[i];
				}
			}
			return survivors;
		}
		final TransactionalBitmap bitmap = asBitmap(records);
		if (Transaction.isTransactionAvailable()) {
			// deferred: a removed id is simply absent from the committed-after bitmap the merge walks; dropping it
			// from the delta only keeps a later re-add in the same transaction from reading a stale entry
			final PendingImpacts pending = pendingOf(impactSlot, bitmap);
			for (final int pk : pks) {
				pending.remove(pk);
			}
			return pending;
		}
		// WARM_UP: resolve every position against the still-unchanged delegate, then compact chunk by chunk
		final byte[][] chunks = asChunks(impactSlot);
		final PersistentRoaringBitmap live = bitmap.getRoaringBitmap();
		final long[] positions = new long[pks.length];
		int count = 0;
		for (final int pk : pks) {
			if (live.contains(pk)) {
				positions[count++] = locate(live, chunks, pk) & POSITION_MASK;
			}
		}
		if (count == 0) {
			return chunks;
		}
		Arrays.sort(positions, 0, count);
		// a repeated id resolves to the same position, and equal positions are adjacent once sorted: drop them once
		int unique = 0;
		for (int i = 0; i < count; i++) {
			if (i == 0 || positions[i] != positions[i - 1]) {
				positions[unique++] = positions[i];
			}
		}
		count = unique;
		final byte[][] result = new byte[chunks.length][];
		int written = 0;
		int next = 0;
		for (int ordinal = 0; ordinal < chunks.length; ordinal++) {
			final byte[] chunk = chunks[ordinal];
			if (next >= count || ordinalOf(positions[next]) != ordinal) {
				result[written++] = chunk;
				continue;
			}
			final int first = next;
			while (next < count && ordinalOf(positions[next]) == ordinal) {
				next++;
			}
			final int drop = next - first;
			if (drop == chunk.length) {
				// the container empties and roaring removes it, so its chunk leaves the spine too
				continue;
			}
			final byte[] survivors = new byte[chunk.length - drop];
			int target = 0;
			int dropping = first;
			for (int j = 0; j < chunk.length; j++) {
				if (dropping < next && offsetOf(positions[dropping]) == j) {
					dropping++;
				} else {
					survivors[target++] = chunk[j];
				}
			}
			result[written++] = survivors;
		}
		return written == result.length ? result : Arrays.copyOf(result, written);
	}

	/**
	 * Converts a bucket's impact slot into the shape its COMMITTED record set earns, at the leaf's commit merge -
	 * the only point at which a pending delta is aligned and the only point at which the tier can fall.
	 *
	 * @param impactSlot       the bucket's impact slot as the transaction left it
	 * @param originalRecords  the bucket's record set as the transaction left it (an `int[]` or the
	 *                         {@link TransactionalBitmap} whose layer was just merged)
	 * @param committedRecords the committed record set of a bitmap bucket, or `null` for an array one (whose array
	 *                         already IS its committed state)
	 * @param committedSlot    the record slot the merge stores back: `null` when the bucket demotes to a single
	 *                         record, a sorted `int[]` when it settles into the array tier, a
	 *                         {@link TransactionalBitmap} when it stays a bitmap
	 * @return the impact slot to store beside `committedSlot` - the same instance when nothing changed
	 * @throws GenericEvitaInternalError when a bitmap bucket arrives without its committed records, or its impacts
	 *                                   do not cover exactly the committed records - a record write that bypassed
	 *                                   the impact column
	 */
	@Nonnull
	static Object committed(
		@Nullable Object impactSlot,
		@Nonnull Object originalRecords,
		@Nullable Bitmap committedRecords,
		@Nullable Object committedSlot
	) {
		if (originalRecords instanceof final int[] small) {
			// an array slot was replaced wholesale at every write, so it already holds its committed alignment; only
			// the fall to the single-record tier changes its shape
			final byte[] impacts = asArray(impactSlot, small.length);
			return committedSlot == null ? Byte.valueOf(impacts[0]) : impacts;
		}
		if (committedRecords == null) {
			throw new GenericEvitaInternalError("A bitmap bucket's commit merge must hand over its committed records!");
		}
		final PersistentRoaringBitmap merged = RoaringBitmapBackedBitmap.getRoaringBitmap(committedRecords);
		final byte[] flat;
		if (impactSlot instanceof final PendingImpacts pending) {
			flat = align(pending.committedView, pending.committedChunks, pending.delta, merged);
		} else {
			final byte[][] chunks = asChunks(impactSlot);
			if (totalLength(chunks) != merged.getCardinality()) {
				throw new GenericEvitaInternalError(
					"Impact chunks hold " + totalLength(chunks) + " bytes but the committed bucket holds "
						+ merged.getCardinality() + " records - a record write bypassed the impact column!"
				);
			}
			if (committedSlot instanceof TransactionalBitmap) {
				// the bucket was not written in this transaction and keeps its tier: nothing to realign
				return chunks;
			}
			flat = flatten(chunks);
		}
		if (committedSlot == null) {
			return Byte.valueOf(flat[0]);
		}
		if (committedSlot instanceof int[]) {
			// the demoted array is roaring's own enumeration order, which is the order `flat` was produced in
			return flat;
		}
		return chunkByContainer(flat, merged);
	}

	/**
	 * Converts the impacts of a bucket read back from a persisted page into the slot shape its loaded record set
	 * dictates - the load-path sibling of {@link #committed}, which does the same at a commit merge.
	 *
	 * @param impacts    the bucket's impacts as persisted, one byte per record in the order its records enumerate
	 *                   in (unsigned ascending); adopted, not copied, for the array tier
	 * @param recordSlot the bucket's loaded record slot: `null` for a single record, a sorted `int[]` or a
	 *                   {@link TransactionalBitmap} as {@link OverflowRecords#loadedRecordSet} chose
	 * @return the impact slot to store beside `recordSlot`
	 * @throws GenericEvitaInternalError when the impacts do not cover exactly the bucket's records, or the record
	 *                                   slot has a shape no record tier uses
	 */
	@Nonnull
	static Object loaded(@Nonnull byte[] impacts, @Nullable Object recordSlot) {
		if (recordSlot == null) {
			verifyLoadedImpactCount(impacts, 1);
			return Byte.valueOf(impacts[0]);
		} else if (recordSlot instanceof final int[] small) {
			verifyLoadedImpactCount(impacts, small.length);
			return impacts;
		} else if (recordSlot instanceof final TransactionalBitmap bitmap) {
			verifyLoadedImpactCount(impacts, bitmap.size());
			return chunkByContainer(impacts, RoaringBitmapBackedBitmap.getRoaringBitmap(bitmap));
		} else {
			throw new GenericEvitaInternalError(
				"A loaded record slot must be null, an int[] or a TransactionalBitmap, not " +
					recordSlot.getClass().getName() + "!"
			);
		}
	}

	/**
	 * Verifies that the impacts of a bucket read back from a persisted page cover exactly its records.
	 *
	 * @param impacts     the bucket's impacts as persisted
	 * @param recordCount the number of records the bucket's loaded record slot holds
	 * @throws GenericEvitaInternalError when the two counts differ
	 */
	private static void verifyLoadedImpactCount(@Nonnull byte[] impacts, int recordCount) {
		if (impacts.length != recordCount) {
			throw new GenericEvitaInternalError(
				"A persisted bucket carries " + impacts.length + " impacts for " + recordCount + " records!"
			);
		}
	}

	/**
	 * Returns the impacts of a bucket aligned with the order its records enumerate in. Inside a transaction a
	 * bitmap bucket with pending writes is aligned on the fly against its merged view - a read-your-writes answer
	 * that costs one merge, paid only by a reader of a bucket the open transaction touched.
	 *
	 * Every shape answers a fresh array the caller owns and may keep or write - a clone, a concatenation or a new
	 * alignment, never the stored slot - which is what lets {@link TransactionalBucketBPlusTree#impactsOf} promise a
	 * copy.
	 *
	 * @param impacts the bucket's impact slot
	 * @param records the bucket's record slot - `null` for a single-record bucket
	 * @return the impacts, one byte per record in record order
	 */
	@Nonnull
	static byte[] aligned(@Nullable Object impacts, @Nullable Object records) {
		if (impacts instanceof final Byte single) {
			return new byte[]{single};
		}
		if (impacts instanceof final byte[] array) {
			return array.clone();
		}
		if (impacts instanceof final byte[][] chunks) {
			return flatten(chunks);
		}
		if (impacts instanceof final PendingImpacts pending) {
			return align(
				pending.committedView, pending.committedChunks, pending.delta, asBitmap(records).getRoaringBitmap()
			);
		}
		throw unexpectedSlot(impacts);
	}

	/**
	 * Returns a read-only view of a bucket's impacts in the order its records enumerate in - the zero-copy sibling
	 * of {@link #aligned}. A committed slot is wrapped where it lies; only a bitmap bucket with pending writes is
	 * aligned into a fresh array, exactly as {@link #aligned} aligns it.
	 *
	 * @param impacts the bucket's impact slot
	 * @param records the bucket's record slot - `null` for a single-record bucket
	 * @return the view
	 */
	@Nonnull
	static ImpactView view(@Nullable Object impacts, @Nullable Object records) {
		if (impacts instanceof final Byte single) {
			return ImpactView.single(single);
		}
		if (impacts instanceof final byte[] array) {
			return ImpactView.of(array);
		}
		if (impacts instanceof final byte[][] chunks) {
			return ImpactView.ofChunks(chunks, totalLength(chunks));
		}
		if (impacts instanceof PendingImpacts) {
			return ImpactView.of(aligned(impacts, records));
		}
		throw unexpectedSlot(impacts);
	}

	/**
	 * Returns the heap an impact slot occupies, excluding the slot in the impact column that points at it - the
	 * column charges that itself. A cached {@link Byte} is owned by the JVM and costs the leaf nothing.
	 *
	 * @param impacts the bucket's impact slot
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	static long heapSizeInBytes(@Nonnull Object impacts) {
		final VMLayout layout = VMLayout.current();
		if (impacts instanceof Byte) {
			return 0L;
		}
		if (impacts instanceof final byte[] array) {
			return array.length == 0 ? 0L : layout.sizeOfArray(array.length, Byte.BYTES);
		}
		if (impacts instanceof final byte[][] chunks) {
			return chunksHeapSize(chunks, layout);
		}
		if (impacts instanceof final PendingImpacts pending) {
			// transaction-private; the delta is priced only approximately, it never reaches a committed leaf
			return layout.sizeOfObject(4L * layout.referenceSize() + Integer.BYTES)
				+ chunksHeapSize(pending.committedChunks, layout)
				+ layout.sizeOfArray(pending.delta.size(), Integer.BYTES + Byte.BYTES)
				+ (pending.undo == null ? 0L : layout.sizeOfArray(pending.undo.buffer.length, Long.BYTES));
		}
		throw unexpectedSlot(impacts);
	}

	/**
	 * Refuses an impact outside the range a byte can carry - every value `0..255` is a legal impact, so the only way
	 * an out-of-range value arrives is {@link #NO_IMPACT} reaching a tree that carries impacts.
	 *
	 * @param impact the impact to check
	 */
	static void assertImpactInRange(int impact) {
		if (impact < 0 || impact > 255) {
			throw new GenericEvitaInternalError(
				"An impact is an unsigned byte, but " + impact + " was handed to an impact-carrying bucket!"
			);
		}
	}

	/**
	 * Produces the impacts of `newView` in its iteration order from the impacts of `oldView` and the pairs written
	 * since - the single pass in which a transaction's unaligned delta becomes aligned. Both bitmaps are walked in
	 * roaring's unsigned order with two cursors; an id in both takes the delta's impact when it has one and its old
	 * chunk byte otherwise, an id only in the new view must be in the delta, an id only in the old view was removed
	 * and is skipped. Chunks the transaction did not touch are copied byte by byte as well. Sharing them by reference
	 * would save that copy - a possible optimization, which would need the commit merge to assemble the chunk spine
	 * directly rather than re-chunk the one flat array this answers.
	 *
	 * @param oldView   the bitmap the chunks are aligned with
	 * @param oldChunks the aligned chunks, one per container of `oldView`
	 * @param delta     the `pk -> impact` pairs written since
	 * @param newView   the bitmap to align with
	 * @return the impacts of `newView`'s ids, in its iteration order
	 */
	@Nonnull
	private static byte[] align(
		@Nonnull PersistentRoaringBitmap oldView,
		@Nonnull byte[][] oldChunks,
		@Nonnull IntByteHashMap delta,
		@Nonnull PersistentRoaringBitmap newView
	) {
		final byte[] out = new byte[newView.getCardinality()];
		final PeekableIntIterator oldIt = oldView.getIntIterator();
		final PeekableIntIterator newIt = newView.getIntIterator();
		boolean hasOld = oldIt.hasNext();
		int oldPk = hasOld ? oldIt.next() : 0;
		int oldOrdinal = 0;
		int oldOffset = 0;
		int written = 0;
		while (newIt.hasNext()) {
			final int pk = newIt.next();
			// skip the old ids the transaction removed
			while (hasOld && Integer.compareUnsigned(oldPk, pk) < 0) {
				if (oldIt.hasNext()) {
					final int next = oldIt.next();
					if ((next >>> CONTAINER_SHIFT) != (oldPk >>> CONTAINER_SHIFT)) {
						oldOrdinal++;
						oldOffset = 0;
					} else {
						oldOffset++;
					}
					oldPk = next;
				} else {
					hasOld = false;
				}
			}
			final int deltaIndex = delta.indexOf(pk);
			if (hasOld && oldPk == pk) {
				// the matched old id is consumed lazily: the new ids ascend strictly, so the skip loop of the next
				// iteration steps past it
				out[written++] = deltaIndex >= 0 ? delta.indexGet(deltaIndex) : oldChunks[oldOrdinal][oldOffset];
			} else {
				if (deltaIndex < 0) {
					throw new GenericEvitaInternalError(
						"Record " + pk + " joined a bucket without an impact - a record write bypassed the impact "
							+ "column!"
					);
				}
				out[written++] = delta.indexGet(deltaIndex);
			}
		}
		return out;
	}

	/**
	 * Splits impacts that are aligned with `bitmap`'s iteration order into one chunk per roaring container.
	 *
	 * @param flat   the impacts in iteration order
	 * @param bitmap the bitmap they are aligned with
	 * @return the chunks, ordinal-parallel to the bitmap's containers
	 */
	@Nonnull
	private static byte[][] chunkByContainer(@Nonnull byte[] flat, @Nonnull PersistentRoaringBitmap bitmap) {
		final IntArrayList sizes = new IntArrayList();
		final PeekableIntIterator it = bitmap.getIntIterator();
		int previousKey = -1;
		int size = 0;
		while (it.hasNext()) {
			final int key = it.next() >>> CONTAINER_SHIFT;
			if (key != previousKey && size > 0) {
				sizes.add(size);
				size = 0;
			}
			previousKey = key;
			size++;
		}
		if (size > 0) {
			sizes.add(size);
		}
		return sliceChunks(flat, sizes);
	}

	/**
	 * Splits impacts that are aligned with a sorted id array into one chunk per container the ids would occupy.
	 *
	 * @param flat      the impacts, index-parallel to `sortedIds`
	 * @param sortedIds the ids in unsigned order
	 * @return the chunks, ordinal-parallel to the containers a bitmap of these ids has
	 */
	@Nonnull
	private static byte[][] chunkByContainer(@Nonnull byte[] flat, @Nonnull int[] sortedIds) {
		final IntArrayList sizes = new IntArrayList();
		int previousKey = -1;
		int size = 0;
		for (final int id : sortedIds) {
			final int key = id >>> CONTAINER_SHIFT;
			if (key != previousKey && size > 0) {
				sizes.add(size);
				size = 0;
			}
			previousKey = key;
			size++;
		}
		if (size > 0) {
			sizes.add(size);
		}
		return sliceChunks(flat, sizes);
	}

	/**
	 * Cuts `flat` into consecutive chunks of the given sizes.
	 *
	 * @param flat  the bytes to cut
	 * @param sizes the chunk sizes, summing to `flat.length`
	 * @return the chunks
	 */
	@Nonnull
	private static byte[][] sliceChunks(@Nonnull byte[] flat, @Nonnull IntArrayList sizes) {
		final byte[][] chunks = new byte[sizes.size()][];
		int from = 0;
		for (int i = 0; i < chunks.length; i++) {
			final int chunkSize = sizes.get(i);
			chunks[i] = Arrays.copyOfRange(flat, from, from + chunkSize);
			from += chunkSize;
		}
		if (from != flat.length) {
			throw new GenericEvitaInternalError(
				"Impact chunks cover " + from + " records but " + flat.length + " impacts were handed in!"
			);
		}
		return chunks;
	}

	/**
	 * Concatenates the chunks back into iteration order.
	 *
	 * @param chunks the chunks
	 * @return the impacts in iteration order
	 */
	@Nonnull
	private static byte[] flatten(@Nonnull byte[][] chunks) {
		final byte[] flat = new byte[totalLength(chunks)];
		int to = 0;
		for (final byte[] chunk : chunks) {
			System.arraycopy(chunk, 0, flat, to, chunk.length);
			to += chunk.length;
		}
		return flat;
	}

	/**
	 * @param chunks the chunks
	 * @return the number of impacts the chunks hold
	 */
	private static int totalLength(@Nonnull byte[][] chunks) {
		int total = 0;
		for (final byte[] chunk : chunks) {
			total += chunk.length;
		}
		return total;
	}

	/**
	 * Resolves where `pk` sits, or would sit, in chunks aligned with `live` - all from ranks over the live delegate,
	 * which the WARM_UP path is allowed to ask: `rankLong` is one container search plus one in-container rank. The
	 * ordinal is recovered by walking the chunk lengths up to the number of ids below `pk`'s container, so no
	 * container-level API of the roaring fork is needed.
	 *
	 * @param live   the bitmap the chunks are aligned with
	 * @param chunks the aligned chunks
	 * @param pk     the id to resolve
	 * @return the packed position: ordinal in the high half, offset in the low half, {@link #PRESENT_FLAG} when
	 * the id is a member (the offset is then its index), {@link #CONTAINER_FLAG} when its container exists
	 */
	private static long locate(@Nonnull PersistentRoaringBitmap live, @Nonnull byte[][] chunks, int pk) {
		final int containerStart = (pk >>> CONTAINER_SHIFT) << CONTAINER_SHIFT;
		final long below = containerStart == 0 ? 0L : live.rankLong(containerStart - 1);
		final long upToPk = live.rankLong(pk);
		final long upToContainerEnd = live.rankLong(containerStart | 0xFFFF);
		final boolean present = live.contains(pk);
		final int offset = (int) (upToPk - below) - (present ? 1 : 0);
		int ordinal = 0;
		long accumulated = 0L;
		while (accumulated < below) {
			accumulated += chunks[ordinal++].length;
		}
		if (accumulated != below) {
			throw new GenericEvitaInternalError(
				"Impact chunks are misaligned with their bitmap: " + accumulated + " bytes against " + below
					+ " records below the container of " + pk + "!"
			);
		}
		return ((long) ordinal << 32) | offset
			| (present ? PRESENT_FLAG : 0L)
			| (upToContainerEnd - below > 0 ? CONTAINER_FLAG : 0L);
	}

	/**
	 * Extracts the chunk ordinal from a position packed by {@link #locate}.
	 *
	 * @param position the packed position
	 * @return the ordinal of the chunk (roaring container) the id falls into
	 */
	private static int ordinalOf(long position) {
		return (int) ((position & POSITION_MASK) >>> 32);
	}

	/**
	 * Extracts the offset inside the chunk from a position packed by {@link #locate}.
	 *
	 * @param position the packed position
	 * @return the id's index in its chunk when present, the index it would take when absent
	 */
	private static int offsetOf(long position) {
		return (int) (position & 0xFFFF_FFFFL);
	}

	/**
	 * Reads the membership flag of a position packed by {@link #locate}.
	 *
	 * @param position the packed position
	 * @return true when the id is a member of the bitmap
	 */
	private static boolean isPresent(long position) {
		return (position & PRESENT_FLAG) != 0;
	}

	/**
	 * Reads the container flag of a position packed by {@link #locate}.
	 *
	 * @param position the packed position
	 * @return true when the id's roaring container already holds at least one id, so its chunk exists
	 */
	private static boolean containerExists(long position) {
		return (position & CONTAINER_FLAG) != 0;
	}

	/**
	 * Returns the pending delta of a bitmap bucket, creating it on the transaction's first write to the bucket - at
	 * which moment the bitmap's committed delegate is captured as the view the chunks are aligned with. The capture
	 * has to precede the record arm's first write, because that write hangs a diff layer on the bitmap and from then
	 * on the delegate is reachable only through the merged view.
	 *
	 * @param impactSlot the bucket's current slot (`byte[][]` or an existing {@link PendingImpacts})
	 * @param bitmap     the bucket's bitmap
	 * @return the delta to write into
	 */
	@Nonnull
	private static PendingImpacts pendingOf(@Nullable Object impactSlot, @Nonnull TransactionalBitmap bitmap) {
		if (impactSlot instanceof final PendingImpacts pending) {
			return pending;
		}
		final byte[][] chunks = asChunks(impactSlot);
		if (Transaction.getTransactionalMemoryLayerIfExists(bitmap) != null) {
			throw new GenericEvitaInternalError(
				"The impact delta must be opened before the bucket's first record write of the transaction!"
			);
		}
		final PersistentRoaringBitmap committedView = bitmap.getRoaringBitmap();
		if (committedView.getCardinality() != totalLength(chunks)) {
			throw new GenericEvitaInternalError(
				"Impact chunks hold " + totalLength(chunks) + " bytes but the bucket holds "
					+ committedView.getCardinality() + " records!"
			);
		}
		return new PendingImpacts(committedView, chunks);
	}

	/**
	 * Returns a copy of the array with one byte inserted; the source is never written, which is what keeps a slot
	 * shared with a committed leaf intact.
	 *
	 * @param src   the source array
	 * @param at    index the new byte takes
	 * @param value the byte to insert
	 * @return a new array one longer than the source
	 */
	@Nonnull
	private static byte[] insertByte(@Nonnull byte[] src, int at, byte value) {
		final byte[] grown = new byte[src.length + 1];
		System.arraycopy(src, 0, grown, 0, at);
		grown[at] = value;
		System.arraycopy(src, at, grown, at + 1, src.length - at);
		return grown;
	}

	/**
	 * Returns a shallow copy of the chunk array with one chunk replaced; the source array is never written.
	 *
	 * @param chunks      the source chunks
	 * @param ordinal     index of the chunk to replace
	 * @param replacement the new chunk
	 * @return a new chunk array
	 */
	@Nonnull
	private static byte[][] withChunk(@Nonnull byte[][] chunks, int ordinal, @Nonnull byte[] replacement) {
		final byte[][] copy = chunks.clone();
		copy[ordinal] = replacement;
		return copy;
	}

	/**
	 * Returns a copy of the chunk array with one chunk inserted, for an id opening a new roaring container.
	 *
	 * @param chunks  the source chunks
	 * @param ordinal index the new chunk takes
	 * @param chunk   the new chunk
	 * @return a new chunk array one longer than the source
	 */
	@Nonnull
	private static byte[][] withInsertedChunk(@Nonnull byte[][] chunks, int ordinal, @Nonnull byte[] chunk) {
		final byte[][] grown = new byte[chunks.length + 1][];
		System.arraycopy(chunks, 0, grown, 0, ordinal);
		grown[ordinal] = chunk;
		System.arraycopy(chunks, ordinal, grown, ordinal + 1, chunks.length - ordinal);
		return grown;
	}

	/**
	 * Estimates the retained heap of a chunk array: the reference array plus every chunk.
	 *
	 * @param chunks the chunks
	 * @param layout the VM layout to size the arrays with
	 * @return the estimated size in bytes
	 */
	private static long chunksHeapSize(@Nonnull byte[][] chunks, @Nonnull VMLayout layout) {
		long size = layout.sizeOfArray(chunks.length, layout.referenceSize());
		for (final byte[] chunk : chunks) {
			size += layout.sizeOfArray(chunk.length, Byte.BYTES);
		}
		return size;
	}

	/**
	 * Reads the impact of a single-record bucket's slot.
	 *
	 * @param impactSlot the slot
	 * @return the impact byte
	 * @throws GenericEvitaInternalError when the slot is not a {@link Byte}
	 */
	private static byte asSingle(@Nullable Object impactSlot) {
		if (impactSlot instanceof final Byte single) {
			return single;
		}
		throw unexpectedSlot(impactSlot);
	}

	/**
	 * Reads the impacts of a sorted-array bucket's slot, verifying they are as many as the bucket's records.
	 *
	 * @param impactSlot     the slot
	 * @param expectedLength the bucket's record count
	 * @return the impacts
	 * @throws GenericEvitaInternalError when the slot is not a `byte[]` or its length differs
	 */
	@Nonnull
	private static byte[] asArray(@Nullable Object impactSlot, int expectedLength) {
		if (impactSlot instanceof final byte[] array) {
			if (array.length != expectedLength) {
				throw new GenericEvitaInternalError(
					"An array bucket holds " + expectedLength + " records but its impact slot holds "
						+ array.length + " impacts!"
				);
			}
			return array;
		}
		throw unexpectedSlot(impactSlot);
	}

	/**
	 * Reads the chunks of a committed bitmap bucket's slot.
	 *
	 * @param impactSlot the slot
	 * @return the chunks
	 * @throws GenericEvitaInternalError when the slot is not a `byte[][]`
	 */
	@Nonnull
	private static byte[][] asChunks(@Nullable Object impactSlot) {
		if (impactSlot instanceof final byte[][] chunks) {
			return chunks;
		}
		throw unexpectedSlot(impactSlot);
	}

	/**
	 * Reads a bitmap bucket's record set.
	 *
	 * @param records the overflow slot
	 * @return the bitmap
	 * @throws GenericEvitaInternalError when the slot holds anything else
	 */
	@Nonnull
	private static TransactionalBitmap asBitmap(@Nullable Object records) {
		if (records instanceof final TransactionalBitmap bitmap) {
			return bitmap;
		}
		throw new GenericEvitaInternalError(
			"An overflow bucket is either a sorted int[] or a TransactionalBitmap, never a "
				+ (records == null ? "null" : records.getClass().getName()) + "!"
		);
	}

	/**
	 * Copies a leaf's impact column into a savepoint memento. Every slot is shared by reference, which is a faithful
	 * pre-image for the slots that are never written in place; a {@link PendingImpacts} is the exception, so it is
	 * marked instead - its undo log starts recording, and the memento keeps a {@link PendingImpactsMark} naming the
	 * position {@link #restoreColumn} rewinds it to.
	 *
	 * @param impacts the live impact column of the leaf being captured
	 * @return the column to keep in the memento; it may hold marks and must never be installed as a live column as is
	 */
	@Nonnull
	static OverflowColumn snapshotColumn(@Nonnull OverflowColumn impacts) {
		final OverflowColumn copy = impacts.duplicate();
		final int size = copy.size();
		for (int i = 0; i < size; i++) {
			if (copy.recordsAt(i) instanceof final PendingImpacts pending) {
				copy.setAt(i, new PendingImpactsMark(pending, pending.mark()));
			}
		}
		return copy;
	}

	/**
	 * Returns the impact column a new transactional layer of a leaf starts from: the leaf's own column when it holds
	 * no {@link PendingImpacts}, otherwise a copy in which every delta is replaced by an independent copy of it.
	 *
	 * A committed leaf never holds a delta, so the common case scans the column and allocates nothing. A leaf born in
	 * the running transaction - a split half, built from its origin's layer - holds the deltas of its multi buckets in
	 * its BASE column. A layer sharing them would write them in place, and a layer created inside a savepoint is not
	 * snapshotted (the rollback simply drops it), so those writes would survive the rollback in the base the leaf
	 * falls back to. With its own copies the layer writes only what it owns, and the base keeps the pre-layer delta.
	 *
	 * @param impacts the leaf's base impact column
	 * @return the column for the new layer - the same instance when it holds no delta
	 */
	@Nonnull
	static OverflowColumn ownPendingDeltas(@Nonnull OverflowColumn impacts) {
		OverflowColumn owned = null;
		final int size = impacts.size();
		for (int i = 0; i < size; i++) {
			if (impacts.recordsAt(i) instanceof final PendingImpacts pending) {
				if (owned == null) {
					owned = impacts.duplicate();
				}
				owned.setAt(i, pending.copy());
			}
		}
		return owned == null ? impacts : owned;
	}

	/**
	 * Rebuilds a live impact column from a column {@link #snapshotColumn} captured: every marked delta is rewound to
	 * its mark and put back in place of the mark. The memento is left as it was, so it can be restored from again -
	 * rewinding to a position the log has already been cut back to changes nothing.
	 *
	 * @param memento the column kept in the memento
	 * @return a fresh live column equal to the captured one
	 */
	@Nonnull
	static OverflowColumn restoreColumn(@Nonnull OverflowColumn memento) {
		final OverflowColumn copy = memento.duplicate();
		final int size = copy.size();
		for (int i = 0; i < size; i++) {
			if (copy.recordsAt(i) instanceof final PendingImpactsMark mark) {
				mark.pending().rewind(mark.position());
				copy.setAt(i, mark.pending());
			}
		}
		return copy;
	}

	/**
	 * Releases the marks a memento column holds once its savepoint is closed, so a delta stops paying for its undo
	 * log for the rest of the transaction.
	 *
	 * @param memento the column kept in the memento
	 */
	static void releaseColumn(@Nonnull OverflowColumn memento) {
		final int size = memento.size();
		for (int i = 0; i < size; i++) {
			if (memento.recordsAt(i) instanceof final PendingImpactsMark mark) {
				mark.pending().release();
			}
		}
	}

	/**
	 * Builds the error for an impact slot whose shape no tier produces.
	 *
	 * @param impacts the offending slot
	 * @return the error to throw
	 */
	@Nonnull
	private static GenericEvitaInternalError unexpectedSlot(@Nullable Object impacts) {
		return new GenericEvitaInternalError(
			"An impact slot holds a Byte, a byte[], a byte[][] or a pending delta, never a "
				+ (impacts == null ? "null" : impacts.getClass().getName()) + "!"
		);
	}

	/**
	 * A bitmap bucket's impacts while a transaction writes to it: the chunks aligned with the committed bitmap, a
	 * reference to that committed delegate, and the unaligned `pk -> impact` pairs written since. Lives only in a
	 * leaf layer's impact column and is resolved by {@link ImpactRecords#committed} at the commit-merge.
	 *
	 * ## The undo log
	 *
	 * The delta is written in place, so a per-entity savepoint cannot capture it by reference the way it captures
	 * every other slot, and copying it would cost `O(delta)` per savepoint - for a transaction that writes a common
	 * term on every entity, `O(n²)` over the transaction. Instead a savepoint marks it ({@link #mark()}): from then on
	 * every write first appends the pair's previous state to an undo log, a rollback replays the log backwards down to
	 * the mark ({@link #rewind(int)}), and closing the savepoint drops the log ({@link #release()}). Outside a
	 * savepoint there is no log and a write costs what it did before.
	 *
	 * More than one mark may be open at once - each memento that captured this delta holds one - and the log lives
	 * until the last of them is released. Rewinding to every mark in any order ends at the earliest one, which is
	 * the state the savepoint opened on.
	 */
	static final class PendingImpacts {
		/**
		 * Flags an undo entry whose pair was present before the write; the low byte then holds its impact.
		 */
		private static final long PRESENT = 0x100L;
		/**
		 * The committed delegate the chunks are aligned with - immutable for the life of the transaction, since every
		 * write goes to the bitmap's diff layer.
		 */
		@Nonnull private final PersistentRoaringBitmap committedView;
		/**
		 * The chunks aligned with {@link #committedView}, shared with the committed column and never written.
		 */
		@Nonnull private final byte[][] committedChunks;
		/**
		 * The pairs written in this transaction - inserts and impact replacements alike.
		 */
		@Nonnull private final IntByteHashMap delta = new IntByteHashMap();
		/**
		 * The previous state of every pair written while a mark is open, oldest first: the pk in the high 32 bits,
		 * {@link #PRESENT} and the impact in the low ones. `null` while no mark is open.
		 */
		@Nullable private LongArrayList undo;
		/**
		 * How many marks are open - the log is dropped when the last one is released.
		 */
		private int openMarks;

		/**
		 * @param committedView   the committed delegate the chunks are aligned with
		 * @param committedChunks the chunks aligned with it
		 */
		PendingImpacts(@Nonnull PersistentRoaringBitmap committedView, @Nonnull byte[][] committedChunks) {
			this.committedView = committedView;
			this.committedChunks = committedChunks;
		}

		/**
		 * Returns an independent copy of this delta: the same committed view and chunks, which are never written, and
		 * a private copy of the pairs. The copy starts with no undo log and no open mark - a mark belongs to the
		 * memento that opened it on this instance.
		 *
		 * @return the copy
		 */
		@Nonnull
		PendingImpacts copy() {
			final PendingImpacts copy = new PendingImpacts(this.committedView, this.committedChunks);
			copy.delta.putAll(this.delta);
			return copy;
		}

		/**
		 * Records the impact of a record written in this transaction, replacing any pair written before.
		 *
		 * @param pk     the record
		 * @param impact its impact
		 */
		void put(int pk, byte impact) {
			journal(pk);
			this.delta.put(pk, impact);
		}

		/**
		 * Forgets the pair of a record removed in this transaction.
		 *
		 * @param pk the record
		 */
		void remove(int pk) {
			if (this.delta.containsKey(pk)) {
				journal(pk);
				this.delta.remove(pk);
			}
		}

		/**
		 * Opens a mark: starts the undo log if it is not running yet.
		 *
		 * @return the log position {@link #rewind(int)} must return to
		 */
		int mark() {
			if (this.undo == null) {
				this.undo = new LongArrayList();
			}
			this.openMarks++;
			return this.undo.size();
		}

		/**
		 * Undoes, newest first, every write logged after the position, and cuts the log back to it.
		 *
		 * @param position a position {@link #mark()} returned
		 */
		void rewind(int position) {
			final LongArrayList theUndo = this.undo;
			if (theUndo == null) {
				throw new GenericEvitaInternalError("An impact delta is rewound without a mark open on it!");
			}
			for (int i = theUndo.size() - 1; i >= position; i--) {
				final long entry = theUndo.get(i);
				final int pk = (int) (entry >>> 32);
				if ((entry & PRESENT) != 0L) {
					this.delta.put(pk, (byte) entry);
				} else {
					this.delta.remove(pk);
				}
			}
			if (theUndo.size() > position) {
				theUndo.removeRange(position, theUndo.size());
			}
		}

		/**
		 * Closes a mark, dropping the undo log when it was the last one open.
		 */
		void release() {
			if (this.openMarks <= 0) {
				throw new GenericEvitaInternalError("An impact delta is released more times than it was marked!");
			}
			if (--this.openMarks == 0) {
				this.undo = null;
			}
		}

		/**
		 * Appends the current state of the record's pair to the undo log when a mark is open.
		 *
		 * @param pk the record about to be written
		 */
		private void journal(int pk) {
			final LongArrayList theUndo = this.undo;
			if (theUndo != null) {
				final int index = this.delta.indexOf(pk);
				final long previous = index >= 0 ? PRESENT | (this.delta.indexGet(index) & 0xFFL) : 0L;
				theUndo.add(((long) pk << 32) | previous);
			}
		}
	}

	/**
	 * What a savepoint memento keeps in place of a {@link PendingImpacts}: the delta itself and the undo-log position
	 * it has to be rewound to. Never stored in a live column.
	 *
	 * @param pending  the marked delta
	 * @param position the position {@link PendingImpacts#mark()} returned
	 */
	record PendingImpactsMark(@Nonnull PendingImpacts pending, int position) {
	}
}
