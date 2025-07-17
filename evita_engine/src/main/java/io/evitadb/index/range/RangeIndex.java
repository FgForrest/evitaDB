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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.DisentangleFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.JoinFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.array.TransactionalComplexObjArray;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.utils.Assert;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
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
@Data
public class RangeIndex implements VoidTransactionMemoryProducer<RangeIndex>, Serializable {
	@Serial private static final long serialVersionUID = -6580254774575839798L;

	/**
	 * Function combines two {@link TransactionalRangePoint}s to new one that will hold combination of their starts
	 * and ends. Threshold is not checked by the function and caller must ensure this function produces sane result.
	 */
	private static final BiConsumer<TransactionalRangePoint, TransactionalRangePoint> INT_RANGE_POINT_PRODUCER = (target, source) -> {
		target.addStarts(source.getStarts().getArray());
		target.addEnds(source.getEnds().getArray());
	};
	/**
	 * Function subtracts second argument {@link TransactionalRangePoint} from first {@link TransactionalRangePoint}.
	 * So that first point contains no record id in the start/end set that is present in the second point.
	 * Threshold is not checked by the function and caller must ensure this function produces sane result.
	 */
	private static final BiConsumer<TransactionalRangePoint, TransactionalRangePoint> INT_RANGE_POINT_REDUCER = (target, source) -> {
		target.removeStarts(source.getStarts().getArray());
		target.removeEnds(source.getEnds().getArray());
	};
	/**
	 * Predicate will return true if point has no sense because it contains no data (no starts, no ends). Predicate will
	 * never return true for full range border points (MIN/MAX) even if empty.
	 */
	private static final Predicate<TransactionalRangePoint> INT_RANGE_POINT_OBSOLETE_CHECKER =
		point -> point.getThreshold() != Long.MIN_VALUE && point.getThreshold() != Long.MAX_VALUE && point.getStarts().isEmpty() && point.getEnds().isEmpty();

	/**
	 * Predicate will return true if both range points are deeply equals
	 */
	private static final BiPredicate<TransactionalRangePoint, TransactionalRangePoint> INT_RANGE_POINT_DEEP_COMPARATOR =
		TransactionalRangePoint::deepEquals;

	/**
	 * Contains range information sorted by {@link RangePoint#getThreshold()} in ascending order.
	 * At least two points are always present for MIN and MAX point of the range.
	 */
	final TransactionalComplexObjArray<TransactionalRangePoint> ranges;

	/**
	 * Method collects all starts and ends from ranges between fromIndex and toIndex (inclusive) and returns them collected
	 * in simple DTO.
	 */
	@Nonnull
	static StartsEndsDTO collectsStartsAndEnds(int fromIndex, int toIndex, @Nonnull TransactionalComplexObjArray<TransactionalRangePoint> ranges) {
		final StartsEndsDTO result = new StartsEndsDTO();
		for (int i = fromIndex; i <= toIndex; i++) {
			final RangePoint<?> rangePoint = ranges.get(i);
			result.addStart(rangePoint.getStarts());
			result.addEnd(rangePoint.getEnds());
		}
		return result;
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

	public RangeIndex(@Nonnull TransactionalRangePoint[] ranges) {
		Assert.isTrue(ranges.length >= 2, "At least two ranges are expected!");
		Assert.isTrue(ranges[0].getThreshold() == Long.MIN_VALUE, "First range should have threshold Long.MIN_VALUE!");
		Assert.isTrue(ranges[ranges.length - 1].getThreshold() == Long.MAX_VALUE, "Last range should have threshold Long.MAX_VALUE!");
		assertThresholdIsMonotonic(ranges);
		this.ranges = new TransactionalComplexObjArray<>(
			ranges,
			INT_RANGE_POINT_PRODUCER,
			INT_RANGE_POINT_REDUCER,
			INT_RANGE_POINT_OBSOLETE_CHECKER,
			INT_RANGE_POINT_DEEP_COMPARATOR
		);
	}

	public RangeIndex() {
		this.ranges = new TransactionalComplexObjArray<>(
			new TransactionalRangePoint[]{
				new TransactionalRangePoint(Long.MIN_VALUE),
				new TransactionalRangePoint(Long.MAX_VALUE)
			},
			INT_RANGE_POINT_PRODUCER,
			INT_RANGE_POINT_REDUCER,
			INT_RANGE_POINT_OBSOLETE_CHECKER,
			INT_RANGE_POINT_DEEP_COMPARATOR
		);
	}

	public RangeIndex(long from, long to, @Nonnull int[] recordIds) {
		this.ranges = new TransactionalComplexObjArray<>(
			new TransactionalRangePoint[]{
				new TransactionalRangePoint(Long.MIN_VALUE),
				new TransactionalRangePoint(Long.MAX_VALUE)
			},
			INT_RANGE_POINT_PRODUCER,
			INT_RANGE_POINT_REDUCER,
			INT_RANGE_POINT_OBSOLETE_CHECKER,
			INT_RANGE_POINT_DEEP_COMPARATOR
		);
		for (int recordId : recordIds) {
			addRecord(from, to, recordId);
		}
	}

	/**
	 * Returns all ranges registered in this index.
	 */
	@Nonnull
	public RangePoint<?>[] getRanges() {
		return this.ranges.getArray();
	}

	/**
	 * Adds new record with the interval from/to to the range.
	 */
	public void addRecord(long from, long to, int recordId) {
		final Bitmap recArray = new BaseBitmap(recordId);
		this.ranges.add(new TransactionalRangePoint(from, recArray, EmptyBitmap.INSTANCE));
		this.ranges.add(new TransactionalRangePoint(to, EmptyBitmap.INSTANCE, recArray));
	}

	/**
	 * Removes record with the interval from/to from the range.
	 */
	public void removeRecord(long start, long end, int recordId) {
		final Bitmap recArray = new BaseBitmap(recordId);
		this.ranges.remove(new TransactionalRangePoint(start, recArray, EmptyBitmap.INSTANCE));
		this.ranges.remove(new TransactionalRangePoint(end, EmptyBitmap.INSTANCE, recArray));
	}

	/**
	 * Returns true if the range contains passed record id anywhere in its {@link #ranges}.
	 */
	public boolean contains(int recordId) {
		final Iterator<TransactionalRangePoint> it = this.ranges.iterator();
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
		final int index = this.ranges.indexOf(new TransactionalRangePoint(threshold));
		final int startIndex = index >= 0 ? index : -1 * (index) - 1;

		final StartsEndsDTO startsEndsDTO = collectsStartsAndEnds(startIndex, this.ranges.getLength() - 1, this.ranges);
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
		final int index = this.ranges.indexOf(new TransactionalRangePoint(threshold));
		final int startIndex = index >= 0 ? index : -1 * (index) - 2;

		final StartsEndsDTO startsEndsDTO = collectsStartsAndEnds(0, startIndex, this.ranges);
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
		final RangeLookup rangeLookup = new RangeLookup(this.ranges, threshold, threshold);

		final int startIndex = rangeLookup.isStartThresholdFound() ? rangeLookup.getStartIndex() : rangeLookup.getStartIndex() - 1;
		final int endIndex = rangeLookup.isEndThresholdFound() ? rangeLookup.getEndIndex() + 1 : rangeLookup.getEndIndex();

		final StartsEndsDTO before = startIndex >= 0 ?
			collectsStartsAndEnds(0, startIndex, this.ranges) : new StartsEndsDTO();
		final StartsEndsDTO after = endIndex < this.ranges.getLength() ?
			collectsStartsAndEnds(endIndex, this.ranges.getLength() - 1, this.ranges) : new StartsEndsDTO();

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
			final Bitmap starts = this.ranges.get(rangeLookup.getStartIndex()).getStarts();
			final Bitmap ends = this.ranges.get(rangeLookup.getEndIndex()).getEnds();

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
		final RangeLookup rangeLookup = new RangeLookup(this.ranges, from, to);
		final StartsEndsDTO between = collectsStartsAndEnds(rangeLookup.getStartIndex(), rangeLookup.getEndIndex(), this.ranges);
		final StartsEndsDTO before = collectsStartsAndEnds(0, Math.min(rangeLookup.getStartIndex(), rangeLookup.getEndIndex()), this.ranges);
		final StartsEndsDTO after = collectsStartsAndEnds(Math.max(rangeLookup.getStartIndex(), rangeLookup.getEndIndex()), this.ranges.getLength() - 1, this.ranges);

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
		final StartsEndsDTO all = collectsStartsAndEnds(0, this.ranges.getLength() - 1, this.ranges);
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

		RangeLookup(@Nonnull TransactionalComplexObjArray<TransactionalRangePoint> ranges, long from, long to) {
			final int indexFrom = ranges.indexOf(new TransactionalRangePoint(from));
			if (indexFrom >= 0) {
				this.startIndex = indexFrom;
				this.startPoint = ranges.get(indexFrom);
			} else {
				this.startIndex = -1 * (indexFrom) - 1;
				this.startPoint = null;
			}

			if (from == to) {
				this.endIndex = this.startIndex;
				this.endPoint = this.startPoint;
			} else {
				final int indexTo = ranges.indexOf(new TransactionalRangePoint(to));
				if (indexTo >= 0) {
					this.endIndex = indexTo;
					this.endPoint = ranges.get(indexTo);
				} else {
					this.endIndex = -1 * (indexTo) - 2;
					this.endPoint = null;
				}
			}
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
