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

package io.evitadb.index.mutation.index;

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * This interface is used to co-locate hierarchy placement mutating routines which are rather procedural and long to
 * avoid excessive amount of code in {@link EntityIndexLocalMutationExecutor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface HierarchyPlacementMutator {

	/**
	 * Method updates {@link io.evitadb.index.hierarchy.HierarchyIndex} of the current & passed {@link EntityIndex}
	 * when hierarchy placement is specified in the entity (create or update).
	 */
	static void setParent(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		int primaryKeyToIndex,
		@Nullable Integer parentPrimaryKey,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		Assert.isTrue(entitySchema.isWithHierarchy(), "Hierarchy is not enabled by schema - cannot set hierarchical placement for " + entitySchema.getName() + "!");

		final Scope scope = entityIndex.getIndexKey().scope();
		if (entitySchema.isHierarchyIndexedInScope(scope)) {
			entityIndex.addNode(primaryKeyToIndex, parentPrimaryKey);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(() -> entityIndex.removeNode(primaryKeyToIndex));
			}
		}
	}

	/**
	 * Method updates {@link io.evitadb.index.hierarchy.HierarchyIndex} of the current & passed {@link EntityIndex}
	 * when hierarchy placement is removed from the entity.
	 */
	static void removeParent(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		int primaryKeyToIndex,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		Assert.isTrue(entitySchema.isWithHierarchy(), "Hierarchy is not enabled by schema - cannot remove hierarchical placement for " + entitySchema.getName() + "!");

		final Scope scope = entityIndex.getIndexKey().scope();
		if (entitySchema.isHierarchyIndexedInScope(scope)) {
			final Integer parentNodePk = entityIndex.removeNode(primaryKeyToIndex);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(() -> entityIndex.addNode(primaryKeyToIndex, parentNodePk));
			}
		}
	}

}
