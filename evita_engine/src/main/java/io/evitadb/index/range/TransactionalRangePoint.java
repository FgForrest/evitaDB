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

package io.evitadb.index.range;

import io.evitadb.core.transaction.memory.TransactionalCreatorMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.index.array.TransactionalObject;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.utils.VMLayout;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Read write transactional implementation of the {@link RangePoint}. The class is PROTECTED from modifications
 * only when transactional memory has currently transaction open. Otherwise it is NOT PROTECTED from modifications
 * because it has to return arrays that are by nature modifiable and thus not thread safe.
 *
 * Arrays are used because we want to stick to Java primitives in order to achieve maximum performance.
 *
 * The developers must ensure no code modifies contents of the arrays when transaction is not open.
 *
 * ## Occurrence levels — one record may start or end here more than once
 *
 * One record id may hold several ranges, and two of them may share this threshold — two owners of one referenced
 * entity contribute two ranges to the same record of a referenced-type index, and both may start at the same moment.
 * {@link RangeIndex} answers its queries by counting, per record, the ranges that started minus those that ended, so
 * each occurrence must be counted. A point therefore keeps its starts (and, separately, its ends) in **occurrence
 * levels**: {@link #getStarts()} is level 1, holding every record that starts here at least once, and
 * {@link #getRepeatedStarts()} holds levels 2, 3, … — element `i` holds the records starting here at least `i + 2`
 * times. A record starting here `k` times is in levels `1 … k` and in no other, so each level is a subset of the one
 * below it and the non-empty levels form a prefix: an addition goes to the **lowest** level not yet holding the record,
 * a removal takes it from the **highest** level holding it, and both find that level by a binary search over the
 * nested levels.
 *
 * A point where nothing repeats — the common case by far — carries `null` in both level fields and pays nothing else
 * for them.
 *
 * ## Spare levels, and why growing them means a new instance
 *
 * The level arrays are `final` and never resized in place. A point is shared between the committed state and every
 * open transaction (the tree hands each the same instance, and only the bitmaps inside it keep per-transaction
 * layers), so resizing an array of a shared instance would show the change to every reader at once and lose it on
 * rollback. An array may therefore end with **empty spare levels**: a level that empties stays in place, and a level
 * the record needs next is filled in place — both through the transactional bitmap, so both are isolated and undone on
 * rollback like any other write. Only when every level of the array holds the record does {@link #addStart} /
 * {@link #addEnd} return a new point with twice the capacity, which the caller stores in place of this one —
 * {@link RangeIndex} does so through the tree's transactional, journalled value replacement. The new point carries the
 * other side's levels over with their spare capacity, so the start and end arrays double independently. Growing by
 * doubling keeps the replacements logarithmic in the number of occurrences added to one point between two copies of
 * it, even where one record starts and ends here thousands of times. Readers stop at the first empty level; the spare
 * levels are dropped whenever the point is copied — on the commit of a point changed in place, on clone, and by the
 * persisted form, which writes the non-empty levels only. (A point minted by a growth and not changed afterwards is
 * not dirty, so the commit takes it over as it is, spare levels included.) A commit that copies a point visits every
 * level of it, so the doubling amortizes the additions within one transaction, not across transactions: once a commit
 * compacted the array, the next transaction adding one more occurrence of a record that fills it grows the point
 * again.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see RangePoint for more information about the purpose of the class
 */
@ThreadSafe
@EqualsAndHashCode(of = "threshold")
public class TransactionalRangePoint
	implements TransactionalObject<TransactionalRangePoint>,
	VoidTransactionMemoryProducer<TransactionalRangePoint>,
	RangePoint<TransactionalRangePoint>,
	TransactionalCreatorMaintainer,
	Serializable {
	@Serial private static final long serialVersionUID = -5049147595456087487L;
	/**
	 * Shared answer of {@link #getRepeatedStarts()} / {@link #getRepeatedEnds()} for a point where nothing repeats.
	 */
	private static final TransactionalBitmap[] NO_REPEATED_LEVELS = new TransactionalBitmap[0];
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	private final TransactionalBoolean dirty;
	@Getter private final long threshold;
	/**
	 * Occurrence level 1 of the starts: every record whose range starts at this threshold at least once.
	 */
	@Getter private final TransactionalBitmap starts;
	/**
	 * Occurrence level 1 of the ends: every record whose range ends at this threshold at least once.
	 */
	@Getter private final TransactionalBitmap ends;
	/**
	 * Occurrence levels 2, 3, … of the starts (element `i` is level `i + 2`), possibly ending with empty spare levels,
	 * or `null` when no record ever started here twice. Assigned only by the constructors — see the class javadoc.
	 */
	@Nullable private final TransactionalBitmap[] repeatedStarts;
	/**
	 * Occurrence levels 2, 3, … of the ends (element `i` is level `i + 2`), possibly ending with empty spare levels, or
	 * `null` when no record ever ended here twice. Assigned only by the constructors — see the class javadoc.
	 */
	@Nullable private final TransactionalBitmap[] repeatedEnds;

	/**
	 * Copies the non-empty prefix of the passed occurrence levels into fresh transactional bitmaps.
	 *
	 * @param levels the levels to copy, may be `null` or empty when nothing repeats
	 * @return the copies, or `null` when no level holds anything
	 */
	@Nullable
	private static TransactionalBitmap[] copyLevels(@Nullable Bitmap[] levels) {
		final int count = nonEmptyLevelCount(levels);
		if (count == 0) {
			return null;
		}
		final TransactionalBitmap[] result = new TransactionalBitmap[count];
		for (int i = 0; i < count; i++) {
			result[i] = new TransactionalBitmap(levels[i]);
		}
		return result;
	}

	/**
	 * Copies the passed occurrence levels into fresh transactional bitmaps, keeping the length of the array — its
	 * spare levels become fresh empty bitmaps. Used for the side that does not grow when the other side does: dropping
	 * its spare levels there would make the next repetition on this side grow the point again, so a record alternately
	 * starting and ending here would replace the point on almost every addition instead of once per doubling.
	 *
	 * @param levels the levels to copy, may be `null` when nothing repeats
	 * @return the copies with the same capacity, or `null` for `null`
	 */
	@Nullable
	private static TransactionalBitmap[] copyLevelsKeepingCapacity(@Nullable TransactionalBitmap[] levels) {
		if (levels == null) {
			return null;
		}
		final TransactionalBitmap[] result = new TransactionalBitmap[levels.length];
		final int count = nonEmptyLevelCount(levels);
		for (int i = 0; i < count; i++) {
			result[i] = new TransactionalBitmap(levels[i]);
		}
		for (int i = count; i < result.length; i++) {
			result[i] = new TransactionalBitmap();
		}
		return result;
	}

	/**
	 * Copies the passed occurrence levels into fresh transactional bitmaps and appends a new level holding just
	 * `recordId`, followed by spare levels up to twice the copied count.
	 *
	 * @param levels   the existing repeated levels, all of them holding `recordId`; may be `null` when nothing repeats
	 * @param recordId the record the new level holds
	 * @return the copies, the new level and the spare levels
	 */
	@Nonnull
	private static TransactionalBitmap[] copyLevelsWithOneMore(@Nullable TransactionalBitmap[] levels, int recordId) {
		final int existing = levels == null ? 0 : levels.length;
		final TransactionalBitmap[] result = new TransactionalBitmap[Math.max(1, existing * 2)];
		for (int i = 0; i < existing; i++) {
			result[i] = new TransactionalBitmap(levels[i]);
		}
		result[existing] = new TransactionalBitmap(recordId);
		for (int i = existing + 1; i < result.length; i++) {
			result[i] = new TransactionalBitmap();
		}
		return result;
	}

	/**
	 * Returns how many leading levels of the passed array hold any record — the levels that count; every level after
	 * the first empty one is empty too.
	 *
	 * @param levels the repeated levels, may be `null`
	 * @return the number of non-empty levels
	 */
	private static int nonEmptyLevelCount(@Nullable Bitmap[] levels) {
		if (levels == null) {
			return 0;
		}
		int count = 0;
		while (count < levels.length && !levels[count].isEmpty()) {
			count++;
		}
		return count;
	}

	/**
	 * Returns how many of the passed nested levels hold `recordId`. Membership is monotone along the array — a level
	 * holds the record only when every level before it does — so a binary search finds the boundary.
	 *
	 * @param levels   the repeated levels, may be `null`
	 * @param recordId the record to look for
	 * @return the number of leading levels holding the record
	 */
	private static int levelsHolding(@Nullable TransactionalBitmap[] levels, int recordId) {
		if (levels == null) {
			return 0;
		}
		int low = 0;
		int high = levels.length;
		while (low < high) {
			final int middle = (low + high) >>> 1;
			if (levels[middle].contains(recordId)) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		return low;
	}

	/**
	 * Tests whether two occurrence-level arrays hold the same records level by level, spare levels disregarded; `null`
	 * equals an array of empty levels.
	 *
	 * @param first  the first levels, may be `null`
	 * @param second the second levels, may be `null`
	 * @return `true` when both describe the same repeated occurrences
	 */
	private static boolean levelsEqual(@Nullable TransactionalBitmap[] first, @Nullable TransactionalBitmap[] second) {
		final int count = nonEmptyLevelCount(first);
		if (count != nonEmptyLevelCount(second)) {
			return false;
		}
		for (int i = 0; i < count; i++) {
			if (!Objects.equals(first[i], second[i])) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Folds the non-empty levels into a hash, spare levels disregarded.
	 *
	 * @param seed   the hash so far
	 * @param levels the repeated levels, may be `null`
	 * @return the hash including the levels
	 */
	private static int hashLevels(int seed, @Nullable TransactionalBitmap[] levels) {
		int result = seed;
		final int count = nonEmptyLevelCount(levels);
		for (int i = 0; i < count; i++) {
			result = 31 * result + levels[i].hashCode();
		}
		return result;
	}

	/**
	 * Returns the committed state of every level, spare levels included — the copying constructor drops them.
	 *
	 * @param transactionalLayer the maintainer merging the transactional changes
	 * @param levels             the repeated levels, may be `null`
	 * @return the committed bitmaps, or `null` when there are none
	 */
	@Nullable
	private static Bitmap[] mergeLevels(
		@Nonnull TransactionalLayerMaintainer transactionalLayer,
		@Nullable TransactionalBitmap[] levels
	) {
		if (levels == null) {
			return null;
		}
		final Bitmap[] result = new Bitmap[levels.length];
		for (int i = 0; i < levels.length; i++) {
			result[i] = transactionalLayer.getStateCopyWithCommittedChanges(levels[i]);
		}
		return result;
	}

	/**
	 * Returns the heap of an array of repeated levels and of every bitmap in it, spare levels included.
	 *
	 * @param layout the VM layout to price with
	 * @param levels the repeated levels, may be `null`
	 * @return the heap in bytes, `0` for `null`
	 */
	private static long levelsHeapSize(@Nonnull VMLayout layout, @Nullable TransactionalBitmap[] levels) {
		if (levels == null) {
			return 0L;
		}
		long size = layout.sizeOfArray(levels.length, layout.referenceSize());
		for (final TransactionalBitmap level : levels) {
			size += level.getHeapSizeInBytes();
		}
		return size;
	}

	public TransactionalRangePoint(long threshold) {
		this.threshold = threshold;
		this.starts = new TransactionalBitmap();
		this.ends = new TransactionalBitmap();
		this.repeatedStarts = null;
		this.repeatedEnds = null;
		this.dirty = new TransactionalBoolean();
	}

	public TransactionalRangePoint(long threshold, int[] starts, int[] ends) {
		this.threshold = threshold;
		this.starts = new TransactionalBitmap(starts);
		this.ends = new TransactionalBitmap(ends);
		this.repeatedStarts = null;
		this.repeatedEnds = null;
		this.dirty = new TransactionalBoolean();
	}

	public TransactionalRangePoint(long threshold, Bitmap starts, Bitmap ends) {
		this(threshold, starts, ends, null, null);
	}

	/**
	 * Creates a point with the passed occurrence levels, copying every bitmap so that this point is the sole owner
	 * of each. Empty levels are not copied — the non-empty prefix of each array is what the point keeps.
	 *
	 * The caller is responsible for the invariant of the class javadoc: each repeated level is a subset of the level
	 * below it. It is not verified here, because this constructor is what the persisted form is read through, and
	 * checking it would cost a pass over every point of every loaded range index.
	 *
	 * @param threshold      the threshold of the point
	 * @param starts         occurrence level 1 of the starts
	 * @param ends           occurrence level 1 of the ends
	 * @param repeatedStarts occurrence levels 2, 3, … of the starts, or `null` / empty when nothing repeats
	 * @param repeatedEnds   occurrence levels 2, 3, … of the ends, or `null` / empty when nothing repeats
	 */
	public TransactionalRangePoint(
		long threshold,
		@Nonnull Bitmap starts,
		@Nonnull Bitmap ends,
		@Nullable Bitmap[] repeatedStarts,
		@Nullable Bitmap[] repeatedEnds
	) {
		this.threshold = threshold;
		this.starts = new TransactionalBitmap(starts);
		this.ends = new TransactionalBitmap(ends);
		this.repeatedStarts = copyLevels(repeatedStarts);
		this.repeatedEnds = copyLevels(repeatedEnds);
		this.dirty = new TransactionalBoolean();
	}

	/**
	 * Takes over already-built bitmaps without copying them — used only for the fresh instance that replaces this one
	 * when a level array grows, whose bitmaps the caller has just built and nobody else references.
	 *
	 * @param threshold      the threshold of the point
	 * @param starts         occurrence level 1 of the starts, owned by the new point from now on
	 * @param ends           occurrence level 1 of the ends, owned by the new point from now on
	 * @param repeatedStarts occurrence levels 2, 3, … of the starts, owned by the new point from now on, or `null`
	 * @param repeatedEnds   occurrence levels 2, 3, … of the ends, owned by the new point from now on, or `null`
	 * @param adopted        always `true`; it only tells this constructor apart from the copying one — without it, a
	 *                       caller handing `TransactionalBitmap`s to the copying constructor would resolve to this more
	 *                       specific one and end up sharing the bitmaps instead of copying them
	 */
	private TransactionalRangePoint(
		long threshold,
		@Nonnull TransactionalBitmap starts,
		@Nonnull TransactionalBitmap ends,
		@Nullable TransactionalBitmap[] repeatedStarts,
		@Nullable TransactionalBitmap[] repeatedEnds,
		boolean adopted
	) {
		this.threshold = threshold;
		this.starts = starts;
		this.ends = ends;
		this.repeatedStarts = repeatedStarts;
		this.repeatedEnds = repeatedEnds;
		this.dirty = new TransactionalBoolean();
	}

	/**
	 * Returns occurrence levels 2, 3, … of the starts: element `i` holds the records whose ranges start at this
	 * threshold at least `i + 2` times. Empty when no record ever started here twice — the common case. The array may
	 * end with empty spare levels: a reader stops at the first empty level, after which every level is empty. The
	 * array must not be modified.
	 *
	 * @return the repeated start levels, never `null`
	 */
	@Nonnull
	public TransactionalBitmap[] getRepeatedStarts() {
		return this.repeatedStarts == null ? NO_REPEATED_LEVELS : this.repeatedStarts;
	}

	/**
	 * Returns occurrence levels 2, 3, … of the ends: element `i` holds the records whose ranges end at this threshold
	 * at least `i + 2` times. Empty when no record ever ended here twice — the common case. The array may end with
	 * empty spare levels: a reader stops at the first empty level, after which every level is empty. The array must
	 * not be modified.
	 *
	 * @return the repeated end levels, never `null`
	 */
	@Nonnull
	public TransactionalBitmap[] getRepeatedEnds() {
		return this.repeatedEnds == null ? NO_REPEATED_LEVELS : this.repeatedEnds;
	}

	/**
	 * Registers one more range of `recordId` starting at this point — the lowest occurrence level not holding the
	 * record yet receives it.
	 *
	 * @param recordId the record whose range starts here
	 * @return this point, or — when every level of the array already holds the record — a new point with a doubled
	 * start-level array, which the caller must store in place of this one
	 */
	@Nonnull
	public TransactionalRangePoint addStart(int recordId) {
		this.dirty.setToTrue();
		if (this.starts.add(recordId)) {
			return this;
		}
		return addRepeated(recordId, true);
	}

	/**
	 * Registers one more range of `recordId` ending at this point — the lowest occurrence level not holding the record
	 * yet receives it.
	 *
	 * @param recordId the record whose range ends here
	 * @return this point, or — when every level of the array already holds the record — a new point with a doubled
	 * end-level array, which the caller must store in place of this one
	 */
	@Nonnull
	public TransactionalRangePoint addEnd(int recordId) {
		this.dirty.setToTrue();
		if (this.ends.add(recordId)) {
			return this;
		}
		return addRepeated(recordId, false);
	}

	/**
	 * Unregisters one range of `recordId` starting at this point — the highest occurrence level holding the record
	 * loses it. A record that does not start here is ignored. Never replaces the point: a level that empties stays as a
	 * spare.
	 *
	 * @param recordId the record whose range no longer starts here
	 */
	public void removeStart(int recordId) {
		remove(recordId, this.starts, this.repeatedStarts);
	}

	/**
	 * Unregisters one range of `recordId` ending at this point — the highest occurrence level holding the record loses
	 * it. A record that does not end here is ignored. Never replaces the point: a level that empties stays as a spare.
	 *
	 * @param recordId the record whose range no longer ends here
	 */
	public void removeEnd(int recordId) {
		remove(recordId, this.ends, this.repeatedEnds);
	}

	/**
	 * Compares this range point for all values — the threshold and every non-empty occurrence level of starts and
	 * ends. Spare levels do not count.
	 */
	public boolean deepEquals(@Nullable Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final TransactionalRangePoint that = (TransactionalRangePoint) o;
		return this.threshold == that.threshold
			&& Objects.equals(this.starts, that.starts)
			&& Objects.equals(this.ends, that.ends)
			&& levelsEqual(this.repeatedStarts, that.repeatedStarts)
			&& levelsEqual(this.repeatedEnds, that.repeatedEnds);
	}

	/**
	 * Computes a hash over the same content {@link #deepEquals} compares — the threshold and every non-empty
	 * occurrence level. It is not {@link #hashCode()}, which follows `equals` and covers the threshold only.
	 *
	 * @return the content hash
	 */
	public int deepHashCode() {
		int result = Long.hashCode(this.threshold);
		result = 31 * result + this.starts.hashCode();
		result = 31 * result + this.ends.hashCode();
		result = hashLevels(result, this.repeatedStarts);
		// the separator keeps a repeated start level from hashing like a repeated end level
		result = 31 * result + 1;
		return hashLevels(result, this.repeatedEnds);
	}

	/**
	 * Returns the heap this point occupies, in bytes — its own object, its dirty flag and every record bitmap of every
	 * occurrence level, spare levels and the arrays holding them included.
	 *
	 * Every bitmap is charged in full. Every constructor builds them fresh, including the ones that take existing
	 * record sets and copy them into new {@link TransactionalBitmap} instances, so this point is the sole owner of
	 * each — there is no path by which it comes to share one with the caller that supplied the records. The private
	 * constructor that takes bitmaps over without copying is handed only bitmaps built for the new point a moment
	 * before.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// threshold + the dirty / starts / ends / repeatedStarts / repeatedEnds slots
		return layout.sizeOfObject(Long.BYTES + 5L * layout.referenceSize())
			+ this.dirty.getHeapSizeInBytes()
			+ this.starts.getHeapSizeInBytes()
			+ this.ends.getHeapSizeInBytes()
			+ levelsHeapSize(layout, this.repeatedStarts)
			+ levelsHeapSize(layout, this.repeatedEnds);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(64);
		sb.append("TransactionalRangePoint{threshold=").append(this.threshold)
			.append(", starts=").append(this.starts)
			.append(", ends=").append(this.ends);
		if (this.repeatedStarts != null) {
			sb.append(", repeatedStarts=").append(Arrays.toString(this.repeatedStarts));
		}
		if (this.repeatedEnds != null) {
			sb.append(", repeatedEnds=").append(Arrays.toString(this.repeatedEnds));
		}
		return sb.append('}').toString();
	}

	@Nonnull
	@Override
	public Collection<TransactionalLayerCreator<?>> getMaintainedTransactionalCreators() {
		if (this.repeatedStarts == null && this.repeatedEnds == null) {
			return Arrays.asList(
				this.dirty, this.starts, this.ends
			);
		}
		final List<TransactionalLayerCreator<?>> creators = new ArrayList<>(
			3 + getRepeatedStarts().length + getRepeatedEnds().length
		);
		creators.add(this.dirty);
		creators.add(this.starts);
		creators.add(this.ends);
		creators.addAll(Arrays.asList(getRepeatedStarts()));
		creators.addAll(Arrays.asList(getRepeatedEnds()));
		return creators;
	}

	@Nonnull
	@Override
	public TransactionalRangePoint createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		final boolean isDirty = transactionalLayer
			.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			// every level is merged - spare ones too, so no layer is left behind - and the copying constructor keeps
			// the non-empty prefix only
			return new TransactionalRangePoint(
				this.threshold,
				transactionalLayer.getStateCopyWithCommittedChanges(this.starts),
				transactionalLayer.getStateCopyWithCommittedChanges(this.ends),
				mergeLevels(transactionalLayer, this.repeatedStarts),
				mergeLevels(transactionalLayer, this.repeatedEnds)
			);
		} else {
			return this;
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.dirty.removeLayer(transactionalLayer);
		this.starts.removeLayer(transactionalLayer);
		this.ends.removeLayer(transactionalLayer);
		for (final TransactionalBitmap level : getRepeatedStarts()) {
			level.removeLayer(transactionalLayer);
		}
		for (final TransactionalBitmap level : getRepeatedEnds()) {
			level.removeLayer(transactionalLayer);
		}
	}

	@Nonnull
	@Override
	public TransactionalRangePoint makeClone() {
		return new TransactionalRangePoint(
			this.threshold,
			getStarts(),
			getEnds(),
			this.repeatedStarts,
			this.repeatedEnds
		);
	}

	/**
	 * Places a repeated occurrence of `recordId` into the lowest repeated level not holding it yet, or mints a new
	 * point with a doubled level array when every level holds it. Level 1 is known to hold the record already.
	 *
	 * @param recordId  the record whose range starts or ends here once more
	 * @param fromStart `true` for the start levels, `false` for the end levels
	 * @return this point, or its replacement with the grown level array
	 */
	@Nonnull
	private TransactionalRangePoint addRepeated(int recordId, boolean fromStart) {
		final TransactionalBitmap[] levels = fromStart ? this.repeatedStarts : this.repeatedEnds;
		final int holding = levelsHolding(levels, recordId);
		if (levels != null && holding < levels.length) {
			levels[holding].add(recordId);
			return this;
		}
		// every level holds the record already - only a new point can carry a longer array; the other side keeps its
		// capacity, spare levels included, so each side doubles on its own account
		return fromStart ?
			new TransactionalRangePoint(
				this.threshold,
				new TransactionalBitmap(this.starts), new TransactionalBitmap(this.ends),
				copyLevelsWithOneMore(this.repeatedStarts, recordId), copyLevelsKeepingCapacity(this.repeatedEnds),
				true
			) :
			new TransactionalRangePoint(
				this.threshold,
				new TransactionalBitmap(this.starts), new TransactionalBitmap(this.ends),
				copyLevelsKeepingCapacity(this.repeatedStarts), copyLevelsWithOneMore(this.repeatedEnds, recordId),
				true
			);
	}

	/**
	 * Removes one occurrence of `recordId` from the highest level holding it.
	 *
	 * @param recordId the record whose range no longer starts or ends here
	 * @param levelOne occurrence level 1 of the side the occurrence is removed from
	 * @param levels   the repeated levels of that side, may be `null`
	 */
	private void remove(int recordId, @Nonnull TransactionalBitmap levelOne, @Nullable TransactionalBitmap[] levels) {
		final int holding = levelsHolding(levels, recordId);
		if (holding > 0) {
			//noinspection DataFlowIssue - a positive count implies the levels exist
			levels[holding - 1].remove(recordId);
		} else {
			levelOne.remove(recordId);
		}
		this.dirty.setToTrue();
	}

}
