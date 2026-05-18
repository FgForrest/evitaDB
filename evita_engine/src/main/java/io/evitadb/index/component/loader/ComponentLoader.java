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

package io.evitadb.index.component.loader;

import javax.annotation.Nonnull;

/**
 * Sealed parallel to {@link io.evitadb.index.component.IndexComponent}: where `IndexComponent`
 * owns the **write** side of a single sub-index family (flush, dirty reset, transactional layer
 * teardown), `ComponentLoader` owns the **read** side — rehydrating that family from persistent
 * storage during catalog boot / restart.
 *
 * The split keeps the read protocol decoupled from the live in-memory structures: the loader
 * lives only as long as one `readEntityIndex` call, fetches raw storage parts via
 * {@link LoadContext#storagePartService()}, and emits one matching {@link LoadedComponentBundle}.
 * The owning {@link IndexReloadPlan} then collects every bundle into a `Class`-keyed map and
 * hands it to the subclass finalizer which calls the matching `EntityIndex` constructor.
 *
 * Reload is **not** on the hot query path, so allocations and lookups here can favour clarity
 * over micro-optimisation.
 */
public sealed interface ComponentLoader
	permits AttributeIndexLoader,
	PriceSuperIndexLoader,
	PriceRefIndexLoader,
	HierarchyIndexLoader,
	FacetIndexLoader,
	AttributeCardinalityIndexMapLoader,
	HistogramIndexMapLoader,
	ReferenceTypeCardinalityLoader,
	GroupCardinalityLoader {

	/**
	 * Reloads this loader's sub-index family from the storage parts referenced by
	 * `context.entityIndexStoragePart()` and returns the populated bundle.
	 *
	 * Implementations must:
	 *
	 * - Throw via `Assert.isPremiseValid` when a required storage part is missing — silent
	 *   skipping would surface later as data loss.
	 * - Return a `LoadedComponentBundle` whose concrete type is locked to the sealed shape
	 *   declared by this loader; the finalizer relies on the runtime class for typed lookup.
	 *
	 * @param context immutable per-call bundle of fetch dependencies
	 * @return the populated bundle for this loader's sub-index family
	 */
	@Nonnull
	LoadedComponentBundle load(@Nonnull LoadContext context);

}
