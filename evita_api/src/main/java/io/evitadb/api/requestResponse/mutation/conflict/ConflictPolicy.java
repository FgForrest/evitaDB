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
 * Summary of scopes:
 * - {@link #CATALOG} — all writes to the same catalog conflict
 * - {@link #COLLECTION} — writes within the same collection conflict, different collections can proceed
 * - {@link #ENTITY} — writes to the same entity conflict, different entities can proceed
 * - {@link #ENTITY_ATTRIBUTE} — only writes touching the same entity attribute conflict
 * - {@link #REFERENCE} — only writes touching the same entity reference conflict
 * - {@link #REFERENCE_ATTRIBUTE} — only writes touching the same attribute of the same reference conflict
 * - {@link #ASSOCIATED_DATA} — only writes touching the same associated data of the same entity conflict
 * - {@link #PRICE} — only writes touching the same price of the same entity conflict
 * - {@link #HIERARCHY} — only hierarchy updates that affect the same entity/position conflict
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
	 * entity will generate conflicts.
	 */
	ENTITY,

	/**
	 * This policy generates conflict keys that are scoped to specific attributes of entities. Concurrent mutations
	 * targeting the same attribute of the same entity will generate conflicts, while mutations targeting different
	 * attributes, parts of the same entity or different entities can be processed concurrently.
	 *
	 * This policy doesn't cover attributes of references, see {@link #REFERENCE_ATTRIBUTE} for that.
	 */
	ENTITY_ATTRIBUTE,

	/**
	 * This policy generates conflict keys that are scoped to specific references of entities. Concurrent mutations
	 * targeting the same reference of the same entity will generate conflicts, while mutations targeting different
	 * references, parts of the same entity or different entities can be processed concurrently.
	 */
	REFERENCE,

	/**
	 * This policy generates conflict keys that are scoped to specific attributes of references within entities. Concurrent
	 * mutations targeting the same attribute of the same reference of the same entity will generate conflicts, while mutations
	 * targeting different attributes, references, parts of the same entity or different entities can be processed concurrently.
	 */
	REFERENCE_ATTRIBUTE,

	/**
	 * This policy generates conflict keys that are scoped to associated data of entities. Concurrent mutations
	 * targeting the same associated data of the same entity will generate conflicts, while mutations targeting different
	 * associated data, parts of the same entity or different entities can be processed concurrently.
	 */
	ASSOCIATED_DATA,

	/**
	 * This policy generates conflict keys that are scoped to prices of entities. Concurrent mutations
	 * targeting the same price of the same entity will generate conflicts, while mutations targeting different
	 * prices, parts of the same entity or different entities can be processed concurrently.
	 */
	PRICE,

	/**
	 * This policy generates conflict keys that are scoped to the hierarchy of entities. Concurrent mutations
	 * targeting the same position in the hierarchy of the same entity will generate conflicts, while mutations targeting different
	 * positions, parts of the same entity or different entities can be processed concurrently.
	 */
	HIERARCHY

}
