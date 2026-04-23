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

import io.evitadb.api.query.RequireConstraint;

/**
 * Marker interface for require constraints that partition the primary entity result set into a *slice* for delivery
 * to the client. At most one chunking constraint may be present in a single `require` clause; the engine uses it to
 * determine how many entities to return and from which offset.
 *
 * Concrete implementations:
 * - {@link Page} — page-based pagination (page number + page size); wraps its result in a `PaginatedList` data chunk
 *   with total-page-count metadata. When the requested page exceeds the available pages, the engine automatically
 *   returns the first page to avoid unnecessary round-trips.
 * - {@link Strip} — offset/limit pagination (offset + limit); wraps its result in a `StripList` data chunk, suitable
 *   for infinite-scroll or cursor-style UIs.
 *
 * Chunking constraints also appear inside {@link ReferenceContent} to paginate the references returned per entity,
 * not just the top-level entity list.
 *
 * If no chunking constraint is present, the engine defaults to `page(1, 20)`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface ChunkingRequireConstraint extends RequireConstraint {
}
