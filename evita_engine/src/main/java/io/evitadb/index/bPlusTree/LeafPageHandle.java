/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.index.bPlusTree;

import javax.annotation.Nonnull;

/**
 * A live, write-path handle over a single leaf page: it exposes the leaf's logical persistence page sequence (carried
 * across commits by the leaf node), lets the emitter assign a freshly allocated page to a not-yet-paged (split-born or
 * fresh) leaf, and exposes the leaf's values (in ascending key order) the emitter materializes the page contents from.
 * The handles are returned in ascending key order — the very order the persisted leaf-page list records — by
 * {@link AbstractTransactionalBPlusTree#leafPageHandles()}.
 *
 * It is the value-agnostic emission view shared by every paging variant of the B+ tree family, so the granular
 * write path in the index consumers ({@link io.evitadb.index.range.RangeIndex} today) is written once against this
 * contract regardless of the concrete tree. The page-bookkeeping half (page sequence, dirty flag, page stamp) lives on
 * the value-agnostic {@link PagedLeafHandle} super-interface this extends; this interface adds the value-access methods.
 *
 * @param <T> the leaf payload (value) type
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface LeafPageHandle<T> extends PagedLeafHandle {

	/**
	 * Returns the number of values in this leaf page.
	 *
	 * @return the value count
	 */
	int size();

	/**
	 * Returns the value at the given index within this leaf page (`0 <= index < size()`), in ascending key order.
	 *
	 * @param index the value index
	 * @return the value
	 */
	@Nonnull
	T valueAt(int index);

}
