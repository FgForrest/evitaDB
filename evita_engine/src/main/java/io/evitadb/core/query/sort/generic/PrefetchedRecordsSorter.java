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

package io.evitadb.core.query.sort.generic;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.EntityReferenceSensitiveComparator;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.IntConsumer;

/**
 * This sorter requires list of pre-fetched entities in the {@link QueryPlanningContext}. If none is present the sorter is
 * skipped entirely. If pre-fetched entities are present they are sorted by a {@link #entityComparator} that uses
 * their data. This sorter avoids using pre-sorted indexes, because we speculate that the cardinality of the pre-fetched
 * entities is low and the sorting will be faster than using the index.
 *
 * The comparator is driven through one lifecycle per {@link #sortAndSlice(SortingContext, int[], IntConsumer)} call:
 *
 * 1. **reset** - {@link EntityComparator#prepareFor(int)} drops whatever the previous call left behind,
 * 2. **prepare the selection** - {@link EntityComparator#prepareForSelection(Bitmap)} learns the whole selection,
 * 3. **bind the block key** - an {@link EntityReferenceSensitiveComparator} is bound to the key of the sorted block of
 *    a traversal ordering, when the sorting context carries one,
 * 4. **classify** - {@link EntityComparator#canSort(EntityContract)} decides for every selected entity, exactly once,
 *    whether it carries the data to be sorted by; the entities that do not are passed on to the next sorter,
 * 5. **sort** - only the entities classified as sortable are sorted,
 * 6. **unbind** - the block key is released, whatever the outcome.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class PrefetchedRecordsSorter implements Sorter {
	/**
	 * This instance will be used by this sorter in case the {@link QueryPlanningContext} contains list of prefetched entities.
	 */
	private final EntityComparator entityComparator;

	public PrefetchedRecordsSorter(@Nonnull EntityComparator entityComparator) {
		this.entityComparator = entityComparator;
	}

	/**
	 * Sorts the candidate record set using the pre-fetched entity bodies and slices the result to the requested page.
	 *
	 * **Early-exit:** when {@link QueryExecutionContext#getPrefetchedEntities()} returns `null`, the sorter skips
	 * itself entirely and returns the incoming `sortingContext` unchanged so the next sorter in the chain can handle
	 * the full candidate set.
	 *
	 * **When prefetched entities are present:**
	 *
	 * 1. Resets the comparator and hands it the selection (`prepareFor`, `prepareForSelection`).
	 * 2. If the comparator implements {@link EntityReferenceSensitiveComparator} and a `referenceKey` is set on the
	 *    context, the steps 3 and 4 run while the comparator is bound to that reference key.
	 * 3. Translates each candidate record ID from `sortingContext.nonSortedKeys()` to its `EntityContract` via
	 *    `QueryExecutionContext.translateToEntity` and classifies it once with
	 *    {@link EntityComparator#canSort(EntityContract)}: sortable entities are collected for sorting, the others go
	 *    straight to the `notFoundRecords` bitmap - they are never compared, whatever the size of the selection.
	 * 4. Sorts the sortable entities with `entityComparator`.
	 * 5. Entities the comparator reported as unsortable while comparing (`getNonSortedEntities()` - the channel of
	 *    comparators that do not classify) are added to `notFoundRecords` and excluded from the sortable slice
	 *    `[0, entitiesCount)`.
	 * 6. The sortable slice is paged and written into `result`; skipped records are reported to
	 *    `skippedRecordsConsumer` if provided.
	 * 7. Returns a new `SortingContext` carrying the unsortable records so the next sorter can place them.
	 *
	 * **Partition invariant:** every primary key reported by `entityComparator.getNonSortedEntities()` for a sorted
	 * entity MUST fall outside the sortable slice. By cardinality the two slices are fixed-size partitions, so
	 * verifying the trailing slice `[entitiesCount, sortedEntities.size())` consists exclusively of PKs from
	 * `notFoundRecords` is sufficient - and cheap, costing O(reported) instead of O(entitiesCount). If the comparator
	 * places a non-sortable entity inside the sortable slice, downstream sorters would emit duplicate primary keys;
	 * {@link Assert#isPremiseValid} throws immediately in that case to surface the violation early.
	 *
	 * @param sortingContext      the current sorting state including candidate keys and pagination window
	 * @param result              output array that receives sorted primary keys for the requested page slice
	 * @param skippedRecordsConsumer optional consumer called for each primary key skipped before the page window
	 * @return updated `SortingContext` with unsortable records passed to the next sorter in the chain
	 */
	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final QueryExecutionContext queryContext = sortingContext.queryContext();
		if (queryContext.getPrefetchedEntities() == null) {
			return sortingContext;
		} else {
			final Bitmap selectedRecordIds = sortingContext.nonSortedKeys();
			final int recomputedStartIndex = sortingContext.recomputedStartIndex();
			final int recomputedEndIndex = sortingContext.recomputedEndIndex();
			final int peak = sortingContext.peak();

			// reset the comparator and let it learn the selection
			this.entityComparator.prepareFor(recomputedEndIndex - recomputedStartIndex);
			this.entityComparator.prepareForSelection(selectedRecordIds);

			// classify every entity once and sort only the sortable ones - within the block key when there is one
			final List<EntityContract> sortedEntities = new ArrayList<>(selectedRecordIds.size());
			final PersistentRoaringBitmap notFoundRecords = new PersistentRoaringBitmap();
			final RepresentativeReferenceKey blockKey = sortingContext.referenceKey();
			if (this.entityComparator instanceof EntityReferenceSensitiveComparator ersc && blockKey != null) {
				ersc.withReferencedEntityId(
					blockKey,
					() -> classifyAndSort(queryContext, selectedRecordIds, sortedEntities, notFoundRecords)
				);
			} else {
				classifyAndSort(queryContext, selectedRecordIds, sortedEntities, notFoundRecords);
			}

			// entities a comparator without classification reported while comparing sit at the end of the sorted list
			int reportedRecordsCnt = 0;
			for (EntityContract entityContract : this.entityComparator.getNonSortedEntities()) {
				if (notFoundRecords.checkedAdd(queryContext.translateEntity(entityContract))) {
					reportedRecordsCnt++;
				}
			}

			final int sortedEntitiesSize = sortedEntities.size();
			final int entitiesCount = sortedEntitiesSize - reportedRecordsCnt;
			Assert.isPremiseValid(
				entitiesCount >= 0,
				() -> "Entity comparator " + this.entityComparator.getClass().getName() +
					" reported more non-sortable entities than it was given to sort."
			);
			// invariant: the trailing slice [entitiesCount, sortedEntitiesSize) must contain exactly
			// the non-sortable PKs reported by the comparator. By cardinality the two slices are
			// fixed-size partitions, so verifying the trailing slice is sufficient and costs
			// O(reportedRecordsCnt) — preferred over scanning the full sortable slice on this hot path.
			for (int i = entitiesCount; i < sortedEntitiesSize; i++) {
				final int pk = queryContext.translateEntity(sortedEntities.get(i));
				Assert.isPremiseValid(
					notFoundRecords.contains(pk),
					() -> "Entity comparator " + this.entityComparator.getClass().getName() +
						" kept sortable entity #" + pk + " inside the trailing non-sortable slice" +
						" — sort/non-sort partitions disagree; downstream sorters would emit duplicates."
				);
			}

			int appended = 0;
			final int skippedItems = Math.min(recomputedStartIndex, entitiesCount);
			final int appendedItems = Math.min(Math.min(recomputedEndIndex, entitiesCount), skippedItems + result.length - peak);
			if (skippedRecordsConsumer != null) {
				for (int i = 0; i < skippedItems; i++) {
					skippedRecordsConsumer.accept(queryContext.translateEntity(sortedEntities.get(i)));
				}
			}
			for (int i = skippedItems; i < appendedItems; i++) {
				result[peak + appended++] = queryContext.translateEntity(sortedEntities.get(i));
			}

			return sortingContext.createResultContext(
				notFoundRecords.isEmpty() ?
					EmptyBitmap.INSTANCE : new BaseBitmap(notFoundRecords),
				appended,
				skippedItems
			);
		}
	}

	/**
	 * Classifies every selected entity once with {@link EntityComparator#canSort(EntityContract)} and sorts the
	 * sortable ones. An entity classified as not sortable is recorded in `notFoundRecords` and never reaches the
	 * comparator's {@link EntityComparator#compare(Object, Object)} - so it is passed on to the next sorter even when
	 * it is the only entity of the selection, which a detection inside the comparison alone would miss.
	 *
	 * The loop itself runs over primitive record ids and allocates nothing per entity apart from the growth of the two
	 * collections it fills; what {@link EntityComparator#canSort(EntityContract)} allocates is up to the comparator.
	 *
	 * @param queryContext      the query context translating record ids to prefetched entities
	 * @param selectedRecordIds the record ids to classify and sort
	 * @param sortedEntities    receives the sortable entities, sorted by the comparator on return
	 * @param notFoundRecords   receives the record ids of the entities classified as not sortable
	 */
	private void classifyAndSort(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Bitmap selectedRecordIds,
		@Nonnull List<EntityContract> sortedEntities,
		@Nonnull PersistentRoaringBitmap notFoundRecords
	) {
		final OfInt it = selectedRecordIds.iterator();
		while (it.hasNext()) {
			final int recordId = it.next();
			final EntityContract entity = queryContext.translateToEntity(recordId);
			if (this.entityComparator.canSort(entity)) {
				sortedEntities.add(entity);
			} else {
				notFoundRecords.add(recordId);
			}
		}
		sortedEntities.sort(this.entityComparator);
	}
}
