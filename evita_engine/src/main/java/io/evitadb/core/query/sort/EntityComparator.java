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

package io.evitadb.core.query.sort;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import java.util.Comparator;

/**
 * Entity comparator allows to sort {@link EntityContract}. Its speciality is providing access to co called "non-sorted
 * entities". This iterable object contains references to all entities that were lacking the data we sort along - in
 * other words such values was evaluated to NULL. Such entities need to be propagated to further evaluation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@SuppressWarnings("ComparatorNotSerializable")
public interface EntityComparator extends Comparator<EntityContract> {

	/**
	 * Methods is called just before the comparator is used to prepare the comparator internal data structures to
	 * accommodate expected entity count.
	 * @param entityCount expected entity count to be sorted
	 */
	default void prepareFor(int entityCount) {}

	/**
	 * Method is called just before the comparator is used, with the primary keys of all the entities it is about to
	 * sort. A comparator whose order depends on the whole sorted set rather than on the two compared entities alone
	 * computes that dependency here - the prefetch route of a `pickFirst` reference ordering, for instance, ranks
	 * the referenced entities of the selection in the very order the index route walks them.
	 *
	 * @param entityPrimaryKeys primary keys of the entities to be sorted
	 */
	default void prepareForSelection(@Nonnull Bitmap entityPrimaryKeys) {}

	/**
	 * Classifies the entity before any comparison: tells whether it carries the data this comparator sorts along.
	 * {@link io.evitadb.core.query.sort.generic.PrefetchedRecordsSorter} calls the method exactly once for every entity
	 * of the selection - after {@link #prepareFor(int)} and {@link #prepareForSelection(Bitmap)}, and, for an
	 * {@link EntityReferenceSensitiveComparator}, while the key of the sorted block is bound - and sorts only the
	 * entities classified as sortable. The others are never compared: they are handed to the next sorter.
	 *
	 * Classifying up front is what makes an entity without the data detectable in every selection. A comparator that
	 * detects missing data only inside {@link #compare(Object, Object)} never sees an entity that is sorted alone -
	 * sorting a single element compares nothing - and such an entity is then placed by this comparator although it
	 * lacks the data, which is wrong wherever the next sorter would place it elsewhere (a traversal block of a
	 * reference ordering must pass such an owner on to the next block).
	 *
	 * The default classifies every entity as sortable, so a comparator that does not override the method keeps
	 * reporting missing data through {@link #getNonSortedEntities()} while it compares - the sorter consults both.
	 * An implementation that overrides the method must not report an entity it classified as not sortable through
	 * {@link #getNonSortedEntities()} as well; it may memoize whatever it computed for the comparisons that follow.
	 *
	 * @param entity the entity to classify
	 * @return `true` when the entity carries the data to be sorted by this comparator
	 */
	default boolean canSort(@Nonnull EntityContract entity) {
		return true;
	}

	/**
	 * Returns references to all entities that were lacking the data we were sort along - in other words such values were
	 * evaluated to NULL. Such entities need to be propagated to further evaluation.
	 *
	 * Method will produce result after {@link #compare(Object, Object)} was called on all the entities. Entities that
	 * {@link #canSort(EntityContract)} classified as not sortable are never compared and are not part of the result.
	 */
	@Nonnull
	Iterable<EntityContract> getNonSortedEntities();

}
