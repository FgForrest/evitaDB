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

package io.evitadb.core.query.sort;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Provides access to presorted arrays of records according to certain attribute or other data value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public interface SortedRecordsSupplierFactory {

	/**
	 * Creates sorted records supplier for ascending order.
	 */
	@Nonnull
	SortedRecordsProvider getAscendingOrderRecordsSupplier();

	/**
	 * Creates sorted records supplier for descending order.
	 */
	@Nonnull
	SortedRecordsProvider getDescendingOrderRecordsSupplier();

	/**
	 * Provides access to sorted records array.
	 */
	interface SortedRecordsProvider {

		/**
		 * Empty sorted records provider behaves as if the sort index was empty.
		 */
		SortedRecordsProvider EMPTY = new SortedRecordsProvider() {

			@Override
			public int getRecordCount() {
				return 0;
			}

			@Nonnull
			@Override
			public Bitmap getAllRecords() {
				return EmptyBitmap.INSTANCE;
			}

			@Nonnull
			@Override
			public int[] getRecordPositions() {
				return ArrayUtils.EMPTY_INT_ARRAY;
			}

			@Nonnull
			@Override
			public int[] getSortedRecordIds() {
				return ArrayUtils.EMPTY_INT_ARRAY;
			}

			@Nonnull
			@Override
			public SortedComparableForwardSeeker getSortedComparableForwardSeeker() {
				return SortedComparableForwardSeeker.EMPTY;
			}
		};

		/**
		 * Returns count of all records of the supplier.
		 */
		int getRecordCount();

		/**
		 * Returns bitmap of all record ids present in the sort supplier in distinct ascending order.
		 * Example: 1, 3, 4, 6, 8, 12
		 */
		@Nonnull
		Bitmap getAllRecords();

		/**
		 * Contains index of record from {@link #getAllRecords()} in {@link #getSortedRecordIds()} array.
		 * Example: 1, 4, 5, 0, 3, 2
		 */
		@Nonnull
		int[] getRecordPositions();

		/**
		 * Returns array of records in "sorted" order - i.e. order that conforms to the referring {@link Comparable} order.
		 * Example: 6, 1, 12, 8, 3, 4
		 */
		@Nonnull
		int[] getSortedRecordIds();

		/**
		 * Returns the {@link SortedComparableForwardSeeker} that can be used to retrieve the sorted comparable value
		 * for a given position in the sorted records.
		 *
		 * @return the {@link SortedComparableForwardSeeker} instance.
		 */
		@Nonnull
		SortedComparableForwardSeeker getSortedComparableForwardSeeker();

	}

	/**
	 * Interface representing a forward seeker specifically for sorted collections of comparable records.
	 * Allows retrieval of a comparable value at a specific position in a sorted collection.
	 * The returned value should be consistent with the defined sorting order of the underlying records.
	 *
	 * Forward seeker is a design pattern that allows for efficient traversal of a collection in a forward direction.
	 */
	interface SortedComparableForwardSeeker {

		/**
		 * Empty sorted comparable forward seeker behaves as if the sort index was empty.
		 */
		SortedComparableForwardSeeker EMPTY = new SortedComparableForwardSeeker() {

			@Nonnull
			@Override
			public Serializable getValueToCompareOn(int position) throws ArrayIndexOutOfBoundsException {
				throw new ArrayIndexOutOfBoundsException("No comparable value available for the given position.");
			}
		};

		/**
		 * Resets the forward seeker to its initial state, allowing it to be reused for a new traversal.
		 */
		default void reset() {
			// No-op by default, can be overridden by subclasses if needed.
		}

		/**
		 * Retrieves a comparable value at the specified position within the sorted collection.
		 *
		 * @param position The position within the sorted collection from which the comparable
		 *                 value will be retrieved. Must be a valid index within the collection.
		 * @return The comparable value located at the specified position in the sorted collection.
		 * @throws ArrayIndexOutOfBoundsException If the provided position exceeds the bounds of the collection.
		 */
		@Nonnull
		Serializable getValueToCompareOn(int position)
			throws ArrayIndexOutOfBoundsException;
	}

	/**
	 * Provides access to sorted records array and {@link ReferenceKey} discriminator of the index producing the sorted
	 * records.
	 *
	 * TODO JNO - toto se použije v rámci hierarchického třídění
	 * TODO JNO - a nebo ne ... možná jsme současnou implementaci udělali jinak ... pak tedy toto smazat
	 */
	interface ReferenceSortedRecordsProvider extends SortedRecordsProvider {

		/**
		 * Retrieves the {@link ReferenceKey}, which uniquely identifies the reference schema and the corresponding
		 * entity or external resource associated with it.
		 *
		 * @return the unique {@link ReferenceKey} identifier for the reference schema and entity or resource.
		 */
		@Nonnull
		ReferenceKey getReferenceKey();

	}


}
