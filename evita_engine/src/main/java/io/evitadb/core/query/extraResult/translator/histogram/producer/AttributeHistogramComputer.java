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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogram;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.core.query.extraResult.translator.histogram.cache.FlattenedHistogramComputer;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramProducer.AttributeHistogramRequest;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.InvertedIndexSubSet;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.stream.LongStream;

import static java.util.Optional.ofNullable;

/**
 * DTO that aggregates all data necessary for computing histogram for single attribute.
 */
public class AttributeHistogramComputer implements CacheableEvitaResponseExtraResultComputer<CacheableHistogramContract> {
	/**
	 * Contains the name of the reference attribute.
	 */
	@Getter private final String attributeName;
	/**
	 * Contains reference to the lambda that needs to be executed THE FIRST time the histogram produced by this computer
	 * instance is really computed (and memoized).
	 */
	private final Consumer<CacheableEvitaResponseExtraResultComputer<CacheableHistogramContract>> onComputationCallback;
	/**
	 * Contains filtering formula tree that was used to produce results so that computed sub-results can be used for
	 * sorting. A null value means "no mandatory filter applies" — the histogram spans all records reachable through
	 * the attribute indexes.
	 */
	@Nullable private final Formula filterFormula;
	/**
	 * Bucket count contains desired count of histogram columns=buckets. Output histogram bucket count must never exceed
	 * this value, but might be optimized to lower count when there are big gaps between columns.
	 */
	private final int bucketCount;
	/**
	 * Contains behavior that was requested by the user in the query.
	 *
	 * @see HistogramBehavior
	 */
	@Nonnull private final HistogramBehavior behavior;
	/**
	 * Contains original {@link AttributeHistogramRequest} that was collected during query examination.
	 */
	@Nonnull @Getter private final AttributeHistogramRequest request;
	/**
	 * Contains memoized value of {@link #getEstimatedCost()}  of this formula.
	 */
	private final Long estimatedCost;
	/**
	 * Contains memoized value of {@link #getHash()} method.
	 */
	private final Long hash;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} method.
	 */
	private final long[] transactionalIds;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} computed hash.
	 */
	private final Long transactionalIdHash;
	/**
	 * Execution context from initialization phase.
	 */
	protected QueryExecutionContext context;
	/**
	 * Contains memoized value of {@link #getCost()}  of this formula.
	 */
	private Long cost;
	/**
	 * Contains memoized value of {@link #getCostToPerformanceRatio()} of this formula.
	 */
	private Long costToPerformance;
	/**
	 * Contains bucket array that contains only entity primary keys that match the {@link #filterFormula}. The array
	 * is initialized during {@link #compute()} method and result is memoized, so it's ensured it's computed only once.
	 */
	private ValueToRecordBitmap[] memoizedNarrowedBuckets;
	/**
	 * Contains result - computed histogram. The value is initialized during {@link #compute()} method, and it is
	 * memoized, so it's ensured it's computed only once.
	 */
	private CacheableHistogramContract memoizedResult;

	/**
	 * Method creates instance of histogram data cruncher that computes optimal histogram for the attribute.
	 * Returns either {@link HistogramDataCruncher} or {@link EqualizedHistogramDataCruncher} based on behavior.
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	private static <T extends Comparable<T>> HistogramDataCruncherContract<?> createHistogramDataCruncher(
		@Nonnull AttributeHistogramComputer histogramComputer,
		int bucketCount,
		@Nonnull HistogramBehavior behavior,
		@Nonnull ValueToRecordBitmap[] buckets
	) {
		if (ArrayUtils.isEmpty(buckets)) {
			return null;
		} else {
			// first create converter that converts unknown Number attribute to the integer
			final AttributeHistogramRequest attributeHistogramRequest = histogramComputer.getRequest();
			final ToIntFunction<T> converter = createNumberToIntegerConverter(attributeHistogramRequest);
			final int decimalPlaces = attributeHistogramRequest.getDecimalPlaces();
			final String histogramName = " attribute `" + histogramComputer.getAttributeName() + "` histogram";

			return switch (behavior) {
				case STANDARD ->
					new HistogramDataCruncher<>(
					histogramName,
					bucketCount,
					decimalPlaces,
					buckets,
					bucket -> converter.applyAsInt((T) bucket.getValue()),
					bucket -> bucket.getRecordIds().size(),
					value -> decimalPlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).stripTrailingZeros().scaleByPowerOfTen(-1 * decimalPlaces),
					value -> decimalPlaces == 0 ? value.intValueExact() : value.stripTrailingZeros().scaleByPowerOfTen(decimalPlaces).intValueExact()
				);
				case OPTIMIZED -> HistogramDataCruncher.createOptimalHistogram(
					histogramName,
					bucketCount,
					decimalPlaces,
					buckets,
					bucket -> converter.applyAsInt((T) bucket.getValue()),
					bucket -> bucket.getRecordIds().size(),
					value -> decimalPlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).stripTrailingZeros().scaleByPowerOfTen(-1 * decimalPlaces),
					value -> decimalPlaces == 0 ? value.intValueExact() : value.stripTrailingZeros().scaleByPowerOfTen(decimalPlaces).intValueExact()
				);
				case EQUALIZED -> new EqualizedHistogramDataCruncher<>(
					histogramName,
					bucketCount,
					decimalPlaces,
					buckets,
					bucket -> converter.applyAsInt((T) bucket.getValue()),
					bucket -> bucket.getRecordIds().size(),
					value -> decimalPlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).stripTrailingZeros().scaleByPowerOfTen(-1 * decimalPlaces),
					EqualizedHistogramDataCruncher.BucketCountMode.EXACT
				);
				case EQUALIZED_OPTIMIZED -> new EqualizedHistogramDataCruncher<>(
					histogramName,
					bucketCount,
					decimalPlaces,
					buckets,
					bucket -> converter.applyAsInt((T) bucket.getValue()),
					bucket -> bucket.getRecordIds().size(),
					value -> decimalPlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).stripTrailingZeros().scaleByPowerOfTen(-1 * decimalPlaces),
					EqualizedHistogramDataCruncher.BucketCountMode.ADAPTIVE
				);
			};
		}
	}

	/**
	 * Method creates lambda that converts any {@link Number} value to an int value. The number overflows are checked
	 * in this method an any data precision loss is reported.
	 */
	@Nonnull
	private static <T extends Comparable<T>> ToIntFunction<T> createNumberToIntegerConverter(@Nonnull AttributeHistogramRequest histogramRequest) {
		final ToIntFunction<T> converter;
		if (Byte.class.isAssignableFrom(histogramRequest.attributeSchema().getType())) {
			converter = value -> (int) ((Byte) value);
		} else if (Short.class.isAssignableFrom(histogramRequest.attributeSchema().getType())) {
			converter = value -> (int) ((Short) value);
		} else if (Integer.class.isAssignableFrom(histogramRequest.attributeSchema().getType())) {
			converter = value -> (int) ((Integer) value);
		} else if (Long.class.isAssignableFrom(histogramRequest.attributeSchema().getType())) {
			converter = value -> {
				final int converted = ((Long) value).intValue();
				if ((Long) value != (long) converted) {
					throw new ArithmeticException("int overflow: " + value);
				}
				return converted;
			};
		} else if (BigDecimal.class.isAssignableFrom(histogramRequest.attributeSchema().getType())) {
			converter = value -> ((BigDecimal) value).stripTrailingZeros()
				.scaleByPowerOfTen(histogramRequest.getDecimalPlaces())
				.intValue();
		} else {
			throw new GenericEvitaInternalError(
				"Unsupported histogram number type: " + histogramRequest.attributeSchema().getType() +
					", supported are byte, short, int. Number types long and BigDecimal are allowed as long as their " +
					"fit into an integer range!"
			);
		}
		return converter;
	}

	public AttributeHistogramComputer(
		@Nonnull String attributeName,
		@Nullable Formula filterFormula,
		int bucketCount,
		@Nonnull HistogramBehavior behavior,
		@Nonnull AttributeHistogramRequest request
	) {
		this(attributeName, null, filterFormula, bucketCount, behavior, request);
	}

	private AttributeHistogramComputer(
		@Nonnull String attributeName,
		@Nullable Consumer<CacheableEvitaResponseExtraResultComputer<CacheableHistogramContract>> onComputationCallback,
		@Nullable Formula filterFormula,
		int bucketCount,
		@Nonnull HistogramBehavior behavior,
		@Nonnull AttributeHistogramRequest request
	) {
		this.attributeName = attributeName;
		this.onComputationCallback = onComputationCallback;
		this.filterFormula = filterFormula;
		this.bucketCount = bucketCount;
		this.behavior = behavior;
		this.request = request;

		this.hash = HASH_FUNCTION.hashLongs(
			LongStream.concat(
				LongStream.of(
					bucketCount,
					behavior.ordinal(),
					filterFormula == null ? 0L : filterFormula.getHash()
				),
				LongStream.of(
					request.attributeIndexes()
						.stream()
						.mapToLong(FilterIndex::getId)
						.sorted()
						.toArray()
				)
			).toArray()
		);
		this.transactionalIds = LongStream.concat(
			filterFormula == null ? LongStream.empty() : LongStream.of(filterFormula.gatherTransactionalIds()),
			request.attributeIndexes()
				.stream()
				.mapToLong(FilterIndex::getId)
		).toArray();
		this.transactionalIdHash = HASH_FUNCTION.hashLongs(
			Arrays.stream(this.transactionalIds)
				.distinct()
				.sorted()
				.toArray()
		);
		this.estimatedCost = (filterFormula == null ? 0L : filterFormula.getEstimatedCost()) +
			getAttributeIndexes()
				.stream()
				.map(FilterIndex::getAllRecordsFormula)
				.mapToLong(TransactionalDataRelatedStructure::getEstimatedCost)
				.sum() * getOperationCost();
	}

	@Override
	public FlattenedHistogramComputer toSerializableResult(long extraResultHash, @Nonnull LongHashFunction hashFunction) {
		return new FlattenedHistogramComputer(
			extraResultHash,
			getHash(),
			Arrays.stream(gatherTransactionalIds())
				.distinct()
				.sorted()
				.toArray(),
			Objects.requireNonNull(compute())
		);
	}

	@Override
	public int getSerializableResultSizeEstimate() {
		return FlattenedHistogramComputer.estimateSize(
			gatherTransactionalIds(),
			compute()
		);
	}

	@Nonnull
	@Override
	public CacheableEvitaResponseExtraResultComputer<CacheableHistogramContract> getCloneWithComputationCallback(
		@Nonnull Consumer<CacheableEvitaResponseExtraResultComputer<CacheableHistogramContract>> selfOperator
	) {
		return new AttributeHistogramComputer(
			this.attributeName, selfOperator, this.filterFormula, this.bucketCount, this.behavior, this.request
		);
	}

	@Nonnull
	public List<FilterIndex> getAttributeIndexes() {
		return this.request.attributeIndexes();
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		this.context = executionContext;
		if (this.filterFormula != null) {
			this.filterFormula.initialize(executionContext);
		}
	}

	@Override
	public long getHash() {
		return this.hash;
	}

	@Override
	public long getTransactionalIdHash() {
		return this.transactionalIdHash;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		return this.transactionalIds;
	}

	@Override
	public long getEstimatedCost() {
		return this.estimatedCost;
	}

	@Override
	public long getCost() {
		if (this.cost == null) {
			if (this.memoizedResult == null) {
				return Long.MAX_VALUE;
			} else {
				this.cost = (this.filterFormula == null ? 0L : this.filterFormula.getCost()) +
					Arrays.stream(computeNarrowedHistogramBuckets(this, this.filterFormula, this.request.comparator()))
						.mapToInt(it -> it.getRecordIds().size())
						.sum() * getOperationCost();

			}
		}
		return this.cost;
	}

	@Override
	public long getOperationCost() {
		// if the behavior is optimized we add 33% penalty because some histograms would need to be computed twice
		// equalized variants have similar cost structure
		return switch (this.behavior) {
			case STANDARD, EQUALIZED -> 2213;
			case OPTIMIZED, EQUALIZED_OPTIMIZED -> 3320;
		};
	}

	@Override
	public long getCostToPerformanceRatio() {
		if (this.costToPerformance == null) {
			if (this.memoizedResult == null) {
				return Long.MAX_VALUE;
			} else {
				this.costToPerformance = getCost() / (getOperationCost() * this.bucketCount);
			}
		}
		return this.costToPerformance;
	}

	@Nonnull
	@Override
	public CacheableHistogramContract compute() {
		if (this.memoizedResult == null) {
			// create cruncher that will compute the histogram
			final ValueToRecordBitmap[] histogramBuckets = computeNarrowedHistogramBuckets(
				this, this.filterFormula, this.request.comparator()
			);
			final HistogramDataCruncherContract<?> histogramCruncher = createHistogramDataCruncher(
				this, this.bucketCount, this.behavior, histogramBuckets
			);

			if (histogramCruncher != null) {
				// capture raw (native-typed) min/max from the narrowed buckets so downstream callers (e.g.
				// ReferenceHistogramAccumulator resolving boundary entities via FilterIndex.getRecordsEqualTo)
				// can look up by the exact stored value without BigDecimal ↔ native-type coercion, which would
				// lose precision for BigDecimal attributes whose stored scale exceeds indexedDecimalPlaces
				final Serializable rawMin = histogramBuckets[0].getValue();
				final Serializable rawMax = histogramBuckets[histogramBuckets.length - 1].getValue();
				this.memoizedResult = new CacheableHistogram(
					histogramCruncher.getHistogram(),
					histogramCruncher.getMaxValue(),
					rawMin,
					rawMax
				);
			} else {
				this.memoizedResult = CacheableHistogramContract.EMPTY;
			}

			ofNullable(this.onComputationCallback).ifPresent(it -> it.accept(this));
		}
		return this.memoizedResult;
	}

	/**
	 * Collects histogram buckets intersected with the computer's {@link #filterFormula}. The input formula is expected
	 * to already have all {@link io.evitadb.core.query.algebra.filter.AttributeRangeCarrierFormula} carriers relaxed
	 * by the caller (typically via
	 * {@link io.evitadb.core.query.extraResult.translator.common.UserFilterRelaxer}) so this method performs no
	 * additional formula-tree surgery — it simply threads the filter through the bucket-array combiner.
	 */
	private ValueToRecordBitmap[] computeNarrowedHistogramBuckets(
		@Nonnull AttributeHistogramComputer histogramComputer,
		@Nullable Formula filterFormula,
		@SuppressWarnings("rawtypes") @Nonnull Comparator comparator
	) {
		if (this.memoizedNarrowedBuckets == null) {
			// collect all INDEX histogram subsets that will be used for the computation
			final ValueToRecordBitmap[][] attributeIndexes = histogramComputer
				.getAttributeIndexes()
				.stream()
				.map(FilterIndex::getHistogramOfAllRecords)
				.map(InvertedIndexSubSet::getHistogramBuckets)
				.toArray(ValueToRecordBitmap[][]::new);

			this.memoizedNarrowedBuckets = AttributeHistogramProducer.getCombinedAndFilteredBucketArray(
				filterFormula, attributeIndexes, comparator
			);
		}
		return this.memoizedNarrowedBuckets;
	}

}
