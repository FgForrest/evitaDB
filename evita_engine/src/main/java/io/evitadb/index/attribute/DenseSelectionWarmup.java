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

package io.evitadb.index.attribute;

/**
 * Policy governing how a tree-backed {@link SortedRecordsSupplier} handles a DENSE selection while its arrays are
 * still cold (not yet materialized). Orthogonal to {@link SortDirectionBacking}; sparse selections never consult it
 * (they always resolve by tree probe, materializing nothing).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum DenseSelectionWarmup {

	/**
	 * Long-lived snapshot: warm (lazily materialize) the arrays and take the tight array merge-walk, so the
	 * `O(N log N)` materialization is paid once and reused by every later dense query against that snapshot.
	 */
	WARM_AND_REUSE,

	/**
	 * Short-lived per-transaction layer: run the cold `O(N)` dense tree walk, materializing nothing — a throwaway
	 * layer would rarely reuse warmed arrays, so a walk that allocates nothing is the better trade.
	 */
	COLD_WALK;

	/**
	 * Whether a dense selection should warm and reuse the materialized arrays instead of taking the cold tree walk.
	 *
	 * @return `true` only for {@link #WARM_AND_REUSE}
	 */
	public boolean warmsArrays() {
		return this == WARM_AND_REUSE;
	}

}
