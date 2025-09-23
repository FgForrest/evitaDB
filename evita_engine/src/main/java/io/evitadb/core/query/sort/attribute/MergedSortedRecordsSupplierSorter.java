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
import io.evitadb.api.requestResponse.chunk.OffsetAndLimit;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.attribute.ReferenceSortedRecordsProvider;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.CollectionUtils;
import org.roaringbitmap.BatchIterator;
import org.roaringbitmap.RoaringBatchIterator;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;

import static java.util.Optional.ofNullable;

/**
 * Implementation of the {@link SortedRecordsProvider} that merges multiple instances into a one discarding
 * the duplicate record primary keys. This operation is quite costly for large data sets and should be cached.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public final class MergedSortedRecordsSupplierSorter implements Sorter, MergedSortedRecordsSupplierContract {
	/**
	 * Contains the {@link SortedRecordsProvider} implementation with merged pre-sorted records.
	 */
	private final SortedRecordsProvider[] sortedRecordsProviders;
	/**
	 * Contains the index of the {@link ReferenceKey} in the {@link #sortedRecordsProviders} that is used for sorting.
	 */
	private final Map<RepresentativeReferenceKey, OffsetAndLimit> referenceKeyIndexes;

	/**
	 * Creates a mapping of {@link ReferenceKey} objects to their corresponding {@link OffsetAndLimit} values,
	 * indicating the positions and pagination limits of records in each sorted records provider.
	 *
	 * The method iterates through the provided sorted records providers and generates the mapping by identifying changes
	 * in the {@link ReferenceKey} and calculating the appropriate offsets and limits for each key.
	 *
	 * @param sortedRecordsProviders an array of {@link SortedRecordsProvider} objects,
	 *                               which serve as sources of pre-sorted records to process; must not be null.
	 * @return a map of {@link ReferenceKey} to {@link OffsetAndLimit} providing positional and pagination data
	 * for each reference key. Returns null if the input array does not contain instances of {@link ReferenceSortedRecordsProvider}.
	 */
	@Nullable
	public static LinkedHashMap<RepresentativeReferenceKey, OffsetAndLimit> createSortedRecordsOffsets(@Nonnull SortedRecordsProvider[] sortedRecordsProviders) {
		final int srpCount = sortedRecordsProviders.length;
		LinkedHashMap<RepresentativeReferenceKey, OffsetAndLimit> referenceKeyIndexes = null;
		RepresentativeReferenceKey referenceKey = null;
		OffsetAndLimit oal = null;
		for (int i = 0; i < srpCount; i++) {
			final SortedRecordsProvider sortedRecordsProvider = sortedRecordsProviders[i];
			if (sortedRecordsProvider instanceof ReferenceSortedRecordsProvider rssp) {
				if (!Objects.equals(rssp.getReferenceKey(), referenceKey)) {
					if (oal != null) {
						referenceKeyIndexes.put(referenceKey, new OffsetAndLimit(oal.offset(), i, srpCount));
					}
					referenceKey = rssp.getReferenceKey();
					oal = new OffsetAndLimit(i, 0, srpCount);
					if (referenceKeyIndexes == null) {
						referenceKeyIndexes = CollectionUtils.createLinkedHashMap(srpCount);
					}
				}
			}
		}
		if (oal != null) {
			referenceKeyIndexes.put(referenceKey, new OffsetAndLimit(oal.offset(), srpCount, srpCount));
		}
		return referenceKeyIndexes;
	}

	/**
	 * Maps positions to the record ids in presorted set respecting start and end index.
	 * Retrieves a slice of records from the sorted records based on the given parameters.
	 * Handles skipping of records, filtering already processed records, and adds the resulting
	 * records to the provided result array. It also accounts for skipped records using an
	 * optional consumer.
	 *
	 * @param sortedRecordsProvider  the provider that supplies access to sorted records
	 * @param positions              a bitmap indicating the positions of the records to retrieve
	 * @param alreadySortedRecordIds a set of record IDs that have already been processed
	 * @param skip                   the number of records to skip from the beginning of the positions bitmap
	 * @param length                 the number of records to retrieve
	 * @param result                 an array where the fetched record IDs will be stored
	 * @param peak                   the index at which to start populating the result array
	 * @param buffer                 an auxiliary buffer used to process record positions
	 * @param skippedRecordsConsumer an optional consumer for processing skipped records
	 * @return a PartialSortResult object containing the number of skipped records,
	 * the number of records read, and the updated peak index in the result array
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
			final int skippedInBuffer;
			if (toSkip > 0) {
				skippedInBuffer = Math.max(0, Math.min(toSkip, bufferPeak));
				if (skippedRecordsConsumer != null) {
					// skip records in buffer, cap really read records in the buffer
					for (int i = 0; i < skippedInBuffer; i++) {
						skippedRecordsConsumer.accept(preSortedRecordIds[buffer[i]]);
					}
				}
				toSkip -= bufferPeak;
			} else {
				skippedInBuffer = 0;
			}

			// now we are on the page
			if (toSkip <= 0) {
				// copy records for page
				for (int i = skippedInBuffer; toRead > 0 && i < bufferPeak; i++) {
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

	public MergedSortedRecordsSupplierSorter(@Nonnull SortedRecordsProvider[] sortedRecordsProviders) {
		this.sortedRecordsProviders = sortedRecordsProviders;
		this.referenceKeyIndexes = createSortedRecordsOffsets(sortedRecordsProviders);
	}

	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final QueryExecutionContext queryContext = sortingContext.queryContext();
		final OffsetAndLimit offsetAndLimit = ofNullable(sortingContext.referenceKey())
			.map(this::getOffsetAndLimit)
			.orElse(new OffsetAndLimit(0, this.sortedRecordsProviders.length, this.sortedRecordsProviders.length));

		if (queryContext.getPrefetchedEntities() == null && offsetAndLimit.offset() >= 0) {
			final int[] buffer = queryContext.borrowBuffer();
			try {
				final int startIndex = sortingContext.recomputedStartIndex();
				final int endIndex = sortingContext.recomputedEndIndex();
				final int peak = sortingContext.peak();
				final Bitmap selectedRecordIds = sortingContext.nonSortedKeys();
				final int toRead = endIndex - startIndex;
				int alreadyRead = 0;
				int skipped = 0;

				RoaringBitmap recordsToSort = RoaringBitmapBackedBitmap.getRoaringBitmap(selectedRecordIds);
				final IntSet alreadySortedRecordIds = new IntHashSet(selectedRecordIds.size());
				int recordsToSortCount = selectedRecordIds.size();
				for (int i = offsetAndLimit.offset(); i < offsetAndLimit.limit(); i++) {
					final SortedRecordsProvider sortedRecordsProvider = this.sortedRecordsProviders[i];
					final MaskResult maskResult = getMask(
						queryContext, sortedRecordsProvider, recordsToSort, recordsToSortCount
					);
					final PartialSortResult currentResult = fetchSlice(
						sortedRecordsProvider, maskResult.mask(), alreadySortedRecordIds,
						startIndex - skipped,
						toRead - alreadyRead,
						result,
						peak + alreadyRead,
						buffer, skippedRecordsConsumer
					);
					skipped += currentResult.skipped();
					alreadyRead += currentResult.read();
					recordsToSort = maskResult.notFoundRecords();
					recordsToSortCount = maskResult.notFoundRecordsCount();
					if (alreadyRead >= toRead || recordsToSortCount == 0) {
						break;
					}
				}

				return sortingContext.createResultContext(
					recordsToSort.isEmpty() ?
						EmptyBitmap.INSTANCE : new BaseBitmap(recordsToSort),
					alreadyRead,
					skipped
				);
			} finally {
				queryContext.returnBuffer(buffer);
			}
		} else {
			return sortingContext;
		}
	}

	/**
	 * Retrieves the {@link OffsetAndLimit} settings associated with the specified {@link ReferenceKey}.
	 * If no mapping exists for the provided key, a default {@link OffsetAndLimit} is returned with an offset of -1,
	 * a limit of 0, and the total record count based on the length of the {@code sortedRecordsProviders}.
	 *
	 * @param referenceKey the key used to lookup the associated {@link OffsetAndLimit};
	 *                     must not be null.
	 * @return the {@link OffsetAndLimit} associated with the provided {@link ReferenceKey},
	 * or a default {@link OffsetAndLimit} if no mapping exists.
	 */
	@Nonnull
	private OffsetAndLimit getOffsetAndLimit(@Nonnull RepresentativeReferenceKey referenceKey) {
		if (this.referenceKeyIndexes != null) {
			final OffsetAndLimit indexes = this.referenceKeyIndexes.getOrDefault(referenceKey, null);
			if (indexes != null) {
				return indexes;
			}
		}
		return new OffsetAndLimit(-1, 0, this.sortedRecordsProviders.length);
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

}
