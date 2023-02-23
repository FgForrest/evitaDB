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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.query.require.HierarchyParentsOfSelf;
import io.evitadb.api.query.require.HierarchyStatisticsOfSelf;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and may be
 * referred by multiple child entities. Hierarchy is always composed of entities of same type.
 * Each entity must be part of at most single hierarchy (tree).
 * Hierarchy can limit returned entities by using filtering constraints {@link HierarchyWithin} / {@link HierarchyWithinRoot}.
 * It's also used for computation of extra data - such as {@link HierarchyParentsOfSelf}. It can also invert type of returned entities
 * in case requirement {@link HierarchyStatisticsOfSelf} is used.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface HierarchicalPlacementContract extends Versioned, Droppable, Serializable, Comparable<HierarchicalPlacementContract>, ContentComparator<HierarchicalPlacementContract> {

	/**
	 * Reference to {@link Entity#getPrimaryKey()} of the parent entity.
	 * Null parent primary key means, that the entity is root entity with no parent (there may be multiple root entities).
	 */
	@Nullable
	Integer getParentPrimaryKey();

	/**
	 * Represents order of this entity among other entities under the same parent. It's recommended to be unique, but
	 * it isn't enforced so it could behave like reversed priority where lower number is better (i.e. Integer.MIN is
	 * the first entity under the parent, Integer.MAX is the last entity under the same parent).
	 */
	int getOrderAmongSiblings();

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	default int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE
			// parent
			+ MemoryMeasuringConstants.REFERENCE_SIZE
			// order among siblings
			+ MemoryMeasuringConstants.INT_SIZE;
	}

	/**
	 * Returns true if this hierarchical placement differs in key factors from the passed placement.
	 */
	@Override
	default boolean differsFrom(@Nullable HierarchicalPlacementContract otherHierarchicalPlacement) {
		if (otherHierarchicalPlacement == null) return true;
		if (!Objects.equals(getParentPrimaryKey(), otherHierarchicalPlacement.getParentPrimaryKey())) return true;
		if (getOrderAmongSiblings() != otherHierarchicalPlacement.getOrderAmongSiblings()) return true;
		return isDropped() != otherHierarchicalPlacement.isDropped();
	}
}
