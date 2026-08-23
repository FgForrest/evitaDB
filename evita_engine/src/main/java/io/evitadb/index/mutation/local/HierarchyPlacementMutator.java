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

package io.evitadb.index.mutation.local;

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Co-location interface for hierarchy placement mutation routines that keep {@link EntityIndexLocalMutationExecutor}
 * from growing unboundedly. All methods are static and operate on the {@link io.evitadb.index.hierarchy.HierarchyIndex}
 * embedded in a given {@link EntityIndex}.
 *
 * Hierarchy in evitaDB is a per-entity-type tree where each entity may declare at most one parent entity of the
 * same type. The index records this parent-child relationship and enables hierarchy-aware filtering constraints
 * such as `hierarchyWithin` and extra-result computations such as `hierarchyOfSelf`. The index is only maintained
 * for scopes in which hierarchy indexing is explicitly enabled by the entity schema
 * (see {@link io.evitadb.api.requestResponse.schema.EntitySchemaContract#isHierarchyIndexedInScope(Scope)}).
 *
 * This interface follows the same co-location pattern used by {@link AttributeIndexMutator} and
 * {@link PriceIndexMutator} — procedural, stateless mutation logic is moved into static interface methods and
 * imported with a static import in {@link EntityIndexLocalMutationExecutor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface HierarchyPlacementMutator {

	/**
	 * Records the hierarchical placement of an entity in the {@link io.evitadb.index.hierarchy.HierarchyIndex}
	 * of the supplied {@link EntityIndex}.
	 *
	 * The method is invoked when an entity is first created with a parent, or when an existing entity's parent
	 * is updated via {@link io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation}. It is also
	 * called during full re-indexing to restore hierarchy positions from entity body data.
	 *
	 * The update is skipped silently when the entity's scope does not have hierarchy indexing enabled, allowing
	 * entities to carry parent information without making it queryable in that scope (e.g. archived entities).
	 *
	 * When `parentPrimaryKey` is `null` the entity is placed at the root of the hierarchy tree. The
	 * {@link io.evitadb.index.hierarchy.HierarchyIndex} supports out-of-order insertions: a child entity can be
	 * indexed before its parent exists, in which case it is held in an orphan set until the parent is eventually
	 * indexed.
	 *
	 * @param executor           the active mutation executor that provides access to entity schema and index state
	 * @param entityIndex        the index whose embedded hierarchy index will be updated
	 * @param primaryKeyToIndex  the primary key of the entity being placed in the hierarchy
	 * @param parentPrimaryKey   the primary key of the parent entity, or `null` if the entity is a root node
	 * @throws io.evitadb.exception.EvitaInvalidUsageException if the entity schema does not have hierarchy enabled
	 *                                                         (i.e. {@link EntitySchema#isWithHierarchy()} returns
	 *                                                         `false`)
	 */
	static void setParent(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		int primaryKeyToIndex,
		@Nullable Integer parentPrimaryKey
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		Assert.isTrue(
			entitySchema.isWithHierarchy(),
			"Schema does not enable hierarchy - " +
				"cannot set hierarchical placement for `" + entitySchema.getName() + "`!"
		);

		final Scope scope = entityIndex.getIndexKey().scope();
		if (entitySchema.isHierarchyIndexedInScope(scope)) {
			entityIndex.addNode(primaryKeyToIndex, parentPrimaryKey);
			executor.reportEntityCapabilityTouched(Capability.HIERARCHICAL, scope);
		}
	}

	/**
	 * Removes the hierarchical placement of an entity from the {@link io.evitadb.index.hierarchy.HierarchyIndex}
	 * of the supplied {@link EntityIndex}.
	 *
	 * The method is invoked when an entity's parent relationship is cleared via
	 * {@link io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation}, or when a hierarchical
	 * entity is deleted and its index entry must be cleaned up.
	 *
	 * The update is skipped silently when the entity's scope does not have hierarchy indexing enabled, mirroring
	 * the guard in {@link #setParent}.
	 *
	 * @param executor           the active mutation executor that provides access to entity schema and index state
	 * @param entityIndex        the index whose embedded hierarchy index will be updated
	 * @param primaryKeyToIndex  the primary key of the entity being removed from the hierarchy
	 * @throws io.evitadb.exception.EvitaInvalidUsageException if the entity schema does not have hierarchy enabled
	 *                                                         (i.e. {@link EntitySchema#isWithHierarchy()} returns
	 *                                                         `false`)
	 */
	static void removeParent(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		int primaryKeyToIndex
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		Assert.isTrue(
			entitySchema.isWithHierarchy(),
			"Schema does not enable hierarchy - " +
				"cannot remove hierarchical placement for `" + entitySchema.getName() + "`!");

		final Scope scope = entityIndex.getIndexKey().scope();
		if (entitySchema.isHierarchyIndexedInScope(scope)) {
			entityIndex.removeNode(primaryKeyToIndex);
			executor.reportEntityCapabilityTouched(Capability.HIERARCHICAL, scope);
		}
	}

}
