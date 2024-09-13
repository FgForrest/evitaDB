/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.sort.price;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordsLookupResult;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramProducer;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Collection;

/**
 * FilteredPriceRecordsCollector allows unifying the logic for accessing {@link PriceRecord} referenced in current
 * query computation and narrowing them even more to match exactly the result produced by filtering formula.
 * The same logic is used by {@link FilteredPricesSorter} and {@link PriceHistogramProducer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@NotThreadSafe
public class FilteredPriceRecordsCollector {
	/**
	 * Contains list of all {@link Formula} that provide access to the {@link PriceRecord} used in computation.
	 */
	@Nonnull @Getter private final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors;
	/**
	 * Contains result of the matching algorithm that pairs {@link PriceRecord} to entity primary keys visible in
	 * formula result.
	 */
	@Nonnull @Getter private final FilteredPriceRecordsLookupResult result;
	/**
	 * The execution context for running query operations in the {@link FilteredPriceRecordsCollector} class.
	 */
	@Nonnull private final QueryExecutionContext context;

	public FilteredPriceRecordsCollector(
		@Nonnull RoaringBitmap filteredResults,
		@Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors,
		@Nonnull QueryExecutionContext context
	) {
		this.context = context;
		this.filteredPriceRecordAccessors = filteredPriceRecordAccessors;
		this.result = computeResult(filteredResults, filteredPriceRecordAccessors, context);
	}

	public FilteredPriceRecordsCollector(
		@Nonnull FilteredPriceRecordsLookupResult result,
		@Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors,
		@Nonnull QueryExecutionContext context
	) {
		this.context = context;
		this.filteredPriceRecordAccessors = filteredPriceRecordAccessors;
		this.result = result;
	}

	/**
	 * Method combines data present in this instance with the `filteredResult` passed as an argument. Method doesn't
	 * alter inner data of this object. Method combines existing array of {@link PriceRecord} with new one, that is
	 * computed for entity primary keys passed in the `filteredResults` parameter. Duplicated {@link PriceRecord} are
	 * collapsed and only distinct {@link PriceRecord} are returned to the output of this method.
	 */
	public PriceRecordContract[] combineResultWithAndReturnPriceRecords(@Nonnull RoaringBitmap filteredResults) {
		// compute new lookup result for passed entity primary keys
		final FilteredPriceRecordsLookupResult subResult = computeResult(filteredResults, this.filteredPriceRecordAccessors, this.context);
		// these two arrays will be merged together
		final PriceRecordContract[] originalRecords = this.result.getPriceRecords();
		final PriceRecordContract[] addedRecords = subResult.getPriceRecords();

		if (ArrayUtils.isEmpty(addedRecords)) {
			return originalRecords;
		}

		// lets use elastic array
		final CompositeObjectArray<PriceRecordContract> join = new CompositeObjectArray<>(PriceRecordContract.class, false);
		int i = 0;
		// include every record in original array
		for (PriceRecordContract originalRecord : originalRecords) {
			// but before it add all records with lesser entity primary key from the other array to the result
			while (i < addedRecords.length && addedRecords[i].entityPrimaryKey() < originalRecord.entityPrimaryKey()) {
				join.add(addedRecords[i++]);
			}
			// now include the record from original array
			join.add(originalRecord);
			// skip all records in the other array if they match the same entity primary key as in original array
			while (i < addedRecords.length && addedRecords[i].entityPrimaryKey() == originalRecord.entityPrimaryKey()) {
				i++;
			}
		}
		// append the rest of other array in a distinct way
		while (i < addedRecords.length) {
			join.add(addedRecords[i++]);
		}

		return join.toArray();
	}

	/**
	 * This method collects set of price records that corresponds with entity PKs in computed bitmap and that can be
	 * used for sorting them. Method fills that information in the `result` I/O parameter and returns also integer
	 * array of entity ids for which the price records were not found.
	 */
	@Nonnull
	protected FilteredPriceRecordsLookupResult computeResult(
		@Nonnull RoaringBitmap filteredResults,
		@Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors,
		@Nonnull QueryExecutionContext context
	) {
		return FilteredPriceRecords.collectFilteredPriceRecordsFromPriceRecordAccessors(
			filteredPriceRecordAccessors,
			filteredResults,
			context
		);
	}

}
