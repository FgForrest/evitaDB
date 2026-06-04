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

import io.evitadb.api.query.filter.AttributeInRange;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.DisentangleFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.JoinFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalLongBPlusTree;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.utils.Assert;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * RangeIndex has the following structure:
 *
 * [long - threshold 1]: starts [ recordId1, recordId2 ], ends []
 * [long - threshold 2]: starts [ recordId3 ], ends []
 * [long - threshold 3]: starts [], ends [ recordId3 ]
 * [long - threshold 4]: starts [], ends [ recordId1, recordId2 ]
 *
 * And allows to compute which record ids are valid at the certain point (or at the virtual point between points),
 * which records are valid from certain point forwards, which records are valid until certain point and so on.
 * See methods on this data structure.
 *
 * Beware - single record id may have multiple ranges in this data structure, but client code must ensure that
 * from/to combinations for the record are unique - i.e. that the single record id doesn't share same border.
 * Avoid following combinations for ranges of the SAME record:
 *
 * (2-10)(10-20) - ten is shared
 * (2-20)(2-40) - second is shared
 * (2-10)(5-10) - ten is shared
 *
 * This situation will lead to problems when such record is removed because on removal it removes the shared border
 * information for all ranges.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class RangeIndex implements VoidTransactionMemoryProducer<RangeIndex>, Serializable {
	@Serial private static final long serialVersionUID = -6580254774575839798L;

	/**
	 * Wrapper that adapts a committed value coming out of the B+ tree commit into a {@link TransactionalRangePoint}.
	 * A {@link TransactionalRangePoint} merges to another {@link TransactionalRangePoint} (its
	 * {@link TransactionalRangePoint#createCopyWithMergedTransactionalMemory} returns a {@link TransactionalRangePoint}),
	 * therefore an identity cast is sufficient here — unlike value types that merge to a different class.
	 */
	private static final Function<Object, TransactionalRangePoint> RANGE_POINT_WRAPPER =
		o -> (TransactionalRangePoint) o;

	/**
	 * Leaf block size of the threshold → range-point tree. Unlike the comparator-keyed inverted index, this tree is
	 * `long`-keyed with a single-reference value, so an in-leaf insert is a cheap primitive/reference arraycopy and there
	 * is no read-vs-write block-size conflict. Benchmarking (`RangeIndexBlockSizeBenchmark`; results and analysis under
	 * `documentation/performance/individual/RangeIndexBlockSizeBenchmark/`) shows every access pattern — point lookup,
	 * bounded range, full sweep, and both write paths (commit, bulk load) — improves (or is flat within noise) as the
	 * block grows, all the way to `512`: versus the tree default `64` it cuts range and full-sweep latency by ~30% at
	 * scale with no write cost, and the gains have flattened by `512`. It is a runtime-only parameter — it does not affect
	 * the persisted form, which is rebuilt into the tree on load.
	 */
	private static final int VALUE_BLOCK_SIZE = 512;
	private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
	private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);

	/**
	 * Predicate will return true if point has no sense because it contains no data (no starts, no ends). Predicate will
	 * never return true for full range border points (MIN/MAX) even if empty.
	 */
	private static final Predicate<TransactionalRangePoint> INT_RANGE_POINT_OBSOLETE_CHECKER =
		point -> point.getThreshold() != Long.MIN_VALUE && point.getThreshold() != Long.MAX_VALUE && point.getStarts().isEmpty() && point.getEnds().isEmpty();

	/**
	 * Contains range information keyed by {@link RangePoint#getThreshold()} in ascending order. At least two points are
	 * always present for the `Long.MIN_VALUE` and `Long.MAX_VALUE` border points of the range. A write touches only the
	 * affected leaf and its ancestors (path-copying) instead of reallocating the whole structure.
	 */
	final TransactionalLongBPlusTree<TransactionalRangePoint> ranges;

	/**
	 * Memoized result for the "valid at now" query produced by {@link #getRecordsValidNowFormula(long)}.
	 * Read only outside transactions; nulled on non-transactional mutation. Across commits, a fresh
	 * {@link RangeIndex} is produced by {@link #createCopyWithMergedTransactionalMemory} so this field
	 * starts {@code null} automatically.
	 */
	@Nullable transient volatile EnvelopingNowCache envelopingNowCache;

	/**
	 * Method collects all starts and ends from the range points between `fromIndex` and `toIndex` (inclusive) of the
	 * passed materialized snapshot and returns them collected in a simple DTO.
	 */
	/**
	 * Collects all starts and ends of every range point currently held in the passed index (transactional view) into a
	 * simple DTO. Intended for tests that need to assert the full internal state of the index.
	 */
	@Nonnull
	static StartsEndsDTO collectAllStartsAndEnds(@Nonnull RangeIndex index) {
		final StartsEndsDTO result = new StartsEndsDTO();
		final Iterator<TransactionalRangePoint> it = index.ranges.valueIterator();
		while (it.hasNext()) {
			final RangePoint<?> rangePoint = it.next();
			result.addStart(rangePoint.getStarts());
			result.addEnd(rangePoint.getEnds());
		}
		return result;
	}

	@Nonnull
	static StartsEndsDTO collectsStartsAndEnds(int fromIndex, int toIndex, @Nonnull TransactionalRangePoint[] ranges) {
		final StartsEndsDTO result = new StartsEndsDTO();
		for (int i = fromIndex; i <= toIndex; i++) {
			final RangePoint<?> rangePoint = ranges[i];
			result.addStart(rangePoint.getStarts());
			result.addEnd(rangePoint.getEnds());
		}
		return result;
	}

	/**
	 * Materializes the transactional view of all range points into a positionally addressable array, ordered ascending
	 * by threshold. Used by the {@link RangeLookup}-based queries which reproduce the original positional index math; the
	 * border sentinels guarantee at least two entries. This is the same O(N) scan the array-backed implementation
	 * performed for these full-range queries.
	 */
	@Nonnull
	private TransactionalRangePoint[] materializeRanges() {
		final List<TransactionalRangePoint> result = new ArrayList<>(this.ranges.size());
		final Iterator<TransactionalRangePoint> it = this.ranges.valueIterator();
		while (it.hasNext()) {
			result.add(it.next());
		}
		return result.toArray(new TransactionalRangePoint[0]);
	}

	/**
	 * Method throws {@link IllegalArgumentException} when ranges are not in ascending order or contains duplicate threshold.
	 */
	private static void assertThresholdIsMonotonic(@Nonnull RangePoint<?>[] ranges) {
		Long previous = null;
		for (RangePoint<?> point : ranges) {
			Assert.isTrue(
				previous == null || previous < point.getThreshold(),
				"Range values are not monotonic - conflicting values: " + previous + ", " + point.getThreshold()
			);
			previous = point.getThreshold();
		}
	}

	/**
	 * Creates a fresh tree carrying only the `Long.MIN_VALUE` / `Long.MAX_VALUE` border sentinels.
	 */
	@Nonnull
	private static TransactionalLongBPlusTree<TransactionalRangePoint> createEmptyTree() {
		final TransactionalLongBPlusTree<TransactionalRangePoint> tree = new TransactionalLongBPlusTree<>(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			TransactionalRangePoint.class, RANGE_POINT_WRAPPER
		);
		tree.insert(Long.MIN_VALUE, new TransactionalRangePoint(Long.MIN_VALUE));
		tree.insert(Long.MAX_VALUE, new TransactionalRangePoint(Long.MAX_VALUE));
		return tree;
	}

	public RangeIndex(@Nonnull TransactionalRangePoint[] ranges) {
		Assert.isTrue(ranges.length >= 2, "At least two ranges are expected!");
		Assert.isTrue(ranges[0].getThreshold() == Long.MIN_VALUE, "First range should have threshold Long.MIN_VALUE!");
		Assert.isTrue(ranges[ranges.length - 1].getThreshold() == Long.MAX_VALUE, "Last range should have threshold Long.MAX_VALUE!");
		assertThresholdIsMonotonic(ranges);
		final TransactionalLongBPlusTree<TransactionalRangePoint> tree = new TransactionalLongBPlusTree<>(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			TransactionalRangePoint.class, RANGE_POINT_WRAPPER
		);
		// rebuild the tree from the deserialized snapshot by inserting all points (thresholds are unique & monotonic)
		for (final TransactionalRangePoint point : ranges) {
			tree.insert(point.getThreshold(), point);
		}
		this.ranges = tree;
	}

	public RangeIndex() {
		this.ranges = createEmptyTree();
	}

	public RangeIndex(long from, long to, @Nonnull int[] recordIds) {
		this.ranges = createEmptyTree();
		for (int recordId : recordIds) {
			addRecord(from, to, recordId);
		}
	}

	/**
	 * Private constructor used by {@link #createCopyWithMergedTransactionalMemory} to wrap an already committed tree.
	 *
	 * @param committedTree the tree obtained from the committed transactional state
	 */
	private RangeIndex(@Nonnull TransactionalLongBPlusTree<TransactionalRangePoint> committedTree) {
		this.ranges = committedTree;
	}

	/**
	 * Returns all ranges registered in this index, ordered ascending by threshold.
	 */
	@Nonnull
	public RangePoint<?>[] getRanges() {
		//TODO JNO (#760): materializes the whole range-point array on every commit only to feed the
		// serialization route; remove once StorageParts become granular (issue #760 part B — persist
		// only the changed parts of the index instead of rewriting the entire index per transaction).
		final List<RangePoint<?>> result = new ArrayList<>(this.ranges.size());
		final Iterator<TransactionalRangePoint> it = this.ranges.valueIterator();
		while (it.hasNext()) {
			result.add(it.next());
		}
		return result.toArray(new RangePoint<?>[0]);
	}

	/**
	 * Returns a transaction-aware iterator over the sorted range points held in this index. The iterator walks the
	 * transactional view so callers inside an open transaction observe the in-progress state.
	 * Intended for range-aware histogram sweeps that must respect transactional layering.
	 */
	@Nonnull
	public Iterator<TransactionalRangePoint> rangesIterator() {
		return this.ranges.valueIterator();
	}

	/**
	 * Returns the number of {@link TransactionalRangePoint}s currently held in this index, including the two
	 * `Long.MIN_VALUE`/`Long.MAX_VALUE` sentinels that the index always carries. Intended as a cheap upper-bound
	 * hint for callers that need to pre-size a buffer before walking {@link #rangesIterator()} (e.g. the range
	 * histogram sweep in `FilterIndex.getRangeHistogramOfAllRecords`).
	 *
	 * Outside an open transaction the result is `O(1)` — it reads the committed delegate's `length` directly.
	 * Inside an open transaction this forces a merge of transactional changes to compute the effective length,
	 * which is `O(N)`; callers should fall back to a constant hint on the transactional path.
	 */
	public int getRangePointCount() {
		return this.ranges.size();
	}

	/**
	 * Adds new record with the interval from/to to the range. The updater mutates and returns the SAME
	 * {@link TransactionalRangePoint} instance (never swaps it) so the value's transactional diff layer is preserved.
	 */
	public void addRecord(long from, long to, int recordId) {
		this.ranges.upsert(
			from,
			point -> {
				if (point == null) {
					return new TransactionalRangePoint(from, new BaseBitmap(recordId), EmptyBitmap.INSTANCE);
				}
				point.addStart(recordId);
				return point;
			}
		);
		this.ranges.upsert(
			to,
			point -> {
				if (point == null) {
					return new TransactionalRangePoint(to, EmptyBitmap.INSTANCE, new BaseBitmap(recordId));
				}
				point.addEnd(recordId);
				return point;
			}
		);
		if (!Transaction.isTransactionAvailable()) {
			this.envelopingNowCache = null;
		}
	}

	/**
	 * Removes record with the interval from/to from the range. Each affected point is mutated in place; once a point
	 * becomes obsolete (no starts, no ends) and is not a border sentinel it is deleted from the tree, which releases
	 * its transactional layer.
	 */
	public void removeRecord(long start, long end, int recordId) {
		removeFromPoint(start, recordId, true);
		removeFromPoint(end, recordId, false);
		if (!Transaction.isTransactionAvailable()) {
			this.envelopingNowCache = null;
		}
	}

	/**
	 * Removes the passed `recordId` from the start or end bitmap of the point at the given `threshold` (mutating it in
	 * place) and deletes the point afterwards when it became obsolete and is not a border sentinel.
	 *
	 * @param threshold the threshold of the point to mutate
	 * @param recordId  the record id to remove
	 * @param fromStart `true` to remove from the start bitmap, `false` to remove from the end bitmap
	 */
	private void removeFromPoint(long threshold, int recordId, boolean fromStart) {
		final TransactionalRangePoint point = this.ranges.search(threshold).orElse(null);
		if (point == null) {
			return;
		}
		if (fromStart) {
			point.removeStarts(new int[]{recordId});
		} else {
			point.removeEnds(new int[]{recordId});
		}
		if (INT_RANGE_POINT_OBSOLETE_CHECKER.test(point)) {
			this.ranges.delete(threshold);
		}
	}

	/**
	 * Returns true if the range contains passed record id anywhere in its {@link #ranges}.
	 */
	public boolean contains(int recordId) {
		final Iterator<TransactionalRangePoint> it = this.ranges.valueIterator();
		while (it.hasNext()) {
			final TransactionalRangePoint point = it.next();
			if (point.getStarts().contains(recordId)) {
				return true;
			}
			if (point.getEnds().contains(recordId)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns formula that computes records that are valid since the passed point = threshold in range (inclusive).
	 * This method handles even multiple validity spans for single record id providing that they don't overlap.
	 * The computation is based on starts and end of their validity ranges. Record is valid when there is single
	 * end threshold and not even single start for the same record.
	 *
	 * We also need to avoid situation when there is another full range after the actual one. This situation is solved
	 * by combining {@link JoinFormula} - which is something like OR join that leaves duplicate record ids in place.
	 * After that {@link DisentangleFormula} excludes all record ids that are in both bitmaps on the same place. This
	 * operation will exclude all ranges that both start and ends after examined range.
	 */
	@Nonnull
	public Formula getRecordsFrom(long threshold) {
		// the array implementation collected all points from the first key >= threshold to the end; this is exactly
		// the forward stream of points whose key is greater than or equal to the threshold
		final StartsEndsDTO startsEndsDTO = new StartsEndsDTO();
		final Iterator<TransactionalRangePoint> it = this.ranges.greaterOrEqualValueIterator(threshold);
		while (it.hasNext()) {
			final TransactionalRangePoint point = it.next();
			startsEndsDTO.addStart(point.getStarts());
			startsEndsDTO.addEnd(point.getEnds());
		}
		return createDisentangleFormulaIfNecessary(
			getId(), startsEndsDTO.getRangeEndsAsBitmapArray(),
			startsEndsDTO.getRangeStartsAsBitmapArray()
		);
	}

	/**
	 * Returns formula that computes records that are valid until passed point = threshold in range (inclusive).
	 * This method handles even multiple validity spans for single record id providing that they don't overlap.
	 * The computation is based on starts and end of their validity ranges. Record is valid when there is single
	 * start threshold and not even single end for the same record.
	 *
	 * We also need to avoid situation when there is another full range before the actual one. This situation is solved
	 * by combining {@link JoinFormula} - which is something like OR join that leaves duplicate record ids in place.
	 * After that {@link DisentangleFormula} excludes all record ids that are in both bitmaps on the same place. This
	 * operation will exclude all ranges that both start and ends after examined range.
	 */
	@Nonnull
	public Formula getRecordsTo(long threshold) {
		// the array implementation collected all points from the start up to (and including when present) the threshold;
		// this is exactly the forward stream of points whose key is lesser than or equal to the threshold
		final StartsEndsDTO startsEndsDTO = new StartsEndsDTO();
		final Iterator<TransactionalLongBPlusTree.Entry<TransactionalRangePoint>> it = this.ranges.entryIterator();
		while (it.hasNext()) {
			final TransactionalLongBPlusTree.Entry<TransactionalRangePoint> entry = it.next();
			if (entry.key() > threshold) {
				// keys are ascending - everything that follows is past the threshold
				break;
			}
			final TransactionalRangePoint point = entry.value();
			startsEndsDTO.addStart(point.getStarts());
			startsEndsDTO.addEnd(point.getEnds());
		}
		return createDisentangleFormulaIfNecessary(getId(), startsEndsDTO.getRangeStartsAsBitmapArray(), startsEndsDTO.getRangeEndsAsBitmapArray());
	}

	/**
	 * Method returns formula that computes all records which range fully envelopes passed `threshold`.
	 *
	 * This method supports multiple ranges for the same id.
	 *
	 * Method finds all records which start range is before `threshold` and end range is after `threshold` argument.
	 * Records starting or ending exactly with `threshold` are part of the result.
	 */
	@Nonnull
	public Formula getRecordsEnvelopingInclusive(long threshold) {
		final TransactionalRangePoint[] points = materializeRanges();
		final RangeLookup rangeLookup = new RangeLookup(points, threshold, threshold);

		final int startIndex = rangeLookup.isStartThresholdFound() ? rangeLookup.getStartIndex() : rangeLookup.getStartIndex() - 1;
		final int endIndex = rangeLookup.isEndThresholdFound() ? rangeLookup.getEndIndex() + 1 : rangeLookup.getEndIndex();

		final StartsEndsDTO before = startIndex >= 0 ?
			collectsStartsAndEnds(0, startIndex, points) : new StartsEndsDTO();
		final StartsEndsDTO after = endIndex < points.length ?
			collectsStartsAndEnds(endIndex, points.length - 1, points) : new StartsEndsDTO();

		final AndFormula envelopeFormula = new AndFormula(
			createDisentangleFormulaIfNecessary(getId(), before.getRangeStartsAsBitmapArray(), before.getRangeEndsAsBitmapArray()),
			createDisentangleFormulaIfNecessary(getId(), after.getRangeEndsAsBitmapArray(), after.getRangeStartsAsBitmapArray())
		);

		// both should be true or false since we have same threshold
		if (rangeLookup.isStartThresholdFound() && rangeLookup.isEndThresholdFound()) {
			Assert.isPremiseValid(
				rangeLookup.getStartIndex() == rangeLookup.getEndIndex(),
				"Premise is invalid!"
			);
			final Bitmap starts = points[rangeLookup.getStartIndex()].getStarts();
			final Bitmap ends = points[rangeLookup.getEndIndex()].getEnds();

			if (starts.isEmpty() && ends.isEmpty()) {
				return envelopeFormula;
			} else {
				return FormulaFactory.or(
					envelopeFormula,
					starts.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(starts),
					ends.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(ends)
				);
			}
		} else {
			return envelopeFormula;
		}
	}

	/**
	 * Cache-aware variant of {@link #getRecordsEnvelopingInclusive(long)} intended for the
	 * {@code attributeInRangeNow} (suffix-{@code now}) variant of
	 * {@link AttributeInRange}. The materialized {@link Bitmap} is memoized for
	 * the interval of {@code now} values that yield the same result — either the open interval between two
	 * adjacent thresholds (miss case) or the exact threshold itself (hit case).
	 *
	 * Bypasses the cache entirely when called inside a transaction, since the transactional view of the
	 * underlying {@code ranges} array may differ from the committed view that backs the cache.
	 *
	 * @param now epoch-second value of the moment to evaluate (typically
	 *            {@code request.getAlignedNow().toEpochSecond()})
	 * @return formula computing the records whose validity range envelopes {@code now}
	 */
	@Nonnull
	public Formula getRecordsValidNowFormula(long now) {
		if (Transaction.isTransactionAvailable()) {
			return getRecordsEnvelopingInclusive(now);
		}
		final EnvelopingNowCache snapshot = this.envelopingNowCache;
		if (snapshot != null && snapshot.validFromInclusive() <= now && now <= snapshot.validToInclusive()) {
			return snapshot.result().isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(snapshot.result());
		}
		final long validFrom;
		final long validTo;
		if (this.ranges.search(now).isPresent()) {
			validFrom = now;
			validTo = now;
		} else {
			// `now` falls between two thresholds; the border sentinels guarantee both neighbors exist
			validFrom = this.ranges.lesserOrEqualKeyIterator(now).nextLong() + 1;
			validTo = this.ranges.greaterOrEqualKeyIterator(now).nextLong() - 1;
		}
		final Bitmap materialized = getRecordsEnvelopingInclusive(now).compute();
		this.envelopingNowCache = new EnvelopingNowCache(validFrom, validTo, materialized);
		return materialized.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(materialized);
	}

	/**
	 * Creates a DisentangleFormula if necessary based on the given id and bitmap arrays.
	 * If the left or right bitmap array produces effectively empty bitmap, DisentangleFormula is not created and
	 * more optimized result is returned.
	 *
	 * @param id     the id for the DisentangleFormula
	 * @param left   the left bitmap array to be used for the DisentangleFormula
	 * @param right  the right bitmap array to be used for the DisentangleFormula
	 * @return a Formula object representing the DisentangleFormula if necessary
	 */
	@Nonnull
	private static Formula createDisentangleFormulaIfNecessary(long id, @Nonnull Bitmap[] left, @Nonnull Bitmap[] right) {
		final Formula leftFormula = createJoinFormulaIfNecessary(id, left);
		final Formula rightFormula = createJoinFormulaIfNecessary(id, right);
		if (leftFormula instanceof EmptyFormula) {
			return EmptyFormula.INSTANCE;
		} else if (rightFormula instanceof EmptyFormula) {
			if (leftFormula instanceof ConstantFormula) {
				return leftFormula;
			} else if (leftFormula instanceof JoinFormula joinFormula) {
				return joinFormula.getAsOrFormula();
			} else {
				throw new GenericEvitaInternalError("Unexpected formula type: " + leftFormula.getClass().getSimpleName() + "!");
			}
		} else {
			return new DisentangleFormula(leftFormula, rightFormula);
		}
	}

	/**
	 * Creates a join formula if necessary based on the given id and bitmap array.
	 * If the bitmap array contains only one bitmap, a ConstantFormula is created with that bitmap.
	 * If the bitmap array is empty, an EmptyFormula is returned.
	 * Otherwise, a JoinFormula is created with the given id and filtered bitmaps.
	 *
	 * @param id     the id for the JoinFormula
	 * @param bitmaps the bitmap array to be filtered and used for the JoinFormula
	 * @return a Formula object representing the join formula if necessary
	 */
	@Nonnull
	private static Formula createJoinFormulaIfNecessary(long id, @Nonnull Bitmap[] bitmaps) {
		final Bitmap[] filteredBitmaps = Arrays.stream(bitmaps)
			.filter(it -> !(it instanceof EmptyBitmap))
			.toArray(Bitmap[]::new);
		if (filteredBitmaps.length == 0) {
			return EmptyFormula.INSTANCE;
		} else if (filteredBitmaps.length == 1) {
			return new ConstantFormula(filteredBitmaps[0]);
		} else {
			return new JoinFormula(id, filteredBitmaps);
		}
	}

	/**
	 * Method returns formula that computes all records which range overlap (have points in common)	passed range with
	 * `from` and `to` bounds.
	 *
	 * Method finds all records which start range is before `from` and ends after or equal to `from` or
	 * which ends after `from` but before or equal to `to`.
	 */
	@Nonnull
	public Formula getRecordsWithRangesOverlapping(long from, long to) {
		final TransactionalRangePoint[] points = materializeRanges();
		final RangeLookup rangeLookup = new RangeLookup(points, from, to);
		final StartsEndsDTO between = collectsStartsAndEnds(rangeLookup.getStartIndex(), rangeLookup.getEndIndex(), points);
		final StartsEndsDTO before = collectsStartsAndEnds(0, Math.min(rangeLookup.getStartIndex(), rangeLookup.getEndIndex()), points);
		final StartsEndsDTO after = collectsStartsAndEnds(Math.max(rangeLookup.getStartIndex(), rangeLookup.getEndIndex()), points.length - 1, points);

		return new OrFormula(
			between.getRangeStarts(),
			between.getRangeEnds(),
			new AndFormula(
				createDisentangleFormulaIfNecessary(getId(), before.getRangeStartsAsBitmapArray(), before.getRangeEndsAsBitmapArray()),
				createDisentangleFormulaIfNecessary(getId(), after.getRangeEndsAsBitmapArray(), after.getRangeStartsAsBitmapArray())
			)
		);
	}

	/*
		TRANSACTIONAL MEMORY implementation
	 */

	/**
	 * Returns record ids of all records in this index.
	 */
	@Nonnull
	public Bitmap getAllRecords() {
		final StartsEndsDTO all = new StartsEndsDTO();
		final Iterator<TransactionalRangePoint> it = this.ranges.valueIterator();
		while (it.hasNext()) {
			final TransactionalRangePoint point = it.next();
			all.addStart(point.getStarts());
			all.addEnd(point.getEnds());
		}
		return new AndFormula(all.getRangeStarts(), all.getRangeEnds()).compute();
	}

	/**
	 * Returns count of record ids in range index.
	 */
	public int size() {
		return getAllRecords().size();
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	@Override
	public RangeIndex createCopyWithMergedTransactionalMemory(Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		return new RangeIndex(transactionalLayer.getStateCopyWithCommittedChanges(this.ranges));
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.ranges.removeLayer(transactionalLayer);
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}

	/**
	 * Content-based equality over the logical `(threshold, starts, ends)` sequence of all range points. The transient
	 * {@link #envelopingNowCache} is intentionally excluded.
	 */
	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		final RangeIndex that = (RangeIndex) o;
		final Iterator<TransactionalRangePoint> thisIt = this.ranges.valueIterator();
		final Iterator<TransactionalRangePoint> thatIt = that.ranges.valueIterator();
		while (thisIt.hasNext() && thatIt.hasNext()) {
			final TransactionalRangePoint thisPoint = thisIt.next();
			final TransactionalRangePoint thatPoint = thatIt.next();
			if (thisPoint.getThreshold() != thatPoint.getThreshold()
				|| !Objects.equals(thisPoint.getStarts(), thatPoint.getStarts())
				|| !Objects.equals(thisPoint.getEnds(), thatPoint.getEnds())) {
				return false;
			}
		}
		return thisIt.hasNext() == thatIt.hasNext();
	}

	/**
	 * Content-based hash code over the logical `(threshold, starts, ends)` sequence of all range points.
	 */
	@Override
	public int hashCode() {
		int result = 1;
		final Iterator<TransactionalRangePoint> it = this.ranges.valueIterator();
		while (it.hasNext()) {
			final TransactionalRangePoint point = it.next();
			result = 31 * result + Long.hashCode(point.getThreshold());
			result = 31 * result + point.getStarts().hashCode();
			result = 31 * result + point.getEnds().hashCode();
		}
		return result;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(128);
		sb.append("RangeIndex{ranges=[");
		final Iterator<TransactionalRangePoint> it = this.ranges.valueIterator();
		boolean first = true;
		while (it.hasNext()) {
			final TransactionalRangePoint point = it.next();
			if (!first) {
				sb.append(", ");
			}
			sb.append(point);
			first = false;
		}
		sb.append("]}");
		return sb.toString();
	}

	/**
	 * DTO for passing sets of bitmap starts and ends from index structure to computational logic.
	 */
	@NoArgsConstructor
	static class StartsEndsDTO {
		private static final Formula[] EMPTY_ARRAY = Formula.EMPTY_FORMULA_ARRAY;
		private final List<Formula> rangeStarts = new LinkedList<>();
		private final List<Formula> rangeEnds = new LinkedList<>();

		StartsEndsDTO(@Nonnull List<Bitmap> starts, @Nonnull List<Bitmap> ends) {
			for (Bitmap start : starts) {
				addStart(start);
			}
			for (Bitmap end : ends) {
				addEnd(end);
			}
		}

		/**
		 * Returns formula that computes bitmap of distinct record ids that are present at collected start ranges.
		 */
		@Nonnull
		public Formula getRangeStarts() {
			if (this.rangeStarts.isEmpty()) {
				return EmptyFormula.INSTANCE;
			} else if (this.rangeStarts.size() == 1) {
				return this.rangeStarts.get(0);
			} else {
				return new OrFormula(
					this.rangeStarts.toArray(EMPTY_ARRAY)
				);
			}
		}

		/**
		 * Returns formula that computes bitmap of distinct record ids that are present at collected end ranges.
		 */
		@Nonnull
		public Formula getRangeEnds() {
			if (this.rangeEnds.isEmpty()) {
				return EmptyFormula.INSTANCE;
			} else if (this.rangeEnds.size() == 1) {
				return this.rangeEnds.get(0);
			} else {
				return new OrFormula(
					this.rangeEnds.toArray(EMPTY_ARRAY)
				);
			}
		}

		/**
		 * Returns array of bitmaps of distinct record ids that are present at collected start ranges. All added formulas
		 * so far must be of simple {@link ConstantFormula} type otherwise this method returns {@link IllegalArgumentException}
		 *
		 * @throws IllegalArgumentException when {@link #addStart(Bitmap)} was called with complex formula
		 */
		@Nonnull
		public Bitmap[] getRangeStartsAsBitmapArray() {
			return this.rangeStarts
				.stream()
				.map(it -> {
					if (it instanceof EmptyFormula) {
						return EmptyBitmap.INSTANCE;
					} else {
						Assert.isTrue(it instanceof ConstantFormula, "StartsEndsDTO is expected to contain only ConstantFormula when indistinct values are required. Encountered " + it.getClass());
						return ((ConstantFormula) it).getDelegate();
					}
				})
				.toArray(Bitmap[]::new);
		}

		/**
		 * Returns array of bitmaps of distinct record ids that are present at collected end ranges. All added formulas
		 * so far must be of simple {@link ConstantFormula} type otherwise this method returns {@link IllegalArgumentException}
		 *
		 * @throws IllegalArgumentException when {@link #addEnd(Bitmap)} was called with complex formula
		 */
		@Nonnull
		public Bitmap[] getRangeEndsAsBitmapArray() {
			return this.rangeEnds
				.stream()
				.map(it -> {
					if (it instanceof EmptyFormula) {
						return EmptyBitmap.INSTANCE;
					} else {
						Assert.isTrue(it instanceof ConstantFormula, "StartsEndsDTO is expected to contain only ConstantFormula when indistinct values are required. Encountered " + it.getClass());
						return ((ConstantFormula) it).getDelegate();
					}
				})
				.toArray(Bitmap[]::new);
		}

		/**
		 * Returns true if StartsEndsDTO is contents wise effectively equal to passed one.
		 */
		public boolean effectivelyEquals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			StartsEndsDTO that = (StartsEndsDTO) o;
			final int[][] thisStarts = this.rangeStarts.stream().map(it -> it.compute().getArray()).toArray(int[][]::new);
			final int[][] thatStarts = that.rangeStarts.stream().map(it -> it.compute().getArray()).toArray(int[][]::new);
			if (thisStarts.length != thatStarts.length) {
				return false;
			}
			for (int i = 0; i < thisStarts.length; i++) {
				int[] thisStart = thisStarts[i];
				int[] thatStart = thatStarts[i];
				if (!Arrays.equals(thisStart, thatStart)) {
					return false;
				}
			}
			final int[][] thisEnds = this.rangeEnds.stream().map(it -> it.compute().getArray()).toArray(int[][]::new);
			final int[][] thatEnds = that.rangeEnds.stream().map(it -> it.compute().getArray()).toArray(int[][]::new);
			if (thisEnds.length != thatEnds.length) {
				return false;
			}
			for (int i = 0; i < thisEnds.length; i++) {
				int[] thisEnd = thisEnds[i];
				int[] thatEnd = thatEnds[i];
				if (!Arrays.equals(thisEnd, thatEnd)) {
					return false;
				}
			}
			return true;
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.rangeStarts, this.rangeEnds);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			StartsEndsDTO that = (StartsEndsDTO) o;
			return this.rangeStarts.equals(that.rangeStarts) && this.rangeEnds.equals(that.rangeEnds);
		}

		@Override
		public String toString() {
			final Function<List<Formula>, String> cnv = ints -> ints.stream()
				.map(it -> "[" + it.toString() + "]")
				.collect(Collectors.joining(","));
			return "StartsEndsDTO{" +
				"rangeStarts=" + cnv.apply(this.rangeStarts) +
				", rangeEnds=" + cnv.apply(this.rangeEnds) +
				'}';
		}

		/**
		 * Adds new bitmap as simple {@link ConstantFormula} to the set of start ranges.
		 */
		void addStart(@Nonnull Bitmap starts) {
			if (starts.isEmpty()) {
				this.rangeStarts.add(EmptyFormula.INSTANCE);
			} else {
				this.rangeStarts.add(new ConstantFormula(starts));
			}
		}

		/**
		 * Adds new bitmap as simple {@link ConstantFormula} to the set of end ranges.
		 */
		void addEnd(@Nonnull Bitmap ends) {
			if (ends.isEmpty()) {
				this.rangeEnds.add(EmptyFormula.INSTANCE);
			} else {
				this.rangeEnds.add(new ConstantFormula(ends));
			}
		}
	}

	/**
	 * Memoizes the {@link Bitmap} returned by {@link #getRecordsValidNowFormula(long)} together with the open
	 * (or single-point) interval of {@code now} values that yield the same result. Single-slot: queries naturally
	 * hand progressively larger {@code now} values, and serving the latest one is sufficient.
	 *
	 * @param validFromInclusive smallest {@code now} value that yields this result
	 * @param validToInclusive   largest {@code now} value that yields this result
	 * @param result             materialized bitmap of record ids valid at any {@code now} in the interval
	 */
	record EnvelopingNowCache(long validFromInclusive, long validToInclusive, @Nonnull Bitmap result) {
	}

	/**
	 * Range lookup will find and return positions of the `from` / `to` ranges in the `ranges` array. It computes their
	 * indexes and will provide access to the set of records in form of {@link TransactionalRangePoint} at those indexes
	 * for access to directly assigned records at these bounds.
	 */
	@Data
	static class RangeLookup {
		private final int startIndex;
		private final TransactionalRangePoint startPoint;
		private final int endIndex;
		private final TransactionalRangePoint endPoint;

		RangeLookup(@Nonnull TransactionalRangePoint[] ranges, long from, long to) {
			final int indexFrom = binarySearchThreshold(ranges, from);
			if (indexFrom >= 0) {
				this.startIndex = indexFrom;
				this.startPoint = ranges[indexFrom];
			} else {
				this.startIndex = -1 * (indexFrom) - 1;
				this.startPoint = null;
			}

			if (from == to) {
				this.endIndex = this.startIndex;
				this.endPoint = this.startPoint;
			} else {
				final int indexTo = binarySearchThreshold(ranges, to);
				if (indexTo >= 0) {
					this.endIndex = indexTo;
					this.endPoint = ranges[indexTo];
				} else {
					this.endIndex = -1 * (indexTo) - 2;
					this.endPoint = null;
				}
			}
		}

		/**
		 * Binary search over the ascending-by-threshold `ranges` array reproducing the {@link java.util.Arrays#binarySearch}
		 * contract: returns the index of the matching threshold or `-(insertionPoint) - 1` when not found.
		 *
		 * @param ranges    the range points ordered ascending by threshold
		 * @param threshold the threshold to search for
		 * @return the found index or the negative insertion-point encoding
		 */
		private static int binarySearchThreshold(@Nonnull TransactionalRangePoint[] ranges, long threshold) {
			int low = 0;
			int high = ranges.length - 1;
			while (low <= high) {
				final int mid = (low + high) >>> 1;
				final long midThreshold = ranges[mid].getThreshold();
				if (midThreshold < threshold) {
					low = mid + 1;
				} else if (midThreshold > threshold) {
					high = mid - 1;
				} else {
					return mid;
				}
			}
			return -(low + 1);
		}

		/**
		 * Returns true if start point was found in the index.
		 */
		boolean isStartThresholdFound() {
			return this.startPoint != null;
		}

		/**
		 * Returns true if end point was found in the index.
		 */
		boolean isEndThresholdFound() {
			return this.endPoint != null;
		}

	}

}
