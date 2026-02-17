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

package io.evitadb.api.query.require;

import io.evitadb.dataType.SupportedEnum;

/**
 * Enumerates the internal debug modes that can be activated via the {@link Debug} require constraint. These modes
 * are exclusively for use in integration tests and internal verification — they must never be used in production
 * queries because they introduce significant additional computation.
 *
 * Each value targets a specific aspect of the query engine's correctness:
 *
 * - `VERIFY_ALTERNATIVE_INDEX_RESULTS` — exercises index redundancy by computing the query result through every
 *   available alternative index (e.g. both a sorted and an inverted index for the same attribute) and asserting
 *   that all paths produce bit-identical result sets. Detects inconsistencies between index implementations.
 * - `VERIFY_POSSIBLE_CACHING_TREES` — exercises the formula caching layer by materialising every possible variant
 *   of the query formula tree where sub-trees can be swapped with cached versions, and verifying that all
 *   variants produce identical results. Detects caching bugs.
 * - `PREFER_PREFETCHING` — overrides the cost-based strategy selector and unconditionally uses the prefetch
 *   strategy whenever the query's filter constraints make prefetching feasible. Used to exercise the prefetch
 *   code path that would otherwise be skipped for large datasets where the index scan is cheaper.
 */
@SupportedEnum
public enum DebugMode {

	/**
	 * This option triggers verification of computation results for all possible indexes. This mode allows us to verify
	 * that all alternative indexes produce the same results.
	 *
	 * BEWARE: triggering this debug mode will slow down the query response because all alternative computation paths
	 * needs to be computed along the way.
	 */
	VERIFY_ALTERNATIVE_INDEX_RESULTS,
	/**
	 * This option trigger creation and verification of all possible variants of computational tree where the results
	 * are exchanged with cached variants. This mode allows us to verify that all variants of cacheable tree parts
	 * produce the same results.
	 */
	VERIFY_POSSIBLE_CACHING_TREES,
	/**
	 * This option always selects prefetching strategy if it's possible to use it (depends on the query filter by
	 * constraints).
	 */
	PREFER_PREFETCHING

}
