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
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalLongBPlusTree;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.utils.Assert;
import lombok.Data;
import lombok.Getter;
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
		TransactionalRangePoint.class::cast;

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
	 * Local stream key used with {@link #pageStreamRegistry}. A {@code RangeIndex} owns exactly one page stream (its
	 * threshold tree), so a single fixed key suffices; the persisted, globally-unique stream id is a separate concept
	 * resolved store-side from the sub-index identity (see {@code RangeIndexLeafPagePart}), never this value. It is its
	 * OWN registry (and stream `0`) — independent of the sibling {@code InvertedIndex}'s bucket stream — because the two
	 * structures are separate transactional producers that commit (and publish their staged baselines) independently.
	 */
	private static final int RANGE_PAGE_STREAM = 0;

	/**
	 * Predicate will return true if point has no sense because it contains no data (no starts, no ends). Predicate will
	 * never return true for full range border points (MIN/MAX) even if empty.
	 */
	private static final Predicate<TransactionalRangePoint> INT_RANGE_POINT_OBSOLETE_CHECKER =
		point -> point.getThreshold() != Long.MIN_VALUE && point.getThreshold() != Long.MAX_VALUE && point.getStarts().isEmpty() && point.getEnds().isEmpty();

	/**
	 * Unique transactional id for this index instance. Overrides the {@link VoidTransactionMemoryProducer} default
	 * (the constant `1L`) so that a formula-cache token seeded from this id — the `indexTransactionId` of the
	 * {@link JoinFormula}/{@link DisentangleFormula} built by this index's range queries — is UNIQUE per index yet
	 * STABLE across commits that did not touch it: an untouched index is carried forward by reference from
	 * {@link #createCopyWithMergedTransactionalMemory} (preserving its id), while a mutated index becomes a fresh
	 * instance with a fresh id (correctly invalidating dependent cached formulas). With the constant `1L` default the
	 * token never changed across commits, so a cached result over a `> EXCESSIVE_HIGH_CARDINALITY`-bucket range was
	 * never invalidated — the stale-read defect tracked as issue #37. This is a runtime-only field, regenerated on
	 * load — it is never persisted (the persisted form carries no id).
	 */
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * Contains range information keyed by {@link RangePoint#getThreshold()} in ascending order. At least two points are
	 * always present for the `Long.MIN_VALUE` and `Long.MAX_VALUE` border points of the range. A write touches only the
	 * affected leaf and its ancestors (path-copying) instead of reallocating the whole structure.
	 */
	final TransactionalLongBPlusTree<TransactionalRangePoint> ranges;

	/**
	 * Tracks whether this index was mutated within the current transaction. It mirrors
	 * {@link io.evitadb.index.invertedIndex.InvertedIndex}'s dirty flag and serves a single purpose:
	 * {@link #createCopyWithMergedTransactionalMemory} returns `this` (preserving instance identity, and sparing the
	 * full B+ tree rebuild) when the flag is clean, and only rebuilds when it is dirty. Identity preservation lets the
	 * enclosing transactional map structurally share an untouched range structure across commits.
	 */
	private final TransactionalBoolean dirty;

	/**
	 * Owner-resident page bookkeeping for the granular FilterIndex storage layout: the advance-only
	 * `pageSequence` allocator, the explicit high-water and the `pageSequence -> nodeId` change-detection baseline of this
	 * index's threshold tree. It lives OUTSIDE transactional memory and is carried BY REFERENCE through
	 * {@link #createCopyWithMergedTransactionalMemory} so the surviving committed owner keeps the allocator and baseline
	 * across commits (the discarded transactional copy never has its own). It is consulted only on the single-writer
	 * flush/commit path. This is the range counterpart of the {@code InvertedIndex}'s bucket-stream registry — a SEPARATE
	 * instance, because the two structures commit independently.
	 */
	@Nonnull @Getter private final PageStreamRegistry pageStreamRegistry;

	/**
	 * Memoized result for the "valid at now" query produced by {@link #getRecordsValidNowFormula(long)}.
	 * Read only outside transactions; nulled on non-transactional mutation. A mutated {@link RangeIndex} produces a
	 * fresh instance at commit (so this field starts {@code null} automatically); an untouched one keeps its identity
	 * and its cache, which stays valid because the underlying data is unchanged.
	 */
	@Nullable transient volatile EnvelopingNowCache envelopingNowCache;

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

	/**
	 * Collects all starts and ends from the range points between `fromIndex` and `toIndex` (inclusive) of the passed
	 * materialized snapshot array and returns them collected in a simple DTO.
	 */
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
	 * Creates a fresh, empty threshold tree carrying NO points — not even the border sentinels. Used as the building
	 * block for the boundary-stable page reload ({@link #fromPersistedPages}), which seeds each leaf tree directly from a
	 * persisted page (the sentinels live in the first / last pages) and must not inject duplicates.
	 */
	@Nonnull
	private static TransactionalLongBPlusTree<TransactionalRangePoint> createBareTree() {
		return new TransactionalLongBPlusTree<>(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			TransactionalRangePoint.class, RANGE_POINT_WRAPPER
		);
	}

	/**
	 * Creates a fresh tree carrying only the `Long.MIN_VALUE` / `Long.MAX_VALUE` border sentinels.
	 */
	@Nonnull
	private static TransactionalLongBPlusTree<TransactionalRangePoint> createEmptyTree() {
		final TransactionalLongBPlusTree<TransactionalRangePoint> tree = createBareTree();
		tree.insert(Long.MIN_VALUE, new TransactionalRangePoint(Long.MIN_VALUE));
		tree.insert(Long.MAX_VALUE, new TransactionalRangePoint(Long.MAX_VALUE));
		return tree;
	}

	public RangeIndex(@Nonnull TransactionalRangePoint[] ranges) {
		Assert.isTrue(ranges.length >= 2, "At least two ranges are expected!");
		Assert.isTrue(ranges[0].getThreshold() == Long.MIN_VALUE, "First range should have threshold Long.MIN_VALUE!");
		Assert.isTrue(ranges[ranges.length - 1].getThreshold() == Long.MAX_VALUE, "Last range should have threshold Long.MAX_VALUE!");
		assertThresholdIsMonotonic(ranges);
		final TransactionalLongBPlusTree<TransactionalRangePoint> tree = createBareTree();
		// rebuild the tree from the deserialized snapshot by inserting all points (thresholds are unique & monotonic)
		for (final TransactionalRangePoint point : ranges) {
			tree.insert(point.getThreshold(), point);
		}
		this.ranges = tree;
		// rebuilt from a persisted snapshot - a freshly loaded index is clean
		this.dirty = new TransactionalBoolean(false);
		this.pageStreamRegistry = new PageStreamRegistry();
	}

	public RangeIndex() {
		this.ranges = createEmptyTree();
		this.dirty = new TransactionalBoolean(false);
		this.pageStreamRegistry = new PageStreamRegistry();
	}

	public RangeIndex(long from, long to, @Nonnull int[] recordIds) {
		this.ranges = createEmptyTree();
		// dirty must be initialized before addRecord (which raises it)
		this.dirty = new TransactionalBoolean(false);
		this.pageStreamRegistry = new PageStreamRegistry();
		for (int recordId : recordIds) {
			addRecord(from, to, recordId);
		}
	}

	/**
	 * Private constructor used by {@link #createCopyWithMergedTransactionalMemory} to wrap an already committed tree, and
	 * by {@link #fromPersistedPages} to wrap a boundary-stable reloaded tree. The owner-resident page bookkeeping is
	 * carried BY REFERENCE so the surviving committed owner keeps the allocator + change-detection baseline.
	 *
	 * @param committedTree      the tree obtained from the committed transactional state (or reassembled from pages)
	 * @param pageStreamRegistry the owner-resident page bookkeeping to adopt by reference
	 */
	private RangeIndex(
		@Nonnull TransactionalLongBPlusTree<TransactionalRangePoint> committedTree,
		@Nonnull PageStreamRegistry pageStreamRegistry
	) {
		this.ranges = committedTree;
		// a committed copy starts clean - its diff layer has just been folded into committedTree
		this.dirty = new TransactionalBoolean(false);
		this.pageStreamRegistry = pageStreamRegistry;
	}

	/**
	 * Returns all ranges registered in this index, ordered ascending by threshold.
	 */
	@Nonnull
	public RangePoint<?>[] getRanges() {
		// This materializes the whole range-point array and feeds the SINGLE (whole-index, inline)
		// serialization route only. It is intentionally retained: small range indexes stay inline in the
		// FilterIndexStoragePart root and are cheaper to rewrite whole than to maintain per-leaf page
		// bookkeeping. Large range indexes persist granularly via the page tree (collectChangedPages /
		// RangeIndexLeafPagePart), which bypasses this method entirely and writes
		// only the leaves a transaction actually changed.
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
		this.dirty.setToTrue();
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
		this.dirty.setToTrue();
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
		} else {
			// the point's record set was mutated in place (the leaf's own columns are untouched) — flag the holding
			// leaf dirty so the granular write path re-emits its page (the delete branch already marks it)
			this.ranges.markDirty(threshold);
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
		GRANULAR PAGE STORAGE
	 */

	/**
	 * Returns whether this index's threshold tree spans more than one leaf and is therefore persisted in the granular
	 * `PAGED` shape (one record per leaf) rather than inline in the `FilterIndexStoragePart` root.
	 *
	 * @return true when the tree has an internal root (≥ 2 leaves)
	 */
	public boolean isPaged() {
		return this.ranges.isRootInternal();
	}

	/**
	 * Drops this index's page bookkeeping (allocator, high-water, baseline). Called when the range falls back to the
	 * inline `SINGLE` shape so a later regrow into `PAGED` starts from a clean baseline and re-emits every leaf. The
	 * caller is expected to have already issued removals for the prior `PAGED` leaf pages (see
	 * {@link #currentLeafPageSequences()})
	 * BEFORE calling this.
	 */
	public void forgetPageStream() {
		this.pageStreamRegistry.forget(RANGE_PAGE_STREAM);
	}

	/**
	 * Promotes the page set staged by the PREVIOUS flush to the live change-detection baseline, so this flush's
	 * freed-page diff and `pageListChanged` verdict are taken against what disk actually holds.
	 *
	 * The registry's live set answers "which leaf pages does this threshold tree have on disk", and both the pages a
	 * leaf merge freed (so their obsolete points are REMOVED from storage, not merely left unreferenced) and whether
	 * the ordered page list changed at all (so the `PAGED` root carrying it is re-emitted rather than skipped as
	 * unchanged) are derived from it. It advances solely by publishing, which
	 * {@link #createCopyWithMergedTransactionalMemory} does at the commit-merge.
	 *
	 * A WARM_UP (bulk) flush never reaches a commit-merge — it calls this method directly, flush after flush, and
	 * the merge that publishes only ever runs for a transaction. Left alone, the live set of a freshly re-indexed
	 * range would therefore stay EMPTY for the whole warm-up while disk moved on, so a leaf MERGE (the survivor
	 * absorbs its sibling IN PLACE, keeping its own page and dirty flag — nothing is allocated) would drop a page
	 * that is never removed from storage and is still listed on a root skipped as "unchanged". The next cold load
	 * then assembles the survivor (holding the absorbed points) followed by its stale, still-listed sibling, whose
	 * first threshold no longer sorts after the survivor's last — the overlapping-leaf-page corruption.
	 *
	 * Publishing a staged set HERE — rather than only at the merge — is correct for every path, because of one
	 * invariant: **a failed flush is never followed by another flush of the same data**. Note that this publish runs
	 * at COLLECT time, before this flush has written anything (the baseline-capture pass re-enters this pipeline), so
	 * it cannot lean on the previous flush's bytes having landed by now. It does not need to: a flush that fails
	 * during trunk incorporation SUSPENDS the catalog's transaction processing ({@code TransactionManager.suspend}),
	 * and a flush that fails on the warm-up path POISONS the collection's buffer
	 * ({@code WarmUpDataStoreMemoryBuffer.poison}), so every later collect of it refuses deterministically. Those two
	 * are the same invariant in different dresses: after a failed flush no later flush of that data ever runs, so
	 * nothing can ever diff against the baselines it left behind. A flush that does NOT fail leaves `staged` holding
	 * exactly the page set it wrote — the baseline the next flush must diff against — regardless of which path staged
	 * it, and regardless of whether a merge ever ran. (Should the process die instead, {@link #fromPersistedPages}
	 * rebuilds the registry from disk on restart — page allocation is advance-only, so a burnt id is harmless.) That
	 * is what makes this safe in its own right — not the fact that it happens to be a no-op on the transactional path
	 * (where the merge published first, leaving nothing staged). The commit handshake is untouched.
	 */
	private void publishPreviousFlush() {
		this.pageStreamRegistry.publishStaged();
	}

	/**
	 * Walks the threshold tree leaf-by-leaf and returns the granular write-path emission for this commit: the leaf
	 * pages that changed since the last flush (the ones the commit must (re)write),
	 * the full ordered list of live leaf-page sequences (the `PAGED` root's leaf list), the stream high-water, and the
	 * page sequences a leaf merge dropped this commit. A not-yet-paged (split-born or fresh) leaf is assigned a freshly
	 * allocated page sequence stamped onto the live node so the commit-merge carries it forward; each leaf's
	 * transaction-aware dirty flag decides whether it is re-emitted, and is cleared once its page is collected. The
	 * complete next live-page set is STAGED here and becomes live only when the commit is published. A clean index must
	 * not call this.
	 *
	 * Before staging, any set still staged by the PREVIOUS flush is promoted to live: see
	 * {@link #publishPreviousFlush()} for why that is both necessary and safe.
	 *
	 * @return the changed leaf pages, the ordered live page-sequence list, the high-water and the freed page sequences
	 */
	@Nonnull
	public PageEmission<RangePage> collectChangedPages() {
		publishPreviousFlush();
		return this.pageStreamRegistry.collectChangedPages(
			RANGE_PAGE_STREAM, this.ranges.<TransactionalRangePoint>leafPageHandles(),
			(pageSequence, handle) -> {
				final int size = handle.size();
				final TransactionalRangePoint[] pagePoints = new TransactionalRangePoint[size];
				for (int i = 0; i < size; i++) {
					pagePoints[i] = handle.valueAt(i);
				}
				return new RangePage(pageSequence, pagePoints);
			}
		);
	}

	/**
	 * Returns the leaf-page sequences this range index WILL have on disk once the in-flight commit is durable (the
	 * staged set mid-flush, else the published live set), or an empty array when it is inline (SINGLE) / never paged.
	 * The published set alone lags a whole flush behind, so this reflects the CURRENT tree shape at any point
	 * of the flush, so the owning {@link io.evitadb.index.attribute.AttributeIndex} can snapshot "what disk holds after
	 * this commit" and, when the range companion is later dropped with its emptied filter — after which this index's own
	 * flush never runs again — still reclaim the now-orphaned leaf pages instead of leaking them forever.
	 *
	 * @return the current on-disk leaf-page sequences, or an empty array for a SINGLE / never-paged index
	 */
	@Nonnull
	public int[] currentLeafPageSequences() {
		return this.pageStreamRegistry.pendingLivePageSequences(RANGE_PAGE_STREAM);
	}

	/**
	 * Rebuilds a `PAGED` range index from its persisted leaf pages, preserving the original leaf boundaries and page
	 * identities. Unlike the point-replaying constructor, this builds one leaf per persisted page (so
	 * in-memory leaf *i* is byte-identical to persisted page *i*), stamps each leaf with its persisted page sequence, and
	 * restores the page-stream bookkeeping (high-water + the live-page set). Reconstruction replays the points through
	 * the leaf's mutation path, which flags the freshly built leaves dirty; they are cleared afterwards because they are
	 * exactly what is already on disk. The result is a boundary-stable reload: a subsequent no-mutation commit rewrites
	 * nothing (every leaf is clean), and the first real mutation rewrites only genuinely-changed leaves instead of
	 * re-paginating the whole index. The border sentinels (`Long.MIN_VALUE` / `Long.MAX_VALUE`) live in the first / last
	 * pages, so the reassembled tree carries them.
	 *
	 * @param indexDescription      a full identification of the owning index for corruption diagnostics (e.g. the
	 *                              attribute or histogram the range companion belongs to)
	 * @param orderedPageSequences  the persisted leaf-page sequences in ascending threshold order (the root's leaf list)
	 * @param perPagePoints    the range points of each leaf page, positionally aligned with `orderedPageSequences`
	 * @param highWaterPageSequence the persisted stream high-water (largest page sequence ever allocated)
	 * @return the rebuilt, boundary-stable `PAGED` range index
	 */
	@Nonnull
	public static RangeIndex fromPersistedPages(
		@Nonnull String indexDescription,
		@Nonnull int[] orderedPageSequences,
		@Nonnull TransactionalRangePoint[][] perPagePoints,
		int highWaterPageSequence
	) {
		Assert.isPremiseValid(
			orderedPageSequences.length == perPagePoints.length,
			"The number of page sequences must match the number of leaf-page point arrays."
		);
		Assert.isPremiseValid(orderedPageSequences.length > 0, "A paged range index must have at least one leaf page.");
		final List<TransactionalLongBPlusTree<TransactionalRangePoint>> pageTrees = new ArrayList<>(orderedPageSequences.length);
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final TransactionalRangePoint[] points = perPagePoints[i];
			// build a single-leaf tree from this page's points — a page never exceeds a leaf's capacity, so no split
			final TransactionalLongBPlusTree<TransactionalRangePoint> pageTree = createBareTree();
			for (final TransactionalRangePoint point : points) {
				pageTree.insert(point.getThreshold(), point);
			}
			pageTrees.add(pageTree);
		}
		// assemble the spine over the per-page leaves, preserving boundaries and stamping each leaf's page sequence
		final TransactionalLongBPlusTree<TransactionalRangePoint> tree =
			createBareTree().assembleFromSingleLeafTrees(pageTrees, orderedPageSequences, "range index for " + indexDescription);
		final PageStreamRegistry pageStreamRegistry = PageStreamRegistry.restoredFrom(
			RANGE_PAGE_STREAM, highWaterPageSequence, tree.<TransactionalRangePoint>leafPageHandles()
		);
		return new RangeIndex(tree, pageStreamRegistry);
	}

	/**
	 * One leaf page produced by the granular write path: its stable page sequence and its range points in ascending
	 * threshold order.
	 *
	 * @param pageSequence the leaf's stable page sequence
	 * @param points  the leaf's range points in ascending threshold order
	 */
	public record RangePage(int pageSequence, @Nonnull TransactionalRangePoint[] points) {
	}

	@Nonnull
	@Override
	public RangeIndex createCopyWithMergedTransactionalMemory(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// consume the dirty layer first (mirrors InvertedIndex): when this index was not touched in the transaction,
		// return THIS instance unchanged - preserving identity so the enclosing map can structurally share it, and
		// sparing the full B+ tree rebuild that getStateCopyWithCommittedChanges(this.ranges) would otherwise perform.
		final boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			// publish the page baseline staged by this commit's flush: the merge runs only AFTER the
			// flush has durably written the changed leaf pages + root, so the staged `pageSequence -> nodeId` map now
			// reflects what is on disk and becomes the live change-detection baseline for the next commit. The
			// registry is then carried BY REFERENCE into the committed copy, so the surviving owner keeps it. This is
			// the EARLIEST publish point on the transactional path only; it is not the only one — a staged set that
			// never reaches a merge (the warm-up path has no merge at all) is published by the next flush instead, see
			// `publishPreviousFlush`. (No discard counterpart is needed: a pre-flush abort never stages, and a failed
			// flush suspends this catalog's transaction processing — on the warm-up path it poisons the collection's
			// buffer instead, the same invariant in another dress — so no later flush ever diffs against the baseline
			// a failed one left behind; restart rebuilds a clean registry from disk.)
			this.pageStreamRegistry.publishStaged();
			return new RangeIndex(
				transactionalLayer.getStateCopyWithCommittedChanges(this.ranges),
				// carry the owner-resident page bookkeeping BY REFERENCE
				this.pageStreamRegistry
			);
		} else {
			return this;
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.dirty.removeLayer(transactionalLayer);
		this.ranges.removeLayer(transactionalLayer);
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
			if (!Arrays.deepEquals(thisStarts, thatStarts)) {
				return false;
			}
			final int[][] thisEnds = this.rangeEnds.stream().map(it -> it.compute().getArray()).toArray(int[][]::new);
			final int[][] thatEnds = that.rangeEnds.stream().map(it -> it.compute().getArray()).toArray(int[][]::new);
			return Arrays.deepEquals(thisEnds, thatEnds);
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
