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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.requestResponse.data.HierarchicalPlacementContract;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;

import static java.util.Optional.ofNullable;

/**
 * Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and may be
 * referred by multiple child entities. Hierarchy is always composed of entities of same type.
 * Each entity must be part of at most single hierarchy (tree).
 * Hierarchy can limit returned entities by using filtering constraints {@link HierarchyWithin}. It's also used for
 * computation of extra data - such as {@link HierarchyContent}. It can also invert type of returned entities in case requirement
 * {@link HierarchyOfSelf} is used.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
@Data
@EqualsAndHashCode(of = {"version"})
public class HierarchicalPlacement implements HierarchicalPlacementContract {
	@Serial private static final long serialVersionUID = 3371882656956767136L;

	/**
	 * Contains version of this object and gets increased with any update of the hierarchy placement. Allows to execute
	 * optimistic locking i.e. avoiding parallel modifications.
	 */
	private final int version;
	/**
	 * Reference to {@link Entity#getPrimaryKey()} of the parent entity.
	 */
	private final Integer parentPrimaryKey;
	/**
	 * Represents order of this entity among other entities under the same parent. It's recommended to be unique, but
	 * it isn't enforced so it could behave like reversed priority where lower number is better (i.e. Integer.MIN is
	 * the first entity under the parent, Integer.MAX is the last entity under the same parent).
	 */
	private final int orderAmongSiblings;
	/**
	 * Contains TRUE if hierarchy placement was dropped - i.e. removed. Placement is not removed (unless tidying process
	 * does it), but are lying among other attributes with tombstone flag. Dropped placements can be overwritten by
	 * a new value continuing with the versioning where it was stopped for the last time.
	 */
	private final boolean dropped;

	/**
	 * Creates root entity placement.
	 */
	public HierarchicalPlacement(int orderAmongSiblings) {
		this.version = 1;
		this.parentPrimaryKey = null;
		this.orderAmongSiblings = orderAmongSiblings;
		this.dropped = false;
	}

	/**
	 * Creates non-root entity placement.
	 */
	public HierarchicalPlacement(int parentPrimaryKey, int orderAmongSiblings) {
		this.version = 1;
		this.parentPrimaryKey = parentPrimaryKey;
		this.orderAmongSiblings = orderAmongSiblings;
		this.dropped = false;
	}

	public HierarchicalPlacement(int version, Integer parentPrimaryKey, int orderAmongSiblings) {
		this.version = version;
		this.parentPrimaryKey = parentPrimaryKey;
		this.orderAmongSiblings = orderAmongSiblings;
		this.dropped = false;
	}

	public HierarchicalPlacement(int version, Integer parentPrimaryKey, int orderAmongSiblings, boolean dropped) {
		this.version = version;
		this.parentPrimaryKey = parentPrimaryKey;
		this.orderAmongSiblings = orderAmongSiblings;
		this.dropped = dropped;
	}

	@Override
	public int compareTo(HierarchicalPlacementContract o) {
		final int parentComparison = Integer.compare(ofNullable(parentPrimaryKey).orElse(Integer.MIN_VALUE), ofNullable(o.getParentPrimaryKey()).orElse(Integer.MIN_VALUE));
		if (parentComparison == 0) {
			return Integer.compare(orderAmongSiblings, o.getOrderAmongSiblings());
		} else {
			return parentComparison;
		}
	}

	@Override
	public String toString() {
		return (dropped ? "❌ " : "") + "hierarchy ↰" + parentPrimaryKey + " ↕" + orderAmongSiblings;
	}
}
