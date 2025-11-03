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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;

/**
 * Reference comparator allows to sort {@link ReferenceDecorator} instances within {@link EntityContract}.
 * This comparator is used solely by {@link ReferenceFetcher} implementations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@SuppressWarnings("ComparatorNotSerializable")
public interface ReferenceComparator extends Comparator<ReferenceContract> {

	/**
	 * Default implementation sorts the references by their referenced primary key.
	 */
	ReferenceComparator DEFAULT = new ReferenceComparator() {

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
			throw new UnsupportedOperationException("Default implementation doesn't allow sub-sorting!");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return null;
		}

		@Override
		public int getNonSortedReferenceCount() {
			return 0;
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			if (o1 == null && o2 == null) {
				return 0;
			} else if (o1 != null && o2 == null) {
				return -1;
			} else if (o1 == null) {
				return 1;
			} else {
				final int firstComparison = Integer.compare(o1.getReferencedPrimaryKey(), o2.getReferencedPrimaryKey());
				if (firstComparison == 0) {
					// in case of equality, we fall back to comparing by reference type
					return Integer.compare(o1.getReferenceKey().internalPrimaryKey(), o2.getReferenceKey().internalPrimaryKey());
				} else {
					return firstComparison;
				}
			}
		}
	};

	/**
	 * Returns references to all entities that were lacking the data we were sort along - in other words such values was
	 * evaluated to NULL. Such entities need to be propagated to further evaluation.
	 *
	 * Method will produce result after {@link #compare(Object, Object)} was called on all the entities.
	 */
	int getNonSortedReferenceCount();

	/**
	 * Method allows creating combined comparator from this instance and passed instance.
	 * Creates new comparator that first sorts by this instance order and for rest records, that cannot be sorted by this
	 * sorter, the passed sorter will be used.
	 */
	@Nonnull
	ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords);

	/**
	 * Method returns next sorter in the sort chain. I.e. the sorter that will be applied on entity keys, that couldn't
	 * have been sorted by this sorter (due to lack of information).
	 */
	@Nullable
	ReferenceComparator getNextComparator();

	/**
	 * This interface defines a comparator that is aware of an entity's primary key.
	 * Implementations of this interface can receive and store the primary key of an entity,
	 * which can be used later in the comparison logic.
	 */
	interface EntityPrimaryKeyAwareComparator {

		/**
		 * Sets the primary key for an actual entity. This method is called before the comparison is made and everytime
		 * the entity has been exchanged.
		 *
		 * @param entityPrimaryKey the primary key of the entity to be set
		 */
		void setEntityPrimaryKey(int entityPrimaryKey);

	}

}
