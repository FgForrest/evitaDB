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

package io.evitadb.index.page;

import io.evitadb.index.bPlusTree.PagedLeafHandle;

import javax.annotation.Nonnull;

/**
 * Materializes one changed leaf's persisted payload during the granular write path. Invoked by
 * {@link PageStreamRegistry#collectChangedPages(int, java.util.List, PageBuilder)} once per leaf that must be
 * (re)written, after the leaf's page sequence has been resolved (allocated and stamped for a fresh leaf). The builder
 * reads the leaf's values off the concrete handle `H` (a bucket cursor, or positional `valueAt`) and returns the
 * caller's per-leaf shape `P`. A primitive-`int` page-sequence parameter keeps the flush path boxing-free.
 *
 * @param <H> the concrete leaf-handle type the caller's tree exposes (extends {@link PagedLeafHandle})
 * @param <P> the per-leaf payload type the caller materializes
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@FunctionalInterface
public interface PageBuilder<H extends PagedLeafHandle, P> {

	/**
	 * Builds the persisted payload for one changed leaf.
	 *
	 * @param pageSequence the leaf's resolved page sequence within the stream (>= 0)
	 * @param handle       the live handle over the leaf, positioned for value access
	 * @return the per-leaf payload to collect into the emission
	 */
	@Nonnull
	P build(int pageSequence, @Nonnull H handle);

}
