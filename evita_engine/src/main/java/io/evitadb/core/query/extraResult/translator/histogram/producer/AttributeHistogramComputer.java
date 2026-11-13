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
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.core.query.algebra.identity.TokenList;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogram;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramProducer.AttributeHistogramRequest;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.InvertedIndexSubSet;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * DTO that aggregates all data necessary for computing histogram for single attribute.
 */
public class AttributeHistogramComputer implements CacheableEvitaResponseExtraResultComputer<CacheableHistogramContract> {
	/** Stable identifier of this histogram computation. */
	private static final long CLASS_ID = -5650176810515329475L;
	/** Detached key, built once on first demand. */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	@Nullable private FormulaKey memoizedKey;

	/**
	 * Contains the name of the reference attribute.
	 */
	@Getter private final String attributeName;
	/**
	 * Contains filtering formula tree that was used to produce results so that computed sub-results can be used for
	 * sorting. A null value means "no mandatory filter applies" — the histogram spans all records reachable through
	 * the attribute indexes.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
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
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	@Nonnull @Getter private final AttributeHistogramRequest request;
	/**
	 * Contains memoized value of {@link #getEstimatedCost()}  of this formula.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private final Long estimatedCost;
	/**
	 * Execution context from initialization phase.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	protected QueryExecutionContext context;
	/**
	 * Contains memoized value of {@link #getCost()}  of this formula.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private Long cost;
	/**
	 * Contains bucket array that contains only entity primary keys that match the {@link #filterFormula}. The array
	 * is initialized during {@link #compute()} method and result is memoized, so it's ensured it's computed only once.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private ValueToRecordBitmap[] memoizedNarrowedBuckets;
	/**
	 * Contains result - computed histogram. The value is initialized during {@link #compute()} method, and it is
	 * memoized, so it's ensured it's computed only once.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private CacheableHistogramContract memoizedResult;

	/** Returns the memoized detached computation key. */
	@Nonnull
	@Override
	public FormulaKey getKey() {
		if (this.memoizedKey == null) {
			this.memoizedKey = FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		return this.memoizedKey;
	}

	/** Returns this holder's stable class identifier. */
	public long getClassId() {
		return CLASS_ID;
	}

	/** Captures schema interpretation, canonical index versions and comparator provenance. */
	@Nonnull
	protected IdentityRecord identity() {
		final List<FilterIndex> indexes = this.request.attributeIndexes();
		final long[] ids = new long[indexes.size()];
		for (int i = 0; i < ids.length; i++) {
			ids[i] = indexes.get(i).getId();
		}
		return new Identity(
			this.attributeName, this.filterFormula == null ? null : this.filterFormula.getKey(),
			this.bucketCount, this.behavior, this.request.attributeSchema().getType().getName(),
			this.request.getDecimalPlaces(), TokenList.ofProducerIds(ids, true),
			this.request.comparator() == Comparator.naturalOrder() ? null : IdentityToken.OPAQUE
		);
	}

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
				// the equalized algorithm never produces an empty bucket, so there is nothing for the
				// "optimized" variant to strip - both behaviours resolve to the same cruncher
				case EQUALIZED, EQUALIZED_OPTIMIZED -> new EqualizedHistogramDataCruncher<>(
					histogramName,
					bucketCount,
					decimalPlaces,
					buckets,
					bucket -> converter.applyAsInt((T) bucket.getValue()),
					bucket -> bucket.getRecordIds().size(),
					value -> decimalPlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).stripTrailingZeros().scaleByPowerOfTen(-1 * decimalPlaces)
				);
			};
		}
	}

	/**
	 * Method creates lambda that converts any {@link Number} value to an int value. The number overflows are checked
	 * in this method an any data precision loss is reported.
	 *
	 * For range-typed attribute schemas (`NumberRange` subtypes) the bucket values are the inner numeric type
	 * emitted by the range histogram sweep (see {@link FilterIndex#toBucketKey(long, Class, int)}),
	 * not the `Range` instance itself — so the converter dispatches on
	 * {@link EvitaDataTypes#resolveRangeInnerNumericType(Class)} when the schema type is a `NumberRange` subtype.
	 *
	 * An array-typed schema (`Integer[]`, `BigDecimal[]`) dispatches on its plain component type, because the filter
	 * index stores and buckets every array element on its own. Range arrays never get here: the range path still
	 * tells them by the declared type and rejects them, since its sweep keeps a single interval per record.
	 */
	@Nonnull
	private static <T extends Comparable<T>> ToIntFunction<T> createNumberToIntegerConverter(@Nonnull AttributeHistogramRequest histogramRequest) {
		final Class<? extends Serializable> schemaType = histogramRequest.attributeSchema().getPlainType();
		// range histograms emit inner-numeric bucket keys, so the converter must look at the inner type
		final Class<? extends Number> innerType = EvitaDataTypes.resolveRangeInnerNumericType(schemaType);
		final Class<?> effectiveType = innerType != null ? innerType : schemaType;
		final ToIntFunction<T> converter;
		if (Byte.class.isAssignableFrom(effectiveType)) {
			converter = value -> (int) ((Byte) value);
		} else if (Short.class.isAssignableFrom(effectiveType)) {
			converter = value -> (int) ((Short) value);
		} else if (Integer.class.isAssignableFrom(effectiveType)) {
			converter = value -> (int) ((Integer) value);
		} else if (Long.class.isAssignableFrom(effectiveType)) {
			converter = value -> {
				final int converted = ((Long) value).intValue();
				if ((Long) value != (long) converted) {
					throw new ArithmeticException("int overflow: " + value);
				}
				return converted;
			};
		} else if (BigDecimal.class.isAssignableFrom(effectiveType)) {
			// the filter index now stores `BigDecimal` attribute values as a scaled `int` (the magnitude
			// `BigDecimal.valueOf(scaledInt, indexedDecimalPlaces)` restores), so the bucket value handed to
			// the converter is already an `Integer` in the integer domain — use it as identity. A genuine
			// `BigDecimal` is still accepted defensively (e.g. an externally-built probe): it is rounded to
			// the schema's indexed precision (HALF_UP) onto the indexed grid via the canonical
			// `NumberUtils.convertToInt` — the same primitive the sort/filter index encoding uses — rather
			// than rejected when its natural scale exceeds indexedDecimalPlaces. `convertToInt` still guards
			// against int overflow via `intValueExact()`, preserving the int-range contract documented below.
			final int places = histogramRequest.getDecimalPlaces();
			converter = value -> {
				if (value instanceof Integer scaledInt) {
					return scaledInt;
				}
				return NumberUtils.convertToInt((BigDecimal) value, places);
			};
		} else {
			throw new GenericEvitaInternalError(
				"Unsupported histogram number type: " + schemaType +
					", supported are byte, short, int. Number types long and BigDecimal are allowed as long as their " +
					"fit into an integer range!"
			);
		}
		return converter;
	}

	/**
	 * Creates a histogram computer for the supplied request and filtering inputs.
	 */
	public AttributeHistogramComputer(
		@Nonnull String attributeName,
		@Nullable Formula filterFormula,
		int bucketCount,
		@Nonnull HistogramBehavior behavior,
		@Nonnull AttributeHistogramRequest request
	) {
		this.attributeName = attributeName;
		this.filterFormula = filterFormula;
		this.bucketCount = bucketCount;
		this.behavior = behavior;
		this.request = request;

		this.estimatedCost = (filterFormula == null ? 0L : filterFormula.getEstimatedCost()) +
			getAttributeIndexes()
				.stream()
				.map(FilterIndex::getAllRecordsFormula)
				.mapToLong(TransactionalDataRelatedStructure::getEstimatedCost)
				.sum() * getOperationCost();
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
		// OPTIMIZED carries a penalty because a sparse histogram has to be recomputed to drop empty buckets.
		// The equalised family never produces an empty bucket, so both of its members do exactly the same
		// single pass and must be costed the same - charging EQUALIZED_OPTIMIZED the recomputation penalty
		// would bias the planner against a behaviour that does no extra work.
		return switch (this.behavior) {
			case STANDARD, EQUALIZED, EQUALIZED_OPTIMIZED -> 2213;
			case OPTIMIZED -> 3320;
		};
	}

	@Nonnull
	@Override
	public CacheableHistogramContract compute() {
		if (this.memoizedResult == null) {
			// create cruncher that will compute the histogram
			final ValueToRecordBitmap[] histogramBuckets = computeNarrowedHistogramBuckets(
				this, this.filterFormula, this.request.comparator()
			);

			if (ArrayUtils.isEmpty(histogramBuckets)) {
				this.memoizedResult = CacheableHistogramContract.EMPTY;
			} else {
				// capture raw (native-typed) min/max from the narrowed buckets so downstream callers (e.g.
				// ReferenceHistogramAccumulator resolving boundary entities via FilterIndex.getRecordsEqualTo)
				// can look up by the exact stored value without BigDecimal ↔ native-type coercion, which would
				// lose precision for BigDecimal attributes whose stored scale exceeds indexedDecimalPlaces
				final Serializable rawMin = histogramBuckets[0].getValue();
				final Serializable rawMax = histogramBuckets[histogramBuckets.length - 1].getValue();
				// the equal-width range behaviors (STANDARD, OPTIMIZED) render overlap histograms: a record whose
				// value-range spans several buckets contributes a single distinct occurrence to each, and the overall
				// count is the distinct-record cardinality — not the inflated per-threshold sum the point-oriented
				// HistogramDataCruncher would produce. OPTIMIZED additionally widens the grid to drop empty coverage
				// gaps. The frequency-equalised behaviors (EQUALIZED, EQUALIZED_OPTIMIZED) deliberately fall through
				// to the point/equalised path below: fed the range sweep, EqualizedHistogramDataCruncher accounts
				// each record at every global stop its range covers (the sweep's rolling active set), which yields the
				// fixed total mass cumulative-frequency equalisation requires — something overlap counting, whose
				// bucket-occurrence sum varies with the grid, cannot provide
				final boolean rangeTyped = EvitaDataTypes.resolveRangeInnerNumericType(
					this.request.attributeSchema().getType()
				) != null;
				final boolean overlapBehavior = this.behavior == HistogramBehavior.STANDARD
					|| this.behavior == HistogramBehavior.OPTIMIZED;
				if (rangeTyped && overlapBehavior) {
					final RangeHistogramDataCruncher rangeCruncher = new RangeHistogramDataCruncher(
						histogramBuckets, this.bucketCount, this.request.getDecimalPlaces(), this.behavior
					);
					this.memoizedResult = new CacheableHistogram(
						rangeCruncher.getHistogram(),
						rangeCruncher.getMaxValue(),
						rawMin,
						rawMax,
						rangeCruncher.getOverallCount()
					);
				} else {
					final HistogramDataCruncherContract<?> histogramCruncher = createHistogramDataCruncher(
						this, this.bucketCount, this.behavior, histogramBuckets
					);
					this.memoizedResult = new CacheableHistogram(
						Objects.requireNonNull(histogramCruncher).getHistogram(),
						histogramCruncher.getMaxValue(),
						rawMin,
						rawMax
					);
				}
			}
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
			// dispatch per-leaf: range-typed leaves go through the sentinel-skipping sweep over the
			// RangeIndex companion; scalar leaves use the existing InvertedIndex-backed bucket array.
			// Mixed inputs are valid — sources for the same histogram name may be reused across scalar
			// and range attributes in the same query (multi-histogram support).
			final AttributeHistogramRequest request = histogramComputer.getRequest();
			final Class<? extends Number> innerNumericType = EvitaDataTypes.resolveRangeInnerNumericType(
				request.attributeSchema().getType()
			);
			final int retainedDecimalPlaces = request.getDecimalPlaces();
			final List<FilterIndex> attributeIndexList = histogramComputer.getAttributeIndexes();
			final ValueToRecord[][] attributeIndexes = new ValueToRecord[attributeIndexList.size()][];
			for (int i = 0; i < attributeIndexList.size(); i++) {
				final FilterIndex fi = attributeIndexList.get(i);
				final InvertedIndexSubSet subset;
				if (fi.getRangeIndex() != null) {
					if (innerNumericType == null) {
						throw new GenericEvitaInternalError(
							"Filter index for attribute `" + request.getAttributeName() + "` carries a " +
								"RangeIndex companion but the attribute schema type `" +
								request.attributeSchema().getType().getName() + "` is not a supported " +
								"NumberRange subtype — schema/index drift."
						);
					}
					subset = fi.getRangeHistogramOfAllRecords(innerNumericType, retainedDecimalPlaces);
				} else {
					subset = fi.getHistogramOfAllRecords();
				}
				attributeIndexes[i] = subset.getBuckets();
			}

			this.memoizedNarrowedBuckets = AttributeHistogramProducer.getCombinedAndFilteredBucketArray(
				filterFormula, attributeIndexes, comparator
			);
		}
		return this.memoizedNarrowedBuckets;
	}

	/**
	 * Detached computation inputs.
	 *
	 * @param attributeName output attribute name
	 * @param filterFormula optional mandatory filtering key
	 * @param bucketCount maximum requested bucket count
	 * @param behavior histogram behavior
	 * @param schemaType schema type name
	 * @param indexedDecimalPlaces indexed decimal precision
	 * @param attributeIndexes canonical filter-index versions
	 * @param comparator null for natural order, opaque for a custom comparator
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(
		@Nonnull String attributeName,
		@Nullable FormulaKey filterFormula,
		int bucketCount,
		@Nonnull HistogramBehavior behavior,
		@Nonnull String schemaType,
		int indexedDecimalPlaces,
		@Nonnull TokenList attributeIndexes,
		@Nullable IdentityToken comparator
	) implements IdentityRecord {
		/** Returns the owning holder's identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every component in declaration order. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.attributeName);
			accumulator.add(this.filterFormula);
			accumulator.add(this.bucketCount);
			accumulator.add(this.behavior);
			accumulator.add(this.schemaType);
			accumulator.add(this.indexedDecimalPlaces);
			accumulator.add(this.attributeIndexes);
			accumulator.add(this.comparator);
		}
	}
}
