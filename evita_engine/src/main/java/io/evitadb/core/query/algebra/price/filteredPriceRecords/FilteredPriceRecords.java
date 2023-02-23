/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.algebra.price.filteredPriceRecords;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordsLookupResult;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.index.array.CompositeIntArray;
import io.evitadb.index.array.CompositeObjectArray;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.iterator.BatchArrayIterator;
import io.evitadb.index.iterator.RoaringBitmapBatchArrayIterator;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Filtered price records provide access to {@link PriceRecord price records} that are involved in formula entity id
 * computation. There are two flavours of this class - one contains no prices, but keep access to the
 * {@link PriceListAndCurrencyPriceIndex price indexes} that allow lazy fetching of appropriate price for entity id.
 * Second contains direct array of prices and lacks access to the indexes - only prices in array are examined when
 * getting price for particular entity id.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface FilteredPriceRecords extends Serializable {
	/**
	 * Comparator that sorts {@link PriceRecord} in ascending order by entity id.
	 */
	Comparator<PriceRecordContract> ENTITY_PK_COMPARATOR = Comparator.comparingInt(PriceRecordContract::entityPrimaryKey);
	/**
	 * Empty instance with no price records at all.
	 */
	FilteredPriceRecords EMPTY = new ResolvedFilteredPriceRecords();

	/**
	 * Method collects all {@link FilteredPriceRecords} from all inner formulas of `parentFormula` tree that implement
	 * {@link FilteredPriceRecordAccessor} interface. All those objects are aggregated and reduced to single instance
	 * of {@link FilteredPriceRecords} that represents all of them.
	 */
	@Nonnull
	static FilteredPriceRecords createFromFormulas(@Nonnull Formula parentFormula, @Nullable Bitmap narrowToEntityIds) {
		// collect all FilteredPriceRecordAccessor that were involved in computing delegate result
		final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors = FormulaFinder.findAmongChildren(
			parentFormula, FilteredPriceRecordAccessor.class, LookUp.SHALLOW
		);

		final List<FilteredPriceRecords> filteredPriceRecords = filteredPriceRecordAccessors
			.stream()
			.map(FilteredPriceRecordAccessor::getFilteredPriceRecords)
			.map(it -> it instanceof NonResolvedFilteredPriceRecords ? ((NonResolvedFilteredPriceRecords) it).toResolvedFilteredPriceRecords() : it)
			.toList();

		final boolean allResolved = filteredPriceRecords.stream().allMatch(ResolvedFilteredPriceRecords.class::isInstance);
		final boolean allLazy = filteredPriceRecords.stream().allMatch(LazyEvaluatedEntityPriceRecords.class::isInstance);
		Assert.isPremiseValid(
			allResolved || allLazy,
			"FilteredPriceRecords that mix price records and lookups to price indexes cannot be combined! " +
				"This would lead to error states where invalid prices (with validity span) would get involved even if they should not!"
		);

		// there are no filtered price accessors or narrowed bitmap produces no output
		if (filteredPriceRecords.isEmpty() || (narrowToEntityIds != null && narrowToEntityIds.isEmpty())) {
			return new ResolvedFilteredPriceRecords();
			// exactly one accessor and no filtering is known (all contents should be returned)
		} else if (filteredPriceRecords.size() == 1 && narrowToEntityIds == null) {
			return filteredPriceRecords.get(0);
			// all price records are resolved
		} else if (allResolved) {
			if (narrowToEntityIds == null) {
				// and no filtering is known (all contents combined should be returned)
				return new ResolvedFilteredPriceRecords(
					ArrayUtils.mergeArrays(
						filteredPriceRecords.stream()
							.map(ResolvedFilteredPriceRecords.class::cast)
							.map(ResolvedFilteredPriceRecords::getPriceRecords)
							.toArray(PriceRecordContract[][]::new)
					),
					SortingForm.NOT_SORTED
				);
			} else {
				// limited entity ids are known - we need to include only the prices that link to those entities
				final BatchArrayIterator filteredPriceIdsIterator = new RoaringBitmapBatchArrayIterator(RoaringBitmapBackedBitmap.getRoaringBitmap(narrowToEntityIds).getBatchIterator());
				final PriceRecordLookup[] priceRecordIterators = filteredPriceRecords.stream().map(FilteredPriceRecords::getPriceRecordsLookup).toArray(PriceRecordLookup[]::new);
				final CompositeObjectArray<PriceRecordContract> narrowedPrices = new CompositeObjectArray<>(PriceRecordContract.class, false);
				while (filteredPriceIdsIterator.hasNext()) {
					final int[] batch = filteredPriceIdsIterator.nextBatch();
					final int lastExpectedEntity = filteredPriceIdsIterator.getPeek() > 0 ? batch[filteredPriceIdsIterator.getPeek() - 1] : -1;
					for (int i = 0; i < filteredPriceIdsIterator.getPeek(); i++) {
						int narrowedPriceId = batch[i];
						boolean anyPriceFound = false;
						for (PriceRecordLookup it : priceRecordIterators) {
							anyPriceFound = it.forEachPriceOfEntity(narrowedPriceId, lastExpectedEntity, narrowedPrices::add);
							if (anyPriceFound) {
								break;
							}
						}
						Assert.isPremiseValid(
							anyPriceFound,
							"Entity with id " + narrowedPriceId + " has no price associated!"
						);
					}
				}
				return new ResolvedFilteredPriceRecords(
					narrowedPrices.toArray(),
					SortingForm.ENTITY_PK
				);
			}
		} else {
			// prices are resolved lazily - just combine the indexes for lazy fetch
			return new LazyEvaluatedEntityPriceRecords(
				filteredPriceRecordAccessors.stream()
					.map(FilteredPriceRecordAccessor::getFilteredPriceRecords)
					.map(LazyEvaluatedEntityPriceRecords.class::cast)
					.flatMap(it -> Arrays.stream(it.getPriceIndexes()))
					.toArray(PriceListAndCurrencyPriceIndex[]::new)
			);
		}
	}

	/**
	 * Method collects all prices from list of passed `filteredPriceRecordAccessors`, reduces them to a subset which
	 * {@link PriceRecordContract#entityPrimaryKey()} matches the `filterTo` bitmap and returns thre result wrapped
	 * in {@link FilteredPriceRecordsLookupResult} divided into two parts:
	 *
	 * - array of prices that match passed entities
	 * - the rest of entity ids that were not matched to any price
	 */
	@Nonnull
	static FilteredPriceRecordsLookupResult collectFilteredPriceRecordsFromPriceRecordAccessors(
		@Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors,
		@Nonnull RoaringBitmap filterTo
	) {
		final CompositeObjectArray<PriceRecordContract> collectedPriceRecords = new CompositeObjectArray<>(PriceRecordContract.class, false);
		final List<PriceRecordLookup> priceRecordIterators = filteredPriceRecordAccessors
			.stream()
			.map(it -> it.getFilteredPriceRecords().getPriceRecordsLookup())
			.toList();

		// prepare writer for sorted output entity ids
		final BatchArrayIterator entityIdIterator = new RoaringBitmapBatchArrayIterator(filterTo.getBatchIterator());
		final CompositeIntArray notFound = new CompositeIntArray();

		// iterate through all entity ids
		while (entityIdIterator.hasNext()) {
			final int[] batch = entityIdIterator.nextBatch();
			final int lastExpectedEntity = entityIdIterator.getPeek() > 0 ? batch[entityIdIterator.getPeek() - 1] : -1;
			for (int i = 0; i < entityIdIterator.getPeek(); i++) {
				final int entityId = batch[i];

				boolean noPriceFoundAtAll = true;
				for (PriceRecordLookup priceRecordIt : priceRecordIterators) {
					final boolean anyPriceFound = priceRecordIt.forEachPriceOfEntity(
						entityId, lastExpectedEntity,
						collectedPriceRecords::add
					);
					if (anyPriceFound) {
						noPriceFoundAtAll = false;
						break;
					}
				}

				if (noPriceFoundAtAll) {
					notFound.add(entityId);
				}
			}
		}

		return notFound.isEmpty() ?
			new FilteredPriceRecordsLookupResult(collectedPriceRecords.toArray()) :
			new FilteredPriceRecordsLookupResult(collectedPriceRecords.toArray(), notFound.toArray());
	}

	/**
	 * Method returns an object that allows translating entity id to an appropriate price that represents it in current
	 * search. The price might be a lowest price of the entity or particular price enforced by the filtering constraints.
	 */
	@Nonnull
	PriceRecordLookup getPriceRecordsLookup();

	/**
	 * Method is called when this instance is about to get into the cache.
	 */
	default void prepareForFlattening() {}

	/**
	 * Enumeration that describes ordering of the internal price record array.
	 */
	enum SortingForm {
		/**
		 * No predictable order.
		 */
		NOT_SORTED,
		/**
		 * Sorted by {@link PriceRecord#entityPrimaryKey()} in ascending fashion.
		 */
		ENTITY_PK
	}


	/**
	 * Interface represents a lookup implementation that allows to iterate over all {@link PriceRecordContract} that
	 * are involved in price search.
	 */
	interface PriceRecordLookup {

		/**
		 * Method invokes `priceConsumer` for each price of the entity with `entityPK` primary key.
		 *
		 * @param entityPk           is the key we're looking for
		 * @param lastExpectedEntity is the key closing the currently read batch of entity primary keys, this represents
		 *                           a hint for the search algorithm that allows to narrow the scope that is being
		 *                           looked at
		 * @param priceConsumer      lambda that accepts the {@link PriceRecordContract} of the price that
		 *                           links to the `entityPk`
		 */
		boolean forEachPriceOfEntity(int entityPk, int lastExpectedEntity, @Nonnull Consumer<PriceRecordContract> priceConsumer);

	}

}
