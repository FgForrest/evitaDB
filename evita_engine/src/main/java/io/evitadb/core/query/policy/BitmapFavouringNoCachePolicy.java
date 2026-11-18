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

package io.evitadb.core.query.policy;

import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EntityFetchRequire;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Denies prefetch so query planning uses bitmap indexes. The planning context selects it for the whole query when
 * the request enables {@link DebugMode#VERIFY_POSSIBLE_CACHING_TREES} or {@link DebugMode#PREFER_INDEX_SCAN}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class BitmapFavouringNoCachePolicy implements PlanningPolicy {
	public static final BitmapFavouringNoCachePolicy INSTANCE = new BitmapFavouringNoCachePolicy();

	@Nonnull
	@Override
	public PrefetchPolicy getPrefetchPolicy() {
		return PrefetchPolicy.DENY;
	}

	@Override
	public long estimatePrefetchCost(
		int prefetchedEntityCount,
		@Nonnull EntityFetchRequire requirements,
		boolean preferredByUser
	) {
		// will not be taken into account, since prefetch is forbidden
		return Long.MAX_VALUE;
	}

}
