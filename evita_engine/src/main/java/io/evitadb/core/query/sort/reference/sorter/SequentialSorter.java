/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.core.query.sort.reference.sorter;


import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Sequential sorter will apply {@link #embeddedSorters} one by one on the {@link #atomicBlocks}. The sorter is meant
 * to be used when traversal sorting is required (i.e. {@link MergeMode#APPEND_ALL}.
 *
 * A block holds the reduced indexes of one referenced entity - all its representative keys and both scopes - and the
 * sorting context of the block names that referenced entity only (see {@link #blockKeys}); which row of an owner
 * represents it inside the block is up to the embedded sorters.
 *
 * The embedded sorters see only the records of a block that no earlier block claimed. A block none of those records
 * belongs to is skipped without invoking any embedded sorter, and the walk stops once every record is claimed - an
 * embedded sorter is never handed an empty input.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class SequentialSorter implements Sorter {
	/**
	 * Array of {@link ReducedEntityIndex} that are used to filter the input records. Each block is sorted
	 * separately by {@link #embeddedSorters} and the order of the block matters (pk sorted earlier are not sorted
	 * in later blocks even if they're present there as well - 1:N references).
	 */
	private final ReducedEntityIndex[][] atomicBlocks;
	/**
	 * The key of each of {@link #atomicBlocks}, at the same position: the referenced entity of the block without any
	 * representative attribute values. A block is the whole referenced entity - the indexes of all its representative
	 * keys and of both scopes - so the key names the target only, and the embedded sorters look up everything they
	 * need for the block by that target.
	 */
	private final RepresentativeReferenceKey[] blockKeys;
	/**
	 * Sorters that are applied on the {@link #atomicBlocks} one by one. The order of the sorters matters.
	 */
	private final List<Sorter> embeddedSorters;

	public SequentialSorter(
		@Nonnull ReducedEntityIndex[][] atomicBlocks,
		@Nonnull List<Sorter> embeddedSorters
	) {
		this.atomicBlocks = atomicBlocks;
		this.blockKeys = new RepresentativeReferenceKey[atomicBlocks.length];
		for (int i = 0; i < atomicBlocks.length; i++) {
			this.blockKeys[i] = new RepresentativeReferenceKey(atomicBlocks[i][0].getReferenceKey());
		}
		this.embeddedSorters = embeddedSorters;
		// ensure NoSorter is not present in the list of embedded sorters
		// we need to avoid sorting records which may not have related sort information (such as attribute by which we sort)
		int index = -1;
		for (int i = 0; i < this.embeddedSorters.size(); i++) {
			if (this.embeddedSorters.get(i) instanceof NoSorter) {
				index = i;
			}
		}
		if (index != -1) {
			this.embeddedSorters.remove(index);
		}
	}

	/**
	 * Sorts the records block by block: each block's embedded sorters receive the not yet claimed records of the
	 * block, blocks without such records are skipped, and what no block claims is handed to the next sorter.
	 *
	 * @param sortingContext         the records to sort and the requested slice
	 * @param result                 the array the sorted records are written to
	 * @param skippedRecordsConsumer consumer of the records skipped before the requested slice, or `null`
	 * @return the context carrying the records no block claimed
	 */
	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final PersistentRoaringBitmap recordsToSort = RoaringBitmapBackedBitmap.getRoaringBitmapClone(sortingContext.nonSortedKeys());

		SortingContext nextSortingContext = sortingContext;
		top:
		for (int blockIndex = 0; blockIndex < this.atomicBlocks.length; blockIndex++) {
			final ReducedEntityIndex[] atomicBlock = this.atomicBlocks[blockIndex];
			if (recordsToSort.isEmpty()) {
				// every record found its block - no later block can claim anything
				break;
			}
			final Bitmap limitedInput = FormulaFactory.or(
				Arrays.stream(atomicBlock)
					.map(
						it -> FormulaFactory.and(
							new ConstantFormula(new BaseBitmap(recordsToSort)),
							it.getAllPrimaryKeysFormula()
						)
					)
					.toArray(Formula[]::new)
			).compute();

			if (limitedInput.isEmpty()) {
				// none of the remaining records belongs to this block - the embedded sorters have nothing to do here,
				// and an empty input must never reach them (a merge walk over an empty selection has nothing to
				// match, so running it could only report what is not there)
				continue;
			}

			/*
			  We can't optimize here to skip entire block if the nextSortingContext.startIndex() is greater than the
			  limitedInput.size() because part of the records may have the sorting information (e.g. attribute) and
			  some not and this changes the behavior of the items that are propagated to the next round.
			 */

			// execute sorting
			nextSortingContext = new SortingContext(
				nextSortingContext.queryContext(),
				new BaseBitmap(limitedInput),
				nextSortingContext.startIndex(),
				nextSortingContext.endIndex(),
				nextSortingContext.peak(),
				nextSortingContext.skipped(),
				this.blockKeys[blockIndex]
			);

			final int previousPeak = nextSortingContext.peak();
			for (Sorter embeddedSorter : this.embeddedSorters) {
				nextSortingContext = embeddedSorter.sortAndSlice(
					nextSortingContext,
					result,
					skippedRecordsConsumer == null ?
						recordsToSort::remove :
						pk -> {
							recordsToSort.remove(pk);
							skippedRecordsConsumer.accept(pk);
						}
				);

				for (int i = previousPeak; i < nextSortingContext.peak(); i++) {
					recordsToSort.remove(result[i]);
				}

				if (nextSortingContext.peak() == result.length) {
					// we don't need to continue, we've reached the requested number of records
					break top;
				} else if (nextSortingContext.nonSortedKeys().isEmpty()) {
					// there is nothing else to be sorted
					break;
				}
			}
		}
		return sortingContext.createResultContext(
			new BaseBitmap(recordsToSort),
			nextSortingContext.peak() - sortingContext.peak(),
			nextSortingContext.skipped() - sortingContext.skipped()
		);
	}
}
