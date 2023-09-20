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
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import org.roaringbitmap.BatchIterator;
import org.roaringbitmap.RoaringBatchIterator;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Implementation of the {@link SortedRecordsProvider} that merges multiple instances into a one discarding
 * the duplicate record primary keys. This operation is quite costly for large data sets and should be cached.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class MergedSortedRecordsSupplier extends AbstractRecordsSorter implements ConditionalSorter {
	private static final int[] EMPTY_RESULT = new int[0];
	/**
	 * Contains the {@link SortedRecordsProvider} implementation with merged pre-sorted records.
	 */
	@Getter private final SortedRecordsProvider sortedRecordsProvider;
	/**
	 * This sorter instance will be used for sorting entities, that cannot be sorted by this sorter.
	 */
	private final Sorter unknownRecordIdsSorter;

	private MergedSortedRecordsSupplier(
		@Nonnull SortedRecordsProvider sortedRecordsProvider,
		@Nullable Sorter unknownRecordIdsSorter
	) {
		this.sortedRecordsProvider = sortedRecordsProvider;
		this.unknownRecordIdsSorter = unknownRecordIdsSorter;
	}

	public MergedSortedRecordsSupplier(
		@Nonnull SortedRecordsProvider[] sortedRecordsProviders,
		@Nullable Sorter unknownRecordIdsSorter
	) {
		if (sortedRecordsProviders.length == 1) {
			this.sortedRecordsProvider = new MergedSortedRecordsProvider(
				sortedRecordsProviders[0].getAllRecords() instanceof RoaringBitmapBackedBitmap roaringBitmapBackedBitmap ?
					roaringBitmapBackedBitmap : new BaseBitmap(sortedRecordsProviders[0].getAllRecords()),
				sortedRecordsProviders[0].getSortedRecordIds(),
				sortedRecordsProviders[0].getRecordPositions()
			);
		} else {
			// we need to go the hard way and merge the sorted records
			final int expectedMaxLength = Arrays.stream(sortedRecordsProviders)
				.map(SortedRecordsProvider::getAllRecords)
				.mapToInt(Bitmap::size).sum();
			final RoaringBitmap mergedAllRecords = new RoaringBitmap();
			final int[] mergedSortedRecordIds = new int[expectedMaxLength];
			final int[] mergedRecordPositions = new int[expectedMaxLength];
			int writePeak = -1;

			for (final SortedRecordsProvider sortedRecordsProvider : sortedRecordsProviders) {
				final int[] instanceSortedRecordIds = sortedRecordsProvider.getSortedRecordIds();
				for (int instanceSortedRecordId : instanceSortedRecordIds) {
					if (mergedAllRecords.checkedAdd(instanceSortedRecordId)) {
						writePeak++;
						mergedSortedRecordIds[writePeak] = instanceSortedRecordId;
						mergedRecordPositions[writePeak] = writePeak;
					}
				}
			}
			final BaseBitmap allRecords = new BaseBitmap(mergedAllRecords);
			final int[] sortedRecordIds = Arrays.copyOfRange(mergedSortedRecordIds, 0, writePeak + 1);
			final int[] recordPositions = Arrays.copyOfRange(mergedRecordPositions, 0, writePeak + 1);
			ArrayUtils.sortSecondAlongFirstArray(
				sortedRecordIds,
				recordPositions
			);
			this.sortedRecordsProvider = new MergedSortedRecordsProvider(
				allRecords, sortedRecordIds, recordPositions
			);
		}
		this.unknownRecordIdsSorter = unknownRecordIdsSorter;
	}

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		return new MergedSortedRecordsSupplier(
			sortedRecordsProvider, sorterForUnknownRecords
		);
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return new MergedSortedRecordsSupplier(
			sortedRecordsProvider, null
		);
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return unknownRecordIdsSorter;
	}

	@Override
	public boolean shouldApply(@Nonnull QueryContext queryContext) {
		return queryContext.getPrefetchedEntities() == null;
	}

	@Nonnull
	@Override
	public int[] sortAndSlice(@Nonnull QueryContext queryContext, @Nonnull Formula input, int startIndex, int endIndex) {
		final Bitmap selectedRecordIds = input.compute();
		if (selectedRecordIds.isEmpty()) {
			return EMPTY_RESULT;
		} else {
			final MaskResult positions = getMask(sortedRecordsProvider, selectedRecordIds);
			final SortResult sortPartialResult = fetchSlice(
				sortedRecordsProvider, selectedRecordIds.size(), positions.mask(), startIndex, endIndex
			);
			return returnResultAppendingUnknown(
				queryContext, sortPartialResult,
				positions.notFoundRecords(), unknownRecordIdsSorter,
				startIndex, endIndex
			);
		}
	}

	/**
	 * Maps positions to the record ids in presorted set respecting start and end index.
	 */
	@Nonnull
	private static SortResult fetchSlice(
		@Nonnull SortedRecordsProvider sortedRecordsProvider,
		int recordsFound,
		@Nonnull RoaringBitmap positions,
		int startIndex,
		int endIndex
	) {
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
