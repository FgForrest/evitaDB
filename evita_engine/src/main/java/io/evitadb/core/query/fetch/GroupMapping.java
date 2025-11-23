/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.query.fetch;


import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.IntCursor;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;

/**
 * This class provides an efficient mapping between entity primary keys and their associated group primary keys.
 * It optimizes storage by using different data structures for different mapping scenarios:
 *
 * - When an entity is associated with the default group primary key, it's stored in a simple set
 * - When an entity is associated with a different group primary key, it's stored in a map
 *
 * This approach reduces memory usage while maintaining fast lookup performance.
 */
class GroupMapping {
	private final int groupPrimaryKey;
	private final IntSet entityIds;
	private IntObjectMap<IntSet> entityToGroupMapping;

	/**
	 * Creates a new GroupMapping instance with the specified default group primary key.
	 *
	 * @param groupPrimaryKey  the default group primary key for this mapping
	 * @param expectedElements the expected number of elements to be stored in this mapping,
	 *                         used for initial capacity optimization
	 */
	public GroupMapping(int entityPrimaryKey, int groupPrimaryKey, int expectedElements) {
		this.groupPrimaryKey = groupPrimaryKey;
		this.entityIds = new IntHashSet(expectedElements);
		this.entityIds.add(entityPrimaryKey);
	}

	/**
	 * Adds a mapping between an entity and its associated group.
	 * <p>
	 * If the group primary key matches the default group primary key for this mapping,
	 * the entity ID is added to the entity set. Otherwise, the entity-to-group mapping
	 * is stored in a separate map.
	 *
	 * @param entityPrimaryKey the primary key of the entity
	 * @param groupPrimaryKey  the primary key of the group associated with the entity
	 */
	public void addMapping(int entityPrimaryKey, int groupPrimaryKey) {
		// If the mapped group equals the default group, just record the entity in the fast set
		if (groupPrimaryKey == this.groupPrimaryKey) {
			this.entityIds.add(entityPrimaryKey);
		} else {
			// Otherwise, keep per-entity mapping with potentially multiple group ids
			if (this.entityToGroupMapping == null) {
				this.entityToGroupMapping = new IntObjectHashMap<>(4);
			}
			final IntSet groups = this.entityToGroupMapping.get(entityPrimaryKey);
			if (groups == null) {
				final IntSet newSet = new IntHashSet(4);
				newSet.add(groupPrimaryKey);
				this.entityToGroupMapping.put(entityPrimaryKey, newSet);
			} else {
				groups.add(groupPrimaryKey);
			}
		}
	}

	/**
	 * Retrieves the group ID associated with the specified entity primary key.
	 *
	 * @param entityPrimaryKey the primary key of the entity for which to retrieve the group ID
	 * @return the group ID associated with the entity, or {@code null} if no mapping exists
	 */
	@Nonnull
	public IntStream getGroupId(int entityPrimaryKey) {
		if (this.entityIds.contains(entityPrimaryKey)) {
			return IntStream.of(this.groupPrimaryKey);
		} else if (this.entityToGroupMapping == null) {
			return IntStream.empty();
		} else {
			final IntSet groups = this.entityToGroupMapping.get(entityPrimaryKey);
			if (groups == null) {
				return IntStream.empty();
			} else {
				final Iterator<IntCursor> it = groups.iterator();
				return IntStream.generate(() -> {
					if (it.hasNext()) {
						return it.next().value;
					} else {
						throw new NoSuchElementException();
					}
				}).limit(groups.size());
			}
		}
	}
}
