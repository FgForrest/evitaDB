/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.catalog;

import io.evitadb.index.mutation.DependencyType;
import io.evitadb.index.mutation.ExpressionIndexTrigger;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.List;

/**
 * Catalog-level inverted index that maps mutated entity types to the {@link ExpressionIndexTrigger} instances that
 * depend on their data.
 *
 * Built/rebuilt when entity schemas change. Consulted during the post-processing step of
 * `EntityIndexLocalMutationExecutor` to determine which cross-entity triggers need to fire.
 *
 * ## Ownership inversion
 *
 * The registry inverts the ownership of expression triggers:
 *
 * - **Schema A** (e.g., Product) *defines* a reference "parameter" with a conditional expression like
 *   `$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'`
 * - **Schema B** (e.g., ParameterGroup) is the *mutated entity type* — the registry indexes the trigger under
 *   schema B's entity type
 * - **Registry key:** `("parameterGroup", GROUP_ENTITY_ATTRIBUTE)`
 * - **Registry value:** `[trigger(ownerEntityType="product", referenceName="parameter", ...)]`
 *
 * So "ParameterGroup maintains triggers that fire handlers in Product."
 *
 * ## Immutability (copy-on-write)
 *
 * The registry is **immutable**. {@link #rebuildForEntityType} produces a **new** registry instance; the original
 * remains untouched and continues serving concurrent readers. Callers swap the reference (typically via
 * `TransactionalReference`) only after the rebuild is complete. This eliminates the risk of concurrent mutation
 * processing seeing stale or partially-built trigger state.
 *
 * ## Thread safety
 *
 * Safe for concurrent reads. Mutation is handled exclusively through {@link #rebuildForEntityType} producing a new
 * instance — no in-place modification.
 *
 * ## Generic storage
 *
 * The registry stores all {@link ExpressionIndexTrigger} subtypes without type discrimination. Both
 * `FacetExpressionTrigger` and `HistogramExpressionTrigger` instances coexist in the same index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public interface CatalogExpressionTriggerRegistry {

	/**
	 * Empty registry singleton — the initial value before any schemas are loaded.
	 */
	CatalogExpressionTriggerRegistry EMPTY =
		new CatalogExpressionTriggerRegistryImpl(Collections.emptyMap());

	/**
	 * Finds all triggers that depend on the given entity type with the specified dependency relationship.
	 * Called during post-processing to discover which target collections need notification.
	 *
	 * @param mutatedEntityType the entity type being mutated (e.g., "parameterGroup")
	 * @param dependencyType    how the mutated entity relates to the owner
	 * @return all matching triggers (empty list if none, never `null`)
	 */
	@Nonnull
	List<ExpressionIndexTrigger> getTriggersFor(
		@Nonnull String mutatedEntityType,
		@Nonnull DependencyType dependencyType
	);

	/**
	 * More selective variant — returns only triggers whose
	 * {@link ExpressionIndexTrigger#getDependentAttributes()} contains the given attribute name.
	 * Avoids firing triggers when the changed attribute is irrelevant to the expression.
	 *
	 * A trigger with an empty `dependentAttributes` set is never returned by this method (since
	 * `Set.contains()` on an empty set always returns `false`). Such a trigger has no cross-entity
	 * attribute dependencies and should only be retrievable via {@link #getTriggersFor}.
	 *
	 * @param mutatedEntityType the entity type being mutated
	 * @param dependencyType    how the mutated entity relates to the owner
	 * @param attributeName     the attribute that changed
	 * @return matching triggers filtered by attribute (empty list if none, never `null`)
	 */
	@Nonnull
	List<ExpressionIndexTrigger> getTriggersForAttribute(
		@Nonnull String mutatedEntityType,
		@Nonnull DependencyType dependencyType,
		@Nonnull String attributeName
	);

	/**
	 * Rebuilds the registry index for the given entity type based on the provided list of new triggers.
	 * Called when an entity schema's reference definitions change.
	 *
	 * The caller (typically `Catalog`) is responsible for building the trigger list by invoking
	 * `FacetExpressionTriggerFactory` for each reference schema on the specified entity type.
	 * This method is a **pure function** — it does not access the catalog or any external state.
	 *
	 * **Immutability principle:** the rebuild constructs a **new** registry instance. The original instance remains
	 * untouched and continues serving concurrent readers until they switch to the new instance.
	 *
	 * @param entityType  the owner entity type whose triggers are being rebuilt
	 * @param newTriggers the complete set of triggers built from the entity type's current reference schemas
	 *                    (may be empty to remove all triggers for that entity type)
	 * @return a new registry instance with the rebuilt trigger set
	 */
	@Nonnull
	CatalogExpressionTriggerRegistry rebuildForEntityType(
		@Nonnull String entityType,
		@Nonnull List<ExpressionIndexTrigger> newTriggers
	);

}
