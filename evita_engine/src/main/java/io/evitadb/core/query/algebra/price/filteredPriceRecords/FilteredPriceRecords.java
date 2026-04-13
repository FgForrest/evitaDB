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

package io.evitadb.core.query.algebra.price.filteredPriceRecords;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.SharedBufferPool;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordsLookupResult;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.dataType.iterator.BatchArrayIterator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.iterator.RoaringBitmapBatchArrayIterator;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static java.util.Optional.empty;
import static java.util.Optional.of;

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
	 * Collects all {@link FilteredPriceRecords} from {@link FilteredPriceRecordAccessor} nodes
	 * found in the `parentFormula` tree and reduces them into a single {@link FilteredPriceRecords}
	 * instance. When `narrowToEntityIds` is provided, only prices linked to those entities are
	 * retained. The result may be a {@link ResolvedFilteredPriceRecords},
	 * {@link LazyEvaluatedEntityPriceRecords}, or a {@link CombinedPriceRecords} depending on
	 * the mix of accessor types found.
	 *
	 * @param parentFormula     root of the formula tree to search for price record accessors
	 * @param narrowToEntityIds optional bitmap to restrict results to specific entity primary keys
	 * @param context           current query execution context
	 * @return aggregated price records covering all accessors in the formula tree
	 */
	@Nonnull
	static FilteredPriceRecords createFromFormulas(
		@Nonnull Formula parentFormula,
		@Nullable Bitmap narrowToEntityIds,
		@Nonnull QueryExecutionContext context
	) {
		// collect all FilteredPriceRecordAccessor that were involved in computing delegate result
		final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors = FormulaFinder.findAmongChildren(
			parentFormula, FilteredPriceRecordAccessor.class, LookUp.SHALLOW
		);

		final List<FilteredPriceRecords> filteredPriceRecords;
		{
			final FilteredPriceRecords[] arr = new FilteredPriceRecords[filteredPriceRecordAccessors.size()];
			int idx = 0;
			for (FilteredPriceRecordAccessor accessor : filteredPriceRecordAccessors) {
				FilteredPriceRecords records = accessor.getFilteredPriceRecords(context);
				if (records instanceof NonResolvedFilteredPriceRecords) {
					records = ((NonResolvedFilteredPriceRecords) records).toResolvedFilteredPriceRecords();
				}
				arr[idx++] = records;
			}
			filteredPriceRecords = List.of(arr);
		}

		// there are no filtered price accessors or narrowed bitmap produces no output
		if (filteredPriceRecords.isEmpty() || (narrowToEntityIds != null && narrowToEntityIds.isEmpty())) {
			return new ResolvedFilteredPriceRecords();
			// exactly one accessor and no filtering is known (all contents should be returned)
		} else if (filteredPriceRecords.size() == 1 && narrowToEntityIds == null) {
			return filteredPriceRecords.get(0);
			// all price records are resolved
		} else {
			final Optional<LazyEvaluatedEntityPriceRecords> lazyEvaluatedEntityPriceRecords = getLazyEvaluatedEntityPriceRecords(filteredPriceRecordAccessors, context);
			final Optional<ResolvedFilteredPriceRecords> resolvedFilteredPriceRecords;
			if (narrowToEntityIds == null) {
				// and no filtering is known (all contents combined should be returned)
				resolvedFilteredPriceRecords = getResolvedFilteredPriceRecords(filteredPriceRecords);
			} else {
				// limited entity ids are known - we need to include only the prices that link to those entities
				resolvedFilteredPriceRecords = getNarrowedResolvedFilteredPriceRecords(
					narrowToEntityIds, filteredPriceRecords, lazyEvaluatedEntityPriceRecords.isEmpty()
				);
			}

			if (resolvedFilteredPriceRecords.isPresent() && lazyEvaluatedEntityPriceRecords.isEmpty()) {
				return resolvedFilteredPriceRecords.get();
			} else if (resolvedFilteredPriceRecords.isEmpty() && lazyEvaluatedEntityPriceRecords.isPresent()) {
				return lazyEvaluatedEntityPriceRecords.get();
			} else if (resolvedFilteredPriceRecords.isPresent()) {
				return new CombinedPriceRecords(
					resolvedFilteredPriceRecords.get(),
					lazyEvaluatedEntityPriceRecords.get()
				);
			} else {
				throw new GenericEvitaInternalError("Both resolved and lazy price records are present!");
			}
		}
	}

	/**
	 * Extracts all {@link PriceListAndCurrencyPriceIndex} references from accessors whose
	 * {@link FilteredPriceRecords} are {@link LazyEvaluatedEntityPriceRecords} and merges them
	 * into a single {@link LazyEvaluatedEntityPriceRecords} instance. Returns empty if none
	 * of the accessors use lazy evaluation.
	 *
	 * @param filteredPriceRecordAccessors accessors to inspect for lazy price records
	 * @param context                     current query execution context
	 * @return combined lazy price records, or empty if no lazy records found
	 */
	@Nonnull
	private static Optional<LazyEvaluatedEntityPriceRecords> getLazyEvaluatedEntityPriceRecords(
		@Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors,
		@Nonnull QueryExecutionContext context
	) {
		// cache results to avoid calling getFilteredPriceRecords twice per accessor
		final FilteredPriceRecords[] cachedRecords = new FilteredPriceRecords[filteredPriceRecordAccessors.size()];
		int totalIndexCount = 0;
		int idx = 0;
		for (FilteredPriceRecordAccessor accessor : filteredPriceRecordAccessors) {
			final FilteredPriceRecords records = accessor.getFilteredPriceRecords(context);
			cachedRecords[idx++] = records;
			if (records instanceof LazyEvaluatedEntityPriceRecords lazy) {
				totalIndexCount += lazy.getPriceIndexes().length;
			}
		}
		final PriceListAndCurrencyPriceIndex<?, ?>[] priceIndexes = new PriceListAndCurrencyPriceIndex[totalIndexCount];
		int offset = 0;
		for (final FilteredPriceRecords cachedRecord : cachedRecords) {
			if (cachedRecord instanceof LazyEvaluatedEntityPriceRecords lazy) {
				final PriceListAndCurrencyPriceIndex<?, ?>[] indexes = lazy.getPriceIndexes();
				System.arraycopy(indexes, 0, priceIndexes, offset, indexes.length);
				offset += indexes.length;
			}
		}
		return ArrayUtils.isEmpty(priceIndexes) ?
			empty() :
			of(
				new LazyEvaluatedEntityPriceRecords(
					priceIndexes
				)
			);
	}

	/**
	 * Filters resolved price records to only include prices whose entity primary key is present
	 * in `narrowToEntityIds`. Iterates over the narrowing bitmap in batches via
	 * {@link SharedBufferPool} and looks up matching prices through {@link PriceRecordLookup}.
	 *
	 * @param narrowToEntityIds    bitmap of entity primary keys to retain
	 * @param filteredPriceRecords all collected price records (only resolved ones are examined)
	 * @param requirePriceFound    when true, asserts that every entity id maps to at least one price
	 * @return resolved price records narrowed to matching entities, or empty if no resolved records exist
	 */
	@Nonnull
	private static Optional<ResolvedFilteredPriceRecords> getNarrowedResolvedFilteredPriceRecords(
		@Nonnull Bitmap narrowToEntityIds,
		@Nonnull List<FilteredPriceRecords> filteredPriceRecords,
		boolean requirePriceFound
	) {
		final int[] buffer = SharedBufferPool.INSTANCE.obtain();
		try {
			final Optional<ResolvedFilteredPriceRecords> resolvedFilteredPriceRecords;
			final BatchArrayIterator filteredPriceIdsIterator = new RoaringBitmapBatchArrayIterator(
				RoaringBitmapBackedBitmap.getRoaringBitmap(narrowToEntityIds).getBatchIterator(),
				buffer
			);
			int lookupCount = 0;
			for (FilteredPriceRecords priceRecord : filteredPriceRecords) {
				if (priceRecord instanceof ResolvedFilteredPriceRecords) {
					lookupCount++;
				}
			}
			final PriceRecordLookup[] priceRecordIterators = new PriceRecordLookup[lookupCount];
			int lookupIdx = 0;
			for (FilteredPriceRecords filteredPriceRecord : filteredPriceRecords) {
				if (filteredPriceRecord instanceof ResolvedFilteredPriceRecords) {
					priceRecordIterators[lookupIdx++] = filteredPriceRecord.getPriceRecordsLookup();
				}
			}
			if (ArrayUtils.isEmpty(priceRecordIterators)) {
				resolvedFilteredPriceRecords = empty();
			} else {
				final CompositeObjectArray<PriceRecordContract> narrowedPrices =
					new CompositeObjectArray<>(PriceRecordContract.class, false);
				while (filteredPriceIdsIterator.hasNext()) {
					final int[] batch = filteredPriceIdsIterator.nextBatch();
					final int lastExpectedEntity = filteredPriceIdsIterator.getPeek() > 0
						? batch[filteredPriceIdsIterator.getPeek() - 1]
						: -1;
					for (int i = 0; i < filteredPriceIdsIterator.getPeek(); i++) {
						int narrowedPriceId = batch[i];
						boolean anyPriceFound = false;
						for (PriceRecordLookup it : priceRecordIterators) {
							anyPriceFound = it.forEachPriceOfEntity(
								narrowedPriceId, lastExpectedEntity, narrowedPrices::add
							);
							if (anyPriceFound) {
								break;
							}
						}
						Assert.isPremiseValid(
							!requirePriceFound || anyPriceFound,
							"Entity with id " + narrowedPriceId + " has no price associated!"
						);
					}
				}
				resolvedFilteredPriceRecords = of(
					new ResolvedFilteredPriceRecords(
						narrowedPrices.toArray(),
						SortingForm.ENTITY_PK
					)
				);
			}
			return resolvedFilteredPriceRecords;
		} finally {
			SharedBufferPool.INSTANCE.free(buffer);
		}
	}

	/**
	 * Merges all {@link ResolvedFilteredPriceRecords} from the given list into a single instance
	 * by concatenating their price record arrays. Non-resolved records are skipped.
	 *
	 * @param filteredPriceRecords all collected price records to merge
	 * @return merged resolved price records, or empty if none of the records are resolved
	 */
	@Nonnull
	private static Optional<ResolvedFilteredPriceRecords> getResolvedFilteredPriceRecords(
		@Nonnull List<FilteredPriceRecords> filteredPriceRecords
	) {
		int resolvedCount = 0;
		for (FilteredPriceRecords priceRecord : filteredPriceRecords) {
			if (priceRecord instanceof ResolvedFilteredPriceRecords) {
				resolvedCount++;
			}
		}
		final PriceRecordContract[][] arrays = new PriceRecordContract[resolvedCount][];
		int arrIdx = 0;
		for (FilteredPriceRecords filteredPriceRecord : filteredPriceRecords) {
			if (filteredPriceRecord instanceof ResolvedFilteredPriceRecords resolved) {
				arrays[arrIdx++] = resolved.getPriceRecords();
			}
		}
		final PriceRecordContract[] priceRecords = ArrayUtils.mergeArrays(arrays);
		return ArrayUtils.isEmpty(priceRecords) ?
			empty() :
			of(
				new ResolvedFilteredPriceRecords(
					priceRecords,
					SortingForm.NOT_SORTED
				)
			);
	}

	/**
	 * Collects prices from `filteredPriceRecordAccessors` and retains only those whose
	 * {@link PriceRecordContract#entityPrimaryKey()} is present in `filterTo`. The result
	 * is split into two parts: an array of matched price records and a bitmap of entity ids
	 * that had no associated price.
	 *
	 * @param filteredPriceRecordAccessors accessors providing price records to filter
	 * @param filterTo                    bitmap of entity primary keys to match against
	 * @param context                     current query execution context
	 * @return lookup result containing matched prices and unmatched entity ids
	 */
	@Nonnull
	static FilteredPriceRecordsLookupResult collectFilteredPriceRecordsFromPriceRecordAccessors(
		@Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors,
		@Nonnull RoaringBitmap filterTo,
		@Nonnull QueryExecutionContext context
	) {
		final CompositeObjectArray<PriceRecordContract> collectedPriceRecords = new CompositeObjectArray<>(PriceRecordContract.class, false);
		final PriceRecordLookup[] priceRecordIterators = new PriceRecordLookup[filteredPriceRecordAccessors.size()];
		int prIdx = 0;
		for (FilteredPriceRecordAccessor accessor : filteredPriceRecordAccessors) {
			priceRecordIterators[prIdx++] = accessor.getFilteredPriceRecords(context).getPriceRecordsLookup();
		}

		final int[] buffer = SharedBufferPool.INSTANCE.obtain();
		try {
			// prepare writer for sorted output entity ids
			final BatchArrayIterator entityIdIterator = new RoaringBitmapBatchArrayIterator(filterTo.getBatchIterator(), buffer);
			final RoaringBitmapWriter<RoaringBitmap> notFoundWriter = RoaringBitmapBackedBitmap.buildWriter();

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
						notFoundWriter.add(entityId);
					}
				}
			}

			final RoaringBitmap notFound = notFoundWriter.get();
			return notFound.isEmpty() ?
				new FilteredPriceRecordsLookupResult(collectedPriceRecords.toArray()) :
				new FilteredPriceRecordsLookupResult(collectedPriceRecords.toArray(), new BaseBitmap(notFound));
		} finally {
			SharedBufferPool.INSTANCE.free(buffer);
		}
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
		boolean forEachPriceOfEntity(
			int entityPk,
			int lastExpectedEntity,
			@Nonnull Consumer<PriceRecordContract> priceConsumer
		);

	}

}
