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

package io.evitadb.core.query.algebra.price.filteredPriceRecords;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * This implementation contains the array of {@link PriceRecordContract} that were provided/filtered by appropriate
 * {@link Formula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
public class ResolvedFilteredPriceRecords implements FilteredPriceRecords {
	@Serial private static final long serialVersionUID = -6208329253169611746L;
	private static final PriceRecordContract[] EMPTY_PRICE_RECORDS = new PriceRecordContract[0];

	/**
	 * Collected price records that corresponds with the formula {@link Formula#compute()} output.
	 */
	private final PriceRecordContract[] priceRecords;
	/**
	 * Ordering form of the {@link #priceRecords}.
	 */
	@Getter private SortingForm sortingForm;

	public ResolvedFilteredPriceRecords() {
		this.priceRecords = EMPTY_PRICE_RECORDS;
		this.sortingForm = SortingForm.ENTITY_PK;
	}

	public ResolvedFilteredPriceRecords(@Nonnull PriceRecordContract[] priceRecords, @Nonnull SortingForm sortingForm) {
		this.priceRecords = priceRecords;
		this.sortingForm = sortingForm;
	}

	@Nonnull
	@Override
	public PriceRecordIterator getPriceRecordsLookup() {
		return new PriceRecordIterator(getPriceRecords());
	}

	@Override
	public void prepareForFlattening() {
		// we need to execute the sorting while we occupy the single thread only
		getPriceRecords();
	}

	/**
	 * Method returns the array of {@link PriceRecordContract} that were provided/filtered by appropriate
	 * {@link Formula}. The price records are always sorted by {@link PriceRecordContract#entityPrimaryKey()} in
	 * ascending order.
	 */
	public PriceRecordContract[] getPriceRecords() {
		if (this.sortingForm != SortingForm.ENTITY_PK) {
			Arrays.sort(this.priceRecords, ENTITY_PK_COMPARATOR);
			this.sortingForm = SortingForm.ENTITY_PK;
		}
		return this.priceRecords;
	}

	/**
	 * Implementation of {@link PriceRecordLookup} that iterates over the array of provided {@link PriceRecordContract}.
	 */
	public static class PriceRecordIterator implements PriceRecordLookup {
		/**
		 * Array of price records to iterate over.
		 */
		private final PriceRecordContract[] priceRecords;
		/**
		 * Auxiliary array that contains {@link PriceRecordContract#entityPrimaryKey()} that reflects the objects
		 * on exactly same positions in {@link #priceRecords} array. This array is used for searching via. binary search.
		 * Although we pay price for initial creation of the auxiliary array - it pays off due to better memory locality.
		 * The algorithm is around 20% faster.
		 */
		private final int[] entityIds;
		/**
		 * Contains the last index of the price record that represents the "searched through" part of the array where
		 * it has no sense to look up for next price record.
		 */
		private int lastIndex = -1;
		/**
		 * Last expected entity is the key closing the currently read batch of entity primary keys. It represents a hint
		 * for the search algorithm that allows to narrow the scope that is being looked at.
		 */
		private int lastExpectedEntity;
		/**
		 * Contains the index of the {@link #lastExpectedEntity} in {@link #entityIds}.
		 */
		private int lastExpectedEntityIndex;
		/**
		 * Search end index is a value computed from {@link #lastExpectedEntityIndex} that allows to limit the scope
		 * in which the entity PKs are looked up within {@link #entityIds}.
		 */
		private int searchEndIndex;

		public PriceRecordIterator(PriceRecordContract[] priceRecords) {
			this.priceRecords = priceRecords;
			this.entityIds = new int[priceRecords.length];
			for (int i = 0; i < priceRecords.length; i++) {
				this.entityIds[i] = priceRecords[i].entityPrimaryKey();
			}
		}

		@Override
		public boolean forEachPriceOfEntity(int entityPk, int lastExpectedEntity, @Nonnull Consumer<PriceRecordContract> priceConsumer) {
			// if the last expected entity changed
			if (this.lastExpectedEntity != lastExpectedEntity) {
				// look for the index of the last expected entity
				this.lastExpectedEntityIndex = findFirstEntityPkIndex(lastExpectedEntity, this.lastIndex + 1, this.entityIds.length);
				// compute the end of the area where the entityPk might be present - it must precede the index of last expected entity
				this.searchEndIndex = this.lastExpectedEntityIndex >= 0 ? this.lastExpectedEntityIndex + 1 : -1 * (this.lastExpectedEntityIndex) - 2 + 1;
				// remember the last expected entity
				this.lastExpectedEntity = lastExpectedEntity;
			} else if (entityPk == lastExpectedEntity) {
				// if we reached the last expected entity - we have already precomputed index - just use it
				if (this.lastExpectedEntityIndex >= 0) {
					this.lastIndex = this.lastExpectedEntityIndex;
					// we found it - so report it, but also check the subsequent records - there may be more of them for same entity id
					return reportFound(entityPk, this.lastExpectedEntityIndex, priceConsumer);
				} else {
					this.lastIndex = -1 * (this.lastExpectedEntityIndex) - 2;
					// we remembered, that we didn't find it
					return false;
				}
			}

			// if the entityPk is not the lastExpected entity find its proper location in shortened span
			// defined by searchEndIndex
			int priceRecordIndex = findFirstEntityPkIndex(entityPk, this.lastIndex + 1, this.searchEndIndex);

			// if we didn't find the record
			if (priceRecordIndex < 0) {
				// compute position to continue searching on
				this.lastIndex = -1 * (priceRecordIndex) - 2;
				// report not found
				return false;
			} else {
				this.lastIndex = priceRecordIndex;
				return reportFound(entityPk, priceRecordIndex, priceConsumer);
			}
		}

		/**
		 * Method finds the index of the `entityPk` in the array and fast-rewinds of the first matching record index
		 * in case there are several records matching the same `entityPk`.
		 */
		private int findFirstEntityPkIndex(int entityPk, int fromIndex, int toIndex) {
			// look for the index of price detail (searched block is getting smaller and smaller with each match)
			int priceRecordIndex = Arrays.binarySearch(
				this.entityIds, fromIndex, toIndex, entityPk
			);

			// there may be duplicates and binary search might have not found the exactly first occurrence
			while (priceRecordIndex > 0 && this.priceRecords[priceRecordIndex - 1].entityPrimaryKey() == entityPk) {
				priceRecordIndex--;
			}
			return priceRecordIndex;
		}

		/**
		 * Reports all {@link PriceRecordContract} records to `priceConsumer`. The array may contain multiple records
		 * with same {@link PriceRecordContract#entityPrimaryKey()} so we need to check subsequent records whether
		 * they are eligible for reporting or not.
		 */
		private boolean reportFound(int entityPk, int firstEntityPkIndex, @Nonnull Consumer<PriceRecordContract> priceConsumer) {
			// there may be duplicates in a row - we need to consume them all
			do {
				priceConsumer.accept(this.priceRecords[firstEntityPkIndex++]);
			} while (this.priceRecords.length > firstEntityPkIndex && this.priceRecords[firstEntityPkIndex].entityPrimaryKey() == entityPk);
			// report we found the record
			return true;
		}

	}

}
