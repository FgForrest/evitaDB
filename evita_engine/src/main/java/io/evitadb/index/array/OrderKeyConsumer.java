/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.index.array;

/**
 * Primitive, no-boxing callback used by the position tree ({@link UnorderedLookupTree}) to report each
 * `recordId → orderKey` assignment that a mutation produces, so that the owning composite can keep its value index
 * (the `recordId → orderKey` map) coherent with the container layout (INV-COUPLE).
 *
 * The semantics are **set / overwrite**: after the call the value index must map `recordId` to `orderKey`,
 * regardless of whether the record was newly inserted or merely re-stamped because its container split, stole or
 * merged.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@FunctionalInterface
public interface OrderKeyConsumer {

	/**
	 * Records that `recordId` now lives in the container identified by `orderKey`.
	 *
	 * @param recordId the affected record id
	 * @param orderKey the order-key of the container that now holds it
	 */
	void accept(int recordId, long orderKey);

}
