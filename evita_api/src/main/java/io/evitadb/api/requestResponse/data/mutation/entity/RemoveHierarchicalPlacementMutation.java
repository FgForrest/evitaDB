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

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.HierarchicalPlacementContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.HierarchicalPlacement;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * This mutation allows to remove {@link HierarchicalPlacement} from the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class RemoveHierarchicalPlacementMutation extends HierarchicalPlacementMutation {
	private static final HierarchicalPlacementContract REMOVAL_CONSTANT = new HierarchicalPlacement(Integer.MIN_VALUE, Integer.MIN_VALUE);
	@Serial private static final long serialVersionUID = 1740874836848423328L;

	@Nonnull
	@Override
	public HierarchicalPlacementContract mutateLocal(@Nonnull EntitySchemaContract entitySchema, @Nullable HierarchicalPlacementContract existingValue) {
		Assert.isTrue(
			existingValue != null && existingValue.exists(),
			() -> new InvalidMutationException("Cannot remove hierarchical placement that doesn't exists!")
		);
		return new HierarchicalPlacement(
			existingValue.getVersion() + 1, existingValue.getParentPrimaryKey(), existingValue.getOrderAmongSiblings(), true
		);
	}

	@Override
	public long getPriority() {
		return PRIORITY_REMOVAL;
	}

	@Override
	public HierarchicalPlacementContract getComparableKey() {
		return REMOVAL_CONSTANT;
	}

	@Override
	public String toString() {
		return "remove hierarchical placement";
	}

}
