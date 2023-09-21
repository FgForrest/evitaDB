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

import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.ConditionalSorter;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import lombok.Getter;
import org.roaringbitmap.BatchIterator;
import org.roaringbitmap.RoaringBatchIterator;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Implementation of the {@link SortedRecordsProvider} that merges multiple instances into a one discarding
 * the duplicate record primary keys. This operation is quite costly for large data sets and should be cached.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class MergedSortedRecordsSupplier extends AbstractRecordsSorter implements ConditionalSorter, Serializable {
	@Serial private static final long serialVersionUID = 6709519064291586499L;
	/**
	 * Contains the {@link SortedRecordsProvider} implementation with merged pre-sorted records.
	 */
	@Getter private final SortedRecordsProvider[] sortedRecordsProviders;
	/**
	 * This sorter instance will be used for sorting entities, that cannot be sorted by this sorter.
	 */
	private final Sorter unknownRecordIdsSorter;

	/**
	 * Maps positions to the record ids in presorted set respecting start and end index.
	 */
	@Nonnull
	private static PartialSortResult fetchSlice(
		@Nonnull SortedRecordsProvider sortedRecordsProvider,
		@Nonnull RoaringBitmap positions,
		int skip,
		int length,
		@Nonnull int[] result,
		int peak,
		@Nonnull int[] buffer
	) {
		final RoaringBatchIterator batchIterator = positions.getBatchIterator();
		final int[] preSortedRecordIds = sortedRecordsProvider.getSortedRecordIds();
		int toSkip = skip;
		int read = 0;
		int toRead = length;
		while (batchIterator.hasNext()) {
			final int bufferPeak = batchIterator.nextBatch(buffer);
			if (bufferPeak == 0) {
				// no more results break early
				break;
			}

			// skip previous pages quickly
			if (toSkip > 0) {
				toSkip -= bufferPeak;
			}
			// now we are on the page
			if (toSkip <= 0) {
				// copy records for page
				final int startIndex = toSkip == 0 ? 0 : bufferPeak + toSkip;
				for (int i = startIndex; toRead > 0 && i < bufferPeak; i++) {
					result[peak++] = preSortedRecordIds[buffer[i]];
					read++;
					toRead--;
				}
				// normalize toSkip
				if (toSkip < 0) {
					toSkip = 0;
				}
			}
			// finish - page was read
			if (toRead <= 0) {
				break;
			}
		}
		return new PartialSortResult(skip - toSkip, length - read, peak);
	}

	/**
	 * Returns mask of the positions in the presorted array that matched the computational result
	 * Mask also contains record ids not found in presorted record index.
	 */
	private static MaskResult getMask(
		@Nonnull QueryContext queryContext,
		@Nonnull SortedRecordsProvider sortedRecordsProvider,
		@Nonnull RoaringBitmap selectedRecordIds,
		int selectedRecordCount
	) {
		final int[] bufferA = queryContext.borrowBuffer();
		final int[] bufferB = queryContext.borrowBuffer();
		try {
			final Bitmap unsortedRecordIds = sortedRecordsProvider.getAllRecords();
			final int[] recordPositions = sortedRecordsProvider.getRecordPositions();

			final RoaringBitmapWriter<RoaringBitmap> mask = RoaringBitmapBackedBitmap.buildWriter();
			final RoaringBitmapWriter<RoaringBitmap> notFound = RoaringBitmapBackedBitmap.buildWriter();

			final BatchIterator unsortedRecordIdsIt = RoaringBitmapBackedBitmap.getRoaringBitmap(unsortedRecordIds).getBatchIterator();
			final BatchIterator selectedRecordIdsIt = selectedRecordIds.getBatchIterator();

			int matchesFound = 0;
			int notFoundCount = 0;
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
					notFoundCount++;
					peakB++;
				} else {
					peakA++;
				}
			} while (matchesFound < selectedRecordCount && limitB > 0);

			return new MaskResult(mask.get(), notFound.get(), notFoundCount);
		} finally {
			queryContext.returnBuffer(bufferA);
			queryContext.returnBuffer(bufferB);
		}
	}

	public MergedSortedRecordsSupplier(
		@Nonnull SortedRecordsProvider[] sortedRecordsProviders,
		@Nullable Sorter unknownRecordIdsSorter
	) {
		this.sortedRecordsProviders = sortedRecordsProviders;
		this.unknownRecordIdsSorter = unknownRecordIdsSorter;
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return new MergedSortedRecordsSupplier(
			sortedRecordsProviders, null
		);
	}

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		return new MergedSortedRecordsSupplier(
			sortedRecordsProviders, sorterForUnknownRecords
		);
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return unknownRecordIdsSorter;
	}

	@Override
	public int sortAndSlice(@Nonnull QueryContext queryContext, @Nonnull Formula input, int startIndex, int endIndex, @Nonnull int[] result, int peak) {
		final Bitmap selectedRecordIds = input.compute();
		if (selectedRecordIds.size() < startIndex) {
			throw new IndexOutOfBoundsException("Index: " + startIndex + ", Size: " + selectedRecordIds.size());
		}
		if (selectedRecordIds.isEmpty()) {
			return 0;
		} else {
			final int[] buffer = queryContext.borrowBuffer();
			try {
				final SortResult sortResult = collectPartialResults(
					queryContext, selectedRecordIds, startIndex, endIndex, result, peak, buffer
				);
				return returnResultAppendingUnknown(
					queryContext, sortResult.notSortedRecords(),
					unknownRecordIdsSorter,
					startIndex, endIndex,
					result, sortResult.peak(), buffer
				);
			} finally {
				queryContext.returnBuffer(buffer);
			}
		}
	}

	@Override
	public boolean shouldApply(@Nonnull QueryContext queryContext) {
		return queryContext.getPrefetchedEntities() == null;
	}

	@Nonnull
	private SortResult collectPartialResults(
		@Nonnull QueryContext queryContext,
		@Nonnull Bitmap selectedRecordIds,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak,
		@Nonnull int[] buffer
	) {
		final int toRead = endIndex - startIndex;
		int alreadyRead = 0;
		int skip = startIndex;

		RoaringBitmap recordsToSort = RoaringBitmapBackedBitmap.getRoaringBitmap(selectedRecordIds);
		int recordsToSortCount = selectedRecordIds.size();
		for (final SortedRecordsProvider sortedRecordsProvider : sortedRecordsProviders) {
			final MaskResult maskResult = getMask(queryContext, sortedRecordsProvider, recordsToSort, recordsToSortCount);
			final PartialSortResult currentResult = fetchSlice(
				sortedRecordsProvider, maskResult.mask(), skip, toRead - alreadyRead,
				result, peak, buffer
			);
			skip = skip - currentResult.skipped();
			alreadyRead += currentResult.read();
			recordsToSort = maskResult.notFoundRecords();
			recordsToSortCount = maskResult.notFoundRecordsCount();
			peak = currentResult.peak();
			if (alreadyRead >= toRead || recordsToSortCount == 0) {
				break;
			}
		}
		return new SortResult(recordsToSort, peak);
	}

	/**
	 * Mask result of the positions in the presorted array that matched the computational result.
	 *
	 * @param mask                 IntegerBitmap of positions of record ids in presorted set.
	 * @param notFoundRecords      IntegerBitmap of record ids not found in presorted set at all.
	 * @param notFoundRecordsCount Count of records not found in presorted set at all.
	 */
	private record MaskResult(
		@Nonnull RoaringBitmap mask,
		@Nonnull RoaringBitmap notFoundRecords,
		int notFoundRecordsCount
	) {
	}

	/**
	 * This DTO allows to pass multiple counters as a result of the method
	 *
	 * @param skipped number of records skipped
	 * @param read    number of records read
	 * @param peak    current peak index in the result array
	 */
	private record PartialSortResult(
		int skipped,
		int read,
		int peak
	) {
	}

	/**
	 * This DTO allows to information collected from {@link #collectPartialResults(QueryContext, Bitmap, int, int, int[], int, int[])}
	 * method.
	 *
	 * @param notSortedRecords roaring bitmap with all records that hasn't been sorted yet
	 * @param peak             current peak index in the result array
	 */
	private record SortResult(
		RoaringBitmap notSortedRecords,
		int peak
	) {
	}

}
