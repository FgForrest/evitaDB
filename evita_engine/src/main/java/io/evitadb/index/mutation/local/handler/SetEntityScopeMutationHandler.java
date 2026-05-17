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

package io.evitadb.index.mutation.local.handler;

import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.core.catalog.CatalogExpressionTriggerRegistry;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.Set;

/**
 * Handler for `SetEntityScopeMutation`. Unique among the handlers because the mutation spans both
 * scopes — the active entity is removed from the old scope's index families and re-added to the
 * new scope's index families. The handler must capture all attribute values that cross-entity
 * triggers depend on **before** the removal, because the source `FilterIndex` is the only place
 * those values are reachable after `removeEntityFromIndexes` runs.
 *
 * Implements the same `LocalMutationHandler<M>` interface as every other handler — the cross-scope
 * orchestration is implemented in terms of `executor.removeEntityFromIndexes` /
 * `executor.addEntityToIndexes`, which the handler invokes against the legacy private helpers
 * that remain on the executor (they are shared with the entity-removal path).
 */
public final class SetEntityScopeMutationHandler implements LocalMutationHandler<SetEntityScopeMutation> {

	public static final SetEntityScopeMutationHandler INSTANCE = new SetEntityScopeMutationHandler();

	private SetEntityScopeMutationHandler() {
		// singleton
	}

	@Nonnull
	@Override
	public Class<SetEntityScopeMutation> handledType() {
		return SetEntityScopeMutation.class;
	}

	@Override
	public void apply(
		@Nonnull SetEntityScopeMutation mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		final Entity entity = executor.getFullEntity();
		if (entity.getScope() == mutation.getScope()) {
			return;
		}
		// capture only the attribute values that cross-entity triggers actually reference
		final CatalogExpressionTriggerRegistry registry = executor.getCatalogExpressionTriggerRegistry();
		if (registry != null) {
			final Set<String> triggerAttributes = registry.getEntityAttributeNames(executor.getEntityType());
			if (!triggerAttributes.isEmpty()) {
				executor.captureEntityAttributeValues(entity, triggerAttributes);
			}
		}
		// remove the entity from the indexes
		Assert.isPremiseValid(
			Objects.equals(entity.getScope(), executor.getScope()),
			"Scope between entity and latest entity body container must be the same!"
		);
		executor.removeEntityFromIndexes(entity, entity.getScope());
		executor.addEntityToIndexes(entity, mutation.getScope());
		// reset memoized scope, it has just changed
		executor.setMemoizedScope(mutation.getScope());
	}

}
