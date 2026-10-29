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

package io.evitadb.core.query.sort.attribute.sorter;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.chunk.OffsetAndLimit;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.ForcedSortResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.PositionResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.MergedSortedRecordsSupplierContract;
import io.evitadb.index.attribute.ReferenceSortedRecordsProvider;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.roaringbitmap.RoaringBatchIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Implementation of the {@link SortedRecordsProvider} that merges multiple instances into a one discarding
 * the duplicate record primary keys. This operation is quite costly for large data sets and should be cached.
 *
 * A traversal block is one referenced entity - the sorting context names it - and its providers are all the providers
 * of that entity: one per representative reference key and scope, lined up in the order of the keys
 * ({@link RepresentativeReferenceKey#STRICT_COMPARATOR}) and, for one key, of the scopes, live first. The providers
 * claim the block's records in that order, each the records it holds that no earlier provider claimed - so an owner
 * is represented by its first row, in key order, that carries the value (a provider holds only the owners with
 * a value). The claimed records are then combined in one of two ways:
 *
 * - **merged by value** when the sorter has a value {@link Comparator}: the owners follow their values, then their
 *   primary keys in the direction of the ordering, whichever provider claimed them - the merge is block-local,
 *   a provider of another block never takes part;
 * - **appended one after another** when it has none - a chain, whose positions are not comparable across providers:
 *   the owners follow the provider that claimed them, so the chain of an earlier key precedes the chain of a later one
 *   and, for one key, the live chain precedes the archived one.
 *
 * A block with a single provider is walked directly in both cases, at the cost of the plain concatenation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public final class MergedSortedRecordsSupplierSorter implements Sorter, MergedSortedRecordsSupplierContract {
	/**
	 * Contains the {@link SortedRecordsProvider} implementation with merged pre-sorted records.
	 */
	private final SortedRecordsProvider[] sortedRecordsProviders;
	/**
	 * The slice of {@link #sortedRecordsProviders} holding the providers of each referenced entity - the providers of
	 * all its representative keys and scopes; `null` when the providers are not reference providers. Resolved on the
	 * first sort of a traversal block (see {@link #targetSlicesResolved}) - a sort without a block walks every
	 * provider and never needs it.
	 */
	@Nullable private Map<ReferenceKey, OffsetAndLimit> targetSlices;
	/**
	 * Whether {@link #targetSlices} was resolved; the slices alone cannot tell, because they stay `null` for providers
	 * that are not reference providers.
	 */
	private boolean targetSlicesResolved;
	/**
	 * Comparator of the provider values in the direction of the ordering, merging the providers of one block by value;
	 * `null` when the values are not comparable across providers (a chain) and the providers are appended instead.
	 */
	@SuppressWarnings("rawtypes") @Nullable private final Comparator comparator;
	/**
	 * Direction of the ordering, applied to the primary keys of records whose values compare equal in a block merge.
	 */
	@Nonnull private final OrderDirection primaryKeyOrder;
	/**
	 * Block-local merges of the blocks holding more than one provider, created on the first sort of the block and
	 * reused for the rest of the query; `null` until the first one is needed.
	 */
	@Nullable private Map<ReferenceKey, MergedComparableSortedRecordsSupplierSorter> blockMerges;

	/**
	 * Creates a mapping of {@link ReferenceKey} objects to their corresponding {@link OffsetAndLimit} values,
	 * indicating the positions and pagination limits of records in each sorted records provider.
	 *
	 * The method iterates through the provided sorted records providers and generates the mapping by identifying changes
	 * in the {@link ReferenceKey} and calculating the appropriate offsets and limits for each key. The providers of one
	 * key must lie next to each other - a key met again after another one replaces its earlier slice.
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
	 * Creates the slice of the providers of each referenced entity: the providers of all representative reference keys
	 * and scopes of one referenced entity, which must lie next to each other - a referenced entity met again after
	 * another one is a premise violation, since its earlier providers would be lost from its block.
	 *
	 * @param sortedRecordsProviders the providers in the order in which they claim records
	 * @return the slice of each referenced entity, `null` when the providers are not reference providers
	 */
	@Nullable
	private static Map<ReferenceKey, OffsetAndLimit> createTargetSlices(
		@Nonnull SortedRecordsProvider[] sortedRecordsProviders
	) {
		final int srpCount = sortedRecordsProviders.length;
		Map<ReferenceKey, OffsetAndLimit> targetSlices = null;
		ReferenceKey target = null;
		int sliceStart = -1;
		for (int i = 0; i < srpCount; i++) {
			if (sortedRecordsProviders[i] instanceof ReferenceSortedRecordsProvider rssp) {
				final ReferenceKey providerTarget = rssp.getReferenceKey().referenceKey();
				if (!providerTarget.equals(target)) {
					if (targetSlices == null) {
						targetSlices = CollectionUtils.createHashMap(srpCount);
					} else if (target != null) {
						putTargetSlice(targetSlices, target, sliceStart, i, srpCount);
					}
					target = providerTarget;
					sliceStart = i;
				}
			}
		}
		if (target != null) {
			putTargetSlice(targetSlices, target, sliceStart, srpCount, srpCount);
		}
		return targetSlices;
	}

	/**
	 * Registers the slice of one referenced entity, refusing a second slice of the same entity.
	 *
	 * @param targetSlices the slices registered so far
	 * @param target       the referenced entity
	 * @param offset       the first provider of the slice
	 * @param limit        the position after the last provider of the slice
	 * @param srpCount     the number of all providers
	 */
	private static void putTargetSlice(
		@Nonnull Map<ReferenceKey, OffsetAndLimit> targetSlices,
		@Nonnull ReferenceKey target,
		int offset,
		int limit,
		int srpCount
	) {
		final OffsetAndLimit previous = targetSlices.put(target, new OffsetAndLimit(offset, limit, srpCount));
		Assert.isPremiseValid(
			previous == null,
			() -> "The providers of the referenced entity `" + target + "` are not contiguous!"
		);
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
		@Nonnull PersistentRoaringBitmap positions,
		@Nonnull IntSet alreadySortedRecordIds,
		int skip,
		int length,
		@Nonnull int[] result,
		int peak,
		@Nonnull int[] buffer,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final RoaringBatchIterator batchIterator = positions.getBatchIterator();
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
						skippedRecordsConsumer.accept(sortedRecordsProvider.recordAt(buffer[i]));
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
					final int preSortedRecordId = sortedRecordsProvider.recordAt(buffer[i]);
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
	 * Creates the sorter.
	 *
	 * @param sortedRecordsProviders providers in the order in which they claim records, the providers of one
	 *                               referenced entity next to each other
	 * @param comparator             comparator of the provider values in the direction of the ordering, merging the
	 *                               providers of one block by value; `null` to append them one after another
	 * @param primaryKeyOrder        direction of the ordering, applied to the primary keys of records with equal
	 *                               values in a block merge
	 */
	public MergedSortedRecordsSupplierSorter(
		@Nonnull SortedRecordsProvider[] sortedRecordsProviders,
		@SuppressWarnings("rawtypes") @Nullable Comparator comparator,
		@Nonnull OrderDirection primaryKeyOrder
	) {
		this.sortedRecordsProviders = sortedRecordsProviders;
		this.comparator = comparator;
		this.primaryKeyOrder = primaryKeyOrder;
	}

	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final QueryExecutionContext queryContext = sortingContext.queryContext();
		// a traversal block names its referenced entity - the representative attribute values carry no meaning there
		final RepresentativeReferenceKey contextKey = sortingContext.referenceKey();
		final ReferenceKey blockKey = contextKey == null ? null : contextKey.referenceKey();
		final OffsetAndLimit offsetAndLimit = blockKey == null ?
			new OffsetAndLimit(0, this.sortedRecordsProviders.length, this.sortedRecordsProviders.length) :
			getOffsetAndLimit(blockKey);

		if (
			this.comparator != null && blockKey != null && offsetAndLimit.offset() >= 0 &&
				offsetAndLimit.limit() - offsetAndLimit.offset() > 1
		) {
			// a block of several providers (the representative keys and scopes of one referenced entity) merges the
			// owners its providers claim, in their order, by value
			return getBlockMerge(blockKey, offsetAndLimit).sortAndSlice(sortingContext, result, skippedRecordsConsumer);
		} else if (queryContext.getPrefetchedEntities() == null && offsetAndLimit.offset() >= 0) {
			final int[] buffer = queryContext.borrowBuffer();
			final int[] resolveBufferA = queryContext.borrowBuffer();
			final int[] resolveBufferB = queryContext.borrowBuffer();
			// debug override (or null for cost-based) + per-strategy telemetry tally (null when telemetry is off)
			final ForcedSortResolution forcedResolution = SortResolutionStrategies.resolveForcedResolution(queryContext);
			final int[] strategyTally = SortResolutionStrategies.newStrategyTally(queryContext);
			try {
				final int startIndex = sortingContext.recomputedStartIndex();
				final int endIndex = sortingContext.recomputedEndIndex();
				final int peak = sortingContext.peak();
				final Bitmap selectedRecordIds = sortingContext.nonSortedKeys();
				final int toRead = endIndex - startIndex;
				int alreadyRead = 0;
				int skipped = 0;

				PersistentRoaringBitmap recordsToSort = RoaringBitmapBackedBitmap.getRoaringBitmap(selectedRecordIds);
				final IntSet alreadySortedRecordIds = new IntHashSet(selectedRecordIds.size());
				int recordsToSortCount = selectedRecordIds.size();
				for (int i = offsetAndLimit.offset(); i < offsetAndLimit.limit(); i++) {
					final SortedRecordsProvider sortedRecordsProvider = this.sortedRecordsProviders[i];
					final PositionResolution resolution = sortedRecordsProvider.resolvePositions(
						recordsToSort, recordsToSortCount, resolveBufferA, resolveBufferB, forcedResolution
					);
					SortResolutionStrategies.tally(strategyTally, resolution);
					final PartialSortResult currentResult = fetchSlice(
						sortedRecordsProvider, resolution.mask(), alreadySortedRecordIds,
						startIndex - skipped,
						toRead - alreadyRead,
						result,
						peak + alreadyRead,
						buffer, skippedRecordsConsumer
					);
					skipped += currentResult.skipped();
					alreadyRead += currentResult.read();
					recordsToSort = resolution.notFoundRecords();
					recordsToSortCount = resolution.notFoundRecordsCount();
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
				SortResolutionStrategies.report(queryContext, strategyTally);
				queryContext.returnBuffer(buffer);
				queryContext.returnBuffer(resolveBufferA);
				queryContext.returnBuffer(resolveBufferB);
			}
		} else {
			return sortingContext;
		}
	}

	/**
	 * Returns the merge of the providers of one block, creating it over a copy of the block's slice of the providers
	 * on the first sort of the block. The merge sees only the block's own providers, so a provider of another block
	 * can never claim an owner of this one.
	 *
	 * @param blockKey       the referenced entity of the block
	 * @param offsetAndLimit the slice of {@link #sortedRecordsProviders} belonging to the block
	 * @return the block-local merge
	 */
	@Nonnull
	private MergedComparableSortedRecordsSupplierSorter getBlockMerge(
		@Nonnull ReferenceKey blockKey,
		@Nonnull OffsetAndLimit offsetAndLimit
	) {
		if (this.blockMerges == null) {
			this.blockMerges = new HashMap<>();
		}
		MergedComparableSortedRecordsSupplierSorter blockMerge = this.blockMerges.get(blockKey);
		if (blockMerge == null) {
			blockMerge = new MergedComparableSortedRecordsSupplierSorter(
				Objects.requireNonNull(this.comparator),
				this.primaryKeyOrder,
				Arrays.copyOfRange(this.sortedRecordsProviders, offsetAndLimit.offset(), offsetAndLimit.limit())
			);
			this.blockMerges.put(blockKey, blockMerge);
		}
		return blockMerge;
	}

	/**
	 * Retrieves the slice of the providers of the referenced entity the specified {@link ReferenceKey} names.
	 * If no mapping exists for the provided key, a default {@link OffsetAndLimit} is returned with an offset of -1,
	 * a limit of 0, and the total record count based on the length of the {@code sortedRecordsProviders}.
	 *
	 * @param referenceKey the referenced entity whose providers are looked up; must not be null.
	 * @return the {@link OffsetAndLimit} associated with the provided {@link ReferenceKey},
	 * or a default {@link OffsetAndLimit} if no mapping exists.
	 */
	@Nonnull
	private OffsetAndLimit getOffsetAndLimit(@Nonnull ReferenceKey referenceKey) {
		if (!this.targetSlicesResolved) {
			this.targetSlices = createTargetSlices(this.sortedRecordsProviders);
			this.targetSlicesResolved = true;
		}
		if (this.targetSlices != null) {
			final OffsetAndLimit indexes = this.targetSlices.get(referenceKey);
			if (indexes != null) {
				return indexes;
			}
		}
		return new OffsetAndLimit(-1, 0, this.sortedRecordsProviders.length);
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
