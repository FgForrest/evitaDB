/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.query.algebra.prefetch;


import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * PrefetchOrder is a container with information that are used to prefetch entities for particular query context.
 * All the entities will be fetched from underlying storage upfront of the query execution and will participate in
 * query execution.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class PrefetchOrder {
	@Getter private final boolean prefetchedEntitiesSuitableForFiltering;
	@Getter private final Bitmap entitiesToPrefetch;
	@Getter private final EntityFetchRequire entityRequirements;

}
