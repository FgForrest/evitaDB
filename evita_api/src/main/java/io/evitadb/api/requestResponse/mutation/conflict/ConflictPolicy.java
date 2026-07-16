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

package io.evitadb.api.requestResponse.mutation.conflict;


/**
 * Describes the granularity at which write conflicts are detected and serialized.
 *
 * EvitaDB derives a conflict key for every incoming write mutation. The scope of that key is
 * controlled by this policy: the finer the scope, the more mutations can be processed concurrently
 * without blocking; the coarser the scope, the fewer conflicts are possible, but at the cost of
 * lower concurrency.
 *
 * When no more specific policy is provided by the mutation or the surrounding context, conflicts
 * are scoped to the entire catalog (see {@link #CATALOG}). Choose the most specific policy that
 * correctly reflects what the mutation touches to maximize throughput while preserving correctness.
 *
 * This enum captures only the coarse, mutually exclusive scope axis of the conflict model. The
 * sub-entity refinements are modelled by the separate {@link GranularConflictPolicy} enum, and a
 * coarse policy together with a set of granular refinements is carried by {@link ConflictResolution}.
 *
 * Summary of coarse scopes:
 * - {@link #NONE} — no conflict detection at all (last-writer-wins)
 * - {@link #CATALOG} — all writes to the same catalog conflict
 * - {@link #COLLECTION} — writes within the same collection conflict, different collections can proceed
 * - {@link #ENTITY} — writes to the same entity conflict, different entities can proceed
 *
 * Components using this policy include the conflict key generator
 * ({@link io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext}) and the
 * mutation layer ({@link io.evitadb.api.requestResponse.mutation.Mutation}). It is also consumed by
 * transaction processing to determine which writes can proceed concurrently.
 *
 * Thread-safety: the enum is immutable and safe to share.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum ConflictPolicy {

	/**
	 * This policy disables conflict detection entirely: no conflict keys are generated for the affected
	 * writes, so concurrent transactions never conflict on this basis and the last commit within an
	 * overlapping window silently wins (last-writer-wins). It makes the "no consistency" mode an explicit,
	 * nameable choice; historically the same effect was expressed as an empty policy set. Commutative
	 * mutations that require a post-application check (e.g. range-constrained deltas) may still emit keys
	 * even under this policy — see {@link ConflictResolution}.
	 */
	NONE,

	/**
	 * This policy generates conflict keys that are scoped to the entire catalog. If no more granular policy is
	 * specified, it means that each write to the catalog will be treated as potentially conflicting with any other
	 * write to the same catalog, which will effectively mean, that there will be no concurrent writes to the same
	 * catalog allowed.
	 */
	CATALOG,

	/**
	 * This policy generates conflict keys that are scoped to collections within the catalog. Mutations targeting
	 * different collections can be processed concurrently, while concurrent mutations targeting the same collection
	 * will generate conflicts.
	 */
	COLLECTION,

	/**
	 * This policy generates conflict keys that are scoped to individual entities within a collection. Mutations
	 * targeting different entities can be processed concurrently, while concurrent mutations targeting the same
	 * entity will generate conflicts. Sub-entity refinements within this scope are expressed via
	 * {@link GranularConflictPolicy} carried by {@link ConflictResolution}.
	 */
	ENTITY

}
