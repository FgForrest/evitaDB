/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.sort.primaryKey.sorter;

import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.IntConsumer;

/**
 * Orders the records of a single {@link Scope} by ascending primary key and passes the records of the other scopes on
 * to the next sorter. It is the scoped counterpart of {@link NoSorter} for an ascending primary key order declared in
 * an `inScope` container of a segment ordering: unlike `NoSorter`, which places every record it receives, it must
 * leave the records of the other scopes to the following segments.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ScopedPrimaryKeySorter implements Sorter {
	/**
	 * Primary keys of all records of the scope this sorter orders.
	 */
	private final Bitmap scopeRecords;

	/**
	 * Creates the sorter.
	 *
	 * @param scopeRecords primary keys of all records of the scope the sorter orders
	 */
	public ScopedPrimaryKeySorter(@Nonnull Bitmap scopeRecords) {
		this.scopeRecords = scopeRecords;
	}

	/**
	 * Orders the records of the scope by ascending primary key - exactly as {@link NoSorter} does - and returns the
	 * context carrying the records of the other scopes.
	 *
	 * @param sortingContext         the records to sort and the requested slice
	 * @param result                 the array the sorted records are written to
	 * @param skippedRecordsConsumer consumer of the records skipped before the requested slice, or `null`
	 * @return the context carrying the records of the other scopes
	 */
	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final Bitmap nonSortedKeys = sortingContext.nonSortedKeys();
		if (nonSortedKeys.isEmpty() || this.scopeRecords.isEmpty()) {
			return sortingContext;
		}
		final PersistentRoaringBitmap selected = RoaringBitmapBackedBitmap.getRoaringBitmap(nonSortedKeys);
		final PersistentRoaringBitmap scope = RoaringBitmapBackedBitmap.getRoaringBitmap(this.scopeRecords);
		final PersistentRoaringBitmap inScope = PersistentRoaringBitmap.and(selected, scope);
		if (inScope.isEmpty()) {
			return sortingContext;
		}
		final PersistentRoaringBitmap outOfScope = PersistentRoaringBitmap.andNot(selected, scope);
		// the records of the scope are ordered by the very sorter an unscoped ascending primary key order uses
		final SortingContext scopeResult = NoSorter.INSTANCE.sortAndSlice(
			new SortingContext(
				sortingContext.queryContext(),
				new BaseBitmap(inScope),
				sortingContext.startIndex(),
				sortingContext.endIndex(),
				sortingContext.peak(),
				sortingContext.skipped(),
				sortingContext.referenceKey()
			),
			result,
			skippedRecordsConsumer
		);
		return sortingContext.createResultContext(
			outOfScope.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(outOfScope),
			scopeResult.peak() - sortingContext.peak(),
			scopeResult.skipped() - sortingContext.skipped()
		);
	}

}
