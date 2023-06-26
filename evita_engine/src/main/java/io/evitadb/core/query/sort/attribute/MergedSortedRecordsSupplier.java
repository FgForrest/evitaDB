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

import io.evitadb.core.query.sort.CacheableSortedRecordsProvider;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.attribute.cache.FlattenedMergedSortedRecordsProvider;
import io.evitadb.index.attribute.SortIndex.SortedRecordsSupplier;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;
import lombok.RequiredArgsConstructor;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Implementation of the {@link SortedRecordsProvider} that merges multiple instances into a one discarding
 * the duplicate record primary keys. This operation is quite costly for large data sets and should be cached.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class MergedSortedRecordsSupplier implements CacheableSortedRecordsProvider {
	private static final long CLASS_ID = 795011057191754417L;
	private final @Nonnull SortedRecordsProvider[] sortedRecordsProviders;
	/**
	 * Contains memoized value of {@link #computeHash(LongHashFunction)} method.
	 */
	private Long memoizedHash;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} method.
	 */
	private long[] memoizedTransactionalIds;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} computed hash.
	 */
	private Long memoizedTransactionalIdHash;
	/**
	 * Contains memoized value of {@link #get()} method.
	 */
	private MergedSortedRecordsProvider memoizedResult;

	@Override
	public long computeHash(@Nonnull LongHashFunction hashFunction) {
		if (memoizedHash == null) {
			memoizedHash = hashFunction.hashLongs(
				Stream.of(
						LongStream.of(CLASS_ID),
						LongStream.of(
							Arrays.stream(sortedRecordsProviders)
								.filter(SortedRecordsSupplier.class::isInstance)
								.map(SortedRecordsSupplier.class::cast)
								.mapToLong(SortedRecordsSupplier::getTransactionalId)
								.toArray()
						)
					)
					.flatMapToLong(Function.identity())
					.toArray()
			);
		}
		return memoizedHash;
	}

	@Override
	public long computeTransactionalIdHash(@Nonnull LongHashFunction hashFunction) {
		if (memoizedTransactionalIdHash == null) {
			memoizedTransactionalIdHash = hashFunction.hashLongs(gatherTransactionalIds());
		}
		return memoizedTransactionalIdHash;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		if (memoizedTransactionalIds == null) {
			memoizedTransactionalIds = Arrays.stream(sortedRecordsProviders)
				.filter(SortedRecordsSupplier.class::isInstance)
				.map(SortedRecordsSupplier.class::cast)
				.mapToLong(SortedRecordsSupplier::getTransactionalId)
				.toArray();
		}
		return memoizedTransactionalIds;
	}

	@Override
	public long getEstimatedCost() {
		return get().getRecordCount() * getOperationCost();
	}

	@Override
	public long getCost() {
		return getEstimatedCost();
	}

	@Override
	public long getOperationCost() {
		return 19687L;
	}

	@Override
	public long getCostToPerformanceRatio() {
		return getCost() / (get().getRecordCount() * getOperationCost());
	}

	@Override
	public FlattenedMergedSortedRecordsProvider toSerializableResult(long extraResultHash, @Nonnull LongHashFunction hashFunction) {
		return new FlattenedMergedSortedRecordsProvider(
			computeHash(hashFunction),
			computeTransactionalIdHash(hashFunction),
			gatherTransactionalIds(),
			get()
		);
	}

	@Override
	public int getSerializableResultSizeEstimate() {
		return FlattenedMergedSortedRecordsProvider.estimateSize(gatherTransactionalIds(), get());
	}

	@Override
	public MergedSortedRecordsProvider get() {
		if (memoizedResult == null) {
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
			memoizedResult = new MergedSortedRecordsProvider(
				allRecords, sortedRecordIds, recordPositions
			);
		}
		return memoizedResult;
	}

}
