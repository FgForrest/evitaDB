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

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.BatchIterator;
import org.roaringbitmap.RoaringBatchIterator;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Implementation of the {@link SortedRecordsProvider} that merges multiple instances into a one discarding
 * the duplicate record primary keys. This operation is quite costly for large data sets and should be cached.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public final class MergedComparableSortedRecordsSupplierSorter implements Sorter, MergedSortedRecordsSupplierContract {
	/**
	 * Contains the {@link Comparator} for the sorting execution.
	 */
	@SuppressWarnings("rawtypes") private final Comparator comparator;
	/**
	 * Contains the {@link SortedRecordsProvider} implementation with merged pre-sorted records.
	 */
	@Getter private final SortedRecordsProvider[] sortedRecordsProviders;

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

	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final QueryExecutionContext queryContext = sortingContext.queryContext();
		if (queryContext.getPrefetchedEntities() == null) {
			final int startIndex = sortingContext.recomputedStartIndex();
			final int endIndex = sortingContext.recomputedEndIndex();
			final int peak = sortingContext.peak();
			final Bitmap selectedRecordIds = sortingContext.nonSortedKeys();
			final int[] buffer = queryContext.borrowBuffer();
			try {
				final int toRead = Math.min(endIndex - startIndex, result.length - peak);
				int alreadyRead = 0;
				int toSkip = startIndex;

				RoaringBitmap recordsToSort = RoaringBitmapBackedBitmap.getRoaringBitmap(selectedRecordIds);
				int recordsToSortCount = selectedRecordIds.size();

				// first we need to create masks for all selected record ids using provided sorted record providers
				final MaskResult[] maskResults = new MaskResult[this.sortedRecordsProviders.length];
				int maskPeak = -1;
				for (int i = 0; i < this.sortedRecordsProviders.length; i++) {
					final SortedRecordsProvider sortedRecordsProvider = this.sortedRecordsProviders[++maskPeak];
					final MaskResult maskResult = getMask(
						queryContext, sortedRecordsProvider, recordsToSort, recordsToSortCount
					);
					maskResults[maskPeak] = maskResult;
					recordsToSort = maskResult.notFoundRecords();
					recordsToSortCount = maskResult.notFoundRecordsCount();
					if (recordsToSortCount == 0) {
						break;
					}
				}

				// next we need to fetch comparable values for head values from each mask and compare them
				final SortedRecordsProviderBuffer[] sortedRecordsProviderBuffers = new SortedRecordsProviderBuffer[maskPeak + 1];
				int sortedRecordsProviderBufferPeak = 0;
				// init first values
				for (int i = 0; i <= maskPeak; i++) {
					final MaskResult maskResult = maskResults[i];
					final SortedRecordsProvider sortedRecordsProvider = this.sortedRecordsProviders[i];
					final SortedRecordsProviderBuffer sortedRecordsProviderBuffer = new SortedRecordsProviderBuffer(
						this.comparator, sortedRecordsProvider, maskResult.mask(), queryContext
					);
					if (sortedRecordsProviderBuffer.fetchNext()) {
						sortedRecordsProviderBuffers[sortedRecordsProviderBufferPeak++] = sortedRecordsProviderBuffer;
					} else {
						sortedRecordsProviderBuffer.close();
					}
				}

				// sort buffers
				Arrays.sort(sortedRecordsProviderBuffers, 0, sortedRecordsProviderBufferPeak);

				while ((toRead > alreadyRead || toSkip > 0) && sortedRecordsProviderBufferPeak > 0) {
					SortedRecordsProviderBuffer sortedPk = sortedRecordsProviderBuffers[0];
					if (toSkip > 0) {
						toSkip--;
						if (skippedRecordsConsumer != null) {
							skippedRecordsConsumer.accept(sortedPk.primaryKey());
						}
					} else {
						result[peak + alreadyRead++] = sortedPk.primaryKey();
					}
					// fetch next value for the used one
					if (sortedPk.fetchNext()) {
						if (sortedRecordsProviderBufferPeak > 1) {
							final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
								sortedPk, sortedRecordsProviderBuffers, 1, sortedRecordsProviderBufferPeak
							);
							// when the position is zero, we don't need to do anything and the sortedPk is already in the right place
							// it will produce next sorted PK in the next iteration
							if (insertionPosition.position() > 0) {
								if (insertionPosition.alreadyPresent()) {
									if (sortedRecordsProviderBuffers[insertionPosition.position() - 1].primaryKey() > sortedPk.primaryKey()) {
										// otherwise we need to perform copy of the existing values first
										System.arraycopy(sortedRecordsProviderBuffers, 1, sortedRecordsProviderBuffers, 0, insertionPosition.position() + 1);
										// and then insert the new value
										sortedRecordsProviderBuffers[insertionPosition.position() - 1] = sortedPk;
									} else {
										// otherwise we need to perform copy of the existing values first
										System.arraycopy(sortedRecordsProviderBuffers, 1, sortedRecordsProviderBuffers, 0, insertionPosition.position());
										// and then insert the new value
										sortedRecordsProviderBuffers[insertionPosition.position()] = sortedPk;
									}
								} else {
									// otherwise we need to perform copy of the existing values first
									System.arraycopy(sortedRecordsProviderBuffers, 1, sortedRecordsProviderBuffers, 0, Math.min(sortedRecordsProviderBuffers.length - 1, insertionPosition.position()));
									// and then insert the new value
									sortedRecordsProviderBuffers[insertionPosition.position() - 1] = sortedPk;
								}
							}
						}
					} else {
						// finalize the sorted record provider buffer
						sortedPk.close();
						// sorted record provider buffer is empty, we need to remove it from the array
						System.arraycopy(sortedRecordsProviderBuffers, 1, sortedRecordsProviderBuffers, 0, --sortedRecordsProviderBufferPeak);
					}
				}

				// finalize the rest of the sorted record providers
				for (int i = 0; i < sortedRecordsProviderBufferPeak; i++) {
					sortedRecordsProviderBuffers[i].close();
				}

				return sortingContext.createResultContext(
					recordsToSort.isEmpty() ?
						EmptyBitmap.INSTANCE : new BaseBitmap(recordsToSort),
					alreadyRead,
					startIndex - toSkip
				);
			} finally {
				queryContext.returnBuffer(buffer);
			}
		} else {
			return sortingContext;
		}
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
	 * Represents a buffer for managing and extracting data from sorted record providers while applying a mask filter.
	 * This class implements the {@link Comparable} interface for comparing buffer instances based on their current comparable value
	 * and can be used in a priority queue to process sorted records efficiently.
	 *
	 * Additionally, implements {@link AutoCloseable} to manage the lifecycle of allocated resources such as borrowed buffers.
	 * When closed, the buffer resources are returned to their respective providers.
	 *
	 * Each instance of this buffer holds state information required for iterating and comparing sorted records, as
	 * well as applying masks that specify which records are to be included.
	 */
	private static class SortedRecordsProviderBuffer implements Comparable<SortedRecordsProviderBuffer>, AutoCloseable {
		/**
		 * The comparator used for sorting and comparing records in the buffer.
		 */
		@SuppressWarnings("rawtypes") private final Comparator comparator;
		/**
		 * An iterator over batches of integers in the mask bitmap.
		 * Used to access the indices of relevant sorted records.
		 */
		private final RoaringBatchIterator maskIterator;
		/**
		 * Array of record IDs in sorted order provided by the associated {@link SortedRecordsProvider}.
		 */
		private final int[] sortedRecordIds;
		/**
		 * Seeker for extracting comparable values from the sorted records array at specific positions.
		 * Used to compare records during sorting.
		 */
		private final SortedComparableForwardSeeker sortedComparableForwardSeeker;
		/**
		 * The query execution context containing utilities for managing resources like buffers.
		 */
		private final QueryExecutionContext queryContext;
		/**
		 * A reusable buffer borrowed from the query context for storing intermediate values during operation.
		 */
		private final int[] buffer;
		/**
		 * The primary key of the current record being processed.
		 * Updates as the buffer fetches the next record index from the mask.
		 */
		private int primaryKey;
		/**
		 * The current index within the mask buffer that is being processed.
		 */
		private int maskBufferIndex = 0;
		/**
		 * The maximum valid index in the mask buffer for the current batch.
		 */
		private int maskBufferPeak = 0;
		/**
		 * A flag indicating whether this buffer contains valid data for processing.
		 */
		private boolean usable;

		/**
		 * The comparable value of the currently processed record.
		 * Used for sorting and comparisons.
		 */
		private Serializable valueToCompare;

		public SortedRecordsProviderBuffer(
			@SuppressWarnings("rawtypes") @Nonnull Comparator comparator,
			@Nonnull SortedRecordsProvider sortedRecordsProvider,
			@Nonnull RoaringBitmap mask,
			@Nonnull QueryExecutionContext queryContext
		) {
			this.comparator = comparator;
			this.maskIterator = mask.getBatchIterator();
			this.sortedRecordIds = sortedRecordsProvider.getSortedRecordIds();
			this.sortedComparableForwardSeeker = sortedRecordsProvider.getSortedComparableForwardSeeker();
			this.sortedComparableForwardSeeker.reset();
			this.queryContext = queryContext;
			this.buffer = queryContext.borrowBuffer();
		}

		/**
		 * Advances to the next valid record in the buffer. It fetches data in batches if the current batch
		 * is exhausted and determines usability based on the availability of data. Updates the primary key
		 * and comparable value for the currently active record in the buffer.
		 *
		 * @return {@code true} if a new record is successfully fetched and is usable, {@code false} if
		 * there are no more records available in the buffer.
		 */
		public boolean fetchNext() {
			if (this.maskBufferIndex >= this.maskBufferPeak) {
				if (this.maskIterator.hasNext()) {
					this.maskBufferPeak = this.maskIterator.nextBatch(this.buffer);
					this.maskBufferIndex = 0;
					this.usable = this.maskBufferPeak > 0;
				} else {
					this.usable = false;
				}
			}
			if (this.usable) {
				final int position = this.buffer[this.maskBufferIndex++];
				this.primaryKey = this.sortedRecordIds[position];
				this.valueToCompare = this.sortedComparableForwardSeeker.getValueToCompareOn(position);
				return true;
			} else {
				return false;
			}
		}

		/**
		 * Retrieves the primary key of the current record in the buffer.
		 * The method ensures that the buffer is in a usable state before accessing the primary key.
		 *
		 * @return the primary key of the current record in the buffer
		 */
		public int primaryKey() {
			assertUsable();
			return this.primaryKey;
		}

		@Override
		public int compareTo(@Nonnull SortedRecordsProviderBuffer o) {
			assertUsable();
			// this is ok, we'll always be comparing values of same type and the sorting will never modify contents
			// of the comparable value, we use non-final fields to make the algorithm faster
			//noinspection unchecked,CompareToUsesNonFinalVariable
			final int comparisonResult = this.comparator.compare(this.valueToCompare, o.valueToCompare);
			if (comparisonResult == 0) {
				// then compare primary keys
				//noinspection CompareToUsesNonFinalVariable
				return Integer.compare(this.primaryKey, o.primaryKey);
			} else {
				return comparisonResult;
			}
		}

		@Override
		public void close() {
			// this will prevent returning buffer twice when close method is called multiple times
			if (this.maskBufferPeak >= 0) {
				this.queryContext.returnBuffer(this.buffer);
				this.maskBufferPeak = -1;
			}
		}

		@SuppressWarnings("NonFinalFieldReferencedInHashCode")
		@Override
		public int hashCode() {
			int result = this.primaryKey;
			result = 31 * result + Objects.hashCode(this.valueToCompare);
			return result;
		}

		@SuppressWarnings("NonFinalFieldReferenceInEquals")
		@Override
		public final boolean equals(Object o) {
			if (!(o instanceof SortedRecordsProviderBuffer that)) return false;

			return this.primaryKey == that.primaryKey && Objects.equals(this.valueToCompare, that.valueToCompare);
		}

		@Override
		public String toString() {
			return this.usable ?
				"SortedRecordsProviderBuffer: " + this.valueToCompare + " [" + this.primaryKey + "]" :
				"SortedRecordsProviderBuffer: ❌";
		}

		private void assertUsable() {
			Assert.isPremiseValid(this.usable, "Cannot compare unusable buffer");
		}
	}

}
