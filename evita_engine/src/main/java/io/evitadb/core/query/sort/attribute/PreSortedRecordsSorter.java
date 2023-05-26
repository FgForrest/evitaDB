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

package io.evitadb.core.query.sort.attribute;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.BatchIterator;
import org.roaringbitmap.RoaringBatchIterator;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * This sorter sorts {@link AbstractFormula#compute()} result according to {@link SortedRecordsProvider} that contains information
 * about record ids in already sorted fashion. This sorter executes following operation:
 *
 * - creates mask of positions in presorted array that refer to the {@link AbstractFormula#compute()} result
 * - copies slice of record ids in the presorted array that conform to the mask (in the order of presorted array)
 * the slice respects requested start and end index
 * - if the result is complete it is returned, if there is space left and there were record ids that were not found
 * in presorted array append these to the tail of the result until end index is reached or the set is exhausted
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class PreSortedRecordsSorter extends AbstractRecordsSorter {
	private static final int[] EMPTY_RESULT = new int[0];
	/**
	 * This instance will be used by this sorter to access pre sorted arrays of entities.
	 */
	private final Supplier<SortedRecordsProvider> sortedRecordsSupplier;
	/**
	 * This instance will be used by this sorter in case the {@link QueryContext} contains list of prefetched entities.
	 * The {@link EntityComparator} will take precedence over {@link #sortedRecordsSupplier}.
	 */
	private final EntityComparator entityComparator;
	/**
	 * This sorter instance will be used for sorting entities, that cannot be sorted by this sorter.
	 */
	private final Sorter unknownRecordIdsSorter;

	public PreSortedRecordsSorter(@Nonnull Supplier<SortedRecordsProvider> sortedRecordsSupplier, @Nonnull EntityComparator comparator) {
		this.sortedRecordsSupplier = sortedRecordsSupplier;
		this.entityComparator = comparator;
		this.unknownRecordIdsSorter = null;
	}

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		return new PreSortedRecordsSorter(
			sortedRecordsSupplier,
			entityComparator,
			sorterForUnknownRecords
		);
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return unknownRecordIdsSorter;
	}

	@Nonnull
	@Override
	public int[] sortAndSlice(@Nonnull QueryContext queryContext, @Nonnull Formula input, int startIndex, int endIndex) {
		final Bitmap selectedRecordIds = input.compute();
		if (selectedRecordIds.isEmpty()) {
			return EMPTY_RESULT;
		} else {
			if (queryContext.getPrefetchedEntities() == null) {
				final SortedRecordsProvider sortedRecordsProvider = sortedRecordsSupplier.get();
				final MaskResult positions = getMask(sortedRecordsProvider, selectedRecordIds);
				final SortResult sortPartialResult = fetchSlice(
					sortedRecordsProvider, selectedRecordIds.size(), positions.mask(), startIndex, endIndex
				);
				return returnResultAppendingUnknown(
					queryContext, sortPartialResult,
					positions.notFoundRecords(), unknownRecordIdsSorter,
					startIndex, endIndex
				);
			} else {
				final OfInt it = selectedRecordIds.iterator();
				final List<EntityContract> entities = new ArrayList<>(selectedRecordIds.size());
				while (it.hasNext()) {
					int id = it.next();
					entities.add(queryContext.translateToEntity(id));
				}

				entities.sort(entityComparator);

				int notFoundRecordsCnt = 0;
				final RoaringBitmap notFoundRecords = new RoaringBitmap();
				for (EntityContract entityContract : entityComparator.getNonSortedEntities()) {
					if (notFoundRecords.checkedAdd(queryContext.translateEntity(entityContract))) {
						notFoundRecordsCnt++;
					}
				}

				final AtomicInteger index = new AtomicInteger();
				final int[] result = new int[selectedRecordIds.size()];
				entities.subList(0, selectedRecordIds.size() - notFoundRecordsCnt)
					.stream()
					.skip(startIndex)
					.limit((long) endIndex - startIndex)
					.mapToInt(queryContext::translateEntity)
					.forEach(pk -> result[index.getAndIncrement()] = pk);

				return returnResultAppendingUnknown(
					queryContext,
					new SortResult(result, index.get()),
					notFoundRecords, unknownRecordIdsSorter,
					startIndex, endIndex
				);
			}
		}
	}

	/**
	 * Maps positions to the record ids in presorted set respecting start and end index.
	 */
	@Nonnull
	private static SortResult fetchSlice(@Nonnull SortedRecordsProvider sortedRecordsProvider, int recordsFound, @Nonnull RoaringBitmap positions, int startIndex, int endIndex) {
		final int[] buffer = new int[512];

		final RoaringBatchIterator batchIterator = positions.getBatchIterator();
		final int[] preSortedRecordIds = sortedRecordsProvider.getSortedRecordIds();
		final int length = Math.min(endIndex - startIndex, recordsFound);
		if (length < 0) {
			throw new IndexOutOfBoundsException("Index: " + startIndex + ", Size: " + sortedRecordsProvider.getRecordCount());
		}
		final int[] sortedResult = new int[length];
		int inputPeak = 0;
		int previousInputPeak;
		int outputPeak = 0;
		while (batchIterator.hasNext()) {
			final int read = batchIterator.nextBatch(buffer);
			if (read == 0) {
				// no more results break early
				break;
			}
			previousInputPeak = inputPeak;
			inputPeak += read;
			// skip previous pages quickly
			if (inputPeak >= startIndex) {
				// copy records for page
				for (int i = startIndex - previousInputPeak; i < read && i < (endIndex - previousInputPeak); i++) {
					sortedResult[outputPeak++] = preSortedRecordIds[buffer[i]];
				}
				startIndex = inputPeak;
			}
			// finish - page was read
			if (inputPeak >= endIndex) {
				break;
			}
		}
		return new SortResult(sortedResult, outputPeak);
	}

	/**
	 * Returns mask of the positions in the presorted array that matched the computational result
	 * Mask also contains record ids not found in presorted record index.
	 */
	private MaskResult getMask(@Nonnull SortedRecordsProvider sortedRecordsProvider, @Nonnull Bitmap selectedRecordIds) {
		final int[] bufferA = new int[512];
		final int[] bufferB = new int[512];

		final int selectedRecordCount = selectedRecordIds.size();
		final Bitmap unsortedRecordIds = sortedRecordsProvider.getAllRecords();
		final int[] recordPositions = sortedRecordsProvider.getRecordPositions();

		final RoaringBitmapWriter<RoaringBitmap> mask = RoaringBitmapBackedBitmap.buildWriter();
		final RoaringBitmapWriter<RoaringBitmap> notFound = RoaringBitmapBackedBitmap.buildWriter();

		final BatchIterator unsortedRecordIdsIt = RoaringBitmapBackedBitmap.getRoaringBitmap(unsortedRecordIds).getBatchIterator();
		final BatchIterator selectedRecordIdsIt = RoaringBitmapBackedBitmap.getRoaringBitmap(selectedRecordIds).getBatchIterator();

		int matchesFound = 0;
		int peakA = -1;
		int limitA = -1;
		int peakB = -1;
		int limitB = -1;
		int accA = 1;
		do {
			if (peakA == limitA && limitA != 0) {
				accA += limitA;
				limitA = unsortedRecordIdsIt.nextBatch(bufferA);
				peakA = 0;
			}
			if (peakB == limitB) {
				limitB = selectedRecordIdsIt.nextBatch(bufferB);
				peakB = 0;
			}
			if (peakA < limitA && bufferA[peakA] == bufferB[peakB]) {
				mask.add(recordPositions[accA + peakA]);
				matchesFound++;
				peakB++;
				peakA++;
			} else if (peakB < limitB && (peakA >= limitA || bufferA[peakA] > bufferB[peakB])) {
				notFound.add(bufferB[peakB]);
				peakB++;
			} else {
				peakA++;
			}
		} while (matchesFound < selectedRecordCount && limitB > 0);

		return new MaskResult(mask.get(), notFound.get());
	}

	/**
	 * @param mask            IntegerBitmap of positions of record ids in presorted set.
	 * @param notFoundRecords IntegerBitmap of record ids not found in presorted set at all.
	 */
	private record MaskResult(@Nonnull RoaringBitmap mask, @Nonnull RoaringBitmap notFoundRecords) {
	}

}
