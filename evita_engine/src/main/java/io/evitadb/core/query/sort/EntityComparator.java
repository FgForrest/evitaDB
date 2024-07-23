/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
	 * Returns references to all entities that were lacking the data we were sort along - in other words such values was
	 * evaluated to NULL. Such entities need to be propagated to further evaluation.
	 *
	 * Method will produce result after {@link #compare(Object, Object)} was called on all the entities.
	 */
	@Nonnull
	Iterable<EntityContract> getNonSortedEntities();

}
