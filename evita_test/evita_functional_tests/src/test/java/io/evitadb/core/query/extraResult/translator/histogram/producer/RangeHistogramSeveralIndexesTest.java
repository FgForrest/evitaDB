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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a histogram of a range attribute computed from several filter indexes is the histogram of the same
 * ranges held in a single filter index.
 *
 * Each filter index sweeps its own ranges and emits a bucket only at its own range boundaries, carrying the records
 * active there; merging the buckets of several indexes by threshold therefore loses every record that is active across
 * a boundary only another index emits. Every case spreads its ranges over two or three indexes and computes the
 * histogram once from those indexes and once from a single index holding all the ranges - the oracle - in every
 * histogram behaviour.
 * The buckets of {@link FilterIndex#getRangeHistogramOfAllRecords(List, Class, int)}, which sweeps several indexes at
 * once, are compared with the oracle's own buckets as well.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A range histogram computed from several filter indexes")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(HISTOGRAM)
@Tag(ATTRIBUTE)
class RangeHistogramSeveralIndexesTest {

	/**
	 * The name of the range attribute every index and schema of this class belongs to.
	 */
	private static final String ATTRIBUTE_NAME = "validity";
	/**
	 * The key of every filter index of this class.
	 */
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
	/**
	 * The record placed into the first index by the two-record cases.
	 */
	private static final int FIRST_RECORD = 1;
	/**
	 * The record placed into the second index by the two-record cases.
	 */
	private static final int SECOND_RECORD = 2;
	/**
	 * The record placed by the cases that need a third one.
	 */
	private static final int THIRD_RECORD = 3;
	/**
	 * The record placed by the cases that need a fourth one.
	 */
	private static final int FOURTH_RECORD = 4;
	/**
	 * The number of buckets every histogram is requested with.
	 */
	private static final int BUCKET_COUNT = 5;
	/**
	 * The indexed decimal places of the `BigDecimalNumberRange` cases.
	 */
	private static final int BIG_DECIMAL_PLACES = 2;

	/**
	 * The record of the first index holds two ranges sharing their start, so that index's point at 1 starts it twice;
	 * the record must stay active at the thresholds 8 and 9, which only the second index holds, until its longer range
	 * ends at 10.
	 */
	private static final RangeCase REPEATED_STARTS = new RangeCase(
		"REPEATED_STARTS", IntegerNumberRange.class, 0, null,
		List.of(
			inFirstIndex(FIRST_RECORD, IntegerNumberRange.between(1, 6)),
			inFirstIndex(FIRST_RECORD, IntegerNumberRange.between(1, 10)),
			inSecondIndex(SECOND_RECORD, IntegerNumberRange.between(8, 9))
		)
	);
	/**
	 * The record of the first index holds two ranges sharing their end, so that index's point at 6 ends it twice; the
	 * record must be gone from the thresholds 8 and 9, which only the second index holds.
	 */
	private static final RangeCase REPEATED_ENDS = new RangeCase(
		"REPEATED_ENDS", IntegerNumberRange.class, 0, null,
		List.of(
			inFirstIndex(FIRST_RECORD, IntegerNumberRange.between(1, 6)),
			inFirstIndex(FIRST_RECORD, IntegerNumberRange.between(3, 6)),
			inSecondIndex(SECOND_RECORD, IntegerNumberRange.between(8, 9))
		)
	);
	/**
	 * The record of the first index holds two ranges with a gap between them; the record must be absent from the
	 * threshold 5 inside the gap, which only the second index holds.
	 *
	 * The histogram comparisons of this case prove parity with one index only, not correctness: the overlap cruncher
	 * of the STANDARD and OPTIMIZED behaviours rebuilds every record as one interval from its first to its last
	 * threshold, so even from one index it counts the record in the buckets inside the gap. The correctness of the gap
	 * is carried by the raw buckets, {@link BucketSweepTest#shouldLeaveRecordOutOfGapBetweenItsRanges}.
	 */
	private static final RangeCase GAP = new RangeCase(
		"GAP", IntegerNumberRange.class, 0, null,
		List.of(
			inFirstIndex(FIRST_RECORD, IntegerNumberRange.between(1, 3)),
			inFirstIndex(FIRST_RECORD, IntegerNumberRange.between(8, 10)),
			inSecondIndex(SECOND_RECORD, IntegerNumberRange.between(5, 5))
		)
	);
	/**
	 * Three indexes, every record held by two of them and every threshold but 8 held by at least two of them: each
	 * record's ranges overlap across indexes, and the record must stay active until the last of them ends.
	 */
	private static final RangeCase THREE_INDEXES = new RangeCase(
		"THREE_INDEXES", IntegerNumberRange.class, 0, null,
		List.of(
			inFirstIndex(FIRST_RECORD, IntegerNumberRange.between(1, 6)),
			inFirstIndex(SECOND_RECORD, IntegerNumberRange.between(4, 10)),
			inSecondIndex(FIRST_RECORD, IntegerNumberRange.between(4, 8)),
			inSecondIndex(THIRD_RECORD, IntegerNumberRange.between(6, 6)),
			inThirdIndex(SECOND_RECORD, IntegerNumberRange.between(1, 4)),
			inThirdIndex(THIRD_RECORD, IntegerNumberRange.between(6, 10))
		)
	);

	/**
	 * The shapes of the original reproduction: two records, one in each index.
	 */
	private static final List<RangeCase> REPRODUCED_CASES = List.of(
		// the first range contains the second, so the first record is active at both boundaries of the second
		twoRecords("NESTED", IntegerNumberRange.between(1, 10), IntegerNumberRange.between(4, 6)),
		// each record is active at one boundary of the other
		twoRecords("OVERLAPPING", IntegerNumberRange.between(1, 6), IntegerNumberRange.between(4, 10)),
		// the first range is open to the left, so the only boundary its own index emits is its upper one
		twoRecords("OPEN_FROM", IntegerNumberRange.to(10), IntegerNumberRange.between(4, 6)),
		// the first range is open to the right, so the only boundary its own index emits is its lower one
		twoRecords("OPEN_TO", IntegerNumberRange.from(1), IntegerNumberRange.between(4, 6)),
		// the control: neither record is active at a boundary of the other
		twoRecords("DISJOINT", IntegerNumberRange.between(1, 3), IntegerNumberRange.between(8, 10))
	);

	/**
	 * The shapes added to the reproduction: the other inner numeric types, a narrowing filter, touching ranges, a
	 * record holding several ranges and three indexes.
	 */
	private static final List<RangeCase> FURTHER_CASES = List.of(
		new RangeCase(
			"LONG_NESTED", LongNumberRange.class, 0, null,
			List.of(
				inFirstIndex(FIRST_RECORD, LongNumberRange.between(1L, 10L)),
				inSecondIndex(SECOND_RECORD, LongNumberRange.between(4L, 6L))
			)
		),
		new RangeCase(
			"LONG_OPEN_FROM", LongNumberRange.class, 0, null,
			List.of(
				inFirstIndex(FIRST_RECORD, LongNumberRange.to(10L)),
				inSecondIndex(SECOND_RECORD, LongNumberRange.between(4L, 6L))
			)
		),
		// the bounds are given at their own scale, which the indexes bring to the schema's two places
		new RangeCase(
			"BIG_DECIMAL_NESTED", BigDecimalNumberRange.class, BIG_DECIMAL_PLACES, null,
			List.of(
				inFirstIndex(
					FIRST_RECORD, BigDecimalNumberRange.between(new BigDecimal("1.25"), new BigDecimal("10.5"))
				),
				inSecondIndex(
					SECOND_RECORD, BigDecimalNumberRange.between(new BigDecimal("4.75"), new BigDecimal("6"))
				)
			)
		),
		new RangeCase(
			"BIG_DECIMAL_OPEN_FROM", BigDecimalNumberRange.class, BIG_DECIMAL_PLACES, null,
			List.of(
				inFirstIndex(FIRST_RECORD, BigDecimalNumberRange.to(new BigDecimal("10.5"))),
				inSecondIndex(
					SECOND_RECORD, BigDecimalNumberRange.between(new BigDecimal("4.75"), new BigDecimal("6"))
				)
			)
		),
		// the filter keeps the first record only, so the buckets the second index emits survive only through it
		new RangeCase(
			"NESTED_FILTERED", IntegerNumberRange.class, 0, new int[]{FIRST_RECORD},
			List.of(
				inFirstIndex(FIRST_RECORD, IntegerNumberRange.between(1, 10)),
				inSecondIndex(SECOND_RECORD, IntegerNumberRange.between(4, 6))
			)
		),
		// both indexes hold a point at 6: one range starts there and the other ends there
		twoRecords("TOUCHING", IntegerNumberRange.between(1, 6), IntegerNumberRange.between(6, 10)),
		// one record holds a range in each index
		new RangeCase(
			"SAME_RECORD", IntegerNumberRange.class, 0, null,
			List.of(
				inFirstIndex(FIRST_RECORD, IntegerNumberRange.between(1, 10)),
				inSecondIndex(FIRST_RECORD, IntegerNumberRange.between(4, 6))
			)
		),
		REPEATED_STARTS,
		REPEATED_ENDS,
		GAP,
		THREE_INDEXES
	);

	/**
	 * One range held by a record in one of the case's filter indexes.
	 *
	 * @param index    the position of the index holding the range, `0` for the first index
	 * @param recordId the record holding the range
	 * @param range    the range, an instance of the case's range type
	 */
	private record Placement(
		int index,
		int recordId,
		@Nonnull Serializable range
	) {
	}

	/**
	 * Ranges of one range attribute spread over several filter indexes.
	 *
	 * @param name          the name of the case
	 * @param rangeType     the `NumberRange` subtype of the attribute
	 * @param decimalPlaces the indexed decimal places of the attribute
	 * @param filter        the records the histogram is narrowed to, or `null` when it is not narrowed
	 * @param placements    every range of the case and the index holding it
	 */
	private record RangeCase(
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> rangeType,
		int decimalPlaces,
		@Nullable int[] filter,
		@Nonnull List<Placement> placements
	) {

		/**
		 * Names the case by its name only, which is what the parameterized test names show.
		 *
		 * @return the name of the case
		 */
		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}
	}

	/**
	 * Combines every shape of the original reproduction with every histogram behaviour.
	 *
	 * @return arguments of {@link HistogramTest#shouldComputeSameHistogramFromSeveralIndexesAsFromOne}
	 */
	@Nonnull
	static Stream<Arguments> reproducedCases() {
		return withEveryBehavior(REPRODUCED_CASES);
	}

	/**
	 * Combines every shape added to the reproduction with every histogram behaviour.
	 *
	 * @return arguments of {@link HistogramTest#shouldComputeSameHistogramForEveryTypeFilterAndRangeLayout}
	 */
	@Nonnull
	static Stream<Arguments> furtherCases() {
		return withEveryBehavior(FURTHER_CASES);
	}

	/**
	 * Lists every case without a filter: the sweep knows no filter, so the filtered case would repeat its unfiltered
	 * twin.
	 *
	 * @return arguments of {@link BucketSweepTest#shouldEmitBucketsOfOneIndexHoldingSameRanges}
	 */
	@Nonnull
	static Stream<RangeCase> sweptCases() {
		return Stream.concat(REPRODUCED_CASES.stream(), FURTHER_CASES.stream())
			.filter(rangeCase -> rangeCase.filter() == null);
	}

	/**
	 * Combines every case with every histogram behaviour.
	 *
	 * @param cases the cases
	 * @return one argument pair per case and behaviour
	 */
	@Nonnull
	private static Stream<Arguments> withEveryBehavior(@Nonnull List<RangeCase> cases) {
		final List<Arguments> result = new ArrayList<>(cases.size() * HistogramBehavior.values().length);
		for (RangeCase rangeCase : cases) {
			for (HistogramBehavior behavior : HistogramBehavior.values()) {
				result.add(Arguments.of(rangeCase, behavior));
			}
		}
		return result.stream();
	}

	/**
	 * Creates an unfiltered `IntegerNumberRange` case of two records, the first held by the first index and the second
	 * by the second index.
	 *
	 * @param name   the name of the case
	 * @param first  the range of the first record
	 * @param second the range of the second record
	 * @return the case
	 */
	@Nonnull
	private static RangeCase twoRecords(
		@Nonnull String name,
		@Nonnull IntegerNumberRange first,
		@Nonnull IntegerNumberRange second
	) {
		return new RangeCase(
			name, IntegerNumberRange.class, 0, null,
			List.of(inFirstIndex(FIRST_RECORD, first), inSecondIndex(SECOND_RECORD, second))
		);
	}

	/**
	 * Places a range of a record into the first index.
	 *
	 * @param recordId the record holding the range
	 * @param range    the range
	 * @return the placement
	 */
	@Nonnull
	private static Placement inFirstIndex(int recordId, @Nonnull Serializable range) {
		return new Placement(0, recordId, range);
	}

	/**
	 * Places a range of a record into the second index.
	 *
	 * @param recordId the record holding the range
	 * @param range    the range
	 * @return the placement
	 */
	@Nonnull
	private static Placement inSecondIndex(int recordId, @Nonnull Serializable range) {
		return new Placement(1, recordId, range);
	}

	/**
	 * Places a range of a record into the third index.
	 *
	 * @param recordId the record holding the range
	 * @param range    the range
	 * @return the placement
	 */
	@Nonnull
	private static Placement inThirdIndex(int recordId, @Nonnull Serializable range) {
		return new Placement(2, recordId, range);
	}

	/**
	 * Creates an empty filter index of the case's range attribute.
	 *
	 * @param rangeCase the case
	 * @return the filter index, with its range companion
	 */
	@Nonnull
	private static FilterIndex filterIndex(@Nonnull RangeCase rangeCase) {
		return new OwnerFilterIndex(ATTRIBUTE_KEY, rangeCase.rangeType(), rangeCase.decimalPlaces());
	}

	/**
	 * Creates an empty filter index of an `IntegerNumberRange` attribute.
	 *
	 * @return the filter index, with its range companion
	 */
	@Nonnull
	private static OwnerFilterIndex integerRangeIndex() {
		return new OwnerFilterIndex(ATTRIBUTE_KEY, IntegerNumberRange.class);
	}

	/**
	 * Creates the filter indexes of the case, each holding the ranges placed into it - as many indexes as the highest
	 * placement position requires.
	 *
	 * @param rangeCase the case
	 * @return the indexes, in the order of their positions
	 */
	@Nonnull
	private static List<FilterIndex> severalIndexes(@Nonnull RangeCase rangeCase) {
		int indexCount = 0;
		for (Placement placement : rangeCase.placements()) {
			indexCount = Math.max(indexCount, placement.index() + 1);
		}
		final List<FilterIndex> indexes = new ArrayList<>(indexCount);
		for (int i = 0; i < indexCount; i++) {
			indexes.add(filterIndex(rangeCase));
		}
		for (Placement placement : rangeCase.placements()) {
			indexes.get(placement.index()).addRecord(placement.recordId(), placement.range());
		}
		return indexes;
	}

	/**
	 * Creates the oracle: a single filter index holding every range of the case.
	 *
	 * @param rangeCase the case
	 * @return the filter index
	 */
	@Nonnull
	private static FilterIndex oneIndex(@Nonnull RangeCase rangeCase) {
		final FilterIndex singleIndex = filterIndex(rangeCase);
		for (Placement placement : rangeCase.placements()) {
			singleIndex.addRecord(placement.recordId(), placement.range());
		}
		return singleIndex;
	}

	/**
	 * Computes the histogram of the case's range attribute from `indexes`, narrowed by the case's filter, the way the
	 * attribute histogram producer wires the computer.
	 *
	 * @param rangeCase the case
	 * @param behavior  the histogram behaviour
	 * @param indexes   the filter indexes holding the ranges
	 * @return the computed histogram
	 */
	@Nonnull
	private static CacheableHistogramContract compute(
		@Nonnull RangeCase rangeCase,
		@Nonnull HistogramBehavior behavior,
		@Nonnull List<FilterIndex> indexes
	) {
		return compute(rangeCase.rangeType(), rangeCase.decimalPlaces(), rangeCase.filter(), behavior, indexes);
	}

	/**
	 * Computes the histogram of an attribute of `attributeType` from `indexes`, narrowed by `filter`, the way the
	 * attribute histogram producer wires the computer.
	 *
	 * @param attributeType the type of the attribute schema the request carries
	 * @param decimalPlaces the indexed decimal places of the attribute
	 * @param filter        the records the histogram is narrowed to, or `null` when it is not narrowed
	 * @param behavior      the histogram behaviour
	 * @param indexes       the filter indexes holding the values
	 * @return the computed histogram
	 */
	@Nonnull
	@SuppressWarnings("rawtypes")
	private static CacheableHistogramContract compute(
		@Nonnull Class<? extends Serializable> attributeType,
		int decimalPlaces,
		@Nullable int[] filter,
		@Nonnull HistogramBehavior behavior,
		@Nonnull List<FilterIndex> indexes
	) {
		final AttributeSchema attributeSchema = AttributeSchema._internalBuild(
			ATTRIBUTE_NAME, null, null,
			new EnumMap<>(Scope.class), EnumSet.noneOf(Scope.class), null, EnumSet.noneOf(Scope.class),
			false, false, false,
			attributeType, null, decimalPlaces,
			ConflictResolutionOverride.INHERITED
		);
		final Comparator comparator = FilterIndex.getComparator(ATTRIBUTE_KEY, attributeType);
		final AttributeHistogramProducer.AttributeHistogramRequest request =
			new AttributeHistogramProducer.AttributeHistogramRequest(
				attributeSchema, BUCKET_COUNT, behavior, comparator, indexes
			);
		final Formula filterFormula = filter == null ? null : new ConstantFormula(new BaseBitmap(filter));
		return new AttributeHistogramComputer(ATTRIBUTE_NAME, filterFormula, BUCKET_COUNT, behavior, request).compute();
	}

	/**
	 * Describes a histogram by its minimum, maximum, raw minimum and maximum with their classes, overall count and
	 * every bucket's threshold and occurrences.
	 *
	 * @param histogram the histogram
	 * @return the description
	 */
	@Nonnull
	private static String describe(@Nonnull CacheableHistogramContract histogram) {
		if (histogram == CacheableHistogramContract.EMPTY) {
			return "empty";
		}
		final CacheableBucket[] buckets = histogram.getBuckets();
		final StringBuilder description = new StringBuilder(96 + buckets.length * 8)
			.append("min=").append(plain(histogram.getMin()))
			.append(" max=").append(plain(histogram.getMax()))
			.append(" rawMin=").append(withClass(histogram.getRawMin()))
			.append(" rawMax=").append(withClass(histogram.getRawMax()))
			.append(" count=").append(histogram.getOverallCount())
			.append(" buckets=[");
		for (int i = 0; i < buckets.length; i++) {
			if (i > 0) {
				description.append(", ");
			}
			description.append(plain(buckets[i].threshold())).append(':').append(buckets[i].occurrences());
		}
		return description.append(']').toString();
	}

	/**
	 * Formats `number` without trailing zeros and without an exponent.
	 *
	 * @param number the number
	 * @return the plain form of the number
	 */
	@Nonnull
	private static String plain(@Nullable BigDecimal number) {
		return number == null ? "null" : number.stripTrailingZeros().toPlainString();
	}

	/**
	 * Formats `value` as it is, followed by the simple name of its class, so that two keys of equal value but of
	 * different classes or scales tell apart.
	 *
	 * @param value the value
	 * @return the value and its class
	 */
	@Nonnull
	private static String withClass(@Nullable Serializable value) {
		return value == null ? "null" : value + "(" + value.getClass().getSimpleName() + ")";
	}

	/**
	 * Resolves the inner numeric type of the case's range type.
	 *
	 * @param rangeCase the case
	 * @return the inner numeric type, e.g. `Integer` for `IntegerNumberRange`
	 */
	@Nonnull
	private static Class<? extends Number> innerNumericType(@Nonnull RangeCase rangeCase) {
		return Objects.requireNonNull(EvitaDataTypes.resolveRangeInnerNumericType(rangeCase.rangeType()));
	}

	/**
	 * Sweeps the filter indexes of the case at once.
	 *
	 * @param rangeCase the case
	 * @return the buckets of the sweep
	 */
	@Nonnull
	private static ValueToRecordBitmap[] sweep(@Nonnull RangeCase rangeCase) {
		return FilterIndex.getRangeHistogramOfAllRecords(
			severalIndexes(rangeCase), innerNumericType(rangeCase), rangeCase.decimalPlaces()
		);
	}

	/**
	 * Sweeps `indexes` of an `IntegerNumberRange` attribute at once and describes the buckets.
	 *
	 * @param indexes the indexes
	 * @return the description of the buckets
	 */
	@Nonnull
	private static String sweepIntegerRanges(@Nonnull List<? extends FilterIndex> indexes) {
		return describeBuckets(FilterIndex.getRangeHistogramOfAllRecords(List.copyOf(indexes), Integer.class, 0));
	}

	/**
	 * Describes the buckets the single-index sweep of an `IntegerNumberRange` index emits.
	 *
	 * @param index the index
	 * @return the description of the buckets
	 */
	@Nonnull
	private static String ownSweepOfIntegerRanges(@Nonnull FilterIndex index) {
		return describeBuckets(index.getRangeHistogramOfAllRecords(Integer.class, 0).getBuckets());
	}

	/**
	 * Describes raw buckets by every key with its class, and every record set.
	 *
	 * @param buckets the buckets
	 * @return the description
	 */
	@Nonnull
	private static String describeBuckets(@Nonnull ValueToRecord[] buckets) {
		final StringBuilder description = new StringBuilder(buckets.length * 32);
		for (int i = 0; i < buckets.length; i++) {
			if (i > 0) {
				description.append(", ");
			}
			description.append(withClass(buckets[i].getValue()))
				.append('=')
				.append(Arrays.toString(buckets[i].getRecordIds().getArray()));
		}
		return description.toString();
	}

	/**
	 * Requires the thresholds of `buckets` to be strictly ascending.
	 *
	 * @param rangeCase the case the buckets belong to
	 * @param buckets   the buckets
	 */
	private static void assertStrictlyAscending(@Nonnull RangeCase rangeCase, @Nonnull ValueToRecord[] buckets) {
		for (int i = 1; i < buckets.length; i++) {
			assertTrue(
				FilterIndex.fromBucketKey(buckets[i - 1].getValue()) < FilterIndex.fromBucketKey(buckets[i].getValue()),
				rangeCase + ": the thresholds are not strictly ascending at bucket " + i + " of " +
					describeBuckets(buckets)
			);
		}
	}

	/**
	 * Requires the histogram of the case computed from its several indexes to be the one computed from the oracle.
	 *
	 * @param rangeCase the case
	 * @param behavior  the histogram behaviour
	 */
	private static void assertSameHistogram(@Nonnull RangeCase rangeCase, @Nonnull HistogramBehavior behavior) {
		final String fromOneIndex = describe(compute(rangeCase, behavior, List.of(oneIndex(rangeCase))));
		final String fromSeveralIndexes = describe(compute(rangeCase, behavior, severalIndexes(rangeCase)));
		assertEquals(
			fromOneIndex, fromSeveralIndexes,
			rangeCase + " " + behavior + ": one index (expected) and several indexes (actual) differ"
		);
	}

	/**
	 * The histogram the attribute histogram computer renders from several indexes, compared with the oracle's.
	 */
	@Nested
	@DisplayName("histogram")
	class HistogramTest {

		/**
		 * Computes the histogram of the two records from two filter indexes, one record each, and from one filter
		 * index holding both, and requires the two to be the same - minimum, maximum, raw boundaries, overall count and
		 * every bucket's threshold and occurrences.
		 *
		 * @param rangeCase the ranges of the two records
		 * @param behavior  the histogram behaviour
		 */
		@ParameterizedTest(name = "{0} {1}")
		@MethodSource(
			"io.evitadb.core.query.extraResult.translator.histogram.producer.RangeHistogramSeveralIndexesTest" +
				"#reproducedCases"
		)
		@DisplayName("several filter indexes give the histogram one index holding the same records gives")
		void shouldComputeSameHistogramFromSeveralIndexesAsFromOne(
			@Nonnull RangeCase rangeCase,
			@Nonnull HistogramBehavior behavior
		) {
			assertSameHistogram(rangeCase, behavior);
		}

		/**
		 * Computes the histogram of the case from its filter indexes and from one filter index holding all its ranges,
		 * and requires the two to be the same.
		 *
		 * The comparison proves parity with one index. For {@link RangeHistogramSeveralIndexesTest#GAP} that is all it
		 * proves: the overlap cruncher counts a record in the gap between its ranges even from one index, so the oracle
		 * is wrong there in the STANDARD and OPTIMIZED behaviours, and the correctness of the gap rests on
		 * {@link BucketSweepTest#shouldLeaveRecordOutOfGapBetweenItsRanges}.
		 *
		 * @param rangeCase the case
		 * @param behavior  the histogram behaviour
		 */
		@ParameterizedTest(name = "{0} {1}")
		@MethodSource(
			"io.evitadb.core.query.extraResult.translator.histogram.producer.RangeHistogramSeveralIndexesTest" +
				"#furtherCases"
		)
		@DisplayName(
			"several filter indexes give the histogram of one index for every inner type, a filter, " +
				"touching ranges, a record holding several ranges and three indexes"
		)
		void shouldComputeSameHistogramForEveryTypeFilterAndRangeLayout(
			@Nonnull RangeCase rangeCase,
			@Nonnull HistogramBehavior behavior
		) {
			assertSameHistogram(rangeCase, behavior);
		}

		/**
		 * The sweep over several indexes needs the range companion of every index. A scalar index beside a range index
		 * of a range attribute schema keeps the per-index merge: the range index emits `1:{1} 3:{1}`, the scalar index
		 * `8:{2}`, and the overlap cruncher renders `[1,3]` and the point `8`. The expected histogram is the one the
		 * per-index merge computes.
		 */
		@Test
		@DisplayName("a range index beside a scalar index keeps the merge of their own buckets")
		void shouldMergeOwnBucketsOfRangeIndexBesideScalarIndex() {
			final FilterIndex rangeIndex = integerRangeIndex();
			rangeIndex.addRecord(FIRST_RECORD, IntegerNumberRange.between(1, 3));
			final FilterIndex scalarIndex = new OwnerFilterIndex(ATTRIBUTE_KEY, Integer.class);
			scalarIndex.addRecord(SECOND_RECORD, 8);
			assertEquals(
				"min=1 max=8 rawMin=1(Integer) rawMax=8(Integer) count=2 buckets=[1:1, 2:1, 3:1, 5:0, 6:1]",
				describe(
					compute(
						IntegerNumberRange.class, 0, null, HistogramBehavior.STANDARD,
						List.of(rangeIndex, scalarIndex)
					)
				)
			);
		}

		/**
		 * The per-index merge combines the own buckets of the indexes by threshold, so listing the scalar index before
		 * the range index must give the histogram listing it after the range index gives, in every behaviour.
		 *
		 * @param behavior the histogram behaviour
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(HistogramBehavior.class)
		@DisplayName("a range index and a scalar index give the same histogram in either order")
		void shouldComputeSameHistogramFromRangeAndScalarIndexInEitherOrder(@Nonnull HistogramBehavior behavior) {
			final FilterIndex rangeIndex = integerRangeIndex();
			rangeIndex.addRecord(FIRST_RECORD, IntegerNumberRange.between(1, 3));
			final FilterIndex scalarIndex = new OwnerFilterIndex(ATTRIBUTE_KEY, Integer.class);
			scalarIndex.addRecord(SECOND_RECORD, 8);

			final String rangeIndexFirst = describe(
				compute(IntegerNumberRange.class, 0, null, behavior, List.of(rangeIndex, scalarIndex))
			);
			final String scalarIndexFirst = describe(
				compute(IntegerNumberRange.class, 0, null, behavior, List.of(scalarIndex, rangeIndex))
			);

			assertEquals(rangeIndexFirst, scalarIndexFirst);
		}

		/**
		 * The sweep over several indexes is taken only when every index has its range companion, not when all but the
		 * last one do: two range indexes beside a scalar index keep the per-index merge. Their ranges are disjoint, so
		 * their own buckets merged are the buckets of one range index holding both, and the histogram must be the one
		 * that index gives beside the same scalar index. Overlapping ranges would not do: the per-index merge loses a
		 * record active at a threshold only the other range index emits, which is what the sweep exists to prevent.
		 *
		 * @param behavior the histogram behaviour
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(HistogramBehavior.class)
		@DisplayName("two range indexes beside a scalar index keep the merge of their own buckets")
		void shouldMergeOwnBucketsOfTwoRangeIndexesBesideScalarIndex(@Nonnull HistogramBehavior behavior) {
			final FilterIndex firstRangeIndex = integerRangeIndex();
			firstRangeIndex.addRecord(FIRST_RECORD, IntegerNumberRange.between(1, 3));
			final FilterIndex secondRangeIndex = integerRangeIndex();
			secondRangeIndex.addRecord(THIRD_RECORD, IntegerNumberRange.between(5, 6));
			final FilterIndex bothRangeIndexes = integerRangeIndex();
			bothRangeIndexes.addRecord(FIRST_RECORD, IntegerNumberRange.between(1, 3));
			bothRangeIndexes.addRecord(THIRD_RECORD, IntegerNumberRange.between(5, 6));
			final FilterIndex scalarIndex = new OwnerFilterIndex(ATTRIBUTE_KEY, Integer.class);
			scalarIndex.addRecord(SECOND_RECORD, 8);

			final String fromOneRangeIndex = describe(
				compute(IntegerNumberRange.class, 0, null, behavior, List.of(bothRangeIndexes, scalarIndex))
			);
			final String fromTwoRangeIndexes = describe(
				compute(
					IntegerNumberRange.class, 0, null, behavior,
					List.of(firstRangeIndex, secondRangeIndex, scalarIndex)
				)
			);

			assertEquals(fromOneRangeIndex, fromTwoRangeIndexes);
		}

	}

	/**
	 * The buckets the sweep over several indexes emits, before any cruncher reduces them: both crunchers read only a
	 * part of them - the overlap cruncher each record's first and last threshold, the equalised one the bucket sizes -
	 * so a wrong bucket can pass every histogram comparison above.
	 */
	@Nested
	@DisplayName("bucket sweep")
	class BucketSweepTest {

		/**
		 * Sweeps the filter indexes of the case and requires exactly the buckets the oracle's own sweep emits: strictly
		 * ascending thresholds, every key equal in value, class and scale, every record set equal.
		 *
		 * @param rangeCase the case
		 */
		@ParameterizedTest(name = "{0}")
		@MethodSource(
			"io.evitadb.core.query.extraResult.translator.histogram.producer.RangeHistogramSeveralIndexesTest" +
				"#sweptCases"
		)
		@DisplayName("sweeping several filter indexes emits the buckets one index holding the same ranges emits")
		void shouldEmitBucketsOfOneIndexHoldingSameRanges(@Nonnull RangeCase rangeCase) {
			final ValueToRecordBitmap[] fromSeveralIndexes = sweep(rangeCase);
			assertStrictlyAscending(rangeCase, fromSeveralIndexes);
			final ValueToRecord[] fromOneIndex = oneIndex(rangeCase)
				.getRangeHistogramOfAllRecords(innerNumericType(rangeCase), rangeCase.decimalPlaces())
				.getBuckets();
			assertEquals(
				describeBuckets(fromOneIndex), describeBuckets(fromSeveralIndexes),
				rangeCase + ": one index (expected) and several indexes (actual) differ"
			);
		}

		/**
		 * The record of the first index starts two ranges at 1; when the shorter one ends at 6, the record must stay
		 * in the buckets at 8 and 9 that only the second index emits.
		 */
		@Test
		@DisplayName("a record stays active at foreign thresholds while another of its ranges sharing a start is open")
		void shouldKeepRecordActiveWhileRangeSharingItsStartRemainsOpen() {
			assertEquals(
				"1(Integer)=[1], 6(Integer)=[1], 8(Integer)=[1, 2], 9(Integer)=[1, 2], 10(Integer)=[1]",
				describeBuckets(sweep(REPEATED_STARTS))
			);
		}

		/**
		 * The record of the first index ends two ranges at 6; it must be gone from the buckets at 8 and 9 that only
		 * the second index emits.
		 */
		@Test
		@DisplayName("a record leaves the sweep where the last of its ranges sharing an end closes")
		void shouldRemoveRecordWhenEveryRangeSharingItsEndCloses() {
			assertEquals(
				"1(Integer)=[1], 3(Integer)=[1], 6(Integer)=[1], 8(Integer)=[2], 9(Integer)=[2]",
				describeBuckets(sweep(REPEATED_ENDS))
			);
		}

		/**
		 * The record of the first index holds `[1,3]` and `[8,10]`; the bucket at 5, which only the second index
		 * emits, lies in the gap between them and must not hold it.
		 */
		@Test
		@DisplayName("a record is absent from a foreign threshold in the gap between two of its ranges")
		void shouldLeaveRecordOutOfGapBetweenItsRanges() {
			assertEquals(
				"1(Integer)=[1], 3(Integer)=[1], 5(Integer)=[2], 8(Integer)=[1], 10(Integer)=[1]",
				describeBuckets(sweep(GAP))
			);
		}

		/**
		 * Every record of {@link RangeHistogramSeveralIndexesTest#THREE_INDEXES} holds ranges in two of the three
		 * indexes; each must stay in the buckets until the last of its ranges ends.
		 */
		@Test
		@DisplayName("records holding ranges in three indexes sharing thresholds stay until their last range ends")
		void shouldKeepRecordsOfThreeIndexesUntilTheirLastRangeEnds() {
			assertEquals(
				"1(Integer)=[1, 2], 4(Integer)=[1, 2], 6(Integer)=[1, 2, 3], 8(Integer)=[1, 2, 3], 10(Integer)=[2, 3]",
				describeBuckets(sweep(THREE_INDEXES))
			);
		}

		/**
		 * One index listed twice counts every range twice, symmetrically at its start and its end, so the sweep must
		 * emit exactly the buckets the index emits on its own - open ends and repeated levels included.
		 */
		@Test
		@DisplayName("an index listed twice gives the buckets it gives on its own")
		void shouldEmitOwnBucketsOfIndexListedTwice() {
			final OwnerFilterIndex index = integerRangeIndex();
			index.addRecord(FIRST_RECORD, IntegerNumberRange.between(1, 6));
			index.addRecord(FIRST_RECORD, IntegerNumberRange.between(1, 10));
			index.addRecord(SECOND_RECORD, IntegerNumberRange.to(4));
			index.addRecord(THIRD_RECORD, IntegerNumberRange.from(6));
			index.addRecord(FOURTH_RECORD, IntegerNumberRange.between(5, 5));
			assertEquals(ownSweepOfIntegerRanges(index), sweepIntegerRanges(List.of(index, index)));
		}

		/**
		 * An index holding nothing but its two border sentinels contributes no bucket and no record, wherever it
		 * stands in the list.
		 */
		@Test
		@DisplayName("an index holding only its sentinels leaves the buckets of a populated index as they are")
		void shouldEmitOwnBucketsOfPopulatedIndexBesideSentinelOnlyIndex() {
			final OwnerFilterIndex populated = integerRangeIndex();
			populated.addRecord(FIRST_RECORD, IntegerNumberRange.to(4));
			populated.addRecord(SECOND_RECORD, IntegerNumberRange.between(2, 8));
			populated.addRecord(THIRD_RECORD, IntegerNumberRange.from(6));
			final OwnerFilterIndex sentinelOnly = integerRangeIndex();
			final String expected = ownSweepOfIntegerRanges(populated);
			assertEquals(expected, sweepIntegerRanges(List.of(sentinelOnly, populated)));
			assertEquals(expected, sweepIntegerRanges(List.of(populated, sentinelOnly)));
		}

		/**
		 * Indexes holding nothing but their sentinels - one never populated, one whose only ranges were removed - give
		 * no bucket at all.
		 */
		@Test
		@DisplayName("indexes holding only their sentinels give no bucket")
		void shouldEmitNoBucketWhenEveryIndexHoldsOnlySentinels() {
			final OwnerFilterIndex neverPopulated = integerRangeIndex();
			final OwnerFilterIndex emptied = integerRangeIndex();
			emptied.addRecord(FIRST_RECORD, IntegerNumberRange.between(1, 5));
			emptied.addRecord(SECOND_RECORD, IntegerNumberRange.between(3, 8));
			emptied.removeRecord(FIRST_RECORD, IntegerNumberRange.between(1, 5));
			emptied.removeRecord(SECOND_RECORD, IntegerNumberRange.between(3, 8));
			// the emptied index alone must hold no point but its sentinels, or the sweep below proves nothing new
			assertEquals("", ownSweepOfIntegerRanges(emptied));
			assertEquals("", sweepIntegerRanges(List.of(neverPopulated, emptied)));
		}

		/**
		 * Inside a transaction the sweep reads the transactional view of every index: it must see ranges added and
		 * removed there before they are committed, the committed copies must keep them, and the original indexes must
		 * be left as they were. A removed range takes along every point of its index that no other range holds, so no
		 * bucket remains at a threshold only the removed ranges held.
		 */
		@Test
		@DisplayName("inside a transaction the sweep sees uncommitted additions and removals of every index")
		void shouldSweepUncommittedAdditionsAndRemovalsInsideTransaction() {
			final OwnerFilterIndex first = integerRangeIndex();
			first.addRecord(FIRST_RECORD, IntegerNumberRange.between(1, 10));
			first.addRecord(SECOND_RECORD, IntegerNumberRange.between(4, 6));
			final OwnerFilterIndex second = integerRangeIndex();
			second.addRecord(THIRD_RECORD, IntegerNumberRange.between(2, 8));

			// the oracles are single indexes holding the state before and after the transaction, built outside of it
			final OwnerFilterIndex before = integerRangeIndex();
			before.addRecord(FIRST_RECORD, IntegerNumberRange.between(1, 10));
			before.addRecord(SECOND_RECORD, IntegerNumberRange.between(4, 6));
			before.addRecord(THIRD_RECORD, IntegerNumberRange.between(2, 8));
			final String expectedBefore = ownSweepOfIntegerRanges(before);
			final OwnerFilterIndex after = integerRangeIndex();
			after.addRecord(FIRST_RECORD, IntegerNumberRange.between(1, 10));
			after.addRecord(FOURTH_RECORD, IntegerNumberRange.between(5, 12));
			after.addRecord(FIRST_RECORD, IntegerNumberRange.between(6, 9));
			final String expectedAfter = ownSweepOfIntegerRanges(after);
			assertEquals(expectedBefore, sweepIntegerRanges(List.of(first, second)));

			assertStateAfterCommit(
				List.of(first, second),
				indexes -> {
					indexes.get(0).addRecord(FOURTH_RECORD, IntegerNumberRange.between(5, 12));
					indexes.get(0).removeRecord(SECOND_RECORD, IntegerNumberRange.between(4, 6));
					// the record of the first index gets a range in the second index too
					indexes.get(1).addRecord(FIRST_RECORD, IntegerNumberRange.between(6, 9));
					indexes.get(1).removeRecord(THIRD_RECORD, IntegerNumberRange.between(2, 8));
					assertEquals(expectedAfter, sweepIntegerRanges(indexes));
				},
				(originals, committed) -> {
					assertEquals(expectedAfter, sweepIntegerRanges(committed));
					assertEquals(expectedBefore, sweepIntegerRanges(originals));
				}
			);
		}

		/**
		 * An index without a range companion cannot be swept; the sweep refuses it instead of skipping it.
		 */
		@Test
		@DisplayName("an index without a range companion is refused")
		void shouldRefuseIndexWithoutRangeCompanion() {
			final FilterIndex rangeIndex = integerRangeIndex();
			rangeIndex.addRecord(FIRST_RECORD, IntegerNumberRange.between(1, 3));
			final FilterIndex scalarIndex = new OwnerFilterIndex(ATTRIBUTE_KEY, Integer.class);
			scalarIndex.addRecord(SECOND_RECORD, 8);
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> FilterIndex.getRangeHistogramOfAllRecords(List.of(rangeIndex, scalarIndex), Integer.class, 0)
			);
			assertTrue(error.getMessage().contains("without a RangeIndex companion"), error.getMessage());
		}

		/**
		 * An index whose range type resolves to another inner numeric type than the one requested would emit bucket
		 * keys of the wrong class; the sweep refuses it as schema/index drift.
		 */
		@Test
		@DisplayName("an index of a range type with another inner numeric type is refused")
		void shouldRefuseIndexOfAnotherInnerNumericType() {
			final FilterIndex integerIndex = integerRangeIndex();
			integerIndex.addRecord(FIRST_RECORD, IntegerNumberRange.between(1, 3));
			final FilterIndex longIndex = new OwnerFilterIndex(ATTRIBUTE_KEY, LongNumberRange.class);
			longIndex.addRecord(SECOND_RECORD, LongNumberRange.between(5L, 8L));
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> FilterIndex.getRangeHistogramOfAllRecords(List.of(integerIndex, longIndex), Integer.class, 0)
			);
			assertTrue(error.getMessage().contains("schema/index drift"), error.getMessage());
			assertTrue(error.getMessage().contains(LongNumberRange.class.getName()), error.getMessage());
		}

	}

}
