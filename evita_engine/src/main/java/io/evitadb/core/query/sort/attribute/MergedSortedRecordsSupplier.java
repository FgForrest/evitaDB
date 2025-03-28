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

package io.evitadb.core.query.sort.attribute;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.ConditionalSorter;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.generic.AbstractRecordsSorter;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.BatchIterator;
import org.roaringbitmap.RoaringBatchIterator;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.function.IntConsumer;

/**
 * Implementation of the {@link SortedRecordsProvider} that merges multiple instances into a one discarding
 * the duplicate record primary keys. This operation is quite costly for large data sets and should be cached.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public final class MergedSortedRecordsSupplier extends AbstractRecordsSorter implements ConditionalSorter, Serializable, MergedSortedRecordsSupplierContract {
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
	 * Retrieves a slice of records from the sorted records based on the given parameters.
	 * Handles skipping of records, filtering already processed records, and adds the resulting
	 * records to the provided result array. It also accounts for skipped records using an
	 * optional consumer.
	 *
	 * @param sortedRecordsProvider the provider that supplies access to sorted records
	 * @param positions a bitmap indicating the positions of the records to retrieve
	 * @param alreadySortedRecordIds a set of record IDs that have already been processed
	 * @param skip the number of records to skip from the beginning of the positions bitmap
	 * @param length the number of records to retrieve
	 * @param result an array where the fetched record IDs will be stored
	 * @param peak the index at which to start populating the result array
	 * @param buffer an auxiliary buffer used to process record positions
	 * @param skippedRecordsConsumer an optional consumer for processing skipped records
	 *
	 * @return a PartialSortResult object containing the number of skipped records,
	 *         the number of records read, and the updated peak index in the result array
	 */
	@Nonnull
	private static PartialSortResult fetchSlice(
		@Nonnull SortedRecordsProvider sortedRecordsProvider,
		@Nonnull RoaringBitmap positions,
		@Nonnull IntSet alreadySortedRecordIds,
		int skip,
		int length,
		@Nonnull int[] result,
		int peak,
		@Nonnull int[] buffer,
		@Nullable IntConsumer skippedRecordsConsumer
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
				if (skippedRecordsConsumer != null) {
					// skip records in buffer, cap really read records in the buffer
					final int skippedInBuffer = Math.max(0, Math.min(toSkip, bufferPeak));
					for (int i = 0; i < skippedInBuffer; i++) {
						skippedRecordsConsumer.accept(preSortedRecordIds[buffer[i]]);
					}
				}
				toSkip -= bufferPeak;
			}

			// now we are on the page
			if (toSkip <= 0) {
				// copy records for page
				final int startIndex = toSkip == 0 ? 0 : bufferPeak + toSkip;
				for (int i = startIndex; toRead > 0 && i < bufferPeak; i++) {
					final int preSortedRecordId = preSortedRecordIds[buffer[i]];
					if (!alreadySortedRecordIds.contains(preSortedRecordId)) {
						result[peak++] = preSortedRecordId;
						read++;
						toRead--;
						alreadySortedRecordIds.add(preSortedRecordId);
					}
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
		return new PartialSortResult(skip - toSkip, read, peak);
	}

	/**
	 * Returns mask of the positions in the presorted array that matched the computational result
	 * Mask also contains record ids not found in presorted record index.
	 */
	@Nonnull
	private static MaskResult getMask(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull SortedRecordsProvider sortedRecordsProvider,
		@Nonnull RoaringBitmap selectedRecordIds,
		int selectedRecordCount
	) {
		final int[] unsortedRecordsBuffer = queryContext.borrowBuffer();
		final int[] selectedRecordsBuffer = queryContext.borrowBuffer();
		try {
			final Bitmap unsortedRecordIds = sortedRecordsProvider.getAllRecords();
			final int[] recordPositions = sortedRecordsProvider.getRecordPositions();

			final RoaringBitmapWriter<RoaringBitmap> mask = RoaringBitmapBackedBitmap.buildWriter();
			final RoaringBitmapWriter<RoaringBitmap> notFound = RoaringBitmapBackedBitmap.buildWriter();

			final BatchIterator unsortedRecordIdsIt = RoaringBitmapBackedBitmap.getRoaringBitmap(unsortedRecordIds).getBatchIterator();
			final BatchIterator selectedRecordIdsIt = selectedRecordIds.getBatchIterator();

			int matchesFound = 0;
			int notFoundCount = 0;
			int unsortedRecordsPeak = -1;
			int unsortedRecordsRead = -1;
			int selectedRecordsPeak = -1;
			int selectedRecordsRead = -1;
			int unsortedRecordsAcc = 1;
			do {
				if (unsortedRecordsPeak == unsortedRecordsRead && unsortedRecordsRead != 0) {
					unsortedRecordsAcc += unsortedRecordsRead;
					unsortedRecordsRead = unsortedRecordIdsIt.nextBatch(unsortedRecordsBuffer);
					unsortedRecordsPeak = 0;
				}
				if (selectedRecordsPeak == selectedRecordsRead) {
					selectedRecordsRead = selectedRecordIdsIt.nextBatch(selectedRecordsBuffer);
					selectedRecordsPeak = 0;
				}
				if (unsortedRecordsPeak < unsortedRecordsRead && unsortedRecordsBuffer[unsortedRecordsPeak] == selectedRecordsBuffer[selectedRecordsPeak]) {
					mask.add(recordPositions[unsortedRecordsAcc + unsortedRecordsPeak]);
					matchesFound++;
					selectedRecordsPeak++;
					unsortedRecordsPeak++;
				} else if (selectedRecordsPeak < selectedRecordsRead && (unsortedRecordsPeak >= unsortedRecordsRead || unsortedRecordsBuffer[unsortedRecordsPeak] > selectedRecordsBuffer[selectedRecordsPeak])) {
					notFound.add(selectedRecordsBuffer[selectedRecordsPeak]);
					notFoundCount++;
					selectedRecordsPeak++;
				} else {
					unsortedRecordsPeak++;
				}
			} while (matchesFound < selectedRecordCount && selectedRecordsRead > 0);

			return new MaskResult(mask.get(), notFound.get(), notFoundCount);
		} finally {
			queryContext.returnBuffer(unsortedRecordsBuffer);
			queryContext.returnBuffer(selectedRecordsBuffer);
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
	public int sortAndSlice(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Formula input,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final Bitmap selectedRecordIds = input.compute();
		if (selectedRecordIds.size() < startIndex) {
			throw new IndexOutOfBoundsException("Index: " + startIndex + ", Size: " + selectedRecordIds.size());
		}
		if (selectedRecordIds.isEmpty()) {
			return 0;
		} else {
			final int[] buffer = queryContext.borrowBuffer();
			try {
				final SkippingRecordConsumer delegateConsumer = new SkippingRecordConsumer(skippedRecordsConsumer);
				final SortResult sortResult = collectPartialResults(
					queryContext, selectedRecordIds, startIndex, endIndex, result, peak, buffer, delegateConsumer
				);
				return returnResultAppendingUnknown(
					queryContext, sortResult.notSortedRecords(),
					this.unknownRecordIdsSorter,
					Math.max(0, startIndex - delegateConsumer.getCounter()),
					Math.max(0, endIndex - delegateConsumer.getCounter()),
					result, sortResult.peak(), buffer
				);
			} finally {
				queryContext.returnBuffer(buffer);
			}
		}
	}

	@Override
	public boolean shouldApply(@Nonnull QueryExecutionContext queryContext) {
		return queryContext.getPrefetchedEntities() == null;
	}

	/**
	 * Collects partially sorted results from multiple sorted record providers based on the specified
	 * range and stores them in the provided result array. The method processes sorted records,
	 * handles remaining unsorted records, and calculates the peak value of the sorted results.
	 *
	 * @param queryContext the execution context for the query being processed
	 * @param selectedRecordIds a bitmap containing the IDs of the records to be sorted
	 * @param startIndex the starting index in the sorted result range
	 * @param endIndex the ending index in the sorted result range
	 * @param result an array to store the sorted result
	 * @param peak the current peak index in the result array
	 * @param buffer an auxiliary buffer used for processing
	 * @param skippedRecordsConsumer a consumer for handling skipped record counts, or null if not needed
	 *
	 * @return a SortResult containing information about the remaining unsorted records and the final peak index
	 */
	@Nonnull
	private SortResult collectPartialResults(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Bitmap selectedRecordIds,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak,
		@Nonnull int[] buffer,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final int toRead = endIndex - startIndex;
		int alreadyRead = 0;
		int skip = startIndex;

		RoaringBitmap recordsToSort = RoaringBitmapBackedBitmap.getRoaringBitmap(selectedRecordIds);
		final IntSet alreadySortedRecordIds = new IntHashSet(selectedRecordIds.size());
		int recordsToSortCount = selectedRecordIds.size();
		for (final SortedRecordsProvider sortedRecordsProvider : this.sortedRecordsProviders) {
			final MaskResult maskResult = getMask(
				queryContext, sortedRecordsProvider, recordsToSort, recordsToSortCount
			);
			final PartialSortResult currentResult = fetchSlice(
				sortedRecordsProvider, maskResult.mask(), alreadySortedRecordIds,
				skip, toRead - alreadyRead,
				result, peak, buffer, skippedRecordsConsumer
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
	 * This DTO allows to information collected from
	 * {@link #collectPartialResults(QueryExecutionContext, Bitmap, int, int, int[], int, int[], IntConsumer) collectPartialResults}
	 * method.
	 *
	 * @param notSortedRecords roaring bitmap with all records that hasn't been sorted yet
	 * @param peak             current peak index in the result array
	 */
	private record SortResult(
		@Nonnull RoaringBitmap notSortedRecords,
		int peak
	) {
	}

	/**
	 * The SkippingRecordConsumer is an implementation of the {@link IntConsumer} interface that wraps an optional delegate
	 * {@code IntConsumer} and tracks the count of records processed. This class is intended to be used for scenarios
	 * where certain records need to be tracked, possibly skipped, or processed with optional side effects.
	 */
	@RequiredArgsConstructor
	private static class SkippingRecordConsumer implements IntConsumer {
		@Nullable private final IntConsumer delegate;
		@Getter private int counter = 0;

		@Override
		public void accept(int value) {
			if (this.delegate != null) {
				this.delegate.accept(value);
			}
			this.counter++;
		}
	}
}
