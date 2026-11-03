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

package io.evitadb.core.query.sort;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.IntConsumer;

/**
 * Restricts a sorter to the records of the ordered scopes: the delegate sorts only the records of those scopes and the
 * records of the other scopes are passed on to the next sorter. It applies an ordering declared in an `inScope`
 * container that narrows the scopes of the query whenever the delegate itself does not tell the scopes apart - the
 * primary key orders ({@link NoSorter}, {@link io.evitadb.core.query.sort.primaryKey.sorter.ReversedPrimaryKeySorter},
 * {@link io.evitadb.core.query.sort.primaryKey.sorter.ExactSorter}) and the price orders, which would otherwise place
 * the records of every scope.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ScopedSorter implements Sorter {
	/**
	 * Primary keys of all records of the scopes the delegate orders.
	 */
	private final Bitmap scopeRecords;
	/**
	 * The sorter ordering the records of the scopes.
	 */
	private final Sorter delegate;

	/**
	 * Creates the sorter.
	 *
	 * @param scopeRecords primary keys of all records of the scopes the delegate orders
	 * @param delegate     the sorter ordering the records of the scopes
	 */
	public ScopedSorter(@Nonnull Bitmap scopeRecords, @Nonnull Sorter delegate) {
		this.scopeRecords = scopeRecords;
		this.delegate = delegate;
	}

	/**
	 * Lets the delegate order the records of the scopes and returns the context carrying the records of the other
	 * scopes together with the records the delegate passed on.
	 *
	 * @param sortingContext         the records to sort and the requested slice
	 * @param result                 the array the sorted records are written to
	 * @param skippedRecordsConsumer consumer of the records skipped before the requested slice, or `null`
	 * @return the context carrying the records left for the next sorter
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
		// the records of the scopes are ordered by the delegate alone
		final SortingContext scopeResult = this.delegate.sortAndSlice(
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
		// the records of the other scopes and the ones the delegate passed on go to the next sorter
		final Bitmap passedOn = scopeResult.nonSortedKeys();
		final PersistentRoaringBitmap leftOver = passedOn.isEmpty() ?
			outOfScope : PersistentRoaringBitmap.or(outOfScope, RoaringBitmapBackedBitmap.getRoaringBitmap(passedOn));
		return sortingContext.createResultContext(
			leftOver.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(leftOver),
			scopeResult.peak() - sortingContext.peak(),
			scopeResult.skipped() - sortingContext.skipped()
		);
	}

}
