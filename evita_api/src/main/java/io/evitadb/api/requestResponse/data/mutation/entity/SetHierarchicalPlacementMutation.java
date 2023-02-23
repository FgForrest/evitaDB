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

package io.evitadb.api.requestResponse.data.mutation.entity;

import io.evitadb.api.requestResponse.data.HierarchicalPlacementContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.HierarchicalPlacement;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * This mutation allows to set {@link HierarchicalPlacement} in the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class SetHierarchicalPlacementMutation extends HierarchicalPlacementMutation {
	@Serial private static final long serialVersionUID = 8277337397634643354L;
	/**
	 * The hierarchical placement that needs to be set to the entity.
	 */
	@Nonnull
	private final HierarchicalPlacement hierarchicalPlacement;

	public SetHierarchicalPlacementMutation(int orderAmongSiblings) {
		this.hierarchicalPlacement = new HierarchicalPlacement(orderAmongSiblings);
	}

	public SetHierarchicalPlacementMutation(int parentPrimaryKey, int orderAmongSiblings) {
		this.hierarchicalPlacement = new HierarchicalPlacement(parentPrimaryKey, orderAmongSiblings);
	}

	@Nonnull
	@Override
	public HierarchicalPlacementContract mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable HierarchicalPlacementContract existingValue) {
		if (existingValue == null) {
			return ofNullable(hierarchicalPlacement.getParentPrimaryKey())
				.map(it -> new HierarchicalPlacement(it, hierarchicalPlacement.getOrderAmongSiblings()))
				.orElseGet(() -> new HierarchicalPlacement(hierarchicalPlacement.getOrderAmongSiblings()));
		} else if (!Objects.equals(existingValue.getParentPrimaryKey(), hierarchicalPlacement.getParentPrimaryKey()) || existingValue.getOrderAmongSiblings() != hierarchicalPlacement.getOrderAmongSiblings()) {
			return new HierarchicalPlacement(
				existingValue.getVersion() + 1,
				hierarchicalPlacement.getParentPrimaryKey(),
				hierarchicalPlacement.getOrderAmongSiblings()
			);
		} else {
			return existingValue;
		}
	}

	@Override
	public long getPriority() {
		return PRIORITY_UPSERT;
	}

	@Override
	public HierarchicalPlacementContract getComparableKey() {
		return hierarchicalPlacement;
	}

	@Nullable
	public Integer getParentPrimaryKey() {
		return hierarchicalPlacement.getParentPrimaryKey();
	}

	public int getOrderAmongSiblings() {
		return hierarchicalPlacement.getOrderAmongSiblings();
	}

	@Override
	public String toString() {
		return "set hierarchical placement to: `" + hierarchicalPlacement + "`";
	}

}
