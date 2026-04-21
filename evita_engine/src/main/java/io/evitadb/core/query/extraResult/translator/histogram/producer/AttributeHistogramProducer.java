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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.common.RangeCarrierGroup;
import io.evitadb.core.query.extraResult.translator.common.UserFilterRelaxer;
import io.evitadb.core.query.extraResult.translator.histogram.FilterFormulaAttributeOptimizeVisitor;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.Functions;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * This class contains logic that creates single {@link AttributeHistogram} DTO containing {@link Histogram} for all
 * attributes requested by {@link io.evitadb.api.query.require.AttributeHistogram} require query in input query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeHistogramProducer implements ExtraResultProducer {
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
	 * Contains filtering formula tree that was used to produce results so that computed sub-results can be used for
	 * sorting.
	 */
	@Nonnull private final Formula filterFormula;
	/**
	 * Contains list of requests for attribute histograms. Requests contain all data necessary for histogram computation.
	 */
	private final Map<String, AttributeHistogramRequest> histogramRequests;

	/**
	 * Method combines arrays of {@link ValueToRecordBitmap} (i.e. two-dimensional matrix) together so that in the output
	 * array the buckets are flattened to one-dimensional representation containing only distinct {@link ValueToRecordBitmap#getValue()}
	 * in a way that two or more {@link ValueToRecordBitmap} sharing same {@link ValueToRecordBitmap#getValue()} are combined
	 * into a single bucket.
	 *
	 * The bucket record ids are also filtered to match `filteringFormula` output (i.e. the bucket will not contain a
	 * record that is not part of the `filteringFormula` output). Empty buckets are discarded along the way.
	 */
	static ValueToRecordBitmap[] getCombinedAndFilteredBucketArray(
		@Nullable Formula filteringFormula,
		@Nonnull ValueToRecordBitmap[][] histogramBitmaps,
		@SuppressWarnings("rawtypes") @Nonnull Comparator comparator
	) {
		if (ArrayUtils.isEmpty(histogramBitmaps)) {
			return new ValueToRecordBitmap[0];
		}

		final RoaringBitmap filteredRecordIds;
		if (filteringFormula == null) {
			filteredRecordIds = null;
		} else {
			filteredRecordIds = RoaringBitmapBackedBitmap.getRoaringBitmap(filteringFormula.compute());
		}
		final CompositeObjectArray<ValueToRecordBitmap> finalBuckets = new CompositeObjectArray<>(ValueToRecordBitmap.class, false);

		if (histogramBitmaps.length > 1) {
			// per-input cursor into the ValueToRecordBitmap arrays; -1 marks exhausted
			final int[] indexes = new int[histogramBitmaps.length];
			// positions in `indexes` that must advance after the current iteration
			final int[] incIndexes = new int[histogramBitmaps.length];
			// buckets sharing the current minimum value, to be merged into one output bucket
			final ValueToRecordBitmap[] combinationPack = new ValueToRecordBitmap[histogramBitmaps.length];

			do {
				// last filled position in combinationPack / incIndexes
				int combinationPackPeek = 0;
				Serializable minValue = null;
				for (int i = 0; i < indexes.length; i++) {
					int index = indexes[i];
					if (index > -1) {
						final ValueToRecordBitmap examinedBucket = histogramBitmaps[i][index];
						final Serializable histogramValue = examinedBucket.getValue();

						//noinspection unchecked
						final int comparisonResult = minValue == null ? -1 : comparator.compare(histogramValue, minValue);
						if (comparisonResult < 0) {
							// new minimum — restart the combination pack
							combinationPackPeek = 0;
							minValue = examinedBucket.getValue();
							combinationPack[combinationPackPeek] = examinedBucket;
							incIndexes[combinationPackPeek] = i;
						} else if (comparisonResult == 0) {
							// tie with current minimum — append to the combination pack
							combinationPack[++combinationPackPeek] = examinedBucket;
							incIndexes[combinationPackPeek] = i;
						}
					}
				}
				if (combinationPackPeek == 0) {
					// single bucket with the distinct min value
					addBucket(filteredRecordIds, finalBuckets, combinationPack[0]);
					incrementBitmapIndex(histogramBitmaps, indexes, incIndexes[0]);
				} else {
					// multiple buckets share the min value and must be combined
					addBucket(filteredRecordIds, finalBuckets, Arrays.copyOfRange(combinationPack, 0, combinationPackPeek + 1));
					incrementBitmapIndex(histogramBitmaps, indexes, Arrays.copyOfRange(incIndexes, 0, combinationPackPeek + 1));
				}
			} while (endNotReached(indexes));
		} else if (histogramBitmaps.length == 1) {
			// single input — no merging needed
			for (ValueToRecordBitmap bucket : histogramBitmaps[0]) {
				addBucket(filteredRecordIds, finalBuckets, bucket);
			}
		}

		return finalBuckets.toArray();
	}

	/**
	 * End is reached when all indexes contain -1 value.
	 */
	private static boolean endNotReached(int[] indexes) {
		for (int index : indexes) {
			if (index > -1) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Method combines all `theBucket` into a single bucket with shared distinct {@link ValueToRecordBitmap#getValue()}.
	 * Record ids are combined by OR relation and then filtered by AND relation with `filteredRecordIds` (when
	 * `filteredRecordIds` is `null` the combined OR result is used as-is).
	 */
	private static void addBucket(
		@Nullable RoaringBitmap filteredRecordIds,
		@Nonnull CompositeObjectArray<ValueToRecordBitmap> finalBuckets,
		@Nonnull ValueToRecordBitmap[] theBucket
	) {
		final RoaringBitmap[] bucketBitmaps = new RoaringBitmap[theBucket.length];
		for (int i = 0; i < theBucket.length; i++) {
			bucketBitmaps[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(theBucket[i].getRecordIds());
		}
		final RoaringBitmap combined = RoaringBitmap.or(bucketBitmaps);
		final BaseBitmap recordIds = new BaseBitmap(
			filteredRecordIds == null ? combined : RoaringBitmap.and(filteredRecordIds, combined)
		);
		if (!recordIds.isEmpty()) {
			finalBuckets.add(
				new ValueToRecordBitmap(
					theBucket[0].getValue(),
					recordIds
				)
			);
		}
	}

	/**
	 * Method filters out record ids of the {@link ValueToRecordBitmap} that are not part of `filteredRecordIds` and
	 * produces new bucket with filtered data.
	 */
	private static void addBucket(
		@Nullable RoaringBitmap filteredRecordIds,
		@Nonnull CompositeObjectArray<ValueToRecordBitmap> finalBuckets,
		@Nonnull ValueToRecordBitmap theBucket
	) {
		final Bitmap recordIds = filteredRecordIds == null ?
			theBucket.getRecordIds() :
			new BaseBitmap(
				RoaringBitmap.and(
					filteredRecordIds,
					RoaringBitmapBackedBitmap.getRoaringBitmap(theBucket.getRecordIds())
				)
			);

		if (!recordIds.isEmpty()) {
			finalBuckets.add(
				new ValueToRecordBitmap(
					theBucket.getValue(),
					recordIds
				)
			);
		}
	}

	/**
	 * Method increments indexes in `indexes` by one, if they match index in `bitmapIndexes` array. If the index exceeds
	 * the number of elements in respective `histogramBitmap`, the index is set to -1 which marks end of the stream.
	 */
	private static void incrementBitmapIndex(
		@Nonnull ValueToRecordBitmap[][] histogramBitmaps,
		@Nonnull int[] indexes,
		@Nonnull int[] bitmapIndexes
	) {
		for (int bitmapIndex : bitmapIndexes) {
			incrementBitmapIndex(histogramBitmaps, indexes, bitmapIndex);
		}
	}

	/**
	 * Method increment number (index) in `indexes` array on position `bitmapIndex` by one. If the index reaches number
	 * of available records in `histogramBitmap` on `bitmapIndex`, the index is set to -1 which marks end of the stream.
	 */
	private static void incrementBitmapIndex(
		@Nonnull ValueToRecordBitmap[][] histogramBitmaps,
		@Nonnull int[] indexes,
		int bitmapIndex
	) {
		if (histogramBitmaps[bitmapIndex].length == indexes[bitmapIndex] + 1) {
			indexes[bitmapIndex] = -1;
		} else {
			indexes[bitmapIndex]++;
		}
	}

	/**
	 * If we combine all records for the attribute in filter indexes with current filtering formula result - is there
	 * at least single entity primary key left?
	 */
	private static boolean hasSenseWithMandatoryFilter(
		@Nullable Formula filteringFormula,
		@Nonnull AttributeHistogramRequest request
	) {
		if (filteringFormula == null) {
			return true;
		}
		// collect all records from the filter indexes for this attribute
		final Bitmap[] histogramBitmaps = request
			.attributeIndexes()
			.stream()
			.map(FilterIndex::getAllRecords)
			.toArray(Bitmap[]::new);
		// filter out attributes that don't make sense even with mandatory filtering constraints
		final Formula histogramBitmapsFormula;
		if (histogramBitmaps.length == 0) {
			return false;
		} else if (histogramBitmaps.length == 1) {
			histogramBitmapsFormula = new ConstantFormula(histogramBitmaps[0]);
		} else {
			final long[] indexTransactionIds = request.attributeIndexes()
				.stream()
				.mapToLong(FilterIndex::getId)
				.toArray();
			histogramBitmapsFormula = new OrFormula(indexTransactionIds, histogramBitmaps);
		}
		final AndFormula finalFormula = new AndFormula(
			histogramBitmapsFormula,
			filteringFormula
		);
		return !finalFormula
			.compute()
			.isEmpty();
	}

	public AttributeHistogramProducer(
		int bucketCount,
		@Nonnull HistogramBehavior behavior,
		@Nonnull Formula filterFormula
	) {
		this.bucketCount = bucketCount;
		this.behavior = behavior;
		this.filterFormula = filterFormula;
		this.histogramRequests = new HashMap<>();
	}

	/**
	 * Adds a request for histogram computation passing all data necessary for the computation.
	 * Method doesn't compute the histogram - just registers the requirement to be resolved later
	 * in the {@link ExtraResultProducer#fabricate(QueryExecutionContext)} )}  method.
	 */
	public void addAttributeHistogramRequest(
		@Nonnull AttributeSchemaContract attributeSchema,
		@SuppressWarnings("rawtypes") @Nonnull Comparator comparator,
		@Nonnull List<FilterIndex> attributeIndexes
	) {
		this.histogramRequests.put(
			attributeSchema.getName(),
			new AttributeHistogramRequest(
				attributeSchema,
				comparator,
				attributeIndexes
			)
		);
	}

	@Nullable
	@Override
	public EvitaResponseExtraResult fabricate(@Nonnull QueryExecutionContext context) {
		// optimize for best reuse of memoized intermediate results
		final Formula optimizedFormula = FilterFormulaAttributeOptimizeVisitor.optimize(this.filterFormula, this.histogramRequests.keySet());

		// harvest requested-bucket predicates set by AttributeBetweenTranslator on BetweenAttributeFormula instances
		// — they feed the per-bucket `requested` flag on the output DTO; only histogram attributes are read back
		final Map<String, Predicate<BigDecimal>> userFilterFormulaPredicates = CollectionUtils.createHashMap(
			this.histogramRequests.size()
		);
		for (final UserFilterFormula userFilter : FormulaFinder.find(optimizedFormula, UserFilterFormula.class, LookUp.DEEP)) {
			for (final AttributeFormula attributeFormula : FormulaFinder.find(userFilter, AttributeFormula.class, LookUp.DEEP)) {
				ofNullable(attributeFormula.getRequestedPredicate())
					.ifPresent(it -> userFilterFormulaPredicates.put(attributeFormula.getAttributeName(), it));
			}
		}

		// peel attribute-range carriers from userFilter so the baseline does not contract under the user's own slider
		// picks; facet (FACET_IMPACT) and price-range (PRICE_HISTOGRAM) carriers stay, so the histogram still reflects
		// those narrowings. Relaxer's EmptyFormula sentinel means every carrier was peeled — map it to null so the
		// histogram spans the entire catalog.
		final Formula relaxedBaseline = UserFilterRelaxer.relax(
			optimizedFormula, RangeCarrierGroup.ATTRIBUTE_HISTOGRAM
		);
		final Formula histogramBaselineFormula = relaxedBaseline == EmptyFormula.INSTANCE ? null : relaxedBaseline;

		return new AttributeHistogram(
			this.histogramRequests.entrySet()
				.stream()
				.filter(entry -> hasSenseWithMandatoryFilter(histogramBaselineFormula, entry.getValue()))
				.map(entry -> {
					final AttributeHistogramRequest histogramRequest = entry.getValue();
					final AttributeHistogramComputer computer = new AttributeHistogramComputer(
						histogramRequest.getAttributeName(),
						histogramBaselineFormula, this.bucketCount, this.behavior, histogramRequest
					);
					final CacheableHistogramContract optimalHistogram = context.analyse(computer).compute();
					if (optimalHistogram == CacheableHistogramContract.EMPTY) {
						return null;
					} else {
						return new AttributeHistogramWrapper(
							entry.getKey(),
							optimalHistogram.convertToHistogram(
								ofNullable(userFilterFormulaPredicates.get(entry.getKey()))
									.orElseGet(Functions::alwaysTrue)
							)
						);
					}
				})
				.filter(Objects::nonNull)
				.collect(
					Collectors.toMap(
						AttributeHistogramWrapper::attributeName,
						AttributeHistogramWrapper::histogram
					)
				)
		);
	}

	@Nonnull
	@Override
	public String getDescription() {
		if (this.histogramRequests.size() == 1) {
			return "attribute `" + this.histogramRequests.keySet().iterator().next() + "` histogram";
		} else {
			return "attributes " + this.histogramRequests.keySet().stream().map(it -> '`' + it + '`').collect(Collectors.joining(" ,")) + " histogram";
		}
	}

	/**
	 * DTO that aggregates all data necessary for computing histogram for single attribute.
	 *
	 * @param attributeSchema  Refers to attribute schema.
	 * @param comparator       Comparator to use for manipulation with {@link ValueToRecordBitmap#getValue()} values.
	 * @param attributeIndexes Refers to all filter indexes that map entity primary keys and their associated values for this attribute.
	 */
	public record AttributeHistogramRequest(
		@Nonnull AttributeSchemaContract attributeSchema,
		@SuppressWarnings("rawtypes") @Nonnull Comparator comparator,
		@Nonnull List<FilterIndex> attributeIndexes
	) {
		/**
		 * Returns name of the attribute.
		 */
		@Nonnull
		public String getAttributeName() {
			return this.attributeSchema.getName();
		}

		/**
		 * Returns number of maximum decimal places allowed for this attribute.
		 */
		public int getDecimalPlaces() {
			return this.attributeSchema.getIndexedDecimalPlaces();
		}

	}

	/**
	 * Simple tuple for passing data in stream.
	 */

	private record AttributeHistogramWrapper(@Nonnull String attributeName, @Nonnull HistogramContract histogram) {
	}

}
